# Rencana Implementasi: Sistem Navigasi Cerdas Paving & Penyederhanaan TTS untuk Tunanetra

## Latar Belakang
Sistem peringatan suara (TTS) sebelumnya memberikan informasi yang terlalu teknis dan panjang (contoh: "tangga, jarak seratus dua puluh sentimeter, arah jam dua belas"). Hal ini membuat tunanetra kesulitan memproses informasi saat berjalan cepat. Selain itu, objek khusus seperti **Paving (Guiding Block)** membutuhkan perlakuan khusus, karena paving bukan halangan (rintangan), melainkan jalur panduan. Oleh karena itu, kita perlu membedakan cara *headset* merespons paving dan merespons rintangan biasa, serta mempertimbangkan gerak tubuh (melangkah atau berdiam).

## Detail Perubahan (Implementasi)

### 1. Penyederhanaan Kosa Kata TTS (Kecepatan Kognitif)
Alih-alih membacakan angka jarak yang presisi, sistem sekarang mengategorikan kedalaman spasial menjadi tiga zona intuitif:
- **Jarak Dekat**: < 50 cm (Sangat mendesak, hampir bersentuhan)
- **Jarak Sedang**: 50 cm - 150 cm (Dalam jangkauan tongkat)
- **Jarak Jauh**: > 150 cm (Sebagai informasi kewaspadaan dini)

Format arah jam juga disingkat, dari *"arah jam dua belas"* menjadi *"arah dua belas"*.
**Contoh Output Baru**: *"Tangga, jarak dekat, arah 12"*

### 2. Penyesuaian Identitas Paving (Guiding Block)
Paving memiliki berbagai variasi kelas ("lurus", "belok", "simpang 3", "simpang 4", "stop"). Agar pengguna tidak bingung jika alat tiba-tiba bilang "lurus", sistem kini secara dinamis menambahkan kata "Paving" di depan kelas tersebut.
**Contoh Output Baru**: *"Paving lurus, jarak sedang, arah 12"*

### 3. Logika Penghilangan Jarak Khusus Paving (Anti-Redundant)
Berbeda dengan rintangan (seperti motor atau lubang) di mana jarak *dekat* berarti bahaya, paving yang *dekat* berarti pengguna **sedang menginjak dan berada di jalur yang benar**.
Oleh karena itu, jika paving berada pada kategori **jarak dekat**, penyebutan jarak akan dihilangkan agar super instan.
**Contoh Output Baru**: *"Paving simpang 3, arah 12"* (Lebih cepat direspons telinga).

### 4. Logika Pengingat Berkala (Stationary Paving Reminder)
Ini adalah fitur keselamatan orientasi tingkat lanjut yang menggabungkan AI penglihatan (YOLO) dengan sensor gerak (IMU Accelerometer di dada):
- **Saat Melangkah Terus (>0.3 m/s²)**: *Headset* hanya akan membacakan posisi paving **1 kali saja** di awal untuk menghindari suara *spam* yang menumpuk.
- **Saat Berhenti/Ragu (<0.3 m/s²)**: Jika alat mendeteksi pengguna berhenti melangkah selama 6 detik (misalnya karena kehilangan jejak paving atau meraba-raba dengan tongkat), *headset* akan berinisiatif membacakan ulang posisi paving yang ada di depannya secara berkala (setiap 6 detik) hingga pengguna melangkah kembali.

## Manfaat untuk Pengguna (Tunanetra)
- **Minimalisir Kelelahan Mendengar (Ear-Fatigue)**: Suara hanya muncul ketika benar-benar dibutuhkan. Pengulangan hanya terjadi ketika pengguna butuh "diingatkan" (saat diam), namun diam saat pengguna sudah melangkah lancar.
- **Respon Refleks Lebih Baik**: Dengan kata yang lebih sedikit, *delay* pendengaran ke otak jauh lebih singkat, sangat krusial untuk menghindari tabrakan mendadak.
- **Orientasi Spasial**: Pengguna selalu tahu apakah mereka harus berbelok atau terus lurus tanpa harus selalu menunduk atau menyapu tongkat terlalu lebar.
