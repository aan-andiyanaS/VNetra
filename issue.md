# Optimasi Latensi Firmware ESP32 (Ponytail Full)

Berdasarkan investigasi `/doubt-driven-development` pada `firmware-vnetra.ino`, keterlambatan (latensi) sistem tidak disebabkan oleh kerumitan operasi matematika EKF, melainkan oleh pembagian beban Core yang menumpuk dan adanya sisa kode mati (*Dead Code*). 

Berikut adalah rencana perbaikan efisiensi ekstrem tanpa mengubah satupun output atau fungsionalitas yang ada:

## 1. Hapus Dead Code (`wsQueue`)
*   **Masalah:** Di fungsi `loop()`, ESP32 secara terus-menerus mengecek antrian `wsQueue` ratusan kali per detik. Namun, tidak ada satupun instruksi `xQueueSend` di seluruh firmware (sensor IMU & TOF sudah berevolusi mengirim data via UDP secara langsung).
*   **Solusi:** Hapus seluruh deklarasi `wsQueue`, `xQueueCreate`, dan blok pembacaan `xQueueReceive` di `loop()`. Ini adalah pendekatan *Ponytail Full*: menghapus beban komputasi dan memori yang tidak pernah digunakan.

## 2. Load Balancing (Pindahkan `TOF_Task` ke Core 0)
*   **Masalah:** Saat ini `IMU_Task` (Prioritas 2), `TOF_Task` (Prioritas 1), dan `loop()` untuk kamera (Prioritas 1) semuanya berdesakan memperebutkan **Core 1** ESP32! Akibatnya, saat Kamera sibuk memproses gambar, task sensor harus antre (berimbas pada *jitter*).
*   **Solusi:** Ubah parameter `xTaskCreatePinnedToCore` untuk `TOF_Task` dari Core 1 menjadi **Core 0**. Core 0 saat ini sangat luang karena hanya menangani instruksi WiFi. Mengingat jalur I2C sudah diamankan secara ketat dengan `i2c_mutex`, kedua task sensor ini bisa berjalan paralel dengan sempurna di dua *core* fisik berbeda.

## 3. Stabilisasi EKF 200Hz (`vTaskDelayUntil`)
*   **Masalah:** `IMU_Task` saat ini menghitung *delta time* (`dt`) dengan `millis()`, lalu menjeda task menggunakan `vTaskDelay(5)`. Jitter membuat nilai `dt` fluktuatif antara 4-6ms, yang membuat pembacaan EKF berisiko kurang presisi dan membebani komputasi *floating-point*.
*   **Solusi:** Terapkan `vTaskDelayUntil(&xLastWakeTime, pdMS_TO_TICKS(5))` dan jadikan `dt = 0.005f` sebagai konstanta pasti. Ini memaksa EKF untuk mengunci pada kecepatan absolut 200Hz, mengurangi overhead waktu eksekusi CPU pada baris tersebut menjadi hampir nol.
