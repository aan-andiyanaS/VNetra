# Penjelasan Sederhana Formula G (Batas Aman Adaptif)

> **Dokumen:** Penjelasan bahasa manusia untuk Formula G (Threshold Adaptif)
> **Tujuan:** Memahami bagaimana alat menentukan kapan harus memberi peringatan suara kepada pengguna, dijelaskan tanpa rumus matematika yang rumit.
> **Referensi:** `docs/formula-matematis-threshold-adaptif-G.md`

---

## 1. Konsep Dasar: Apa itu Formula G?

Bayangkan Anda sedang berjalan menggunakan tongkat tunanetra. Jika Anda berjalan santai, Anda hanya perlu tahu rintangan yang jaraknya 1 meter di depan. Tapi, jika Anda **berlari kencang**, Anda butuh tahu rintangan yang jaraknya 3 meter agar punya waktu untuk mengerem.

Selain itu, jika rintangan di depan (seperti mobil) **bergerak mundur ke arah Anda**, Anda juga butuh diperingatkan lebih awal. 

**Formula G** adalah otak yang menghitung "Berapa meter batas aman (threshold) saat ini?". Batas aman ini terus memanjang atau memendek secara otomatis (adaptif) berdasarkan dua hal:
1. **Seberapa cepat Anda bergerak** (jalan santai vs lari).
2. **Seberapa cepat objek di depan mendekat ke Anda**.

---

## 2. Kenalan dengan Pemain Utama (Variabel)

Sebelum menghitung, sistem mengumpulkan data dari dua tempat: **Kacamata (Sensor Gerak/IMU)** dan **Kamera/ToF (Sensor Jarak)**.

| Nama Variabel | Arti Sehari-hari | Dari mana asalnya? |
|--------------|------------------|--------------------|
| **Jarak Benda** ($d_{obj}$) | Seberapa jauh benda itu sekarang (dalam milimeter). | Kamera & Sensor Jarak (ToF) |
| **Jarak Sebelumnya** ($d_{prev}$) | Jarak benda itu sepersekian detik yang lalu. | Ingatan sistem Android |
| **Selang Waktu** ($\Delta t$) | Waktu antara pengukuran sebelumnya dan sekarang. | Stopwatch internal sistem |
| **Efek Anggukan** ($v_{head\_base}$) | Seberapa kencang kepala Anda sedang mengangguk (naik-turun). | Sensor Gerak di kacamata (ESP32) |
| **Intensitas Langkah** ($a_{lin}$) | Seberapa heboh tubuh Anda bergerak maju. Nilainya 0 kalau diam, membesar kalau lari. | Sensor Gerak di kacamata (ESP32) |

---

## 3. Alur Cerita (Langkah demi Langkah)

Formula G bekerja melalui urutan logika berikut setiap sepersekian detik:

### Langkah 0: "Tunggu Sensor Fokus Dulu" (Guard Mahony)
Saat alat baru dinyalakan, sensor keseimbangan di kepala butuh waktu sekitar 5 detik untuk "pemanasan" agar tahu mana atas dan bawah. Selama masa ini, sistem pakai batas aman standar saja (1 meter).

### Langkah 1: "Menghapus Ilusi Mata" (Efek Semu / $v_{head}$)
Coba bayangkan Anda berdiri diam melihat tembok. Lalu Anda menganggukkan kepala ke bawah dengan cepat. Tembok itu akan **terasa seolah-olah bergerak** di mata kamera, padahal temboknya diam. 
- Sistem mengalikan kecepatan anggukan ($v_{head\_base}$) dengan jarak benda ($d_{obj}$).
- Tujuannya untuk tahu: *"Oh, kecepatan pergerakan sebesar ini murni karena kepalanya yang goyang, bukan karena temboknya maju."*

**Rumus Matematis:**
$$v_{head} = v_{head\_base} \cdot d_{obj}[t]$$

### Langkah 2: "Seberapa Cepat Benda Itu Aslinya Mendekat?" ($v_{raw}$)
Sistem menghitung selisih jarak benda (Jarak lalu dikurangi Jarak sekarang) kemudian dibagi waktu tempuhnya. Hasilnya adalah kecepatan kotor.
- Kemudian, kecepatan kotor ini **dikurangi** dengan "Ilusi Mata" dari Langkah 1.
- Hasilnya adalah $v_{raw}$ (kecepatan asli benda mendekat). Jika benda malah menjauh, nilainya dipaksa jadi 0 (karena kita tidak peduli benda yang menjauh).

