# Code Review Report — Putaran 2
**Tanggal:** 2026-07-12  
**Scope:** Seluruh kode Android (Kotlin) + Firmware pasca-perbaikan ADR-024 s/d ADR-027  
**Metode:** `/code-review-and-quality` (5-axis) + `/ponytail full`

---

## Ringkasan Eksekutif

| No | Tingkat | File | Temuan |
|----|---------|------|--------|
| 1  | KRITIS | TtsAlertManager.kt | Duplikasi masif Formula G: 80+ baris disalin-tempel 3x dalam satu file |
| 2  | KRITIS | TtsAlertManager.kt | TERRAIN_TRACKING_ID tidak terdefinisi di SpatialMappingUtils — referensi undefined |
| 3  | TINGGI | YoloDetector.kt | Duplikasi blok postprocess bounding box 2x — kandidat helper function |
| 4  | TINGGI | CameraStreamService.kt | Duplikasi penuh parsing IMU & ToF antara WebSocket dan UDP receiver |
| 5  | SEDANG | CameraStreamActivity.kt | latencyMonitorJob loop while(true) tanpa isActive |
| 6  | SEDANG | CameraStreamActivity.kt | File terlalu besar (1389 baris) — kandidat dekomposisi |
| 7  | SEDANG | YoloDetector.kt | scaleY dead variable — dideklarasikan tapi tidak pernah digunakan |
| 8  | OPTIMASI | SpatialMappingUtils.kt | B0..B3 seharusnya const val, bukan val biasa |
| 9  | OPTIMASI | YoloDetector.kt | supportGpu = true hardcoded menciptakan dead branch yang tidak pernah aktif |
| 10 | NITO | YoloDetector.kt | results.size == 0 seharusnya results.isEmpty() |

---

## Temuan Mendetail

---

### KRITIS — Poin 1: Duplikasi Masif Formula G di TtsAlertManager.kt

**File:** app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt  
**Lokasi duplikasi:**
- Asli: process() L169-229 (BENAR)
- Salinan 1: postProcessDetections() L322-383 (BROKEN)
- Salinan 2: SmartNavigationTts.processNavigationState() L500-563 (BROKEN)

Kode Formula G (80+ baris: mulai `var T = D_W0` sampai `lastCalculatedT[trackingId] = T`) disalin tempel 3x.
Pada salinan 1 dan 2, variabel `imuData`, `trackingId`, `dObj`, `objectLabel` TIDAK TERDEFINISI
di scope tersebut — ini broken code yang kemungkinan lolos karena Kotlin inner class lookup.

```kotlin
// Di postProcessDetections() — imuData, trackingId, dObj TIDAK ADA di scope ini:
fun postProcessDetections(activeClasses: Set<Int>) {
    val now = System.currentTimeMillis()
    // Formula G copy-paste 60 baris yang referensikan variabel tidak ada:
    if (imuData != null && imuData.size >= 9) {   // imuData = TIDAK ADA!
        val vHead = vHeadBase * dObj               // dObj = TIDAK ADA!
    }
    // Lalu baru logika yang benar:
    for (classId in activeClasses) { lastSeenTime[classId] = now }
    ...
}
```

**Solusi:** Hapus seluruh blok Formula G dari postProcessDetections() dan processNavigationState().
Kedua method tersebut tidak butuh Formula G. Cukup:

```kotlin
// postProcessDetections() SETELAH cleanup:
fun postProcessDetections(activeClasses: Set<Int>) {
    val now = System.currentTimeMillis()
    for (classId in activeClasses) { lastSeenTime[classId] = now }
    for ((trackingId, alerted) in alertFlags) {
        if (alerted && trackingId != SpatialMappingUtils.WALL_TRACKING_ID) {
            val lastSeen = lastSeenTime[trackingId] ?: 0L
            if (now - lastSeen > 3000L) {
                alertFlags[trackingId] = false
                dObjPrev.remove(trackingId)
                tsEspPrev.remove(trackingId)
                vRawHistory.remove(trackingId)
                lastCalculatedT.remove(trackingId)
            }
        }
    }
}
```

---

### KRITIS — Poin 2: TERRAIN_TRACKING_ID Tidak Terdefinisi

