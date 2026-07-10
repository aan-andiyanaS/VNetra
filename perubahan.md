# Rencana Refactoring: Kerapian & Efisiensi Penulisan (Ponytail Full)

Meskipun kode saat ini sudah berjalan efisien secara performa, secara *penulisan*, struktur kode dapat dirapikan agar jauh lebih mudah dibaca dan dipahami oleh developer lain. Pendekatan `/ponytail full` memandang bahwa: **"Jika sesuatu bisa berjalan di loop biasa, jangan buat FreeRTOS Task khusus untuk itu."**

## Rencana Perubahan (Tanpa Mengubah Output Logika)

### 1. Pembasmian `ButtonReset_Task` (Over-Engineering)
*   **Masalah:** Saat ini firmware mengalokasikan **4096 bytes RAM** dan membuat **satu thread/core terpisah (FreeRTOS Task)** HANYA untuk mengecek apakah sebuah tombol sedang ditekan atau tidak (melalui `digitalRead`). Ini adalah *over-engineering*.
*   **Solusi:** Hapus task ini sepenuhnya! Pindahkan logika pembacaan tombol ke dalam sebuah fungsi sederhana `handleButton()` yang dipanggil langsung dari `loop()`. Tidak perlu antrean thread, membebaskan memori 4KB, dan kode menjadi lebih pendek.

### 2. Refactoring Fungsi `loop()` Menjadi Modular
*   **Masalah:** Fungsi `loop()` saat ini adalah sebuah fungsi raksasa (sekitar 160+ baris) yang berisi campuran logika: *BLE Provisioning*, *Power Save*, WiFi Reconnect, dan pengiriman *Heartbeat*. Sangat membingungkan jika developer lain ingin melacak alur programnya.
*   **Solusi:** Memecah isi `loop()` menjadi fungsi-fungsi modular yang sangat rapi. Bentuk akhirnya nanti hanya akan seperti ini:
    ```cpp
    void loop() {
        handleButton();             // 1. Cek tombol (reset WiFi / kalibrasi IMU)
        handleBLEProvisioning();    // 2. Setup via BLE (jika sedang aktif)
        handleWiFiReconnection();   // 3. Jaga stabilitas koneksi WiFi
        handleCameraStreaming();    // 4. Ambil foto & kirim via WebSocket
        handleStatsAndHeartbeat();  // 5. Kirim data ping & print statistik
        yield();
    }
    ```

### 3. Simplifikasi Logika Kalibrasi Ganda (DRY Principle)
*   **Masalah:** Baris kode untuk menghapus kalibrasi di memori (NVS) dan merestart ESP32 ditulis dua kali secara berulang (sekali di dalam `onWsEvent`, dan sekali lagi di logika *double-click* tombol).
*   **Solusi:** Bungkus kode tersebut ke dalam satu helper `void triggerImuCalibration()`. Ini menerapkan prinsip *Don't Repeat Yourself* (DRY) yang membuat pemeliharaan kode menjadi lebih mudah.
