# Notebook Baru: Penambahan Kelas `vehicle` via Fine-Tuning (Transfer Learning)

## Latar Belakang & Keputusan Desain (ADR)

### Fakta: Model Sudah Dilatih dengan Hasil Luar Biasa

| Metrik | Nilai |
|---|---|
| mAP@50 Original (.pt) | **93.7%** |
| mAP@50 FP32 (.tflite) | **91.6%** |
| mAP@50 INT8 (.tflite) | **89.1%** |
| Epoch | 258 epoch (3.3 jam) |
| Kelas | 10 kelas navigasi tunanetra |

---

## Jawaban: Bisakah Menambah Kelas Tanpa Training Ulang dari 0?

**YA, BISA.** Tekniknya disebut **Incremental Fine-Tuning** dengan *class-aware transfer learning*.

> [!IMPORTANT]
> **Perubahan Arsitektur:** Menambah kelas dari 10 menjadi 11 berarti output head YOLO berubah ukurannya. Ultralytics menangani ini otomatis saat `model = YOLO('best.pt')` dilatih dengan `data.yaml` baru yang berisi 11 kelas.

> [!WARNING]
> **Catastrophic Forgetting** adalah risiko terbesar. Mitigasi wajib: `freeze=10`, `lr0=0.0005`, dan dataset navigasi lama **harus ikut disertakan** (Experience Replay / Rehearsal).

---

## Keputusan: Opsi A — Dataset Lama + Inject Vehicle

Setelah evaluasi dua opsi:
- **Opsi A (Dipilih):** Gunakan dataset navigasi lama yang sudah terbukti + tambahkan dataset vehicle baru
- **Opsi B (Ditolak):** Regenerasi seluruh dataset dari Roboflow dari awal

**Alasan Opsi A lebih baik:**
1. **Experience Replay**: Dataset navigasi yang sama muncul lagi → model "diingatkan" pelajaran lama
2. Tidak perlu re-download 6 dataset navigasi dari Roboflow (hemat waktu)
3. Split train/valid/test navigasi sudah terbukti seimbang dan menghasilkan 93.7% mAP

---

## Kelas `vehicle`: Satu Kelas untuk Semua Kendaraan

Semua jenis kendaraan digabung ke **satu kelas tunggal `vehicle`**. Alasannya:
- Bagi tunanetra: "ada kendaraan" lebih penting daripada "itu mobil atau motor"
- Data training berlipat ganda: COCO punya 60K+ anotasi kendaraan dari semua jenis
- Model lebih robust dan confident dalam mendeteksi semua bentuk kendaraan

| Sumber | Kelas Asli | Dipetakan ke | Prioritas |
|---|---|---|---|
| COCO (Roboflow v46) | car, truck, bus, motorcycle, bicycle | `vehicle` | Utama |
| Indonesia (Roboflow) | mobil, motor, angkot, pickup, sepeda | `vehicle` | Backup |

---

## Arsitektur Final: Dua Notebook Terpisah

```
[NOTEBOOK 1 - Colab]                    [NOTEBOOK 2 - Kaggle]
dataset_vnetra_yolov11n_colab_car.ipynb  train_vnetra_yolov11n_kaggle_vehicle.ipynb
─────────────────────────────────        ──────────────────────────────────────────
Input: Roboflow API                      Input 1: vnetra_master_dataset.zip (10 kelas)
  - COCO (car, truck, bus, motor...)     Input 2: vehicle_dataset.zip (1 kelas)
  - Dataset Indonesia (backup)           Input 3: best.pt (model 93.7% mAP)
                                         
Processing: merge ke 1 kelas             Processing: merge keduanya + remap ID
Output: vehicle_dataset.zip (nc=1)       ├── Nav labels: old_id + 1 (geser kanan)
                                         ├── Vehicle labels: tetap ID=0
                                         └── data.yaml: 11 kelas
                                         
                                         Training: YOLO fine-tuning dari best.pt
                                         Output: best_vehicle_yolo11n.pt + .tflite
```

---

## Remapping Class ID (Krusial, Zero-Error)

Old dataset (10 kelas) → New dataset (11 kelas):