**Dirujuk di:**
- TtsAlertManager.kt L196, L349, L529 (3x)
- CameraStreamActivity.kt L848

**SpatialMappingUtils.kt hanya memiliki:**
```kotlin
const val WALL_TRACKING_ID = 999  // ada
// TERRAIN_TRACKING_ID = ???       // TIDAK ADA
```

**Solusi:** Tambahkan ke SpatialMappingUtils.kt:
```kotlin
const val WALL_TRACKING_ID    = 999
const val TERRAIN_TRACKING_ID = 998  // ID unik untuk terrain alert via Formula J
```

---

### TINGGI — Poin 3: Duplikasi Blok Postprocess YOLO

**File:** YoloDetector.kt L345-363 (Transposed) dan L401-419 (Standard)

Logika konversi koordinat bounding box ditulis identik dua kali:

```kotlin
// Kedua blok ini identik (bedanya hanya cara akses output[i][0] vs output[0][i]):
val cxAbsolute = if (cx < 2.0f) cx * INPUT_SIZE else cx
val cyAbsolute = if (cy < 2.0f) cy * INPUT_SIZE else cy
val wAbsolute  = if (w  < 2.0f) w  * INPUT_SIZE else w
val hAbsolute  = if (h  < 2.0f) h  * INPUT_SIZE else h
val left   = (cxAbsolute - wAbsolute / 2 - padX) / scale
...
val rect = RectF(left.coerceAtLeast(0f), top.coerceAtLeast(0f), ...)
```

**Solusi:** Ekstrak helper:
```kotlin
private fun buildDetectionRect(
    cx: Float, cy: Float, w: Float, h: Float,
    padX: Float, padY: Float, scale: Float,
    originalWidth: Int, originalHeight: Int
): RectF {
    val cxA = if (cx < 2f) cx * INPUT_SIZE else cx
    val cyA = if (cy < 2f) cy * INPUT_SIZE else cy
    val wA  = if (w  < 2f) w  * INPUT_SIZE else w
    val hA  = if (h  < 2f) h  * INPUT_SIZE else h
    return RectF(
        ((cxA - wA/2 - padX)/scale).coerceAtLeast(0f),
        ((cyA - hA/2 - padY)/scale).coerceAtLeast(0f),
        ((cxA + wA/2 - padX)/scale).coerceAtMost(originalWidth.toFloat()),
        ((cyA + hA/2 - padY)/scale).coerceAtMost(originalHeight.toFloat())
    )
}
```

---

### TINGGI — Poin 4: Duplikasi Parsing IMU dan ToF WebSocket vs UDP

**File:** CameraStreamService.kt

Blok parsing IMU (L327-344) identik dengan (L468-483).
Blok parsing ToF (L346-371) identik dengan (L485-507).
Total ~80 baris kode disalin antara WebSocket onMessage dan startUdpReceiver().

**Solusi:** Ekstrak dua helper suspend function:

```kotlin
private suspend fun emitImuPayload(payload: ByteArray) {
    when {
        payload.size >= 36 -> {
            val floats = FloatArray(9)
            java.nio.ByteBuffer.wrap(payload)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(floats)
            _imuFlow.emit(floats)
        }
        payload.size >= 24 -> {
            val floats = FloatArray(9)
            java.nio.ByteBuffer.wrap(payload, 0, 24)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(floats, 0, 6)
            _imuFlow.emit(floats)
        }
        // payload < 24B: corrupt, abaikan
    }
}

private suspend fun emitTofPayload(payload: ByteArray) {
    if (payload.size < 2) { Log.e(TAG, "TOF payload terlalu kecil"); return }
    val resMode  = payload[0].toInt() and 0xFF
    val numCells = resMode * resMode
    val distSize = numCells * 2
    if (payload.size < 1 + distSize) {
        Log.e(TAG, "TOF payload kurang: ${payload.size}B < ${1+distSize}B"); return
    }
    val ints = IntArray(numCells)
    val buf = java.nio.ByteBuffer.wrap(payload, 1, distSize)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    for (i in 0 until numCells) ints[i] = buf.get(i).toInt()
    _tofFlow.emit(ints)
}
```

