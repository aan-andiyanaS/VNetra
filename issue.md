# Laporan Perbaikan Bug: GPU Native Crash & Transposed TFLite Output Shape

## 1. Deskripsi Masalah

Terdapat dua masalah utama yang menyebabkan aplikasi mengalami *force close* atau gagal menampilkan *bounding box* setelah memasukkan model TFLite baru (YOLOv8/11):

1. **`NoClassDefFoundError` pada `GpuDelegate` (Force Close):**
   Aplikasi mengalami *crash* dan langsung tertutup dengan pesan error `java.lang.NoClassDefFoundError: Failed resolution of: Lorg/tensorflow/lite/gpu/GpuDelegateFactory$Options`. Hal ini disebabkan oleh absennya library `tensorflow-lite-gpu-api` pada file `build.gradle.kts`. Pada versi 2.9.0 ke atas, Google memisahkan implementasi GPU menjadi dua *dependency* terpisah.

2. **Output Shape Model Transposed (Silent Failure):**
   Model TFLite yang diekspor menggunakan Ultralytics versi terbaru (YOLOv8/11) memiliki bentuk tensor output `[1, 8400, 25]` (*transposed*), sedangkan kode bawaan `YoloDetector.kt` secara statis mengharapkan format `[1, 25, 8400]`. Hal ini membuat TFLite melemparkan `IllegalArgumentException` yang tertangkap oleh `catch`, sehingga aplikasi tidak *crash* tetapi tidak menampilkan hasil deteksi sama sekali.

## 2. Tindakan Perbaikan

- **Menambahkan Dependency GPU API:**
  Menambahkan `implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")` ke `app/build.gradle.kts` agar *GpuDelegate* dapat diinisialisasi dengan sempurna dan dapat memanfaatkan akselerasi GPU secara penuh.

- **Dynamic Output Shape Parsing di `YoloDetector.kt`:**
  Memodifikasi kode parsing di `YoloDetector.kt` agar mendeteksi secara otomatis *shape* output dari model TFLite:
  - Jika output berbentuk `[1, 8400, 25]`, kode akan menggunakan array dua dimensi terbalik (`outputBufferTransposed`).
  - Jika output berbentuk `[1, 25, 8400]`, kode tetap menggunakan array standar.
  Perbaikan ini memastikan aplikasi kompatibel secara *backward* dengan berbagai varian model hasil ekspor dari YOLOv8 maupun YOLOv11.

- **Peningkatan Keamanan Eksekusi (Error Handling):**
  Mengganti semua penangkapan *exception* dari `catch (e: Exception)` menjadi `catch (e: Throwable)` pada inisialisasi TFLite. Hal ini bertujuan agar *Runtime Error* level mesin (seperti *LinkageError* atau memori habis) dapat diantisipasi dan otomatis kembali menggunakan CPU, sehingga tidak mematikan paksa aplikasi secara mendadak.

## 3. Hasil Validasi

- *Build* dan sinkronisasi Gradle berhasil.
- Aplikasi sudah tidak lagi mengalami *force close* saat mode `DelegateMode.AUTO` mengeksekusi `GpuDelegate`.
- Pendeteksian objek sudah berjalan normal dengan NMS dan pembacaan *bounding box* yang mulus.
