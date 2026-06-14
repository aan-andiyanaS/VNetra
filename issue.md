# Plan Perbaikan UI: Penyesuaian Layout untuk Edge-to-Edge & Safe Area (Notch/Nav-bar)

## 📌 Deskripsi Masalah UI
Berdasarkan pengujian pada beberapa jenis perangkat/layar *smartphone*:
1. **Bagian Atas (Status Bar Overlap):** Teks status koneksi ("Connected"/"Disconnected"), judul/header aplikasi, teks pembacaan IMU (Pitch, Roll, Accel), dan badge status seringkali saling bertumpuk (overlap) dengan *System Status Bar* bawaan HP (seperti jam, indikator baterai, sinyal, maupun poni/notch kamera).
2. **Bagian Bawah (Navigation Bar Overlap):** Tombol-tombol aksi utama di bagian bawah layar, seperti tombol **"AKHIRI"** pada mode *Live Camera*, terpotong atau tertutup secara transparan oleh *System Navigation Bar* (baik dalam mode 3-tombol maupun mode *gesture* layar penuh).

Hal ini membuat beberapa bagian informasi terhalang dan tombol menjadi sulit di-klik.

## 🛠️ Rencana Eksekusi (Untuk Junior Developer)

Tujuan utama dari perbaikan ini adalah memastikan aplikasi mendukung mode *Edge-to-Edge* secara sempurna, di mana aplikasi tetap bisa menggambar hingga ke ujung layar, namun elemen interaktif dan teks penting didorong ke dalam zona aman (*Safe Area* / *Window Insets*).

Berikut adalah langkah *high-level* yang perlu dikerjakan:

### 1. Aktifkan Edge-to-Edge Display di Level Window
Pada setiap Activity utama (khususnya `CameraStreamActivity`, `DeviceConfigActivity`, dan `MainActivity`):
- Di dalam fungsi `onCreate()`, tepat sebelum atau sesudah `setContentView(...)`, panggil fungsi untuk memberitahu sistem agar aplikasi diizinkan merender di bawah *system bars*:
  ```kotlin
  androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
  ```

### 2. Terapkan Listener WindowInsets pada Root View / Container Utama
Agar elemen UI tidak tertabrak *System Bars*, kita perlu mendeteksi tinggi dari *Status Bar* (atas) dan *Navigation Bar* (bawah) secara dinamis, lalu mengaplikasikannya sebagai *padding* atau *margin*:
- Gunakan `ViewCompat.setOnApplyWindowInsetsListener` pada *root layout* (contoh: `binding.root`).
- Ekstrak nilai insets menggunakan `WindowInsetsCompat.Type.systemBars()`.
- Dapatkan nilai `insets.top` (untuk batas atas) dan `insets.bottom` (untuk batas bawah).

### 3. Penyesuaian Spesifik pada Masing-Masing Layar
Tugas detail yang harus diubah di XML atau secara terprogram (*programmatically*):

- **Di Layar Camera Stream (`CameraStreamActivity`):**
  - **Teks IMU & Badge:** Tambahkan *margin-top* senilai `insets.top` pada container yang menampung teks IMU (Pitch/Roll/Accel) dan Badge "Menerima data dari ESP32" agar terdorong ke bawah melewati *notch* / *status bar*.
  - **Tombol Akhiri:** Tambahkan *margin-bottom* atau *padding-bottom* senilai `insets.bottom` (ditambah sedikit ekstra padding ~16dp) pada tombol **"AKHIRI"** agar posisinya terangkat ke atas *navigation bar*.
  
- **Di Layar Konfigurasi & Scanner (`MainActivity` / `DeviceConfigActivity`):**
  - **Header / App Bar:** Berikan *padding-top* senilai `insets.top` pada `Toolbar` atau teks judul paling atas agar tulisan "Connected"/"Disconnected" atau "ESP32 Config" tidak menabrak status bar.
  - **Aksi Bawah:** Jika ada komponen yang menempel di layar bawah (misal tombol *View Camera*), pastikan *margin-bottom*-nya disesuaikan dengan `insets.bottom`.

### 4. Checklist Pengujian (Testing)
Setelah mengimplementasikan langkah-langkah di atas, lakukan pengujian pada kondisi berikut:
- [ ] Tampilan pada device dengan *Gesture Navigation* (garis transparan di bawah layar).
- [ ] Tampilan pada device dengan *3-Button Navigation* (tombol fisik back/home/recent virtual yang memakan banyak ruang).
- [ ] Tampilan dalam orientasi *Portrait* dan *Landscape* (jika aplikasi mengizinkan rotasi).
- [ ] Tidak ada lagi teks (IMU/Status) yang terpotong oleh jam/baterai, dan semua tombol (seperti AKHIRI) bebas dari blokir area *Navigation Bar*.
