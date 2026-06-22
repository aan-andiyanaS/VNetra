# Saran Pengembangan Mendatang — VNetra

Dokumen ini berisi kumpulan ide pengembangan lanjutan yang **belum diimplementasikan** pada versi skripsi saat ini,
namun secara teknis layak dan dapat meningkatkan kemampuan VNetra secara signifikan di masa mendatang.

---

## 1. Segmentasi Semantik (Semantic Segmentation)

### Motivasi
Model deteksi objek (YOLO11n) menggunakan *bounding box* persegi yang tidak mengikuti kontur asli objek.
Untuk kelas seperti `curb`, `sidewalk`, dan `crosswalk` yang berbentuk tidak beraturan,
segmentasi semantik akan menghasilkan pemahaman spasial yang jauh lebih akurat.

### Ide Implementasi
- **Model:** Migrasi dari `yolo11n.pt` ke `yolo11n-seg.pt` (segmentasi instan, pipeline identik)
- **Dataset:** Dataset Dongeui University (`sidewalk-exydy`) berisi anotasi polygon sidewalk yang kaya
- **Output:** Selain bounding box, model menghasilkan *mask* piksel — bisa digunakan untuk menghitung
  seberapa besar area berjalan yang aman tersisa di depan pengguna

### Kelayakan Teknis
- ESP32 mengirim frame 10 FPS → budget 100ms/frame di Android
- YOLO11n-seg inferensi ~30–40ms → masih ada sisa ~60ms untuk audio & UI
- Export ke TFLite: identik dengan pipeline saat ini (`model.export(format="tflite")`)

### Langkah Selanjutnya
```python
# Ganti pre-trained model:
model = YOLO('yolo11n-seg.pt')

# Training dan export sama persis:
model.train(data="data.yaml", ...)
model.export(format="tflite", half=True)
```

---

## 2. Estimasi Kedalaman (Depth Estimation)

### Motivasi
VNetra saat ini hanya mendeteksi **apa** objeknya, bukan **seberapa jauh** objek tersebut.
Dengan estimasi kedalaman, sistem bisa memberikan peringatan dini yang lebih akurat:
*"Ada lubang 2 meter di depan"* alih-alih hanya *"Ada lubang"*.

### Model yang Direkomendasikan

| Model | Ukuran | Kecepatan | Catatan |
|-------|--------|-----------|---------|
| **Depth Anything V2 Small** | ~100 MB | ~40–60ms | Akurasi terbaik untuk mobile |
| **MiDaS Small** | ~25 MB | ~20–30ms | Sangat ringan, akurasi lebih rendah |
| **MonoDepth2** | ~60 MB | ~35–50ms | Monocular, cocok untuk 1 kamera |

### Kelayakan Teknis
- Kamera ESP32-S3 hanya 1 lensa (monocular) → butuh model monocular depth estimation
- Budget waktu: YOLO11n-det (~25ms) + Depth (~40ms) = ~65ms → masih dalam budget 100ms ✅
- Output: peta kedalaman relatif (bukan metrik absolut tanpa kalibrasi kamera)

### Catatan Kalibrasi
Estimasi kedalaman monocular bersifat **relatif** (bukan meter absolut). Untuk mendapat jarak sebenarnya,
perlu kalibrasi intrinsik kamera OV2640 (focal length, distortion coefficient).

---

## 3. Dua Model Paralel (Multi-Task Pipeline)

### Motivasi
Menggabungkan deteksi objek (YOLO) dengan segmentasi area (YOLO-seg atau DeepLab) dalam satu pipeline
untuk memberikan informasi yang lebih lengkap dan akurat tentang lingkungan sekitar pengguna.

### Arsitektur Pipeline yang Diusulkan

```
ESP32-S3 OV2640 (10 FPS)
        │ frame tiap 100ms
        ▼
   Android App
        │
   ┌────┴────────────────────┐
   │    Thread Inferensi     │
   │                         │
   │  [YOLO11n-det: ~25ms]   │  → Deteksi: pothole, pole, stairs, person...
   │  [YOLO11n-seg: ~35ms]   │  → Segmentasi: area sidewalk, curb, crosswalk
   │  ─────────────────────  │
   │  Total: ~60ms ✅         │
   └────────────┬────────────┘
                │
   [Audio TTS / Haptik Feedback]
```

### Catatan Implementasi
- Kedua model dijalankan **sequential** (bukan paralel) di satu thread GPU
- GPU Mobile Android tidak mendukung dua konteks GPU secara bersamaan
- Gunakan GPU Delegate untuk kedua model, dijalankan bergantian dalam budget 100ms

---

## 4. Peningkatan Dataset Kelas Sulit

### Kelas yang Masih Lemah (per evaluasi skripsi)
| Kelas | mAP50 saat ini | Target | Tindakan |
|-------|---------------|--------|----------|
| `bench` | ~0.12 | 0.50+ | Tambah 2.000+ gambar dari Roboflow |
| `chair` | ~0.26 | 0.50+ | Tambah 1.000+ gambar, terutama outdoor |
| `curb` | ~0.24 | 0.60+ | Aktifkan dataset_curb1 & dataset_curb2 yang di-comment |
| `truck` | ~0.34 | 0.55+ | Tambah dataset kendaraan berat Indonesia |
| `stairs_down` | ~0.61 | 0.80+ | Dataset sangat sedikit (63 train), cari lebih banyak |

---

## 5. Peningkatan Arsitektur Model

### Opsi: Naik ke YOLO11s (Small)
- Parameter: ~9.4M (vs 2.6M saat ini)
- Estimasi mAP: naik ~8–12% di semua kelas
- Ukuran TFLite: ~20 MB (vs ~5.5 MB saat ini)
- Pertimbangan: latency naik ~2× → perlu evaluasi apakah masih dalam budget 100ms

### Opsi: Quantization-Aware Training (QAT)
- Melatih model dengan simulasi kuantisasi dari awal
- Hasil INT8 jauh lebih baik dari post-training quantization
- Cocok jika target deployment adalah NPU/NNAPI di Android

---

## 6. Peningkatan Hardware

### Ganti ESP32-S3 dengan Kamera Lebih Baik
- ESP32-S3 + OV2640 hanya 10 FPS pada resolusi VGA
- **Alternatif:** ESP32-S3 + OV5640 → 30 FPS pada 1080p
- **Alternatif lain:** Raspberry Pi Zero 2W → kamera lebih baik, komputasi lokal

### Tambahkan Sensor Ultrasonik
- Untuk deteksi rintangan sangat dekat (<30cm) yang kamera tidak tangkap
- Bisa berjalan paralel dengan pipeline kamera secara independen

---

## 7. Kontekstualisasi Indonesia

### Dataset Khusus Indonesia
Semua dataset Roboflow yang digunakan saat ini mayoritas berasal dari lingkungan barat/Korea.
Pengembangan mendatang dapat membuat dataset primer dari trotoar dan jalan Indonesia:
- Kondisi trotoar retak/rusak khas Indonesia
- Jenis kendaraan (bajaj, becak, motor bebek)
- Tanda dan marka jalan berstandar Indonesia

### Kolaborasi dengan Komunitas Tunanetra
Uji lapangan langsung bersama pengguna tunanetra (misalnya melalui PERTUNI atau Yayasan Mitra Netra)
untuk mendapatkan umpan balik yang lebih realistis tentang kelas objek apa yang paling kritis.

---

*Dokumen ini dibuat sebagai bagian dari pengembangan proyek skripsi VNetra (Navigasi Tunanetra).*
*Terakhir diperbarui: 2026-06-21*
