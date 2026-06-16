# Plan Implementasi: Auto-Switch Resolusi VL53L5CX Berdasarkan Ambient Light

**Tujuan:** Membuat sensor ToF otomatis berpindah dari mode 8x8 ke 4x4 saat berada di luar ruangan (cahaya matahari terik) untuk mencegah saturasi SPAD, dan otomatis kembali ke 8x8 saat masuk ke dalam ruangan.

## Analisis Alur Kerja (Workflow) Saat Ini
1. **ESP32:** Pergantian mode saat ini dikendalikan oleh variabel `tofModeChangePending`. Saat aktif, sensor menghentikan proses *ranging*, mengkonfigurasi resolusi/frekuensi baru, lalu memulai *ranging* lagi. Transisi ini sangat mulus (*seamless*) dan **tidak memerlukan restart ESP32**.
2. **Android App:** HP menerima array 1D berisi jarak. Jika jumlah elemennya 64, ia merender grid 8x8. Jika 16, ia merender 4x4. UI saat ini hanya berubah ukuran ketika user memencet tombol `SET_TOF_MODE`. Jika paket ukuran 16 tiba tapi UI masih mode 8x8, HP akan menolak (*drop*) frame tersebut karena ukuran elemen visual tidak cocok dengan data.

## 1. Rencana Modifikasi ESP32 Firmware (`firmware-vnetra.ino`)
Agar ESP32 otomatis berubah tanpa menunggu HP, kita akan mengekstrak data gangguan cahaya (*noise* ambient) bawaan dari sensor.

**Langkah-langkah Eksekusi (High-Level):**
1. Sensor menghasilkan parameter `measurementData.ambient_per_spad[64]` yang berisi tingkat foton cahaya sekitar (dalam *kcps/spad*).
2. Di dalam perulangan `TOF_Task`, setiap kali berhasil `getRangingData`, lakukan *looping* untuk menghitung nilai rata-rata dari seluruh zona aktif `ambient_per_spad`.
3. Terapkan logika **Hysteresis** (dua nilai ambang batas) untuk mencegah *bouncing* (mode berkedip terus-menerus saat cahaya sedang di batas tanggung):
   - `THRESHOLD_HIGH` (misal 100 kcps/spad): Batas untuk mengaktifkan 4x4 (Matahari).
   - `THRESHOLD_LOW` (misal 40 kcps/spad): Batas untuk kembali ke 8x8 (Dalam ruangan).
   *(Catatan: Junior Developer perlu melakukan print/serial monitor nilai rata-rata ini di bawah terik matahari dan di kamar gelap untuk menentukan angka pasti THRESHOLD ini).*
4. Eksekusi trigger jika syarat terpenuhi:
   - Jika rata-rata ambient > `THRESHOLD_HIGH` dan mode sekarang 8x8: 
     Set `tofResolution = 4` dan `tofModeChangePending = true`.
   - Jika rata-rata ambient < `THRESHOLD_LOW` dan mode sekarang 4x4: 
     Set `tofResolution = 8` dan `tofModeChangePending = true`.
5. *Voila!* Kode lama di bawahnya akan secara otomatis menangkap `tofModeChangePending`, me-reset konfigurasi I2C secara aman tanpa me-restart ESP32.

## 2. Rencana Modifikasi Android App (`CameraStreamActivity.kt`)
HP harus bisa bereaksi mandiri terhadap perubahan ukuran data tiba-tiba yang dikirimkan oleh ESP32, dan langsung memperbarui tampilan Grid.

**Langkah-langkah Eksekusi (High-Level):**
1. Pergi ke blok fungsi `startCollectingSensors()` di dalam _coroutine_ `tofCollectJob`.
2. Temukan baris validasi pencegah _crash_ ini: 
   ```kotlin
   if (tofData.size != tofViews.size) { ... }
   ```
3. Modifikasi blok tersebut. Daripada sekadar me-return (mengabaikan data), tambahkan fungsi untuk langsung membangun ulang (rebuild) grid secara dinamis berdasarkan `tofData.size`:
   ```kotlin
   if (tofData.size != tofViews.size) {
       // Deteksi mode dari panjang paket
       val detectedMode = if (tofData.size == 16) 4 else 8
       
       // Update UI dan konfigurasi secara otomatis!
       if (currentTofMode != detectedMode) {
           currentTofMode = detectedMode
           saveTofMode(detectedMode)           // Simpan preferensi HP
           rebuildTofGrid(detectedMode)        // Gambar ulang Grid UI
           updateTofModeButtons(detectedMode)  // Pindahkan indikator biru pada tombol
       }
       
       smoothedTofData = null // Reset filter EMA smoothing
       return@withContext
   }
   ```
