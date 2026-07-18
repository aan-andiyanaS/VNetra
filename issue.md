# Refactor `CameraStreamActivity` (God Class) & Evaluasi GC Pressure (ADR-040)

## Deskripsi Masalah
File `CameraStreamActivity.kt` membesar secara signifikan hingga menyentuh angka lebih dari 1500 baris. Ini bertentangan dengan prinsip **Clean Code** karena activity tersebut menangani logika *stream* data firmware, deteksi Yolo (Vision Pipeline), manajemen peringatan/TTS, dan perhitungan UI Grid ToF secara bersamaan. Hal ini mempersulit perbaikan atau ekstensi fitur di kemudian hari. 

Namun, berhubung VNetra dijalankan pada SoC kelas menengah ke bawah (*Helio G99*), kita tidak dapat dengan bebas memecah kode menjadi banyak *layers/abstractions* yang murni OOP karena akan menghasilkan *Garbage Collection (GC) Pressure* pada *Main Thread* atau *Default Dispatcher*.

## Penyelesaian
Berdasarkan pendekatan **Doubt-Driven Development** dan pembatasan struktural ala **Ponytail Full**, kami mengekstrak komponen yang memiliki efek paling ringan terhadap *GC pressure* namun memberikan dampak besar bagi kebersihan modul:

1. **Memisahkan `NavigationCoordinator.kt`**
   - Logika penentuan arah navigasi, filter batas putar kepala (`isHeadRotating`), serta *debounce* untuk *Text-To-Speech* (TTS) peringatan Yolo (Instan) dipindahkan ke `NavigationCoordinator`.
   - Hal ini membuat domain pergerakan tidak lagi bercampur dengan domain koneksi service.

2. **Memisahkan `ToFGridRenderer.kt`**
   - Pengaturan UI seperti transisi gradasi warna HSV untuk *grid cells*, manipulasi translasi layout ToF, dan pemrosesan `smoothed` data dari sensor diekstrak.
   - Activity utama kini hanya memanggil fungsi-fungsi tingkat tinggi seperti `tofGridRenderer.updateGrid()`.

3. **Membatalkan Ekstraksi `VisionPipeline.kt` (YAGNI)**
   - Saat mengevaluasi `startCollectingFrames()`, kami mendapati bahwa logika loop pengolahan *bitmap* dan *bounding box* sudah tertulis cukup rapi (±80 baris) dan secara cermat menangani konteks Coroutine (berpindah antara *Default* dan *Main* thread). 
   - Memecahnya akan menghasilkan interaksi pemanggilan balasan (*callbacks*) dan *passing parameter* (seperti image bytes, model tracker, manager jarak) dengan frekuensi 10-20x per detik (*per-frame*). Ini hanya akan menciptakan **Over-engineering** dan memicu performa lag akibat objek-objek sementara yang perlu dikutip oleh GC. Oleh karena itu, ekstraksi `VisionPipeline` dengan tegas dibatalkan.

## Dampak (Impact)
- Total baris kode `CameraStreamActivity` berkurang menjadi ~1308 baris.
- Terbukti stabil dan telah lolos uji *Compile & Build (AssembleDebug)* dengan sukses.
- Kompromi performa vs arsitektur dapat terjaga. Detail keputusan lebih rinci direkam di dalam `docs/decisions/adr-040-refactor-god-class.md`.
