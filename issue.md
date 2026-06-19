# Pemisahan Pipeline Training & Kuantisasi TPU, Optimasi Dataset, dan Sinkronisasi Aplikasi Android

Dokumen ini merangkum seluruh modifikasi arsitektur dan pembersihan kode yang terjadi pada proyek **VNetra** sejak titik *commit* `a5766d88f79148161f27e25190d2e5b84ab64737`.

## 1. Pemecahan Arsitektur Hybrid (Kaggle & Colab TPU)
*   **Kaggle (Mesin Latih GPU):** Notebook `train_vnetra_yolo11n_kaggle.ipynb` kini sepenuhnya didedikasikan untuk *training* menggunakan 2x T4 GPU. Seluruh beban kerja INT8 dan sel komparasi `model.val()` telah dihapus total.
*   **Colab (Mesin Kuantisasi Khusus):** Telah ditambahkan notebook baru `quantize_vnetra_int8_colab.ipynb` yang bertugas me-*load* bobot dari Google Drive, mengekspor `.pt` menjadi `FP16` dan `INT8` (memanfaatkan mesin TPU host-CPU raksasa), dan menghasilkan otomatis 3 Grafik Batang Profesional (Akurasi, ms, MB) yang siap digunakan untuk Laporan Skripsi.
*   **Colab Training:** Ditambahkan versi turunan `train_vnetra_yolo11n_colab.ipynb` dengan konfigurasi parameter memori yang disesuaikan untuk Colab standar.

## 2. Optimasi Mesin Pelatih (Kaggle Notebook)
*   **Penghapusan Kelas Speed Bump:** Kelas `speed_bump` secara resmi dicabut karena dinilai kurang relevan dengan rintangan navigasi tunanetra. Semua sel yang terkait (API *download*, kode *merge dataset*) telah dilenyapkan.
*   **Penyeimbang Kelas (*Dataset Limiting*):** Menyuntikkan parameter pencekik `max_samples=2500` ke dalam modul logika fungsi `merge_dataset` untuk mencegah data "Pohon" (Tree) mendominasi distribusi kelas.
*   **Batas Waktu Kaggle (*Graceful Stop*):** Parameter rahasia `time=11.0` telah dilekatkan ke pemanggilan `model.train()`. Hal ini menjamin bahwa pelatih akan dipaksa berhenti (*safe commit*) 1 jam lebih awal sebelum batas 12-Jam mesin gratisan Kaggle me-reset semua progres *training* semalaman Anda.
*   **Paket ZIP Skripsi:** Menambahkan sebuah sel baru paling bawah khusus untuk Kaggle. Ia akan membungkus seluruh luaran file latihan (Grafik Tensorboard, Confusion Matrix, kurva, dan `best.pt`) ke dalam `vnetra_training_results.zip` tanpa menyertakan dataset bergigabyte, mengizinkan Anda *download* dalam hitungan detik.

## 3. Sinkronisasi Aplikasi Android
*   **File Modifikasi:** `app/src/main/java/com/airi/vnetra/model/YoloDetector.kt`
*   Menurunkan jumlah konstanta patokan `NUM_CLASSES` dari 30 menjadi 29.
*   Menghapus elemen `"speed_bump"` dari dalam daftar panjang *array* string kelas deteksi untuk mensinkronkan model TFLite terbaru yang dihasilkan dengan sistem deteksi internal aplikasi.
