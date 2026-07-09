# Laporan Perubahan Arsitektur & Performa (Diff HEAD vs be855bf8080f)

Dokumen ini merangkum seluruh perubahan dan optimasi teknis tingkat lanjut yang diterapkan pada repositori VNetra sejak commit `be855bf8080f453dbb97e7e6b93b670e2d12b2bb`. Perubahan ini mencakup tiga ranah utama: **Model AI (YOLOv11n)**, **Android Client**, dan **Firmware ESP32**.

---

## 1. Rekayasa Data & Optimasi YOLOv11n (Jupyter Notebooks)

Terjadi pergeseran paradigma pada pemilihan kelas (*class selection*) untuk memaksimalkan kapasitas representasi (parameter memori) model *nano* (2.6M parameters).

### A. Peleburan dan Pemangkasan Kelas (15 Kelas Final)
*   **Penghapusan Kelas Non-Esensial**: Kelas seperti `bench`, `train`, `pothole`, `open_drain`, `puddle`, `hanging_branch`, dan `fence` telah dihapus sepenuhnya. Objek-objek ini dianggap tidak krusial/mengancam nyawa bagi mobilitas tunanetra, sehingga kapasitas model difokuskan pada ancaman langsung.
*   **Peleburan `truck` ke `car`**: Berdasarkan analisis matriks kebingungan (confusion matrix) sebelumnya, akurasi kendaraan roda empat terpecah. Oleh karena itu, objek truk (`truck`) kini dilabeli ulang (di- *mapping*) menjadi mobil (`car`).
*   **Isolasi `bus`**: Kelas `bus` dipertahankan sebagai entitas independen karena memiliki ciri visual yang sangat khas dan berukuran masif (berpotensi membingungkan ToF jika salah klasifikasi).

```python
# Definisi final 15 kelas pada pipeline training:
master_classes = [
    "person", "bicycle", "car", "motorcycle", "bus", "pole",
    "tactile_paving_straight", "tactile_paving_turn", 
    "tactile_paving_3way", "tactile_paving_4way", "tactile_paving_stop",
    "stairs_up", "stairs_down", "crosswalk", "tree"
]

coco_rf_mapping = {
    "person": "person", "car": "car", "bus": "bus",
    "truck": "car", # <-- Peleburan truck ke car
    "motorcycle": "motorcycle", "bicycle": "bicycle"
}
```

### B. Distribusi Kepadatan & Resolusi *Overfitting*
*   **Filter Kapasitas Ketat (Strict Cap)**: Diimplementasikan batas maksimal `MAX_INSTANCES = 4` per gambar. Gambar yang terlalu ramai (kerumunan) langsung dibuang untuk menghindari *bias* spasial.
*   **Pembatasan Kelas Dominan**: Gambar *person* dibatasi tajam ke 6000 instans, dan mobil ke 4000 instans. 
*   **Penyeimbangan Kelas Kritis**: Batas sampel maksimal untuk *tree* dinaikkan ke 1000, dan *crosswalk* ke 1100 guna memperkuat akurasi di area pejalan kaki.
*   **Augmentasi Head-Mounted**: Nilai rotasi (`degrees`) pada setup YOLO dinaikkan dari 10.0 menjadi 20.0 untuk mensimulasikan kemiringan alami kepala pengguna (kacamata), menggantikan asumsi awal kamera di dada.
*   **Optimasi Eksekusi Cloud**: Fitur *auto-zip* dataset dimatikan dan diganti dengan *auto-delete* pasca training. Trik simpel ini secara efektif mencegah *storage* server Kaggle meledak (penuh) saat fase ekspor model.
*   **Hasil Evaluasi**: Dengan pengaturan di atas plus pembekuan 5 layer awal (`freeze=5`), model FP32 mencapai **mAP@50 0.862** dan versi TFLite FP16 hanya mengalami degradasi minor ke **0.848**. Model TFLite INT8 juga telah didukung secara penuh melalui *notebook* `quantize_vnetra_int8_colab.ipynb`.

---

## 2. Peningkatan Kecerdasan Aplikasi Android (Kotlin)

