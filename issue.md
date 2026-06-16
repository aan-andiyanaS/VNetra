# Perbaikan Lanjutan: Dual-Path Architecture (TCP & UDP) dan Application-Level Flow Control (Anti-Bufferbloat)

## Deskripsi Masalah Sebelumnya
Setelah kita mengimplementasikan fitur pemantauan *ping* secara *real-time* (Issue #28), kita menemukan bahwa lalu lintas data gabungan antara *video stream* (resolusi tinggi) dan data sensor IMU & ToF (frekuensi tinggi) melalui satu jalur *WebSocket* menyebabkan *bottleneck*. Ketergantungan *Camera Task* terhadap `i2c_mutex` (yang digunakan sensor) menyebabkan *frame rate* menjadi lambat, tidak stabil, serta menyumbat aliran antrean data (lag). 

## Solusi Utama yang Diimplementasikan

### 1. Dual-Path Architecture: Memisahkan Jalur Data Sensor dan Video
Untuk mengatasi *bottleneck* I2C dan TCP:
- **Pemisahan Protokol**: Data Video (kamera) tetap dipertahankan pada **TCP (WebSocket)** karena membutuhkan transmisi tanpa cacat (*lossless*). Sebaliknya, aliran data sensor yang berfrekuensi tinggi (IMU & ToF) dipindahkan ke protokol **UDP**, yang *connectionless* dan super cepat.
- **Kemandirian Task**: Karena sensor memiliki jalurnya sendiri, *Camera Task* tidak lagi harus tertahan oleh `i2c_mutex`. Hasilnya, kamera dapat memproduksi dan mentransmisikan *frame* hingga 50 FPS (tergantung limitasi *delay* `TARGET_FRAME_MS`).
- **Android App Fix**: Di sisi aplikasi Android (`CameraStreamService.kt`), *DatagramSocket* digunakan untuk menangkap data UDP. Sebuah *bug* kritis berupa ukuran *buffer* paket yang menyusut akibat `DatagramPacket` ditangani secara tuntas dengan mereset panjang paket (`packet.length = buffer.size`) di setiap perulangan penerimaan.

### 2. Application-Level Flow Control (ACK:CAM) & Frame Dropping Cerdas
Dampak tak terduga dari pemisahan jalur adalah *Camera Task* menjadi terlalu cepat (memproduksi frame tanpa batas). Ini membuat antrean *AsyncTCP* pada ESP32 seketika penuh dengan 32 pesan (maksimum buffer), menghasilkan **Bufferbloat** yang memicu *ping* membengkak menjadi > 3000ms dan data UDP lenyap karena *bandwidth* sinyal Wi-Fi dimonopoli oleh proses antrean ulang TCP.

Untuk memberantas *Bufferbloat*, mekanisme kendali aliran ketat diterapkan:
- **Feedback (ACK:CAM) dari Android**: Setiap kali aplikasi Android berhasil mengurai (*parse*) *frame* JPEG dan menampilkannya, aplikasi akan merespon dengan mengirim *text frame* `"ACK:CAM"` kembali ke ESP32.
- **Limitasi Antrean (Max 4 Frames)**: ESP32 akan menghitung jumlah *frame* yang sudah meluncur tetapi belum di-ACK (`unacked_frames`). Jika `unacked_frames >= 4` (sekitar ~400ms in-flight buffer), kamera akan melakukan *Frame Dropping*.
- **Silent & Non-Blocking Frame Drop**: Ketika limit antrean tercapai, hasil tangkapan gambar dibuang tanpa membekukan CPU. Ini menjaga FPS agar selalu seirama dengan kemampuan aktual jaringan tanpa menjebol *buffer*.
- **Bug Fix 3 Detik Awal (Event Connect)**: Inisialisasi awal variabel penghitung `last_ack_time = millis();` dipindahkan secara akurat ke *event* `WS_EVT_CONNECT`. Hal ini mencegah *bug* di mana antrean membeludak ke batas 32-frame di 3 detik pertama koneksi sebelum Flow Control dapat bereaksi.
- **Graceful Fallback**: Sebagai pengaman jika HP Android putus koneksi sementara waktu, jaringan sangat terganggu, atau pengguna belum meng-*update* aplikasi Android, terdapat batas tunggu toleransi 3 detik. Jika 3 detik berlalu tanpa ACK, antrean akan di-reset (kembali ke *behaviour* bawaan), sehingga menghindari kamera terhenti secara permanen.

## Kesimpulan
Perubahan infrastruktur jaringan secara komprehensif ini secara signifikan meringankan *bandwidth* protokol TCP, menekan latensi (*ping*) kembali di bawah ~400ms, mengembalikan fungsionalitas paket UDP sensor yang sempat hilang, dan menghasilkan pengalaman rekaman video langsung (streaming) yang bebas "patah-patah".
