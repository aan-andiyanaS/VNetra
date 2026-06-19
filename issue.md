# Plan Perbaikan Bug: Potensi Deadlock Inference AI

**Berdasarkan Laporan**: report.md (Poin 2)
**Tingkat Keparahan**: Tinggi
**Lokasi File**: `app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt`

## 1. Latar Belakang Masalah
Pada fungsi `startCollectingFrames()`, aplikasi menggunakan *flag* `isInferencing` untuk mencegah eksekusi prediksi AI yang menumpuk. *Flag* ini di-set `true` sebelum proses deteksi dimulai, dan di-set `false` setelah proses deteksi dan update UI selesai. 

Masalah terjadi karena pemanggilan `detector.detect(bitmap)` maupun pembaruan UI di dalamnya tidak dibungkus dengan perlindungan `try...finally`. Apabila terjadi *Exception* (seperti *memory leak* sementara atau kerusakan pada *bitmap*), proses *coroutine* tersebut akan terhenti seketika dan melompat keluar tanpa pernah mengeksekusi baris `isInferencing = false`. Akibatnya, status inference akan tertahan di nilai `true` untuk selamanya (Deadlock), menyebabkan fitur deteksi AI mati total meskipun *streaming* kamera tetap berjalan.

## 2. Tujuan Perbaikan
Memastikan bahwa status *flag* `isInferencing` selalu dikembalikan menjadi `false` tidak peduli apakah proses deteksi YOLO berhasil diselesaikan dengan baik ataupun mengalami error/gagal di tengah jalan. Hal ini mencegah matinya fungsi asisten AI secara permanen dan memastikan keamanan pengguna saat terjadi gangguan memori sementara.

## 3. Langkah Implementasi secara Detail

### 3.1. Lokasi Kode yang Akan Diubah
Buka file: `app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt`
Cari blok kode yang bertanggung jawab atas **AI Inference** di dalam fungsi `startCollectingFrames()` (berada di sekitar baris 487-504).

### 3.2. Referensi Kode Saat Ini (Sebelum Diperbaiki)
```kotlin
// AI Inference
if (!isInferencing && yoloDetector?.modelStatus != ModelStatus.NONE) {
    isInferencing = true
    val detector = yoloDetector
    if (detector != null) {
        lifecycleScope.launch(Dispatchers.Default) {
            val results = detector.detect(bitmap)
            withContext(Dispatchers.Main) {
                if (!isDestroyed && !isFinishing && !isAkhiring) {
                    binding.boundingBoxOverlay.setResults(results, bitmap.width.toFloat(), bitmap.height.toFloat())
                }
                isInferencing = false // <--- TITIK RAWAN DEADLOCK JIKA ADA EXCEPTION SEBELUM BARIS INI
            }
        }
    } else {
        isInferencing = false
    }
}
```

### 3.3. Kode Solusi (Setelah Diperbaiki)
Ubah blok kode di atas dengan menambahkan struktur `try...catch...finally`. Pastikan pengaturan `isInferencing = false` diletakkan **di dalam blok `finally`**. 

Berikut adalah bentuk kode implementasi yang disarankan dan aman terhadap coroutine:

```kotlin
// AI Inference
if (!isInferencing && yoloDetector?.modelStatus != ModelStatus.NONE) {
    isInferencing = true
    val detector = yoloDetector
    if (detector != null) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // 1. Lakukan proses deteksi di background
                val results = detector.detect(bitmap)
                
                // 2. Jika berhasil, update UI di Main Thread
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing && !isAkhiring) {
                        binding.boundingBoxOverlay.setResults(results, bitmap.width.toFloat(), bitmap.height.toFloat())
                    }
                }
            } catch (e: Exception) {
                // Tangkap error jika terjadi agar tidak membatalkan seluruh coroutine parent
                // jika bukan CancellationException
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("CameraStreamActivity", "Error during AI inference", e)
                } else {
                    throw e
                }
            } finally {
                // 3. Pastikan flag selalu direset apapun yang terjadi
                // Gunakan NonCancellable agar flag tetap di-reset meskipun parent job di-cancel
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                    isInferencing = false
                }
            }
        }
    } else {
        isInferencing = false
    }
}
```

## 4. Catatan Penting
- **Penggunaan `NonCancellable`:** Karena proses *reset* nilai `isInferencing = false` ditempatkan di dalam blok `finally` yang memanggil *suspend function* (`withContext`), kita diwajibkan menyisipkan `NonCancellable` pada context-nya. Hal ini mencegah *Exception* ketika mencoba me-reset state pada coroutine yang sedang di-*cancel* (misalnya ketika Activity di-destroy).
- Plan ini dirancang secara detail agar eksekusi perbaikan *(copy-paste)* kode tidak menyebabkan kemunculan *bug* baru akibat *coroutine lifecycle*.
