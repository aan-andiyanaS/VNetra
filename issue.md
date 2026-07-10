# Refaktor Arsitektur Notebook: Perbaikan Syntax, Renaming, & Isolasi Kuantisasi INT8

Berikut adalah ringkasan perubahan (perbandingan) antara *working directory* saat ini dengan commit `1fed77e00c6c343f4b1a0653aed169ddae6fbc3d`:

### 1. Pembersihan dan Penamaan Ulang (Renaming)
Sebagian besar notebook lama yang menumpuk dan memiliki inkonsistensi penamaan telah dihapus dan digantikan dengan format standar baru (menambahkan huruf `v` pada `yolov11n`).
* **Dihapus:** `train_vnetra_yolo11n_colab.ipynb`, `train_vnetra_yolo11n_kaggle.ipynb`, `quantize_vnetra_int8_colab.ipynb`, dan beberapa file *manual* atau cadangan (*resume*) yang sudah tidak relevan.
* **Ditambahkan (dengan versi baru):** `dataset_vnetra_yolov11n_colab.ipynb`, `train_vnetra_yolov11n_colab.ipynb`, dan `train_vnetra_yolov11n_kaggle.ipynb`.

### 2. Perbaikan Fatal `SyntaxError` di JSON Jupyter
Sebelumnya, notebook rusak di tingkat struktur JSON akibat adanya kesalahan *escape character* (literal `\n` terbaca sebagai `\ ` dan `n` di Python). Ini menyebabkan `SyntaxError` saat dijalankan.
* **Solusi:** Seluruh blok kode yang rusak diganti ulang secara bersih. `print("\n===...")` diperbaiki sehingga Python `ast.parse` dapat mengkompilasi file tanpa error (TDD pass 100%).

### 3. Optimasi Fungsi `merge_dataset` (Ponytail Mode)
Kode penyaringan dataset YOLO disederhanakan drastis.
* **Diubah:** Mengganti perulangan panjang saat membaca file `.txt` YOLO menjadi implementasi himpunan (`Set`) satu baris yang ringan di RAM:
  ```python
  lines = open(lbl_path).readlines()
  classes_in_img = {str(original_classes[int(p.split()[0])]).lower() 
                    for p in lines if p.strip() and int(p.split()[0]) < len(original_classes)}
  if classes_in_img.intersection({'motorcycle', 'bicycle', 'bus', 'truck'}):
      score += 100
  ```
* Logika *Smart Sort*, *Distance Filter*, dan *Spatial Passenger Filter* kini sepenuhnya konsisten dan dipakai secara seragam di Kaggle dan Colab.

### 4. Pemisahan Kuantisasi INT8
Kuantisasi `INT8` ditarik keluar secara total dari notebook *training* untuk mencegah *Catastrophic Failure* akibat kehabisan memori saat kalibrasi paska-latih.
* **Dihapus:** Blok kode `model.export(format="litert", int8=True)` dari seluruh notebook training.
* **Ditambahkan:** File baru `quantize_vnetra_yolov11n_int8_colab.ipynb` khusus untuk Kuantisasi INT8.

### 5. Notebook Kuantisasi Baru yang Jauh Lebih Interaktif
File `quantize_vnetra_yolov11n_int8_colab.ipynb` kini memiliki alur *(pipeline)* yang sangat terstruktur:
* **Ekstraksi Otomatis:** Menambahkan sel ekstraksi `zipfile` dari Google Drive agar dataset siap dipakai kalibrasi INT8.
* **Tabel Distribusi Pandas:** Menyisipkan kode untuk membaca `data.yaml` dan mencetak tabel DataFrame pembagian proporsi dataset sebelum melakukan kuantisasi.
* **Dokumentasi Bawaan:** Menyisipkan instruksi berformat *Markdown* berbahasa Indonesia pada setiap awal sel untuk menjelaskan fungsinya.
* **Visualisasi Matplotlib:** Menambahkan *bar chart* komparatif di akhir eksekusi untuk secara visual membandingkan nilai **Akurasi (mAP@50)** antara:
  - Model Original (.pt)
  - Model LiteRT (.tflite FP32)
  - Model LiteRT (.tflite INT8) 
  
  ```python
  bars = plt.bar(labels, values, color=['#1f77b4', '#ff7f0e', '#2ca02c'])
  #...
  plt.title('Perbandingan mAP@50: Original vs FP32 vs INT8')
  ```
