# Rencana Sinkronisasi Kelas YOLO (VNetra Android App vs Colab Notebook)

Berdasarkan pengecekan `/code-review-and-quality` terhadap struktur kelas di aplikasi Android dan konfigurasi dataset pada file `notebooks/dataset_vnetra_yolov11n_colab.ipynb`, ditemukan ketidaksesuaian (*mismatch*) jumlah dan urutan kelas YOLO.

## 🔍 Temuan Analisis

1. **Dataset Master Notebook (`dataset_vnetra_yolov11n_colab.ipynb`)**
   Terdapat **14 kelas** (Index 0 - 13):
   `person`, `car`, `motorcycle`, `bus`, `pole`, `tactile_paving_straight`, `tactile_paving_turn`, `tactile_paving_3way`, `tactile_paving_4way`, `tactile_paving_stop`, `stairs_up`, `stairs_down`, `crosswalk`, `tree`.
   *Kelas `bicycle` telah direduksi/diabaikan saat perangkaian COCO di dataset.*

2. **Aplikasi Android (`YoloDetector.kt` & `CameraDepthEstimator.kt`)**
   Terdapat **15 kelas**. Kelas `bicycle` masih terdaftar di urutan ke-1 (setelah `person`), yang menyebabkan semua urutan kelas setelahnya bergeser (Meleset 1 index). Hal ini sangat fatal karena pembacaan *bounding box* akan meleset (misal model mendeteksi `car` di index 1, tetapi Android membacanya sebagai `bicycle`).

## 🛠️ Rencana Perbaikan (Implementation Plan)

### 1. `app/src/main/java/com/airi/vnetra/model/YoloDetector.kt`
- Mengubah konstanta `NUM_CLASSES` dari `15` menjadi `14`.
- Menghapus `"bicycle"` dari _array_ `CLASSES`.
- Memastikan urutan *array* persis 100% sama dengan urutan di _notebook_:
  ```kotlin
  val CLASSES = arrayOf(
      "person", "car", "motorcycle", "bus", "pole",
      "tactile_paving_straight", "tactile_paving_turn", 
      "tactile_paving_3way", "tactile_paving_4way", "tactile_paving_stop", 
      "stairs_up", "stairs_down", "crosswalk", "tree"
  )
  ```

### 2. `app/src/main/java/com/airi/vnetra/util/CameraDepthEstimator.kt`
- Menghapus _entry_ `"bicycle" to 1000f` dari map `CLASS_HEIGHTS_MM`.

## 📌 Langkah Selanjutnya
Mohon konfirmasi agar saya dapat langsung mengeksekusi sinkronisasi ini. Mengingat efek dominonya pada pembacaan index TFLite, *fix* ini sifatnya kritis sebelum aplikasi digunakan untuk inferensi.
