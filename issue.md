
# ADR-015: Peningkatan Sensitivitas Linear Acceleration (Penghapusan Deadzone MPU6050)
## Status
Proposed

## Date
2026-07-12

## Context
Akselerometer MPU6050 pada VNetra Edge Computing Node memproses `a_lin_mag` (magnitudo akselerasi linear tanpa gravitasi) menggunakan *Exponential Moving Average (EMA)* yang sangat berat (bobot 0.1 untuk data baru) dan sebuah *Noise Gate / Deadzone* yang memaksa nilai di bawah `0.2 m/s²` menjadi `0.0`.
Pengguna menyadari bahwa pendekatan ini membuat pergerakan terasa sangat artifisial, kaku, dan lambat (menghilangkan dinamika mikro dari akselerasi di dunia nyata).

## Decision (Ponytail & Doubt-Driven Approach)
Berdasarkan fisika kinematika, akselerasi yang nyata tidak pernah benar-benar stabil di angka nol (karena getaran mikroskopis dan *noise* kelistrikan sensor selalu ada). *Clamp* artifisial ini menghalangi realisme pengukuran.
Langkah modifikasi yang diambil:
1. **Sensitivitas EMA Ditingkatkan:** Menaikkan bobot pembacaan baru dari `0.1` menjadi `0.4` pada fungsi `a_lin_smooth = (0.4f * a_lin_mag_raw) + (0.6f * a_lin_smooth)`.
2. **Penghancuran Deadzone:** Menghapus sepenuhnya blok logika `if (a_lin_mag < 0.2f) { a_lin_mag = 0.0f; }`.
3. **Mempertahankan *Floor Clamp* pada `0.0`:** Secara matematis, sebuah besaran Skalar (Magnitudo = `sqrt(x^2+y^2+z^2)`) tidak memiliki arah dan karenanya **TIDAK BISA NEGATIF**. Jika nilainya menjadi negatif setelah dikurangi *DC Bias*, itu murni merupakan artifak komputasi matematika, bukan perlambatan fisik. Oleh karena itu pembatasan nilai terendah di `0.0` tetap dipertahankan.

## Consequences
- **Positif:** Respons akselerasi di aplikasi Android akan langsung "terbangun" meskipun perangkat hanya disentuh sedikit. Kecepatan dan pergerakan akan terasa hidup layaknya kinematika dunia nyata.
- **Negatif:** Saat perangkat diletakkan diam di atas meja, nilai magnitudo akselerasi mungkin tidak akan menampilkan "0.00" secara mutlak, melainkan berayun tipis (misal: 0.02 - 0.07 m/s²). Ini adalah harga wajar yang dibayar untuk mendapatkan realisme data sensor mentah (*raw realism*).
