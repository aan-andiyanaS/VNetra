# Plan Implementasi: Real-time Latency (Ping) Monitor di Halaman Kamera

## 🎯 Tujuan
Menambahkan sebuah lapisan antarmuka (Overlay UI) pada halaman `CameraStreamActivity` yang berfungsi untuk memonitor waktu pemrosesan (*ping/latency* dalam milidetik) dari seluruh komponen sensor dan algoritma matematika. 

Tujuannya adalah untuk mengidentifikasi *bottleneck* performa aplikasi secara visual. Sistem ini harus pintar membedakan mana proses yang berjalan paralel (bersamaan) dan mana yang berjalan sekuensial (merunut).

---

## 🧠 Konsep Arsitektur (Untuk Developer)

Dalam aplikasi VNetra, kita menggunakan *Coroutines* (`Dispatchers.Default`) untuk mengelola aliran data sensor.
1. **Parallel / Concurrent (Berjalan Bersamaan):**
   - Coroutine untuk memproses *Frame Kamera* (JPEG Decode).
   - Coroutine untuk memproses *ToF & Sensor Logik* (Data Sensor).
   - *Syarat UI:* Tampilkan berdampingan/sejajar, lalu ambil dan sorot **nilai terbesarnya** (karena nilai terbesar ini yang menjadi *bottleneck* atau penentu kecepatan frame utama).
2. **Sequential / Merunut (Berjalan Berurutan):**
   - Di dalam *ToF Coroutine*, eksekusi algoritmanya merunut: `Smoothing EMA` ➔ `Formula E & H (Objek Depan)` ➔ `TerrainDetector (Formula J)`.
   - *Syarat UI:* Tampilkan secara terpisah (berurut/menurun) dengan nilai *ping* masing-masing.

---

## 🛠️ Langkah Eksekusi (High-Level Plan)

### Tahap 1: Persiapan UI (XML Layout)
1. Buka file `activity_camera_stream.xml`.
2. Tambahkan sebuah kontainer (misal: `LinearLayout` transparan hitam) di area yang tidak menutupi kamera secara total (contoh: pojok kanan atas atau di bawah status IMU).
3. Di dalamnya, tambahkan beberapa `TextView` untuk menampilkan metrik:
   - Ping Kamera (Paralel A)
   - Ping Total ToF (Paralel B)
   - Ping *Bottleneck* (Nilai Max dari Kamera vs ToF)
   - Rincian Ping ToF (Smoothing, Formula E/H, TerrainDetector)

### Tahap 2: Utility Pengukur Waktu (Kotlin)
1. Buat variabel state global/lokal di `CameraStreamActivity` untuk menampung metrik.
   ```kotlin
   @Volatile var pingCamera: Long = 0
   @Volatile var pingTofSmooth: Long = 0
   @Volatile var pingFormulaEH: Long = 0
   @Volatile var pingTerrain: Long = 0
   @Volatile var pingTotalTof: Long = 0
   ```
2. Gunakan `System.currentTimeMillis()` atau `measureTimeMillis { }` dari library Kotlin standard untuk membungkus blok kode target.

### Tahap 3: Implementasi Pengukuran di `frameCollectJob` (Kamera)
Bungkus bagian decoding JPEG ke Bitmap dan set UI:
```kotlin
val timeMs = measureTimeMillis {
    val bitmap = BitmapFactory.decodeByteArray(...)
    // update UI frame
}
pingCamera = timeMs
```

### Tahap 4: Implementasi Pengukuran di `tofCollectJob` (Merunut)
Di dalam collect ToF, hitung waktu secara terpisah untuk tiap fase:
1. **Fase Smoothing (EMA):** Hitung waktu yang dihabiskan dalam perulangan array *smoothing* dan *holdover*.
2. **Fase Formula E & H:** Hitung waktu yang dihabiskan untuk *calculate* ToF kolom tengah dan *process* FormulaH.
3. **Fase TerrainDetector:** Hitung waktu eksekusi `terrainDetector.process()`.
4. **Hitung Total Ping ToF:** Jumlahkan waktu dari Fase 1, 2, dan 3 menjadi `pingTotalTof`.

### Tahap 5: Pembaruan Tampilan Monitor (UI Update)
Buat satu fungsi/coroutine khusus yang meng-update teks UI setiap beberapa ratus milidetik (jangan di-update per *frame* agar tidak *flicker* dan terlalu berat).

**Logika Perbandingan (Paralel):**
```kotlin
val maxBottleneck = maxOf(pingCamera, pingTotalTof)
```

**Contoh Format Visual Teks UI yang Diharapkan:**
```text
=== SYSTEM PING MONITOR ===
[Parallel Processing]
Cam Decode : 12 ms
ToF Total  : 15 ms
---------------------------
► MAX BOTTLENECK : 15 ms

[Sequential ToF Details]
├─ Smoothing : 3 ms
├─ Formula E/H : 4 ms
└─ Terrain J : 8 ms
===========================
```

---

## ✅ Checklist Penerimaan (Acceptance Criteria)
- [ ] Angka waktu tampil secara statis di UI, diperbarui dengan mulus (misal ~5Hz atau 200ms sekali update).
- [ ] Proses parallel Kamera dan ToF terdeteksi jelas dan nilai terbesarnya secara otomatis disorot sebagai "MAX BOTTLENECK".
- [ ] Pemecahan proses (merunut) di dalam ToF tampil terstruktur tanpa merusak kalkulasi totalnya.
- [ ] Penambahan logika monitor waktu ini **TIDAK** menyebabkan beban kinerja (RAM/CPU) aplikasi menjadi *lag*.
