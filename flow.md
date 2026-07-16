# VNetra System Flow and Architecture

VNetra adalah sistem cerdas berbasis *Wearable Device* (ESP32-S3) dan *Android Application* yang bertujuan membantu navigasi tunanetra dengan memanfaatkan deteksi objek (YOLO), pemetaan spasial ToF (Time-of-Flight), dan sensor inersia (IMU).

Laporan ini mendokumentasikan cara kerja keseluruhan sistem secara detail dari tingkat firmware (hardware) hingga tingkat perangkat lunak Android, dilengkapi dengan flowchart dan ulasan Code Quality (berdasarkan standar praktik terbaik).

---

## 1. Arsitektur Keseluruhan (High-Level Architecture)

Sistem VNetra terbagi menjadi dua komponen utama yang saling berkomunikasi melalui protokol nirkabel:

1. **ESP32-S3 Firmware (Wearable)**: Mengambil data dari sensor (Kamera OV2640/OV5640, VL53L5CX ToF, MPU6050 IMU) dan mengirimkannya ke Android.
2. **Aplikasi Android (Client)**: Memproses data sensor, menjalankan inferensi YOLOv11n (Machine Learning), menggabungkan data spasial, dan memberikan umpan balik suara (TTS - Text-to-Speech) kepada pengguna.

### Komunikasi
- **BLE (Bluetooth Low Energy)**: Digunakan secara eksklusif untuk proses *Provisioning* (konfigurasi jaringan WiFi awal).
- **WiFi (WLAN)**: Digunakan untuk streaming data kecepatan tinggi.
    - **WebSocket (TCP, Port 80)**: Mengirimkan frame JPEG dari kamera dan sinyal kontrol (Command/Heartbeat).
    - **UDP (Port 8080/8081)**: Mengirimkan data telemetri real-time dengan latensi rendah (data IMU dan ToF).

---

## 2. Alur Sistem ESP32-S3 (Firmware)

Firmware ESP32-S3 dirancang untuk boot up dengan cepat dan memiliki mekanisme *fallback* ke mode BLE jika tidak ada WiFi yang dikonfigurasi.

### Flowchart Booting & Sensor Loop ESP32
```mermaid
graph TD
    A[Power On / Boot] --> B{Cek NVS WiFi Credentials}
    B -- Ada --> C[Background WiFi Connect Task]
    B -- Tidak Ada --> D[Masuk Mode BLE Provisioning]
    
    C --> E{Berhasil Connect?}
    E -- Ya --> F[Start WebSocket & UDP Server]
    E -- Tidak --> D
    
    D --> G[Tunggu Android via BLE]
    G --> H[Android Mengirim SSID & Password]
    H --> I[ESP32 Connect ke WiFi]
    I --> F
    
    F --> J[Start Sensor Tasks]
    J --> K[Main Loop]
    
    subgraph Parallel Tasks
    K1[Camera Task<br/>Capture JPEG -> WebSocket]
    K2[IMU Task 200Hz<br/>MPU6050 -> EKF -> UDP]
    K3[ToF Task 10-15Hz<br/>VL53L5CX -> UDP]
    end
    
    K --> Parallel Tasks
```

### Penjelasan Detail Tiap Komponen ESP32:
1. **Provisioning BLE (`BleManager.kt` & `firmware-vnetra.ino`)**:
   - Jika ESP32 tidak punya koneksi WiFi, ia menyalakan BLE server (`ESP32S3-WiFi-Config`).
   - Android memindai (`scan`) jaringan WiFi di sekitar ESP32 dan menampilkannya.
   - Pengguna memilih WiFi, lalu Android mengirimkan kredensial ke ESP32.
   - Setelah terkoneksi, ESP32 mematikan BLE untuk menghemat daya dan mengaktifkan WebSocket.
2. **Camera Streaming**:
   - Berjalan dalam loop utama (atau task terpisah). Mengambil gambar dari sensor kamera.
   - Jika `is_moving_fast` terdeteksi (dari data IMU), `JPEG_QUALITY` diturunkan secara dinamis untuk menghemat bandwidth (Dynamic QoS).
   - Data dikirim dengan header 9-byte (`Tipe 0x01` + `Timestamp 8-byte`) via WebSocket.
3. **IMU EKF Task (`IMU_Task`)**:
   - Berjalan di frekuensi konstan **200Hz (5ms)**.
   - Membaca raw Accelerometer dan Gyroscope dari MPU6050.
   - Menggunakan filter **Mahony AHRS** untuk menghitung orientasi (Pitch/Roll) dan mengeliminasi gravitasi untuk mendapatkan akselerasi linear murni.
   - Data dikirim setiap 10 tick (20Hz) via paket UDP.
