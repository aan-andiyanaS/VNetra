# Laporan Analisis Kamera VNetra: Penyebab Ping Tinggi & Patah-patah

Berdasarkan analisis mendalam terhadap struktur *firmware* ESP32 (`firmware-vnetra.ino`), terdapat beberapa masalah arsitektur di dalam pengiriman WebSocket yang menyebabkan kamera menjadi patah-patah (stuttering) dan seolah-olah memiliki ping yang tinggi.

Berikut adalah hasil temuan utama yang menjadi akar permasalahan:

## 1. [Kritis] Tabrakan Pembatas FPS (Double FPS Limiter)
* **Lokasi**: Fungsi `loop()` (Baris ~1597) dan `captureAndSend()` (Baris ~475)
* **Analisis**: Terdapat dua pembatas FPS yang berjalan bersamaan tetapi menggunakan referensi timer yang berbeda (`esp_timer_get_time()` dan `millis()`). Di fungsi `loop()`, kamera dipanggil setiap 100ms. Namun di dalam `captureAndSend()`, ada pengecekan ulang: jika jarak antar frame kurang dari 100ms, frame akan langsung dibuang (`return`).
* **Dampak**: Karena tidak tersinkronisasi secara sempurna, sedikit saja variasi waktu eksekusi (jitter sub-milidetik) akan membuat pembatas di dalam `captureAndSend()` aktif secara sepihak. Hal ini membuang frame secara percuma dan menyebabkan FPS anjlok secara instan. Inilah penyebab utama video terasa patah-patah secara acak.

## 2. [Kritis] Mekanisme Flow Control ACK yang Terlalu Ketat
* **Lokasi**: Fungsi `captureAndSend()` (Baris ~465) pada logika `unacked_frames`
* **Analisis**: Sistem menggunakan mekanisme konfirmasi lapisan aplikasi manual dari Android (`ACK:CAM`). Jika terdapat 4 frame yang sedang berjalan (*in-flight*) dan belum dibalas, ESP32 akan langsung membuang (drop) tangkapan kamera berikutnya.
* **Dampak**: Padahal, lapisan koneksi TCP (WebSocket) secara internal (OS) masih memiliki *buffer* untuk menampung paket. Jika aplikasi Android mengalami jeda sekejap karena beban pemrosesan YOLO atau fluktuasi sinyal Wi-Fi minor, ESP32 akan langsung "panik" dan menghentikan perekaman. Ini mengakibatkan lonjakan penundaan (delay) visual yang parah. Seharusnya sistem memercayakan TCP backpressure dari fungsi bawaan `client.queueIsFull()`.

## 3. [Sedang] Beban Semaphor & Queue WebSocket yang Menganggur
* **Lokasi**: `loop()` dan variabel `ws_mutex` serta `wsQueue`
* **Analisis**: Awalnya `ws_mutex` dan `wsQueue` didesain karena mengasumsikan sensor IMU dan ToF mengirim data juga melewati WebSocket. Faktanya, berdasarkan pemeriksaan kode terbaru, **IMU dan ToF sudah diubah menggunakan transmisi UDP murni** (melalui `udpSensor.sendTo(...)`).
* **Dampak**: Eksekusi pengecekan antrean kosong (`xQueueReceive`) terus-menerus dan penahanan/penguncian lajur kamera menggunakan `xSemaphoreTake(ws_mutex, 10ms)` hanyalah beban statis yang merampas siklus CPU ESP32 secara percuma dan berpotensi menyumbat aliran utama jika terjadi latensi mutex internal.

---

## Solusi & Rencana Tindakan (Action Plan) yang Direkomendasikan
Untuk mencapai pengiriman video yang jauh lebih responsif, stabil (anti-stutter), dan ping yang optimal:

1. **Sinkronisasi FPS**: Hapus blok pengecekan `millis() - last_frame_time` di dalam `captureAndSend()` dan biarkan `loop()` utama yang mengatur ritme waktu mutlak menggunakan `esp_timer_get_time()`.
2. **Pemanfaatan TCP Native (Non-blocking)**: Hapus sistem hitung mundur `unacked_frames` yang rentan terhadap jeda. Ganti dengan memanfaatkan pengecekan bawaan `if (!client.queueIsFull())` sebelum mengirimkan data *binary* ke WebSocket. Ini akan memastikan frame mengalir sehalus mungkin secepat jaringan dapat membawanya.
3. **Pembersihan Beban CPU (Cleanup)**: Hapus inisialisasi `ws_mutex` dan blok pembacaan `wsQueue`. Karena semua pengiriman WebSocket kini 100% tersentralisasi secara sinkron dari Core 0 (di dalam loop kamera), ancaman korupsi *memory stack* antar-thread (Data Race) sudah tidak ada lagi.
