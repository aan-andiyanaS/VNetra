# ADR-038: Pemisahan Komputasi ToF dari Main Thread untuk Mengatasi Frame Drop

## Status
Accepted

## Date
2026-07-18

## Context
Aplikasi VNetra mengalami masalah performa yang signifikan, yaitu *frame rate* (FPS) dari aliran kamera sering turun dari 10 FPS menjadi 3-7 FPS. Setelah diinvestigasi secara menyeluruh (Doubt-Driven Development & Ponytail), akar permasalahannya bukanlah pada *firmware* ESP32 atau jaringan WebSocket, melainkan pada **bottleneck di Main Thread (UI Thread) Android**.

Di dalam `CameraStreamActivity.kt`, terdapat `tofCollectJob` yang berjalan pada frekuensi 10Hz (setiap 100ms). Sebelumnya, seluruh proses pengolahan data Time-of-Flight (ToF) yang mencakup:
1. *Exponential Moving Average* (EMA) smoothing
2. Pengecekan tumpang tindih (*centroid check*) dengan deteksi YOLO
3. Perhitungan warna cell (HSV ke ARGB)
4. Update UI untuk 64 cell (`setText` dan `setBackgroundColor`)

Semuanya dilakukan di dalam **satu blok `withContext(Dispatchers.Main)`**. Proses ini sangat membebani Main Thread (memakan waktu ~15-30ms). Karena Main Thread tidak bisa mengerjakan dua hal secara bersamaan, frame kamera yang sudah siap di-*decode* di `Dispatchers.Default` terpaksa harus **menunggu** blok ToF ini selesai sebelum bisa di-render menggunakan `setImageBitmap()`. Waktu tunggu inilah yang menyebabkan terjadinya *frame drop*.

Selain itu, variabel `smoothedTofData` dan `holdoverCount` dideklarasikan sebagai atribut *class* (field) padahal hanya digunakan oleh `tofCollectJob`, yang berpotensi menimbulkan *race condition* jika tidak ditangani dengan hati-hati.

## Decision
Menerapkan pemisahan tugas (*Separation of Concerns*) secara tegas berdasarkan *Thread* (Ponytail: "Kirim ke Main Thread hanya yang memang harus di-Main"). 

Kita melakukan *refactoring* pada `tofCollectJob` menjadi 3 fase utama:

1. **Fase Guard (Main Thread - Kondisional):** Hanya dijalankan untuk memeriksa resolusi (16 vs 64 cell) dan membangun ulang *grid* UI jika diperlukan.
2. **Fase Komputasi (Default Thread - Pekerja Keras):** Seluruh logika matematis (EMA *smoothing*, hitung *holdover*, pemetaan jarak ke warna, dan pengecekan centroid YOLO) dipindahkan sepenuhnya ke `Dispatchers.Default`. Hasil akhirnya disimpan dalam *array local* (`cellTexts` dan `cellColors`).
   - Variabel state `smoothedTofData` dan `holdoverCount` dihapus dari tingkat *class* dan dipindahkan ke dalam *local coroutine state* agar bersifat *thread-safe* secara bawaan (*by design*).
3. **Fase Render (Main Thread - Sangat Ringan):** Main Thread hanya bertugas melakukan *assignment* murni (`setText` dan `setBackgroundColor`) dari *array* yang sudah disiapkan, tanpa ada kalkulasi apapun.

## Consequences
- **Performa Membaik Drastis:** Main Thread terbebas dari kalkulasi berat (kini hanya ~2-3ms per 100ms untuk ToF). Frame kamera tidak lagi menumpuk atau menunggu, sehingga FPS akan stabil mendekati kemampuan *firmware* (sekitar 8-10 FPS).
- **Thread-Safety yang Elegan:** Penghapusan field `smoothedTofData` dan `holdoverCount` serta menjadikannya sebagai *local state* di dalam coroutine membuat kode kebal dari *race condition* tanpa perlu menggunakan mekanisme `synchronized` atau `@Volatile`.
- **Clean Code:** Tanggung jawab kode lebih terarah. Main Thread didedikasikan secara eksklusif untuk rendering View, sedangkan pengolahan data intensif didorong ke barisan pekerja (`Dispatchers.Default`).
