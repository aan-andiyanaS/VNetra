# Laporan Perubahan dan Penyelesaian Bug (Issue Tracker)

Dokumen ini merangkum seluruh perubahan yang **belum di-commit** (unstaged) sejak commit terakhir (`5de32af` — *fix: resolve sensor_t conflict and type mismatch*), penyelesaian bug, serta penjelasan detail dari fitur dan fungsi baru pada proyek VNetra.

**File yang berubah:**
- `firmware-vnetra/firmware-vnetra/firmware-vnetra.ino` — +190 baris
- `app/src/main/java/com/airi/vnetra/service/CameraStreamService.kt` — +81 baris
- `app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt` — +75 baris
- `app/src/main/res/layout/activity_camera_stream.xml` — +36 baris
- `docs/formula-matematis-v8.md` — +20 baris
- `firmware-vnetra/saran.md` — file baru (kosong)

## 1. Penyelesaian Bug (Bug Fixes)

Berikut adalah daftar bug yang berhasil diselesaikan pada pembaruan ini:

*   **Bug Crash pada Firmware akibat Thread-Safety WebSocket:**
    *   **Masalah:** Pemanggilan fungsi `ws.binaryAll()` sebelumnya dilakukan langsung dari dalam task FreeRTOS (`IMU_Task` dan `TOF_Task`) dan juga dari `loop()`. Library `ESPAsyncWebServer` tidak *thread-safe*, sehingga akses konkuren ini menyebabkan korupsi memori dan *crash* (ESP32 restart mendadak).
    *   **Penyelesaian:** Diimplementasikan sistem **Message Queue** (`wsQueue`) dan **Mutex** (`ws_mutex`). Task sensor (`IMU_Task` dan `TOF_Task`) sekarang hanya mengalokasikan memori (`malloc`), membuat objek `WsMessage_t`, dan memasukkannya ke dalam `wsQueue`. Proses pengiriman data sesungguhnya dieksekusi secara aman di dalam `loop()` utama yang dilindungi oleh `ws_mutex`.
*   **Bug `wsClientConnected` Tidak Akurat Saat Disconnect (Firmware):**
    *   **Masalah:** Saat sebuah klien WebSocket memutus koneksi, kondisi `wsClientConnected` dicek dengan `ws.count() > 1`. Logika ini **salah** — ketika callback `WS_EVT_DISCONNECT` dipanggil, `ws.count()` sudah otomatis berkurang 1 (klien sudah dihapus dari daftar). Akibatnya, `wsClientConnected` tetap `true` meskipun tidak ada klien yang terhubung, dan ESP32 terus membuang sumber daya untuk mengirim data.
    *   **Penyelesaian:** Kondisi diperbaiki menjadi `ws.count() > 0` agar akurat sesuai perilaku nyata library.
*   **Bug Timeout Koneksi WebSocket di Android (OkHttp):**
    *   **Masalah:** Aplikasi Android sering terputus dari ESP32 setelah beberapa detik. Hal ini disebabkan karena konfigurasi `pingInterval(15, TimeUnit.SECONDS)` pada OkHttp. ESPAsyncWebServer ternyata tidak mendukung balasan otomatis untuk WebSocket Ping (opcode 0x9), sehingga OkHttp mengira koneksi mati (*timeout*).
    *   **Penyelesaian:** `pingInterval` dihapus dari OkHttp. Sebagai gantinya, ESP32 sekarang mengirimkan paket data *Heartbeat* buatan sendiri (`FRAME_TYPE_HBEAT`) setiap 10 detik. Aplikasi Android akan mendeteksi frame ini sebagai tanda bahwa koneksi masih hidup.
*   **Bug Sensor VL53L5CX (ToF) Gagal Inisialisasi:**
    *   **Masalah:** Sering terjadi kegagalan saat sensor ToF sedang mengunggah *firmware* bawaannya (sekitar 90KB) via I2C, yang rentan terhadap *error* jika *clock* terlalu tinggi.
    *   **Penyelesaian:** Kecepatan I2C (`Wire.setClock`) diturunkan menjadi 100kHz khusus saat inisialisasi sensor `myImager.begin()`. Setelah inisialisasi berhasil, *clock* dikembalikan ke 400kHz. Frekuensi *ranging* sensor juga diturunkan dari 15Hz ke 10Hz agar komunikasi I2C lebih stabil.
