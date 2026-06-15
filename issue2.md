# Ringkasan Perbaikan: Kompensasi Kemiringan Hardware ToF & Kamera 20 Derajat

## 📌 Deskripsi Isu
Perangkat keras (ToF VL53L5CX dan Kamera) telah dimodifikasi secara fisik sehingga menunduk secara konstan sebesar 20 derajat ke arah lantai. Di sisi lain, sensor IMU MPU6050 tetap berada di posisi datar/lurus (membaca ~0 derajat saat menghadap ke depan). 
Ketidaksesuaian orientasi antara IMU dan ToF ini dapat menyebabkan *double-compensation* atau pergeseran ekstrim yang tidak natural pada zona perhitungan *TerrainDetector* dan estimasi jarak objek (*Formula E*).

## 🛠️ Perbaikan yang Dilakukan

### 1. Pembaruan Konstanta Geometris (TerrainDetector.kt)
- Memperbarui konstanta `ALPHA_MOUNT` dari `15f` menjadi `20f`.
- Hal ini krusial agar estimasi ketinggian tangga / kedalaman lubang (`hEst`) dapat dihitung presisi menggunakan trigonometri *cosinus* yang sesuai dengan kemiringan fisik terbaru.

### 2. Kompensasi Offset MPU6050 (CameraStreamActivity.kt)
- Menambahkan baris kompensasi: `val thetaDeg = rawTheta - 20f` sebelum menginjeksi data *pitch* ke `FormulaE` dan `TerrainDetector`.
- **Dampak Logis:** Karena ToF sudah mengarah ke bawah 20 derajat, garis lurus cakrawala (horizon) secara efektif bergeser secara fisik menuju bingkai paling atas dari sensor ToF. Dengan mengurangkan 20 derajat dari MPU6050, *software* akan memusatkan `rCenter` (baris referensi depan) secara dinamis ke indeks baris `0`, yang mana ini sepenuhnya selaras dengan pandangan absolut sensor terhadap dunia nyata.

## ✅ Hasil
Estimasi deteksi tangga dan objek kini tetap kokoh dan stabil meskipun terdapat selisih sudut fisik pemasangan 20 derajat antara IMU dan ToF. Visualisasi kotak *grid overlay* tetap sinkron karena posisi fisik kamera ikut miring 20 derajat bersama ToF.
