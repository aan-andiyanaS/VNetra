# Plan Implementasi: Mitigasi WebSocket Jitter via Dynamic QoS & Frame Dropping

## 🎯 Tujuan
Mengatasi masalah *latency/ping spike* (jitter) pada WebSocket yang terjadi saat perangkat ESP32 bergerak cepat. Lonjakan *ping* ini disebabkan oleh ukuran *frame* JPEG yang membengkak karena *motion blur*, yang kemudian menyumbat antrean pengiriman TCP (*Head-of-Line Blocking*).

Solusi yang akan diimplementasikan merupakan kombinasi dari:
1. **Dynamic JPEG Quality berbasis IMU (Motion-Aware QoS)**
2. **Consistent Frame Dropping (FPS Limiter)**

Rencana ini ditujukan untuk diterapkan pada file utama *firmware* ESP32 (`firmware-vnetra.ino`).

---

## 🧠 Konsep Logika

1. **Dynamic Quality**: Saat pengguna diam, kamera bisa mengirimkan gambar dengan kualitas tinggi (angka kompresi kecil). Namun, saat IMU mendeteksi pergerakan (akselerasi/gyroscope tinggi), kualitas gambar harus *diturunkan* (angka kompresi diperbesar) secara otomatis. Ini menjaga ukuran *byte payload* tetap stabil di jaringan Wi-Fi.
2. **Frame Dropping**: Kita harus memastikan kamera tidak membanjiri antrean TCP meskipun *loop* utama berjalan sangat cepat. Kita akan menetapkan batas misal **10 FPS maksimal** (1 *frame* setiap 100 milidetik). Jika *frame* datang lebih cepat dari itu, buang (return/skip).

---

## 🛠️ Langkah Eksekusi (High-Level Plan)

### Tahap 1: Persiapan Variabel Global
Di bagian atas file `firmware-vnetra.ino` (di area deklarasi *TUNING* atau *GLOBAL STATE*), tambahkan beberapa variabel baru:
1. Variabel penanda waktu terakhir *frame* dikirim (misal: `unsigned long last_frame_time = 0;`).
2. Variabel durasi target antar *frame* (misal: `const unsigned long TARGET_FRAME_MS = 100;` untuk target 10 FPS).
3. Variabel *threshold* pergerakan IMU (misal: `const float MOTION_THRESHOLD = 1.5;`).
4. Variabel rentang kualitas JPEG (misal: `QUALITY_STILL = 12` untuk diam/tajam, dan `QUALITY_MOTION = 30` untuk bergerak/buram).

### Tahap 2: Deteksi Pergerakan dari Data IMU
1. Masuk ke fungsi atau *Task* tempat sensor IMU membaca data (misal: `IMU_Task`).
2. Setelah mendapatkan data *gyroscope* atau selisih akselerasi dari `mpu.getEvent()`, buat sebuah kalkulasi sederhana untuk mencari magnitudo pergerakan (contoh: `sqrt(gx*gx + gy*gy + gz*gz)`).
3. Buat sebuah variabel global yang bisa diakses oleh fungsi kamera, misal `volatile bool is_moving_fast`.
4. Perbarui variabel tersebut: Jika magnitudo pergerakan lebih besar dari `MOTION_THRESHOLD`, maka `is_moving_fast = true`, sebaliknya `false`.

*(Opsi Alternatif)*: Jika tidak ingin membebani IMU Task, deteksi `is_moving_fast` bisa dilakukan dengan mengukur selisih state dari array EKF akselerasi sebelum data kamera dikirim.

### Tahap 3: Modifikasi Fungsi `captureAndSend()` (Sistem Kamera)
Cari fungsi `captureAndSend()` di dalam `firmware-vnetra.ino`. Di bagian paling atas fungsi ini, tambahkan dua lapis pengecekan baru:

**Langkah 3A: Frame Dropping (FPS Limiter)**
1. Cek apakah selisih waktu `millis()` dengan `last_frame_time` masih **kurang dari** `TARGET_FRAME_MS`.
2. Jika iya, langsung lakukan `return;` (batalkan pengiriman agar *frame* di-*drop*).
3. Jika tidak (sudah lebih dari batas), *update* `last_frame_time = millis()` dan lanjutkan proses.

**Langkah 3B: Dynamic JPEG Quality**
1. Dapatkan *instance* sensor kamera menggunakan `esp_camera_sensor_get()`.
2. Lakukan pengecekan terhadap variabel `is_moving_fast` yang didapat dari Tahap 2.
3. Jika `is_moving_fast == true`, panggil `sensor->set_quality(sensor, QUALITY_MOTION);`.
4. Jika `is_moving_fast == false`, panggil `sensor->set_quality(sensor, QUALITY_STILL);`.
5. *(Opsional)*: Tambahkan fungsi *debounce* atau transisi bertahap agar kualitas tidak berkedip (berubah-ubah sangat cepat tiap milidetik).

### Tahap 4: Pengujian & Validasi (Acceptance Criteria)
1. **Pengujian Statis**: Letakkan perangkat di atas meja. Pastikan kualitas gambar tajam (ukuran JPEG lebih besar, *ping* stabil) dan framerate mentok di 10 FPS.
2. **Pengujian Dinamis**: Goyangkan atau putar perangkat secara cepat.
   - **Ekspektasi Visual**: Gambar akan menjadi lebih buram (artefak kompresi terlihat).
   - **Ekspektasi Jaringan**: Indikator *Latency / Ping WebSocket* di Android harus tetap stabil atau tidak melonjak tajam seperti sebelumnya.
   - **Ekspektasi Log**: *Serial Monitor* tidak dipenuhi peringatan kehabisan *Heap* atau antrean TCP penuh.

---

## 📝 Catatan Khusus untuk Developer
- Jangan menggunakan nilai *Quality* di bawah 10 (misal 5) karena justru akan membuat ESP32 kehabisan memori RAM (PSRAM penuh) dan memicu *restart*.
- Pastikan logika pembaruan `sensor->set_quality()` dipanggil **sebelum** fungsi `esp_camera_fb_get()` melakukan jepretan agar parameter kompresi terbaru langsung diaplikasikan ke perangkat keras OV2640.