4. **ToF Task (`TOF_Task`)**:
   - Membaca sensor kedalaman VL53L5CX.
   - Resolusi beradaptasi secara otomatis (*Auto-Switch*):
     - **4x4 (16 zone, 15Hz)** di luar ruangan/cahaya terang (mencegah SPAD saturasi).
     - **8x8 (64 zone, 10Hz)** di dalam ruangan/cahaya teduh (akurasi spasial maksimal).
   - Membuang noise dengan menetapkan nilai -1 pada pengukuran jarak yang tidak valid. Dikirim via UDP.

---

## 3. Alur Aplikasi Android (Processing & Perception)

Aplikasi Android bertindak sebagai otak dari sistem. Ia mengelola koneksi dan memproses data sensor yang masuk menjadi informasi bermakna bagi pengguna.

### Flowchart Navigasi Android
```mermaid
graph TD
    A[MainActivity] --> B{Sesi IP Aktif Tersimpan?}
    B -- Tidak --> C[Scan BLE ESP32]
    C --> D[DeviceConfigActivity]
    D --> E[Hubungkan ke WiFi & Simpan IP]
    E --> F[CameraStreamActivity]
    
    B -- Ya --> F
    
    subgraph Data Pipeline (CameraStreamService)
        F1[WebSocket Receiver] --> F2[JPEG Decoder]
        F3[UDP Receiver] --> F4[Parse IMU & ToF Data]
    end
    
    F --> Data Pipeline
    
    subgraph Perception Engine
        F2 --> G1[YoloDetector - Deteksi Objek 2D]
        F4 --> G2[CameraDepthEstimator & TofDepthEstimator - Kedalaman]
        F4 --> G3[TerrainDetector - Deteksi Tangga/Lubang]
    end
    
    G1 --> H1[SpatialMappingUtils - Gabung 2D & Kedalaman]
    G2 --> H1
    G3 --> H2[SimpleTracker - Tracking ID Objek]
    
    H1 --> H2
    H2 --> I[TtsAlertManager - Manajemen Peringatan Audio]
    I --> J[Text-to-Speech Output]
```

### Penjelasan Detail Modul Android:

1. **Jaringan & Konektivitas (`CameraStreamService.kt` & `CameraManager.kt`)**:
   - Membuka WebSocket client dan UDP socket server secara bersamaan.
   - Parsing frame binary. Byte ke-0 menandakan jenis paket (0x01=JPEG, 0x02=IMU, 0x04=ToF).
   - Sinkronisasi waktu dilakukan dengan menyesuaikan `timestamp_us` ESP32 ke timeline Android.
2. **AI Inference (`YoloDetector.kt`)**:
   - Menggunakan TensorFlow Lite.
   - Menerima `Bitmap`, mengkonversinya ke input tensor `Float32 [1, 3, 640, 640]`.
   - Menggunakan format **YOLOv11**: Output tensor memiliki dimensi `[1, 84, 8400]` (84 = 4 bounding box + 80 class probabilitas).
   - Menerapkan NMS (Non-Maximum Suppression) untuk menghapus duplikasi bounding box.
3. **Sensor Fusion & Spasial**:
   - **`SpatialMappingUtils.kt`**: Memetakan bounding box YOLO (skala VGA 640x480) ke grid sensor ToF (8x8 atau 4x4) berdasarkan nilai Field of View (FoV) vertikal dan horizontal.
   - **`TofDepthEstimator.kt`**: Menghitung jarak objek YOLO dengan mengambil nilai rata-rata, median, dan minimum dari matriks ToF yang bersinggungan (*overlap*) dengan bounding box objek.
   - **`CameraDepthEstimator.kt`**: Digunakan sebagai *fallback* monocular depth (menggunakan proporsi tinggi/lebar objek di layar) apabila ToF gagal atau objek berada di luar FoV ToF (ToF FoV hanya ~45°).
   - **`TerrainDetector.kt`**: Menganalisis grid ToF bagian bawah (kolom/baris terakhir) dan menghitung variansi gradien permukaan (`deltaZ / deltaY`). Jika lereng ekstrem ke atas → Tangga Naik. Ekstrem ke bawah → Lubang/Tangga Turun.
4. **Tracking (`SimpleTracker.kt`)**:
   - Menggunakan algoritma k-NN dan IoU (Intersection over Union) 3D sederhana untuk melacak objek antar frame (memberikan Tracking ID yang konsisten) agar sistem tidak menganggap objek yang sama di frame berikutnya sebagai ancaman baru.
