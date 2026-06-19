# Perbaikan Bug Fatal & Optimasi Stabilitas Notebook (YOLO11n)

Dokumen ini merangkum seluruh perbaikan bug kritis yang dilakukan pada proyek **VNetra** yang diselesaikan pada titik *commit* `0439ccc8c11677d22ab2bc472b4dca7a2174ffdf`. Fokus pembaruan kali ini adalah memastikan *pipeline* berjalan 100% mulus dari awal hingga akhir (*Save & Run All*) di platform Kaggle maupun Colab tanpa interupsi manual.

## 1. Perbaikan Kritis Lingkungan (Environment C-Extension Crash)
*   **Akar Masalah:** Terjadi *error* mematikan `ImportError: The _imaging extension was built for another version of Pillow` pada sel pertama. Ini disebabkan Kaggle/Colab telah menanamkan ekstensi modul C `_imaging` ke dalam memori secara permanen, yang berbenturan dengan instalasi versi `Pillow` yang baru.
*   **Solusi:** Menulis ulang logika instalasi dependensi (pip install) dengan **sistem penguncian dinamis**. Notebook kini akan secara otomatis mendeteksi versi Pillow bawaan sistem (contoh: 11.3.0), dan memaksa *pip* untuk menahan agar versi tersebut tidak diganti (`Pillow=={pil_ver}`). Hal ini 100% meniadakan *crash* karena modul Python dan modul C tetap selaras.

## 2. Resolusi Jaringan Pengunduhan Dataset (COCO WebSessionError)
*   **Akar Masalah:** Saat mengunduh 3000 gambar subset COCO, server `images.cocodataset.org` menolak koneksi secara massal (*ConnectionResetError* atau *WebSessionError*) akibat terlalu banyak antrean unduhan serentak dari alamat IP yang sama.
*   **Solusi:**
    1.  Menurunkan beban paralel ke *server* COCO dengan mengaktifkan parameter pencekik `num_workers=2`.
    2.  Menanamkan sistem **Auto-Resume & Retry** (Maksimal 5x coba). Jika terjadi pemutusan koneksi di tengah jalan (misal: gagal pada gambar ke 1150), notebook akan menunggu 5 detik, lalu secara otomatis melanjutkan sisa unduhan tanpa memulai dari nol dan tanpa menghentikan *Run All*.

## 3. Penyapuan Kesalahan Logika Penulisan (Sequential Thinking)
Berdasarkan hasil pemindaian *Sequential Thinking* terhadap seluruh isi notebook, dua *bug* logika murni telah ditemukan dan diperbaiki:
*   **Penghapusan IndentationError:** Sebuah variabel kosong `copied_count = 0` yang menjorok (indentasi) secara ilegal di luar blok perulangan pada logika *dataset merging* telah diperbaiki posisinya untuk menghindari kegagalan eksekusi (*syntax error*).
*   **Penghapusan NameError:** Pada sel laporan akhir distribusi Dataset (sesaat sebelum *training* dimulai), rumus pembagian persentase menggunakan nama variabel yang salah cetak (`total_lembar`). Variabel tersebut telah disinkronkan kembali menjadi `total_images`, sehingga laporan distribusi akan tercetak dengan sempurna tanpa memicu *crash* di menit-menit kritis.
