# Penyelarasan Sensor ToF (VL53L5CX) dan Kamera (OV2640)

Issue ini merangkum seluruh logika dan implementasi UI yang diterapkan untuk menyelaraskan pembacaan jarak dari sensor ToF dengan visualisasi stream dari kamera, mengingat terdapat perbedaan *Field of View* (FOV) dan *offset* fisik.

## 1. Spesifikasi Hardware
*   **Sensor Kamera (OV2640):** Memiliki Field of View (FOV) sebesar **65°**.
*   **Sensor ToF (VL53L5CX):** Memiliki Field of View (FOV) sebesar **45°** dengan resolusi 8x8 zona.
*   **Offset Fisik:** Posisi modul kamera berada **0,5 cm di bawah** sensor ToF. (Artinya, ToF melihat area yang sedikit lebih tinggi daripada area yang ditangkap kamera).

## 2. Masalah yang Diselesaikan
Jika grid 8x8 ToF sekadar diletakkan di atas layar kamera secara *fullscreen*, maka:
1.  Kotak pembacaan ToF akan merepresentasikan sudut 65°, padahal aslinya ToF hanya memancarkan sinar selebar 45°. Ini menyebabkan ketidaktepatan horizontal (objek di layar terlihat masuk zona pinggir, padahal tidak terdeteksi ToF).
2.  Karena kamera berada 0,5 cm lebih rendah dari ToF, pusat pandangan ToF sedikit bergeser ke atas dibandingkan pusat layar kamera (Parallax error).

## 3. Implementasi Solusi pada UI (Android)

Untuk mengatasi masalah tersebut, telah dilakukan beberapa kalibrasi pada `CameraStreamActivity`:

### A. Kompensasi FOV (Field of View)
Karena FOV ToF (45°) lebih sempit daripada FOV Kamera (65°), lebar grid ToF diperkecil secara proporsional.
*   **Perhitungan:** $45 / 65 \approx 0.692$.
*   **Implementasi:** Di file `activity_camera_stream.xml`, lebar `GridLayout` diatur menjadi 69% dari lebar frame kamera menggunakan atribut:
    `app:layout_constraintWidth_percent="0.69"`

### B. Menjaga Bentuk Grid Persegi Sempurna (1:1)
Pembacaan ToF 8x8 merepresentasikan area fisik yang persegi. Agar kotak di layar tidak memanjang atau melebar:
*   **Implementasi:** Digunakan atribut `app:layout_constraintDimensionRatio="1:1"`.
*   Digabungkan dengan `rowCount="8"` dan `columnCount="8"`, ini memastikan ke-64 sel memiliki ukuran yang presisi (kotak sempurna).

### C. Kompensasi Parallax (Pergeseran Vertikal / Translasi)
Karena ToF diposisikan 0,5 cm lebih tinggi dari kamera, grid ToF di layar harus digeser sedikit ke atas. Melalui beberapa eksperimen visual, titik optimal didapatkan dengan menggeser grid persis 1 baris ke atas.
*   **Implementasi XML:** Grid dikunci ke bagian atas bingkai kamera menggunakan `app:layout_constraintTop_toTopOf="@id/ivCameraFrame"` dan `app:layout_constraintVertical_bias="0.0"`.
*   **Implementasi Kotlin:** Setelah antarmuka di-*render*, grid didorong naik ke atas sebesar 1 baris (1/8 dari tingginya) secara terprogram menggunakan fungsi `.post`:
    ```kotlin
    binding.gridTof.post {
        binding.gridTof.translationY = -(binding.gridTof.height / 8f)
    }
    ```
*   **Hasil Visual:** Baris pertama (index 0-7) berada di luar batas pandangan atas layar kamera, sementara baris ke-2 hingga ke-8 tumpang tindih secara presisi dengan visual objek di depan kamera.

## 4. Langkah Pengujian Selanjutnya
Jika diperlukan penyesuaian (*fine-tuning*) di masa mendatang:
*   **Horizontal:** Ubah nilai `0.69` jika area pembacaan kiri-kanan terasa kurang akurat.
*   **Vertikal:** Ubah pembagi pada `translationY` (misalnya `/ 6f` atau `/ 10f`) jika benda pada jarak tertentu masih belum pas menyentuh kotak grid.
