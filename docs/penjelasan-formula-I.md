# Penjelasan Formula I: "Kotak Makin Besar = Bahaya Makin Dekat"

> **Untuk siapa dokumen ini?** Untuk siapa saja — baik sesama pengembang, dosen penguji, maupun pembaca skripsi — yang ingin memahami **mengapa dan bagaimana** VNetra bisa mendeteksi objek yang sedang mendekat, tanpa perlu memiliki latar belakang matematika yang dalam.

---

## Intuisi Dasar: Pelajaran dari Sopir Bus

Coba bayangkan Anda adalah seorang sopir bus yang menatap ke depan. Anda tidak punya alat ukur jarak. Namun, otak Anda secara insting tahu bahwa sebuah motor di kejauhan mulai **mengancam** bukan karena Anda tahu jaraknya 10 meter — melainkan karena **ukurannya di mata Anda makin membesar, dan membesar dengan cepat**.

Inilah prinsip yang sama persis yang digunakan oleh Formula I dalam sistem VNetra.

> **Aturan emas Formula I:**
> "Semakin cepat sebuah kotak deteksi (bounding box) membesar di layar kamera, semakin segera benturan akan terjadi."

Prinsip ini bukan asumsi sembarangan. Ia dibuktikan secara ilmiah oleh **David N. Lee**, seorang psikolog ekologi dari University of Edinburgh, dalam makalahnya tahun 1976. Lee menemukan bahwa makhluk hidup — dari lalat hingga manusia — menggunakan laju pelebaran bayangan visual (bukan jarak dalam meter!) untuk memutuskan kapan harus menghindari tabrakan.

---

## Mengapa Formula I Sangat Penting untuk VNetra?

Sensor jarak utama VNetra (ToF / Time-of-Flight) punya kelemahan fisik: **ia buta total di atas 1 meter saat siang hari karena cahaya matahari terlalu terang** (lihat: [report-tof.md](./decisions/report-tof.md)).

Artinya, ada *blind spot* yang berbahaya:
- Sensor ToF: melihat 0 – 1 meter (di siang hari)
- Tanpa Formula I: **1 meter – 20 meter? Kosong. Sistem buta.**
- Dengan Formula I: 1 meter – 20 meter terdeteksi menggunakan kamera YOLO

Formula I adalah "mata jarak jauh" yang mengisi kebutaan tersebut.

---

## Cara Kerja Step-by-Step (Tanpa Rumus)

Setiap kali YOLO berhasil mendeteksi sebuah objek (misalnya: motor, orang, mobil), Formula I mengajukan **4 pertanyaan sekaligus**:

### Pertanyaan 1: "Seberapa cepat kotaknya membesar?" → `area_score`

```
Frame sebelumnya:   [  Kotak motor  ]   ← ukurannya segini
Frame sekarang:     [ Kotak motor lebih besar ]   ← makin besar
```

Kalau kotak membesar **50% atau lebih** antar frame, area_score = 1.0 (ancaman penuh).
Kalau kotaknya mengecil atau tidak berubah, area_score = 0.0 (tidak ada ancaman).

**Rumus area_score secara awam:**
```
area_score = (persentase pembesaran kotak) / 50%
```
Maksimal 1.0, minimal 0.0.

### Pertanyaan 2: "Apakah kotaknya memanjang atau melebar secara aneh?" → `ar_score`

Sebuah motor yang betul-betul mendekati Anda secara lurus akan terlihat **makin besar, tapi tetap proporsional** (rasio lebar/tinggi kotak stabil).

Sebaliknya, sebuah motor yang sedang **memutar badan** (bukan mendekat) akan terlihat tiba-tiba berubah bentuk — dari panjang-sempit (tampak samping) menjadi pendek-lebar (tampak depan). Ini adalah sinyal palsu!

```
Mendekat lurus → Bentuk kotak stabil   → ar_score tinggi (tidak palsu)
Memutar badan  → Bentuk kotak berubah  → ar_score rendah (tandai sebagai palsu)
```

Perubahan rasio hingga 20% masih dianggap wajar. Lebih dari itu, skor dikurangi.

### Pertanyaan 3: "Apakah sensor jarak setuju?" → `dist_score`

Logika sederhana: kalau kotaknya makin besar (objek mendekat), seharusnya sensor ToF juga melaporkan jarak yang makin kecil. Kalau kotaknya membesar tapi sensor ToF bilang jarak tetap sama atau malah bertambah jauh — ada yang tidak konsisten, kemungkinan besar itu pergerakan semu (rotasi, bukan mendekat).

