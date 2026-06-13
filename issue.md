# Plan: Implementasi Gradasi Warna Dinamis Berdasarkan Jarak pada Grid ToF

## 📌 Deskripsi Tugas
Tujuan dari task ini adalah menambahkan indikator visual yang intuitif berupa gradasi warna pada setiap *cell* (sel) di antarmuka Android berdasarkan pembacaan jarak dari sensor ToF. 
- **Semakin dekat** jarak objek = Warna latar *cell* menjadi **Merah** 🔴
- **Semakin jauh** jarak objek = Warna latar *cell* menjadi **Hijau** 🟢

## 🛠️ Rencana Implementasi (High-Level)

### 1. Tentukan Batas Jarak (Thresholds)
Tentukan nilai batas jarak (dalam milimeter) untuk dijadikan acuan normalisasi. Nilai ini bisa disesuaikan nanti melalui pengujian langsung.
- `MIN_DISTANCE` (misal: `200` mm) -> Jarak ini dan di bawahnya akan bernilai Merah penuh.
- `MAX_DISTANCE` (misal: `1500` mm atau `2000` mm) -> Jarak ini dan di atasnya akan bernilai Hijau penuh.

### 2. Pembuatan Fungsi Kalkulasi Warna (Color Interpolation)
Buat sebuah fungsi utilitas (*helper method*) yang menerima input jarak aktual (angka) dan mengembalikan format warna UI (biasanya `Int` ARGB).
**Rekomendasi Algoritma Pendekatan:**
- **Menggunakan HSB/HSV (Paling Direkomendasikan):** 
  - Dalam spektrum warna HSV, nilai `Hue` untuk Merah adalah `0` dan Hijau adalah `120`.
  - Hitung persentase/rasio jarak dari `MIN_DISTANCE` hingga `MAX_DISTANCE`.
  - Konversi rasio tersebut menjadi nilai `Hue` (dari `0` hingga `120`).
  - Gunakan kelas pembantu `Color.HSVToColor()` bawaan Android SDK untuk mendapatkan kode warna akhir.
- **Alternatif (Color Blending):** Anda juga bisa menggunakan `ColorUtils.blendARGB()` dari *AndroidX* untuk mencampurkan dua kode warna heksadesimal berdasarkan rasio kedekatan.

### 3. Integrasi pada Pembaruan UI (*UI Rendering*)
- Cari metode/blok kode di aktivitas Anda (sepertinya di dalam `CameraStreamActivity`) yang bertugas menerima *payload* data sensor ToF dan memperbarui teks jarak di setiap *cell* pada `GridLayout`.
- Pada setiap iterasi sel, panggil fungsi kalkulasi warna yang telah dibuat dengan menyuplai jarak sel tersebut.
- Ubah properti warna latar belakang dari `View` atau `TextView` milik sel tersebut (contohnya menggunakan `setBackgroundColor(color)`).

### 4. Hal yang Harus Diperhatikan (Performa)
- Logika ini akan berjalan terus menerus pada **setiap frame data sensor** (kurang lebih 15 FPS) dan pada seluruh *cell* (bisa sampai 64 *cell* di mode 8x8).
- **Efisiensi:** Pastikan operasi matematika di dalam kalkulasi warna sesederhana mungkin. Hindari instansiasi/pembuatan objek kelas secara masif di dalam *looping* pembaruan bingkai/frame untuk mencegah *Garbage Collection* yang berlebihan (dapat membuat aplikasi *patah-patah/stuttering*).

## ✅ Kriteria Penerimaan (Acceptance Criteria)
- [ ] *Cell* pada grid mengubah warnanya secara *real-time* saat mendeteksi pergerakan objek mendekat/menjauh.
- [ ] Transisi warna berjalan dengan halus (gradual dari Merah -> Kuning -> Hijau).
- [ ] *Frame rate* aplikasi tidak drop atau patah-patah ketika mode gradasi warna ini aktif.
