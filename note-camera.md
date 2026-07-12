# Dokumentasi Rekayasa: Optimalisasi Latensi & Stabilitas Kamera (VNetra)

Dokumen ini merekam secara rinci hasil investigasi dan tindakan rekayasa perangkat lunak (*software engineering*) yang dilakukan untuk mengatasi masalah tingginya latensi (ping) dan video yang patah-patah (*stuttering*) pada sistem kamera VNetra.

## Konteks Masalah
Sebelumnya, sistem VNetra sering kali menunjukkan gejala macet pada transmisi visual dari kamera ESP32-S3 ke aplikasi Android. Hasil penelusuran arsitektural menemukan bahwa kode *firmware* mengidap sifat yang **terlalu defensif (paranoid)**. Upaya berlebihan untuk membatasi lalu lintas data secara manual pada level aplikasi (*Application Layer*) justru mencekik kinerja lapisan transpor (*Transport Layer* TCP/LwIP).

## Detail Analisis & Solusi (Perbandingan Kode)

### 1. Penghapusan *Double FPS Limiter* (Efek *Beat Frequency*)
**Masalah**: Kamera sebelumnya dibatasi kecepatan bingkainya (*framerate*) oleh dua fungsi yang berjalan bersamaan tetapi menggunakan referensi jam (*timer*) yang berbeda:
- `loop()` menggunakan timer mikrodetik presisi tinggi: `esp_timer_get_time()`.
- `captureAndSend()` menggunakan timer bawaan OS: `millis()`.

Ketidaksinkronan ini memicu fenomena fisika *Beat Frequency*. Misalnya, saat `loop()` membuka gerbang pada milidetik ke-100, nilai `millis()` di `captureAndSend()` mungkin masih berada pada milidetik ke-99. Akibatnya, bingkai (frame) tersebut langsung ditolak (*drop*), memicu *micro-stuttering*.

**Solusi**: Menyerahkan ritme transmisi sepenuhnya pada pengatur waktu presisi tinggi di `loop()`.

```cpp
// ❌ KODE LAMA (Defensif, membuang frame secara acak)
void captureAndSend() {
    // FPS Limiter
    if (millis() - last_frame_time < TARGET_FRAME_MS) {
        return; // Menolak frame jika perhitungan millis() meleset sedikit
    }
    last_frame_time = millis();
    // ...
}

// ✅ KODE BARU (Optimal)
void captureAndSend() {
    // Pengecekan waktu millis() dihapus sepenuhnya.
    // Ritme transmisi dikontrol mutlak oleh loop()
    // ...
}
```

### 2. Penghapusan *Manual Flow Control* (`unacked_frames`) & Pemangkasan `ACK:CAM`
**Masalah**: Logika lama berusaha membangun protokol *flow control* sendiri di atas WebSocket. ESP32 hanya akan mengirim 4 frame, lalu menunggu Android berteriak `"ACK:CAM"`. Jika Android terlambat membalas (misal karena CPU sedang memproses beban berat dari model deteksi *YOLO*), ESP32 akan panik dan menjatuhkan (*drop*) frame berikutnya. Padahal, LwIP (sistem operasi jaringan ESP32) memiliki memori penyangga (buffer) TCP.

**Solusi**: Menghancurkan variabel `unacked_frames` dari ESP32, lalu memanfaatkan kemampuan *native TCP backpressure* (kendali kemacetan bawaan dari TCP).
Selain itu, kita juga **menghapus instruksi pengiriman `ACK:CAM` dari Android**, menghemat pemrosesan daya, bandwidth, dan siklus CPU yang tadinya terbuang sia-sia.

```cpp
// ❌ KODE LAMA (ESP32)
if (unacked_frames >= 4) {
    Serial.println("[CAM] Buffer penuh (max 4). Frame didrop.");
    return; // Frame dibuang jika Android telat mengirim ACK
}
//...
unacked_frames++;
client.binary(g_wsBuf, total);


// ✅ KODE BARU (ESP32)
// Mengandalkan antrean LwIP TCP bawaan secara elegan
for (auto& client : ws.getClients()) {
    if (client.status() == WS_CONNECTED && !client.queueIsFull()) {
        client.binary(g_wsBuf, total);
    }
}
```

```kotlin
// ❌ KODE LAMA (Android - CameraStreamService.kt)
FRAME_TYPE_JPEG -> {
    _frameFlow.emit(payload)
    runCatching { activeWebSocket?.send("ACK:CAM") } // Beban mubazir!
}

// ✅ KODE BARU (Android)
FRAME_TYPE_JPEG -> {
    _frameFlow.emit(payload)
}
```

### 3. Pembebasan Siklus CPU dari Gembok *Mutex*
**Masalah**: Arsitektur awal menggunakan `ws_mutex` (sebuah kunci *Semaphore*) untuk melindungi fungsi kirim WebSocket dari risiko tabrakan antar-*thread* (misalnya dari Core 0 dan Core 1).
Namun, sejak data sensor IMU dan ToF bermigrasi sepenuhnya ke protokol **UDP**, WebSocket kini 100% dimonopoli oleh proses Kamera di Core 1.
Mempertahankan *mutex* berarti terus-menerus membuang siklus berharga CPU untuk mengunci/membuka akses terhadap fungsi yang sebenarnya sudah aman (tidak ada lagi yang akan mengaksesnya dari luar *thread*).

**Solusi**: Pembersihan deklarasi `ws_mutex`.

```cpp
// ❌ KODE LAMA (Membuang waktu OS)
if (xSemaphoreTake(ws_mutex, pdMS_TO_TICKS(10)) == pdTRUE) {
    client.binary(g_wsBuf, total);
    xSemaphoreGive(ws_mutex);
}

// ✅ KODE BARU (Tanpa gesekan internal OS)
client.binary(g_wsBuf, total);
```

## Kesimpulan Eksekusi
Pemusnahan lebih dari 40 baris kode "*defensif*" usang ini menghilangkan fenomena balapan waktu (*race condition*) pada timer dan memecahkan sumbatan komunikasi. Hasilnya:
1. Aliran video menjadi sangat mulus (*smooth*).
2. Lonjakan *ping* / penundaan visual turun drastis karena *buffer* TCP tidak lagi diganggu oleh logika *flow control* tingkat aplikasi.
3. Kinerja komputasi dan suhu mesin ESP32 dan perangkat Android lebih stabil berkat hilangnya beban memantau variabel proteksi yang sia-sia.
