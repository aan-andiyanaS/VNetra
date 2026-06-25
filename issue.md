# Issue / Changelog: Penyesuaian Dataset & Ekstrem Data Augmentation

## Ringkasan Perubahan
Modifikasi ini bertujuan untuk memfokuskan model pada objek yang paling esensial dengan **menghapus 3 kelas yang dirasa kurang diperlukan**, serta menambahkan **simulasi cuaca dan pencahayaan ekstrem** agar model YOLO lebih tangguh *(robust)* terhadap kualitas kamera ESP32 yang buruk (terutama di malam hari dan kondisi sangat silau).

## Rincian Perubahan (Sejak Commit Terakhir)

### 1. Sinkronisasi Model & Pengurangan Class (`YoloDetector.kt`)
- **Penyesuaian `NUM_CLASSES`**: Diturunkan dari **21** menjadi **18** untuk menyesuaikan arsitektur model YOLO terbaru.
- **Pembersihan `CLASSES`**: Menghapus kelas `"open_drain"`, `"puddle"`, dan `"fence"` dari dalam struktur *array* deteksi karena difilter keluar dari *notebook training*.

### 2. Penyesuaian Notebook Training (`train_vnetra_yolo11n_colab.ipynb` & `Kaggle`)
- **Penyaringan Dataset (*Dataset Filtering*)**: 
  - Mengubah susunan `master_classes` dengan menghapus string `"open_drain"`, `"puddle"`, dan `"fence"`.
  - Me-nonaktifkan (*commenting-out*) *cell block* yang bertugas memproses dan melakukan *merging* untuk dataset drain, puddle, dan fence agar tidak terjadi error atau *mismatch* ukuran tensor pada *data.yaml*.
- **Peningkatan *Data Augmentation* Ekstrem**:
  - `hsv_v` *(Value / Kecerahan)*: Ditingkatkan secara signifikan ke **0.8 (80%)**. Hal ini memaksa YOLO untuk secara mandiri menyimulasikan gambar dalam dua kondisi ekstrem: sangat gelap gulita (seperti malam tanpa lampu) dan sangat putih silau *(overexposed)*.
  - `hsv_s` *(Saturation / Saturasi)*: Ditingkatkan ke **0.9 (90%)**. Menyimulasikan gambar yang kehilangan warna aslinya (*washed-out* noise khas ESP32 saat malam) hingga warna yang terlalu mencolok.
- **Integrasi Penuh *Albumentations***: 
  - Menyisipkan instalasi `!pip install -q albumentations` pada blok kode instalasi pertama. Hal ini dilakukan agar *library* Ultralytics YOLOv11 yang sedang berjalan dapat mengaktifkan sistem transformasi berbasis *Albumentations* secara otomatis untuk memaksimalkan efek *blur*, *gray*, dan koreksi cahaya lanjut selama pelatihan berjalan.

### 3. Konfigurasi Sistem Internal Android (`AndroidManifest.xml`)
- Menambahkan baris konfigurasi `android:extractNativeLibs="true"` pada level `<application>`. Perubahan internal ini berfungsi untuk memastikan *native library* dari TensorFlow Lite (`libtensorflowlite_jni.so`) diekstrak ke sistem Android dengan benar tanpa terkena kompresi *(Uncompressed Native Libs)* yang sering kali memicu `NoClassDefFoundError` atau kendala GPU saat inisiasi model di HP modern.

---
**Tindakan Selanjutnya**:
Jalankan *Run All* pada Notebook Kaggle/Colab untuk mulai melatih arsitektur YOLO 18-class dengan setelan augmentasi cuaca ekstrem tersebut. Saat selesai, pindahkan `best.tflite` ke folder `assets` di Android.
