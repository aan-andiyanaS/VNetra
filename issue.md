# Catatan Rilis Teknis: Fusi Sensor YOLOv11 & VL53L5CX ToF (Tahap 2)

**Tanggal:** 26 Juni 2026  
**Status:** `RELEASED`  
**Cakupan:** Transisi dari sistem penghindar rintangan statis (Tahap 1) ke pemetaan spasial dinamis berbasis objek (Tahap 2).

---

## 1. Latar Belakang & Masalah
Sebelumnya, sistem VNetra bergantung pada alur monitoring rintangan statis yang sederhana (Tahap 1). Rintangan dideteksi murni dari data jarak raw dari kolom tengah sensor ToF, memicu peringatan Text-to-Speech (TTS) umum dengan kalimat "rintangan". Walaupun pendeteksi objek YOLOv11n sudah berjalan, output-nya baru sebatas di-render secara visual pada UI overlay dan belum terintegrasi dengan alur komputasi sensor jarak.

Untuk mengatasi ini, kami mengimplementasikan kalkulasi **Centroid Bounding Box** untuk memetakan output semantik dari model computer vision dengan data kedalaman sensor ToF. Hal ini memungkinkan sistem untuk menyebutkan *apa* objeknya, *berapa* jaraknya, dan *ke arah mana* posisinya dalam satu alur pipeline yang terpadu.

---

## 2. Detail Arsitektur & Implementasi

Seluruh modifikasi diterapkan pada berkas [VNetra/app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt](app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt). Detail implementasinya adalah sebagai berikut:

### A. Sinkronisasi Alur Lintas-Thread (Cross-Thread Pipeline Sync)
Karena pemrosesan frame kamera (`frameCollectJob`) dan pengumpulan data ToF (`tofCollectJob`) berjalan pada thread terpisah dengan sample rate yang berbeda (~15Hz kamera vs ~10Hz ToF), kami membangun jembatan thread-safe:
* Mendeklarasikan state `@Volatile private var latestDetections: List<DetectionResult>`.
* Pipeline kamera memublikasikan hasil inferensi terbaru ke dalam state ini segera setelah proses deteksi YOLO selesai.

### B. Perombakan Loop Pemrosesan ToF (`tofCollectJob`)
Alih-alih membaca kolom statis tengah, thread ToF sekarang memproses koordinat spasial secara dinamis:
1. **Centroid Extraction (Ekstraksi Centroid):** Menghitung titik tengah horizontal dari setiap objek terdeteksi:
   $$x_c = \frac{x_{min} + x_{max}}{2}$$
2. **FoV Filtering (Penyaringan FoV):** Memastikan centroid objek berada dalam zona aktif ToF. Jika di luar (FoV ToF lebih sempit secara horizontal dibanding kamera), objek segera diabaikan untuk mencegah miskalkulasi jarak.
3. **Spatial Clock Direction Mapping (Pemetaan Arah Jam Spasial):** Mengonversi koordinat titik tengah `x_c` menjadi arah jam pendengaran (misalnya jam 10, 11, 12, 1, 2).
4. **Column Binning (Pemetaan Kolom):** Memetakan koordinat ruang gambar `x_c` ke kolom sensor ToF yang sesuai secara dinamis (misal $j \in [0..7]$ untuk 8x8, atau $j \in [0..3]$ untuk 4x4).
5. **Head Tilt Compensation & Depth Extraction (Kompensasi Kemiringan Kepala & Ekstraksi Jarak):** Memanfaatkan sudut pitch kepala ($\theta$) dari IMU MPU6050 untuk menggeser baris pembacaan ToF secara dinamis. Ini menjamin sensor jarak selalu memantau ke depan relatif terhadap cakrawala, bukan menghadap tanah, saat pengguna menunduk.
6. **TTS Dispatcher (Penyalur Pesan Suara TTS):** Mengirimkan data semantik objek (`className`), hasil perhitungan jarak, dan arah jam ke TTS engine.

### C. Strategi Cadangan (Fallback Strategy - Failsafe)
Jika YOLO gagal mendeteksi objek (akibat minim cahaya, motion blur, atau batasan model), sistem otomatis beralih ke monitoring Tahap 1. Ini mencegah sistem membisu saat berada di depan rintangan tak dikenal:
```kotlin
if (detections.isNotEmpty()) {
    // Tahap 2: Semantik Dinamis + Jarak + Arah
} else {
    // Tahap 1 (Fallback): Monitoring kolom tengah ToF secara statis
    // Memperingatkan pengguna akan adanya "rintangan" umum di arah jam 12
}
```

---

## 3. Perbaikan Teknis & Optimasi

