# Plan Perbaikan: Type Mismatch `esp_camera_sensor_t` vs `sensor_t` (Lanjutan)

## Deskripsi Masalah (Bug)
Setelah menerapkan solusi *macro manipulation* (`#define sensor_t esp_camera_sensor_t`) pada saat meng-include `esp_camera.h` di tahap sebelumnya, muncul *compilation error* baru:
`cannot convert 'esp_camera_sensor_t*' {aka '_sensor*'} to 'sensor_t*' in initialization`

Penyebabnya adalah:
1. Fungsi `esp_camera_sensor_get()` dari library kamera sekarang mengembalikan tipe data `esp_camera_sensor_t*` (karena nama aslinya telah kita ganti untuk menghindari konflik).
2. Di dalam fungsi-fungsi pada `firmware-vnetra.ino` (terutama di baris ~272 dan ~323), kita masih mendeklarasikan variabel penampungnya menggunakan `sensor_t* s = esp_camera_sensor_get();`.
3. Karena `#undef sensor_t` telah dipanggil sebelumnya, tipe `sensor_t` di sisa file tersebut kembali merujuk pada `sensor_t` milik library `Adafruit_Sensor`. Sehingga, terjadi ketidakcocokan tipe (*type mismatch*) antara kembalian fungsi kamera dan deklarasi variabel.

## Solusi (High-Level)
Untuk menyelesaikan masalah ini, kita hanya perlu menyesuaikan deklarasi tipe data untuk variabel kamera di dalam file `firmware-vnetra.ino`. Setiap kali kita memanggil `esp_camera_sensor_get()`, kita harus menggunakan tipe `esp_camera_sensor_t*` (atau cukup gunakan keyword `auto`) alih-alih `sensor_t*`.

## Instruksi Eksekusi (Untuk Junior Developer / AI Model)

1. Buka file utama firmware: `E:\Project\Skripsi\VNetra\firmware-vnetra\firmware-vnetra\firmware-vnetra.ino`.
2. Temukan fungsi `initCamera()` (sekitar baris 272).
3. Cari baris kode berikut:
   ```cpp
   sensor_t* s = esp_camera_sensor_get();
   ```
4. Ubah baris tersebut menjadi:
   ```cpp
   esp_camera_sensor_t* s = esp_camera_sensor_get();
   ```
5. Temukan juga fungsi `onWsEvent()` (sekitar baris 323).
6. Cari baris kode berikut:
   ```cpp
   sensor_t* s = esp_camera_sensor_get();
   ```
7. Ubah baris tersebut menjadi:
   ```cpp
   esp_camera_sensor_t* s = esp_camera_sensor_get();
   ```
8. (Opsional / Alternatif) Anda juga bisa menggunakan keyword `auto` pada kedua baris tersebut, contohnya: `auto* s = esp_camera_sensor_get();`.
9. Simpan file `firmware-vnetra.ino`.
10. Lakukan kompilasi ulang (Verify).

## Kriteria Selesai (Definition of Done)
1. Firmware berhasil dikompilasi tanpa memunculkan error `cannot convert 'esp_camera_sensor_t*' to 'sensor_t*'`.
2. Tidak ada error terkait "*struct has no member named...*" yang muncul saat melakukan konfigurasi kamera (`set_whitebal`, `set_exposure_ctrl`, dll).
