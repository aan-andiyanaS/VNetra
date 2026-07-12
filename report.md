# Laporan Code Review Menyeluruh — Proyek VNetra
*Tanggal: 12 Juli 2026 | Scope: Seluruh codebase Android (Kotlin) + Firmware (C++)*

---

## Ringkasan Eksekutif
Kode secara umum sudah **cukup solid** dan terstruktur dengan baik. Arsitektur sensor (WebSocket + UDP) sudah optimal pasca-ADR-023. Namun, ditemukan beberapa **potensi bug berbahaya** (salah satunya menjadi penyebab crash kompilasi), beberapa **kebocoran sumber daya**, dan sejumlah area yang bisa dioptimalkan tanpa mengorbankan akurasi.

---

## 🔴 KRITIS: Bug Deklarasi Ganda (Compile Error Potensial)

### 1. `isCameraActive` Dideklarasikan Dua Kali — `firmware-vnetra.ino`
**Prioritas: TINGGI** | **File**: [firmware-vnetra.ino](file:///e:/Project/Skripsi/VNetra/firmware-vnetra/firmware-vnetra/firmware-vnetra.ino#L99)

Akibat refaktor ADR-023 yang tidak bersih, variabel `isCameraActive` kini muncul **dua kali** di level global dengan deklarasi yang berbeda. Ini adalah bug yang langsung menyebabkan kegagalan kompilasi (*"redefinition of isCameraActive"*) di Arduino IDE atau PlatformIO.

```cpp
// Baris 99 (BARU, ditambahkan saat ADR-023):
volatile bool isCameraActive = true;

// Baris 244 (LAMA, sudah ada sebelumnya):
bool isCameraActive = true;
```
**Perbaikan**: Hapus deklarasi di baris 99 dan pertahankan deklarasi lama di baris 244.

---

### 2. `ws_mutex` Masih Dideklarasikan tapi Tidak Pernah Diinisialisasi — `firmware-vnetra.ino`
**Prioritas: TINGGI** | **File**: [firmware-vnetra.ino](file:///e:/Project/Skripsi/VNetra/firmware-vnetra/firmware-vnetra/firmware-vnetra.ino#L102)

Variabel `ws_mutex` (baris 102) masih dideklarasikan, namun perintah inisialisasinya (`ws_mutex = xSemaphoreCreateMutex()`) sudah dihapus dari `setup()` saat ADR-023. Variabel yang tidak diinisialisasi akan bernilai `NULL`. Meskipun kini tidak ada kode yang memanggil `xSemaphoreTake(ws_mutex, ...)`, deklarasi ini merupakan *dead code* yang menyesatkan dan bisa memicu *crash* jika penambahan fitur di masa depan secara tidak sengaja menggunakannya tanpa sadar bahwa ia tidak pernah dibuat.

```cpp
// Baris 102 — sisa deklarasi yang tidak digunakan:
SemaphoreHandle_t ws_mutex;   // Proteksi ws.binaryAll() dari multiple FreeRTOS tasks
```
**Perbaikan**: Hapus baris deklarasi ini.

---

### 3. Konstanta Ganda `TARGET_FRAME_MS` — `firmware-vnetra.ino`
**Prioritas: SEDANG** | **File**: [firmware-vnetra.ino](file:///e:/Project/Skripsi/VNetra/firmware-vnetra/firmware-vnetra/firmware-vnetra.ino#L194)

Saat penghapusan *double FPS limiter*, variabel `last_frame_time` dan konstanta `TARGET_FRAME_MS` tetap tertinggal di scope global namun tidak lagi digunakan di manapun.

```cpp
// Baris 194 — konstanta yatim piatu:
static constexpr unsigned long TARGET_FRAME_MS = 100;

// Baris 208 — variabel yatim piatu:
unsigned long last_frame_time = 0;

// Baris 206 — variabel yatim piatu:
uint32_t last_ack_time = 0;
```
**Perbaikan**: Hapus ketiga baris ini.

---

## 🟡 SEDANG: Potensi Bug & Kebocoran

### 4. Kebocoran Bitmap di `YoloDetector.kt` — Double Resize
**Prioritas: SEDANG** | **File**: [YoloDetector.kt](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/model/YoloDetector.kt#L255)

Pada fungsi `detect()`, bitmap diubah ukurannya dua kali. Pertama di `detect()` (`Bitmap.createScaledBitmap` ke ukuran 640x640) dan kedua di dalam `convertBitmapToByteBuffer()` (resize ulang dengan letterbox). Bitmap pertama dari `detect()` tidak pernah digunakan untuk inferensi (karena `convertBitmapToByteBuffer` membuat bitmap baru dengan rasio yang benar), namun tetap dialokasikan di memori dan membutuhkan waktu ekstra.

```kotlin
// detect() — L255: Resize pertama yang TIDAK digunakan:
val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
convertBitmapToByteBuffer(resizedBitmap) // <-- resizedBitmap tidak dipakai di sini!

// convertBitmapToByteBuffer() — L290: Resize kedua (yang benar, dengan letterbox):
val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
```
**Perbaikan**: Hapus baris `val resizedBitmap = ...` di `detect()` dan ubah pemanggilan `convertBitmapToByteBuffer(resizedBitmap)` menjadi `convertBitmapToByteBuffer(bitmap)` agar bitmap asli langsung diproses oleh fungsi letterbox.

---

### 5. Potensi Race Condition pada `isInferencing` di `CameraStreamActivity.kt`
**Prioritas: SEDANG** | **File**: [CameraStreamActivity.kt](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt#L156)

Variabel `isInferencing` dideklarasikan sebagai `var` biasa namun dibaca dan ditulis dari dua *dispatcher* berbeda (`Dispatchers.Main` dan `Dispatchers.Default`). Meskipun di JVM penugasan nilai primitif/referensi bersifat atomik, pola *check-then-act* yang ada tidak bersifat atomik.

```kotlin
// Baris 499-500: Dibaca dari Main, lalu di-set ke true:
if (!isInferencing && yoloDetector?.modelStatus != ModelStatus.NONE) {
    isInferencing = true
    // ... lalu lompat ke Dispatchers.Default
```
Secara teoritis, dua frame bisa lolos pengecekan `!isInferencing` secara bersamaan jika *scheduler* tidak kooperatif. Gunakan `@Volatile` atau `AtomicBoolean` untuk menjamin keamanan antar-*thread*.

**Perbaikan**: Ubah deklarasi menjadi:
```kotlin
private val isInferencing = java.util.concurrent.atomic.AtomicBoolean(false)
```

---

### 6. Potensi `IndexOutOfBoundsException` pada ToF `tofViews`
**Prioritas: SEDANG** | **File**: [CameraStreamActivity.kt](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt#L628)

Pada tofCollectJob, ada pengecekan `if (tofData.size != tofViews.size)` yang akan `return@withContext` jika ukuran berbeda. Namun, selama proses `rebuildTofGrid()` (yang menghapus dan membuat ulang view-view), ada jendela waktu kecil di mana `tofViews` sudah berisi *array* baru yang kosong namun `addView()` ke GridLayout belum selesai. Jika callback ToF datang tepat pada saat itu, akses ke `tofViews[i]` bisa gagal.

---

## 🟢 OPTIMASI (Tanpa Mengubah Akurasi)

### 7. Padding Batas `postprocessBoxes` Tidak Akurat — `YoloDetector.kt`
**Prioritas: RENDAH** | **File**: [YoloDetector.kt](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/model/YoloDetector.kt#L319)

Pada `postprocessBoxes()`, nilai `padX` dan `padY` dihitung berdasarkan dimensi gambar *asli* (`originalWidth`, `originalHeight`), namun padding seharusnya dihitung berdasarkan dimensi gambar *setelah di-resize* (dalam ruang koordinat `INPUT_SIZE`). Ini adalah sumber ketidakakuratan kecil pada posisi bounding box yang ditampilkan, terutama untuk gambar dengan rasio aspek yang jauh dari 1:1 (misalnya gambar portrait atau landscape yang sangat lebar).

```kotlin
// Baris 319-321 (Kurang presisi):
val scale = Math.min(INPUT_SIZE.toFloat() / originalWidth, INPUT_SIZE.toFloat() / originalHeight)
val padX = (INPUT_SIZE - originalWidth * scale) / 2f   // <-- originalWidth belum di-scale
val padY = (INPUT_SIZE - originalHeight * scale) / 2f  // <-- originalHeight belum di-scale
```
Nilai `padX` dan `padY` yang benar menggunakan `newWidth = (originalWidth * scale).toInt()`:
```kotlin
val padX = (INPUT_SIZE - (originalWidth * scale).toInt()) / 2f
val padY = (INPUT_SIZE - (originalHeight * scale).toInt()) / 2f
```

---

### 8. `CameraStreamService.kt` — Coroutine PING Bocor saat WS Tertutup
**Prioritas: RENDAH** | **File**: [CameraStreamService.kt](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/service/CameraStreamService.kt#L274)

Pada `onOpen`, sebuah coroutine baru diluncurkan untuk mengirimkan `PING` setiap 1 detik. Kondisi berhentinya adalah `while (isActive && activeWebSocket == ws)`. Namun, jika `serviceScope` tidak di-*cancel* (misalnya saat terjadi koneksi ulang), coroutine PING lama dari koneksi sebelumnya mungkin masih berjalan selama beberapa siklus hingga `activeWebSocket` berubah.

---

### 9. Penggunaan `Math.abs()` / `Math.min()` Java — `CameraStreamActivity.kt`
**Prioritas: SANGAT RENDAH** | **File**: [CameraStreamActivity.kt](file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt#L405)

Beberapa tempat di kode Kotlin masih menggunakan fungsi Java `Math.abs()`, `Math.min()`, `Math.max()`. Di Kotlin, tersedia idiom yang lebih bersih dan idiomatis menggunakan `kotlin.math.abs()`, `minOf()`, `maxOf()`.

```kotlin
// Ganti ini:
Math.abs(diffX) > 80f && Math.abs(velocityX) > 100f

// Dengan ini (lebih idiomatis Kotlin):
diffX.absoluteValue > 80f && velocityX.absoluteValue > 100f
```

---

## Tabel Ringkasan

| No | Severity | File | Masalah | Dampak |
|----|----------|------|---------|--------|
| 1 | 🔴 KRITIS | `firmware-vnetra.ino` | Deklarasi ganda `isCameraActive` | **Crash saat kompilasi** |
| 2 | 🔴 KRITIS | `firmware-vnetra.ino` | `ws_mutex` dideklarasikan tapi tidak pernah diinisialisasi | Crash potensial / Dead code menyesatkan |
| 3 | 🟡 SEDANG | `firmware-vnetra.ino` | `TARGET_FRAME_MS`, `last_frame_time`, `last_ack_time` tidak digunakan | Dead code, memori sedikit terbuang |
| 4 | 🟡 SEDANG | `YoloDetector.kt` | Double resize bitmap di `detect()` | Performa turun, alokasi memori mubazir |
| 5 | 🟡 SEDANG | `CameraStreamActivity.kt` | `isInferencing` bukan *thread-safe* | Potensi dua inferensi berjalan bersamaan |
| 6 | 🟡 SEDANG | `CameraStreamActivity.kt` | Jendela `tofViews` saat `rebuildTofGrid` | Potensi `IndexOutOfBoundsException` |
| 7 | 🟢 OPTIMASI | `YoloDetector.kt` | Kalkulasi `padX/padY` kurang presisi | Posisi bounding box sedikit bergeser |
| 8 | 🟢 OPTIMASI | `CameraStreamService.kt` | Coroutine PING bocor saat reconnect | Overhead CPU kecil |
| 9 | 🟢 OPTIMASI | `CameraStreamActivity.kt` | Penggunaan `Math.*` Java (non-idiomatic) | Tidak ada dampak performa, hanya gaya |
