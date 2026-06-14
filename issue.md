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

---

# Plan Fitur Tambahan: Indikator Arah Jam (Clock Direction) 10:00 - 14:00

## 📌 Deskripsi Fitur
Pengguna membutuhkan representasi spasial (penunjuk arah) berupa "Arah Jam" (Clock Direction) di atas layar *streaming* kamera. Arah ini dimulai dari jam 10 (paling kiri), jam 11, jam 12 (tengah lurus), jam 1, hingga jam 2 (paling kanan). Pemetaan ini harus dibagi rata berdasarkan jumlah panjang pixel (lebar layar/frame kamera).

## 🛠️ Rencana Eksekusi (Untuk Junior Developer)

Tujuan utama dari tugas ini adalah membuat UI pembagian zona arah jam yang memetakan lebar frame kamera (pixel) secara merata menjadi 5 zona (10, 11, 12, 1, 2) tanpa tumpang tindih dengan elemen lain.

Berikut langkah-langkah detail pengerjaannya:

### 1. Penambahan UI di XML (`activity_camera_stream.xml`)
Gunakan pendekatan `LinearLayout` dengan atribut `layout_weight` untuk membagi lebar layar (pixel) secara merata dan otomatis tanpa perlu menghitung pixel secara manual di Kotlin.

- Cari `ConstraintLayout` dengan id `@+id/cameraContainer` yang membungkus `ivCameraFrame` dan `gridTof`.
- Tambahkan komponen `LinearLayout` baru di dalam container tersebut, dan atur agar posisinya berada di tengah baris (vertical center) dari area kamera.
- Konfigurasi `LinearLayout`:
  ```xml
  <LinearLayout
      android:id="@+id/clockDirectionIndicator"
      android:layout_width="0dp"
      android:layout_height="wrap_content"
      android:orientation="horizontal"
      android:weightSum="5"
      android:background="#40000000" <!-- Background semi-transparan agar teks terbaca -->
      app:layout_constraintTop_toTopOf="@id/ivCameraFrame"
      app:layout_constraintBottom_toBottomOf="@id/ivCameraFrame"
      app:layout_constraintStart_toStartOf="@id/ivCameraFrame"
      app:layout_constraintEnd_toEndOf="@id/ivCameraFrame">
      
      <!-- Tambahkan 5 TextView di dalamnya -->
  </LinearLayout>
  ```
- Tambahkan 5 buah `TextView` di dalam `LinearLayout` tersebut. Masing-masing TextView diberi atribut:
  - `android:layout_width="0dp"`
  - `android:layout_height="wrap_content"`
  - `android:layout_weight="1"` (Agar panjang pixel dibagi rata ke 5 area)
  - `android:textAlignment="center"` atau `android:gravity="center"`
  - `android:textColor="#FFFFFF"` dan `android:textStyle="bold"`
  - Isi teks masing-masing secara berurutan: "10", "11", "12", "1", "2".
  - Berikan ID yang jelas (misal `@+id/tvClock10`, `@+id/tvClock11`, dst.) agar bisa dimodifikasi melalui kode.

### 2. Penyesuaian Margins di Kotlin (`CameraStreamActivity.kt`)
Karena fitur ini ditambahkan di atas `ivCameraFrame`, pastikan tidak bertabrakan dengan panel IMU (`@+id/layoutImu`) atau tertutup oleh poni/status bar.
- Jika diletakkan menggunakan *ConstraintLayout*, UI ini otomatis merespons batas dari `ivCameraFrame`.
- Pastikan insets (safe area) tetap diaplikasikan jika elemen ini berada di area paling atas layar, atau jika diletakkan di dalam *ConstraintLayout*, posisinya sudah relatif terhadap elemen lain.

### 3. Sifat Overlay (Statis berdasarkan Pixel)
Pemetaan angka jam ini **murni berbasis pembagian pixel lebar layar** dan letaknya berada di luar sistem *grid cell* ToF. Ini berarti angka jam 10 hingga 2 hanya sebagai panduan statis pembagian visual untuk pengguna dan tidak terikat dengan logika deteksi ToF atau warna yang berubah-ubah secara dinamis.

### 4. Checklist Pengujian (Testing)
- [ ] Teks "10", "11", "12", "1", "2" muncul terbagi rata (proporsional secara pixel) sejajar dengan lebar tampilan kamera.
- [ ] Posisi angka jam berada tepat di **tengah baris** (vertikal) pada frame kamera.
- [ ] Overlay arah jam tidak menghalangi tampilan elemen lain seperti panel IMU.
- [ ] Teks terbaca jelas dengan background *semi-transparent*.
- [ ] Rotasi layar (jika diizinkan) tetap menjaga pembagian pixel secara proporsional.
