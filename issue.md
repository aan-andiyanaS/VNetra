# Pembaruan Minor: Penghapusan Kelas 'Bench' & Sinkronisasi Aplikasi (Optimasi Kapasitas YOLOv11n)

Berdasarkan hasil evaluasi *training* terbaru, dokumen ini merangkum langkah optimasi lanjutan yang dilakukan pada proyek VNetra. Fokus utama pada iterasi ini adalah penghapusan kelas `bench` secara menyeluruh dari *pipeline* data hingga ke level aplikasi klien Android.

---

## 1. Latar Belakang & Analisis Hasil Evaluasi

Dari log validasi model YOLOv11n sebelumnya, terlihat jelas fenomena **limitasi kapasitas parameter**:
- Kelas dengan jumlah data dominan seperti `person` (19k *instances*) hanya mencapai mAP50 **0.603**.
- Kelas esensial untuk keselamatan jalan seperti `bicycle` (0.456) dan `motorcycle` (0.628) masih *underperforming* karena model kehabisan kapasitas (*representational bottleneck*) akibat noise latar belakang dataset COCO.
- Kelas `bench` memiliki performa yang sangat buruk (**0.468 mAP50**). Bagi tunanetra, bangku bukanlah objek dinamis yang mengancam nyawa. Memaksa model *nano* (2,6M parameter) untuk mempelajari variasi bentuk bangku hanya membuang kapasitas memori yang seharusnya bisa dipakai untuk objek berbahaya.

Atas dasar ini, diputuskan bahwa kelas `bench` dihapus agar parameter model bisa difokuskan ulang ke objek kendaraan (*motorcycle/bicycle*) dan fasilitas tunanetra.

## 2. Pembersihan di Notebooks (Colab & Kaggle)

Berkas *training* (`train_vnetra_yolo11n_colab.ipynb` & `train_vnetra_yolo11n_kaggle.ipynb`) telah dimodifikasi agar bersih dari ketergantungan terhadap kelas `bench`.

- **Penghapusan Dataset Kustom**: Baris kode yang bertugas mengunduh dataset khusus bangku dari Roboflow (`dataset_bench1`, `dataset_bench2`, `dataset_bench3`) dan pemanggilan `merge_dynamic` untuk ketiganya telah dihilangkan.
- **Pembaruan Konfigurasi `merge_dataset`**: Konfigurasi COCO dari Roboflow sekarang murni hanya mengekstrak kelas kendaraan roda dua, diubah menjadi:
  ```python
  coco_rf_mapping = {"motorcycle": "motorcycle", "bicycle": "bicycle"}
  coco_rf_limits = {"motorcycle": 1500, "bicycle": 1500}
  ```
- **Pembaruan Susunan Kelas Dinamis**: Kata `'bench'` telah dicabut dari *array* `master_classes`. Skrip *generator* file `data.yaml` langsung menyesuaikan pergeseran jumlah dan nama kelas tanpa menyebabkan *error*.
- Perbaikan *markdown/comment* terkait agar dokumentasi *notebook* tetap rapi dan relevan.

## 3. Sinkronisasi Kode Aplikasi Android

Perubahan struktur deteksi wajib diiringi dengan penyesuaian kode pada klien (*inference parser*) Android, jika tidak akan timbul *IndexOutOfBoundsException* atau nama kelas yang tertukar.

- **Berkas `YoloDetector.kt`**: 
  1. Daftar objek dibersihkan total. Kelas usang (`train`, `pothole`, dan sekarang `bench`) dihapus dari konstanta array `CLASSES`.
  2. Konstanta kelas diperbarui secara eksplisit: `private const val NUM_CLASSES = 14`.
  3. Aplikasi klien VNetra sekarang hanya berfokus mendeteksi 14 kategori utama yang paling esensial untuk keselamatan dan navigasi tunanetra.
- Berkas manajemen suara (`TtsAlertManager.kt`) secara *native* mewarisi penyaringan ini, sehingga pengguna VNetra tidak akan lagi menerima peringatan suara *spam* tentang "bangku".

---

**Kesimpulan:**
Dengan 14 kelas final ini, YOLOv11n diharapkan memiliki "ruang berpikir" yang jauh lebih lega. Pada eksperimen pelatihan berikutnya (terutama saat jumlah *person* dibatasi ke 6.000 dan *car* ke 3.000), mAP rata-rata model sangat berpotensi melonjak melewati 0.85+.