*   **Bug Akurasi Akselerometer (Gravitasi tidak nol saat diam):**
    *   **Masalah:** Terdapat *offset* (bias) bawaan pabrik pada MPU-6050, sehingga meskipun perangkat diam, akselerometer masih mencatat adanya pergerakan.
    *   **Penyelesaian:** Ditambahkan fungsi **Kalibrasi Bias Statis** (`calibrateAccelBias()`). Saat *booting*, ESP32 akan mengambil 500 sampel untuk mengukur *offset* gravitasi dan menghitung nilai bias. Nilai bias ini kemudian secara otomatis dikurangkan dari data pembacaan sensor (*de-biasing*) pada setiap iterasi sebelum diproses oleh algoritma EKF.
*   **Bug Tampilan Grid ToF di Android Tidak Terlihat atau Berantakan:**
    *   **Masalah:** Pembuatan kotak-kotak Grid ToF sebelumnya menggunakan *layout weights* yang tidak dapat diprediksi ukurannya sebelum dirender di layar, sehingga sering kali grid tidak tampil.
    *   **Penyelesaian:** Modifikasi algoritma pembuatan grid. Grid sekarang di-set ukurannya ke 1x1 piksel dengan posisi baris/kolom eksplisit (deterministik). Setelah *GridLayout* berhasil mengkalkulasi lebar dan tinggi area utamanya di layar (`binding.gridTof.post`), barulah ukuran tiap *cell* (kotak) diatur secara pasti dalam satuan piksel (misalnya `gridW / 8`).
*   **Bug Frame Drop (Android):**
    *   **Masalah:** Beberapa frame sensor hilang/terlewat karena proses *render* UI yang terlalu sibuk di Android.
    *   **Penyelesaian:** Menambahkan kapasitas antrean (*buffer capacity*) pada `_imuFlow` dan `_tofFlow` dari 2 menjadi 4. Selain itu, kecepatan pengiriman data IMU dari ESP32 dikurangi (dari 200Hz menjadi ~20Hz), sehingga beban jaringan dan CPU Android jauh berkurang.
*   **Bug Stack Overflow pada FreeRTOS Task (Firmware):**
    *   **Masalah:** Stack size yang dialokasikan untuk `IMU_Task` (8192 byte) dan `TOF_Task` (4096 byte) terlalu kecil setelah penambahan fitur baru (queue, malloc, kalibrasi), berpotensi menyebabkan *stack overflow* dan crash ESP32 yang sulit dideteksi.
    *   **Penyelesaian:** Stack size `IMU_Task` dinaikkan dari **8192 → 12288 byte** (ditambah 50%), dan `TOF_Task` dari **4096 → 6144 byte** (ditambah 50%), memberi ruang aman untuk semua operasi baru.
*   **Bug Interval Polling ToF Terlalu Lambat (Firmware):**
    *   **Masalah:** `TOF_Task` memiliki delay `vTaskDelay(60ms)` yang terlalu lambat, menyebabkan data jarak dari VL53L5CX tidak bisa diambil secara konsisten sesuai frekuensi ranging 10Hz (idealnya setiap ~100ms, bukan diperiksa tiap 60ms).
    *   **Penyelesaian:** Delay loop `TOF_Task` diturunkan dari **60ms → 10ms**, sehingga polling lebih responsif dan tidak ada data frame yang terlewat dari sensor.

---

## 2. Penjelasan Detail Fitur dan Fungsi yang Diperbarui

### A. Firmware ESP32 (`firmware-vnetra.ino`)

*   **`ws_mutex` & `wsQueue` (Sistem Antrean Pesan):**
    *   **Fungsi:** Menjamin keamanan pertukaran data antara *task* pembacaan sensor (berjalan paralel di *core* CPU) dengan pengiriman WiFi. Data yang dibaca sensor dibungkus lalu dilempar ke antrean. Loop utama bertugas membaca antrean tersebut satu per satu dan mengirimkannya ke klien (Android).
*   **`calibrateAccelBias()`:**
    *   **Fungsi:** Mengkalibrasi sensor gerak pada saat perangkat dinyalakan. Ia mengambil ratusan data mentah dalam keadaan diam, mencari selisih rata-ratanya terhadap gravitasi bumi ideal ($9.81 m/s^2$), dan menyimpan angka tersebut untuk mengoreksi bacaan sensor di waktu berikutnya.
