---
title: "feat(FormulaI): Lateral Drift Guard — drift_score untuk mengurangi false positive kendaraan melintas"
labels: enhancement, formula, safety
---

## Ringkasan

Menambahkan mekanisme **Lateral Drift Guard** (`drift_score`) ke Formula I (Delta Bounding Box / TTC) sebagai *conditional modifier* untuk mengurangi *false positive* saat kendaraan melintas di jalur berbeda — bukan menabrak pengguna secara frontal.

---

## 🐛 Masalah: False Positive pada Kendaraan yang Melintas

### Skenario

Pengguna tunanetra berjalan di trotoar pinggir jalan. Sebuah motor melaju di jalur jalan (bukan di trotoar):
- Saat jauh: motor terlihat di Jam 12 (efek *vanishing point*)
- Saat dekat: motor sudah bergeser ke Jam 11/10, lewat dengan aman

### Kekurangan Formula I (v8 saat ini)

Formula I.v8 menggunakan tiga sub-skor:
1. `area_score` — pertumbuhan luas bounding box
2. `ar_score` — perubahan aspect ratio  
3. `dist_score` — konsistensi jarak ToF

Skenario di atas bisa menghasilkan `TTC_weighted` tinggi (PROBABLE/IMMINENT) karena area bounding box motor membesar saat mendekat — meski motor itu tidak akan menabrak pengguna.

`ar_score` membantu, tetapi hanya mendeteksi perubahan *bentuk* kotak, bukan perubahan *posisi horizontal* objek.

---

## ✅ Solusi: Formula I.8 — Lateral Drift Guard

### Desain (Ponytail Full: Minimal, Tidak Ubah Bobot Lama)

```
Δx_c = x_c^(t) - x_c^(t-1)                          ← pergeseran horizontal centroid

drift_score = clip(|Δx_c| / drift_norm, 0, 1)         ← skor 0..1
  drift_norm = 60px  (= R_col = lebar 1 kolom ToF)

drift_guard = drift_score    jika dist_score ≤ 0.5    ← hanya aktif saat jarak ambigu
            = 0              jika dist_score > 0.5    ← nonaktif saat jarak pasti berkurang

TTC_score_final = TTC_score × (1 - drift_guard)       ← modifier kondisional
```

### Mengapa `dist_score ≤ 0.5` sebagai Guard?

| Kondisi | dist_score | drift_guard | Hasil |
|---|---|---|---|
| Kendaraan frontal murni (lurus menabrak) | 1.0 | 0 | TTC tidak berkurang ✓ |
| Kendaraan oblique (sudut 45°, mendekati) | 1.0 | 0 | TTC tidak berkurang ✓ |
| Kendaraan melintas, jarak ambigu | 0.5 | drift_score | TTC dikurangi sesuai drift ✓ |
| Kendaraan melintas, jarak tidak berkurang | 0.0 | drift_score | TTC = 0 (tidak ada bahaya) ✓ |

Kunci keamanan: **jika jarak ToF sedang berkurang (`dist_score > 0.5`), kendaraan memang mendekat secara fisik, sehingga drift diabaikan sepenuhnya.** Ini mencegah false negative pada pendekatan oblique yang sebenarnya berbahaya.

### Bobot Tidak Berubah

`w_A=0.50, w_AR=0.25, w_dist=0.25` tetap sama persis. `drift` adalah modifier *post-hoc* kondisional, bukan sub-skor ke-4 yang mengacak ulang bobot.

---

## 🔍 Adversarial Review (Doubt-Driven Development)

Tiga isu yang ditemukan dan cara mengatasinya:

| Isu | Klasifikasi | Resolusi |
|---|---|---|
| Oblique approach false negative: Δx_c besar tapi kendaraan menabrak | Valid + Actionable | `dist_score > 0.5` guard: drift dinonaktifkan saat jarak ToF berkurang |
| Bobot lama berubah jika drift jadi sub-skor ke-4 | Valid + Trade-off | Implementasi sebagai modifier kondisional, bukan sub-skor. Bobot tetap. |
| YAGNI: ar_score sudah mencakup lateral? | Valid Trade-off | ar mengukur perubahan *bentuk*, drift mengukur perubahan *posisi*. Keduanya orthogonal. |

---

## 📐 Konstanta Baru

| Simbol | Nilai | Satuan | Keterangan |
|---|---|---|---|
| `drift_norm` | 60 | px | Normalisasi pergeseran lateral; dipilih = `R_col` (lebar 1 kolom ToF) agar threshold bermakna secara fisik |

---

## 📄 File yang Diubah

- `docs/formula-matematis-v9.4.md` → diperbaharui menjadi **v9.5**:
  - Tabel Konstanta Sistem: tambah `drift_norm`
  - Formula I: tambah I.8 (drift_score + drift_guard)  
  - Tabel Variabel I: tambah 3 entri baru
  - Tabel Ringkasan Formula I: update deskripsi
  - Footer: versi 9.4 → 9.5
