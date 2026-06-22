# Rangkuman Perubahan (Uncommitted Changes vs Commit 5e90c21)

Dokumen ini merangkum seluruh perubahan dan penambahan baru yang terjadi pada *working directory* sejak *commit* terakhir `5e90c21c0da659c417c75789c538d21cda86e81c`.

## 1. Reduksi Kelas Objek (29 Kelas ➡️ 23 Kelas)
Sistem sekarang disederhanakan untuk lebih fokus pada objek rintangan kritis tunanetra.
*   **Kelas yang dihapus:** `stop sign`, `chair`, `potted plant`, `dog`, `cat`, dan `curb`.
*   **Penambahan Dataset Eksternal:** Menambahkan 3 sumber dataset baru untuk kelas `bench` secara eksplisit (`dataset_bench1` hingga `dataset_bench3`).
*   **Aplikasi Android (`YoloDetector.kt`):**
    ```kotlin
    // Perubahan pada konstanta jumlah kelas dan array CLASSES
    private const val NUM_CLASSES = 23
    val CLASSES = arrayOf(
        "person", "bicycle", "car", "motorcycle", "bus", "truck", "train", 
        "bench", "pothole", "open_drain", "puddle", "pole", ... // total 23
    )
    ```

## 2. Penyempurnaan Proporsi Dataset (Hybrid Rebalancing)
Skrip di Kaggle dan Colab (`train_vnetra_yolo11n_*.ipynb`) telah dirombak untuk distribusi dataset yang lebih adil dan mencegah model menghafal data.
*   **Distribusi Validasi & Test yang Seimbang:** Logika lama hanya meminjam data dari *Train* ke *Valid*. Kini algoritma memastikan *Valid* dan *Test* mendapatkan jatah data yang berimbang (Target 50 gambar per kelas).
*   **Fallback Dinamis (15%):** Jika total gambar di suatu kelas kurang dari 250, algoritma akan mengambil 15% dari total gambar alih-alih memaksa 50 gambar.
*   **Strict Capping (Pengembalian Kelebihan):** Jika gambar di *Valid/Test* terlalu banyak, kelebihannya akan didorong kembali ke folder *Train*.
*   **Evaluasi Eksplisit pada Test Set:** Pengujian model kini menargetkan *split test* secara spesifik.
    ```python
    # Evaluasi akurasi diarahkan ke test set
    val_pt = model.val(data=f"{master_dir}/data.yaml", split='test')
    ```

## 3. Peningkatan Parameter Pelatihan (Training Enhancements)
Terdapat optimasi signifikan pada hyperparameter pelatihan YOLO11n agar lebih tahan terhadap goyangan kamera dan tidak melupakan dataset COCO.
*   **Pencegahan COCO Amnesia:** Menambahkan parameter `freeze=5` untuk membekukan 5 *layer* pertama (backbone) agar model tidak mengalami *catastrophic forgetting*.
*   **Augmentasi Kamera OV2640:**
    *   `fliplr=0.0`: Dimatikan (Flip Horizontal = 0) agar kamera tidak keliru dalam mengartikan rambu ubin taktil belok kanan/kiri.
    *   `scale=0.3`: Dikurangi agar mensimulasikan objek pada sudut pandang kamera *wearable* di dada.
    *   `erasing=0.3`: Mensimulasikan benda yang tertutup benda lain (oklusi) atau *motion blur*.
*   **Optimalisasi Hardware:** Penambahan parameter `workers=4` (Colab) / `workers=8` (Kaggle), dan peningkatan jumlah *batch* (hingga `100` di Kaggle) agar pemakaian GPU T4 ganda lebih maksimal.
*   **Keamanan Eksekusi:** Penambahan `exist_ok=True` (menimpa folder lama) dan `save_period=10` (auto-save *checkpoint* model tiap 10 epoch).

## 4. Validasi Kuantisasi & Visualisasi (Benchmarking Skripsi)
Pada bagian akhir *notebook* Colab/Kaggle dan skrip kuantisasi, ditambahkan dua tahapan khusus untuk keperluan analisis laporan skripsi:
*   **Evaluasi Kuantisasi:** Komparasi langsung mAP@50 antara Model Original FP32 (`.pt`) dan Model TFLite Kuantisasi (`.tflite` FP16/INT8) menggunakan Test Set.
*   **Visualisasi (Predict):** Pengambilan 1 gambar acak dari Test Set untuk memplotkan hasil prediksi model asli bersanding dengan hasil prediksi model FP16 secara bersebelahan (*side-by-side* memakai *matplotlib*).

## 5. Penyesuaian Path Penyimpanan Hasil
Direktori keluaran (output runs) YOLO disesuaikan ke standar Ultralytics terbaru:
```python
# Sebelum
shutil.copy('vnetra_training/yolo11n_custom/weights/best.pt', ...)

# Sesudah
shutil.copy('runs/detect/vnetra_training/yolo11n_custom/weights/best.pt', ...)
```

## 6. Penambahan Dokumen Saran Pengembangan Baru (`saran.md`)
Sebuah dokumen baru bernama `saran.md` telah ditambahkan ke dalam repositori (berstatus *untracked file*). Dokumen ini berisi cetak biru gagasan masa depan untuk menyempurnakan VNetra, yang meliputi:
*   **Segmentasi Semantik (Semantic Segmentation):** Rencana transisi menggunakan `yolo11n-seg.pt` untuk mendeteksi kontur trotoar secara asimetris.
*   **Estimasi Kedalaman (Depth Estimation):** Potensi integrasi *Depth Anything V2 Small* untuk mengukur jarak lubang atau halangan.
*   **Multi-Task Pipeline:** Menjalankan dua inferensi (YOLO deteksi dan segmentasi) secara paralel bergantian di GPU Mobile dalam *budget* <100ms.
*   **Perbaikan Dataset & Model:** Arahan untuk memperbanyak data kelas sulit (seperti `bench` dan `truck`) serta ide untuk beralih ke YOLO11s atau melatih dengan metode Quantization-Aware Training (QAT).
*   **Pengembangan Perangkat Keras:** Usulan penggunaan kamera OV5640 30FPS, Raspberry Pi, atau integrasi sensor ultrasonik independen.
*   **Kontekstualisasi Lokal:** Rekomendasi untuk mendirikan *dataset* spesifik lingkungan jalanan Indonesia beserta kolaborasi uji lapangan bersama tunanetra.

## 7. Pembaruan Konfigurasi Minor
*   **IDE Configuration:** Terdapat modifikasi minor otomatis pada berkas konfigurasi Android Studio (`.idea/misc.xml`) berupa penghapusan deklarasi header XML standar, yang tidak memengaruhi logika aplikasi secara keseluruhan.
