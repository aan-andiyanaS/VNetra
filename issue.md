### Arsitektur Integrasi Model TFLite di Android (Dynamic & Fallback Mode)
Bagian ini merancang bagaimana aplikasi seluler Android VNetra menangani file TFLite (FP16/INT8) secara cerdas dan kokoh (robust).

1. **Model Placeholder & Fallback System (Bypass Inference)**
   - Jika file `best_fp16.tflite` maupun `best_int8.tflite` tidak ditemukan di dalam folder `app/src/main/assets/`, aplikasi Android **tidak boleh *crash***.
   - Aplikasi akan otomatis beralih ke mode **INFERENCE_BYPASS_MODE**. Dalam mode ini, siaran video dari ESP32 Cam di halaman utama (*Camera Page*) tetap berjalan mulus, namun *frame* tersebut sekadar ditampilkan ke layar tanpa dilewatkan ke proses deteksi AI YOLO.


2. **Prioritas & Pemilihan Model Fleksibel**
   - Saat aplikasi pertama kali terbuka (*startup*), kelas *Object Detector* akan memindai folder `assets/`.
   - **Skenario A:** Jika HANYA `best_fp16.tflite` yang ada, model ini akan dimuat menggunakan *GPU Delegate*.
   - **Skenario B:** Jika HANYA `best_int8.tflite` yang ada, model ini akan dimuat menggunakan *NNAPI / XNNPACK Delegate*.
   - **Skenario C:** Jika KEDUA model disisipkan, aplikasi akan memilih **FP16** sebagai prioritas utama (karena kestabilan GPU mayoritas HP Android lebih bisa diandalkan daripada NNAPI).
3. **Indikator Visual Deteksi AI di Halaman Kamera (Camera Page)**
   - Tepat di atas siaran langsung kamera ESP32 pada UI *Camera Page*, akan ada sebuah teks sederhana yang menginformasikan status model:
     - **"NONE"** -> Jika folder assets kosong (tidak ada model yang dipasang, kamera hanya me-*render* video murni dari ESP32 tanpa kotak deteksi).
     - **"FP16"** -> Jika HANYA model `best_fp16.tflite` yang terpasang di aplikasi.
     - **"INT8"** -> Jika HANYA model `best_int8.tflite` yang terpasang di aplikasi.
     - **"FULL"** -> Jika KEDUA model (FP16 dan INT8) terpasang di dalam aplikasi (walaupun yang dieksekusi secara aktif adalah FP16).
4. **Alur Kode (Kotlin)**
   - Inisialisasi TensorFlow Lite `Interpreter` wajib dibungkus dalam blok `try-catch`.
   - Jika `Exception` terjadi (model rusak atau hilang), lemparkan sebuah pesan `Callback` ke antarmuka pengguna agar memicu pergantian warna teks indikator di *Camera Page* ke warna merah.
5. **Pemilihan Hardware Accelerator (NPU / GPU / CPU) & Model (FP16/INT8) Dinamis/Manual**
   - Mendukung pemilihan *DelegateMode* (AUTO, NPU, GPU, CPU) dan *ModelPreference* (AUTO, FP16, INT8) yang bisa ditentukan secara fleksibel melalui konstruktor `YoloDetector`.
   - **Pemilihan Model Manual:** Jika *ModelPreference* disetel ke `FP16` atau `INT8`, sistem akan memaksa menggunakan model tersebut (selama file modelnya ada). Fitur ini sangat berguna jika pengguna ingin memaksa menjalankan FP16 di atas CPU.
   - Jika *DelegateMode.AUTO* (atau secara manual memilih NPU):
     - **NPU (NNAPI):** Akan digunakan HANYA jika *Smartphone* memenuhi batas minimal *Android 8.1 (API 27)*. Jika API di bawah 27, aplikasi tidak akan memaksa NPU melainkan *fallback* ke opsi yang disupport (GPU / CPU).
   - **GPU Blokir Mode:** Jika model yang dipilih secara sistem atau paksa oleh user adalah `INT8`, maka sistem **melarang** menggunakan GPU karena kurang efisien. Akan dilakukan *fallback* ke NPU atau CPU.
   - **CPU:** Akan digunakan sebagai pertahanan terakhir (*last resort fallback*) dengan dukungan *multi-threading* jika fitur lain tidak disupport perangkat secara fisik/sistem.
