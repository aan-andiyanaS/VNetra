# Plan Integrasi Sensor MPU6050 & VL53L5CX

## Deskripsi Tugas
Mengintegrasikan sensor MPU6050 (IMU + EKF) dan VL53L5CX (ToF 8x8) ke dalam firmware utama `firmware-vnetra.ino`. Mengubah aplikasi Android untuk menerima dan menampilkan data dari kedua sensor secara *real-time* di halaman `CameraStreamActivity`.

## 1. Modifikasi Firmware (`firmware-vnetra.ino`)
- **Inisialisasi I2C & Sensor:**
  - Menambahkan konfigurasi pin I2C (`SDA = 1`, `SCL = 2`).
  - Menggabungkan setup dan inisialisasi dari `MPU60500EFK.ino` dan `VL53L5CX.ino`.
  - Menambahkan mutex I2C (`SemaphoreHandle_t i2c_mutex`) agar akses `Wire` aman saat diakses oleh dua task yang berbeda (IMU dan ToF).
- **FreeRTOS Tasks (Core 1):**
  - Membuat `IMU_Task` (100Hz) untuk menjalankan algoritma EKF 7-State.
  - Membuat `TOF_Task` (15Hz) untuk membaca data matriks 8x8 secara efisien tanpa memblokir EKF.
- **Transmisi WebSocket (Binary):**
  - **IMU Frame (`0x02`):** Mengirim 24 byte (6 nilai `float`: Pitch, Roll, Wx, Wy, Wz, Akselerasi Linear).
  - **ToF Frame (`0x04`):** Mengirim 128 byte (64 nilai `int16_t`: Jarak tiap zona).

## 2. Modifikasi Aplikasi Android (Service & Manager)
- **`CameraManager.kt`:** 
  - Memperbarui parser WebSocket untuk mengenali `FRAME_TYPE_IMU` (0x02) dan `FRAME_TYPE_TOF` (0x04).
  - Mengonversi data *binary* menjadi `FloatArray` (IMU) dan `IntArray` (ToF).
  - Membuat `imuFlow` dan `tofFlow` menggunakan `callbackFlow`.
- **`CameraStreamService.kt`:**
  - Mengekspos `imuFlow` dan `tofFlow` agar dapat diobservasi oleh Activity.

## 3. Modifikasi UI/UX (`activity_camera_stream.xml` & `CameraStreamActivity.kt`)
- **Grid ToF 8x8:**
  - Menambahkan `GridLayout` 8x8 transparan (overlay) di atas *Camera Frame*.
  - Mengisi setiap kotak dengan teks angka jarak (mm).
  - *(Opsional)* Memberikan warna selang-seling atau gradasi warna berdasar jarak agar mudah dibaca.
- **Panel IMU:**
  - Menambahkan `TextView` overlay di pojok layar untuk menampilkan data Pitch, Roll, dan Akselerasi Linear (MPU6050).
- **`CameraStreamActivity.kt`:**
  - Menjalankan coroutine untuk melakukan *collect* dari `imuFlow` dan `tofFlow`.
  - Mengupdate grid UI dan teks IMU pada *Main Thread* (`Dispatchers.Main`).

## 4. Optimasi Performa
- Pemisahan *core*: Kamera & Jaringan di Core 0 (default ESP-IDF), Sensor di Core 1.
- Penggunaan tipe data *binary* (bukan *string* JSON) melalui WebSocket memastikan latensi sangat rendah (mencegah lag video).
- Render grid ToF di Android menggunakan struktur *View* yang digunakan ulang (hindari *re-inflate*) demi 60 FPS pada UI.
