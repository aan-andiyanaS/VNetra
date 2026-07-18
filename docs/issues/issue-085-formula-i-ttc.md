# ADR-039: Implementasi Formula I (Time-to-Collision) dan Filter Lateral Drift


## Latar Belakang Masalah
Berdasarkan investigasi pada sensor ToF (VL53L5CX), ditemukan bahwa sensor mengalami kebutaan (*blind spot*) pada jarak > 1 meter saat digunakan di luar ruangan (siang hari terik) karena interferensi inframerah dari matahari.
Oleh karena itu, sistem membutuhkan *fallback* berbasis *Computer Vision* murni untuk memprediksi ancaman tabrakan pada jarak 1 - 20 meter.

## Penjelasan Perubahan yang Dilakukan (Uncommitted Changes)

1. **Penghapusan `CameraDepthEstimator.kt`**
   - Menghapus estimator lama yang kurang akurat.

2. **Implementasi `TtcManager.kt` (Formula I v9.4)**
   - Menggabungkan teori ekologi David N. Lee ($\tau = 2A / \dot{A}$) dan Hartley-Zisserman.
   - Menghitung ancaman (TTC) menggunakan 3 komponen utama:
     - `areaScore` (50%): Pembesaran kotak (*Bounding Box Expansion*).
     - `arScore` (25%): Stabilitas rasio aspek (menolak alarm palsu dari objek yang berputar/berubah pose).
     - `distScore` (25%): Validasi konsistensi dari sensor ToF (jika ToF tidak buta).

3. **Penerapan Bobot Bahaya (*Class Weights* / $m_{class}$)**
   - Mengkalibrasi peringatan berdasarkan *Kinetic Energy* dan *Stopping Distance* kendaraan.
   - Truk/Bus (x1.6), Mobil (x1.5), Motor (x1.2), Orang (x1.0). Hal ini memastikan kendaraan berat dan cepat memicu peringatan jauh lebih dini.

4. **Integrasi ke `CameraStreamActivity.kt`**
   - Menghubungkan `TtcManager` ke alur deteksi YOLO.
   - Jika status mencapai `IMMINENT` (skor > 0.75), sistem secara paksa menimpa jarak ToF (dijadikan 1mm) agar sistem *Text-to-Speech* (TTS) langsung berteriak memberikan peringatan.

5. **Penambahan Filter *Lateral Drift* (Mengatasi Keraguan \#1)**
   - **Masalah:** Mobil yang sekadar melintas secara menyamping (kiri ke kanan) memotong area pandang (jam 12) akan terdeteksi membesar dan berpotensi memicu alarm palsu.
   - **Solusi:** Menambahkan pelacakan sumbu X pusat kotak (`lastCx`). Jika objek bergeser secara horizontal melebihi 20% dari lebarnya per *frame*, sistem mengenalinya sebagai kendaraan yang melintas menyamping (*passing by*) dan langsung menjatuhkan skor ancamannya (dikali 0).

6. **Pembuatan Dokumentasi Ramah Pengguna**
   - Membuat dan melengkapi `docs/penjelasan-formula-I.md` untuk menjelaskan cara kerja sistem tanpa rumus matematis yang rumit, menjabarkan konsep 4 Pertanyaan (Pelebaran, Bentuk, Jarak, dan *Lateral Drift*).

## Status Saat Ini
Kode telah dimodifikasi, *build* Gradle sukses tanpa *error*, dan perubahan ini siap untuk di-*commit*.
