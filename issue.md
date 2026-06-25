# Dokumentasi Pembaruan Sistem: Sinkronisasi Deteksi, Manajemen Eksperimen, dan Keamanan Training

Berikut adalah laporan mendetail mengenai pembaruan dan optimasi yang telah diimplementasikan pada arsitektur sistem terbaru. Dokumentasi ini berfokus pada penyelesaian isu sinkronisasi kelas Android, pencegahan kegagalan sesi training, dan perbaikan struktur manajemen eksperimen, lengkap dengan cuplikan kode (*code snippets*) operasional.

---

## 1. Perbaikan Bug Sinkronisasi Kelas di Android (`YoloDetector.kt`)
**Masalah:** Susunan kelas di Android tidak sinkron dengan model Python (misal: ID `0` di Python adalah `person`, tapi di Android ditulis `pothole`).
**Solusi:** Menyusun ulang variabel `val CLASSES` agar urutan *array* persis 100% mengikuti `master_classes` dari proses *training*.
**File yang diubah:** `app/src/main/java/com/airi/vnetra/model/YoloDetector.kt`

```kotlin
// [SEBELUMNYA] Urutan acak-acakan yang menyebabkan salah deteksi:
val CLASSES = arrayOf(
    "pothole", "tactile_paving_straight", "tactile_paving_turn", ...
)

// [SESUDAHNYA] Urutan disinkronisasi 100% dengan master_classes Python:
val CLASSES = arrayOf(
    "person", "bicycle", "car", "motorcycle", "train", "bench", "pothole", 
    "open_drain", "puddle", "pole", "hanging_branch", "tactile_paving_straight", 
    "tactile_paving_turn", "tactile_paving_3way", "tactile_paving_4way", 
    "tactile_paving_stop", "stairs_up", "stairs_down", "crosswalk", "tree", "fence"
)
```
*(Catatan Tambahan: Folder `app/src/main/assets/` juga dibuat untuk menampung model `best_fp16.tflite` agar otomatis dibaca aplikasi).*

---

## 2. Peningkatan Keamanan Waktu Training (Rem Darurat)
**Masalah:** Server Kaggle/Colab sering *timeout* (mati paksa) sehingga merusak file `last.pt` dan membatalkan pembuatan grafik hasil evaluasi.
**Solusi:** Menambahkan mekanisme "Rem Darurat" (*Callback*) yang menghitung mundur waktu aktif server.
**File yang diubah:** `notebooks/train_vnetra_yolo11n_kaggle.ipynb` & `notebooks/train_vnetra_yolo11n_colab.ipynb`

```python
# [DITAMBAHKAN] Kode pengaman di sel training:
import time

def alarm_kuota(trainer):
    # Jika waktu aktif sudah melebihi 1,5 jam (5400 detik) atau 11 jam (Kaggle)
    if time.time() - trainer.train_time_start > 5400:  
        print("🚨 ALARM: Sisa kuota hampir habis! Menyimpan progress...")
        trainer.stop = True # Memaksa YOLO berhenti secara elegan (graceful exit)

# Mendaftarkan penjaga waktu ke sistem YOLO
model.add_callback("on_train_epoch_end", alarm_kuota)
```

---

## 3. Penambahan Fitur Resume Training Otomatis
**Masalah:** Me-*resume* *training* menggunakan skrip utama terkadang merusak sistem pembacaan *epoch* jika dibarengi dengan penggunaan argumen `time=...`.
**Solusi:** Memisahkan skrip khusus murni untuk me-*resume* sesi dari `last.pt`.
**File baru:** `notebooks/resume_vnetra_yolo11n_kaggle.ipynb` & `notebooks/resume_vnetra_yolo11n_colab.ipynb`

```python
# [DIBUAT BARU] Mekanisme utama di dalam notebook resume:
model_path = f'{model_dir}/yolo11n_custom/weights/last.pt'

# 1. Muat checkpoint terakhir
model = YOLO(model_path)

# 2. Pasang kembali alarm rem darurat untuk sesi lanjutan
model.add_callback("on_train_epoch_end", alarm_kuota)

# 3. Lanjutkan training tanpa merusak target epochs asli
results = model.train(resume=True)
```

---

## 4. Manajemen Direktori Eksperimen Terpusat
**Masalah:** Skrip lama menyimpan file sembarangan di *root* Google Drive (`/content/drive/MyDrive/`), menyebabkan penumpukan jika ada beberapa eksperimen.
**Solusi:** Menambahkan variabel kontrol terpusat (`EXPERIMENT_ID`) untuk me-*routing* otomatis semua file.
**File yang diubah:** `notebooks/quantize_vnetra_int8_colab.ipynb`

```python
# [DITAMBAHKAN] Manajemen folder terpusat di awal notebook:
EXPERIMENT_ID = 1

DRIVE_BASE_DIR = f'/content/drive/MyDrive/YOLO/eksperimen_{EXPERIMENT_ID}'
INPUT_DIR = f'{DRIVE_BASE_DIR}/input'
OUTPUT_DIR = f'{DRIVE_BASE_DIR}/output'

os.makedirs(INPUT_DIR, exist_ok=True)
os.makedirs(OUTPUT_DIR, exist_ok=True)

# Contoh implementasinya untuk ekspor TFLite:
# int8_drive_path = f'{OUTPUT_DIR}/best_int8.tflite'
```

---
**Catatan untuk Commit:**
Pastikan seluruh file _untracked_ di folder `notebooks/` (terutama seri `resume`) dan folder `assets/` ikut di-_stage_ (`git add .`) sebelum melakukan _commit_ agar mekanisme _safe-resume_ dan folder arsitektur tersimpan di Git.
