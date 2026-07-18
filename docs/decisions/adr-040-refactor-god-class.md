# ADR-040: Refactor CameraStreamActivity (God Class)

## Status
Accepted

## Date
2026-07-18

## Context
File `CameraStreamActivity.kt` telah berkembang menjadi *God Class* (±1500 baris kode) yang menangani berbagai tanggung jawab:
1. Menerima data stream dari ESP32 (Firmware) via Service.
2. Mengontrol *lifecycle* UI dan layouting (termasuk Grid ToF overlay).
3. Memproses deteksi Yolo dan mengelola tracking/TTC.
4. Menentukan instruksi suara berdasarkan *debounce* gerakan dan navigasi (TTS).
5. Logging, debugging, dan manajemen dataset.

Ukuran *file* yang terlalu besar membuatnya sulit di-*maintenance*, tidak sejalan dengan prinsip *Clean Code* (Single Responsibility Principle). Namun, mengingat keterbatasan *resource* pada perangkat genggam (*SoC Helio G99*), kita tidak bisa begitu saja menggunakan *layering* yang terlampau dalam atau desain arsitektural berat (*Clean Architecture* murni) yang memicu *GC (Garbage Collection) Pressure*.

## Decision
Kami memutuskan untuk melakukan refactoring secara parsial dan taktis, dengan pedoman "Doubt-Driven Development" dan batasan "Ponytail Full" (solusi minimalis yang works).

Ekstraksi yang disetujui:
1. **`NavigationCoordinator.kt`**: Di-*extract* untuk menangani logika domain peringatan navigasi, termasuk filter rotasi kepala (`isHeadRotating`) dan prioritas *Text-To-Speech* (TTS).
2. **`ToFGridRenderer.kt`**: Di-*extract* untuk merangkum seluruh logika *UI overlay* grid sensor jarak (ToF), manajemen warna HSV (`getColorForDistance`), dan *holdover* UI cell.

Ekstraksi yang dibatalkan:
- **`VisionPipeline.kt`**: Logika dekode Bitmap dan inferensi Yolo (serta Tracking) diputuskan **TETAP** di `CameraStreamActivity`. Memindahkan fungsi ini akan memaksa pengiriman *object/callbacks* (Bitmap, List deteksi, UI Update) ke objek baru secara terus menerus (pada frekuensi sangat tinggi, setiap *frame* kamera). Ini bertentangan dengan kebutuhan menghemat *GC pressure* (Helio G99) dan kaidah *YAGNI*. Fungsi `startCollectingFrames()` saat ini masih sangat mudah dibaca (~80 baris) dan mengontrol *Coroutine Dispatcher* dengan baik.

## Consequences
- **Positif:** 
  - `CameraStreamActivity.kt` berkurang ratusan baris (~1308 baris sekarang) dan mendelegasikan tugas ke kelas yang relevan.
  - Memisahkan domain audio navigasi dari logika kamera/stream, memudahkan perbaikan logika navigasi kedepannya.
  - Render Grid ToF lebih modular dan dapat diperbarui/dikondisikan tanpa mengotori *Activity*.
- **Negatif / Trade-off:**
  - `CameraStreamActivity` masih tergolong cukup besar untuk sebuah UI Controller, tetapi inilah letak "Sweet Spot" atau kompromi antara kebersihan kode dan performa komputasi di ponsel kelas menengah.