| Kondisi | Skor |
|---|---|
| Kotak membesar + jarak ToF berkurang → **Konsisten** | 1.0 (Pasti mendekat) |
| Kotak membesar + jarak ToF tidak berubah → **Meragukan** | 0.5 (Mungkin mendekat) |
| Kotak membesar tapi jarak ToF bertambah → **Inkonsisten** | 0.0 (Kemungkinan besar palsu) |

> **Catatan penting:** Saat ToF buta (menampilkan "--" di siang hari), `dist_score` otomatis diabaikan. Formula I tetap bisa bekerja hanya dari pertanyaan lainnya.

### Pertanyaan 4: "Apakah objek hanya melintas di pinggir jalan?" → `lat_score` (Lateral Drift)

Ini adalah masalah klasik dalam sistem peringatan tabrakan (ADAS). Bagaimana jika Anda berdiri di trotoar pinggir jalan menghadap ke depan (jam 12), lalu ada mobil melaju kencang melintas di jalan raya di depan Anda?
Kotak mobil itu akan **membesar** (karena ia mendekati titik terdekat dengan Anda), tetapi ia **tidak akan menabrak Anda**, melainkan hanya lewat dari kiri ke kanan.

Untuk mengatasi "alarm palsu" ini, sistem mengecek pergerakan horizontal (sumbu X) dari pusat kotak objek:
- Jika objek lurus menabrak Anda, pusat kotaknya akan **stabil di tengah**.
- Jika objek hanya lewat menyamping, pusat kotaknya akan **bergeser drastis (drifting) dari satu sisi ke sisi lain**.

Jika pusat kotak bergeser horizontal **lebih dari 20% lebarnya sendiri** per *frame*, sistem menyimpulkan bahwa kendaraan itu sedang melintas di jalur lain (*lateral drift*), dan skor ancamannya langsung di-Nol-kan (diabaikan).

---

## Menggabungkan 3 Jawaban Menjadi 1 Skor

Keempat jawaban di atas digabung menjadi satu angka: **TTC_score** (antara 0.0 hingga 1.0).

$$\text{TTC\_score} = [(0.50 \times \text{area\_score}) + (0.25 \times \text{ar\_score}) + (0.25 \times \text{dist\_score})] \times \text{lat\_score}$$

Dibaca: "Pembesaran kotak bobotnya paling besar (50%), lalu dibantu oleh stabilitas bentuk (25%) dan sensor ToF (25%). Setelah itu, kalikan dengan *Lateral Drift Score* — jika terbukti objek hanya melintas menyamping, nilai total langsung hangus (dikali 0)."

### Penyesuaian Berdasarkan Jenis Objek

Tidak semua objek sama berbahayanya meskipun skor TTC-nya sama. Mobil 1.5 ton yang mendekat jauh lebih berbahaya daripada sepeda yang mendekat, meskipun kotaknya membesar dengan kecepatan yang sama. Maka skor dikalikan bobot bahaya:

$$\text{TTC\_weighted} = \text{TTC\_score} \times \text{bobot\_kelas}$$

Pendekatan sistem VNetra dalam membedakan bobot ancaman didasarkan pada riset otomotif modern mengenai **Dynamic Time-to-Collision (DTTC)**. Dalam teori DTTC, ambang batas bahaya (threshold) tidak boleh statis, melainkan harus dinamis menyesuaikan profil fisik objek.

Nilai bobot bahaya (Multiplier) di VNetra diturunkan dari dua prinsip fisika dasar:
1. **Energi Kinetik & Momentum ($p = mv$)**: Semakin besar massa kendaraan, semakin fatal dampak tabrakannya.
2. **Jarak Pengereman (Stopping Distance)**: Kendaraan berat secara hukum fisika membutuhkan jarak yang jauh lebih panjang untuk berhenti total, sehingga sistem peringatan VNetra harus "diduplikasi" (di-trigger lebih awal) untuk mengakomodasi jarak tersebut.

Oleh karena itu, Pejalan Kaki (Orang) dijadikan nilai acuan dasar (*baseline* = 1.0), sedangkan kendaraan bermotor diberi bobot tambahan (margin keselamatan ekstra) agar peringatan berbunyi lebih dini.

| Jenis Objek | Pengali ($m_{class}$) | Landasan Teknis (DTTC & Fisika) |
|---|---|---|
| **Bus / Truk** | **× 1.6** | Margin +60%. Memiliki massa masif (belasan ton) dan jarak pengereman terpanjang. Visibilitas sopir sangat terganggu (*blind spots* luas). Ancaman fatalitas absolut. |
| **Mobil** | **× 1.5** | Margin +50%. Massa tinggi (> 1000 kg). Kecepatan bervariasi dengan manuver ruang sempit yang buruk. Potensi fatalitas sangat tinggi bagi pejalan kaki. |
| **Motor** | **× 1.2** | Margin +20%. Kecepatan tinggi namun memiliki kelincahan manuver (radius putar kecil), sehingga pengendara seringkali masih mampu menghindar di saat-saat terakhir. |
| **Orang** | **× 1.0** | *Baseline*. Kecepatan rendah dan massa setara dengan pengguna tunanetra. Energi benturan minimal. |
| **Sepeda** | **× 0.8** | Relatif lambat dan ringan. |