*   **Pengurangan Rate-Limit Frame IMU (`imu_send_tick`):**
    *   **Fungsi:** Filter yang bertugas membatasi pengiriman data IMU. EKF (algoritma filter) tetap berjalan cepat di 200Hz demi akurasi perhitungan matematis. Namun, tidak semua datanya dikirim ke Android (cukup 1 dari 10 data, alias 20Hz), karena mata manusia tidak butuh 200 perbaruan UI dalam sedetik. Hal ini menghemat *bandwidth*.
*   **Status Sensor Jarak (ToF Target Status):**
    *   **Fungsi:** Paket data ToF tidak hanya mengirim jarak (mm) lagi, tapi sekarang memuat tambahan memori 64 byte untuk `target_status`. Format paket baru: **Header(1B) + Timestamp(8B) + Distance×64(128B) + Status×64(64B) = 201 byte**. Jika sensor mendeteksi jarak 0, Android dapat melihat kode *status* error-nya dari paket tambahan ini.
*   **Optimasi I2C Transaction (`setWireMaxPacketSize`):**
    *   **Fungsi:** Menambahkan pemanggilan `myImager.setWireMaxPacketSize(128)` setelah VL53L5CX berhasil di-inisialisasi. Ini mengoptimalkan ukuran maksimum paket I2C per transaksi di ESP32 agar sesuai dengan kemampuan hardware, mengurangi risiko *buffer overflow* di layer I2C dan meningkatkan stabilitas komunikasi sensor ToF.
*   **Diagnostik Statistik Frame (`stat_frames_cam/imu/tof`):**
    *   **Fungsi:** Tiga variabel counter baru (`stat_frames_cam`, `stat_frames_imu`, `stat_frames_tof`) dideklarasikan sebagai `volatile uint32_t`. Setiap frame yang berhasil dikirim ke antrian/klien akan menaikkan counter-nya. Setiap 10 detik (saat *heartbeat*), Serial Monitor menampilkan statistik `[DATA SENT] CAM: X | IMU: Y | TOF: Z` lalu di-reset, membantu mendeteksi apakah ada sensor yang berhenti mengirim data.

### B. Aplikasi Android (Kotlin & XML)

*   **`FRAME_TYPE_HBEAT` di `CameraStreamService.kt`:**
    *   **Fungsi:** Menangkap paket data kode `0x03` dari ESP32. Jika data ini diterima, aplikasi mencatatnya di Log bahwa koneksi masih "sehat" (*Heartbeat diterima*), mencegah pemutusan paksa karena idle.
*   **Logika Peringatan (Warning) Jarak ToF `0`:**
    *   **Fungsi:** Membantu *debugging* sensor. Jika aplikasi Android mendeteksi semua kotak ToF berjarak 0, maka angka 0 tersebut akan digantikan dengan kode *status* error milik sensor dalam bentuk negatif (misal: `-4`, `-5`), lalu muncul peringatan (Warning Log) bahwa semua jarak berisi 0.
*   **Manajemen Tugas (Job Cancellation) di `CameraStreamActivity.kt`:**
    *   **Fungsi:** Memisahkan variabel kontrol tugas untuk Kamera, IMU, dan ToF (`imuCollectJob`, `tofCollectJob`). Ketika koneksi terputus dan mencoba menyambung lagi, fungsi ini memastikan tugas lama dihentikan total (di-cancel) sebelum memulai yang baru, menghindari *overlap* data di layar.
*   **Pembaruan Tampilan Antarmuka (UI) EKF / MPU6050:**
    *   **Fungsi:** Elemen UI untuk Pitch, Roll, dan Accel dipercantik. Kini memiliki judul panel "EKF / MPU6050" dengan warna cerah `#4FC3F7`, menggunakan huruf mesin tik (*monospace*), dan dibekali simbol derajat (°) serta satuan percepatan ($m/s^2$) agar lebih profesional dan mudah dibaca (File: `activity_camera_stream.xml`).

### C. Pembaruan Dokumen Matematis (`formula-matematis-v8.md`)

*   **Fungsi:** Telah ditambahkan Bab **A.EKF.0** yang berisi landasan teori dari *Kalibrasi Bias Akselerometer Statis*. Penjelasan ini menguraikan rumus dasar bahwa bias adalah hasil pengurangan vektor rata-rata sampel dikurangi skala normalisasi gravitasi aktual. Ini menjelaskan fondasi teori mengapa `calibrateAccelBias()` di ESP32 diperlukan.