5. **Manajemen Peringatan (`TtsAlertManager.kt`)**:
   - Komponen kritis agar tunanetra tidak dibombardir oleh suara yang terus-menerus (SPAM).
   - Menggunakan **Formula G (Dynamic Urgency Threshold)**: Ambang batas kedekatan di mana sistem harus berbicara. Semakin tinggi kecepatan jalan user (`v_user` dari IMU), semakin cepat sistem harus mengingatkan (threshold jarak meningkat).
   - Menggunakan **Formula H (Hysteresis & Falloff)**: Begitu sistem memperingatkan objek (misal: "Motor, 2 meter"), peringatan selanjutnya untuk objek yang sama (Tracking ID sama) akan ditahan/dikunci (*cooldown*) kecuali objek tersebut semakin mendekat melewati ambang batas Hysteresis yang lebih sensitif.

---

## 4. Evaluasi Code Review & Quality Assurance

Berikut merupakan hasil inspeksi kualitas sistem secara menyeluruh berdasarkan 5 kapabilitas review:

### A. Arsitektur dan Desain (Architecture & Design)
- **Kelebihan**: 
  - Pemisahan *concern* sudah sangat baik. Android berjalan murni sebagai sistem reaktif (StateFlow dan Coroutine) yang merespons event dari Service jaringan. 
  - ESP32 menggunakan RTOS (Real-Time OS) di mana IMU, ToF, dan WiFi berjalan di task/core terpisah sehingga komunikasi I2C yang lambat tidak membuat frame drop kamera.
- **Saran Peningkatan**: 
  - Pengikatan *Lifecycle* Service dan Activity masih sedikit rawan terhadap Memory Leak jika Activity dihancurkan namun `CameraManager` belum sepenuhnya membersihkan socket UDP. Perlu dipertimbangkan pola `bound service`.

### B. Keamanan (Security)
- **Kelebihan**: 
  - Tidak ada pengumpulan data privasi. Akses jaringan murni LAN/Local (tidak terekspos ke publik).
- **Saran Peningkatan**:
  - Konfigurasi WiFi lewat BLE (Provisioning) dikirim secara plain-text (`CONNECT:ssid|password`). Walaupun jangkauan BLE kecil, ini rawan terhadap *sniffing* lokal. Sebaiknya gunakan *AES Encryption* ringan antara App dan ESP32 saat provisioning.

### C. Kinerja (Performance)
- **Kelebihan**: 
  - Firmware ESP32 menggunakan pre-allocated static buffer PSRAM (`g_wsBuf`) untuk mencegah fragmentasi heap.
  - Implementasi YOLO di Android berjalan efisien di background thread dengan `MappedByteBuffer`.
- **Saran Peningkatan**: 
  - Tensor AI float32 cukup berat di CPU ponsel menengah. Sebaiknya menggunakan modul NNAPI delegate (`TfLiteNnapiDelegate`) agar bisa menggunakan akselerasi GPU/NPU di Android.

### D. Keandalan dan Penanganan Error (Reliability)
- **Kelebihan**: 
  - Terdapat mekanisme *Auto-Reconnect* baik di WiFi (ESP32) maupun di WebSocket (Android).
  - Data -1 Sentinel Value digunakan di ToF untuk mencegah haptic feedback ngawur saat terjadi *hardware glitch* karena pantulan cahaya matahari.
- **Saran Peningkatan**:
  - Di `TtsAlertManager`, belum ada manajemen antrean yang memutus/meng-interrupt kalimat panjang jika tiba-tiba ada bahaya seketika (contoh: objek mendadak muncul 1 meter di depan). Harus diimplementasikan fungsi `tts.stop()` untuk memotong kalimat "Obstacle di jarak 3 met..." menjadi "AWAS! MOTOR 1 METER!".

### E. Kualitas Kode (Code Maintainability)
- **Kelebihan**: 
  - Sangat rapi, variabel berbahasa Inggris namun dokumentasi inline (Komentar) menjelaskan logika dalam bahasa Indonesia dengan sangat jelas dan terperinci.
  - Penggunaan `companion object` (konstanta) mudah disesuaikan.
- **Saran Peningkatan**:
  - Beberapa *magic number* seperti `0.6f` (bobot smoothing filter) dan nilai `120.0f` (batas ambient ToF outdoor) tersebar (hardcoded) di firmware. Lebih baik dideklarasikan secara global di atas dokumen dengan `#define` yang memiliki penjelasan lengkap agar memudahkan tuning.

---

**Kesimpulan:**
Secara keseluruhan, VNetra adalah sistem yang dirancang dengan sangat baik, mengawinkan perangkat keras terbenam (*embedded hardware*) dan pemrosesan ponsel cerdas secara optimal. Manajemen antrian TTS, sinkronisasi timeline waktu antar perangkat, serta fusi sensor membuktikan tingkat kedewasaan kode yang sangat tinggi untuk sebuah purwarupa akademis.
