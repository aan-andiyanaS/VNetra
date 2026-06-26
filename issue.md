# Pembaruan Utama: Optimasi Pipeline Dataset & Tuning Model VNetra

Dokumen ini mencatat serangkaian pembaruan krusial pada pipeline proyek VNetra. Pembaruan ini difokuskan pada penyempurnaan komposisi kelas deteksi, modifikasi logika penggabungan (*merging*) dataset agar lebih presisi dan anti-bentrok, serta penyesuaian parameter augmentasi untuk mendongkrak akurasi model di dunia nyata.

Semua perubahan ini telah diintegrasikan sepenuhnya ke dalam lingkungan *training* utama (`train_vnetra_yolo11n_colab.ipynb` dan `train_vnetra_yolo11n_kaggle.ipynb`).

---

## 1. Modifikasi Logika Fungsi `merge_dataset`
Fungsi `merge_dataset` dirombak agar mampu menangani pemotongan dataset (capping) yang lebih fleksibel. 

- **Penambahan Parameter Baru (`max_samples_per_class`)**:  
  Sebelumnya, batas sampel hanya bisa diatur secara global (`max_instances_per_class_total`). Sekarang fungsi ini menerima *dictionary* yang memungkinkan pembatasan spesifik untuk masing-masing kelas.
- **Pemisahan Konteks `instance_counter`**:  
  Variabel penghitung sampel sekarang disalurkan dari luar fungsi. Hal ini mencegah bentrok (catastrophic overlap) ketika menggabungkan dua dataset COCO dari sumber yang berbeda (misalnya COCO dari FiftyOne dan COCO dari Roboflow).
- **Pengembalian Variabel Kuota FiftyOne**:  
  Variabel kuota kelas utama untuk dataset COCO bawaan (FiftyOne) dikembalikan ke angka aslinya:
  ```python
  person_cap = 12500 # <-- Variabel khusus kelas person
  car_cap = 4000     # <-- Variabel khusus kelas car
  ```

## 2. Integrasi Tambahan COCO dari Roboflow
Sebuah blok baru ditambahkan khusus untuk menyerap dataset ekstra (COCO versi Roboflow) guna memperkaya variasi *instance* dari 3 kelas krusial, tanpa mengganggu batas dataset COCO FiftyOne.

- Target Kelas: `motorcycle`, `bicycle`, dan `bench`
- Batasan *Strict Cap*: Masing-masing kelas ini dibatasi ketat agar hanya mengambil **maksimal 1.500 tambahan sampel**.
- Cuplikan Implementasi:
  ```python
  coco_rf_counter = {} # Counter terpisah
  coco_rf_mapping = {"motorcycle": "motorcycle", "bicycle": "bicycle", "bench": "bench"}
  coco_rf_limits = {"motorcycle": 1500, "bicycle": 1500, "bench": 1500}
  
  merge_dataset(dataset_coco_rf.location, coco_rf_mapping, max_samples_per_class=coco_rf_limits, instance_counter=coco_rf_counter)
  ```

## 3. Penghapusan Kelas `pothole` dan `train`
Untuk meringankan beban deteksi dan lebih memfokuskan atensi model pada hal-hal yang esensial bagi tunanetra, dua buah kelas dihapus secara permanen dari daftar deteksi model.

- **Kelas Pothole (Jalan Berlubang)**: 
  - Baris *download* dataset dari Roboflow dimatikan (`# dataset_pothole = rf.workspace...`).
  - Baris eksekusi penggabungan (pemanggilan `merge_dataset` untuk *pothole*) dihilangkan.
  - Nama kelas `'pothole'` dihapus dari *array* `master_classes`.
- **Kelas Train (Kereta Api)**: 
  - Nama kelas `'train'` dicabut dari *array* `master_classes`.
  - Pada pemetaan dictionary dataset bawaan (`custom_coco`), kunci `"train": "train"` dihapus. Ini memaksa fungsi untuk mengabaikan segala *bounding box* kereta dari data COCO.
- **Efek pada Model**: Total jumlah kelas yang semula ada 16, kini secara dinamis tercatat **hanya 14 kelas utama** (`nc=14`) di dalam `data.yaml`.

## 4. Penyesuaian Hyperparameter Augmentasi YOLO
Augmentasi bawaan dari kode sebelumnya sangatlah ekstrem (dimaksudkan untuk simulasi noise kamera OV2640), tetapi disinyalir terlalu merusak bentuk asli objek sehingga memperburuk akurasi. Parameter tersebut kini dihaluskan agar menjadi **lebih natural**.

- `degrees`: Diturunkan dari **15.0 menjadi 10.0** (Miring wajar, tanpa membahayakan objek tiang atau pejalan kaki).
- `scale`: Dinaikkan dari **0.3 menjadi 0.5** (Kembali ke default YOLO agar *zoom-in/out* tidak memotong objek secara brutal).
- `hsv_s`: Saturation diturunkan dari **0.9 menjadi 0.5** (Warna tidak terlalu pucat/kontras secara ekstrem, garis marka masih jelas).
- `hsv_v`: Value/Brightness diturunkan dari **0.8 menjadi 0.4** (Simulasi terang redup diturunkan ke level menengah).
- `erasing`: Efek penghapusan sebagian gambar acak diturunkan tajam dari **0.3 menjadi 0.1** (Agar area penting seperti *Tactile Paving* tidak tertutup sepenuhnya oleh balok hitam augmentasi).

## 5. Perubahan Lain-Lain (Minor Fixes)
- Memperbaiki `max_samples` untuk pemanggilan dataset *Crosswalk* menjadi `900`.
- Menghapus pembatasan `max_samples=3000` dari dataset tangga `dataset_stairs2`.
- Menambahkan ikon peringatan `🚨` pada *print statement* darurat untuk indikator pengingat sisa waktu (kuota runtime) sesi Kaggle/Colab.
