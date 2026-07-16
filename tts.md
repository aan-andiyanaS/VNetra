# Laporan Text-to-Speech (TTS) VNetra

Laporan ini mendokumentasikan seluruh kemungkinan keluaran suara (pesan TTS) yang akan diucapkan oleh sistem VNetra kepada pengguna, beserta kondisi pemicunya dan prioritas antreannya. 

Sistem TTS pada VNetra diatur oleh modul `TtsAlertManager.kt` dan dibantu oleh logika pengkondisian di `CameraStreamActivity.kt`.

---

## 1. Pesan Peringatan Objek (YOLO & Spasial)

Kategori ini merupakan peringatan dinamis berdasarkan objek yang dideteksi oleh kamera (YOLO) dan jaraknya (ToF/Kamera).

### Aturan Konversi Jarak & Arah
*   **Jarak**: Dikonversi menjadi 3 tingkat berdasarkan Threshold Adaptif (T) dari Formula G:
    *   `Jarak < T * 0.5` → `"jarak dekat"`
    *   `Jarak < T * 1.5` → `"jarak sedang"`
    *   `Jarak >= T * 1.5` → `"jarak jauh"`
*   **Arah (Sistem Jam)**: Posisi objek di layar diterjemahkan menjadi 5 arah: `"jam 10", "jam 11", "jam 12", "jam 1", "jam 2"`.

### A. Objek Rintangan Umum (Orang, Motor, Mobil, Meja, dll.)
*   **Format**: `"{nama_objek}, {kategori_jarak}, {arah_jam}"`
*   **Contoh Keluaran**:
    *   *"Motor, jarak sedang, jam 11"*
    *   *"Orang, jarak dekat, jam 12"*
    *   *"Kursi, jarak jauh, jam 1"*
*   **Prioritas (Antrean)**:
    *   Jika arah objek tepat di depan (Jam 11, 12, atau 1): Pesan berstatus **URGENT** (Memotong suara lain).
    *   Jika arah objek di tepi/peripheral (Jam 10 atau 2): Pesan berstatus **INFO** (Masuk antrean suara).

> [!NOTE]
> Jika ada beberapa objek berbahaya sekaligus, pesan akan digabung. Contoh: *"Motor, jarak dekat, jam 11, dan orang, jarak sedang, jam 12"*.

### B. Objek Paving (Guiding Block)
Sistem mengenali *guiding block* jalan (lurus, belok, simpang 3, simpang 4, stop).
*   **Format 1 (Jika Paving sangat dekat < 50cm)**: `"{nama_paving}, {arah_jam}"` *(Kata "jarak" dihilangkan agar lebih cepat)*
    *   *Contoh: "Paving lurus, jam 12"*
    *   *Contoh: "Paving belok, jam 2"*
*   **Format 2 (Jika Paving > 50cm)**: `"{nama_paving}, {kategori_jarak}, {arah_jam}"`
    *   *Contoh: "Paving simpang tiga, jarak sedang, jam 12"*
*   **Prioritas (Antrean)**: Selalu berstatus **INFO** (Masuk antrean suara agar tidak memotong peringatan bahaya).

---

## 2. Pesan Perubahan Permukaan (Elevasi / Terrain)

Kategori ini dipicu ketika sensor ToF mendeteksi pola tanah yang tidak rata (tangga atau lubang) dengan akurasi tinggi.

*   **Pemicu**: `TerrainDetector.kt` memvalidasi kelandaian ekstrem (delta Z).
*   **Jenis Permukaan yang Dikenali**: `"tangga turun"`, `"tangga naik"`, `"lubang"`, dan `"objek dekat"` (halangan rendah di bawah lutut).
*   **Format**: `"{jenis_permukaan}, {kategori_jarak}, {arah_jam}"`
*   **Contoh Keluaran**:
    *   *"Tangga turun, jarak dekat, jam 12"*
    *   *"Lubang, jarak sedang, jam 11"*
*   **Prioritas (Antrean)**: 
    *   Jika *confidence* AI >= 70%: **URGENT** (Memotong suara lain).
    *   Jika *confidence* AI < 70%: **INFO** (Masuk antrean).

---

## 3. Pesan *Smart Navigation* (Pemandu Ruang & Tembok)

Kategori ini berasal dari `SmartNavigationTts` di dalam `TtsAlertManager.kt`. Fungsi ini berguna sebagai pemandu saat tunanetra mencoba mencari jalan di dalam ruangan kosong atau saat berhadapan dengan tembok besar (tanpa objek YOLO).

### A. Tembok & Halangan Pasif
*   **Kondisi**: ToF mendeteksi halangan besar secara merata di depan, tetapi AI tidak mendeteksi objek spesifik. 
*   **Format Peringatan Detail**: `"Tembok, {kategori_jarak}, jam 12"` *(Contoh: "Tembok, jarak dekat, jam 12")*.
*   **Format Peringatan Mendadak (Smart Nav)**: Jika transisi mendadak dari aman ke terhalang, dan pengguna tidak sedang memalingkan kepalanya untuk mencari jalan:
    *   *"Awas, tembok di depan"*
*   **Spam Pencegahan**: Jika sistem sudah mengingatkan tembok, tetapi pengguna masih nekat melangkah maju:
    *   *"Awas, masih ada tembok"* (Diulang setiap 3 detik jika terus dipaksa maju).

### B. Pemandu Jalan Kosong (CLEAR)
*   **Kondisi 1 (Penemuan Jalan)**: Pengguna yang sebelumnya terjebak (berhadapan dengan tembok), lalu memutar badannya dan sensor ToF mendeteksi bahwa lorong tersebut sekarang kosong.
    *   *"Jalan di depan kosong"* (Hanya diucapkan 1 kali sebagai penanda sukses).
*   **Kondisi 2 (Keraguan Pengguna)**: Jalan di depan sudah kosong dan aman, tetapi pengguna terdeteksi diam (oleh sensor IMU) selama lebih dari 6 detik.
    *   *"Jalan aman, silakan maju"* (Mendorong pengguna untuk melangkah).

---

> [!IMPORTANT]
> **Hysteresis & Falloff (Anti-Spam)**  
> Segala jenis peringatan memiliki mekanisme pendingin (*cooldown*). Sistem tidak akan mengulang-ulang kata *"Motor, jarak dekat, jam 11"* setiap detik. Peringatan berikutnya hanya akan diucapkan jika objek tersebut secara matematis **semakin mendekat dengan kecepatan agresif** melampaui ambang batas pergerakan (*Hysteresis EPS_NOISE*), atau jika pengguna berjalan maju dengan agresif menuju objek pasif tersebut.
