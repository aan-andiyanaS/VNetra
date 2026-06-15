# Plan Implementasi: Pemisahan Jalur Data Sensor (UDP) dan Video (TCP/WebSocket)

## 🎯 Tujuan
Mengatasi masalah *Head-of-Line Blocking* secara permanen dengan memisahkan pengiriman data. Data gambar (JPEG) yang berukuran besar tetap menggunakan jalur WebSocket (TCP), sedangkan data sensor (IMU & ToF) yang sangat sensitif terhadap waktu (*time-sensitive*) dan berukuran kecil akan dipindahkan ke jalur UDP (*User Datagram Protocol*).

Arsitektur UDP memungkinkan pengiriman "tembak dan lupakan" tanpa mekanisme antrean ulang, sehingga kemacetan pada *frame* kamera tidak akan pernah memengaruhi *ping* atau laju *update* sensor navigasi.

---

## 🧠 Konsep Arsitektur

1. **Jalur Video (TCP/WebSocket):** Tetap berjalan di Port 80 via WebSocket. TCP memastikan *frame* gambar JPEG tidak rusak/hilang (penting untuk *decoder* gambar).
2. **Jalur Sensor (UDP):** Berjalan di UDP Port (misal: **8080**). Tidak ada *overhead* atau penumpukan antrean. Data sensor terbaru (200Hz untuk EKF) selalu dikirim secara instan.
3. **Mekanisme Handshake Jaringan:** Agar ESP32 tahu ke alamat IP mana data UDP harus ditembakkan, ESP32 akan membaca alamat IP perangkat Android yang sedang terhubung ke WebSocket, lalu otomatis mulai menyemburkan (*streaming*) data UDP ke IP tersebut.

---

## 🛠️ Langkah Eksekusi (High-Level Plan)

### Tahap 1: Modifikasi Firmware ESP32 (`firmware-vnetra.ino`)

1. **Library & Inisialisasi:** 
   - Gunakan library `AsyncUDP.h` atau `WiFiUDP.h` bawaan ESP32. (Sangat disarankan `AsyncUDP` karena tidak *blocking* / asinkron).
   - Deklarasikan objek UDP global (contoh: `AsyncUDP udpSensor;`) dan konstanta port (contoh: `const int UDP_TARGET_PORT = 8080;`).

2. **Identifikasi Target IP Otomatis:**
   - Di dalam fungsi *callback* `onWsEvent`, pada saat status `WS_EVT_CONNECT`, tangkap IP *client* Android menggunakan `client->remoteIP()`.
   - Simpan IP ini ke sebuah variabel global (misal `IPAddress activeClientIp;`).
   - Ubah sebuah bendera/flag (misal `volatile bool udpClientReady = true;`).
   - Pada saat `WS_EVT_DISCONNECT`, atur ulang flag menjadi `false` agar ESP32 berhenti mengirim UDP.

3. **Pemindahan Rute Pengiriman Sensor:**
   - Di dalam *Task* sensor (`IMU_Task` dan `TOF_Task`), **hapus** logika yang memasukkan paket data (`WsMessage_t`) ke dalam `wsQueue`.
   - Gantilah dengan memanggil metode kirim UDP. Jika `udpClientReady == true`, kirim *buffer* (`imu_buf` dan `tof_buf`) langsung ke `activeClientIp` pada port `UDP_TARGET_PORT`.
   - **Catatan:** Jangan ubah susunan isi *payload* (seperti *header* `0x02` untuk IMU atau `0x04` untuk ToF) agar Android tetap bisa mengidentifikasi jenis data.

### Tahap 2: Modifikasi Android App (Kotlin / Networking)

1. **Penerima UDP (UDP Listener):**
   - Buat sebuah Coroutine atau *Thread* baru di Service Android Anda (misal `udpReceiverJob`) yang menginisialisasi `DatagramSocket(8080)`.
   - Buat perulangan (`while(isActive)`) untuk membaca `socket.receive(datagramPacket)`.

2. **Routing Data (Parser):**
   - Saat paket UDP diterima, baca *byte* pertama (index `[0]`) sebagai penanda tipe *frame*.
   - Jika `0x02` (IMU) ➔ Ekstrak sisa *byte* dan masukkan ke *StateFlow/Channel* IMU yang sudah ada.
   - Jika `0x04` (ToF) ➔ Ekstrak sisa *byte* dan masukkan ke *StateFlow/Channel* ToF yang sudah ada.

3. **Siklus Hidup (Lifecycle):**
   - Pastikan `DatagramSocket` dibuka saat layanan *streaming* aktif, dan wajib di-*close* secara bersih saat pengguna keluar/putus koneksi. Jika tidak ditutup, akan menyebabkan *Port Binding Error* pada pemakaian berikutnya.

### Tahap 3: Penyesuaian UI Latency Monitor (Opsional)

1. Label *ping* WebSocket di UI Android saat ini merepresentasikan waktu transfer video/koneksi secara keseluruhan. Jika dirasa perlu, label ini bisa diubah menjadi "Ping Kamera (TCP)" untuk memperjelas, dan bisa ditambah "Ping Sensor (UDP)".
2. Namun, arsitektur dasar parser EKF/Formula E & H di Android tidak perlu dirombak total karena data *array/byte* yang diteruskan ke sana bentuknya tetap persis sama.

---

## ⚠️ Peringatan untuk Developer (Gotchas)
- **Kapasitas Buffer UDP**: Di kode Kotlin Android, pastikan `ByteArray` penampung (buffer) untuk `DatagramPacket` berukuran cukup besar (disarankan minimal 256 bytes) agar paket ToF yang berisi 64 zona tidak terpotong (ter-truncate).
- **Pemrosesan Asinkron**: UDP Packet di Android akan datang dengan sangat cepat (200x sedetik untuk IMU). Pastikan proses *parsing* byte-nya cepat dan langsung dioper ke `Dispatchers.Default` (bukan *Main thread*) agar tidak membuat UI *freeze*.
- **Little-Endian**: Pastikan proses *parsing* data (*timestamp*, *float*) di penerima UDP Android tetap disetel ke `ByteOrder.LITTLE_ENDIAN`, persis sama seperti logika *parsing* WebSocket sebelumnya.
