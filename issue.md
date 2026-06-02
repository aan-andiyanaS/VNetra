# Dokumentasi Perbaikan Koneksi dan Recovery Otomatis VNetra

## Ringkasan Perubahan
Pembaruan ini berfokus pada penyelesaian bug "Half-open TCP connection" yang menyebabkan aplikasi Android *freeze* (tidak menerima data meskipun status masih `CONNECTED` pasca ESP32 restart). Selain itu, terdapat optimalisasi waktu *booting* (Fast Boot) dan stabilitas inisialisasi sensor di sisi *firmware* ESP32, serta perbaikan *UI state* Android selama masa re-koneksi.

---

## 1. Android App: Perbaikan Koneksi & Watchdog (`CameraStreamService.kt`)

### Bug yang Diperbaiki:
*   **Half-open Socket (Freeze State):** Sebelumnya, jika ESP32 di-restart paksa (mati daya), OkHttp di Android tidak segera mendeteksi terputusnya *socket* karena tidak adanya *ping reply* dari `ESPAsyncWebServer`. Akibatnya, aplikasi tertahan di state `CONNECTED` tanpa data.

### Fitur yang Ditambahkan/Diubah:
*   **Implementasi Data Watchdog (`watchdogJob`):** 
    *   **Ditambahkan:** Variabel `lastDataReceivedTime` untuk melacak kapan *frame* data terakhir (Kamera/IMU/ToF) masuk.
    *   **Ditambahkan:** *Coroutine* baru `watchdogJob` yang berjalan di *background*. Jika statusnya `CONNECTED` tapi tidak ada data yang masuk selama lebih dari **12 detik**, *watchdog* akan memaksa pembatalan (cancel) `activeWebSocket`.
    *   **Efek:** Memaksa putusnya koneksi yang "menggantung" sehingga mekanisme rekoneksi otomatis (yang sudah ada) bisa segera bekerja.
*   **Optimalisasi OkHttp Builder:**
    *   **Diubah:** Menambahkan `.pingInterval(5, TimeUnit.SECONDS)` dan `.readTimeout(15, TimeUnit.SECONDS)`.
    *   **Alasan:** Meskipun `ESPAsyncWebServer` tidak merespons *ping*, *ping interval* dan *read timeout* ini digunakan secara sengaja untuk memicu *force-disconnect* secara agresif di level protokol saat *socket* sudah tidak sehat.
*   **Pembersihan Resource:**
    *   **Diubah:** Pemanggilan `watchdogJob?.cancel()` dipastikan dieksekusi saat koneksi dihentikan melalui `stopStreamAndRelease()` dan di awal `startStreaming()`.

---

## 2. Android App: Perbaikan UI/UX Rekoneksi (`CameraStreamActivity.kt`)

### Bug yang Diperbaiki:
*   **Data Stale (Nyangkut) Saat Reconnect:** Sebelumnya, angka dari sensor IMU (Pitch, Roll, Accel) atau ToF yang lama masih tampil di layar selama proses rekoneksi.
*   **Multiple Data Collectors & Error Palsu:** Saat rekoneksi berhasil, terjadi pemanggilan *collectors* data yang bertumpuk (*overlapping*), dan pembatalan (*cancellation*) tugas terkadang memicu pesan *error* palsu di antarmuka.

### Fitur yang Ditambahkan/Diubah:
*   **Manajemen Collector Terpusat:**
    *   **Dihilangkan:** Pemanggilan `startCollectingFrames()` dan `startCollectingSensors()` di dalam `onServiceConnected`.
    *   **Ditambahkan:** Pemanggilan fungsi-fungsi tersebut dipindahkan secara eksklusif ke dalam blok `CameraStreamService.ConnectionState.CONNECTED`. Ini memastikan data baru hanya dikumpulkan saat koneksi benar-benar sudah tersambung ulang.
*   **Pembersihan Tampilan Sensor (`clearStaleSensorDisplay`):**
    *   **Ditambahkan:** Fungsi `clearStaleSensorDisplay()` yang dipanggil saat *state* berubah ke `CONNECTING`. Fungsi ini mengubah teks Pitch, Roll, Accel, dan nilai ToF menjadi "—" (kosong) agar tidak membingungkan pengguna.
*   **Penanganan Error Coroutine yang Lebih Baik:**
    *   **Diubah:** Menambahkan tangkapan spesifik terhadap `kotlinx.coroutines.CancellationException` di dalam fungsi *collector*. Pengecualian ini sekarang di-*re-throw* (dilemparkan ulang) tanpa memicu `StreamState.ERROR(...)` pada UI, menghindari notifikasi *error* yang tidak relevan saat *stream* sengaja di-restart.

---

## 3. Firmware ESP32: Fast Boot, Sensor Retry, & WiFi Fix (`firmware-vnetra.ino`)

### Bug yang Diperbaiki:
*   **Waktu Booting Terlalu Lama:** Proses *booting* tertahan oleh inisialisasi sensor yang berjalan lambat, khususnya saat mengunggah *firmware* sensor VL53L5CX (bisa memakan waktu > 5-10 detik).
*   **Inisialisasi Sensor Rentan Gagal:** Sensor MPU6050 atau VL53L5CX terkadang gagal menyala di percobaan pertama saat tegangan baru saja dihidupkan.
*   **Crash Saat Disconnect dari WiFi (Isu B):** *Pointer queue* (antrean WebSocket) masih diproses oleh tugas sensor meskipun WiFi sudah terputus, dan transisi berbagi radio antara WiFi dan BLE terlalu cepat sehingga dapat memicu *crash*.

### Fitur yang Ditambahkan/Diubah:
*   **Fast Boot via Background WiFi Task (`wifiInitTask`):**
    *   **Diubah:** Proses koneksi WiFi tidak lagi memblokir antrean utama (*main loop/setup*). Koneksi kini dialihkan ke *background task* di Core 0 (`wifiInitTask`). Ini membuat proses *connect* WiFi (termasuk *BSSID fast-path*) berjalan paralel dengan *hardware init*.
*   **Inisialisasi Sensor VL53L5CX Terpisah (`TOF_InitTask`):**
    *   **Ditambahkan:** Pengunggahan *firmware* internal VL53L5CX sebesar 90KB via I2C memakan banyak waktu. Hal ini sekarang dilakukan di dalam tugas *background* khusus (`TOF_InitTask`). Pengiriman data jarak akan otomatis dimulai segera setelah tugas ini berhasil selesai.
*   **Mekanisme Retry (Percobaan Ulang) MPU6050:**
    *   **Ditambahkan:** Loop *retry* sebanyak 3 kali (dengan *delay* 500ms) untuk pemanggilan `mpu.begin()`. Hal ini meningkatkan peluang sukses saat inisialisasi awal.
*   **Perbaikan Transisi WiFi ke BLE (Crash Fix - Isu B):**
    *   **Ditambahkan:** Membersihkan antrean data secara eksplisit menggunakan `xQueueReset(wsQueue)` saat transisi putus koneksi.
    *   **Diubah:** Mengatur ulang (*reset*) bendera (*flag*) `wifiConnected = false` secara lebih awal dan menambahkan penundaan (*delay*) yang lebih panjang (1000ms) sebelum radio WiFi dimatikan, guna memberikan waktu penyelesaian komunikasi *co-existence* radio ESP32 yang lebih stabil sebelum beralih ke mode BLE.

---
*Status: Closed / Diselesaikan*