| Lama | Kelas | Baru |
|---|---|---|
| — | **vehicle** (baru) | **0** |
| 0 | pole | 1 |
| 1 | tactile_paving_straight | 2 |
| 2 | tactile_paving_turn | 3 |
| 3 | tactile_paving_3way | 4 |
| 4 | tactile_paving_4way | 5 |
| 5 | tactile_paving_stop | 6 |
| 6 | stairs_up | 7 |
| 7 | stairs_down | 8 |
| 8 | crosswalk | 9 |
| 9 | tree | 10 |

**Algoritma remapping:** `new_id = old_id + 1` (sederhana, tidak ada ambiguitas)

> [!NOTE]
> Vehicle dataset diberi prefix nama file `veh_` untuk mencegah konflik nama file dengan dataset navigasi yang mungkin punya nama file serupa.

---

## File yang Dibuat

| File | Platform | Fungsi |
|---|---|---|
| [dataset_vnetra_yolov11n_colab_car.ipynb](file:///e:/Project/Skripsi/VNetra/notebooks/dataset_vnetra_yolov11n_colab_car.ipynb) | Google Colab | Buat vehicle_dataset.zip (nc=1) |
| [train_vnetra_yolov11n_kaggle_vehicle.ipynb](file:///e:/Project/Skripsi/VNetra/notebooks/train_vnetra_yolov11n_kaggle_vehicle.ipynb) | Kaggle | Gabung dataset, fine-tune, export, benchmark |

---

## Konfigurasi Fine-Tuning Kritis (Anti-Catastrophic Forgetting)

```python
model = YOLO('best.pt')  # WAJIB: dari model terbaik (93.7%), bukan yolo11n.pt kosong
results = model.train(
    data=f"{master_dir}/data.yaml",  # 11 kelas (vehicle + 10 navigasi)
    epochs=150,
    time=10.0,                       # Aman dari Kaggle 12-jam timeout
    lr0=0.0005,                      # 4x lebih kecil dari training pertama (0.002)
    freeze=10,                       # 2x lebih banyak frozen layer (sebelumnya 5)
    patience=15,                     # Lebih cepat berhenti jika sudah konvergen
    warmup_epochs=3.0,               # Lebih singkat (model sudah hangat)
    optimizer="AdamW",
    cos_lr=True,
    box=7.5, cls=1.5, dfl=1.5,       # Identik dengan training pertama
    # Augmentasi OV2640 identik dengan training pertama
    mosaic=1.0, degrees=8.0, fliplr=1.0, scale=0.5,
    hsv_h=0.015, hsv_s=0.5, hsv_v=0.4, erasing=0.1,
)
```

---

## Cara Pakai di Kaggle

**Dataset:**
1. Upload `vnetra_master_dataset.zip` (dataset navigasi lama) via **Add Data → Datasets**
2. Upload `vehicle_dataset.zip` (hasil Notebook 1) via **Add Data → Datasets**

**Model (pilih salah satu):**
3. **Cara A (Direkomendasikan):** Add Data → **Notebooks** → pilih notebook training pertama → centang output-nya
   - `best.pt` akan otomatis ditemukan di dalam output ZIP di path: `.../vnetra_training/yolo11n_custom/weights/best.pt`
4. **Cara B (Manual):** Upload `best.pt` sebagai Dataset terpisah via Add Data → Datasets

**Auto-detect scoring** (tidak perlu konfigurasi manual):

| Sumber | Score | Dipilih? |
|---|---|---|
| Output notebook → `weights/best.pt` | **215** | ✅ Prioritas utama |
| Output notebook → `best_yolo11n.pt` | 105 | Fallback #1 |
| Upload dataset → `best.pt` | 15 | Fallback #2 |
| `/kaggle/working/best.pt` | 10 | Fallback #3 |

5. Jalankan `train_vnetra_yolov11n_kaggle_vehicle.ipynb` → semua file terdeteksi otomatis

---

## Estimasi Hasil yang Diharapkan

| Kelas | mAP@50 (Prediksi) |
|---|---|
| **vehicle (baru)** | **0.80 - 0.88** |
| pole (lama) | 0.95+ (sedikit turun) |
| tactile_paving_* (lama) | 0.95+ (stabil) |
| stairs_* (lama) | 0.85+ (sedikit turun) |
| crosswalk (lama) | 0.90+ (stabil) |
| tree (lama) | 0.70+ (sedikit turun) |
| **Rata-rata mAP@50** | **0.88 - 0.92** |

Penurunan kecil ~1-3% pada kelas lama adalah wajar dan dapat diterima untuk fine-tuning incremental.
