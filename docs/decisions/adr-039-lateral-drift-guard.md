# ADR-039: Lateral Drift Guard (drift_score)

## Status
Accepted

## Date
2026-07-18

## Context
Pengguna tunanetra berjalan di trotoar pinggir jalan. Sebuah motor melaju di jalur jalan (bukan di trotoar). 
Saat jauh, motor terlihat di arah "Jam 12" (efek *vanishing point*). Saat motor mendekat, ia bergeser ke arah "Jam 11/10" dan lewat dengan aman tanpa menabrak.

Formula I (Time-to-Collision) v8 menggunakan tiga sub-skor:
1. `area_score`: pertumbuhan luas bounding box
2. `ar_score`: perubahan aspect ratio
3. `dist_score`: konsistensi jarak ToF

Skenario di atas bisa menghasilkan `TTC_weighted` yang tinggi (menyebabkan peringatan palsu) karena area bounding box motor membesar secara signifikan saat mendekat, meskipun secara lateral motor tersebut sudah menjauhi pusat lintasan pengguna. `ar_score` membantu, namun hanya mendeteksi perubahan bentuk, bukan perubahan posisi secara horizontal.

## Decision
Menambahkan mekanisme **Lateral Drift Guard** (`drift_score`) ke Formula I sebagai *conditional modifier* untuk mengurangi *false positive* pada objek yang melintas menyamping.

```
Δx_c = x_c^(t) - x_c^(t-1)                          ← pergeseran horizontal centroid

drift_score = clip(|Δx_c| / drift_norm, 0, 1)         ← skor 0..1
drift_norm = 60px  (= R_col = lebar 1 kolom ToF)

drift_guard = drift_score    jika dist_score ≤ 0.5    ← hanya aktif saat jarak ambigu
            = 0              jika dist_score > 0.5    ← nonaktif saat jarak pasti berkurang

TTC_score_final = TTC_score × (1 - drift_guard)       ← modifier kondisional
```

## Alternatives Considered
- **Menjadikan drift_score sebagai sub-skor ke-4:** Ditolak karena akan mengubah bobot lama (`w_A`, `w_AR`, `w_dist`) yang sudah disetel dengan baik. Implementasi sebagai modifier *post-hoc* lebih aman.
- **Mengandalkan ar_score saja:** Ditolak karena aspect ratio mengukur perubahan bentuk, sedangkan drift mengukur perubahan posisi. Keduanya orthogonal.

## Consequences
- Kunci keamanan: `dist_score > 0.5` guard. Drift dinonaktifkan saat jarak ToF secara konsisten berkurang (kendaraan memang mendekat secara fisik). Ini mencegah *false negative* pada pendekatan *oblique* (sudut miring) yang sebenarnya berbahaya.
- Menambahkan parameter `drift_norm` ke konstanta sistem.
- Dokumentasi `formula-matematis-v9.4.md` ditingkatkan menjadi v9.5.