> **Catatan Akademis:** Kenapa Truk hanya diberi margin +60% (1.6), dan tidak dikali 5.0 sekalian agar sangat aman? 
> Ini untuk menghindari **Alarm Fatigue (Kelelahan Alarm)**. Jika truk diberi bobot terlalu besar, truk yang bergerak sangat pelan di kejauhan (yang sebenarnya belum mengancam) akan langsung memicu peringatan `IMMINENT` palsu terus-menerus. Nilai 1.6 adalah *sweet spot* kalibrasi heuristik (*Educated Guess*) yang menyeimbangkan kewaspadaan dini tanpa menghasilkan *spam* peringatan.

### Keputusan Akhir

Dari skor akhir (`TTC_weighted`), sistem mengambil salah satu dari 3 keputusan:

| Skor | Status | Aksi |
|---|---|---|
| > 0.75 | **IMMINENT** (Segera!) | Peringatan suara langsung, paksa alert terlepas dari ToF |
| 0.40 – 0.75 | **PROBABLE** (Mungkin) | Kirim ke Formula G untuk konfirmasi lanjutan |
| < 0.40 | **POSSIBLE** (Lemah) | Dicatat, tidak menghasilkan suara |

---

## Contoh Nyata: Motor yang Menyerobot

**Skenario:** Seekor motor melaju kencang dari arah kanan, memasuki frame YOLO dari sisi.

**Frame 1 → Frame 2:**
- Kotak motor membesar 40% → `area_score = 0.80`
- Kotak motor melebar (motor tampak lebih frontal) → `ar_score = 0.85` (masih stabil)
- ToF melaporkan jarak berkurang 200mm → `dist_score = 1.0`

$$\text{TTC\_score} = 0.50 \times 0.80 + 0.25 \times 0.85 + 0.25 \times 1.0 = 0.863$$

$$\text{TTC\_weighted} = 0.863 \times 1.2\ (\text{motor}) = 1.0\ \text{(dibatasi di 1.0)}$$

$$\rightarrow\ \boxed{\textbf{IMMINENT} — \text{Peringatan suara langsung dibunyikan}}$$

---

## Contoh Nyata: Motor yang Hanya Memutar Badan di Tempat

**Skenario:** Seorang pengendara motor berbalik arah di tempat, kotak YOLO membesar karena tampak samping menjadi tampak depan — bukan karena mendekat.

**Frame 1 → Frame 2:**
- Kotak membesar 35% → `area_score = 0.70`
- Rasio lebar/tinggi berubah drastis (dari panjang-sempit menjadi pendek-lebar) → `ar_score = 0.0` (rotasi terdeteksi!)
- ToF tidak melaporkan perubahan jarak signifikan → `dist_score = 0.5`

$$\text{TTC\_score} = 0.50 \times 0.70 + 0.25 \times 0.0 + 0.25 \times 0.5 = 0.475$$

$$\text{TTC\_weighted} = 0.475 \times 1.2 = 0.57 \rightarrow \boxed{\textbf{PROBABLE — tidak langsung alert, diteruskan ke Formula G}}$$

Sistem tidak langsung panik karena `ar_score` yang rendah meredam skor keseluruhan. Pengguna tidak mendapat peringatan suara palsu.

---

## Hubungan dengan Kondisi Siang Hari

Ingat masalah ToF buta di siang hari? Berikut cara Formula I menambal celah tersebut secara otomatis:

```
Kondisi Dalam Ruangan / Malam:
  ToF aktif → dist_score berkontribusi penuh (bobot 0.25)
  Formula I bekerja di jarak > 4 meter

Kondisi Siang Hari / Outdoor:
  ToF buta mulai > 1 meter → dist_score = 0.5 (netral/diabaikan)
  Formula I tetap aktif dari jarak 1 meter hingga 20 meter
  hanya mengandalkan area_score + ar_score (total bobot 0.75)
  → Masih cukup sensitif untuk mendeteksi ancaman nyata
```

Dengan demikian, sistem **tidak perlu dikonfigurasi ulang** antara kondisi siang dan malam. Formula I akan otomatis beradaptasi tergantung apakah ToF sedang memberikan data valid atau tidak.