**Rumus Matematis:**
$$v_{raw} = \frac{d_{obj}[t-1] - d_{obj}[t]}{\Delta t} - v_{head}$$
$$v_{raw} \leftarrow \max(0, v_{raw})$$

### Langkah 2a: "Orang Diam, Tembok Diam" (Noise Gate)
Sensor jarak kadang memiliki getaran kecil pembacaan (noise). Jika Anda sedang duduk diam (Intensitas Langkah / $a_{lin}$ rendah) dan di depan ada tembok statis, sistem akan berkata: *"Saya sedang duduk, tembok tidak punya kaki. Jadi kalau sensor bilang tembok ini mendekat lambat, itu pasti cuma noise sensor."*
- Sistem langsung **memaksa kecepatan benda menjadi 0**. Ini mencegah alat cerewet memberi peringatan palsu saat Anda sedang diam.

**Rumus Matematis:**
$$\text{Jika objek statis DAN } a_{lin} < 2.94\ \text{m/s}^2 \text{ maka } v_{raw} = 0$$

### Langkah 2b: "Jangan Kagetan" (Moving Average / $v_{avg}$)
Sistem tidak langsung percaya pada satu hasil kecepatan saja. Ia mengambil rata-rata dari 3 hasil terakhir agar hitungannya lebih stabil dan tidak kagetan gara-gara satu kesalahan sensor kecil.

### Langkah 3: "Penentuan Jarak Aman Akhir" (Threshold / $T$)
Ini adalah garis finish. Sistem meracik batas aman akhir dengan resep berikut:

1. **Modal Awal ($D_{W_0}$)**: Selalu mulai dari jarak aman standar, yaitu **1 meter (1000 mm)**.
2. **Ditambah Bahaya Benda Mendekat**: Kecepatan benda mendekat ($v_{avg}$) dikalikan 2 detik (karena rata-rata manusia butuh 2 detik untuk kaget dan menghindar).
3. **Ditambah Bahaya Kecepatan Berlari**: Intensitas langkah Anda ($a_{lin}$) dikali 200. Semakin Anda lari kencang, batas aman makin diperpanjang jauh ke depan.

Sistem memastikan hasil akhirnya tidak lebih dari 4 meter, karena sensor jarak tidak bisa melihat akurat lebih dari itu.

**Rumus Matematis:**
$$T = \min\!\Bigl(1000 + v_{avg} \cdot 2.0 + a_{lin} \cdot 200,\ 4000\Bigr)\ \text{mm}$$

---

## 4. Kesimpulan: Kapan Alat Akan Berteriak?

Setelah mendapat batas aman akhir ($T$), aturannya sangat simpel:

> **JIKA** (Jarak Benda di depan < Batas Aman $T$) **DAN** (Alat belum berteriak sebelumnya)  
> **MAKA** "Awas, Tembok di Depan!"

**Rumus Matematis:**
$$\text{ALERT} \iff d_{obj} < T \quad \text{DAN} \quad \text{flag}[id] = \texttt{false}$$

**Contoh Kasus Nyata Sehari-hari:**

1. **Duduk baca buku (Ada meja di jarak 80 cm):**
   - Tubuh diam ($a_{lin} = 0$), Meja diam ($v_{avg} = 0$).
   - Batas aman tetap = 1 meter.
   - Karena meja di 80 cm (kurang dari 1 m), alat teriak "Awas halangan". Tapi setelah itu alat diam, tidak cerewet terus-terusan (karena noise gate bekerja).
2. **Jalan santai:**
   - Kecepatan tubuh ($a_{lin}$) normal. Batas aman memanjang sedikit jadi **1.4 meter**. Anda aman diberitahu sebelum menabrak.
3. **Berlari kencang:**
   - Kecepatan tubuh ($a_{lin}$) sangat tinggi. Batas aman memanjang jauh jadi **3.2 meter**. Alat akan teriak dari jarak yang sangat jauh agar Anda punya ruang untuk mengerem mendadak.
4. **Jalan santai, tapi ada mobil mundur ke arah Anda:**
   - Tubuh santai, tapi benda mendekat sangat cepat ($v_{avg}$ tinggi). Batas aman otomatis memanjang jadi **2.5 meter**. Anda terselamatkan lebih cepat!
