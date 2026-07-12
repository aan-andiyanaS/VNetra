

## ADR-023: Optimalisasi Jaringan Kamera & Penghapusan Bottleneck WebSocket (Report-Camera.md)

- **Status:** Direncanakan (12 Juli 2026)

### 1. Konteks
Kamera VNetra sering mengalami patah-patah (*stuttering*) dan lonjakan latensi (ping tinggi). Berdasarkan 
eport-camera.md, hal ini disebabkan oleh tiga *bottleneck* arsitektural pada *firmware* ESP32:
1. **Double FPS Limiter:** Fungsi captureAndSend() dan loop() sama-sama membatasi FPS menggunakan timer yang berbeda (millis() vs esp_timer_get_time()), menyebabkan balapan kondisi (*race condition*) yang membuang frame secara acak.
2. **ACK Flow Control Kuno:** Penggunaan variabel unacked_frames yang membatasi maksimal 4 frame *in-flight* terlalu ketat. Jika Android telat membalas *ACK*, ESP32 langsung berhenti mengirim frame, mengabaikan buffer asli bawaan TCP.
3. **Semaphore yang Mubazir:** Variabel ws_mutex dan wsQueue digunakan untuk melindungi pengiriman WebSocket antar-*thread*. Namun, karena sensor IMU dan ToF sudah bermigrasi ke UDP, WebSocket kini 100% dimonopoli oleh kamera di Core 1, sehingga *mutex* hanya menjadi beban eksekusi (menahan CPU).

### 2. Keputusan
Kita akan memangkas semua batasan statis ini dan bergantung sepenuhnya pada arsitektur asinkron *native*:
1. Menghapus batasan FPS di captureAndSend() dan menyerahkan ritme waktu sepenuhnya pada loop().
2. Menghapus logika unacked_frames dan mekanisme pemrosesan pesan ACK:CAM. Pengiriman frame akan menggunakan pengecekan *native* dari pustaka WebSocket (memastikan antrean TCP tidak penuh).
3. Menghapus total ws_mutex dan wsQueue.

### 3. Konsekuensi
- **Positif:** Aliran *video stream* akan jauh lebih mulus (*smooth*), latensi turun drastis, dan CPU ESP32 lebih lega.
- **Positif:** Beban aplikasi Android untuk terus mengirim balik pesan *ACK* hilang.
- **Negatif:** Jika koneksi Wi-Fi tiba-tiba sangat buruk, buffer TCP LwIP internal bisa penuh (namun kita akan menanganinya dengan pengecekan ketersediaan buffer sebelum fungsi *send* dipanggil).

## ADR-024: Perbaikan Deklarasi Ganda `isCameraActive`
- **Status:** Dieksekusi (12 Juli 2026)

### 1. Konteks
Dari hasil inspeksi menyeluruh pada codebase (poin 1 di `report.md`), ditemukan *bug* deklarasi ganda pada variabel global `isCameraActive` di `firmware-vnetra.ino`. Ini terjadi karena sisa-sisa refaktor ADR-023.
```cpp
// Baris 99:
volatile bool isCameraActive = true;

// Baris 244:
bool isCameraActive = true;
```
Deklarasi ganda ini akan memicu error kompilasi `"redefinition of isCameraActive"`.

### 2. Keputusan (Incremental Implementation & Ponytail)
Menggunakan pendekatan *ponytail* (solusi paling sederhana yang langsung bekerja), kita akan menghapus deklarasi baru yang salah (baris 99) dan mempertahankan deklarasi aslinya di baris 244. Tidak perlu membongkar ulang seluruh file, cukup menghapus satu baris bermasalah.

### 3. Konsekuensi
- **Positif:** Kompilasi *firmware* kembali berhasil tanpa error.
- **Positif:** Menjaga kebersihan *global scope*.
