# ADR-009: Peningkatan Akurasi Akselerometer (Mahony LPF & Kp Tuning)

## Status
Proposed

## Date
2026-07-11

## Context
Setelah melakukan migrasi dari EKF ke Mahony Filter (ADR-008), pengguna melaporkan bahwa indikator akselerasi linear (`a_lin_mag`) di layar Android menunjukkan angka **1.2 m/s² padahal kacamata (alat) sedang diam di atas meja**. Ini dianggap lebih buruk daripada saat menggunakan EKF.

## Doubt-Driven Analysis
Kenapa hal ini terjadi?
1. **EKF vs Mahony:** EKF menggunakan matriks kovarians (*Q* dan *R*) untuk menebak dan meredam *noise* sensor di level matematika yang dalam, sehingga hasil akhir `a_lin` tampak mulus. Sedangkan Mahony tidak memiliki peredam *noise* bawaan untuk hasil ekstraksi linear akselerasi. 
2. **Kalkulasi Mentah:** Di kode saat ini, `a_lin_mag` dihitung secara mentah setiap 5ms (200Hz). Sensor MPU6050 murah memang memiliki getaran elektrik (jitter) alami sebesar 0.5 - 1.5 m/s². 
3. **Konvergensi Gravitasi lambat:** Nilai Kp (*Proportional Gain*) pada Mahony mungkin terlalu rendah (`twoKp = 1.0f`), sehingga vektor gravitasi lambat mengejar posisi aslinya, menyisakan selisih yang terbaca sebagai "gerakan".

## Decision (Ponytail Approach)
Kita tidak akan kembali ke EKF yang berat. Kita akan membuat Mahony menjadi halus dengan 3 langkah sederhana yang memakan 0% CPU:

1. **Implementasi LPF (Low-Pass Filter / EMA):**
   Menerapkan rumus *Exponential Moving Average* pada hasil `a_lin_mag` sebelum dikirim ke Android.
   `a_lin_smooth = (0.1 * a_lin_raw) + (0.9 * a_lin_smooth)`
   Ini akan meratakan *noise* secara drastis layaknya EKF, tapi hanya butuh 1 baris kode.

2. **Tuning Kp & Ki Mahony:**
   Menaikkan `twoKp` (misal menjadi 2.5f) agar filter lebih kaku (cepat mengunci vektor gravitasi saat diam).

3. **Noise Gate (Deadzone):**
   Akselerasi linear di bawah ambang batas getaran statis alami (misalnya < 0.4 m/s²) akan dipaksa (*clamp*) menjadi 0.0 m/s². Dengan begini, saat ditaruh di meja, UI akan murni menampilkan 0.00 m/s².

## Proposed Changes
### `firmware-vnetra.ino`
- **Variabel Global**:
  - Ubah `const float twoKp = 1.0f;` menjadi `const float twoKp = 2.5f;`.
  - Tambahkan `static float a_lin_smooth = 0.0f;` di dalam `IMU_Task`.
- **IMU_Task**:
  - Terapkan rumus LPF: `a_lin_smooth = (0.1f * a_lin_mag) + (0.9f * a_lin_smooth);`
  - Terapkan *Noise Gate*: `if (a_lin_smooth < 0.4f) a_lin_smooth = 0.0f;`
  - Masukkan `a_lin_smooth` ke dalam paket UDP payload `[5]`.

## Consequences
- **Positif:** Angka akselerasi linear di layar akan kembali 0 m/s² saat diam, dan pergerakan akan terasa jauh lebih *smooth* tanpa lonjakan *noise*.
- **Positif:** CPU ESP32-S3 tetap ringan karena LPF hanya operasi matematika statis 1 dimensi.
