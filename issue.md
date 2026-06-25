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

## 3. Laporan Tambahan (Patch Lanjutan)

### 3.1. Bounding Box Tidak Muncul (Aspect Ratio & Scaling)
- **Masalah:** Gambar dari kamera dikompres paksa (*squashing*) menjadi ukuran 640x640, yang merusak aspek rasio asli gambar dan menyebabkan model kebingungan (akurasi deteksi merosot drastis hingga 0 Bounding Box ditemukan). Selain itu, penerjemahan lokasi koordinat hasil AI dari 640x640 kembali ke layar utama salah.
- **Tindakan:** Mengimplementasikan teknik **Letterboxing** (menambahkan *padding* garis hitam) tanpa mengubah aspek rasio kamera asli (4:3) di dalam fungsi `convertBitmapToByteBuffer`. Logika `postprocessBoxes` juga dikalibrasi ulang (`padX`, `padY`, `scale`) untuk menghapus *padding* secara otomatis dan mengonversi koordinatnya tepat di atas objek pada layar ponsel.

### 3.2. Kegagalan Total GPU Delegate (TFLite 2.16.1 Bug)
- **Masalah:** Meskipun library `gpu-api` sebelumnya ditambahkan, versi TensorFlow Lite **2.16.1** (Standalone) diketahui bermasalah dan tetap melemparkan `NoClassDefFoundError` pada `GpuDelegateFactory$Options`. Hal ini menyebabkan aplikasi selalu melakukan *fallback* ke CPU secara diam-diam. Selain itu, terdapat redundansi inisialisasi pada blok mode `AUTO` yang menyebabkan *compiler error* di Kotlin.
- **Tindakan:** Melakukan *downgrade* versi `tensorflow-lite` dan `tensorflow-lite-gpu` secara serentak ke versi yang terbukti stabil, yaitu **2.14.0**. Membersihkan blok logika `DelegateMode.AUTO` agar tidak melakukan inisialisasi ganda, serta memperbaiki struktur `when` *expression* agar *exhaustive* mematuhi aturan ketat Kotlin.

### 3.3. Penyesuaian Confidence Threshold
- **Masalah:** Ambang batas *confidence* sebelumnya terlalu tinggi (50%) untuk model kustom yang baru dilatih, sehingga tidak ada deteksi yang lewat. 
- **Tindakan:** Mengkalibrasi ulang `CONFIDENCE_THRESHOLD` ke angka standar industri untuk model kustom, yaitu **0.30f (30%)**.

## 4. Hasil Validasi Akhir

- *Build* dan sinkronisasi Gradle (versi 2.14.0) sukses terinstal ke perangkat S20 Ultra dan Galaxy A16.
- Aplikasi berhasil mengeksekusi TFLite menggunakan **GPU Delegate 100%** tanpa *fallback* ke CPU.
- Bounding box berhasil muncul dengan presisi lokasi yang sangat akurat di atas objek berkat perbaikan *Letterboxing*.
