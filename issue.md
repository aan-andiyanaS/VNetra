## ADR-028: Resolusi Bug Kritis & Optimasi Lanjutan (Putaran 2)
- **Status:** Proposed
- **Tanggal:** 12 Juli 2026

### 1. Konteks
Berdasarkan `report_pt2.md`, terdapat 10 temuan baru setelah implementasi ADR-027. Temuan ini berkisar dari bug kritis (potensi *compile error* / *undefined reference*) hingga duplikasi kode yang memperbesar ukuran file tanpa alasan kuat.

### 2. Breakdown Penyelesaian (Task Breakdown)
Sesuai prinsip **Ponytail** (simplifikasi maksimal, hapus dead code) dan **Incremental Implementation**, kita akan membagi eksekusi ini menjadi beberapa tahap:

#### Tahap 1: Perbaikan Bug Kritis (Poin 1 & 2)
Ini wajib dilakukan segera karena kode saat ini mengandung referensi ke variabel yang tidak terdefinisi (*broken scope*).
- **[ ] `TtsAlertManager.kt`**: Hapus blok duplikat Formula G (sekitar 60 baris) di dalam `postProcessDetections()` dan `SmartNavigationTts.processNavigationState()`. Kedua fungsi ini tidak perlu menghitung ulang *threshold* adaptif (T), dan referensi ke `imuData`, `trackingId`, `dObj` di dalamnya adalah *broken code*.
- **[ ] `SpatialMappingUtils.kt`**: Tambahkan konstanta yang hilang agar kompilasi tidak gagal:
  ```kotlin
  const val TERRAIN_TRACKING_ID = 998
  ```

#### Tahap 2: Refactoring Duplikasi (Poin 3 & 4)
Menghilangkan duplikasi blok logika yang rentan terhadap inkonsistensi jika diubah di masa depan.
- **[ ] `YoloDetector.kt`**: Ekstrak blok kalkulasi `left, top, right, bottom` dari *standard* dan *transposed* output menjadi satu *helper function* privat `buildDetectionRect()`.
- **[ ] `CameraStreamService.kt`**: Ekstrak blok parsing *byte array* IMU (v1/v2) dan ToF menjadi dua fungsi privat: `emitImuPayload(payload: ByteArray)` dan `emitTofPayload(payload: ByteArray)`. Ganti blok duplikat di WebSocket `onMessage` dan UDP `startUdpReceiver` dengan pemanggilan fungsi ini.

#### Tahap 3: Perbaikan Stabilitas & Kebersihan (Poin 5, 7, 8, 9, 10)
- **[ ] `CameraStreamActivity.kt`**: Ganti `while (true)` menjadi `while (isActive)` pada `latencyMonitorJob` agar coroutine bisa dihentikan dengan bersih.
- **[ ] `YoloDetector.kt`**: Hapus deklarasi *dead variable* `scaleY`. Hapus bypass `val supportGpu = true` beserta blok kondisional matinya. Ubah `results.size == 0` menjadi `results.isEmpty()`.
- **[ ] `SpatialMappingUtils.kt`**: Ubah `val B0`, `B1`, `B2`, `B3` menjadi `const val` agar di-*inline* saat kompilasi.

> **Catatan (Poin 6):** Dekomposisi `CameraStreamActivity.kt` (1389 baris) ditunda dan tidak dimasukkan ke ADR ini agar tidak menimbulkan *merge conflict* besar atau mematahkan fitur yang sedang stabil, mengingat batas waktu pengembangan.

### 3. Konsekuensi
- **Positif:** Mengurangi potensi *crash* di *runtime* atau kesalahan kompilasi karena variabel yang tidak di-import/didefinisikan.
- **Positif:** Ukuran file lebih ringkas (berkurang ~150 baris duplikasi, pembacaan lebih mudah).
- **Positif:** Mencegah *coroutine leak* saat terjadi putus-nyambung jaringan pada `latencyMonitorJob`.