Fokus utama pada klien Android adalah integrasi *sensor fusion*, penyelesaian konflik data spasial, dan pemolesan algoritma TTS (Text-to-Speech) agar lebih manusiawi.

### A. Fallback Monokuler Kamera (Computer Vision)
Jika sensor ToF *out-of-range* (gagal membaca jarak > 2 meter atau terjadi pantulan ekstrem), sistem secara otomatis melakukan *fallback* ke algoritma estimasi geometri optik.

```kotlin
var dObj = TofDepthEstimator.calculate(...)

// Jika ToF gagal (D_MAX), fallback ke kamera monokuler YOLO
if (dObj >= TofDepthEstimator.D_MAX) {
    dObj = CameraDepthEstimator.estimateDistance(
        className   = det.className,
        boundingBox = det.boundingBox,
        imageHeight = latestFrameHeight,
        thetaDeg    = thetaDeg
    )
}
```

### B. Sistem TTS Cerdas & Anti-Spam (Smart Navigation)
Modul `TtsAlertManager.kt` direkayasa ulang menjadi sebuah *state machine*.
1.  **Agregasi Kalimat**: Jika terdapat lebih dari satu ancaman di frame yang sama, sistem tidak lagi menumpuk (*queue*) pesan secara terpisah, melainkan menggabungkannya secara tata bahasa.
    *(Contoh: "orang, 150 cm, arah jam 12, dan motor, 200 cm, arah jam 2")*
2.  **Pemeliharaan Flag Absensi (> 3 detik)**: Objek yang sempat masuk zona bahaya tetapi kemudian tidak terdeteksi oleh YOLO selama lebih dari 3 detik (misal objek lewat di samping), status bahayanya otomatis di-*reset*.
3.  **Navigasi IMU (State Machine)**: Peringatan "Jalan di depan kosong" atau "Awas, tembok di depan" kini memperhatikan kecepatan gerak (*accelerometer*) dan tolakan kepala (*gyroscope*). TTS akan diam jika pengguna terdeteksi sedang menengok perlahan untuk memindai jalan, sehingga mencegah kebanjiran audio.
4.  **Stabilitas Histeresis ToF (Noise Floor)**: Parameter `EPS_NOISE` dilebarkan secara drastis dari 30mm menjadi 150mm. Ini krusial untuk mencegah sistem memanggil TTS berulang-ulang (spam) hanya karena sinyal pantulan ToF sedikit bergetar (*flicker*) saat rintangan berada tepat di batas jarak aman.

### C. Sinkronisasi Kernel YOLO & INT8
File `YoloDetector.kt` diperbarui untuk:
*   Beradaptasi dengan vektor berukuran `NUM_CLASSES = 15`.
*   Secara otomatis memprioritaskan pemuatan `best_int8.tflite` apabila NPU/NNAPI diaktifkan, atau *fallback* cerdas ke FP16 untuk delegasi GPU.

---

## 3. Modifikasi Firmware ESP32 & Kalibrasi Dinamis

*   **Pembalikan Orientasi IMU**: Berdasarkan letak fisik MPU6050 terbaru pada perangkat VNetra, makro `#define MPU_FLIP_X_AXIS` dicabut agar sumbu rotasi Y (pitch) yang dibalik, bukan X (roll), menjamin *Right-Handed System* terkalibrasi lurus dengan horizon kamera.
*   **Eksekusi Kalibrasi via Udara (OTA WebSocket)**: Menambahkan *handler* perintah khusus di mana klien Android dapat menyuruh ESP32 untuk membersihkan parameter kalibrasi MPU6050 dari memori permanen (NVS) dan merestart perangkat secara paksa. Pengguna dapat memicu ini dengan **mengetuk ganda (double-tap)** pada logo aplikasi di layar utama Android.

```cpp
} else if (cmd == "CALIBRATE_IMU") {
    Serial.println("[CAL] Request calibration. Clearing NVS bias and restarting...");
    preferences.begin("sensors", false);
    preferences.remove("bias_ok");
    preferences.end();
    delay(500);
    esp_restart();
}
```