### ✅ [FIXED] Koreksi Fisik Sensor MPU6050 Terbalik
* **Masalah:** Sensor MPU6050 dipasang terbalik secara fisik pada kacamata (komponen menghadap ke tanah). Hal ini membalikkan vektor sumbu Z, sehingga perhitungan EKF (Extended Kalman Filter) dan kompensasi pitch menghasilkan offset arah yang salah.
* **Solusi:** Alih-alih merombak sirkuit fisik, kami memodifikasi kode firmware pada [VNetra/firmware-vnetra/firmware-vnetra/firmware-vnetra.ino](firmware-vnetra/firmware-vnetra/firmware-vnetra.ino). Kami menambahkan flag `MPU_MOUNTING_INVERTED` dan membungkus pembacaan sensor dalam fungsi kustom `getMpuEvent()`. Saat diaktifkan, firmware secara matematis membalikkan sumbu Z dan sumbu X (menjaga sistem koordinat kaidah tangan kanan / Right-Handed System) sebelum menyuplai nilainya ke EKF, menyelesaikan masalah ini secara transparan.

### ✅ [FIXED] Ketahanan Resolusi (Skala VGA vs. QVGA)
* **Masalah:** Firmware ESP32 memiliki fallback ke resolusi `FRAMESIZE_QVGA` (320x240) jika PSRAM tidak terbaca (resolusi standar adalah `FRAMESIZE_VGA` 640x480). Karena `FormulaUtils.kt` menggunakan koordinat hardcoded asumsi lebar kamera 640px, frame 320px akan menggeser setengah layar kanan ke zona mati permanen.
* **Solusi:** Menambahkan pelacak volatile `latestFrameWidth` pada aplikasi Android. Centroid mentah `xcRaw` sekarang dikalibrasi secara dinamis ke ruang koordinat virtual 640px sebelum dialirkan ke rumus pemetaan:
  $$x_c = x_{c\_raw} \times \frac{640}{W_{frame}}$$
  Hal ini memisahkan algoritma fusi dari dependensi resolusi fisik kamera.

### ✅ [OPTIMIZED] Penyesuaian Nada & Kecepatan TTS
* **Konversi Jarak:** Secara native, VL53L5CX mengukur kedalaman dalam milimeter (mm). Mendengar ucapan *'seribu seratus milimeter'* (1100 mm) terasa kurang natural dan lambat dicerna. Pipeline sekarang mengonversinya ke centimeter dengan membagi nilai raw dengan 10 (`dObj / 10`), sehingga dibacakan sebagai *'110 sentimeter'* yang lebih efisien.
* **Akselerasi Kecepatan:** Meningkatkan speech rate TTS dari `1.05f` menjadi **`1.3f`** pada `TtsAlertManager.kt`. Peningkatan tempo bicara 30% ini membuat penyampaian suara navigasi terasa jauh lebih tanggap dan responsif selama pengguna berjalan.

### ✅ [REFACTOR] Pembenahan Nama Berkas Fungsional (Menghindari Kebingungan "Formula")
* **Masalah:** Skema penamaan generik menggunakan istilah 'Formula' (`FormulaUtils`, `FormulaE`, `FormulaH`) memicu kebingungan struktural (*cognitive overhead*) dan menyulitkan pembacaan fungsi asli masing-masing file utilitas.
* **Solusi:** Menyusun ulang struktur berkas dan penamaan kelas/objek internal agar lebih semantis dan self-documenting:
  * `FormulaUtils.kt` $\rightarrow$ [VNetra/app/src/main/java/com/airi/vnetra/util/SpatialMappingUtils.kt](app/src/main/java/com/airi/vnetra/util/SpatialMappingUtils.kt) (Fungsi centroid & pemetaan arah jam/kolom)
  * `FormulaE.kt` $\rightarrow$ [VNetra/app/src/main/java/com/airi/vnetra/util/TofDepthEstimator.kt](app/src/main/java/com/airi/vnetra/util/TofDepthEstimator.kt) (Rata-rata jarak baris dengan kompensasi pitch)
  * `FormulaH.kt` $\rightarrow$ [VNetra/app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt](app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt) (Manajemen flag one-shot & pembungkus TTS engine)
  Seluruh deklarasi import dan pemanggilan objek di dalam `CameraStreamActivity.kt` disesuaikan, dan kompilasi proyek berhasil diselesaikan.

---

## 4. Rencana Tindak Lanjut
- [ ] Melakukan pengujian lapangan dengan objek bergerak cepat untuk mengevaluasi latensi sinkronisasi antara deteksi YOLO dan keluaran suara TTS.
- [ ] Mengalibrasi nilai ambang batas ($D_{W0}$) untuk pemicu TTS pada berbagai variasi kecepatan jalan kaki pengguna.
