# Ringkasan Perbaikan Mode Resolusi ToF (8x8 & 4x4)

Dokumen ini merangkum semua perubahan, penambahan fitur, serta pengurangan kode pada komit/perubahan terakhir guna memperbaiki stabilitas perpindahan mode sensor ToF VL53L5CX (4x4 dan 8x8).

## 🚀 Fitur dan Perbaikan yang Ditambahkan

### 1. Perbaikan Kritis pada Android (Mencegah Force Close)
*   **Perbaikan Urutan Render UI (`CameraStreamActivity`):** Memperbaiki bug *crash* (force close) saat perpindahan dari 8x8 ke 4x4. Sebelumnya, mengubah matriks matriks kolom/baris (`columnCount`) saat sel-sel lama masih ada menyebabkan _ArrayIndexOutOfBoundsException_ internal pada Android `GridLayout`. Solusinya: Pemanggilan `removeAllViews()` kini dilakukan **sebelum** melakukan pembaruan matriks kolom/baris.
*   **Penanganan Transisi Frame yang Aman:** Alih-alih melakukan *auto-rebuild* grid saat menerima data dengan ukuran tidak terduga, sistem pada `tofCollectJob` kini dirancang untuk mendeteksi _mismatch_ antara jumlah sel layar dan panjang paket data. Jika terjadi ketidaksesuaian (karena proses transisi asinkron), aplikasi secara otomatis mengabaikan (skip) frame tersebut hingga ukuran layar dan data berhasil sinkron. Hal ini mencegah error *crash loop*.

### 2. Persistensi State Mode & Sinkronisasi
*   **Simpan Preferensi Lokal (SharedPreferences):** Mengimplementasikan mekanisme penyimpanan resolusi yang dipilih menggunakan `SharedPreferences`. Jika pengguna memilih mode 4x4 dan menutup aplikasi, aplikasi akan memuat antarmuka 4x4 secara *default* di sesi berikutnya.
*   **Sinkronisasi Mode Auto-Reconnect:** Apabila koneksi terputus atau ESP32 mengalami *restart* mendadak, firmware secara bawaan kembali ke mode awal (8x8). Kini, aplikasi Android otomatis mendeteksi hal tersebut dan secara proaktif mengirimkan paket sinkronisasi ulang (`SET_TOF_MODE:4`) tepat ketika status koneksi tercapai (`CONNECTED`). 

### 3. Perbaikan Kritis dan Stabilitas Firmware (ESP32)
*   **Parsing *String* Teks WebSocket yang Aman:** Modifikasi cara firmware membaca perintah teks WebSocket. Sebelumnya program mencoba membaca *pointer* data secara kasar (tanpa pengaman batas akhir) yang dapat merusak tumpukan memori (*stack corruption*). Kini, perintah diurai menggunakan metode *buffer null-terminated* murni (`char cmdBuf[32]`) dibantu dengan alokasi statis dan fungsi `memcpy` secara aman.
*   **Stabilitas Komunikasi Sensor I2C (Mencegah Hang):** Menambahkan *delay settling* selama 100ms tepat setelah sensor dimatikan asupan sinyalnya dengan fungsi `stopRanging()`. 
*   **Modulasi Clock I2C Dinamis:** Mengamankan transisi register konfigurasi ukuran/frekuensi menggunakan kecepatan pita I2C yang diturunkan perlahan menjadi `100kHz`, dan kemudian dikembalikan menuju batas batas kencang `400kHz` sesaat sebelum instruksi penembakan *laser* dihidupkan ulang (`startRanging()`). Hal ini dirancang mengeliminasi isu VL53L5CX mogok kerja.
*   **Dinamisasi Panjang Payload UDP/WS:** Memori paket dinamisasi total; tidak lagi boros mengirim memori paket 64-elemen (ukuran paket 8x8) ketika sensor sedang berada di mode 4x4 (16-elemen).

---

## 🗑️ Hal yang Dihapus / Dikurangkan

### 1. Penghapusan Sinkronisasi Redundan (`tofResJob` & `StateFlow`)
*   Logika observer asinkron berbasis *Coroutine StateFlow* bernama `tofResJob` yang sebelumnya digunakan di dalam `CameraStreamService` dihapus sepenuhnya. Hal ini secara drastis mengurangi risiko terjadinya _race conditions_ atau keadaan ketika status variabel ukuran dan fungsi antar muka grafis saling berbalapan (yang mana sering memicu *force close*). 
*   Status resolusi kini dipegang tunggal secara langsung oleh parameter fungsi aktivitas, dan panjang antrean data terkirimlah yang menjadi tolak ukur sah pergantian mode.

### 2. Penghapusan Kode Sensor Pemicu Crash
*   Baris manipulasi teks tidak aman `String((char*)data).substring(0, len)` pada modul pemroses WebSocket *server* dihilangkan.
*   Penempatan paksa/tanam baku setelan `setResolution(8 * 8)` pada inisialisasi awal sensor (InitTask) dihapuskan, beralih pada penerapan mode ukuran modular berdasarkan preferensi tersimpan sewaktu alat dihidupkan.
