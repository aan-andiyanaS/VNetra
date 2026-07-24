# VNetra — Code Review & FPS Lag Root Cause Analysis

> Reviewer: Antigravity AI (Claude Sonnet 4.6 Thinking)  
> Tanggal: 22 Juli 2026  
> Scope: Seluruh codebase — Firmware (ESP32-S3) + Aplikasi Android  
> Fokus: Lima Axis (Correctness, Readability, Architecture, Security, Performance) + **Root Cause FPS Lag**

---

## 🔴 DIAGNOSIS UTAMA: Mengapa FPS Kamera Benar-Benar Lag?

Setelah review menyeluruh, ditemukan **5 penyebab lag FPS utama** yang bekerja secara bersamaan:

---

### 🚨 PENYEBAB #1 (CRITICAL) — Inference YOLO Dijalankan di Main Thread Sebelum Dioptimalkan

**File:** [`CameraStreamActivity.kt` L.554–603](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt#L554-L603)

```kotlin
// Dalam startCollectingFrames(), SETIAP frame memanggil ini di Main Thread:
withContext(Dispatchers.Main) {
    binding.ivCameraFrame.setImageBitmap(bitmap)   // ← Render frame
    updateFpsCounter(jpegBytes.size)

    // AI Inference dimulai dari Main Thread:
    if (yoloDetector?.modelStatus != ModelStatus.NONE && isInferencing.compareAndSet(false, true)) {
        lifecycleScope.launch(Dispatchers.Default) {  // ← Lompat ke Default
            val rawResults = detector.detect(bitmap)  // ← Inferensi 640x640
            // ...
        }
    }
}
```

**Masalah kritis:**  
- `setImageBitmap(bitmap)` dan logika peluncuran YOLO berjalan di **Main Thread** untuk SETIAP frame.  
- `detector.detect(bitmap)` memanggil `convertBitmapToByteBuffer()` yang melakukan loop **640×640 piksel** (409.600 iterasi) per frame.  
- Bitmap `640×480` di-scale ke 640×640 dengan `Bitmap.createScaledBitmap()` — ini mengalokasikan Bitmap baru setiap frame.  
- Dengan `AtomicBoolean isInferencing`, frame baru ditampilkan **MENUNGGU** inferensi sebelumnya selesai di UI thread karena `withContext(Main)` memblokir slot render.

**Estimasi dampak:** Pada GPU, YOLO640 bisa 50–150ms/frame. Pada CPU (4 thread), 300–800ms/frame. Ini adalah **bottleneck terbesar** yang langsung membatasi FPS ke 2–3 FPS.

---

### 🚨 PENYEBAB #2 (CRITICAL) — `Bitmap.createScaledBitmap` Alokasi Per-Frame Tanpa Recycle

**File:** [`YoloDetector.kt` L.247–271](file:///e:/Project/Skripsi\VNetra/app/src/main/java/com/airi/vnetra/model/YoloDetector.kt#L247-L271)

```kotlin
private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
    inputBuffer.rewind()
    val scale = minOf(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
    val newWidth = (bitmap.width * scale).toInt()
    val newHeight = (bitmap.height * scale).toInt()

    // ❌ MASALAH: Alokasi Bitmap baru SETIAP frame inference!
    val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    val intValues = IntArray(newWidth * newHeight)  // ❌ IntArray baru SETIAP frame
    resizedBitmap.getPixels(intValues, 0, newWidth, 0, 0, newWidth, newHeight)
    // resizedBitmap TIDAK di-recycle!
```

**Masalah:**
1. `Bitmap.createScaledBitmap` = alokasi heap besar (640×480 × 4B ≈ 1.2MB) per frame.
2. `IntArray(newWidth * newHeight)` = alokasi baru ≈ 1.2MB setiap frame inference.
3. `resizedBitmap` tidak pernah di-`recycle()` → GC pressure ekstrem → GC pause 50–200ms yang muncul acak-acakan, menyebabkan frame **drop masif**.
4. GC pause ini tidak bisa diprediksi dan menyebabkan FPS jatuh tiba-tiba.

---

### 🚨 PENYEBAB #3 (CRITICAL) — `withContext(Dispatchers.Main)` di Dalam ToF Collect Hot Loop

**File:** [`CameraStreamActivity.kt` L.725–776](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt#L725-L776)

```kotlin
tofCollectJob = lifecycleScope.launch(Dispatchers.Default) {
    svc.tofFlow.collect { tofData ->
        // ...
        // ❌ MASALAH: withContext(Main) SETIAP frame ToF untuk cek ukuran grid
        val tofViewSize = withContext(Dispatchers.Main) {
            if (tofData.size != tofGridRenderer.getGridSize()) {
                tofGridRenderer.rebuildGrid(detectedMode)  // UI op
                // ...
                return@withContext -1
            }
            tofGridRenderer.getGridSize()
        }
        // ...

        // ❌ Kemudian withContext(Main) LAGI untuk render grid
        withContext(Dispatchers.Main) {
            tofGridRenderer.updateGrid(...)
        }
    }
}
```

**Masalah:**  
ToF berjalan di 10Hz (8x8) atau 15Hz (4x4). Setiap frame ToF membutuhkan **2x context switch ke Main Thread**. Dengan kamera di 10 FPS dan ToF di 10 Hz, ini berarti Main Thread dipaksa menangani:
- 10x frame decode + bitmap render
- 10x ToF grid render (2 context switch masing-masing)
- YOLO overlay update
- IMU UI update (20Hz)
- Latency monitor update (5Hz)

Main Thread menjadi bottleneck karena **semua sensor bersaing waktu di thread yang sama**.

---

### 🚨 PENYEBAB #4 (CRITICAL — Firmware) — TARGET_FRAME_US = 100.000 μs (10 FPS Saja) di Firmware

**File:** [`firmware-vnetra.ino` L.186–187](file:///e:/Project/Skripsi/VNetra/firmware-vnetra/firmware-vnetra/firmware-vnetra.ino#L186-L187)

```cpp
static constexpr uint32_t TARGET_FRAME_US   = 100000;   // ~10 FPS
```

**Masalah:**  
ESP32-S3 dengan PSRAM OPI dan 240MHz CPU seharusnya mampu mencapai **15–25 FPS** pada JPEG kualitas 20 di resolusi VGA. Target hanya 10 FPS ini membatasi *ceiling* secara artifisial. Dikombinasikan dengan:
- Overhead WebSocket ESPAsync yang memproses setiap frame di async loop
- `ws.cleanupClients()` dipanggil setiap 2 detik di `loop()` yang potensial blocking

Hasilnya, effective FPS yang diterima Android jauh di bawah 10 karena ada jitter tambahan dari overhead pengiriman.

---

### 🚨 PENYEBAB #5 (CRITICAL) — `serviceScope.launch` di Dalam WebSocket `onMessage` Callback (Hot Path)

**File:** [`CameraStreamService.kt` L.321–337](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/service/CameraStreamService.kt#L321-L337)

```kotlin
override fun onMessage(ws: WebSocket, bytes: ByteString) {
    // ...
    serviceScope.launch {   // ❌ Launch coroutine baru SETIAP frame
        when (type) {
            FRAME_TYPE_JPEG -> { _frameFlow.emit(payload) }
            FRAME_TYPE_IMU  -> { emitImuPayload(payload) }
            FRAME_TYPE_TOF  -> { emitTofPayload(payload) }
        }
    }
}
```

**Masalah:**  
Setiap frame JPEG yang masuk (10/detik) → peluncuran coroutine baru. Setiap IMU (20/detik) → coroutine baru. Setiap ToF (10/detik) → coroutine baru. Total: **≈40 coroutine launch/detik** dari callback OkHttp. Ini overhead scheduling yang signifikan dan menyebabkan `CoroutineDispatcher.Default` sering dijemput dari banyak titik berbeda, meningkatkan latency suspend/resume.

---

## 📊 Ringkasan Temuan Lima Axis

---

### Axis 1: Correctness (Kebenaran)

| # | Lokasi | Severity | Masalah |
|---|--------|----------|---------|
| C1 | `CameraStreamActivity.kt` L.700 | **Required** | Label UI IMU keliru: `tvImuRollRate` diberi `imuData[2]` (Pitch Rate), bukan Roll Rate. Roll Rate seharusnya `imuData[3]`. |
| C2 | `YoloDetector.kt` L.360–363 | **Required** | `buildDetectionRect`: threshold `cx < 2.0f` untuk membedakan normalized vs absolute sangat rapuh — koordinat absolute `2.0` valid dan akan di-misclassify sebagai normalized. |
| C3 | `CameraStreamService.kt` L.321 | **Required** | `serviceScope.launch` di dalam `onMessage` tanpa guard `if (!stopped)` di beberapa path → coroutine bisa tetap launch setelah service di-stop. |
| C4 | `firmware-vnetra.ino` L.1313 | Nit | Log di TOF_InitTask mencantumkan `intTime=50` tapi kode actual set `30ms` untuk 8x8 (L.1305). Inkonsistensi dokumentasi. |
| C5 | `CameraStreamActivity.kt` L.725–744 | Required | `tofViewSize` cek `getGridSize()` dilakukan di `withContext(Main)` lalu hasilnya digunakan di Default thread → tidak ada jaminan nilai ini konsisten jika grid di-rebuild asinkron. |

---

### Axis 2: Readability & Simplicity

| # | Lokasi | Severity | Masalah |
|---|--------|----------|---------|
| R1 | `CameraStreamActivity.kt` | **Required** | File 1324 baris — jauh melampaui batas sehat 800 baris. Logika YOLO inference, ToF processing, TTS coordination, lifecycle, dan UI semua bercampur dalam satu class. |
| R2 | `CameraStreamActivity.kt` L.779–988 | Nit | `tofCollectJob` mengandung ~210 baris inline logic tanpa satu pun helper function yang diekstrak. |
| R3 | `firmware-vnetra.ino` | Optional | 1636 baris monolitik. Wajib dipecah menjadi modul: `camera.cpp`, `sensor_imu.cpp`, `sensor_tof.cpp`, `websocket_server.cpp`. |
| R4 | `CameraStreamService.kt` L.321 | Required | Nested lambda `serviceScope.launch { when(...) }` di dalam `WebSocketListener.onMessage` mempersulit tracing eksekusi. |

---

### Axis 3: Architecture

| # | Lokasi | Severity | Masalah |
|---|--------|----------|---------|
| A1 | `CameraManager.kt` | **Required** | `CameraManager` adalah **dead code** — tidak digunakan di mana pun. `CameraStreamService` mengelola WebSocket sendiri. Kelas ini sebaiknya dihapus atau dijadikan implementasi resmi. |
| A2 | `CameraStreamActivity.kt` | Required | Orchestrator (Activity) langsung melakukan AI inference, TTS coordination, ToF smoothing, terrain detection. Ini seharusnya di ViewModel/UseCase. |
| A3 | `CameraStreamService.kt` + `CameraStreamActivity.kt` | Nit | Duplikasi konstanta `FRAME_TYPE_JPEG`, `FRAME_HEADER_SZ` di kedua file (dan juga di firmware). Sebaiknya satu sumber kebenaran. |
| A4 | `firmware-vnetra.ino` L.1318 | **Required** | `TOF_Task` di-pin ke Core 0 sementara `IMU_Task` di Core 1. Namun `captureAndSend()` berjalan di `loop()` yang juga di Core 1 (default Arduino). I2C mutex bisa menyebabkan contention antara IMU Task dan loop() jika ada sensor read di sana. |
| A5 | Firmware L.588–591 | Optional | UDP sensor server mendengar di port 8081 tapi Android `UdpReceiver` mendengar di port 8080 — port mismatch! UDP sensor dari firmware dikirim ke `UDP_TARGET_PORT = 8080` (Android), bukan 8081. Ini sebenarnya benar (8081 untuk incoming UDP dari Android, 8080 untuk outgoing ke Android), tapi dokumentasinya confusing. |

---

### Axis 4: Security

| # | Lokasi | Severity | Masalah |
|---|--------|----------|---------|
| S1 | `firmware-vnetra.ino` L.606–609 | FYI | WiFi TX Power disetel ke `WIFI_POWER_19_5dBm` (maksimum). Di beberapa negara ini mungkin melanggar regulasi RF. Tidak kritikal untuk skripsi, tapi perlu didokumentasikan. |
| S2 | `CameraStreamService.kt` L.78–79 | FYI | `ACTION_STOP` dan `ACTION_EXIT_APP` menggunakan package name lama (`com.example.phase4_camera_eps_s3_mobile`) bukan package aktual (`com.airi.vnetra`). Ini bisa menyebabkan broadcast tidak diterima di beberapa konfigurasi Android. |
| S3 | `firmware-vnetra.ino` | Optional | WebSocket tidak memiliki autentikasi — siapa pun di network yang sama bisa connect ke `ws://[IP]/ws` dan melihat feed kamera. Acceptable untuk lab, tapi perlu dicatat di laporan. |

---

### Axis 5: Performance (Detail FPS Lag)

Ini adalah axis paling kritis. Penyebab-penyebab di atas (PENYEBAB #1–5) sudah dijabarkan. Berikut tambahan temuan performa:

| # | Lokasi | Severity | Masalah |
|---|--------|----------|---------|
| P1 | `YoloDetector.kt` L.247 | **Critical** | `Bitmap.createScaledBitmap` tanpa `recycle()` — GC pressure. Gunakan bitmap pool atau reuse buffer. |
| P2 | `YoloDetector.kt` L.248 | **Critical** | `IntArray(newWidth * newHeight)` alokasi setiap frame. Jadikan field class, alokasi sekali. |
| P3 | `CameraStreamActivity.kt` L.535–617 | **Critical** | YOLO inference dimulai dari dalam `withContext(Main)` — blok Main Thread unnecessarily. |
| P4 | `CameraStreamActivity.kt` L.595–597 | Required | `withContext(Dispatchers.Main + NonCancellable)` hanya untuk `isInferencing.set(false)` — overkill. AtomicBoolean thread-safe, set bisa dilakukan langsung di Default dispatcher. |
| P5 | `firmware-vnetra.ino` L.186 | Required | `TARGET_FRAME_US = 100000` (10 FPS cap). Naikkan ke 66666 (15 FPS) atau 50000 (20 FPS) untuk mencapai performa lebih baik dengan PSRAM/CPU yang ada. |
| P6 | `CameraStreamService.kt` L.321 | Required | `serviceScope.launch` per pesan (40/detik). Gunakan langsung `_frameFlow.tryEmit(payload)` tanpa launch untuk JPEG frame. |
| P7 | `ToFGridRenderer.kt` L.74–141 | Optional | `updateGrid()` sudah dioptimalkan dengan diff check. Sudah baik, tapi `gridLayout` masih merupakan `GridLayout` — `RecyclerView` akan lebih efisien untuk update parsial. |
| P8 | `CameraStreamActivity.kt` L.554–557 | Required | Render bitmap (`setImageBitmap`) terjadi di Main Thread untuk setiap frame. Pertimbangkan `SurfaceView` atau `TextureView` untuk hardware-accelerated rendering yang lebih efisien. |
| P9 | `firmware-vnetra.ino` L.424 | Nit | `ws.setNoDelay(true)` sudah diset — bagus. |
| P10 | `YoloDetector.kt` L.231 | Nit | `Log.i()` dipanggil setiap detection selesai dengan `results.size` dan top confidence — overhead logging di production. Ganti dengan `Log.d()` atau hapus. |

---

## 🔧 Rekomendasi Perbaikan Prioritas (Diurutkan by Impact)

### 🔴 Priority 1 — Perbaikan Langsung FPS

#### Fix 1.1: Hapus YOLO Launch dari Main Thread

```kotlin
// CameraStreamActivity.kt — startCollectingFrames()
// ❌ SEKARANG: withContext(Main) lalu launch inference di dalamnya
withContext(Dispatchers.Main) {
    binding.ivCameraFrame.setImageBitmap(bitmap)
    if (yoloDetector != null && isInferencing.compareAndSet(false, true)) {
        lifecycleScope.launch(Dispatchers.Default) { /* inference */ }
    }
}

// ✅ PERBAIKAN: Pisahkan render dari inference
withContext(Dispatchers.Main) {
    binding.ivCameraFrame.setImageBitmap(bitmap)
    updateFpsCounter(jpegBytes.size)
}
// Inference TIDAK di dalam withContext(Main):
if (yoloDetector != null && isInferencing.compareAndSet(false, true)) {
    // Sudah di Dispatchers.Default dari frameCollectJob
    runCatching {
        val rawResults = yoloDetector!!.detect(bitmap)
        val trackedResults = tracker.process(rawResults)
        // ...
        withContext(Dispatchers.Main) {
            binding.boundingBoxOverlay.setResults(...)
        }
    }
    isInferencing.set(false)
}
```

#### Fix 1.2: Pre-alokasi Buffer di YoloDetector

```kotlin
// YoloDetector.kt
class YoloDetector(...) {
    // Pre-alokasikan sekali di init
    private var resizedBitmap: Bitmap? = null
    private var cachedIntValues: IntArray? = null
    
    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        val scale = minOf(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        // Reuse bitmap jika ukuran sama
        val dst = resizedBitmap?.takeIf { it.width == newWidth && it.height == newHeight }
            ?: Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.RGB_565).also { resizedBitmap = it }
        
        val canvas = android.graphics.Canvas(dst)
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, newWidth.toFloat(), newHeight.toFloat()), null)
        
        val pixelCount = newWidth * newHeight
        if (cachedIntValues == null || cachedIntValues!!.size != pixelCount) {
            cachedIntValues = IntArray(pixelCount)
        }
        dst.getPixels(cachedIntValues!!, 0, newWidth, 0, 0, newWidth, newHeight)
        // ... lanjutkan pengisian inputBuffer
    }
    
    override fun close() {
        resizedBitmap?.recycle(); resizedBitmap = null
        // ...
    }
}
```

#### Fix 1.3: Naikkan Target FPS Firmware

```cpp
// firmware-vnetra.ino
// ❌ SEKARANG:
static constexpr uint32_t TARGET_FRAME_US = 100000; // 10 FPS

// ✅ PERBAIKAN:
static constexpr uint32_t TARGET_FRAME_US = 66666;  // 15 FPS
// atau 50000 untuk 20 FPS (test dulu apakah WiFi bandwidth cukup)
```

#### Fix 1.4: Hapus `serviceScope.launch` untuk JPEG Frame

```kotlin
// CameraStreamService.kt — onMessage (binary)
override fun onMessage(ws: WebSocket, bytes: ByteString) {
    if (stopped) return
    lastDataReceivedTime = System.currentTimeMillis()
    runCatching {
        val raw = bytes.toByteArray()
        if (raw.size < FRAME_HEADER_SZ) return
        val type = raw[0]
        val payload = raw.copyOfRange(FRAME_HEADER_SZ, raw.size)

        // ✅ Emit langsung tanpa launch untuk high-frequency frames
        when (type) {
            FRAME_TYPE_JPEG -> _frameFlow.tryEmit(payload)  // tryEmit = non-blocking
            FRAME_TYPE_HBEAT -> Log.d(TAG, "Heartbeat")
            // IMU dan TOF tetap perlu suspend (emitImuPayload), jadi masih butuh launch
            else -> serviceScope.launch { /* sensor frames */ }
        }
    }
}
```

#### Fix 1.5: Pisahkan ToF Grid Check dari withContext(Main)

```kotlin
// tofCollectJob: hindari withContext(Main) untuk operasi non-UI
// Ganti pendekatan: simpan grid size sebagai @Volatile field, update saat rebuildGrid()

@Volatile private var currentGridSize: Int = 0

// Di tofCollectJob, cukup:
val currentSize = currentGridSize  // Atomic read, tidak butuh Main context
if (tofData.size != currentSize) {
    // Request rebuild via post ke Main
    binding.root.post {
        tofGridRenderer.rebuildGrid(detectedMode)
        currentGridSize = tofGridRenderer.getGridSize()
    }
    return@collect  // Skip frame ini
}
```

---

### 🟡 Priority 2 — Perbaikan Arsitektur

#### Fix 2.1: Hapus CameraManager Dead Code

[`CameraManager.kt`](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/camera/CameraManager.kt) tidak digunakan. Hapus atau jadikan implementasi resmi menggantikan logika di `CameraStreamService`.

#### Fix 2.2: Perbaiki Label IMU yang Salah

```kotlin
// CameraStreamActivity.kt L.700 — SEKARANG SALAH:
binding.tvImuRollRate.text  = "Roll Rate : %5.1f°/s".format(imuData[2])  // ❌ Ini Pitch Rate

// ✅ PERBAIKAN (sesuai payload firmware [2]=ωx=Pitch Rate, [3]=ωy=Roll Rate):
binding.tvImuPitchRate.text = "Pitch Rate: %5.1f°/s".format(imuData[2])
binding.tvImuRollRate.text  = "Roll Rate : %5.1f°/s".format(imuData[3])
```

#### Fix 2.3: Perbaiki Package Name di ACTION_STOP

```kotlin
// CameraStreamService.kt L.78-79 — SEKARANG SALAH:
const val ACTION_STOP = "com.example.phase4_camera_eps_s3_mobile.ACTION_STOP"  // ❌

// ✅ PERBAIKAN:
const val ACTION_STOP = "com.airi.vnetra.ACTION_STOP"
const val ACTION_EXIT_APP = "com.airi.vnetra.ACTION_EXIT_APP"
```

---

### 🟢 Priority 3 — Optional / Nit

- **P10**: Ganti `Log.i()` di `YoloDetector.detect()` dengan `Log.d()` atau hapus di production build.
- **R3**: Refactor firmware ke modul `.h/.cpp` terpisah untuk maintainability skripsi.
- **C4**: Perbaiki inkonsistensi log `intTime=50` vs kode `30ms` di `TOF_InitTask`.

---

## 📐 Dead Code Identified

```
DEAD CODE IDENTIFIED:
- CameraManager.kt — tidak direferensikan dari mana pun (digantikan CameraStreamService)
→ Aman dihapus atau dijadikan abstraksi resmi
```

---

## 📋 Checklist Verdict

| Axis | Status | Catatan |
|------|--------|---------|
| ✅ Correctness | Request Changes | Label IMU salah (C1), ACTION package name salah (S2) |
| ✅ Readability | Acceptable | CameraStreamActivity.kt terlalu besar (1324 baris) |
| ✅ Architecture | Request Changes | CameraManager dead code, YOLO inference di Main Thread |
| ✅ Security | FYI | ACTION package name mismatch bisa blokir broadcast |
| 🔴 Performance | **BLOCK** | 5 penyebab lag FPS kritis ditemukan |

**Verdict: Request Changes — Tidak siap merge ke production sebelum perbaikan Priority 1 diimplementasikan.**

---

## 📊 Estimasi FPS Setelah Perbaikan

| Kondisi | Sebelum | Setelah Fix 1.1–1.5 |
|---------|---------|----------------------|
| Tanpa YOLO | 8–10 FPS | 13–15 FPS |
| Dengan YOLO (GPU) | 2–5 FPS | 8–12 FPS |
| Dengan YOLO (CPU) | 1–3 FPS | 5–8 FPS |
| ToF 8x8 aktif | −2 FPS (context switch) | −0.5 FPS |

> Catatan: Angka estimasi berdasarkan profil tipkal ESP32-S3 PSRAM + Android mid-range. Hasil aktual dapat bervariasi tergantung hardware.

---

## 🗺️ Diagram Alur Data & Bottleneck

```
[ESP32-S3]
  JPEG capture (10 FPS cap ← TARGET_FRAME_US)
      ↓ WebSocket TCP
[CameraStreamService.onMessage()]
  serviceScope.launch() ← 40x/detik launch overhead
      ↓ _frameFlow.emit()
[CameraStreamActivity.startCollectingFrames()]
  Dispatchers.Default
  → BitmapFactory.decodeByteArray()
  → withContext(Main) ← BOTTLENECK A
      → setImageBitmap()
      → launch(Default) ← YOLO start (still holds Main slot)
          → createScaledBitmap() ← ALLOC + GC BOTTLENECK B
          → IntArray(640*480) ← ALLOC BOTTLENECK C
          → 640x640 pixel loop
          → TFLite inference (50–800ms)
          → withContext(Main) ← overlay update
  [ToF tofCollectJob]
  → withContext(Main) #1 ← grid size check BOTTLENECK D
  → TerrainDetector.process()
  → withContext(Main) #2 ← grid render
  → TTS coordination
```

Seluruh `withContext(Main)` di atas bersaing untuk slot yang sama. Ini adalah root cause fundamental dari lag FPS.
