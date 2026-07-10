# ADR-008: Migrasi EKF ke Mahony Filter & *Dynamic Gyro Auto-Reset*

## Status
Proposed

## Date
2026-07-11

## Context
Di *firmware* saat ini (`firmware-vnetra.ino`), kita menggunakan **Extended Kalman Filter (EKF)** berbasis operasi matriks 7x7 (`BasicLinearAlgebra.h`) untuk memproses orientasi dari MPU6050. 
Pada mikrokontroler seperti ESP32, inversi matriks 7x7 pada *loop* kecepatan tinggi (200Hz) membuang siklus CPU yang sangat berharga yang seharusnya dipakai untuk kelancaran *streaming* JPEG kamera dan sensor *Time-of-Flight* (ToF).

Selain itu, MPU6050 tidak memiliki Magnetometer (kompas), sehingga cepat atau lambat *noise* pada giroskop akan membesar dan membuat arah melenceng (*drift*). Pengguna meminta fitur "reset berkala" untuk menyelaraskan ulang (kalibrasi) titik tengah saat *noise* meninggi.

## Doubt-Driven Analysis
Berdasarkan hasil investigasi pada kode `IMU_Task`:
1. *Firmware* ESP32 saat ini **hanya mengirimkan Pitch (theta) dan Roll (phi)** ke Android melalui paket UDP. Arah hadap absolut (*Yaw*) sama sekali tidak dikirim atau digunakan di Android. 
2. Oleh karena itu, melakukan trik matematika untuk "me-reset *Yaw* ke arah jam 12" tidak akan berdampak apapun pada sistem.
3. Masalah *noise* giroskop yang dikeluhkan sebenarnya adalah *Gyro Bias Drift* (giroskop membaca adanya putaran padahal pengguna diam). Ini yang menyebabkan hasil filter bergoyang perlahan.

## Decision
Sebagai solusi penganut aliran *ponytail* (simpel, mematikan, langsung ke akar masalah):

1. **Buang EKF, Gunakan Mahony Filter:**
   Kita akan membuang semua kode `BLA::Matrix` dan `EKF` yang berat. Sebagai gantinya, kita gunakan `MahonyQuaternionUpdate`. Mahony hanya memerlukan belasan baris operasi aritmatika dasar (tanpa matriks) namun memberikan akurasi Pitch dan Roll yang sama presisinya dengan EKF untuk aplikasi 6-DOF.
   
2. **Dynamic Gyro Auto-Reset (Kalibrasi Otomatis Saat Diam):**
   Alih-alih memaksa "reset arah", kita akan menyerang *noise*-nya secara langsung.
   - Sistem akan terus memantau apakah kepala pengguna sedang diam total (*motionless*).
   - Kondisi diam = pergerakan akselerometer nyaris 0 (hanya ada gravitasi 1G) dan putaran giroskop di bawah 2 derajat/detik, selama minimal 3 detik penuh.
   - Jika kondisi diam tercapai, sistem akan mengambil *noise* giroskop saat itu dan menjadikannya titik 0 yang baru (memperbarui `gyro_bias`).
   - Ini secara harfiah akan "membersihkan" *noise* secara *real-time* setiap kali tunanetra berdiri diam, membuat arah kembali presisi.

## Proposed Changes
### `firmware-vnetra.ino`
- **Hapus**: `#include <BasicLinearAlgebra.h>` dan semua variabel `x_ekf, P, Q, R, K`.
- **Tambah**: Fungsi standar `MahonyAHRSupdateIMU(gx, gy, gz, ax, ay, az, dt)`.
- **Ubah di `IMU_Task`**: 
  - Ganti langkah update EKF menjadi pemanggilan `MahonyAHRSupdateIMU`.
  - Tambahkan blok *Dynamic Gyro Auto-Reset* (penghitung *standstill timer*).
  - Ekstrak *theta* dan *phi* dari *quaternion* keluaran Mahony.
  - Sisa sistem pengiriman UDP ke Android tetap sama persis sehingga aplikasi Android **tidak perlu diubah sama sekali**.

## Consequences
- **Positif:** Menghemat penggunaan memori dan ratusan siklus CPU per detik di ESP32-S3. Modul kamera akan bekerja jauh lebih mulus.
- **Positif:** *Noise* akan menghilang secara mandiri setiap kali pengguna berdiri diam sejenak di jalan (kalibrasi *on-the-fly*).
- **Negatif:** Tidak ada, ini adalah peningkatan performa *hardware* murni.