---

### SEDANG — Poin 5: latencyMonitorJob while(true) Tanpa isActive

**File:** CameraStreamActivity.kt L569-574

```kotlin
// SAAT INI — while(true) tanpa isActive:
latencyMonitorJob = lifecycleScope.launch {
    while (true) {
        kotlinx.coroutines.delay(200)
        updateLatencyMonitorUi()
    }
}

// SEHARUSNYA:
latencyMonitorJob = lifecycleScope.launch {
    while (isActive) {   // idiomatis, aman cancel
        kotlinx.coroutines.delay(200)
        updateLatencyMonitorUi()
    }
}
```

---

### SEDANG — Poin 6: CameraStreamActivity.kt Terlalu Besar (1389 Baris)

File ini menangani terlalu banyak tanggung jawab:
- UI controller, frame collector, YOLO orchestrator,
- TTS trigger, ToF grid manager, latency monitor, permission handler.

Kandidat ekstraksi tanpa mengubah logika:
- TofGridManager.kt: rebuildTofGrid(), initTofGrid(), updateTofModeButtons() (~60 baris)
- LatencyMonitor.kt: state ping*, updateLatencyMonitorUi() (~40 baris)
- InferenceOrchestrator.kt: triggerInstantYoloTts() dan YOLO dispatch (~80 baris)

---

### SEDANG — Poin 7: scaleY Dead Variable di YoloDetector.kt

**File:** YoloDetector.kt L321

```kotlin
val scaleY = originalHeight.toFloat() / INPUT_SIZE   // TIDAK PERNAH DIGUNAKAN
```

Sisa dari refactor sebelumnya. Hapus baris ini.

---

### OPTIMASI — Poin 8: B0..B3 Seharusnya const val

**File:** SpatialMappingUtils.kt L80-83

```kotlin
// SAAT INI — val biasa (overhead getter runtime):
val B0 = D_LEFT
val B1 = D_LEFT + W_Z
val B2 = D_LEFT + 2 * W_Z
val B3 = D_LEFT + 3 * W_Z

// SEHARUSNYA — const val (inlined at compile time):
const val B0 = D_LEFT
const val B1 = D_LEFT + W_Z
const val B2 = D_LEFT + 2 * W_Z
const val B3 = D_LEFT + 3 * W_Z
```

---

### OPTIMASI — Poin 9: supportGpu Dead Branch

**File:** YoloDetector.kt L106 dan L161-164

```kotlin
val supportGpu = true   // selalu true, hardcoded

// Konsekuensi: blok ini TIDAK PERNAH aktif:
if (targetDelegate == DelegateMode.GPU && !supportGpu) {  // !true = false selalu
    targetDelegate = DelegateMode.CPU
}
```

Solusi: Hapus variabel dan blok kondisinya, atau dokumentasikan secara eksplisit
bahwa ini intentional bypass (biarkan GPU fallback ditangani oleh try-catch GpuDelegate).

---

### NITO — Poin 10: results.size == 0 Non-Idiomatis

**File:** YoloDetector.kt L394

```kotlin
if (results.size == 0) { ... }   // non-idiomatis
// Seharusnya:
if (results.isEmpty()) { ... }   // idiomatis Kotlin
```

---

## Prioritas Eksekusi

| Urutan | Poin | Alasan |
|--------|------|--------|
| 1 (Sekarang) | Poin 1 + 2 | KRITIS: broken logic + missing constant |
| 2 (Segera) | Poin 4 | TINGGI: duplikasi kode production-sensitive |
| 3 (Batch) | Poin 3, 5, 7 | SEDANG: refactor + dead code |
| 4 (Opsional) | Poin 6 | Dekomposisi file besar — jangka panjang |
| 5 (Nito) | Poin 8, 9, 10 | Gaya + minor optimization |

---

## Catatan Akhir

Kode sudah jauh lebih bersih setelah ADR-027.
Temuan terbesar (Formula G copy-paste 3x) kemungkinan masuk saat development iteratif cepat.

**Skor Kesehatan Kode (perkiraan):**
- Pasca ADR-027: 7.5/10
- Target setelah eksekusi Poin 1-5: 9/10
