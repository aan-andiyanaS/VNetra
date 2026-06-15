# Ringkasan Perubahan: Implementasi Tahap 1 (Multi-Sensor) & Dukungan Resolusi 4x4

## 📌 Deskripsi Fitur
Perubahan ini mengeksekusi implementasi **Tahap 1** dari roadmap integrasi multi-sensor (Kamera OV2640, ToF VL53L5CX, dan IMU MPU-6050) pada aplikasi Android VNetra, sesuai dengan spesifikasi matematika v9.4. Selain itu, pembaruan ini juga menyempurnakan keselarasan geometris (FoV) antara kamera dan ToF, serta mengintegrasikan mode resolusi 4x4 yang lebih ringan dan tahan noise.

## 🛠️ Perubahan yang Dilakukan

### 1. Peningkatan Payload IMU (Firmware & Service)
- Firmware ESP32-S3 di-update untuk mengirimkan **45 bytes payload** IMU, yang memuat 9 field (Pitch, Roll, Yaw, Gyro X/Y/Z, Accel, Timestamp, dan Status Konvergensi EKF).
- `CameraStreamService.kt` diperbarui dengan fungsi *parser* `parseImuPacket9Fields` untuk menangani struktur data EKF terbaru, dengan fallback aman ke parser lama jika payload tidak sesuai.

### 2. Keselarasan Geometris ToF dan Kamera (FormulaUtils)
- **Koreksi Konstanta Sistem**: Mengganti asumsi lebar ToF menjadi sesuai dengan Constraint UI (`width_percent="0.69"`). Ini menghasilkan `D_LEFT = 99px`, `W_TOF = 442px`, `R_COL = 55px`, dan zona lebar `W_Z = 147px`.
- Offset visual grid (`translationY`) disesuaikan menggunakan proporsi perbedaan Vertical Field of View (FoV) sensor: `(45° - 41°) / 2 / 45° ≈ 0.0444`. Bagian grid ToF yang "melampaui" jangkauan kamera kini tersembunyi dengan akurat.

### 3. Peringatan Objek Jarak Dekat (Formula E & H)
- `FormulaE.kt` diimplementasikan untuk melakukan estimasi jarak objek secara robust, memfilter *sentinel value* firmware, dan menerapkan ganti rugi (kompensasi) baris sensor berbasis orientasi *pitch* dari IMU (`centerRow = (N-1)/2`).
- `FormulaH.kt` diimplementasikan sebagai peringatan bahaya rintangan secara *one-shot*. Menggunakan kolom tengah grid sebagai proxy keberadaan halangan (karena deteksi YOLO belum aktif di tahap 1).
- Peringatan suara *Text-to-Speech* (TTS) Bahasa Indonesia diaktifkan, menyuarakan arah ("depan", "kiri depan") dan jarak dalam sentimeter.

### 4. Deteksi Anomali Permukaan / Terrain (TerrainDetector)
- Algoritma `TerrainDetector.kt` secara fungsional mengekstrak 6 fitur dari grid ToF (gradien vertikal, fluktuasi temporal, deviasi standar per kolom, rasio elevasi, *edge sharpness*, dan pola distribusi spasial).
- Pohon keputusan logika (*Decision Tree*) mendeteksi **STAIR_UP** (tangga naik), **STAIR_DOWN** (tangga turun), **HOLE** (lubang), dan **RAMP** (bidang miring/landai).
- Terhubung secara asynchronous tanpa memblokir antarmuka pengguna, serta me-routing *AlertLevel* menjadi ucapan TTS (contoh: "Awas! tangga turun, sekitar 15 sentimeter, depan!").

### 5. Kompatibilitas Penuh Mode 4x4
Semua algoritma telah direfaktor untuk mendukung alih mode mulus ke 4x4 (16 nilai) selain 8x8 standar:
- `FormulaUtils.kt`: Mendapat *helper* resolusi seperti `rCol(resolution)` dan perhitungan porsi kolom tengah dinamis (`{1, 2}` untuk 4x4, `{3, 4}` untuk 8x8).
- `FormulaE.kt`: Parameter perhitungan pitch disesuaikan sehingga kompensasi derajat-per-baris akurat pada FoV 11.25°/baris (resolusi 4x4).
- `TerrainDetector.kt`: Mendapat `process4x4()` yang mereduksi zona veritkal menjadi 2 area fungsional (High dan Low) sambil menurunkan batasan threshold *confidence* dari 0.80 ke 0.70 karena keunggulan *Signal-to-Noise Ratio* (SNR) yang lebih bersih pada profil sensor 4x4.

## ✅ Checklist Pengujian
- [x] Aplikasi tidak *crash* saat transisi antara mode ToF 8x8 dan 4x4.
- [x] Angka grid yang muncul sejajar akurat dengan lebar objek di *streaming* kamera.
- [x] Jika ada objek berjarak kurang dari 1 meter di tengah sensor, TTS mengucapkan peringatan bahasa Indonesia tanpa loop ganda (*one-shot sukses*).
- [x] Build Android APK berjalan tanpa masalah API usang / peringatan fatal (Tested and Built).
