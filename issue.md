# ADR-003: Kompensasi Sudut Tunduk Mekanis (Fixed Pitch 20°) pada Modul Depth Estimator

## Status
Proposed

## Date
2026-07-11

## Context
Perangkat keras (kamera dan sensor ToF VL53L5CX) dipasang secara fisik dengan sudut kemiringan statis sebesar **20 derajat menunduk** ke arah permukaan jalan. 
Saat ini, kalkulasi jarak di `CameraDepthEstimator.kt` dan pemilihan zona ROI (*Region of Interest*) di `TofDepthEstimator.kt` hanya memperhitungkan sudut dinamis `thetaDeg` dari sensor MPU6050 (kemiringan kepala pengguna). 
Akibatnya:
1. **Pada Kamera:** Kalkulasi koreksi *foreshortening* `cos(theta)` meleset 20 derajat, membuat estimasi jarak YOLO tidak akurat karena seolah-olah kamera dipasang lurus (0 derajat) saat kepala tegak.
2. **Pada ToF:** Pencarian baris pusat (`rCenter`) untuk mendeteksi rintangan horizontal tidak selaras dengan sudut pandang sebenarnya, karena cakupan bidang pandang (FoV) ToF sudah menunduk 20 derajat secara *default*.

## Decision
Menambahkan sebuah konstanta global `MOUNT_PITCH_DEG = 20f` di dalam kedua *estimator* (kamera dan ToF). 
Sudut kemiringan total (`totalPitch`) yang akan digunakan untuk semua perhitungan trigonometri dan geometri adalah kombinasi dari sudut statis pemasangan perangkat dan sudut dinamis kepala pengguna:
`totalPitch = thetaDeg + MOUNT_PITCH_DEG`

## Consequences
- **`CameraDepthEstimator.kt`:** 
  Perhitungan kompensasi jarak dasar kini menggunakan `cos(totalPitch)` dan bukan sekadar `cos(thetaDeg)`.
- **`TofDepthEstimator.kt`:** 
  Perhitungan `rCenter` (indeks baris pusat ToF) menggunakan `totalPitch`, sehingga pada saat kepala tegak lurus (`thetaDeg = 0`), ROI sudah otomatis menargetkan area yang tepat akibat sudut *default* pemasangan sensor.
- **Konsistensi Sensor:** Fusi data antara YOLO dan ToF akan menjadi jauh lebih presisi karena keduanya kini berpatokan pada sumbu elevasi spasial yang persis sama.
