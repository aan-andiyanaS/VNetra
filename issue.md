# Plan Penyesuaian Layout Grid ToF (VL53L5CX) pada Camera Stream

## Latar Belakang Masalah
Saat ini, grid jarak dari sensor ToF (VL53L5CX) dirender menutupi area *Camera Stream* secara penuh. Padahal, *aspect ratio* dari tangkapan kamera adalah **4:3** (resolusi `640x480`), sedangkan hasil pembacaan zona dari ToF VL53L5CX membentuk matriks **8x8** yang merupakan persegi (*aspect ratio* **1:1**). 

Jika *aspect ratio* grid ToF dipaksakan menutupi seluruh dimensi kamera, maka setiap kotak grid akan mengalami distorsi (tertarik menjadi lonjong). Secara proporsional, grid ToF 8x8 seharusnya ditampilkan secara persegi murni (misal dirender seolah-olah memiliki resolusi layar `480x480`). 
Karena resolusi layar adalah `640x480`, ini akan menyisakan ruang ekstra secara horizontal sebesar 160 unit, yang seharusnya dibagi dua secara adil sebagai area tanpa data ToF di bagian margin kiri (80 unit) dan margin kanan (80 unit).

## Tujuan
Memperbaiki antarmuka (UI) di `CameraStreamActivity` agar overlay data grid ToF 8x8 dirender dalam bentuk **persegi sempurna (1:1)**, dan ditempatkan tepat di **tengah-tengah** area stream kamera (*aspect ratio 4:3*).

## Instruksi Pengerjaan (High Level)

### 1. Penyesuaian Layout UI (`activity_camera_stream.xml`)
- **Modifikasi Overlay ToF:** Ubah properti dari pembungkus grid ToF (misalnya `GridLayout`) agar bentuknya dipaksa menjadi persegi (Aspect Ratio 1:1).
- **Pemosisian (*Centering*):** Posisikan elemen grid ToF tersebut di **tengah-tengah** (center) area *stream* kamera secara horizontal maupun vertikal.
- **Responsivitas:** Hindari penggunaan nilai absolut (*hardcode*) `80px` untuk lebar pinggiran. Alih-alih, gunakan properti dari *ConstraintLayout* seperti `app:layout_constraintDimensionRatio="1:1"`, kemudian berikan relasi constraint ke sisi kiri dan kanan agar otomatis memposisikan dirinya di tengah. Dengan cara ini, UI akan tetap tampil proporsional (margin simetris) pada berbagai ukuran layar smartphone.

### 2. Modifikasi Komponen di dalam Grid
- Pastikan bahwa setiap kotak/sel (`TextView` pembacaan jarak) di dalam `GridLayout` 8x8 dibagi secara rata (mendapatkan lebar dan tinggi yang proporsional sehingga tiap sel membentuk persegi kecil).
- Manfaatkan konfigurasi lebar dan tinggi berbasis beban (layout weight) atau *match constraint* agar 8 baris dan 8 kolom mendistribusikan ruang 1:1 tersebut secara merata.

### 3. Pengecekan Aspek Visual & Penandaan Batas (Opsional/Disarankan)
- Pertimbangkan untuk menambahkan pembatas transparan tipis atau warna kontras di sekeliling area ToF (area 1:1) sebagai penanda visual yang membantu *user* memahami batasan jangkauan ToF.
- Hal ini akan mempertegas bahwa ruang di sisi paling kiri dan paling kanan dari layar *stream* kamera adalah wilayah *blind spot* bagi sensor ToF.

## Kriteria Selesai (Definition of Done)
1. Grid 8x8 ToF berubah bentuk menjadi *square* murni (persegi, tidak lonjong).
2. Terdapat margin area tanpa overlay data ToF (hanya menampilkan gambar video secara jernih) yang luasnya simetris di ujung paling kiri dan paling kanan layar.
3. Seluruh angka bacaan nilai spasial jarak ToF (`mm`) masih tetap terbaca jelas di resolusi yang diperbarui.
