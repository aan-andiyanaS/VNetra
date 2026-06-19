# Laporan Analisis Anomali & Potensi Bug Sistem VNetra

Laporan ini merangkum hasil pemeriksaan komprehensif terhadap seluruh struktur proyek VNetra, meliputi sistem _Firmware_ (ESP32-S3), Aplikasi Android, dan integrasi Model YOLOv11. Beberapa anomali ini memiliki tingkat keparahan dari Kritis (menyebabkan _Force Close_) hingga Sedang/Rendah (mengganggu performa jangka panjang).

---

## 1. [Kritis] Ketidaksesuaian Tensor Output (Shape Mismatch)
* **Status**: 🟢 **Sudah saya perbaiki secara otomatis** di `YoloDetector.kt`.
* **Lokasi**: `app/src/main/java/com/airi/vnetra/model/YoloDetector.kt`
* **Penjelasan**: Notebook Kaggle Anda menghasilkan model dengan 30 kelas (0 hingga 29, kelas terakhir adalah `fence`). Namun, aplikasi Android sebelumnya di- _hardcode_ untuk memproses hanya 29 kelas (kelas `fence` tertinggal).
* **Dampak jika tidak diperbaiki**: Saat Android memuat file `.tflite` Anda dan mencoba menjalankan prediksi (_inference_) pada frame gambar pertama, TensorFlow Lite akan langsung memberikan _Exception_ `IllegalArgumentException: Cannot copy between a TensorFlowLite tensor with shape [1, 34, 8400] and a Java Buffer with shape [1, 33, 8400]`. **Aplikasi akan langsung _Force Close_ (Keluar sendiri) setiap kali ESP32 terkoneksi.**

## 2. [Tinggi] Potensi *Deadlock Inference* AI (AI Berhenti Mendadak)
* **Status**: 🟢 **Sudah diperbaiki** di `CameraStreamActivity.kt`.
* **Lokasi**: `app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt` (Baris ~488)
* **Penjelasan**: Anda menggunakan variabel _flag_ `isInferencing` untuk mencegah penumpukan frame agar HP tidak _lag_. Logikanya adalah:
  ```kotlin
  if (!isInferencing) {
      isInferencing = true
      val results = detector.detect(bitmap) // <-- BAGAIMANA JIKA INI ERROR?
      // ... update UI ...
      isInferencing = false
  }
  ```
  Masalahnya, proses deteksi tidak dibungkus dengan blok pengaman `try...finally`. Jika sesekali `detector.detect(bitmap)` mengalami gangguan kecil (misalnya _memory leak_ sekejap, atau _bitmap_ korup saat dikirim via WiFi), fungsi ini akan melempar _Exception_ dan eksekusi coroutine akan terhenti sebelum mencapai baris `isInferencing = false`.
* **Dampak jika tidak diperbaiki**: Jika terjadi satu saja error sekejap saat prediksi, `isInferencing` akan selamanya bernilai `true`. Akibatnya, **kamera akan tetap berjalan lancar menampilkan jalanan, tetapi kotak hijau (Bounding Box) pendeteksian dan suara AI akan mati/berhenti total** sampai tunanetra me-_restart_ aplikasi secara manual. Ini sangat berbahaya jika terjadi di tengah jalan raya.

## 3. [Sedang] Bahaya Alokasi Ulang Memori PSRAM (Heap Fragmentation)
* **Status**: 🟢 **Sudah diperbaiki** (Pre-alokasi statis permanen diterapkan).
* **Lokasi**: `firmware-vnetra.ino` (Fungsi `captureAndSend()`)
* **Penjelasan**: ESP32 memiliki memori (RAM) yang sangat terbatas. Di baris 519, terdapat logika dinamis di mana jika ukuran file gambar (JPEG) mendadak lebih besar dari memori buffer sementara (`g_wsBuf`), sistem akan menghapus memori lama (`heap_caps_free`) dan meminta blok memori baru yang lebih besar (`heap_caps_malloc`).
* **Dampak jika tidak diperbaiki**: Mekanisme meminta-hapus-minta-hapus memori secara terus menerus (puluhan kali per detik) akan menyebabkan **Heap Fragmentation** (memori berlubang-lubang seperti keju Swiss). Jika perangkat VNetra digunakan selama lebih dari 1 jam tanpa henti, memori tidak akan bisa menemukan blok kosong yang berurutan. ESP32 akan kehabisan memori (_Out of Memory_), gagal mengirim gambar, dan akhirnya akan mengalami **Kernel Panic / Restart otomatis**. Disarankan untuk langsung mengalokasikan ukuran absolut (misal 150KB) sejak ESP32 menyala, tanpa pernah membebaskannya.

## 4. [Sedang] Potensi Kebocoran / Tabrakan Port UDP
* **Status**: 🔴 Perlu diperbaiki jika ada _reconnect logic_.
* **Lokasi**: `firmware-vnetra.ino` (Fungsi `startCameraServer()`)
* **Penjelasan**: Anda memulai server sensor UDP menggunakan perintah `udpSensor.listen(8081)`. Perintah ini dipanggil di dalam fungsi `startCameraServer()`. Walaupun saat ini dipanggil satu kali setelah koneksi WiFi (via BLE), namun jika kelak Anda menambahkan fitur "Auto Reconnect WiFi" pada Firmware ESP32 yang memanggil ulang server, perintah `listen(8081)` akan dijalankan dua kali.
* **Dampak jika tidak diperbaiki**: Sistem AsyncUDP akan _crash_ (LoadStoreError) karena sistem mencoba memaksa membuka port 8081 yang sedang aktif/menggantung. ESP32 akan _Boot Loop_ (Mati hidup terus menerus).

## 5. [Rendah] _Holdover Frame_ Sensor ToF Terlalu Singkat
* **Status**: 🟡 Cukup direkomendasikan untuk diubah nilainya.
* **Lokasi**: `app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt` (Variabel `HOLDOVER_FRAMES = 5`)
* **Penjelasan**: VNetra membaca sensor jarak ToF pada ~10 FPS. Jika sebuah kotak (cell) gagal menangkap inframerah (karena aspal yang terlalu gelap/berair menyerap cahaya), sistem akan menahannya selama 5 frame (0.5 detik) sebelum mengubah layar menjadi "—" (tidak valid).
* **Dampak jika tidak diperbaiki**: Saat tunanetra berjalan cepat di luar ruangan yang aspalnya panas atau memantulkan silau matahari, sensor ToF sering mengalami gagal baca selama 1 hingga 2 detik. Karena nilai _holdover_ hanya 0.5 detik, suara TTS peringatan "Lubang!" atau "Turunan" akan tiba-tiba terputus karena Android langsung menganggap rintangan tersebut hilang seketika, padahal itu hanya kegagalan sensor membaca data. (Saran: Naikkan menjadi `10` atau `15` frame).

---
**Kesimpulan**: 
* Masalah poin ke-1 (Shape Mismatch) yang dapat membuat aplikasi _Force Close_ seketika **sudah diselesaikan**.
* Masalah poin ke-2 (AI Macet / Deadlock Inference) **sudah diperbaiki** menggunakan pengaman `try...catch...finally` dan `NonCancellable` coroutine context untuk memastikan stabilitas sistem asisten tunanetra.
* Masalah poin ke-3 (Fragmentasi Heap PSRAM) **sudah diperbaiki** dengan menerapkan pre-alokasi buffer statis absolut untuk performa optimal dan stabilitas jangka panjang.
