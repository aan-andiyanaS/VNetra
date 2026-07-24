## ADR-049: Asynchronous YOLO Initialization, UI Refinement, and TerrainDetector Deprecation

Pembaruan kali ini mencakup perbaikan *bottleneck* performa di perangkat spesifikasi menengah, perbaikan visual *augmented-reality* grid ToF, dan pembersihan ekstensif (*clean code*) dengan menghapus arsitektur *dead-code* dari sistem deteksi anomali daratan (*Terrain*).

---

### 1. Performa: Async YOLO Initialization (`f8f1317`)

**Konteks Masalah:**
Saat aplikasi dijalankan di perangkat kelas menengah (contoh: Samsung A17 4G), pengguna mengalami *freeze/stutter* yang parah pada *splash screen* sesaat setelah terhubung dengan ESP32. Profiler menunjukkan bahwa penyebab utamanya adalah alokasi memori tensor dan inisialisasi GPU Delegate TensorFlow Lite (`YoloDetector`) yang memblokir `Main Thread`.

**Solusi:**
Proses instansiasi dipindahkan ke *background thread* (I/O). Selama model dimuat, aktivitas *streaming* tetap berjalan lancar. Mekanisme pengamanan (guard) di dalam loop *drawing* juga ditambahkan untuk mencegah *NullPointerException* sebelum model siap.

**Potongan Kode:**
```kotlin
// SEBELUM: Memblokir Main Thread di onCreate
yoloDetector = YoloDetector(this, "yolov11n_float32.tflite")

// SESUDAH: Non-blocking (Asynchronous)
lifecycleScope.launch(Dispatchers.IO) {
    try {
        val detector = YoloDetector(this@CameraStreamActivity, "yolov11n_float32.tflite")
        yoloDetector = detector
        Log.d(TAG, "YOLO detector initialized asynchronously")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize YOLO detector", e)
    }
}
```

---

### 2. UI/UX: Refinement Visual ToF Grid & Zona Arah Jam (`9a0efca`)

**Konteks Masalah:**
Warna *overlay* matriks ToF (Merah, Kuning, Hijau) terlalu menyilaukan dan menghalangi visual asli dari kamera. Lebar sel dan garis putih antar-sel merusak *immersion*. Selain itu, teks "Jam 11", "Jam 12", "Jam 1" mengambang tanpa kejelasan batas zonanya.

**Solusi:**
Nilai HSV diubah menjadi lebih pastel (S=0.80, V=0.85). *Opacity* diangkat ke 145/255 agar warna terbaca jelas tanpa menyilaukan. *Stroke* sel ditiadakan, diganti dengan *background* abu-abu gelap transparan. Pemisah batas arah jam kini digambarkan secara eksplisit dengan pilar hijau.

**Potongan Kode:**
```kotlin
// Perbaikan palet warna HSV di ToFGridRenderer.kt
val hsv = FloatArray(3)
hsv[0] = hue
hsv[1] = 0.80f // Saturation diturunkan dari 1.0 (lebih pastel)
hsv[2] = 0.85f // Value diturunkan dari 1.0 (tidak menyilaukan)

// Alpha masking
val alpha = if (isDimmed) 65 else 145 // 56.9% opacity vs 37% lama
```
```xml
<!-- Penambahan batas arah jam di activity_camera_stream.xml -->
<View
    android:layout_width="1dp"
    android:layout_height="18dp"
    android:background="#AA00C853"
    android:layout_marginStart="4dp"
    android:layout_marginEnd="4dp" />
```

---

### 3. Arsitektur: Deprekasi & Penghapusan TerrainDetector (`32006e9`)

**Konteks Masalah:**
Fitur pendeteksian Tangga Naik, Tangga Turun, dan Lubang secara eksklusif menggunakan algoritma *spatial-gradient* dari sensor ToF (dijuluki **Fase 3: Terrain J**). Namun di lapangan (siang hari), sensor ToF tidak mampu mengukur kedalaman tanah melebihi jarak 1 meter (terblokir saturasi IR matahari), sehingga kalkulasi lubang/tangga selalu menghasilkan *false-positive* atau terlambat dideteksi. Selain itu, model YOLOv11 secara standar tidak memiliki kelas "tangga/lubang", sehingga validasi silang (YOLO + ToF) yang dirancang di awal tidak pernah terpenuhi.

**Solusi:**
Sesuai prinsip **YAGNI** (*You Aren't Gonna Need It*), keseluruhan sistem `TerrainDetector` (± 400 baris kode), variabel pelacakan latensi `pingTerrain`, dan pengidentifikasi `TERRAIN_TRACKING_ID` dihapus dari _codebase_ secara permanen, bukan sekadar di-*comment*. TTS kini murni bereaksi terhadap *Tembok/Obstacle* statis dan deteksi objek YOLO.

**Potongan Kode:**
```kotlin
// DIHAPUS SEPENUHNYA dari CameraStreamActivity.kt:
// private val terrainDetector = TerrainDetector()

// DIHAPUS dari TtsAlertManager.kt (Penyederhanaan Noise Gate ADR-017):
// SEBELUM:
val isStaticObject = trackingId == SpatialMappingUtils.WALL_TRACKING_ID || trackingId == SpatialMappingUtils.TERRAIN_TRACKING_ID || isPavingObj

// SESUDAH:
val isStaticObject = trackingId == SpatialMappingUtils.WALL_TRACKING_ID || isPavingObj
```
