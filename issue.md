# Rencana Arsitektur Fusi YOLO & ToF (Terrain Detector V10.0)

Berdasarkan permintaan Anda, arsitektur `TerrainDetector` akan dirombak total dari yang tadinya bertindak sebagai "Pengambil Keputusan Tunggal" (Single Source of Truth) menjadi sekadar "Estimator Jarak/Kedalaman", di mana **kamera/YOLO bertindak sebagai verifikator akhir** untuk topografi ekstrem.

## 🎯 Tujuan Utama
1. Menghindari *false positive* ToF pada rintangan kompleks (seperti pantulan cahaya/permukaan aneh yang terbaca sebagai tangga/lubang).
2. Mendelegasikan verifikasi visual ke AI (YOLO).
3. Hanya menyisakan peringatan instan (*pure ToF*) untuk keselamatan dasar: `CONTAMINATED` (nabrak objek/tembok depan mata), `OPEN` (bebas halangan), dan `SAFE` (lantai normal).
4. Pemusnahan fitur deteksi `RAMP` (tanjakan/bidang miring).
5. Peningkatan ketat pada logika deteksi `HOLE` di tingkat raw ToF.

---

## 🛠️ Detail Perubahan (Incremental Implementation)

### Fase 1: Perombakan `TerrainDetector.kt`
- **Hapus `TerrainType.RAMP`:** Menghilangkan semua referensi dan blok logika `RAMP` dari *decision tree* (Formula J.3) dan *confidence scoring* (J.6).
- **Pengetatan Kriteria `HOLE` (Formula J.3):**
  Saat ini `HOLE` terdeteksi jika `anomalyCols.size <= 3`. Ini terlalu longgar. Kita akan memperketatnya:
  1. Hanya boleh maksimal **2 kolom** anomali (`anomalyCols.size <= 2`).
  2. Harus memiliki *vertical drop* ($\Delta z_v$) yang **sangat besar** (misal > `DELTA_Z_STEP * 1.5`).
  3. Rasio kedalaman ($R$) dinaikkan *threshold*-nya.
- **Pencabutan Wewenang *Alerting*:**
  `TerrainDetector` tidak lagi berhak mengeluarkan `AlertLevel.HIGH` atau `AlertLevel.MED` untuk tangga dan lubang. Tanggung jawab ini dilempar ke atas (ke `CameraStreamActivity`), sehingga output murni hanya berisi `TerrainType`, `hEst` (estimasi kedalaman), dan `confidence`.

### Fase 2: Pembangunan Sistem Validasi YOLO di `CameraStreamActivity.kt`
- **Tof-YOLO Fusion Pipeline:**
  Saat `TerrainDetector` melempar hasil `STAIR_DOWN` atau `STAIR_UP`, sistem **TIDAK AKAN** membunyikan *speaker* TTS.
  Sistem akan terlebih dahulu menahan *state* ini dan memeriksa daftar *Bounding Box* terbaru (*latest available frame*) dari YOLO (Object Detection).
- **Proses Validasi:**
  1. Jika ToF mengatakan `STAIR_DOWN` di arah "Jam 12", sistem akan mengecek apakah YOLO melihat kelas `stairs_down` atau `stairs_up` di area tengah layar.
  2. Jika **YA** (Validasi Lulus): TTS akan berbunyi dengan gabungan informasi: "Awas tangga turun, kedalaman [x] cm (dari ToF)".
  3. Jika **TIDAK ADA** kotak YOLO yang cocok: Sistem akan mengabaikan peringatan ToF (menganggapnya *noise*).
- **Pengecualian (*Bypass*):**
  Untuk status `CONTAMINATED`, `SAFE`, `OPEN`, dan **`HOLE`**, sistem akan **membypass validasi YOLO** dan langsung percaya pada ToF. Khusus untuk `HOLE`, karena berjalan terlepas dari YOLO, validasinya di tingkat raw ToF dibuat jauh lebih ketat agar tidak menimbulkan *false positive*.

---
*(Semua pertanyaan konfirmasi telah terjawab, implementasi dapat dimulai.)*
