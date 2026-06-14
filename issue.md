# Penanganan Isu Lag dan Delay pada Sensor ToF & Kamera (Fix FPS Drop)

## 📌 Deskripsi Masalah

Setelah melakukan peningkatan akurasi pada sensor VL53L5CX (dengan meningkatkan `integration_time` dan melakukan pemulusan data UI), performa aplikasi Android turun drastis. Indikator masalahnya adalah:
1. **FPS Kamera drop** dari stabil di 9-10 FPS menjadi hanya 1.5 FPS atau bahkan diam/stuck.
2. **Sistem Lag & Disconnect**: Data yang diterima aplikasi sering putus nyambung (disconnect dari ESP32) dan frame lambat ter-update.
3. **Delay Sensor Tinggi**: Jarak ToF menjadi lambat bereaksi, sehingga sangat berbahaya jika digunakan oleh tunanetra untuk navigasi *real-time*.
4. **Pembacaan "Nyangkut"**: Cell terluar sering tertahan pada pembacaan objek dekat, dan tidak merespon saat objek dijauhkan.

---

## 🛠️ Langkah Perbaikan & Perubahan Kode

Untuk menyelesaikan masalah ini, dilakukan optimasi dua arah: **Sisi Firmware (ESP32)** dan **Sisi Aplikasi (Android)**.

### 1. Perbaikan Sisi Firmware (`firmware-vnetra.ino`)

**Tujuan:** Mengembalikan frekuensi I2C dan memfilter pembacaan *invalid* (agar Android tidak lag memproses data kotor).

- **[MODIFIED] Mengembalikan Integration Time (Waktu Integrasi)**
  - *Sebelumnya:* Sempat dicoba nilai 80ms (8x8) dan 100ms (4x4) untuk menambah akurasi.
  - *Perubahan:* Dikembalikan ke nilai stabil sebelumnya, yaitu **30ms untuk 8x8** dan **50ms untuk 4x4**. 
  - *Alasan:* Integration time yang terlalu lama memonopoli `i2c_mutex`, menyebabkan thread IMU dan kamera (serta *WebSocket loop*) *starving* (kelaparan CPU/bandwidth) yang membuat ESP32 gagal mengirim stream.
  
- **[MODIFIED] Menambahkan Filter Sentinel (`-1`) pada `TOF_Task`**
  - *Sebelumnya:* Jika `target_status` tidak valid (bukan 5 atau 6), firmware tetap mengirimkan jarak terakhir atau `0`.
  - *Perubahan:* Firmware kini mengirimkan jarak **`-1` (sebagai penanda sentinel/invalid)** apabila `target_status` menunjukkan data yang tidak reliabel (terutama pada sudut luar cell grid).
  - *Alasan:* Ini memperbaiki masalah "nilai jarak yang nyangkut/stuck" di bagian pinggir layar, sekaligus memberikan *flag* pada Android untuk tidak menggambar warna/angka.

### 2. Perbaikan Sisi Layanan Android (`CameraStreamService.kt`)

**Tujuan:** Memperbaiki sistem *keep-alive* WebSocket yang menyebabkan aplikasi berulang kali terputus (disconnect) dan menghasilkan 0 FPS.

- **[MODIFIED] Konfigurasi `OkHttpClient` (Menghapus `pingInterval`)**
  - *Sebelumnya:* Memiliki `.pingInterval(5, TimeUnit.SECONDS)`.
  - *Perubahan:* `.pingInterval` **dihapus sama sekali**.
  - *Alasan (Root Cause Terbesar):* OkHttp mengirim paket `Ping` setiap 5 detik. Namun, server `ESPAsyncWebServer` yang sibuk memproses JPEG kamera sering gagal/terlambat membalas paket `Pong`. Karena `Pong` telat, OkHttp menganggap koneksi mati dan secara paksa **menutup koneksi tiap ~10 detik**. Ini menyebabkan aplikasi menghabiskan waktu berulang kali untuk *reconnect*, membuat FPS anjlok. 
  - *Solusi Pengganti:* Layanan kini sepenuhnya mengandalkan paket *Heartbeat* (yang memang sudah dikirim ESP32 setiap 10 detik) dan *Watchdog Timeout* (12 detik tanpa data) bawaan aplikasi.

### 3. Perbaikan Sisi UI Android (`CameraStreamActivity.kt`)

**Tujuan:** Menghilangkan *Garbage Collection (GC) Pressure* yang sangat masif, yang menyebabkan HP Android tersendat (stuttering) akibat alokasi memori objek secara berulang di dalam *loop* frame 10Hz.

- **[MODIFIED] Pra-komputasi Objek Warna (`colorInvalidCell`)**
  - *Sebelumnya:* `android.graphics.Color.parseColor("#60000000")` dieksekusi **hingga 640 kali per detik** (di setiap cell invalid (misal 64 cell) * 10 FPS). String parsing sangat mahal.
  - *Perubahan:* Dibuat variabel statis/field class `private val colorInvalidCell = android.graphics.Color.argb(96, 0, 0, 0)`.
  - *Alasan:* Menghemat pemrosesan CPU UI. Jika nilai ToF `-1` atau `<= 0`, aplikasi hanya memanggil referensi konstanta, bukan menjalankan metode konversi teks.

- **[MODIFIED] Pra-alokasi *Float Array* (`hsvTemp`)**
  - *Sebelumnya:* Pemanggilan `floatArrayOf(hue, 1f, 1f)` membuat objek array baru untuk setiap cell grid yang valid pada setiap frame.
  - *Perubahan:* Dibuat `private val hsvTemp = floatArrayOf(0f, 1f, 1f)` di level class, yang digunakan secara *reuse* (dipakai berulang) di fungsi `getColorForDistance`.
  - *Alasan:* HP Android sebelumnya harus secara konstan menghapus ratusan `FloatArray` yang sudah tak terpakai setiap detiknya (*Garbage Collection pause*). GC pause inilah yang menahan proses render gambar kamera di layar.

---

## ✅ Hasil Akhir (Result)

Berdasarkan *screenshot* yang diambil via ADB setelah seluruh optimasi diterapkan:
- **FPS Kamera:** Kembali naik dan stabil di **10.8 FPS** (berhasil melewati target 9-10 FPS).
- **Koneksi:** Tidak ada lagi pesan "offline" berkala karena siklus *reconnect* palsu OkHttp telah diperbaiki.
- **Tampilan Grid (ToF Heatmap):** Cell terluar kini dapat merender simbol garis strip `—` dengan warna transparan jika target menjauh / keluar jangkuan (tidak *nyangkut* lagi), sementara warna gradasi dari merah ke hijau pada cell bagian tengah tetap bekerja normal secara real-time.
- **Responsivitas:** Karena tidak ada *GC pause* di UI Android dan tidak ada interupsi *mutex I2C* pada ESP32, delay penerimaan sensor sangat rendah dan siap dipakai berjalan oleh user.
