# Formula Matematis — Sistem Navigasi Bantu Tunanetra (Revisi v8)

> **Dokumen ini adalah revisi dari v7** dengan penyempurnaan keamanan dan presisi deteksi:
> - **[DIPERBARUI v8]** Formula F: Arsitektur multi-objek dengan *danger scoring* tiga dimensi menggantikan seleksi tunggal `min(d)`; alert adaptif tiga tingkat (HIGH/MEDIUM/LOW)
> - **[DIPERBARUI v8]** Formula I: TTC *multi-dimensional* tiga fitur (area + aspect ratio + validasi jarak) menggantikan single-dimension area; penambahan bobot tipe objek YOLO
> - **[DIPERBARUI v8]** Formula J: Model empat zona vertikal menggantikan dua zona; klasifikasi lima tipe medan (STAIR_DOWN / STAIR_UP / HOLE / RAMP / SAFE); estimasi kedalaman dan arah spasial jam; confidence scoring per deteksi
> - **[DIPERBARUI v8]** Konstanta Sistem: Penambahan konstanta baru untuk danger scoring, TTC scoring, dan terrain classification
>
> Formula A, B, C, D, E, G, H, K tidak mengalami perubahan substansial dari v7.

---

## Daftar Isi

- [Konstanta Sistem](#konstanta-sistem)
- [A. Pra-Pemrosesan Orientasi MPU-6050 (ESP32-S3)](#a-pra-pemrosesan-orientasi-mpu-6050-dijalankan-di-esp32-s3)
- [B. Centroid Bounding Box](#b-formulasi-titik-tengah-objek-centroid-bounding-box)
- [C. Pemetaan Arah Jam](#c-formulasi-pemetaan-arah-jam)
- [D. Indeks Kolom ToF](#d-formulasi-pemetaan-indeks-kolom-tof-grid-binning)
- [E. Jarak Objek](#e-formulasi-jarak-objek-dari-sensor-tof)
- [F. Deteksi Sumber Gerakan](#f-formulasi-deteksi-sumber-gerakan)
- [G. Threshold Peringatan Adaptif](#g-formulasi-threshold-peringatan-adaptif)
- [H. Peringatan Objek Statis (One-Shot)](#h-formulasi-peringatan-objek-statis-one-shot-alert)
- [I. Delta Bounding Box (TTC)](#i-formulasi-delta-bounding-box-time-to-contact-proxy)
- [J. Deteksi Anomali Medan](#j-formulasi-deteksi-anomali-medan)
- [K. Mode Darurat / Fail-Safe (ESP32-S3)](#k-formulasi-mode-darurat-fail-safe)
- [Ringkasan & Aliran Data](#ringkasan--aliran-data-antar-formula)

---

## Konstanta Sistem

Nilai-nilai tetap yang ditentukan oleh spesifikasi hardware. Seluruh formula mengacu ke simbol-simbol ini — **bukan angka hardcoded** — agar sistem mudah diadaptasi jika hardware berganti.

| Simbol | Nilai | Satuan | Keterangan |
|---|---|---|---|
| $W_{cam}$ | 640 | px | Lebar resolusi kamera OV2640 (mode VGA) |
| $H_{cam}$ | 480 | px | Tinggi resolusi kamera OV2640 (mode VGA) |
| $N_{col}$ | 8 | — | Jumlah kolom sensor ToF VL53L5CX |
| $N_{row}$ | 8 | — | Jumlah baris sensor ToF VL53L5CX |
| $D_{left}$ | 80 | px | Dead zone kiri: area kamera tidak ter-cover ToF |
| $D_{right}$ | 80 | px | Dead zone kanan: area kamera tidak ter-cover ToF |
| $W_{tof}$ | 480 | px | Lebar coverage ToF dalam piksel: $W_{cam} - D_{left} - D_{right}$ |
| $R_{col}$ | 60 | px/kolom | Lebar satu kolom ToF dalam piksel: $W_{tof} / N_{col}$ |
| $W_{z}$ | 160 | px | Lebar satu zona arah jam: $W_{tof} / 3$ |
| $d_{w0}$ | 1000 | mm | Threshold jarak aman minimum (statis) |
| $d_{max}$ | 4000 | mm | Jangkauan maksimum sensor ToF |
| $d_{guard}$ | 2500 | mm | Guard deteksi medan: jika $\bar{z}_{mid} \ge d_{guard}$, terrain dianggap terbuka |
| $t_r$ | 2 | s | Waktu reaksi manusia (WHO Pedestrian Safety) |
| $a_{th}$ | 2.94 | m/s² | Threshold akselerasi gerakan ($\approx 0.3\,g$) |
| $\Delta A_{th}$ | 20 | % | ~~Threshold pertumbuhan bounding box~~ **[DEPRECATED v8]** Digantikan oleh $\Delta A_{norm} = 50\%$ di Formula I.v2 |
| $R_{th}$ | 0.8 | — | Threshold rasio anomali medan (atas) |
| $R_{th,lo}$ | 0.7 | — | Threshold rasio anomali medan (bawah hysteresis) |
| $d_{cont}$ | 800 | mm | Guard kontaminasi objek berdiri di Formula J: jika $\bar{z}_{mid} < d_{cont}$, zona tengah dianggap membaca badan objek bukan lantai |
| $\alpha_{mount}$ | 15.0 | ° | Sudut kemiringan mounting sensor ke bawah dari horizontal (asumsi tetap) |
| $\theta_{FoV}$ | 45.0 | ° | FoV vertikal total VL53L5CX (dari datasheet ST UM2884) |
| $\delta\theta$ | 5.625 | °/baris | Resolusi sudut per baris: $\theta_{FoV} / N_{row}$ |
| $f_{imu}$ | 100 | Hz | Frekuensi sampling MPU-6050 |
| $\mathbf{Q}$ | (7×7) | — | **[EKF]** Matriks noise proses: ketidakpercayaan pada giroskop; diagonal komponen quaternion ~$10^{-4}$, komponen bias ~$10^{-3}$ |
| $\mathbf{R}$ | (3×3) | — | **[EKF]** Matriks noise pengukuran: ketidakpercayaan pada akselerometer; $\mathbf{R} = \sigma_{acc}^2 \mathbf{I}_3$, $\sigma_{acc} \approx 0.05\ \text{m/s}^2$ |
| $\mathbf{P}_0$ | (7×7) | — | **[EKF]** Kovarians kesalahan awal; umumnya $\mathbf{P}_0 = \mathbf{I}_7$ (inisialisasi dengan ketidakpastian tinggi) |
| $\Delta t_{min}$ | 0.001 | s | Guard minimum interval waktu (1 ms) untuk mencegah division-by-zero |
| $\varepsilon_{noise}$ | 30 | mm | Noise floor sensor ToF VL53L5CX; digunakan sebagai threshold reset flag Formula H |
| $B_{th}$ | 40 | — | Threshold kecerahan kamera minimum (skala 0–255, dikalibrasi empiris) |
| $t_{wifi}$ | 5 | s | Toleransi durasi WiFi putus sebelum mode Offline aktif |
| $w_d$ | 0.35 | — | **[Formula F.v2]** Bobot sub-skor jarak pada danger score |
| $w_v$ | 0.45 | — | **[Formula F.v2]** Bobot sub-skor kecepatan pendekatan pada danger score |
| $w_s$ | 0.20 | — | **[Formula F.v2]** Bobot sub-skor tipe gerakan pada danger score |
| $D_{HIGH}$ | 0.70 | — | **[Formula F.v2]** Threshold bahaya tinggi — alert urgent, repeat setiap frame |
| $D_{MID}$ | 0.35 | — | **[Formula F.v2]** Threshold bahaya menengah — alert normal, debounce 2 s |
| $d_{crit}$ | 1000 | mm | **[Formula F.v2]** Jarak kritis (d_score = 1.0 jika $d_{obj} < d_{crit}$) |
| $v_{slow}$ | 500 | mm/s | **[Formula F.v2 / I.v2]** Batas bawah kecepatan pendekatan (score = 0.0) |
| $v_{fast}$ | 2000 | mm/s | **[Formula F.v2 / I.v2]** Batas atas kecepatan pendekatan (score = 1.0) |
| $w_A$ | 0.50 | — | **[Formula I.v2]** Bobot sub-skor pertumbuhan area bounding box |
| $w_{AR}$ | 0.25 | — | **[Formula I.v2]** Bobot sub-skor stabilitas aspect ratio |
| $w_{dist}$ | 0.25 | — | **[Formula I.v2]** Bobot sub-skor konsistensi jarak ToF |
| $\Delta\lambda_{th}$ | 20 | % | **[Formula I.v2]** Threshold perubahan aspect ratio; di atas ini dianggap rotasi/bukan pendekatan frontal |
| $\Delta A_{norm}$ | 50 | % | **[Formula I.v2]** Nilai normalisasi area score; pertumbuhan $\ge 50\%$ → score = 1.0 |
| $TTC_{HIGH}$ | 0.75 | — | **[Formula I.v2]** Threshold bahaya TTC tinggi |
| $TTC_{MID}$ | 0.40 | — | **[Formula I.v2]** Threshold bahaya TTC menengah |
| $\Delta z_{step}$ | 500 | mm | **[Formula J.v2]** Ambang gradien vertikal untuk mengklasifikasikan step/lubang |
| $\sigma_{col,th}$ | 200 | mm | **[Formula J.v2]** Threshold standar deviasi per kolom untuk deteksi anomali lokal |
| $edge_{th}$ | 300 | mm | **[Formula J.v2]** Threshold sharpness tepi vertikal (tajam = cliff/obstacle) |
| $C_{HIGH}$ | 0.80 | — | **[Formula J.v2]** Threshold confidence tinggi terrain — force alert |
| $C_{MID}$ | 0.60 | — | **[Formula J.v2]** Threshold confidence menengah terrain — feed ke Formula G |

> **Catatan:** $D_{left} = D_{right} = (W_{cam} - W_{tof})/2 = (640 - 480)/2 = 80\ \text{px}$. Ini bukan angka arbitrer — ini adalah konsekuensi langsung dari perbedaan FoV kamera (~66°) versus FoV sensor ToF (~45°).

---

## A. Pra-Pemrosesan Orientasi MPU-6050 — Extended Kalman Filter (Dijalankan di ESP32-S3)

> **Catatan Arsitektur:** Formula A **berjalan sepenuhnya di ESP32-S3**, bukan di smartphone. Ini keharusan teknis karena EKF membutuhkan $\Delta t_{imu}$ yang presisi dari hardware timer (±0.1 ms), sedangkan pengiriman data raw via WiFi WebSocket menghasilkan jitter ±5–50 ms yang merusak kovarians filter. ESP32-S3 mengirimkan output Formula A $(\theta, \phi, \omega_z, \|\mathbf{a}_{lin}\|)$ yang sudah siap pakai ke smartphone via paket WebSocket JSON. **Paket output A.6 tidak berubah dari v3 — hanya mesin filter di bawahnya yang diganti dari Complementary Filter ke EKF.**

**Referensi:** Embedded IMU processing, FreeRTOS Task IMU\_Filter

---

### A.1 Notasi

**A.0 — Guard interval waktu minimum (wajib sebelum semua komputasi K):**

$$\Delta t_{imu} := \max\!\left(\Delta t_{imu},\ \Delta t_{min}\right)$$

---

**A.EKF.1 — State Vector dan Matriks Parameter**

EKF melacak 7 variabel dalam satu vektor status — 4 komponen quaternion orientasi dan 3 komponen bias giroskop:

$$\mathbf{x}_t = \begin{bmatrix} q_w & q_x & q_y & q_z & b_{\omega x} & b_{\omega y} & b_{\omega z} \end{bmatrix}^T$$

Kepercayaan terhadap setiap sensor tidak lagi ditetapkan sebagai konstanta skalar (seperti $\alpha_{cf} = 0.96$ pada CF), melainkan oleh tiga matriks tuning:

| Matriks | Ukuran | Peran |
|---|---|---|
| $\mathbf{Q}$ | $7 \times 7$ | Noise proses: seberapa besar kita tidak percaya pada giroskop |
| $\mathbf{R}$ | $3 \times 3$ | Noise pengukuran: seberapa besar kita tidak percaya pada akselerometer |
| $\mathbf{P}_t$ | $7 \times 7$ | Kovarians kesalahan saat ini: keyakinan sistem, terus diperbarui setiap iterasi |

---

**A.EKF.2 — Fase Prediksi** *(menggantikan integrasi giroskop Euler)*

Fase ini menggunakan pembacaan giroskop $\boldsymbol{\omega}_t = [\omega_x, \omega_y, \omega_z]^T$ yang sudah dikoreksi bias untuk menebak orientasi berikutnya. Misalkan $\boldsymbol{\omega}_{corr} = \boldsymbol{\omega}_t - \mathbf{b}_\omega^{(t-1)}$, maka rotasi quaternion satu langkah:

$$\boxed{\hat{\mathbf{x}}_{t|t-1} = f\!\left(\hat{\mathbf{x}}_{t-1|t-1},\ \boldsymbol{\omega}_t,\ \Delta t_{imu}\right)}$$

dengan fungsi $f$ yang memutar quaternion via perkalian quaternion:

$$\mathbf{q}_{t|t-1} = \mathbf{q}_{t-1} \otimes \begin{bmatrix} \cos\!\frac{\|\boldsymbol{\omega}_{corr}\|\Delta t}{2} \\ \dfrac{\boldsymbol{\omega}_{corr}}{\|\boldsymbol{\omega}_{corr}\|}\sin\!\frac{\|\boldsymbol{\omega}_{corr}\|\Delta t}{2} \end{bmatrix}, \quad \mathbf{b}_\omega^{pred} = \mathbf{b}_\omega^{(t-1)}$$

**Jacobian $\mathbf{F}_t$ (7×7) — turunan $f$ terhadap state vector:**

$$\mathbf{F}_t = \frac{\partial f}{\partial \mathbf{x}}\Bigg|_{\hat{\mathbf{x}}_{t-1}} = \begin{bmatrix} \mathbf{\Xi}(\boldsymbol{\omega}_{corr},\,\Delta t) & -\frac{\Delta t}{2}\mathbf{Q}_{left}(\mathbf{q}_{t-1})\,\mathbf{J}_\omega \\ \mathbf{0}_{3\times4} & \mathbf{I}_{3\times3} \end{bmatrix}$$

di mana:
- $\mathbf{\Xi}(\boldsymbol{\omega},\Delta t) \in \mathbb{R}^{4\times4}$: matriks rotasi quaternion orde-1, elemen ke-$(i,j)$ adalah $\partial q_i^{pred}/\partial q_j^{prev}$
- $\mathbf{Q}_{left}(\mathbf{q})$: matriks perkalian quaternion kiri (left-multiplication matrix) berukuran $4\times4$
- $\mathbf{J}_\omega = \mathbf{I}_{3\times3}$: Jacobian bias terhadap laju koreksi giroskop
- Blok kanan atas $-\frac{\Delta t}{2}\mathbf{Q}_{left}\mathbf{J}_\omega \in \mathbb{R}^{4\times3}$: menunjukkan sensitivitas quaternion terhadap perubahan bias

Kovarians (ketidakpastian) bertambah seiring waktu:

$$\mathbf{P}_{t|t-1} = \mathbf{F}_t\, \mathbf{P}_{t-1|t-1}\, \mathbf{F}_t^T + \mathbf{Q}$$

---

**A.EKF.3 — Fase Koreksi** *(menggantikan fusi akselerometer CF)*

Fase ini menggunakan data akselerometer raw $\mathbf{a}_t = [a_x, a_y, a_z]^T$ untuk mengoreksi tebakan dari fase prediksi.

Fungsi pengukuran $h$ mengekstrak vektor gravitasi yang diprediksi dari quaternion:

$$h(\hat{\mathbf{x}}_{t|t-1}) = \mathbf{g}_q = g \cdot \begin{bmatrix} 2(q_x q_z - q_w q_y) \\ 2(q_w q_x + q_y q_z) \\ q_w^2 - q_x^2 - q_y^2 + q_z^2 \end{bmatrix}$$

Inovasi (selisih antara prediksi gravitasi dan akselerometer nyata):

$$\mathbf{y}_t = \mathbf{a}_t - h\!\left(\hat{\mathbf{x}}_{t|t-1}\right)$$

**Jacobian $\mathbf{H}_t$ (3×7) — turunan $h$ terhadap state vector:**

$$\mathbf{H}_t = \frac{\partial h}{\partial \mathbf{x}}\Bigg|_{\hat{\mathbf{x}}_{t|t-1}} = g \cdot \begin{bmatrix} -2q_y & 2q_z & -2q_w & 2q_x & 0 & 0 & 0 \\ 2q_x & 2q_w & 2q_z & 2q_y & 0 & 0 & 0 \\ 2q_w & -2q_x & -2q_y & 2q_z & 0 & 0 & 0 \end{bmatrix}$$

Kolom 5–7 bernilai nol karena fungsi pengukuran $h$ tidak bergantung langsung pada bias giroskop $\mathbf{b}_\omega$ — bias hanya mempengaruhi sistem melalui fase prediksi.

Kalman Gain — pengganti dinamis dari $\alpha_{cf}$, berubah setiap iterasi berdasarkan noise aktual:

$$\mathbf{S}_t = \mathbf{H}_t\, \mathbf{P}_{t|t-1}\, \mathbf{H}_t^T + \mathbf{R}$$

$$\boxed{\mathbf{K}_t = \mathbf{P}_{t|t-1}\, \mathbf{H}_t^T\, \mathbf{S}_t^{-1}}$$

Pembaruan status final (fusi selesai):

$$\boxed{\hat{\mathbf{x}}_{t|t} = \hat{\mathbf{x}}_{t|t-1} + \mathbf{K}_t\, \mathbf{y}_t}$$

$$\mathbf{P}_{t|t} = \left(\mathbf{I} - \mathbf{K}_t\, \mathbf{H}_t\right) \mathbf{P}_{t|t-1}$$

**Normalisasi quaternion (wajib setiap iterasi):**

Akumulasi error floating-point dapat membuat $\|\mathbf{q}\| \neq 1$. Setelah pembaruan, normalisasi wajib dilakukan:

$$\mathbf{q}_{t|t} := \frac{\mathbf{q}_{t|t}}{\|\mathbf{q}_{t|t}\|}$$

---

**A.EKF.4 — Ekstraksi Output dari State Vector**

Setelah satu siklus EKF selesai, $\hat{\mathbf{x}}_{t|t} = [q_w, q_x, q_y, q_z, b_{\omega x}, b_{\omega y}, b_{\omega z}]^T$ dibedah untuk menghasilkan format paket yang dibutuhkan formula hilir.

Ekstraksi pitch $\theta$ dan roll $\phi$ dari quaternion:

$$\boxed{\theta^{(t)} = \arcsin\!\left(2(q_w q_y - q_z q_x)\right) \cdot \frac{180°}{\pi}}$$

$$\boxed{\phi^{(t)} = \operatorname{atan2}\!\left(2(q_w q_x + q_y q_z),\ 1 - 2(q_x^2 + q_y^2)\right) \cdot \frac{180°}{\pi}}$$

Laju rotasi yaw terkalibrasi bias (EKF secara otomatis mempelajari $b_{\omega z}$):

$$\boxed{\omega_z^{corr} = g_z - b_{\omega z}^{(t)}}$$

**[DIPERBARUI v6] — Laju rotasi pitch dan roll terkalibrasi bias:**

EKF juga mempelajari $b_{\omega x}$ dan $b_{\omega y}$ di dalam state vector yang sama. Nilai ini tersedia tanpa komputasi tambahan dan **wajib disertakan** dalam paket karena Formula G membutuhkan $\omega_x^{corr}$ untuk menghitung kecepatan semu gerak kepala:

$$\boxed{\omega_x^{corr} = \omega_x - b_{\omega x}^{(t)}}$$

$$\boxed{\omega_y^{corr} = \omega_y - b_{\omega y}^{(t)}}$$

Ketiga nilai ini lebih akurat dari raw giroskop karena bias suhu sudah dieliminasi oleh EKF secara adaptif.

Magnitude akselerasi linear murni (gravitasi dikurangi secara tepat via quaternion, lebih akurat dari aproksimasi skalar CF):

$$\mathbf{g}_q = \begin{bmatrix} 2(q_x q_z - q_w q_y) \\ 2(q_w q_x + q_y q_z) \\ q_w^2 - q_x^2 - q_y^2 + q_z^2 \end{bmatrix} \cdot 9.81\ \text{m/s}^2$$

$$\boxed{\|\mathbf{a}_{lin}\| = \left\|\mathbf{a}_t - \mathbf{g}_q\right\|}$$

**A.EKF.5 — Pra-komputasi $v_{head\_base}$ (BARU: Arsitektur v8.1):**

Sebagai langkah terakhir sebelum pengiriman WebSocket, ESP32 menghitung **faktor pengali IMU-murni** dari $v_{head}$ Formula G.1. Faktor ini hanya bergantung pada data IMU (EKF) — tidak memerlukan konteks YOLO:

$$k_{damp} = \begin{cases} 0.5, & \left|\omega_x^{corr}\right| > \omega_{x,lim} \\ 1.0, & \left|\omega_x^{corr}\right| \le \omega_{x,lim} \end{cases}$$

$$\boxed{v_{head\_base} = k_{damp} \cdot \left|\omega_x^{corr}\right| \cdot \cos\!\left(\frac{\theta \cdot \pi}{180}\right) \cdot \frac{\pi}{180} \quad \text{(satuan: rad/s, skalar per-frame)}}$$

Satuan $v_{head\_base}$ adalah **rad/s** — faktor pengali murni. Mobile menyelesaikannya dengan **satu perkalian skalar** per objek:

$$v_{head}^{(i)} = v_{head\_base} \times d_{obj}^{(i)} \quad \text{(Mobile, Formula G.1b)}$$

Dekomposisi ini valid karena $k_{damp}$, $\cos(\theta)$, dan konversi $\pi/180$ **tidak bergantung pada tracking ID YOLO** — nilainya identik untuk semua objek dalam satu frame. Dengan mengirimkan $v_{head\_base}$ (4 byte float) di paket WebSocket, Mobile tidak perlu menghitung `cos()`, `abs()`, branching `k_damp`, dan konversi unit untuk setiap objek.

**A.6 — Paket output yang dikirim ke smartphone via WebSocket** *[DIPERBARUI v8.1: diperluas dari 7 menjadi 9 field]*

$$\boxed{\mathbf{p}_{imu} = \left(\theta^{(t)},\ \phi^{(t)},\ \omega_x^{corr},\ \omega_y^{corr},\ \omega_z^{corr},\ \|\mathbf{a}_{lin}\|,\ ts_{esp},\ v_{head\_base},\ \text{is\_converged}\right)}$$

Dua field baru ditambahkan:
- **$v_{head\_base}$** (float, 4 byte): faktor pengali IMU-murni dari Formula G.1 — Mobile hanya perlu satu perkalian $\times d_{obj}^{(i)}$ per objek
- **$\text{is\_converged}$** (uint8, 1 byte): flag konvergensi EKF dari G.EKF — Mobile tidak perlu mengelola counter `t_frame` sendiri

**Estimasi overhead bandwidth total:** $(2+1) \text{ field} \times 5 \text{ byte} \times 30 \text{ Hz} = 450 \text{ byte/detik}$ — masih tidak signifikan.

**Domain:**
- Input: $a_x, a_y, a_z \in \mathbb{R}$ m/s² (raw akselerometer); $\omega_x, \omega_y, g_z \in \mathbb{R}$ °/s (raw giroskop)
- Output: $\theta^{(t)},\ \phi^{(t)} \in [-90°, +90°]$; $\omega_x^{corr}, \omega_y^{corr}, \omega_z^{corr} \in \mathbb{R}$ °/s; $\|\mathbf{a}_{lin}\| \in [0, \infty)$ m/s²; $v_{head\_base} \in [0, \infty)$ rad/s; $\text{is\_converged} \in \{0,1\}$

### A.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $a_x, a_y, a_z$ | Input | $\mathbb{R}$ | m/s² | Komponen akselerasi raw dari MPU-6050 |
| $\boldsymbol{\omega}_t = [\omega_x, \omega_y, \omega_z]^T$ | Input | $\mathbb{R}^3$ | °/s | Laju rotasi raw dari giroskop MPU-6050 |
| $g_z$ | Input | $\mathbb{R}$ | °/s | Komponen yaw rate dari giroskop |
| $\Delta t_{imu}$ | Input | $[\Delta t_{min}, \infty)$ | s | Interval antar sample IMU (dari hardware timer ESP32) |
| $\mathbf{x}_t$ | State | $\mathbb{R}^7$ | — | State vector EKF: $[q_w, q_x, q_y, q_z, b_{\omega x}, b_{\omega y}, b_{\omega z}]^T$ |
| $\mathbf{Q}$ | Konstanta | $\mathbb{R}^{7\times7}$ | — | Matriks noise proses (tuning empiris) |
| $\mathbf{R}$ | Konstanta | $\mathbb{R}^{3\times3}$ | — | Matriks noise pengukuran (tuning empiris) |
| $\mathbf{P}_t$ | State | $\mathbb{R}^{7\times7}$ | — | Matriks kovarians kesalahan (diperbarui setiap iterasi) |
| $\mathbf{K}_t$ | Intermediat | $\mathbb{R}^{7\times3}$ | — | Kalman Gain: bobot dinamis fusi giroskop–akselerometer |
| $\mathbf{y}_t$ | Intermediat | $\mathbb{R}^3$ | m/s² | Inovasi: selisih akselerometer nyata vs prediksi gravitasi |
| $\mathbf{g}_q$ | Intermediat | $\mathbb{R}^3$ | m/s² | Vektor gravitasi diprediksi dari quaternion EKF |
| $b_{\omega z}^{(t)}$ | State terderivasi | $\mathbb{R}$ | °/s | Bias yaw giroskop yang dipelajari EKF |
| $\theta^{(t)}$ | **Output** | $[-90°, +90°]$ | ° | **Pitch kepala pengguna (+ = menunduk)** |
| $\phi^{(t)}$ | **Output** | $[-90°, +90°]$ | ° | **Roll kepala pengguna (+ = miring kanan)** |
| $\omega_x^{corr}$ | **Output** | $\mathbb{R}$ | °/s | **Pitch rate terkalibrasi bias** — dibutuhkan Formula G.1 untuk $v_{head\_base}$ |
| $\omega_y^{corr}$ | **Output** | $\mathbb{R}$ | °/s | **Roll rate terkalibrasi bias** — tersedia untuk post-MVP |
| $\omega_z^{corr}$ | **Output** | $\mathbb{R}$ | °/s | **Yaw rate terkalibrasi bias** — dibutuhkan Formula I, K |
| $\|\mathbf{a}_{lin}\|$ | **Output** | $[0, \infty)$ | m/s² | **Magnitude akselerasi linear murni (gravitasi dikurangi via quaternion)** |
| $ts_{esp}$ | **Output** | $[0, \infty)$ | ms | **Timestamp ESP32 — digunakan Formula G untuk menghitung $\Delta t$ yang konsisten** |
| $v_{head\_base}$ | **Output** | $[0, \infty)$ | rad/s | **Faktor pengali IMU-murni untuk $v_{head}$**: $k_{damp}\cdot|\omega_x^{corr}|\cdot\cos(\theta)\cdot\pi/180$ — dihitung sekali di ESP32, dipakai Mobile per-objek (A.EKF.5)** |
| $\text{is\_converged}$ | **Output** | $\{0,1\}$ | — | **Flag konvergensi EKF** — 0 selama warmup 5 detik, 1 setelah stabil; digunakan Mobile untuk guard Formula G.EKF |

---

### A.3 Cara Kerja Detail

MPU-6050 menghasilkan dua jenis data pada setiap siklus:
- **Akselerometer:** mengukur percepatan termasuk gravitasi — akurat untuk sudut statis, tetapi sangat noisy saat bergerak
- **Giroskop:** mengukur laju rotasi — sangat halus dan responsif, tetapi terakumulasi drift seiring waktu dan memiliki bias offset yang berubah dengan suhu

**Perbedaan mendasar EKF vs Complementary Filter (CF):**

CF menetapkan kepercayaan secara kaku: *"percayai giroskop 96% dan akselerometer 4% sepanjang waktu."* Ini efisien namun buta terhadap kondisi aktual — saat pengguna berlari, akselerometer penuh dengan noise langkah kaki, tetapi CF tetap memberikan 4% kepercayaan padanya.

EKF memecahkan masalah ini dengan **Kalman Gain $\mathbf{K}_t$ yang berubah setiap iterasi**. Ketika inovasi $\mathbf{y}_t$ melonjak tajam (akselerometer penuh noise karena pengguna berlari), $\mathbf{K}_t$ secara otomatis mengecil mendekati $0$ — sistem sepenuhnya mengandalkan giroskop. Saat pengguna berdiri diam dan akselerometer stabil, $\mathbf{K}_t$ membesar untuk melakukan koreksi silang. Inilah yang membuat EKF setara dengan perilaku sensor fusion *silicon-embedded* milik BNO055.

**EKF juga mempelajari bias giroskop $b_\omega$:**

Giroskop MPU-6050 memiliki offset bias yang tidak nol dan berubah dengan suhu. CF tidak bisa mengoreksi ini — jika giroskop punya bias 0.1°/s, dalam 10 menit postur kepala akan meleset 60°. EKF memasukkan $[b_{\omega x}, b_{\omega y}, b_{\omega z}]$ ke dalam state vector dan memperbaruinya setiap iterasi, sehingga bias terkalibrasi secara otomatis tanpa prosedur kalibrasi manual.

**Perhitungan $\|\mathbf{a}_{lin}\|$ yang lebih akurat:**

CF menggunakan aproksimasi skalar: $\|\mathbf{a}_{lin}\| = |\|\mathbf{a}_t\| - g|$. Ini tidak akurat saat sensor miring — jika kacamata miring 30°, komponen gravitasi di setiap sumbu berbeda dan tidak bisa dieliminasi hanya dengan mengurangi $g = 9.81$ dari norma total. EKF menghitung $\mathbf{g}_q$ — vektor gravitasi yang tepat berdasarkan orientasi quaternion saat ini — lalu mengurangkannya dari $\mathbf{a}_t$ secara vektorial.

**Konvensi tanda $\theta$ (pitch):** sama dengan v3.
- $\theta = 0°$: kepala di posisi standar (sensor mengarah $-\alpha_{mount} = -15°$ dari horizontal)
- $\theta > 0°$: kepala menunduk
- $\theta < 0°$: kepala mendongak

**Mengapa $\omega_z$ tidak diintegrasikan?** sama dengan v3 — yaw tidak memiliki referensi gravitasi. Namun di EKF, $\omega_z$ kini sudah dikurangi bias $b_{\omega z}^{(t)}$ yang dipelajari secara otomatis, sehingga lebih bersih dari nilai raw $g_z$.

---

**Prosedur Tuning Matriks EKF — Panduan Implementasi:**

Tuning $\mathbf{Q}$, $\mathbf{R}$, dan $\mathbf{P}_0$ adalah langkah kritis yang menentukan kualitas keluaran EKF. Berikut titik awal yang direkomendasikan berdasarkan spesifikasi MPU-6050 (InvenSense, Rev 3.4):

*Matriks Noise Pengukuran $\mathbf{R}$ — dari datasheet akselerometer:*

$$\mathbf{R} = \sigma_{acc}^2 \cdot \mathbf{I}_3, \quad \sigma_{acc} = 0.05\ \text{m/s}^2$$

$$\mathbf{R} = \begin{bmatrix} 0.0025 & 0 & 0 \\ 0 & 0.0025 & 0 \\ 0 & 0 & 0.0025 \end{bmatrix}$$

Naikkan $\mathbf{R}$ jika estimasi terlalu bergejolak saat berjalan (akselerometer terlalu dipercaya). Turunkan jika orientasi terasa lambat saat kepala diam.

*Matriks Noise Proses $\mathbf{Q}$ — mencerminkan ketidakpercayaan pada giroskop:*

$$\mathbf{Q} = \text{diag}\!\left(\sigma_q^2,\, \sigma_q^2,\, \sigma_q^2,\, \sigma_q^2,\, \sigma_b^2,\, \sigma_b^2,\, \sigma_b^2\right)$$

$$\sigma_q = 1\times10^{-4}\ \text{(komponen quaternion)}, \quad \sigma_b = 1\times10^{-3}\ \text{(bias giroskop, °/s per sample)}$$

Naikkan $\sigma_b$ jika suhu ruangan berfluktuasi (bias giroskop lebih cepat berubah). Naikkan $\sigma_q$ jika estimasi pitch terasa tertinggal saat kepala digerakkan cepat.

*Kovarians Awal $\mathbf{P}_0$ — kondisi ketidakpastian di frame pertama:*

$$\mathbf{P}_0 = \mathbf{I}_7$$

Setelah $\sim$50 iterasi pertama ($= 0.5$ detik pada 100 Hz), EKF biasanya sudah konvergen ke nilai $\mathbf{P}$ yang stabil.

*Inisialisasi State Vector $\hat{\mathbf{x}}_0$ dari akselerometer (sebelum iterasi pertama):*

$$\theta_0 = \arctan\!\left(\frac{a_y}{\sqrt{a_x^2 + a_z^2}}\right), \quad \phi_0 = \arctan\!\left(\frac{-a_x}{a_z}\right)$$

$$\mathbf{q}_0 = \text{Euler2Quat}(\theta_0,\, \phi_0,\, 0°), \quad \mathbf{b}_{\omega,0} = \mathbf{0}_3$$

$$\hat{\mathbf{x}}_0 = \begin{bmatrix} \mathbf{q}_0 \\ \mathbf{0}_3 \end{bmatrix}$$

Yaw diinisialisasi ke $0°$ karena tidak ada referensi heading absolut tanpa magnetometer. Bias diinisialisasi ke $\mathbf{0}$ karena EKF akan mempelajarinya dalam $\sim$5--10 detik pertama operasi normal.

---

### A.4 Asal Usul Formula

**Konsep:** Extended Kalman Filter untuk estimasi orientasi dari IMU 6DOF.

**Penemu/Peneliti:**

Kalman Filter dasar (linear) ditemukan oleh **Rudolf E. Kálmán** dalam makalah fundamental *"A New Approach to Linear Filtering and Prediction Problems"* (ASME Journal of Basic Engineering, 1960). Kálmán menunjukkan bahwa untuk sistem linear dengan noise Gaussian, filter ini menghasilkan estimasi optimal MMSE. Persamaan update Kálmán asli (linear, 1D):

$$\hat{x}_{k|k} = \hat{x}_{k|k-1} + K_k\!\left(z_k - H\hat{x}_{k|k-1}\right), \quad K_k = \frac{P_{k|k-1}H^T}{HP_{k|k-1}H^T + R}$$

EKF yang digunakan di Formula A adalah perluasan non-linear dari persamaan di atas, di mana $H$ digantikan oleh Jacobian $\mathbf{H}_t$ dan fungsi $f$, $h$ bersifat non-linear.

**Extended Kalman Filter (EKF)** adalah perluasan ke sistem non-linear melalui linearisasi Jacobian, dikembangkan oleh **Stanley F. Schmidt** di NASA Ames Research Center sekitar 1960–1966 untuk keperluan navigasi inersial misi Apollo. Aplikasi pertama EKF yang terdokumentasi adalah sistem navigasi kapsul Apollo 11 yang membawa Neil Armstrong mendarat di bulan pada 1969.

Penerapan EKF untuk estimasi orientasi IMU 6DOF secara spesifik dikembangkan lebih lanjut oleh:
- **Sabatini, A.M.** (2006). *Quaternion-based extended Kalman filter for determining orientation by inertial and magnetic sensing.* IEEE Transactions on Biomedical Engineering. — Referensi kunci untuk formulasi quaternion + bias estimation yang digunakan di sistem ini.
- **Madgwick, S.O.H., Harrison, A.J.L., & Vaidyanathan, R.** (2011). *Estimation of IMU and MARG orientation using a gradient descent algorithm.* IEEE ICRA. — Benchmark perbandingan EKF vs filter lainnya untuk IMU wearable.

Penggunaan **quaternion** (bilangan hiperkompleks $q = q_w + q_x\mathbf{i} + q_y\mathbf{j} + q_z\mathbf{k}$) sebagai representasi orientasi 3D menghindari *gimbal lock* yang terjadi pada representasi sudut Euler, dan pertama kali diusulkan untuk komputasi orientasi oleh **Euler** (1775) dan diformalkan oleh **William Rowan Hamilton** (1843) dalam karyanya *"On Quaternions"*.

Referensi implementasi untuk ESP32:
- InvenSense. (2013). *MPU-6000 and MPU-6050 Product Specification Rev 3.4.*
- Trawny, N. & Roumeliotis, S.I. (2005). *Indirect Kalman Filter for 3D Attitude Estimation.* University of Minnesota Technical Report. — Dokumen teknis yang banyak dijadikan referensi untuk implementasi EKF quaternion di embedded system.

---

### A.5 Aliran Variabel

```
[MPU-6050 via I2C @ 100Hz]
    │
    ├──→ a_x, a_y, a_z (m/s²)   ← akselerometer raw
    └──→ ω_x, ω_y, g_z (°/s)    ← giroskop raw
              │
    [A.0: Guard Δt = max(Δt, 0.001s)]
              │
    ┌─────────┴──────────────────────────────────┐
    ▼                                            ▼
[A.EKF.2: FASE PREDIKSI]              [Simpan sebagai pengukuran]
x̂_{t|t-1} = f(x̂_{t-1}, ω_t, Δt)     a_t = [ax, ay, az]
P_{t|t-1} = F_t P_{t-1} F_t^T + Q
(quaternion dirotasi, kovarians naik)
    │
    ▼
[A.EKF.3: FASE KOREKSI]
y_t = a_t - h(x̂_{t|t-1})       ← inovasi
K_t = P_{t|t-1} H_t^T S_t^{-1} ← Kalman Gain (dinamis)
x̂_{t|t} = x̂_{t|t-1} + K_t y_t ← fusi final
P_{t|t} = (I - K_t H_t) P_{t|t-1}
    │
    ▼
[A.EKF.4: EKSTRAKSI OUTPUT dari x̂_{t|t} = [qw,qx,qy,qz, bx,by,bz]]
    │
    ├──→ θ^(t) = arcsin(2(q_w q_y - q_z q_x)) × 180/π
    ├──→ φ^(t) = atan2(2(q_w q_x + q_y q_z), 1-2(qx²+qy²)) × 180/π
    ├──→ ω_x^corr = ω_x - b_{ωx}^(t)  ← pitch rate terkalibrasi  [NEWv6]
    ├──→ ω_y^corr = ω_y - b_{ωy}^(t)  ← roll rate terkalibrasi   [NEWv6]
    ├──→ ω_z^corr = g_z - b_{ωz}^(t)  ← yaw rate terkalibrasi
    └──→ ‖a_lin‖ = ‖a_t - g_q‖        ← gravitasi dikurangi via quaternion
              │
    [A.EKF.5: PRA-KOMPUTASI v_head_base]         ← BARU v8.1
    k_damp = 0.5 if |ω_x^corr| > 5°/s else 1.0
    v_head_base = k_damp × |ω_x^corr| × cos(θ×π/180) × π/180
    is_converged = 𝟏[t_frame ≥ 150] OR 𝟏[‖P_t‖_F < 0.10]
              │
    [A.6: Paket WebSocket JSON — 9 field]         ← BARU v8.1
    p_imu = (θ, φ, ω_x^corr, ω_y^corr, ω_z^corr, ‖a_lin‖, ts_esp,
             v_head_base, is_converged)
              │
              └──→ [WiFi WebSocket ke Smartphone]
                        │
             ┌──────────┼──────────┬──────────┐
             ▼          ▼          ▼          ▼
        [Formula E] [Formula G] [Formula J] [Formula F]
         θ→R_obj   v_head_base   θ→R_low    ‖a_lin‖→src
                   is_converged  θ→R_mid
                   ts_esp→Δt
                   →v_head^(i)=v_head_base×d_obj^(i)
```

---

## B. Formulasi Titik Tengah Objek (Centroid Bounding Box)

**Referensi:** Flowchart 3a, SD-2

---

### B.1 Notasi yang Diperbaiki

$$\boxed{x_c = \frac{x_{min} + x_{max}}{2}}$$

**Domain input:** $x_{min},\ x_{max} \in [0,\ W_{cam}-1] = [0,\ 639]$ px, dengan $x_{max} > x_{min}$

**Range output:** $x_c \in (0,\ 639)$ px

### B.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $x_{min}$ | Input | $[0, 639]$ | px | Batas kiri bounding box dari YOLOv11 |
| $x_{max}$ | Input | $[0, 639]$ | px | Batas kanan bounding box dari YOLOv11 |
| $x_c$ | **Output** | $(0, 639)$ | px | **Posisi horizontal titik tengah objek** |

---

### B.3 Cara Kerja Detail

YOLOv11 menghasilkan bounding box dalam format `[x_min, y_min, x_max, y_max]` — empat koordinat sudut kotak pembatas. Format ini adalah representasi **minimal** sebuah persegi panjang di ruang 2D.

Formula ini mengambil **rata-rata aritmatika** dari batas kiri dan kanan untuk mendapatkan posisi horizontal tengah. Secara geometri, ini adalah proyeksi titik centroid objek ke sumbu-X:

$$x_c = x_{min} + \frac{x_{max} - x_{min}}{2} = \frac{x_{min} + x_{max}}{2}$$

Kedua bentuk ekuivalen, tetapi bentuk $\frac{x_{min} + x_{max}}{2}$ lebih ringkas dan merupakan bentuk standar di literatur computer vision.

**Mengapa hanya sumbu X?** Sistem ini hanya membutuhkan posisi *horizontal* objek karena:
1. Arah jam (kiri-depan-kanan) ditentukan dari posisi horizontal
2. Kolom sensor ToF dipetakan dari posisi horizontal
3. Informasi vertikal ($y_c$) tidak digunakan di pipeline ini karena sensor ToF sudah menangani dimensi vertikal melalui pembagian baris sensor

---

### B.4 Asal Usul Formula

**Konsep:** Rata-rata aritmatika dan centroid geometri.

**Penemu:** Konsep centroid (titik pusat massa) pertama kali diformulasikan oleh **Archimedes** (~287–212 SM) dalam karyanya *On the Equilibrium of Planes*. Archimedes membuktikan bahwa pusat gravitasi segitiga berada pada titik perpotongan median-mediannya.

Dalam konteks **pengolahan citra digital**, penggunaan centroid bounding box sebagai representasi posisi objek distandarisasi oleh **Azriel Rosenfeld dan Avinash Kak** dalam buku *Digital Picture Processing* (1982, Academic Press). Mereka mendefinisikan centroid region sebagai:

$$\bar{x} = \frac{1}{N}\sum_{i=1}^{N} x_i, \quad \bar{y} = \frac{1}{N}\sum_{i=1}^{N} y_i$$

yang untuk kasus bounding box persegi panjang (di mana semua piksel terdistribusi seragam) menyederhanakan menjadi:

$$\bar{x} = \frac{x_{min} + x_{max}}{2}, \quad \bar{y} = \frac{y_{min} + y_{max}}{2}$$

Formula B menggunakan $\bar{x}$ saja karena sistem hanya memerlukan posisi horizontal.

**Penerapan dalam sistem ini:** Formula ini tidak dimodifikasi dari bentuk aslinya — ini adalah aplikasi langsung dari definisi centroid 1D pada output YOLO.

---

### B.5 Aliran Variabel

```
[YOLOv11 Inference]
    │
    ├─── x_min (px) ─────────────────────────┐
    │                                        ├──→ [Formula B] ──→ x_c (px)
    └─── x_max (px) ─────────────────────────┘                      │
                                                                     │
                                             ┌───────────────────────┘
                                             │
                                             ├──→ [Formula C] → Arah Jam
                                             ├──→ [Formula D] → j (kolom ToF)
                                             └──→ [Formula I] → (bersama y_min, y_max)
```

---

## C. Formulasi Pemetaan Arah Jam

**Referensi:** Flowchart 3a, SD-2

---

### C.1 Notasi yang Diperbaiki

**Langkah 1 — Definisi batas zona dari konstanta sistem:**

$$b_0 = D_{left}, \quad b_1 = D_{left} + W_z, \quad b_2 = D_{left} + 2W_z, \quad b_3 = D_{left} + 3W_z$$

**Substitusi nilai:**

$$b_0 = 80,\quad b_1 = 240,\quad b_2 = 400,\quad b_3 = 560$$

**Langkah 2 — Fungsi pemetaan:**

$$\boxed{h(x_c) = \begin{cases}
10, & x_c < b_0 \\
11, & b_0 \le x_c < b_1 \\
12, & b_1 \le x_c < b_2 \\
1,  & b_2 \le x_c < b_3 \\
2,  & x_c \ge b_3
\end{cases}}$$

**Domain input:** $x_c \in [0,\ 639]$ px

**Range output:** $h(x_c) \in \{10,\ 11,\ 12,\ 1,\ 2\}$

### C.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $x_c$ | Input | $[0, 639]$ | px | Posisi horizontal objek dari Formula B |
| $b_0, b_1, b_2, b_3$ | Konstanta terderivasi | — | px | Batas-batas zona, dihitung dari $D_{left}$ dan $W_z$ |
| $h(x_c)$ | **Output** | $\{10,11,12,1,2\}$ | — | **Posisi arah jam objek relatif terhadap user** |

---

### C.3 Cara Kerja Detail

Frame kamera 640 px dibagi menjadi **5 zona** berdasarkan arsitektur hardware:

```
Lebar Kamera: 640 piksel
┌──────────┬──────────────┬──────────────┬──────────────┬──────────┐
│  80 px   │   160 px     │   160 px     │   160 px     │  80 px   │
│  JAM 10  │   JAM 11     │   JAM 12     │   JAM 1      │  JAM 2   │
│  [0, 79] │ [80, 239]    │ [240, 399]   │ [400, 559]   │[560, 639]│
│ ❌ no ToF │  ✅ ToF       │  ✅ ToF       │  ✅ ToF       │ ❌ no ToF │
└──────────┴──────────────┴──────────────┴──────────────┴──────────┘
      b_0=80        b_1=240       b_2=400       b_3=560
```

**Zona Jam 10 dan 2** (lebar $D_{left} = D_{right} = 80$ px) adalah *dead zone* — kamera YOLO masih dapat mendeteksi objek di sini, tetapi sensor ToF tidak menjangkau area ini. Jika objek terdeteksi di zona ini, sistem hanya bisa memberikan informasi arah tanpa data jarak yang presisi.

**Zona Jam 11, 12, 1** (masing-masing $W_z = 160$ px) adalah zona aktif penuh. Pembagian rata menjadi tiga mencerminkan tiga sektor sudut pandang frontal manusia: kiri-depan, tepat-depan, kanan-depan.

**Kenapa 5 zona, bukan 3?** Dua zona tambahan (Jam 10 dan 2) memberikan informasi periferal — objek yang terlihat di tepi frame tetap perlu diperingatkan kepada tunanetra, meskipun tanpa jarak presisi.

---

### C.4 Asal Usul Formula

**Konsep:** Clock direction system dalam Orientation & Mobility (O&M) untuk tunanetra.

**Penemu/Pengembang:** Sistem arah jam sebagai alat navigasi tunanetra distandarisasi oleh **Richard E. Hoover** (1915–1986), seorang dokter dan veteran tentara AS yang mengembangkan *long cane technique* pasca Perang Dunia II. Hoover memperkenalkan terminologi arah jam dalam pelatihan mobilitas tunanetra di Valley Forge General Hospital sekitar tahun 1944–1950.

Metode ini kemudian dikodifikasikan dalam kurikulum O&M oleh **California State University, Los Angeles** dan **Perkins School for the Blind**, dan saat ini merupakan standar global dalam pendidikan mobilitas tunanetra yang diajarkan oleh *Certified Orientation and Mobility Specialists* (COMS).

Adaptasi ke sistem digital (konversi koordinat piksel ke notasi jam) merupakan inovasi yang berkembang bersamaan dengan asisten navigasi tunanetra berbasis kamera, seperti yang dilaporkan dalam:
- Arditi, A. & Tian, Y. (2012). *User interface preferences in the design of a camera-based navigation and wayfinding aid.* Journal of Visual Impairment & Blindness.
- Zientara, P.A. et al. (2017). *Electronic locomotion guidance for the visually impaired.* IEEE Transactions on Neural Systems and Rehabilitation Engineering.

**Penerapan dalam sistem ini:** Batas zona diderivasi secara eksplisit dari konstanta hardware ($D_{left}$, $W_z$) — bukan angka arbitrary — sehingga formula ini **generik dan portabel** ke hardware lain dengan FoV berbeda. Ekspresi umum batas zona ke-$k$:

$$b_k = D_{left} + k \cdot W_z, \quad k \in \{0, 1, 2, 3\}, \quad W_z = \frac{W_{tof}}{3}$$

---

### C.5 Aliran Variabel

```
[Formula B]
    │
    └──→ x_c (px)
              │
              ▼
         [Formula C: Pemetaan Zona]
         Evaluasi: x_c vs {b_0, b_1, b_2, b_3}
              │
              └──→ h(x_c) ∈ {10, 11, 12, 1, 2}
                        │
                        └──→ [TTS Output]
                             "Jam sebelas" / "Jam dua belas" / dll.
```

---

## D. Formulasi Pemetaan Indeks Kolom ToF (Grid Binning)

**Referensi:** Flowchart 3a, SD-2

---

### D.1 Notasi yang Diperbaiki

$$\boxed{j = \text{sat}\!\left(\left\lfloor \frac{x_c - D_{left}}{R_{col}} \right\rfloor,\ 0,\ N_{col}-1\right)}$$

dengan fungsi saturasi:

$$\text{sat}(v,\ v_{min},\ v_{max}) = \max\!\left(v_{min},\ \min(v,\ v_{max})\right)$$

**Domain input:** $x_c \in [D_{left},\ D_{left} + W_{tof} - 1] = [80,\ 559]$ px (zona aktif ToF)

**Range output:** $j \in \{0, 1, 2, 3, 4, 5, 6, 7\}$

### D.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $x_c$ | Input | $[80, 559]$ | px | Posisi horizontal objek dari Formula B |
| $D_{left}$ | Konstanta | — | px | Offset dead zone kiri (= 80 px) |
| $R_{col}$ | Konstanta | — | px/kolom | Lebar satu kolom ToF dalam piksel (= 60 px) |
| $N_{col}$ | Konstanta | — | — | Jumlah kolom sensor (= 8) |
| $\lfloor \cdot \rfloor$ | Operator | — | — | Floor: pembulatan ke bawah ke bilangan bulat |
| $\text{sat}(\cdot)$ | Fungsi | — | — | Klamping: memastikan $j \in [0, N_{col}-1]$ |
| $j$ | **Output** | $\{0,...,7\}$ | — | **Indeks kolom sensor ToF yang sesuai posisi objek** |

---

### D.3 Cara Kerja Detail

Formula ini melakukan **quantization spasial** — memetakan ruang kontinu piksel kamera (480 px aktif) ke ruang diskret kolom sensor (8 kolom integer).

**Langkah demi langkah:**

1. **Offset koreksi:** $x_c - D_{left}$ — geser koordinat piksel agar dead zone kiri tidak dihitung. Piksel ke-80 menjadi piksel ke-0 dalam sistem koordinat ToF.

2. **Pembagian blok:** $\frac{x_c - D_{left}}{R_{col}}$ — setiap 60 piksel kamera mewakili satu kolom ToF.

3. **Floor:** $\lfloor \cdot \rfloor$ — semua piksel dalam satu blok 60 px dipetakan ke integer yang sama. Ini adalah *nearest-lower-bound quantization*.

4. **Saturasi:** `sat(·, 0, 7)` — guard wajib. Tanpa ini, jika $x_c = 560$ maka hasil = $\lfloor(560-80)/60\rfloor = \lfloor 8 \rfloor = 8$ yang **out of range** dan akan menyebabkan array access error.

**Tabel mapping:**

| $j$ | Rentang piksel $x_c$ | Zona Jam |
|---|---|---|
| 0 | 80 – 139 | Jam 11 |
| 1 | 140 – 199 | Jam 11 |
| 2 | 200 – 259 | Jam 11/12 |
| 3 | 260 – 319 | Jam 12 |
| 4 | 320 – 379 | Jam 12 |
| 5 | 380 – 439 | Jam 12/1 |
| 6 | 440 – 499 | Jam 1 |
| 7 | 500 – 559 | Jam 1 |

---

### D.4 Asal Usul Formula

**Konsep:** Uniform scalar quantization dan spatial binning.

**Penemu:** Teori quantization optimal diletakkan oleh **Joel Max** dalam makalah *"Quantizing for Minimum Distortion"* (IRE Transactions on Information Theory, 1960). Max menunjukkan bahwa untuk distribusi seragam, pembagian interval sama rata adalah kuantizer optimal yang meminimalkan mean squared error. Kuantizer uniform Max didefinisikan sebagai:

$$Q(x) = q_k \quad \text{jika } x \in [t_k,\ t_{k+1}), \quad q_k = \frac{t_k + t_{k+1}}{2}$$

di mana $t_k$ adalah batas interval dan $q_k$ adalah nilai rekonstruksi (centroid sel). Untuk sel dengan lebar seragam $\Delta = R_{col}$, ini menyederhanakan ke:

$$q_k = t_0 + \left(k + \frac{1}{2}\right)\Delta$$

Formula D menggunakan variasi floor (bukan midpoint) karena yang dibutuhkan adalah *indeks kolom* integer, bukan nilai rekonstruksi: $j = \lfloor (x_c - D_{left}) / R_{col} \rfloor$.

Dalam konteks **computer vision**, teknik *spatial binning* dipopulerkan oleh **Navneet Dalal dan Bill Triggs** melalui algoritma **HOG (Histogram of Oriented Gradients)** (CVPR 2005). Formula HOG cell membagi frame ke dalam sel $8 \times 8$ piksel:

$$\text{cell}(x, y) = \left(\left\lfloor \frac{x}{c_w} \right\rfloor,\ \left\lfloor \frac{y}{c_h} \right\rfloor\right)$$

di mana $c_w, c_h$ adalah lebar dan tinggi sel — persis analog dengan Formula D yang menggunakan $c_w = R_{col}$.

Fungsi saturasi `sat()` merupakan standar dalam implementasi **fixed-point arithmetic** pada embedded systems — terdokumentasi dalam ARM ACLE (Architecture Language Compiler Extension) dan digunakan secara universal dalam DSP dan mikrokontroler.

**Penerapan dalam sistem ini:** Formula ini merupakan *linear mapping* dari ruang kamera ke ruang sensor. Karena FoV keduanya diasumsikan sejajar (aligned mounting), pemetaan linearnya valid. Fungsi `sat()` ditambahkan untuk kekokohan implementasi pada ESP32-S3.

---

### D.5 Aliran Variabel

```
[Formula B]                     [Konstanta Sistem]
    │                                    │
    └──→ x_c (px)           D_{left}=80, R_{col}=60, N_{col}=8
              │                          │
              └────────────┬─────────────┘
                           ▼
                    [Formula D: Binning]
                    j = sat(⌊(x_c - 80)/60⌋, 0, 7)
                           │
                           └──→ j ∈ {0,...,7}
                                     │
                                     └──→ [Formula E] → d_obj
```

---

## E. Formulasi Jarak Objek dari Sensor ToF

**Referensi:** Flowchart 3a, SD-2

> **[DIPERBARUI v3]** $\mathcal{R}_{obj}$ kini dihitung secara dinamis berdasarkan sudut pitch $\theta$ dari MPU-6050 yang diterima via paket WebSocket.
> **[DIPERBARUI v7]** Ditambahkan filter nilai error sensor VL53L5CX sebelum averaging.

---

### E.1 Notasi

**Guard condition** (diperiksa sebelum komputasi):

$$\text{if } j \notin [0,\ N_{col}-1]\ \Rightarrow\ d_{obj} := d_{max}, \quad \text{skip E.2}$$

**E.0 — Filter nilai error sensor (wajib sebelum E.3):**

VL53L5CX mengembalikan dua nilai sentinel yang harus dibuang sebelum averaging:
- $z_{r,j} = 0$: objek terlalu dekat (< 3 cm) atau sensor timeout
- $z_{r,j} = 65535$: ambient light terlalu kuat / VCSEL error

$$z_{r,j}^{valid} = \begin{cases} z_{r,j}, & z_{r,j} \in [z_{min},\ d_{max}] \\ \varnothing, & \text{otherwise} \end{cases}, \quad z_{min} = 30\ \text{mm}$$

$$\mathcal{R}_{valid}(\theta,j) = \left\{r \in \mathcal{R}_{obj}(\theta) : z_{r,j}^{valid} \neq \varnothing \right\}$$

**E.1 — Baris pusat sensor terhadap ketinggian badan ($\theta$-aware):**

$$r_{center}(\theta) = \text{round}\!\left(3.5 + \frac{\theta}{\delta\theta}\right) = \text{round}\!\left(3.5 + \frac{\theta}{5.625}\right)$$

**E.2 — Himpunan baris objek (tiga baris di sekitar pusat):**

$$\mathcal{R}_{obj}(\theta) = \left\{\ \text{sat}(r_c - 1,\ 0,\ N_{row}-1),\ \text{sat}(r_c,\ 0,\ N_{row}-1),\ \text{sat}(r_c + 1,\ 0,\ N_{row}-1)\ \right\}$$

di mana $r_c = r_{center}(\theta)$.

**E.3 — Jarak objek (rata-rata baris valid):**

$$\boxed{d_{obj} = \begin{cases} \dfrac{1}{|\mathcal{R}_{valid}|} \displaystyle\sum_{r \in \mathcal{R}_{valid}(\theta,j)} z_{r,j}, & |\mathcal{R}_{valid}| > 0 \\[10pt] d_{max}, & |\mathcal{R}_{valid}| = 0 \end{cases}}$$

Jika semua baris di $\mathcal{R}_{obj}$ menghasilkan nilai error (misalnya pantulan cermin atau pencahayaan ekstrem), $d_{obj}$ di-set ke $d_{max}$ sebagai nilai sentinel "tidak terdeteksi" — sehingga tidak memicu peringatan palsu.

**Domain input:** $z_{r,j} \in [0,\ d_{max}]$ mm, $j \in \{0,...,7\}$, $\theta \in [-90°, +90°]$

**Range output:** $d_{obj} \in [0,\ 4000]$ mm

**Contoh nilai $\mathcal{R}_{obj}$ pada berbagai postur kepala:**

| Postur kepala | $\theta$ | $r_{center}$ | $\mathcal{R}_{obj}$ |
|---|---|---|---|
| Kepala tegak (standar) | $0°$ | $\text{round}(3.5) = 4$ | $\{3, 4, 5\}$ |
| Menunduk 5.6° | $+5.6°$ | $\text{round}(4.5) = 5$ | $\{4, 5, 6\}$ |
| Mendongak 5.6° | $-5.6°$ | $\text{round}(2.5) = 3$ | $\{2, 3, 4\}$ |
| Menunduk 11.3° | $+11.3°$ | $\text{round}(5.5) = 6$ | $\{5, 6, 7\}$ |

### E.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $z_{r,j}$ | Input | $[0, 4000]$ | mm | Pembacaan sensor ToF pada baris $r$, kolom $j$ |
| $j$ | Input | $\{0,...,7\}$ | — | Indeks kolom dari Formula D |
| $\theta$ | Input | $[-90°, +90°]$ | ° | Pitch kepala dari Formula A (via paket WebSocket) |
| $\delta\theta$ | Konstanta | — | °/baris | Resolusi sudut per baris = 5.625° |
| $r_{center}(\theta)$ | Intermediat | $\{0,...,7\}$ | — | Baris pusat sensor setelah kompensasi pitch |
| $\mathcal{R}_{obj}(\theta)$ | Dinamis | $\subseteq\{0,...,7\}$ | — | Himpunan 3 baris sensor untuk rata-rata jarak |
| $d_{max}$ | Konstanta | — | mm | Nilai sentinel saat guard aktif (= 4000 mm) |
| $d_{obj}$ | **Output** | $[0, 4000]$ | mm | **Jarak objek yang terdeteksi** |

---

### E.3 Cara Kerja Detail

Sensor VL53L5CX menghasilkan **matriks jarak 8×8 = 64 nilai** setiap siklus pengukuran. Setiap elemen $z_{r,j}$ adalah jarak (mm) ke objek yang berada dalam zona sudut pandang baris $r$, kolom $j$.

**Sudut absolut setiap baris dari horizontal:**

```
Baris 0: +19.7° → -15° + 19.7° = +4.7°   → Sedikit di atas horizontal
Baris 1: +14.1° → -15° + 14.1° = -0.9°   → Nyaris horizontal
Baris 2: + 8.4° → -15° +  8.4° = -6.6°   → Area di atas badan
Baris 3: + 2.8° → -15° +  2.8° = -12.2°  ← Bahu/dada objek
Baris 4:   0.0° → -15° +  0.0° = -15.0°  ← Tengah/perut objek
Baris 5: - 2.8° → -15° -  2.8° = -17.8°  ← Pinggang objek
Baris 6: - 8.4° → -15° -  8.4° = -23.4°  → Kaki / lantai dekat
Baris 7: -14.1° → -15° - 14.1° = -29.1°  → Lantai sangat dekat
```

Sudut relatif di atas dihitung dari sumbu optik sensor ($0°$). Sudut absolut dari horizontal dihitung dengan memperhitungkan $\alpha_{mount} = 15°$.

**Mengapa $r_{center}$ perlu bergeser saat kepala berubah postur?**

Saat pengguna menunduk sebesar $\theta > 0$, seluruh sensor ikut menunduk, sehingga baris yang sebelumnya mengarah ke perut objek ($r=4$) kini mengarah ke lantai. Formula E.1 mengkompensasi ini dengan menggeser $r_{center}$ ke atas ($r$ lebih kecil) agar tetap membaca area badan objek.

**Mengapa rata-rata, bukan minimum?** Minimum akan terlalu sensitif terhadap satu zona yang terkena refleksi atau noise. Rata-rata memberikan estimasi yang lebih stabil dengan trade-off sedikit lebih lambat merespons objek tipis (seperti tiang).

---

### E.4 Asal Usul Formula

**Konsep:** Spatial averaging untuk estimasi jarak robust dari sensor array.

**Landasan teoritis:** Berasal dari teori **least squares estimation** yang dikembangkan oleh **Carl Friedrich Gauss** (1795, dipublikasikan 1809 dalam *Theoria Motus Corporum Coelestium*). Gauss membuktikan bahwa untuk pengukuran dengan noise Gaussian, estimator yang meminimalkan jumlah kuadrat residual adalah rata-rata aritmatika — dikenal sebagai **Gauss-Markov Theorem**:

$$\hat{\mu}_{BLUE} = \bar{z} = \frac{1}{n}\sum_{i=1}^{n} z_i$$

yang merupakan **Best Linear Unbiased Estimator (BLUE)** — artinya tidak ada estimator linear lain yang memiliki varians lebih kecil. Untuk $n = |\mathcal{R}_{obj}|$ baris sensor:

$$\hat{d}_{obj} = \frac{1}{|\mathcal{R}_{obj}|}\sum_{r \in \mathcal{R}_{obj}} z_{r,j}, \quad \text{Var}(\hat{d}_{obj}) = \frac{\sigma_z^2}{|\mathcal{R}_{obj}|}$$

Menggunakan 3 baris ($|\mathcal{R}_{obj}| = 3$) mengurangi varians noise menjadi $\sigma_z^2/3$ dibandingkan menggunakan satu baris saja.

Dalam konteks **sensor ToF array**, teknik ini digunakan secara ekstensif dalam robotika dan kendaraan otonom. Referensi kunci:
- Foix, S., Alenya, G., & Torras, C. (2011). *Lock-in Time-of-Flight (ToF) cameras: a survey.* IEEE Sensors Journal. — Mendeskripsikan praktik averaging untuk noise reduction pada sensor ToF.
- ST Microelectronics. (2022). *VL53L5CX API User Manual.* UM2884. — Dokumentasi resmi sensor yang digunakan dalam sistem ini.

**Penerapan dalam sistem ini:** Dengan MPU-6050, $\mathcal{R}_{obj}(\theta)$ kini dihitung **dinamis** berdasarkan pitch $\theta$ yang diterima dari paket WebSocket ESP32. Ini menggantikan nilai hardcode $\{3,4,5\}$ yang digunakan pada versi tanpa IMU.

---

### E.5 Aliran Variabel

```
[Sensor VL53L5CX]    [Formula D]    [Formula A via WebSocket]
    │                    │                    │
    └──→ z_{r,j}         └──→ j ∈ {0,...,7}  └──→ θ (°)
    (matriks 8×8, mm)         │                    │
              │               └────────┬───────────┘
              └────────────────────────┤
                                       ▼
                              [Formula E.1: r_center]
                              r_c = round(3.5 + θ/5.625)
                                       │
                              [Formula E.2: R_obj]
                              R_obj = {sat(r_c-1,0,7), sat(r_c,0,7), sat(r_c+1,0,7)}
                                       │
                              [Formula E.3: Averaging]
                              d_obj = (1/3) Σ z_{r,j} for r ∈ R_obj
                                       │
                                       └──→ d_obj (mm)
                                                 │
                                       ┌─────────┴──────────┐
                                       ▼                    ▼
                                [Formula G]           [Formula H]
                                Threshold adaptif     One-shot alert
                                       │
                                       └──→ [Formula F] (sebagai Δd input)
```

---

## F. Formulasi Deteksi Sumber Gerakan

**Referensi:** Flowchart 3c

> **[DIPERBARUI v3]** Sumber akselerasi beralih dari akselerometer smartphone di saku ke $\|\mathbf{a}_{lin}\|$ dari MPU-6050 yang dipasang di kacamata/helm (diterima via paket WebSocket). Data ini lebih representatif dan bebas dari noise gerakan saku.
>
> **[DIPERBARUI v8]** Arsitektur diubah dari seleksi objek tunggal `min(d)` menjadi **Danger-Weighted Multi-Object Routing** berbasis tiga sub-skor: jarak, kecepatan pendekatan, dan tipe gerakan. Setiap objek yang terdeteksi YOLO kini mendapat *danger score* $D^{(i)} \in [0, 1]$ dan dialirkan ke pool peringatan yang sesuai. Ini menyelesaikan *known limitation* MVP di mana objek terdekat (statis) bisa menyumbat deteksi terhadap objek lain yang mendekat cepat.

---

### F.1 Notasi

**F.1 — Magnitude akselerasi linear (dari paket WebSocket ESP32):**

$$\|\mathbf{a}_{lin}\| \leftarrow \mathbf{p}_{imu}.\text{acc\_mag}$$

Nilai ini sudah dihitung di ESP32 oleh Formula A.5: $\|\mathbf{a}_{lin}\| = \|\mathbf{a}_t - \mathbf{g}_q\|$

**F.2 — Klasifikasi sumber gerakan per-objek (tidak berubah):**

Setiap objek $i$ diklasifikasikan secara independen:

$$\boxed{\text{src}^{(i)} = \begin{cases}
\text{user},    & \|\mathbf{a}_{lin}\| > a_{th} \\
\text{object},  & \|\mathbf{a}_{lin}\| \le a_{th}\ \wedge\ \Delta d^{(i)} > \varepsilon_{noise} \\
\text{receding},& \|\mathbf{a}_{lin}\| \le a_{th}\ \wedge\ \Delta d^{(i)} < -\varepsilon_{noise} \\
\text{static},  & \|\mathbf{a}_{lin}\| \le a_{th}\ \wedge\ |\Delta d^{(i)}| \le \varepsilon_{noise}
\end{cases}}$$

dengan delta jarak per-objek:

$$\Delta d^{(i)} = d_{obj}^{(i),(t-1)} - d_{obj}^{(i),(t)}$$

(positif berarti objek ke-$i$ mendekat)

**F.3 — Danger Score per-objek (BARU v8):**

Untuk setiap objek $i$ dengan $\text{src}^{(i)} \in \{\text{object}, \text{user}, \text{static}\}$, hitung danger score melalui tiga sub-skor (objek `receding` di-skip dengan $D^{(i)} = 0$):

**F.3a — Sub-skor jarak $d\_\text{score}^{(i)}$:**

$$d\_\text{score}^{(i)} = \begin{cases}
1.0, & d_{obj}^{(i)} < d_{crit} \\[4pt]
\dfrac{d_{max} - d_{obj}^{(i)}}{d_{max} - d_{crit}}, & d_{crit} \le d_{obj}^{(i)} \le d_{max} \\[4pt]
0.0, & d_{obj}^{(i)} > d_{max}
\end{cases}$$

**F.3b — Sub-skor kecepatan pendekatan $v\_\text{score}^{(i)}$:**

Kecepatan pendekatan per-objek dihitung dengan definisi yang sama dengan Formula G.2:

$$v^{(i)} = \frac{\max\!\left(\Delta d^{(i)},\ 0\right)}{\Delta t} - v_{head}$$

$$v\_\text{score}^{(i)} = \begin{cases}
0.0, & v^{(i)} < v_{slow} \\[4pt]
\dfrac{v^{(i)} - v_{slow}}{v_{fast} - v_{slow}}, & v_{slow} \le v^{(i)} \le v_{fast} \\[4pt]
1.0, & v^{(i)} > v_{fast}
\end{cases}$$

**F.3c — Sub-skor tipe gerakan $\text{src\_score}^{(i)}$:**

$$\text{src\_score}^{(i)} = \begin{cases}
1.0, & \text{src}^{(i)} = \text{object} \\
0.8, & \text{src}^{(i)} = \text{user} \\
0.2, & \text{src}^{(i)} = \text{static} \\
0.0, & \text{src}^{(i)} = \text{receding}
\end{cases}$$

Logika: sumber `object` (objek bergerak aktif mendekati user yang diam) adalah ancaman tertinggi; `user` (user yang bergerak mendekati objek diam) lebih mudah dihindari; `static` hanya rintangan pasif; `receding` tidak berbahaya.

**F.3d — Danger Score final (gabungan tertimbang):**

$$\boxed{D^{(i)} = w_d \cdot d\_\text{score}^{(i)} + w_v \cdot v\_\text{score}^{(i)} + w_s \cdot \text{src\_score}^{(i)}}$$

dengan bobot normalisasi $w_d + w_v + w_s = 1.0$ (nilai default: $w_d = 0.35$, $w_v = 0.45$, $w_s = 0.20$).

**Alasan pemilihan bobot:** Kecepatan pendekatan ($w_v = 0.45$) memiliki bobot tertinggi karena Time-to-Contact (TTC) adalah faktor penentu keselamatan utama — objek jauh yang mendekat sangat cepat lebih berbahaya daripada objek dekat yang diam. Jarak absolut ($w_d = 0.35$) menempati posisi kedua karena batas fisik tetap penting sebagai safety buffer. Tipe gerakan ($w_s = 0.20$) bersifat *subsidiary* — contextual information yang memperhalus priority.

**Domain:** $D^{(i)} \in [0, 1]$ untuk setiap objek $i$ yang aktif.

**F.4 — Danger Pooling dan Routing (BARU v8):**

Setelah semua $D^{(i)}$ dihitung, objek dikelompokkan ke tiga *pool* berdasarkan tingkat bahaya:

$$\boxed{\text{pool}^{(i)} = \begin{cases}
\text{HIGH},   & D^{(i)} > D_{HIGH} \\
\text{MEDIUM}, & D_{MID} < D^{(i)} \le D_{HIGH} \\
\text{LOW},    & D^{(i)} \le D_{MID}
\end{cases}}$$

Setiap pool mendapat penanganan berbeda:
- **HIGH** ($D > 0.70$): Objek dialirkan ke Formula G *dan* H dengan urgency tinggi; TTS diulang setiap frame; tidak ada debounce.
- **MEDIUM** ($0.35 < D \le 0.70$): Objek dialirkan ke Formula G dengan debounce 2 detik; TTS priority normal.
- **LOW** ($D \le 0.35$): Hanya Formula H (one-shot); tidak diulang.

Jika terdapat lebih dari satu objek HIGH secara bersamaan, urutkan berdasarkan $D^{(i)}$ menurun dan umumkan dengan jeda 200 ms antar objek agar TTS tidak bertumpuk.

**Domain:** $D^{(i)} \in [0, 1]$, $\text{pool}^{(i)} \in \{\text{HIGH, MEDIUM, LOW}\}$

---

### F.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $\|\mathbf{a}_{lin}\|$ | Input | $[0, \infty)$ | m/s² | Magnitude akselerasi linear dari **MPU-6050 via paket WebSocket** (sudah dikurangi gravitasi oleh Formula A.5) |
| $a_{th}$ | Konstanta | — | m/s² | Threshold gerakan = 2.94 m/s² ($\approx 0.3g$) |
| $d_{obj}^{(i),(t)}$ | Input | $[0, 4000]$ | mm | Jarak objek ke-$i$, frame saat ini (dari Formula E, per tracking ID) |
| $d_{obj}^{(i),(t-1)}$ | State | $[0, 4000]$ | mm | Jarak objek ke-$i$, frame sebelumnya |
| $\varepsilon_{noise}$ | Konstanta | — | mm | Noise floor ToF = 30 mm |
| $\Delta d^{(i)}$ | Intermediat | $(-4000, 4000)$ | mm | Perubahan jarak per-objek (positif = mendekat) |
| $\text{src}^{(i)}$ | Intermediat | $\{\text{user, object, receding, static}\}$ | — | Klasifikasi sumber gerakan per-objek |
| $v^{(i)}$ | Intermediat | $[0, \infty)$ | mm/s | Kecepatan pendekatan bersih per-objek (menggunakan definisi Formula G.2) |
| $d\_\text{score}^{(i)}$ | Intermediat | $[0, 1]$ | — | Sub-skor jarak |
| $v\_\text{score}^{(i)}$ | Intermediat | $[0, 1]$ | — | Sub-skor kecepatan pendekatan |
| $\text{src\_score}^{(i)}$ | Intermediat | $[0, 1]$ | — | Sub-skor tipe gerakan |
| $D^{(i)}$ | **Output** | $[0, 1]$ | — | **Danger Score objek ke-$i$** |
| $\text{pool}^{(i)}$ | **Output** | $\{\text{HIGH, MEDIUM, LOW}\}$ | — | **Pool routing berdasarkan danger** |
| $w_d, w_v, w_s$ | Konstanta | — | — | Bobot danger score: 0.35, 0.45, 0.20 |
| $d_{crit}$ | Konstanta | — | mm | Jarak kritis = 1000 mm |
| $v_{slow}, v_{fast}$ | Konstanta | — | mm/s | Batas kecepatan scoring: 500 dan 2000 mm/s |
| $D_{HIGH}, D_{MID}$ | Konstanta | — | — | Threshold pool: 0.70 dan 0.35 |

---

### F.3 Cara Kerja Detail

**Masalah arsitektur MVP yang diselesaikan:**

Seleksi `min(d)` pada v7 adalah strategi konservatif — memprioritaskan objek paling dekat. Namun dalam skenario jalan ramai, ini menciptakan *silent failure*: objek terdekat yang statis (tiang, dinding) menjadi "penyumbat" sehingga objek lebih jauh yang mendekat sangat cepat (motor, pesepeda) tidak pernah diproses oleh Formula G.

**Ilustrasi masalah:**
```
User berjalan di trotoar
├─ Obj 1 (Tiang): d = 500 mm, Δd ≈ 0 → src = static
│  Dipilih oleh min(d) → dikirim ke Formula H → one-shot alert
│
└─ Obj 2 (Motor): d = 1200 mm, v = 4500 mm/s → src = object
   DILEWATKAN oleh v7 → tidak ada peringatan ← BAHAYA NYATA
```

**Cara kerja F.3 — Danger Score:**

Danger Score menggabungkan tiga dimensi risiko ke dalam satu angka $D \in [0, 1]$. Mari telusuri contoh numerik untuk kedua objek di atas:

*Obj 1 (Tiang): d = 500 mm, v ≈ 0, src = static*
$$d\_\text{score} = 1.0 \quad (500 < d_{crit} = 1000)$$
$$v\_\text{score} = 0.0 \quad (v \approx 0 < v_{slow} = 500)$$
$$\text{src\_score} = 0.2 \quad (\text{static})$$
$$D^{(1)} = 0.35 \times 1.0 + 0.45 \times 0.0 + 0.20 \times 0.2 = 0.39$$
Pool: **MEDIUM** → alert normal, debounce 2 s

*Obj 2 (Motor): d = 1200 mm, v = 4500 mm/s, src = object*
$$d\_\text{score} = \frac{4000 - 1200}{4000 - 1000} = 0.93$$
$$v\_\text{score} = \min\!\left(\frac{4500 - 500}{2000 - 500},\ 1.0\right) = 1.0 \quad (\text{clipped})$$
$$\text{src\_score} = 1.0 \quad (\text{object})$$
$$D^{(2)} = 0.35 \times 0.93 + 0.45 \times 1.0 + 0.20 \times 1.0 = 0.975$$
Pool: **HIGH** → alert urgent, tidak ada debounce

Dengan v8, tiang diperingatkan secara normal (tidak mendominasi pipeline), dan motor yang mendekat sangat cepat langsung mendapat peringatan urgent — **kedua objek ditangani sesuai tingkat bahayanya yang sebenarnya**.

**Mengapa MPU-6050 lebih baik dari akselerometer smartphone?**

MPU-6050 dipasang di kacamata/helm — posisinya di kepala pengguna, jauh lebih representatif untuk mendeteksi apakah pengguna sedang berjalan atau diam. Akselerometer smartphone di saku mengalami gerakan tambahan dari ayunan baju dan pergerakan saku yang tidak terkait dengan gerak tubuh. Selain itu, gravitasi sudah dieliminasi oleh Formula A.5 di ESP32 menggunakan hardware yang terdedikasi, sehingga tidak bergantung pada `TYPE_LINEAR_ACCELERATION` API Android/iOS yang tidak selalu tersedia di semua perangkat.

**Penambahan case `receding` (v3, tetap berlaku):**

Domain piecewise F.2 bersifat exhaustive: setiap kombinasi $(\|\mathbf{a}_{lin}\|, \Delta d^{(i)})$ jatuh ke tepat satu case. Ini penting untuk implementasi — tidak ada kondisi "tidak terdefinisi" yang bisa menyebabkan perilaku tidak terduga.

**Kompleksitas komputasi:**

Dengan $N$ objek terdeteksi, Formula F.v2 berjalan dalam $O(N)$ untuk klasifikasi dan scoring, dan $O(N \log N)$ untuk pengurutan jika lebih dari satu objek HIGH. Dalam praktik, $N \le 5$ objek per frame di lingkungan perkotaan normal — overhead terhadap MVP hanya beberapa milidetik, dapat diabaikan.

---

### F.4 Asal Usul Formula

**Konsep utama (F.2):** Activity recognition berbasis akselerometer — tidak berubah dari v7.

**Penemu/Peneliti:** Deteksi gerakan manusia via akselerometer smartphone pertama kali dipopulerkan oleh **Jennifer Kwapisz, Gary Weiss, dan Samuel Moore** dalam makalah berpengaruh *"Activity Recognition using Cell Phone Accelerometers"* (ACM SIGKDD, 2011).

**Konsep danger scoring (F.3):** Weighted multi-criteria decision making (MCDM).

**Landasan F.3 — Weighted Sum Model (WSM):** Teknik penggabungan kriteria jamak dengan bobot tertimbang diformalkan oleh **Churchman, Ackoff & Arnoff** dalam *Introduction to Operations Research* (1957). WSM didefinisikan sebagai:

$$D = \sum_{k=1}^{K} w_k \cdot s_k, \quad \sum_k w_k = 1, \quad s_k \in [0, 1]$$

di mana $w_k$ adalah bobot kepentingan relatif kriteria ke-$k$ dan $s_k$ adalah skor ternormalisasi. Formula F.3d adalah instansiasi langsung dengan tiga kriteria ($K = 3$): jarak, kecepatan, dan tipe gerakan.

**Landasan F.4 — Alert level design:** Sistem tiga level (HIGH / MEDIUM / LOW) mengikuti prinsip *alarm management* dari standar **IEC 62682:2014** (*Management of Alarms in the Process Industries*) yang mendefinisikan hierarki *Priority 1 (emergency)*, *Priority 2 (high)*, *Priority 3 (medium)*, dan *Priority 4 (low)* untuk sistem alarm industri. Standar ini secara eksplisit melarang sistem alarm yang memperlakukan semua kondisi dengan urgensi yang sama, karena hal tersebut menyebabkan *alert fatigue* — fenomena di mana operator/pengguna mengabaikan alarm akibat terlalu banyak alarm dengan prioritas seragam.

**Penerapan dalam sistem ini:** Bobot $w_d = 0.35$, $w_v = 0.45$, $w_s = 0.20$ adalah nilai default yang perlu dikalibrasi empiris menggunakan ROC curve analysis pada dataset lapangan. Threshold $D_{HIGH} = 0.70$ dan $D_{MID} = 0.35$ harus dioptimalkan untuk memaksimalkan F1-score terhadap ground truth "perlu peringatan vs tidak perlu".

---

### F.5 Aliran Variabel

```
[Formula A via WebSocket]       [Formula E, frame (t) & (t-1)]
    │                           [Per YOLO tracking ID i = 1..N]
    └──→ ‖a_lin‖ (m/s²)         └──→ d_obj^(i,t), d_obj^(i,t-1)
    └──→ ω_x^corr, Δt                      │
              │                            └──→ Δd^(i) = d^(i,t-1) - d^(i,t)
              │                                          │
              └─────────────────────┬────────────────────┘
                                    ▼
                         FOR EACH object i:
                    [Formula F.2: Klasifikasi src^(i)]
                                    │
                    ┌───────────────┼─────────────────┐
                    ▼               ▼                  ▼
              src=user/object  src=receding       src=static
                    │               │                  │
                    ▼               ▼                  ▼
             [Formula F.3:       SKIP           [Formula F.3:
              Danger Score D^i]  (D=0)           Danger Score D^i]
                    │                             (v=0, src_score=0.2)
                    ▼
             [Formula F.4: Routing ke pool]
                    │
       ┌────────────┼──────────────────┐
       ▼            ▼                  ▼
    HIGH          MEDIUM             LOW
  D > 0.70    0.35 < D ≤ 0.70     D ≤ 0.35
       │            │                  │
    F.G+H         F.G+H             F.H saja
    Urgent       Normal,           One-shot
    repeat       debounce 2s
```
---

## G. Formulasi Threshold Peringatan Adaptif

**Referensi:** Flowchart 3c, SD-3a

> **[DIPERBARUI v3]** Ditambahkan sub-formula G.0 untuk kompensasi kecepatan semu akibat gerak kepala menggunakan $\omega_x$ dari MPU-6050.
> **[DIPERBARUI v6]** `ω_x` kini diambil dari `p_imu.ω_x^{corr}` (bias-corrected dari EKF, bukan raw). `Δt` kini dihitung dari selisih `ts_esp` antar frame untuk konsistensi referensi waktu dengan data ToF. Output indikator diubah ke $\delta_G$ agar konsisten dengan nama formula.
> **[DIPERBARUI v8]** Tiga perbaikan presisi ditambahkan: (1) koreksi $\cos(\theta)$ pada $v_{head}$ untuk sudut pitch besar ($|\theta| > 30°$) di mana aproksimasi busur mulai menghasilkan error signifikan; (2) guard laju rotasi ekstrim untuk mencegah *overcompensation* saat pengguna menoleh tajam; (3) guard konvergensi EKF (`is_converged`) yang memblokir output Formula G selama periode warmup ~5 detik setelah sistem menyala, di mana estimasi $\theta$ dan $\omega_x^{corr}$ belum stabil; (4) moving average 3-frame pada kecepatan pendekatan $v$ untuk meredam fluktuasi akibat jitter WiFi WebSocket 5–50 ms.

---

### G.1 Notasi

**G.0 — Interval waktu dari timestamp ESP32 (tidak berubah):**

$$\Delta t_{raw} = \frac{ts_{esp}^{(t)} - ts_{esp}^{(t-1)}}{1000}$$

$$\boxed{\Delta t = \text{sat}\!\left(\Delta t_{raw},\ \Delta t_{min},\ \Delta t_{max}\right)}, \quad \Delta t_{min} = 0.001\ \text{s},\quad \Delta t_{max} = 0.5\ \text{s}$$

Fungsi `sat()` menangani dua kondisi edge case sekaligus:
- **Batas bawah $\Delta t_{min}$:** mencegah division-by-zero jika dua paket tiba dengan timestamp identik
- **Batas atas $\Delta t_{max} = 0.5$ s:** mencegah underestimate kecepatan saat koneksi WebSocket sempat putus lalu tersambung kembali. Tanpa batas ini, jika koneksi putus 3 detik, $\Delta t = 3$ s menghasilkan $v \approx 0$ meskipun ada gerakan nyata

**Guard frame pertama ($t = 1$, tidak berubah):**

Pada frame pertama setelah sistem menyala atau setelah reconnect, $ts_{esp}^{(t-1)}$ belum tersedia di memori. Kondisi ini ditangani dengan:

$$\text{if } ts_{esp}^{(t-1)} = \varnothing: \quad v := 0,\ v_{head} := 0,\ T := d_{w0} \quad \text{(skip G.1 dan G.2)}$$

Ini memastikan tidak ada peringatan palsu di frame pertama akibat $\Delta t$ yang tidak terdefinisi.

---

**G.EKF — Guard konvergensi EKF (BARU v8):**

EKF membutuhkan beberapa detik untuk konvergen dari kondisi inisialisasi $\mathbf{P}_0 = \mathbf{I}_7$ (ketidakpastian tinggi). Selama periode ini, estimasi $\theta$ dan $\omega_x^{corr}$ yang dipakai Formula G belum akurat — bias $b_\omega$ belum terpelajari, dan quaternion masih dalam proses stabilisasi. Jika Formula G dijalankan selama warmup, $v_{head}$ yang tidak akurat bisa menghasilkan false positive atau, sebaliknya, gagal memberikan peringatan yang diperlukan.

Guard ini melacak dua kondisi secara paralel:

**Berbasis frame counter:**

$$\text{is\_converged} := \mathbf{1}\!\left[t_{frame} \ge N_{warmup}\right]$$

dengan $t_{frame}$ adalah penghitung frame sejak sistem menyala (bukan sejak reconnect WebSocket) dan $N_{warmup} = 150$ frame $\approx 5$ detik pada 30 Hz.

**Berbasis norma kovarians (lebih presisi, opsional):**

$$\text{is\_converged} := \mathbf{1}\!\left[\|\mathbf{P}_t\|_F < \varepsilon_{conv}\right]$$

dengan $\|\mathbf{P}_t\|_F = \sqrt{\sum_{i,j} P_{ij}^2}$ adalah norma Frobenius matriks kovarians EKF yang dikirim ESP32 sebagai scalar tambahan dalam paket WebSocket, dan $\varepsilon_{conv} = 0.10$.

Jika kedua metode tersedia, gunakan logika **OR**: `is_converged` aktif begitu salah satu kondisi terpenuhi — ini lebih aman karena pada lingkungan stabil (pengguna diam saat menyalakan perangkat) kovarians bisa konvergen lebih cepat dari 150 frame.

**Penanganan saat belum konvergen:**

$$\text{if } \neg\,\text{is\_converged}: \quad \delta_G := 0,\ T := d_{w0} \quad \text{(skip G.1 sampai G.4)}$$

Threshold $T = d_{w0} = 1000$ mm tetap aktif sebagai batas minimum keamanan meskipun formula tidak berjalan penuh — ini memastikan Formula K (fail-safe buzzer di ESP32) tetap menjadi satu-satunya lapisan keamanan selama warmup.

---

**G.1 — Kecepatan semu akibat gerak kepala (DIPERBARUI v8.1: Dekomposisi ESP32/Mobile):**

Formula v7 menggunakan aproksimasi busur (*arc approximation*):

$$v_{head}^{v7} = \left|\omega_x^{corr}\right| \cdot d_{obj}^{(t)} \cdot \frac{\pi}{180}$$

Aproksimasi ini valid untuk sudut pitch kecil ($|\theta| \lesssim 30°$) karena panjang busur $\approx$ panjang tali busur pada sudut kecil. Namun ketika pengguna sangat menunduk atau mendongak ($|\theta| > 30°$), yang berubah secara nyata bukan jarak penuh $d_{obj}$ melainkan **proyeksi horizontalnya** $d_{obj} \cdot \cos(\theta)$. Tanpa koreksi ini, $v_{head}$ *overestimated* hingga 13% pada $|\theta| = 30°$ dan 50% pada $|\theta| = 60°$.

**G.1a — Faktor IMU-murni (dihitung di ESP32-S3, lihat A.EKF.5):**

Semua suku dalam $v_{head}$ yang hanya bergantung pada IMU — tidak bergantung pada tracking ID YOLO — dikelompokkan menjadi satu skalar:

$$\boxed{v_{head\_base} = k_{damp} \cdot \left|\omega_x^{corr}\right| \cdot \cos\!\left(\frac{\theta \cdot \pi}{180}\right) \cdot \frac{\pi}{180}}$$

dengan faktor redaman yang dievaluasi di ESP32:

$$k_{damp} = \begin{cases}
0.5, & \left|\omega_x^{corr}\right| > \omega_{x,lim} \\
1.0, & \left|\omega_x^{corr}\right| \le \omega_{x,lim}
\end{cases}$$

Guard $k_{damp}$: ketika pengguna menoleh dengan laju pitch sangat besar ($|\omega_x^{corr}| > 5°/s$), gerakan kepala tersebut kemungkinan besar adalah gerakan aktif yang disengaja. Pada kondisi ini $k_{damp}$ dikurangi ke 0.5 untuk mempertahankan sebagian kepekaan Formula G terhadap ancaman nyata.

$v_{head\_base}$ dikirim ke Mobile sebagai bagian dari paket WebSocket $\mathbf{p}_{imu}$ (A.6). Nilai ini **identik untuk semua objek** dalam satu frame — sehingga cukup dihitung sekali di ESP32.

**G.1b — Penyelesaian per-objek (dihitung di Mobile):**

Mobile menerima $v_{head\_base}$ dari paket WebSocket dan menyelesaikannya dengan **satu perkalian skalar** per tracking ID YOLO:

$$\boxed{v_{head}^{(i)} = v_{head\_base} \times d_{obj}^{(i)}}$$

Formula lengkap $v_{head}$ setelah dekomposisi:

$$v_{head}^{(i)} = \underbrace{k_{damp} \cdot \left|\omega_x^{corr}\right| \cdot \cos(\theta) \cdot \frac{\pi}{180}}_{v_{head\_base}\text{ (ESP32, 1×/frame)}} \times \underbrace{d_{obj}^{(i)}}_{\text{Mobile, per-objek}}$$

**Manfaat dekomposisi:** Mobile tidak perlu menghitung `cos()`, `abs()`, branching `k_damp`, maupun konversi `π/180` — seluruhnya sudah diselesaikan ESP32. Overhead WebSocket hanya +4 byte/frame.

**Perbandingan nilai $v_{head}$ v7 vs v8:**

| $\theta$ | v7: $\|\omega_x\| \cdot d \cdot \pi/180$ | v8: $v_{head\_base} \times d$ | Error v7 |
|---|---|---|---|
| 0° | $v_{base}$ | $v_{base}$ | 0% |
| 10° | $v_{base}$ | $0.985 \cdot v_{base}$ | +1.5% |
| 20° | $v_{base}$ | $0.940 \cdot v_{base}$ | +6.4% |
| 30° | $v_{base}$ | $0.866 \cdot v_{base}$ | +15.5% |
| 45° | $v_{base}$ | $0.707 \cdot v_{base}$ | +41.4% |
| 60° | $v_{base}$ | $0.500 \cdot v_{base}$ | +100% |

Untuk $|\theta| \le 10°$ (postur kepala normal berjalan), perbedaannya di bawah 2% dan dapat diabaikan. Koreksi menjadi bermakna di atas $|\theta| = 20°$.

---

**G.2 — Kecepatan pendekatan bersih (DIPERBARUI v8):**

$$v_{raw} = \frac{\max\!\left(d_{obj}^{(t-1)} - d_{obj}^{(t)},\ 0\right)}{\Delta t} - v_{head}$$

dengan hasil diklamping ke nol jika negatif: $v_{raw} := \max(v_{raw}, 0)$.

**G.2b — Moving average kecepatan pendekatan (BARU v8):**

WiFi WebSocket mengalami jitter 5–50 ms pada jaringan lokal yang sibuk. Jitter ini menyebabkan $\Delta t$ berfluktuasi antar frame, dan karena $v_{raw} \propto 1/\Delta t$, fluktuasi kecil pada $\Delta t$ menghasilkan fluktuasi yang diperbesar pada $v_{raw}$. Pada $d_{obj} = 1000$ mm dan $\Delta t$ berfluktuasi antara 28 ms dan 38 ms (jitter ±5 ms sekitar 33 ms nominal):

$$v_{raw,min} = \frac{\Delta d}{0.038} \approx 26.3 \cdot \Delta d, \quad v_{raw,max} = \frac{\Delta d}{0.028} \approx 35.7 \cdot \Delta d$$

Rasio $v_{max}/v_{min} \approx 1.36$ — fluktuasi 36% dari satu frame ke frame berikutnya, murni akibat jitter, bukan gerakan nyata. Ini cukup untuk membuat threshold $T$ berfluktuasi antara $\approx 1900$ mm dan $\approx 2300$ mm, yang pada skenario borderline bisa menyebabkan alert muncul dan menghilang secara tidak konsisten (*alert flickering*).

Moving average 3-frame meredam fluktuasi ini tanpa menambahkan latensi yang berarti (1 frame = 33 ms pada 30 Hz):

$$\boxed{\bar{v}^{(t)} = \frac{v_{raw}^{(t)} + v_{raw}^{(t-1)} + v_{raw}^{(t-2)}}{3}}$$

dengan fallback untuk frame awal: $\bar{v}^{(1)} = v_{raw}^{(1)}$, $\bar{v}^{(2)} = (v_{raw}^{(1)} + v_{raw}^{(2)})/2$.

Kecepatan akhir yang digunakan Formula G.3:

$$\boxed{v = \bar{v}^{(t)}}$$

**Mengapa 3-frame (bukan lebih banyak)?**

Window $N_{v,avg} = 3$ adalah titik tengah yang optimal antara dua trade-off:
- **Terlalu kecil (N=1, tanpa avg):** sensitif terhadap jitter, alert tidak konsisten
- **Terlalu besar (N=5+):** latensi respons meningkat; pada objek yang mendekat cepat dari 3 m ke 1 m dalam 0.5 detik, window 5 frame (167 ms) akan *underestimate* kecepatan saat ini sebesar ~15%

Pada 30 Hz, N=3 menambahkan latensi efektif 66 ms — dapat diabaikan dibanding waktu reaksi manusia 2 detik ($t_r$), tetapi cukup untuk meredam jitter WiFi yang karakteristik durasinya 5–50 ms per burst.

---

**G.3 — Threshold peringatan adaptif (tidak berubah):**

$$\boxed{T = \min\!\left(d_{w0} + v \cdot t_r,\ d_{max}\right)}$$

Kini $v = \bar{v}^{(t)}$ (hasil G.2b), bukan $v_{raw}$ langsung.

---

**G.4 — Kondisi peringatan (tidak berubah):**

$$\boxed{\delta_G = \mathbf{1}\!\left[d_{obj}^{(t)} < T\right]}$$

dengan $\mathbf{1}[\cdot]$ adalah fungsi indikator: bernilai 1 jika kondisi terpenuhi, 0 jika tidak. Guard `is_converged` dari G.EKF memastikan $\delta_G = 0$ selama warmup EKF.

**Domain:** $v \in [0, \infty)$ mm/s, $T \in [d_{w0},\ d_{max}] = [1000,\ 4000]$ mm, $\delta_G \in \{0, 1\}$

---

### G.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $d_{obj}^{(t)}$ | Input | $[0, 4000]$ | mm | Jarak objek frame saat ini (dari Formula E) |
| $d_{obj}^{(t-1)}$ | State | $[0, 4000]$ | mm | Jarak objek frame sebelumnya (disimpan di memori) |
| $ts_{esp}^{(t)},\ ts_{esp}^{(t-1)}$ | Input | $[0, \infty)$ | ms | Timestamp ESP32 frame saat ini dan sebelumnya (dari paket Formula A) |
| $\Delta t$ | Terderivasi | $[\Delta t_{min}, \Delta t_{max}]$ | s | Interval waktu bersih setelah `sat()`: min 1 ms, max 500 ms |
| $\omega_x^{corr}$ | Input (via WebSocket) | $\mathbb{R}$ | °/s | Pitch rate terkalibrasi bias — tersedia di paket $\mathbf{p}_{imu}$ |
| $\theta$ | Input (via WebSocket) | $[-90°, +90°]$ | ° | Sudut pitch kepala dari Formula A |
| $\text{is\_converged}$ | Input (via WebSocket) | $\{0, 1\}$ | — | Flag konvergensi EKF: dihitung di ESP32 (A.EKF.5), diterima Mobile lewat paket $\mathbf{p}_{imu}$ |
| $k_{damp}$ | Dihitung di **ESP32** | $\{0.5, 1.0\}$ | — | Faktor redaman: 0.5 jika $|\omega_x^{corr}| > \omega_{x,lim}$, else 1.0 — bagian dari $v_{head\_base}$ |
| $v_{head\_base}$ | Input (via WebSocket) | $[0, \infty)$ | rad/s | **Faktor pengali IMU-murni** dihitung ESP32 (A.EKF.5): $k_{damp}\cdot|\omega_x^{corr}|\cdot\cos(\theta)\cdot\pi/180$ |
| $v_{head}^{(i)}$ | Intermediat (Mobile) | $[0, \infty)$ | mm/s | Kecepatan semu per-objek: $v_{head\_base} \times d_{obj}^{(i)}$ — satu perkalian per tracking ID |
| $v_{raw}$ | Intermediat | $[0, \infty)$ | mm/s | Kecepatan pendekatan satu frame (sebelum moving average) |
| $v_{raw}^{(t-1)}, v_{raw}^{(t-2)}$ | State | $[0, \infty)$ | mm/s | Kecepatan dua frame sebelumnya (disimpan untuk moving average) |
| $\bar{v}^{(t)}$ | Intermediat | $[0, \infty)$ | mm/s | Kecepatan pendekatan bersih setelah moving average 3-frame |
| $v$ | Intermediat | $[0, \infty)$ | mm/s | Alias $\bar{v}^{(t)}$ — kecepatan yang digunakan Formula G.3 |
| $d_{w0}$ | Konstanta | — | mm | Threshold minimum statis = 1000 mm |
| $t_r$ | Konstanta | — | s | Waktu reaksi manusia = 2 s |
| $d_{max}$ | Konstanta | — | mm | Batas maksimum threshold = 4000 mm |
| $T$ | Intermediat | $[1000, 4000]$ | mm | Threshold peringatan yang adaptif terhadap kecepatan |
| $\delta_G$ | **Output** | $\{0, 1\}$ | — | **Indikator peringatan** (1 = peringatkan, 0 = aman) |
| $\theta_{pitch,lim}$ | Konstanta | — | ° | Batas sudut pitch untuk validitas aproksimasi busur = 30° |
| $\omega_{x,lim}$ | Konstanta | — | °/s | Guard laju rotasi ekstrim = 5.0 °/s |
| $N_{warmup}$ | Konstanta | — | frame | Frame warmup EKF = 150 (≈5 detik) |
| $\varepsilon_{conv}$ | Konstanta | — | — | Threshold norma kovarians EKF = 0.10 |
| $N_{v,avg}$ | Konstanta | — | frame | Window moving average kecepatan = 3 |

---

### G.3 Cara Kerja Detail

Threshold statis selalu 1 meter akan berbahaya dalam dua skenario ekstrem:
- **Berjalan cepat 1.5 m/s:** Jarak 1 meter hanya memberi waktu 0.67 detik — lebih pendek dari waktu reaksi manusia (1.5–2.5 detik).
- **Berdiri diam:** Threshold 1 meter sudah cukup karena pengguna tidak bergerak.

Formula ini memecahkan masalah tersebut dengan membuat threshold **proporsional terhadap kecepatan**:

$$T = d_{w0} + v \cdot t_r = 1000\ \text{mm} + \bar{v}\ \text{(mm/s)} \cdot 2\ \text{s}$$

**Peran $v_{head}$ dan koreksi $\cos(\theta)$ (v8):**

Tanpa kompensasi, jika pengguna menundukkan kepala dengan kecepatan $\omega_x = 10°/s$ saat objek berada di $d_{obj} = 1500$ mm, maka perubahan jarak yang teramati:

$$\Delta d_{semu} = v_{head} \cdot \Delta t = \left(10 \cdot 1500 \cdot \frac{\pi}{180}\right) \cdot 0.033 \approx 8.7\ \text{mm per frame}$$

Ini cukup besar untuk memicu Formula G seakan-akan objek mendekati, padahal objek dan pengguna diam.

Di v7, formula $v_{head} = |\omega_x^{corr}| \cdot d_{obj} \cdot \pi/180$ tidak mempertimbangkan sudut pitch saat itu. Bayangkan pengguna sudah sangat menunduk ($\theta = 45°$) dan menggerakkan kepala dengan $\omega_x = 10°/s$: di v7, $v_{head}$ dihitung seolah kepala bergerak di bidang horizontal, padahal kamera sudah mengarah ke bawah. Gerakan pitch pada sudut ini menghasilkan perubahan jarak *horisontal yang jauh lebih kecil* karena komponen gerakan ke arah objek hanya $\cos(45°) \approx 71\%$ dari kecepatan putar penuh. V8 memperbaiki ini dengan:

$$v_{head}^{v8} = k_{damp} \cdot |\omega_x^{corr}| \cdot d_{obj} \cdot \cos(\theta) \cdot \frac{\pi}{180}$$

Koreksi ini signifikan secara praktis pada pengguna yang berjalan sambil menunduk melihat rintangan kaki ($\theta \approx 20°$–$30°$): v7 *overestimates* $v_{head}$ sebesar 6–15%, artinya kompensasi berlebihan yang membuat Formula G sedikit lebih "tumpul" dari seharusnya terhadap ancaman nyata.

**Mengapa guard EKF warmup diperlukan:**

Pada detik 0–5 setelah sistem dihidupkan, kovarians EKF $\mathbf{P}_t$ masih besar dan bias giroskop $\mathbf{b}_\omega$ belum terpelajari. Selama periode ini, $\omega_x^{corr} = \omega_x - b_{\omega x}^{(t)}$ masih mengandung bias offset yang bisa mencapai 1–3°/s. Pada jarak objek 1000 mm, bias 2°/s menghasilkan:

$$v_{head}^{bias} = 2 \cdot 1000 \cdot \frac{\pi}{180} \approx 34.9\ \text{mm/s}$$

Ini cukup untuk *mengkompensasi* kecepatan pendekatan nyata ~35 mm/s — membuat Formula G buta terhadap ancaman yang benar-benar ada. Setelah EKF konvergen, bias tereliminasi dan $v_{head}$ akurat. Guard `is_converged` memastikan Formula G tidak dieksekusi sebelum kondisi ini tercapai.

**Peran moving average $\bar{v}$ (v8):**

Ilustrasi dampak jitter tanpa dan dengan moving average, skenario pengguna berjalan stabil 500 mm/s menuju objek statis pada 2500 mm:

```
Frame  Δt (ms)  v_raw (mm/s)  v̄ (3-avg)  T_raw (mm)  T_avg (mm)
  1      33.0       500          500         2000        2000
  2      28.0       591          530         2183        2060     ← T berfluktuasi
  3      38.0       434          508         1869        2017        tanpa avg
  4      34.0       485          503         1971        2007
  5      31.0       532          484         2065        1969
```

Dengan moving average, $T$ berfluktuasi di sekitar 2000–2060 mm (stabil). Tanpa moving average, $T$ berfluktuasi antara 1869–2183 mm — rentang 314 mm yang pada kondisi borderline bisa menyebabkan $\delta_G$ berpindah-pindah 0 dan 1 antar frame (*flickering*), menghasilkan TTS yang terputus-putus dan membingungkan pengguna.

**Contoh numerik lengkap (v8):**

| Skenario | $\theta$ | $\omega_x^{corr}$ | $k_{damp}$ | $v_{head}$ | $v_{raw}$ | $\bar{v}$ | $T$ | Objek di 2.5 m | Peringatan? |
|---|---|---|---|---|---|---|---|---|---|
| Diam, kepala diam | 0° | 0 °/s | 1.0 | 0 | 0 | 0 mm/s | 1000 mm | 2500 ≥ 1000 | ❌ |
| Diam, kepala menunduk 10°/s (θ=0°) | 0° | 10 °/s | 0.5 | 131 mm/s | 0 (clamped) | 0 mm/s | 1000 mm | 2500 ≥ 1000 | ❌ ✅ |
| Diam, kepala menunduk 10°/s (θ=30°) | 30° | 10 °/s | 0.5 | 113 mm/s | 0 (clamped) | 0 mm/s | 1000 mm | 2500 ≥ 1000 | ❌ ✅ |
| Jalan cepat 1500 mm/s, kepala diam | 0° | 0 °/s | 1.0 | 0 | 1500 | 1500 mm/s | 4000 mm | 2500 < 4000 | ✅ |
| Jalan 500 mm/s, menunduk 20° | 20° | 2 °/s | 1.0 | 31 mm/s | 469 | ~490 mm/s | ~1980 mm | 2500 ≥ 1980 | ❌ |
| EKF belum konvergen ($t<150$ frame) | — | — | — | — | — | — | 1000 mm | (bypass) | ❌ (warmup) |

---

### G.4 Asal Usul Formula

**Konsep:** Kinematic safety distance dan adaptive threshold berdasarkan reaction time.

**Landasan 1 — Kinematika dasar:** $d = v \cdot t$ adalah persamaan gerak lurus beraturan dari **Isaac Newton** (1687, *Principia Mathematica*). Formula $T = d_{w0} + v \cdot t_r$ adalah aplikasi langsung dengan dua komponen:

$$T = \underbrace{d_{w0}}_{\text{buffer minimum}} + \underbrace{v \cdot t_r}_{\text{jarak tempuh selama reaksi}}$$

**Landasan 2 — Waktu reaksi manusia ($t_r = 2$ s):** Konstanta ini berasal dari penelitian *pedestrian safety* yang dikompilasi oleh **World Health Organization (WHO)** dalam laporan *Global Status Report on Road Safety* (2018). WHO merekomendasikan 1.5–2.5 detik sebagai rentang waktu reaksi pejalan kaki dewasa dalam kondisi waspada (bukan terkejut mendadak).

**Landasan 3 — Adaptive threshold dalam autonomous driving:** Konsep serupa digunakan dalam sistem ADAS (Advanced Driver Assistance Systems). Standar **ISO 22179:2009** (*FSRA — Full Speed Range Adaptive Cruise Control*) mendefinisikan *time headway* sebagai parameter adaptif terhadap kecepatan. Dalam konteks kendaraan otonom, formula serupa dikembangkan oleh **Shalev-Shwartz et al.** dalam *"Formal Models of Cautious Driving"* (2017, arXiv:1708.06374).

**Landasan 4 — Koreksi proyeksi horizontal $\cos(\theta)$ (BARU v8):**

Koreksi $\cos(\theta)$ pada formula $v_{head}$ berasal dari geometri dasar proyeksi vektor. Kecepatan sudut $\omega_x$ menghasilkan kecepatan linear tangensial $v_{tan} = \omega_x \cdot d_{obj}$ yang mengarah tegak lurus radius — yaitu **tangensial terhadap busur pandang**. Namun yang relevan untuk perubahan jarak radial adalah komponen *radial* dari kecepatan ini. Untuk sensor yang mengarah ke bawah dengan sudut $\theta$ dari horizontal, komponen radial ke arah horizontal (mendekati objek) adalah:

$$v_{radial} = v_{tan} \cdot \cos(\theta) = \omega_x \cdot d_{obj} \cdot \cos(\theta)$$

Ini merupakan aplikasi langsung dari **dekomposisi vektor ortogonal** yang dirumuskan oleh **René Descartes** (1637) dalam *La Géométrie* dan dikembangkan lebih lanjut oleh **Leonhard Euler** (1748) dalam *Introductio in Analysin Infinitorum*. Dalam mekanika rotasi, proyeksi komponen ini setara dengan **Teorema Sumbu Paralel** (dikenal juga sebagai *Huygens-Steiner theorem*) yang menyatakan bahwa momen inersia sistem berubah bergantung pada sumbu rotasi aktif.

**Landasan 5 — Moving average sebagai low-pass filter (BARU v8):**

Moving average (MA) adalah *finite impulse response (FIR) filter* orde-1 paling sederhana. Dalam domain frekuensi, MA dengan window $N$ frame memiliki fungsi transfer:

$$H(z) = \frac{1}{N} \cdot \frac{1 - z^{-N}}{1 - z^{-1}}$$

Ini adalah *low-pass filter* yang meredam frekuensi tinggi (noise/jitter) sambil mempertahankan komponen frekuensi rendah (gerakan nyata). Teori MA filter dipopulerkan dalam konteks signal processing oleh **Claude Shannon** (1948, *A Mathematical Theory of Communication*) dan diaplikasikan pada sistem kontrol real-time oleh **Norbert Wiener** (*Cybernetics*, 1948). Untuk data kecepatan berbasis ToF di sistem kendali real-time, penggunaan MA 3-frame adalah praktik standar yang terdokumentasi dalam **IEEE Standard for Sensor Fusion** (IEEE Std 1817-2015).

**Landasan 6 — Guard konvergensi EKF (BARU v8):**

Konsep *convergence guard* atau *initialization guard* pada filter statistik merupakan praktik standar dalam rekayasa sistem kendali. **Grewal & Andrews** dalam *Kalman Filtering: Theory and Practice Using MATLAB* (2015, edisi ke-4) secara eksplisit merekomendasikan periode "spin-up" di mana output filter tidak digunakan sampai kovarians $\mathbf{P}_t$ mencapai nilai stasioner. Standar **MIL-STD-882E** (*System Safety*, DoD 2012) mengklasifikasikan output filter sebelum konvergensi sebagai "unvalidated data" yang tidak boleh digunakan untuk safety-critical decisions.

**Penerapan dalam sistem ini:** Koreksi $\cos(\theta)$ paling signifikan pada pengguna yang terbiasa berjalan dengan postur menunduk, yang umum pada tunanetra yang menggunakan tongkat panjang. Guard konvergensi EKF memerlukan pengujian empiris untuk memvalidasi apakah $N_{warmup} = 150$ frame cukup pada hardware MPU-6050 GY-521 di berbagai kondisi suhu. Threshold $\varepsilon_{conv} = 0.10$ perlu dikalibrasi terhadap distribusi nilai $\|\mathbf{P}_t\|_F$ yang sesungguhnya pada fase operasi normal. Moving average 3-frame harus dievaluasi ulang jika frame rate WebSocket berubah di bawah 20 Hz — pada frame rate lebih rendah, window yang lebih kecil ($N=2$) mungkin lebih sesuai untuk menjaga latensi respons.

---

### G.5 Aliran Variabel

```
═══════════════════════════════════════════════════════
              [ESP32-S3: A.EKF.5 — per frame]
═══════════════════════════════════════════════════════
[EKF State: θ, ω_x^corr, P_t, t_frame]
    │
    ├── k_damp = 0.5 if |ω_x^corr| > 5°/s else 1.0
    ├── v_head_base = k_damp × |ω_x^corr| × cos(θ×π/180) × π/180
    └── is_converged = 𝟏[t_frame ≥ 150] OR 𝟏[‖P_t‖_F < 0.10]
    │
    [Paket WebSocket: p_imu — 9 field]
    (θ, φ, ω_x^corr, ω_y^corr, ω_z^corr, ‖a_lin‖, ts_esp,
     v_head_base, is_converged)
    │
    └──→ [WiFi WebSocket ke Smartphone]
═══════════════════════════════════════════════════════
              [Mobile: Formula G — per-objek YOLO]
═══════════════════════════════════════════════════════
[Formula E, frame (t)]     [Formula E, frame (t-1)]   [Paket WebSocket]
    │                               │                        │
    └──→ d_obj^(i,t) (mm) d_obj^(i,t-1) (mm, memori)        ├──→ v_head_base  ← BARU v8.1
              │                     │                        ├──→ is_converged ← BARU v8.1
              │                     │                        └──→ ts_esp (ms)
              │                     │                                 │
              │                     │           ┌────────────────────┤
              │                     │           ▼                    ▼
              │                     │   [G.EKF: is_converged?]  [G.0: Δt dari ts_esp]
              │                     │   (dari paket WebSocket)  sat(Δt_raw, 0.001, 0.5)
              │                     │         │                         │
              │                     │         └── FALSE: δ_G=0, skip   │
              │                     │                                   │
              │                     └──────────┬────────────────────────┘
              │                                ▼
              │     [G.1b: v_head^(i) = v_head_base × d_obj^(i)]   ← BARU v8.1
              │             (satu perkalian skalar per-objek)
              │                                │
              │             [G.2: v_raw^(i) = max(Δd^(i)/Δt, 0) - v_head^(i)]
              │             v_raw := max(v_raw, 0)
              │                                │
              │             [G.2b: Moving Average v̄ 3-frame]
              │             v̄ = (v_raw^t + v_raw^(t-1) + v_raw^(t-2)) / 3
              │                                │
              │             [G.3: T = min(1000 + v̄ × 2, 4000)]
              │                                │
              └──────────────────────────────→ │
                                    [G.4: δ_G = 𝟏[d_obj^(i,t) < T]]
                                               │
                                               └──→ δ_G ∈ {0, 1}
                                                         │
                                                [TTS jika δ_G = 1]
                                                "Peringatan, jarak X meter"
```

---


## H. Formulasi Peringatan Objek Statis (One-Shot Alert)

**Referensi:** Flowchart 3c, SD-3a

> **[DIPERBARUI v3]** Kondisi reset flag diubah dari `Δd ≠ 0` menjadi `|Δd| > ε_noise` untuk menghindari reset yang tidak diinginkan akibat noise sensor ToF (±15–30 mm).

---

### H.1 Notasi

$$\boxed{\delta_H = \mathbf{1}\!\left[d_{obj}^{(t)} < d_{w0}\right] \cdot \mathbf{1}\!\left[f_{obj} = 0\right]}$$

**Mekanisme update flag setelah peringatan:**

$$f_{obj} := \begin{cases} 1, & \text{jika } \delta_H = 1 \quad \text{(set: sudah diperingatkan)} \\ 0, & \text{jika objek hilang dari frame YOLO, atau } |\Delta d| > \varepsilon_{noise} \quad \text{(reset)} \end{cases}$$

dengan $\Delta d = d_{obj}^{(t-1)} - d_{obj}^{(t)}$ dan $\varepsilon_{noise} = 30$ mm.

**Domain:** $\delta_H \in \{0, 1\}$, $f_{obj} \in \{0, 1\}$

### H.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $d_{obj}^{(t)}$ | Input | $[0, 4000]$ | mm | Jarak objek frame saat ini dari Formula E |
| $d_{w0}$ | Konstanta | — | mm | Threshold minimum statis = 1000 mm |
| $\varepsilon_{noise}$ | Konstanta | — | mm | Noise floor ToF = 30 mm; perubahan di bawah ini dianggap noise |
| $f_{obj}$ | State | $\{0, 1\}$ | — | Flag per-objek: 0 = belum diperingatkan, 1 = sudah |
| $\delta_H$ | **Output** | $\{0, 1\}$ | — | **Indikator peringatan** (1 = bunyikan TTS sekali) |

---

### H.3 Cara Kerja Detail

Formula G berjalan saat ada gerakan. Formula H menangani kasus **kedua diam** — pengguna berdiri dan objek tidak bergerak. Tanpa mekanisme ini, sistem akan terus-menerus membunyikan TTS "Objek dekat... Objek dekat..." selama kondisi tidak berubah.

**Mekanisme two-condition AND:**
1. $d_{obj}^{(t)} < d_{w0}$: objek memang berada dalam jarak berbahaya
2. $f_{obj} = 0$: belum pernah diperingatkan untuk objek ini

Hanya jika **keduanya** terpenuhi, TTS berbunyi. Setelahnya, $f_{obj}$ di-set ke 1 — peringatan terkunci hingga kondisi berubah.

**Reset flag terjadi ketika:**
- Objek menghilang dari frame YOLO (tracking ID tidak lagi ada)
- $|\Delta d| > \varepsilon_{noise}$: perubahan jarak melebihi noise floor → salah satu bergerak → sistem beralih ke Formula G

**Mengapa $|\Delta d| > \varepsilon_{noise}$ menggantikan $\Delta d \neq 0$?**

Sensor VL53L5CX memiliki noise tipikal ±15–30 mm (dari datasheet). Kondisi $\Delta d \neq 0$ hampir selalu terpenuhi meskipun objek benar-benar diam, sehingga flag akan di-reset setiap frame — membuat one-shot alert menjadi tidak efektif. Dengan threshold $\varepsilon_{noise} = 30$ mm, flag hanya di-reset saat ada pergerakan yang nyata, bukan fluktuasi noise.

---

### H.4 Asal Usul Formula

**Konsep:** Debouncing dan one-shot trigger dalam sistem kendali.

**Penemu:** Konsep *one-shot* (monostable multivibrator) berasal dari elektronika analog — pertama kali diimplementasikan dalam sirkuit oleh **William Eccles dan F.W. Jordan** (1919) yang menemukan flip-flop bistable. Monostable (satu-shot) adalah variannya.

Dalam **software engineering**, pola ini diformalkan oleh **David Harel** dalam makalah *"Statecharts: A Visual Formalism for Complex Systems"* (Science of Computer Programming, 1987). Logika Harel untuk transisi one-shot:

$$S_0 \xrightarrow{[\text{cond}]/\text{action}} S_1 \xrightarrow{[\text{reset\_cond}]} S_0$$

yang dalam Formula H menjadi:

$$S_0 \xrightarrow{\left[d_{obj} < d_{w0}\ \wedge\ f_{obj}=0\right]/\text{TTS}} S_1 \xrightarrow{\left[|\Delta d| > \varepsilon_{noise}\ \vee\ \text{obj hilang}\right]} S_0$$

Dalam konteks **embedded systems dan IoT**, pola ini adalah standar universal untuk mencegah *alert fatigue* — dijelaskan dalam IEC 62682:2014 (*Management of Alarms in the Process Industries*) yang mendefinisikan prinsip "alarm hanya berbunyi ketika kondisi pertama kali muncul, bukan terus-menerus".

**Penerapan dalam sistem ini:** Flag $f_{obj}$ dikelola per tracking ID YOLO. Jika YOLO menggunakan ByteTrack atau BoT-SORT untuk multi-object tracking, setiap ID unik memiliki flag sendiri — sehingga sistem bisa membedakan "tiang A sudah diperingatkan" dari "orang B belum diperingatkan".

**Known Limitation — Tracking ID Loss:**

Saat YOLO kehilangan tracking satu frame (tracking ID hilang lalu muncul kembali dengan ID baru karena oklusi atau confidence drop sesaat), flag $f_{obj}$ untuk ID lama terhapus dari memori dan ID baru mulai dari $f_{obj} = 0$ — peringatan akan berbunyi kembali meskipun objek secara fisik sama. Ini adalah perilaku yang **dapat diterima untuk MVP** karena lebih aman (false positive) daripada melewatkan peringatan (false negative). Untuk post-MVP, solusinya adalah menambahkan IoU-based re-identification: jika bounding box ID baru overlap $> 70\%$ dengan ID lama yang hilang di frame sebelumnya, warisi flag ID lama.

---

### H.5 Aliran Variabel

```
[Formula E]         [Memori Sistem]
    │                     │
    └──→ d_obj^(t) (mm)   └──→ f_obj ∈ {0, 1}
              │                 │
              └──────┬──────────┘
                     ▼
              [Formula H]
              δ_H = 𝟏[d_obj < 1000] · 𝟏[f_obj = 0]
                     │
              ┌──────┴──────────┐
              ▼                 ▼
        δ_H = 1             δ_H = 0
              │                 │
    TTS: "Objek dekat"      Diam
    f_obj := 1
              │
    Reset jika |Δd| > 30mm atau objek hilang
```

---

## I. Formulasi Delta Bounding Box (Time-to-Contact Proxy)

**Referensi:** Flowchart 3d, SD-3b

> **[DIPERBARUI v7]** Ditambahkan moving average 3-frame pada area bounding box untuk meredam fluktuasi YOLO antar frame.
>
> **[DIPERBARUI v8]** Arsitektur diubah dari single-dimension (area saja) menjadi **TTC Multi-Dimensional** tiga fitur: (1) pertumbuhan area, (2) stabilitas aspect ratio, dan (3) validasi konsistensi jarak ToF. Penambahan bobot tipe objek YOLO menyesuaikan urgensi alert dengan massa/kecepatan tipikal objek. Ini menyelesaikan masalah *false positive* dari objek yang berputar atau bergerak miring, yang pada v7 memiliki $\Delta A$ besar tetapi bukan merupakan ancaman pendekatan langsung.

---

### I.1 Notasi

**I.1 — Area bounding box (tidak berubah):**

$$A^{(t)} = \left(x_{max}^{(t)} - x_{min}^{(t)}\right) \cdot \left(y_{max}^{(t)} - y_{min}^{(t)}\right)$$

**I.1b — Moving average area 3-frame (tidak berubah):**

$$\boxed{\bar{A}^{(t)} = \frac{A^{(t)} + A^{(t-1)} + A^{(t-2)}}{3}}$$

Untuk frame ke-1 dan ke-2: $\bar{A}^{(1)} = A^{(1)}$, $\bar{A}^{(2)} = (A^{(1)}+A^{(2)})/2$.

**I.2 — Laju pertumbuhan area $\Delta A$ (tidak berubah):**

$$\Delta A = \begin{cases} \dfrac{\bar{A}^{(t)} - \bar{A}^{(t-1)}}{\bar{A}^{(t-1)}} \times 100\%, & \bar{A}^{(t-1)} > 0 \\ 0, & \bar{A}^{(t-1)} = 0 \end{cases}$$

**I.2b — Sub-skor area (BARU v8):**

Daripada threshold biner 20%, area pertumbuhan kini dikonversi ke skor kontinu $[0, 1]$:

$$\boxed{\text{area\_score} = \begin{cases} 0.0, & \Delta A < 0 \\ \dfrac{\Delta A}{\Delta A_{norm}}, & 0 \le \Delta A \le \Delta A_{norm} \\ 1.0, & \Delta A > \Delta A_{norm} \end{cases}}$$

dengan $\Delta A_{norm} = 50\%$ (pertumbuhan 50\% menghasilkan skor penuh 1.0).

**I.3 — Aspect ratio bounding box dan sub-skor stabilitas (BARU v8):**

Aspect ratio $\lambda = w/h$ mencerminkan orientasi tampak objek di kamera. Jika objek benar-benar mendekati secara frontal, $\lambda$ tetap stabil karena proyeksi perspektif mempertahankan rasio dimensi. Jika objek berputar atau bergerak miring, $\lambda$ berubah signifikan — sinyal bahwa pertumbuhan area bukan disebabkan pendekatan langsung.

**Guard degenerate bounding box:**

$$\text{if } y_{max}^{(t)} = y_{min}^{(t)}: \quad \lambda^{(t)} := 1.0,\ \text{ar\_score} := 1.0 \quad \text{(skip I.3)}$$

$$\lambda^{(t)} = \frac{x_{max}^{(t)} - x_{min}^{(t)}}{y_{max}^{(t)} - y_{min}^{(t)}}$$

$$\Delta\lambda = \frac{|\lambda^{(t)} - \lambda^{(t-1)}|}{\lambda^{(t-1)}} \times 100\%$$

$$\boxed{\text{ar\_score} = \begin{cases} 1.0 - \dfrac{\Delta\lambda}{\Delta\lambda_{th}}, & \Delta\lambda \le \Delta\lambda_{th} \\ 0.0, & \Delta\lambda > \Delta\lambda_{th} \end{cases}}$$

dengan $\Delta\lambda_{th} = 20\%$. Skor = 1.0 berarti aspect ratio sangat stabil (pendekatan frontal murni); skor = 0.0 berarti aspect ratio berubah sangat besar (rotasi/miring signifikan).

**I.4 — Sub-skor validasi jarak ToF (BARU v8):**

Secara teori perspektif, area proyeksi berbanding terbalik dengan kuadrat jarak: $A \propto 1/d^2$. Jika $A$ membesar (objek mendekat), maka $d$ harus berkurang. Jika $A$ membesar tetapi $d_{obj}$ dari Formula E tetap atau malah membesar, terdapat inkonsistensi — kemungkinan besar perubahan area disebabkan rotasi atau perubahan pose objek, bukan pendekatan nyata.

$$\Delta d_{I} = d_{obj}^{(t-1)} - d_{obj}^{(t)}$$

$$\boxed{\text{dist\_score} = \begin{cases} 1.0, & \Delta d_{I} > \varepsilon_{noise}\ \wedge\ \Delta A > 0 \\[4pt] 0.5, & |\Delta d_{I}| \le \varepsilon_{noise}\ \wedge\ \Delta A > 0 \\[4pt] 0.0, & \Delta d_{I} < -\varepsilon_{noise}\ \vee\ \Delta A \le 0 \end{cases}}$$

Kasus 0.5 ("ambiguous") terjadi ketika area membesar tetapi jarak ToF tidak berubah secara statistik — kemungkinan 50/50 antara pendekatan nyata dan rotasi. Kasus ini diteruskan ke Formula G sebagai *probable* (bukan urgent).

**I.5 — TTC Score gabungan (BARU v8):**

$$\boxed{\text{TTC\_score}^{(i)} = w_A \cdot \text{area\_score} + w_{AR} \cdot \text{ar\_score} + w_{dist} \cdot \text{dist\_score}}$$

dengan bobot normalisasi $w_A + w_{AR} + w_{dist} = 1.0$ (nilai default: $w_A = 0.50$, $w_{AR} = 0.25$, $w_{dist} = 0.25$).

**I.6 — Bobot tipe objek YOLO (BARU v8):**

Massa dan kecepatan tipikal objek berbeda signifikan. Mobil 1.5 ton mendekat dengan kecepatan 60 km/h memiliki impak yang berbeda secara fisik dibanding sepeda yang mendekat 15 km/h, meskipun TTC visual keduanya sama. Bobot kelas YOLO digunakan sebagai *multiplier* untuk menyesuaikan urgensi:

$$\text{TTC\_weighted}^{(i)} = \min\!\left(\text{TTC\_score}^{(i)} \times m_{\text{class}^{(i)}},\ 1.0\right)$$

| Kelas YOLO | $m_\text{class}$ | Alasan |
|---|---|---|
| `bus`, `truck` | 1.6 | Sangat berat, jarak pengereman sangat panjang |
| `car` | 1.5 | Kendaraan berat, tidak bisa berhenti mendadak |
| `motorcycle` | 1.2 | Cepat dan sulit diprediksi |
| `person` | 1.0 | Baseline pejalan kaki |
| `bicycle` | 0.8 | Relatif lambat, lebih mudah dihindari |
| (tidak dikenal) | 1.0 | Default ke baseline |

**I.7 — Kondisi peringatan berbasis pool (BARU v8):**

$$\boxed{\delta_I^{\text{pool}} = \begin{cases}
\text{IMMINENT},  & \text{TTC\_weighted}^{(i)} > TTC_{HIGH} \\
\text{PROBABLE},  & TTC_{MID} < \text{TTC\_weighted}^{(i)} \le TTC_{HIGH} \\
\text{POSSIBLE},  & \text{TTC\_weighted}^{(i)} \le TTC_{MID}
\end{cases}}$$

- **IMMINENT** ($>0.75$): Override Formula G — paksa alert terlepas dari apakah $d_{obj} < T$. Ini penting untuk kendaraan yang mendekat dari luar jangkauan ToF 4 m (Formula I aktif sejak $>4$ m, Formula G baru aktif saat $\le 4$ m).
- **PROBABLE** ($0.40$–$0.75$): Kirim ke Formula G sebagai *confirmation signal*. Formula G membuat keputusan final berdasarkan $d_{obj}$ dan $T$.
- **POSSIBLE** ($\le 0.40$): Silent — catat untuk analisis, tidak menghasilkan TTS.

**Domain:** $\text{TTC\_score}^{(i)}, \text{TTC\_weighted}^{(i)} \in [0, 1]$, $\delta_I^{\text{pool}} \in \{\text{IMMINENT, PROBABLE, POSSIBLE}\}$

---

### I.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $x_{min}^{(t)}, x_{max}^{(t)}, y_{min}^{(t)}, y_{max}^{(t)}$ | Input | $[0, 639/479]$ | px | Koordinat bounding box frame saat ini dari YOLO |
| $A^{(t)}$ | Intermediat | $(0, 307200]$ | px² | Area bounding box frame saat ini |
| $\bar{A}^{(t)}$ | Intermediat | $(0, 307200]$ | px² | Moving average area 3-frame |
| $\Delta A$ | Intermediat | $(-100\%, +\infty)$ | % | Laju pertumbuhan area relatif antar frame |
| $\text{area\_score}$ | Intermediat | $[0, 1]$ | — | Sub-skor pertumbuhan area, ternormalisasi ke 50% |
| $\lambda^{(t)}$ | Intermediat | $(0, \infty)$ | — | Aspect ratio bounding box frame saat ini |
| $\Delta\lambda$ | Intermediat | $[0, \infty)$ | % | Perubahan aspect ratio relatif antar frame |
| $\text{ar\_score}$ | Intermediat | $[0, 1]$ | — | Sub-skor stabilitas aspect ratio (1.0 = frontal, 0.0 = rotasi) |
| $\Delta d_I$ | Intermediat | $(-4000, 4000)$ | mm | Perubahan jarak ToF untuk validasi konsistensi area |
| $\text{dist\_score}$ | Intermediat | $\{0.0, 0.5, 1.0\}$ | — | Sub-skor validasi jarak-area |
| $\text{TTC\_score}^{(i)}$ | Intermediat | $[0, 1]$ | — | TTC score gabungan sebelum class weighting |
| $m_{\text{class}}$ | Konstanta | $[0.8, 1.6]$ | — | Multiplier bobot kelas YOLO |
| $\text{TTC\_weighted}^{(i)}$ | **Output** | $[0, 1]$ | — | **TTC score final setelah class weighting** |
| $\delta_I^{\text{pool}}$ | **Output** | $\{\text{IMMINENT, PROBABLE, POSSIBLE}\}$ | — | **Pool peringatan TTC** |
| $\Delta A_{th}$ | Konstanta | — | % | Threshold lama (v7) = 20%; digantikan oleh $\Delta A_{norm} = 50\%$ di v8 |
| $\Delta A_{norm}$ | Konstanta | — | % | Normalisasi area score = 50% |
| $\Delta\lambda_{th}$ | Konstanta | — | % | Threshold aspect ratio stability = 20% |
| $TTC_{HIGH}, TTC_{MID}$ | Konstanta | — | — | Threshold pool: 0.75 dan 0.40 |

---

### I.3 Cara Kerja Detail

Sensor ToF VL53L5CX memiliki jangkauan maksimum 4000 mm. Kendaraan bermotor di jalan bisa mendekat dari jarak 20+ meter dengan kecepatan 60 km/h — pada saat masuk ke jangkauan ToF, waktu tabrakan sudah kurang dari 0.24 detik (jauh di bawah waktu reaksi 2 detik). Formula I adalah lapisan deteksi **jarak jauh berbasis optik** yang aktif *sebelum* Formula G bisa bekerja.

**Landasan geometri perspektif:**

Berdasarkan geometri perspektif pin-hole camera, luas proyeksi objek bola berjari-jari $r$ pada jarak $d$ adalah:

$$A \propto \left(\frac{r \cdot f}{d}\right)^2 = \frac{r^2 f^2}{d^2}$$

di mana $f$ adalah panjang fokus. Turunan logaritmik memberikan hubungan antara laju perubahan area dan laju perubahan jarak:

$$\frac{\dot{A}}{A} = -2 \frac{\dot{d}}{d}$$

Ini adalah dasar dari *tau theory* David N. Lee (1976): time-to-contact $\tau = d/|\dot{d}| = 2A/\dot{A}$, atau dalam diskret: $\tau \approx 2/(\Delta A / A \cdot \Delta t^{-1}) = 2A\Delta t / \Delta A$.

**Mengapa aspect ratio diperlukan (v8):**

Ketika sebuah motor berputar 90° di tempat, tampak luasnya bisa bertambah hingga 3× karena dari tampak samping menjadi tampak depan. Ini menghasilkan $\Delta A \gg 20\%$ yang memicu alert palsu di v7. Namun aspect ratio $\lambda = w/h$ berubah dramatis — dari nilai tinggi (kendaraan tampak samping panjang) ke nilai rendah (tampak depan lebar). Perubahan $\Delta\lambda > 20\%$ terdeteksi oleh sub-skor aspect ratio dan menurunkan TTC\_score secara proporsional, mengeliminasi false positive tersebut.

**Contoh numerik: v7 vs v8 pada motor berputar:**

*Frame t-1:* Motor tampak samping, bbox = 120×60 px, $A_{t-1} = 7200$ px², $\lambda_{t-1} = 2.0$, $d_{t-1} = 3000$ mm

*Frame t:* Motor berputar, bbox = 60×90 px, $A_t = 5400$ px², $\lambda_t = 0.67$

$$\Delta A = \frac{5400 - 7200}{7200} \times 100\% = -25\%$$
$$\text{area\_score} = 0.0 \quad (\Delta A < 0)$$
$$\text{TTC\_weighted} \approx 0.0 \to \text{POSSIBLE} \quad \text{(tidak ada alert)}$$

Dengan v7, frame berikutnya saat motor mulai mendekat ($A$ membesar ke 8000 px²):

$$\Delta A = \frac{8000 - 5400}{5400} \times 100\% = 48.1\% > 20\% \to \delta_I = 1 \quad \text{TRUE}$$

Namun $\Delta\lambda = |1.33 - 0.67|/0.67 = 98.5\% > 20\%$ (masih berputar!):

$$\text{ar\_score} = 0.0, \quad \text{dist\_score} = 0.5$$
$$\text{TTC\_score} = 0.5 \times 0.96 + 0.25 \times 0.0 + 0.25 \times 0.5 = 0.605$$
$$\text{TTC\_weighted} = 0.605 \times 1.2 = 0.726 \to \text{PROBABLE (bukan IMMINENT)} \quad \checkmark$$

V8 menghasilkan level yang lebih akurat — motor berputar di tempat tidak diklasifikasikan sebagai ancaman *imminent*, tetapi juga tidak diabaikan sepenuhnya.

**Integrasi dengan Formula G:**

Formula I dan G saling melengkapi dengan jangkauan berbeda:
- **Formula I**: aktif dari $>4000$ mm (ToF blind zone), bergantung pada kamera YOLO
- **Formula G**: aktif dari $\le 4000$ mm, bergantung pada sensor ToF

Objek dengan TTC\_weighted = IMMINENT yang belum masuk jangkauan ToF langsung menghasilkan alert, tidak menunggu Formula G. Ini menutup *gap* deteksi kritis antara jarak 4–20 meter.

---

### I.4 Asal Usul Formula

**Konsep:** Time-to-contact (TTC) dari optik ekologi.

**Penemu:** Teori ini dikembangkan oleh **David N. Lee** (psikolog ekologi, University of Edinburgh) dalam makalah legendaris *"A Theory of Visual Control of Braking Based on Information about Time-to-Collision"* (Perception, 1976). Lee menunjukkan bahwa sistem visual biologis menggunakan **tau** ($\tau$) untuk mengestimasi time-to-contact:

$$\tau = \frac{\theta}{\dot{\theta}}$$

di mana $\theta$ adalah ukuran sudut objek di retina dan $\dot{\theta}$ adalah laju perubahannya. Karena area proyeksi $A \propto \theta^2$, dan laju pertumbuhan $\dot{A}/A = 2\dot{\theta}/\theta$, maka:

$$\tau = \frac{2A}{\dot{A}} = \frac{2}{\dot{A}/A}$$

$\Delta A / A$ yang digunakan Formula I adalah aproksimasi diskret dari $\dot{A}/A$, sehingga **$\Delta A$ besar berarti $\tau$ kecil** — tabrakan akan segera terjadi.

**Konsep aspect ratio stability (v8):** Invariansi perspektif untuk pendekatan frontal adalah konsekuensi dari transformasi proyektif. **Hartley & Zisserman** dalam *Multiple View Geometry in Computer Vision* (2003) membuktikan bahwa rasio dimensi pada proyeksi frontal dipertahankan di bawah transformasi skala murni. Perubahan aspect ratio yang signifikan mengindikasikan transformasi non-skalar (rotasi, gerak miring) — bukan pendekatan lurus.

**Penerapan dalam sistem ini:** Formula I.v2 adalah *lightweight multi-dimensional TTC proxy* yang tetap berjalan dalam O(1) per objek. Moving average 3-frame dipertahankan dari v7 untuk stabilitas YOLO noise. Bobot kelas $m_\text{class}$ harus divalidasi empiris — nilai di atas didasarkan pada massa dan kecepatan rata-rata tipikal kendaraan perkotaan Indonesia.

---

### I.5 Aliran Variabel

```
[YOLOv11, frame (t)]                [Memori, frame (t-1), (t-2)]
    │                                           │
    ├──→ x_min, x_max, y_min, y_max             └──→ Ā^(t-1), Ā^(t-2), λ^(t-1)
    └──→ class_label (car/person/...)           └──→ d_obj^(t-1) (dari Formula E)
              │                                            │
              │                                            │
              ▼                                            │
    A^(t) = (x_max-x_min)×(y_max-y_min)                   │
    λ^(t) = (x_max-x_min)/(y_max-y_min)                   │
              │                                            │
              └──────────────────┬────────────────────────┘
                                 ▼
                    [Formula I.1b: Moving Average]
                    Ā^(t) = (A^t + A^(t-1) + A^(t-2)) / 3
                                 │
                    [Formula I.2: ΔA = (Ā^t - Ā^(t-1)) / Ā^(t-1)]
                    [Formula I.2b: area_score]
                    [Formula I.3: Δλ, ar_score]
                    [Formula I.4: Δd_I, dist_score dari Formula E]
                                 │
                    [Formula I.5: TTC_score = Σ w_k · score_k]
                    [Formula I.6: TTC_weighted × m_class]
                    [Formula I.7: Pool → IMMINENT/PROBABLE/POSSIBLE]
                                 │
                    ┌────────────┼────────────────┐
                    ▼            ▼                 ▼
               IMMINENT      PROBABLE           POSSIBLE
            Override G+H   Feed ke G+H          Silent
            TTS: urgent    TTS: normal          (log only)
```

## J. Formulasi Deteksi Anomali Medan

**Referensi:** Flowchart 3e, SD-3c

> **[DIPERBARUI v3]** $\mathcal{R}_{low}$ dan $\mathcal{R}_{mid}$ kini dinamis berdasarkan $\theta$ dari MPU-6050. Ditambahkan guard $\bar{z}_{mid} < d_{guard}$ untuk mencegah false positive di ruang terbuka.
>
> **[DIPERBARUI v7]** Hysteresis dimasukkan ke notasi formal; guard kontaminasi objek berdiri $d_{cont}$ ditambahkan.
>
> **[DIPERBARUI v8]** Model diperluas dari dua zona vertikal menjadi **empat zona** ($z_{high}$, $z_{mid}$, $z_{low}$, $z_{ultra}$); enam fitur spasial-temporal diekstrak; klasifikasi output diperluas dari binary menjadi **lima tipe terrain**: STAIR\_DOWN, STAIR\_UP, HOLE, RAMP, SAFE; ditambahkan estimasi kedalaman/ketinggian anomali dalam mm; penambahan deteksi arah spasial kompatibel dengan sistem jam Formula C; confidence scoring per deteksi memungkinkan routing bertingkat ke Formula G/H.

---

### J.1 Notasi

**J.0 — Baris pusat empat zona terrain ($\theta$-aware):**

Model v8 menggunakan empat zona vertikal independen untuk mendapatkan gambaran terrain yang jauh lebih kaya. Setiap zona merujuk ke sudut pandang berbeda dari sensor ToF:

$$r_{high}(\theta) = \text{sat}\!\left(\text{round}\!\left(1.0 + \frac{\theta}{\delta\theta}\right),\ 0,\ 2\right)$$

$$r_{mid}(\theta) = \text{sat}\!\left(\text{round}\!\left(3.5 + \frac{\theta}{\delta\theta}\right),\ 3,\ 4\right)$$

$$r_{low}(\theta) = \text{sat}\!\left(\text{round}\!\left(5.5 + \frac{\theta}{\delta\theta}\right),\ 5,\ 5\right)$$

$$r_{ultra}(\theta) = \text{sat}\!\left(\text{round}\!\left(6.5 + \frac{\theta}{\delta\theta}\right),\ 6,\ 7\right)$$

Guard non-overlap: baris zona tidak boleh tumpang-tindih. Jika hasil round menghasilkan baris yang sama dengan zona di atasnya, shift +1 secara paksa.

**J.1 — Rata-rata jarak empat zona vertikal:**

$$\bar{z}_{high} = \frac{1}{N_{col}} \sum_{j=0}^{N_{col}-1} z_{r_{high}(\theta),\, j}$$

$$\bar{z}_{mid} = \frac{1}{2} \sum_{r \in \{r_{mid}, r_{mid}-1\}} \frac{1}{N_{col}}\sum_{j=0}^{N_{col}-1} z_{r,j}$$

$$\bar{z}_{low} = \frac{1}{N_{col}} \sum_{j=0}^{N_{col}-1} z_{r_{low}(\theta),\, j}$$

$$\bar{z}_{ultra} = \frac{1}{2} \sum_{r \in \{r_{ultra}, r_{ultra}-1\}} \frac{1}{N_{col}}\sum_{j=0}^{N_{col}-1} z_{r,j}$$

**J.2 — Enam fitur terrain (BARU v8):**

**Fitur 1 — Gradien Vertikal $\Delta z_v$:** Ukuran besarnya penurunan atau kenaikan lantai dari area jauh ke area dekat kaki.

$$\boxed{\Delta z_v = \bar{z}_{high} - \bar{z}_{low}}$$

Pada lantai datar normal: $\bar{z}_{high} > \bar{z}_{low}$ (area jauh lebih dekat karena sudut pandang miring ke bawah), sehingga $\Delta z_v > 0$ kecil. Drop tajam menghasilkan $\Delta z_v \gg 0$; obstacle tinggi menghasilkan $\Delta z_v \ll 0$.

**Fitur 2 — Perubahan Temporal $\Delta z_t$:** Seberapa cepat $\bar{z}_{low}$ berubah frame ke frame.

$$\boxed{\Delta z_t = \bar{z}_{low}^{(t)} - \bar{z}_{low}^{(t-1)}}$$

Nilai positif besar menunjukkan lantai dekat "menghilang" — pengguna semakin mendekati tepi atau lubang.

**Fitur 3 — Standar Deviasi Per Kolom $\sigma_j$:** Distribusi spasial anomali di sepanjang lebar FOV sensor.

$$\boxed{\sigma_j = \sqrt{\frac{1}{N_{row,low}} \sum_{r \in \{r_{low}\}} \left(z_{r,j} - \bar{z}_{low}\right)^2}, \quad j = 0, ..., N_{col}-1}$$

Vektor $[\sigma_0, \sigma_1, ..., \sigma_7]$ menunjukkan kolom mana yang memiliki variabilitas tinggi.

**Fitur 4 — Depth Ratio $R$ (dipertahankan dari v7):**

$$\boxed{R = \frac{\bar{z}_{low}}{\bar{z}_{mid}}}$$

**Fitur 5 — Edge Sharpness $\xi$:** Perubahan jarak maksimum antar dua baris berurutan, mengukur apakah transisi terrain tajam (cliff/tangga) atau halus (ramp).

$$\boxed{\xi = \max_{r \in \{0,...,N_{row}-2\}} \left|\bar{z}_r - \bar{z}_{r+1}\right|}$$

dengan $\bar{z}_r = \frac{1}{N_{col}}\sum_j z_{r,j}$.

**Fitur 6 — Pola Spasial Kolom:** Klasifikasi distribusi $\sigma_j$:

$$\boxed{\text{pattern} = \begin{cases}
\text{UNIFORM}, & \text{card}\!\left(\{j : \sigma_j > \sigma_{col,th}\}\right) \ge 5 \\
\text{LOCALIZED}, & \text{card}\!\left(\{j : \sigma_j > \sigma_{col,th}\}\right) \le 3 \\
\text{MIXED}, & \text{otherwise}
\end{cases}}$$

dengan $\sigma_{col,th} = 200$ mm. UNIFORM berarti anomali menyebar lebar (tangga/ramp); LOCALIZED berarti anomali hanya di sebagian kolom (lubang, parit).

**J.3 — Klasifikasi tipe terrain (BARU v8):**

Decision tree berbasis enam fitur di atas:

$$\boxed{\text{type} = \begin{cases}
\text{OPEN},       & \bar{z}_{mid} \ge d_{guard}\ \vee\ \bar{z}_{mid} < 30 \\
\text{CONTAM.},    & \bar{z}_{low} < d_{cont} \\
\text{STAIR\_DOWN},& \Delta z_v > \Delta z_{step}\ \wedge\ R > R_{th,hi}\ \wedge\ \text{pattern} = \text{UNIFORM} \\
\text{HOLE},       & \Delta z_v > \Delta z_{step}\ \wedge\ R > R_{th,hi}\ \wedge\ \text{pattern} = \text{LOCALIZED} \\
\text{STAIR\_UP},  & \Delta z_v < -\Delta z_{step}\ \wedge\ \xi > edge_{th} \\
\text{RAMP},       & -\Delta z_{step} \le \Delta z_v \le \Delta z_{step}\ \wedge\ R > R_{th,lo}\ \wedge\ \xi \le edge_{th} \\
\text{SAFE},       & \text{otherwise}
\end{cases}}$$

dengan urutan evaluasi dari atas ke bawah (guard dievaluasi pertama, SAFE adalah catch-all di bawah).

Konstanta:
- $\Delta z_{step} = 500$ mm — ambang gradien vertikal
- $R_{th,hi} = 0.8$, $R_{th,lo} = 0.7$ — threshold rasio (sama dengan v7)
- $edge_{th} = 300$ mm — batas sharpness untuk membedakan step vs ramp
- $d_{guard} = 2500$ mm, $d_{cont} = 800$ mm (sama dengan v7)

**J.4 — Estimasi kedalaman/ketinggian anomali (BARU v8):**

$$\boxed{h_{est} = \begin{cases}
\dfrac{\bar{z}_{low}^{anomaly} - \bar{z}_{low}^{normal}}{\cos(\alpha_{mount})}, & \text{type} = \text{STAIR\_DOWN} \\[8pt]
\bar{z}_{low}^{anomaly} - \bar{z}_{mid}, & \text{type} = \text{HOLE} \\[8pt]
\bar{z}_{mid} - \bar{z}_{low}^{anomaly}, & \text{type} = \text{STAIR\_UP} \\[8pt]
|\bar{z}_{mid} - \bar{z}_{low}|, & \text{type} = \text{RAMP} \\[8pt]
0, & \text{otherwise}
\end{cases}}$$

di mana $\bar{z}_{low}^{normal}$ adalah nilai $\bar{z}_{low}$ pada frame sebelumnya (saat terrain masih datar), dan $\alpha_{mount} = 15°$ adalah sudut mounting sensor ke bawah (dari konstanta sistem).

**J.5 — Deteksi arah spasial anomali (BARU v8):**

Menentukan di kolom mana anomali terjadi, lalu memetakannya ke arah jam kompatibel dengan Formula C:

$$\mathcal{J}_{anomaly} = \{j \in \{0,...,7\} : \sigma_j > \sigma_{col,th}\}$$

$$\text{dir} = \begin{cases}
\text{JAM}_{11}, & \text{mean}(\mathcal{J}_{anomaly}) < 2.5 \quad (\text{kolom kiri}) \\
\text{JAM}_{12}, & 2.5 \le \text{mean}(\mathcal{J}_{anomaly}) \le 5.5 \quad (\text{kolom tengah}) \\
\text{JAM}_1, & \text{mean}(\mathcal{J}_{anomaly}) > 5.5 \quad (\text{kolom kanan})
\end{cases}$$

Jika $\mathcal{J}_{anomaly} = \varnothing$ (tidak ada kolom anomali), dir := JAM$_{12}$ sebagai default.

**J.6 — Confidence scoring (BARU v8):**

Setiap klasifikasi disertai confidence score $C \in [0, 1]$ yang mencerminkan konsistensi fitur:

$$\boxed{C = 0.40 \cdot C_R + 0.30 \cdot C_{spatial} + 0.20 \cdot C_{temporal} + 0.10 \cdot C_{edge}}$$

dengan sub-confidence:
- $C_R$: seberapa jauh $R$ dari nilai tipikal untuk tipe yang terdeteksi (1.0 jika kuat, 0.0 jika borderline)
- $C_{spatial}$: entropi distribusi $\sigma_j$ — lebih seragam/lokal = lebih yakin
- $C_{temporal}$: konsistensi deteksi selama 3 frame terakhir
- $C_{edge}$: seberapa jelas edge sharpness mendukung keputusan (STAIR\_UP butuh $\xi$ tinggi; RAMP butuh $\xi$ rendah)

**J.7 — Indikator output final dengan routing (BARU v8):**

$$\boxed{\delta_J = \begin{cases}
1\ (\text{HIGH}), & \text{type} \in \{\text{STAIR\_DOWN, HOLE}\}\ \wedge\ C > C_{HIGH} \\
1\ (\text{MED}), & \text{type} \in \{\text{STAIR\_DOWN, HOLE, STAIR\_UP}\}\ \wedge\ C_{MID} < C \le C_{HIGH} \\
1\ (\text{INFO}), & \text{type} = \text{RAMP}\ \wedge\ C > C_{MID} \\
0, & \text{type} \in \{\text{SAFE, OPEN, CONTAMINATED}\}\ \vee\ C \le C_{MID}
\end{cases}}$$

**Hysteresis tetap dipertahankan:** Untuk mencegah *alert flapping* pada kondisi borderline (R berfluktuasi di sekitar $R_{th}$), logika hysteresis v7 tetap aktif sebagai post-processing: jika type berganti-ganti antara STAIR\_DOWN dan SAFE dalam 3 frame berturut-turut tanpa stabilisasi, pertahankan state sebelumnya.

---

### J.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $z_{r,j}$ | Input | $[0, 4000]$ | mm | Data ToF matriks 8×8 dari sensor VL53L5CX |
| $\theta$ | Input | $[-90°, +90°]$ | ° | Pitch kepala dari Formula A (via paket WebSocket) |
| $\bar{z}_{high}$ | Intermediat | $[0, 4000]$ | mm | Rata-rata jarak zona atas (rows 0–2) |
| $\bar{z}_{mid}$ | Intermediat | $[0, 4000]$ | mm | Rata-rata jarak zona tengah — referensi lantai (rows 3–4) |
| $\bar{z}_{low}$ | Intermediat | $[0, 4000]$ | mm | Rata-rata jarak zona bawah — lantai dekat kaki (row 5) |
| $\bar{z}_{ultra}$ | Intermediat | $[0, 4000]$ | mm | Rata-rata jarak zona ultra-bawah (rows 6–7) |
| $\Delta z_v$ | Intermediat | $(-\infty, +\infty)$ | mm | Gradien vertikal: drop (+) atau rise (−) |
| $\Delta z_t$ | Intermediat | $(-\infty, +\infty)$ | mm | Perubahan $\bar{z}_{low}$ frame ke frame |
| $\sigma_j$ | Intermediat | $[0, \infty)$ | mm | Standar deviasi per kolom $j$ di zona bawah |
| $R$ | Intermediat | $(0, \infty)$ | — | Depth ratio = $\bar{z}_{low}/\bar{z}_{mid}$ |
| $\xi$ | Intermediat | $[0, \infty)$ | mm | Edge sharpness: jump maksimum antar baris |
| $\text{pattern}$ | Intermediat | $\{\text{UNIFORM, LOCALIZED, MIXED}\}$ | — | Pola spasial distribusi anomali |
| $\text{type}$ | Intermediat | $\{\text{OPEN, CONTAM., STAIR\_DOWN, STAIR\_UP, HOLE, RAMP, SAFE}\}$ | — | Tipe terrain terklasifikasi |
| $h_{est}$ | **Output** | $[0, \infty)$ | mm | **Estimasi kedalaman/ketinggian anomali** |
| $\text{dir}$ | **Output** | $\{\text{JAM}_{11}, \text{JAM}_{12}, \text{JAM}_1\}$ | — | **Arah spasial anomali** |
| $C$ | **Output** | $[0, 1]$ | — | **Confidence score klasifikasi** |
| $\delta_J$ | **Output** | $\{0, 1\}$ | — | **Indikator anomali terrain** (dengan level HIGH/MED/INFO) |
| $\Delta z_{step}$ | Konstanta | — | mm | Ambang gradien vertikal = 500 mm |
| $\sigma_{col,th}$ | Konstanta | — | mm | Threshold std dev kolom = 200 mm |
| $edge_{th}$ | Konstanta | — | mm | Batas sharpness = 300 mm |
| $C_{HIGH}, C_{MID}$ | Konstanta | — | — | Threshold confidence = 0.80 dan 0.60 |

---

### J.3 Cara Kerja Detail

**Geometri sensor dan terrain normal:**

VL53L5CX dipasang dengan sudut kemiringan $\alpha_{mount} = 15°$ ke bawah dari horizontal. Setiap baris sensor menembakkan sinyal laser ke sudut vertikal berbeda. Pada terrain datar normal:
- Baris atas (rows 0–2, $\bar{z}_{high}$): menembak ke depan-atas, membaca dinding/udara → jarak besar
- Baris tengah (rows 3–4, $\bar{z}_{mid}$): menembak ke lantai pada jarak ~1–2.5 m dari pengguna
- Baris bawah (row 5, $\bar{z}_{low}$): menembak ke lantai ~0.5–1.5 m dari kaki
- Baris ultra (rows 6–7, $\bar{z}_{ultra}$): menembak ke lantai sangat dekat, <0.5 m

Sehingga pada lantai datar: $\bar{z}_{high} > \bar{z}_{mid} > \bar{z}_{low} > \bar{z}_{ultra}$, dan $\Delta z_v > 0$ kecil.

**Cara membedakan STAIR_DOWN vs HOLE:**

Kedua tipe sama-sama menghasilkan $\Delta z_v > 500$ mm dan $R > 0.8$, tetapi distribusi spasialnya berbeda:

*Tangga turun:* Seluruh lebar depan jalan berubah serentak — semua 8 kolom ToF membaca jarak yang lebih jauh. Standar deviasi per kolom $\sigma_j$ menjadi besar di hampir semua kolom → pola UNIFORM.

*Lubang/parit:* Hanya sebagian kecil lebar jalan yang terputus — misal kolom 2–4 membaca jarak jauh sementara kolom 0–1 dan 5–7 tetap membaca lantai normal. $\sigma_j$ hanya besar di kolom 2–4 → pola LOCALIZED.

```
Contoh nyata:
Tangga turun (lebar 2m):
  σ_col = [350, 380, 410, 420, 400, 390, 350, 360]
  Semua kolom σ > 200mm → UNIFORM → STAIR_DOWN ✓

Lubang (lebar 40cm):
  σ_col = [80, 90, 950, 1100, 85, 70, 80, 75]
  Hanya kolom 2-3 σ > 200mm → LOCALIZED → HOLE ✓
```

**Cara membedakan STAIR_UP (tangga naik) dari obstacle vertikal lain:**

Tangga naik dan dinding sama-sama menghasilkan $\Delta z_v < -500$ mm (baris bawah membaca lebih dekat dari normal). Pembeda utamanya adalah edge sharpness $\xi$: tangga naik dan dinding memiliki transisi sangat tajam (perubahan ratusan mm dalam satu baris), sedangkan terrain miring seperti tanjakan halus memiliki transisi gradual.

$$\xi > edge_{th} = 300\ \text{mm} \to \text{STAIR\_UP (atau wall)}$$
$$\xi \le edge_{th} \to \text{RAMP (tanjakan halus)}$$

Pembedaan lebih lanjut antara tangga naik dan dinding vertikal bisa dilakukan melalui integrasi dengan YOLO class label (`stairs`, `wall`) — rekomendasi untuk post-v8.

**Estimasi kedalaman — contoh numerik:**

Pengguna mendekati tangga turun pertama kali:
- Frame normal: $\bar{z}_{low}^{normal} = 800$ mm (lantai datar di depan)
- Frame anomali: $\bar{z}_{low}^{anomaly} = 2300$ mm (toF melewati tepi tangga)

$$h_{est} = \frac{2300 - 800}{\cos(15°)} = \frac{1500}{0.966} \approx 1552\ \text{mm}$$

Ini *overestimasi* karena asumsi bahwa toF membaca dasar tangga langsung, padahal lantai bawah mungkin lebih jauh. Estimasi yang lebih baik memerlukan tracking jarak di dua kedalaman berbeda — rekomendasi post-v8. Untuk kepraktisan, hasilkan peringatan dengan nilai dibulatkan ke satuan 5 cm: "Tangga turun sekitar 15 sentimeter".

**Deteksi arah dan konsistensi dengan Formula C:**

Formula C menggunakan zona 3 bagian (kiri/tengah/kanan, masing-masing $W_z$ piksel) untuk menentukan arah jam dari bounding box YOLO. Formula J.v2 menggunakan 8 kolom ToF yang dibagi ke tiga segmen yang sama: kolom 0–2 (kiri → JAM 11), kolom 3–5 (tengah → JAM 12), kolom 6–7 (kanan → JAM 1). Ini memastikan konsistensi informasi arah antara peringatan berbasis kamera (Formula C) dan peringatan berbasis ToF (Formula J).

**Integrasi confidence ke Formula G:**

```
IF type ∈ {STAIR_DOWN, HOLE} AND C > C_HIGH (0.80):
    → δ_J = 1 (HIGH): Force Formula G alert; bypass threshold T
    → TTS: "AWAS! [type] [h_est cm], [dir]!"
    → Urgency: MAX

ELIF type ∈ {STAIR_DOWN, HOLE, STAIR_UP} AND C_MID < C ≤ C_HIGH:
    → δ_J = 1 (MED): Feed ke Formula G sebagai confirmation
    → TTS: "Perhatian, [type] [h_est cm] di [dir]"
    → Urgency: NORMAL

ELIF type = RAMP AND C > C_MID:
    → δ_J = 1 (INFO): One-shot saja
    → TTS: "Landai [h_est cm] di depan"
    → Urgency: LOW

ELSE:
    → δ_J = 0: Tidak ada peringatan terrain
```

---

### J.4 Asal Usul Formula

**Konsep:** Ratio-based anomaly detection, Statistical Process Control, dan multi-feature terrain classification.

**Landasan 1 — Rasio depth ($R$):** Tetap menggunakan konsep rasio Karl Pearson (1895) seperti v7 — tidak berubah.

**Landasan 2 — Empat zona vertikal:** Konsep membagi sensor array ke zona fungsional digunakan secara luas dalam *structured light* dan *LiDAR processing*. Velodyne dalam *High Definition LiDAR HDL-64E* (2009) membagi beam array ke zona ground, object, dan obstacle untuk segmentasi terrain real-time.

**Landasan 3 — Decision tree terrain:** Penggunaan decision tree untuk klasifikasi terrain ToF diilhami oleh **Morgan Quigley et al.** (ROS/Point Cloud Library, 2009) yang mengembangkan `pcl::GroundSegmentation` menggunakan gradien vertikal point cloud. Formula J.v2 mengadaptasi logika tersebut ke matriks 8×8 yang jauh lebih kecil — optimasi komputasi O(1) yang cocok untuk edge computing smartphone.

**Landasan 4 — Edge sharpness:** Konsep *edge detection* dari **John F. Canny** (1986) dalam makalah *"A Computational Approach to Edge Detection"* (IEEE PAMI). Canny mendefinisikan tepi sebagai lokasi perubahan intensitas maksimum. Formula J mengadaptasi prinsip ini ke data 1D per baris: $\xi = \max |\bar{z}_r - \bar{z}_{r+1}|$ adalah detektor tepi Canny satu dimensi.

**Landasan 5 — Confidence scoring:** Skor kepercayaan berbasis multiple evidence adalah prinsip *Dempster-Shafer Theory* (Arthur Dempster, 1967; Glenn Shafer, 1976) — menggabungkan bukti dari sumber independen menjadi satu ukuran keyakinan. Formula J.6 menggunakan aproksimasi linear yang lebih ringan komputasinya, tetapi filosofinya serupa.

**Penerapan dalam sistem ini:** Threshold $R_{th,hi} = 0.8$, $R_{th,lo} = 0.7$, $\Delta z_{step} = 500$ mm, $\sigma_{col,th} = 200$ mm, dan $edge_{th} = 300$ mm adalah nilai default yang perlu divalidasi empiris pada berbagai jenis permukaan dan kondisi (tegel, aspal, tanah, karpet, dalam/luar ruangan). Kampanye pengujian 50+ skenario lapangan direkomendasikan sebelum produksi massal.

---

### J.5 Aliran Variabel

```
[Sensor VL53L5CX]          [Formula A via WebSocket]
    │                               │
    └──→ z_{r,j} (8×8, mm)          └──→ θ (°)
              │                          │
              │             [J.0: Hitung 4 baris zona]
              │             r_high, r_mid, r_low, r_ultra ← θ-adaptive
              │                          │
              └──────────┬───────────────┘
                         │
              ┌──────────┴──────────────────────┐
              ▼           ▼         ▼           ▼
          z̄_high       z̄_mid    z̄_low      z̄_ultra
              │           │         │           │
              └───────────┴────┬────┘           │
                               ▼                │
                    [J.2: Ekstrak 6 fitur]       │
                    Δz_v = z̄_high - z̄_low       │
                    Δz_t = z̄_low^t - z̄_low^t-1  │
                    σ_j (per kolom)              │
                    R = z̄_low / z̄_mid            │
                    ξ = max|z̄_r - z̄_{r+1}|      │
                    pattern (UNIFORM/LOCAL/MIXED) │
                               │                 │
                    [J.3: Decision Tree]          │
                    type ∈ {DOWN/UP/HOLE/RAMP/SAFE}
                               │
              ┌────────────────┤
              ▼                ▼
    [J.4: Estimasi h_est]  [J.5: Deteksi dir]
    depth/height (mm)      JAM_11/12/1
              │                │
              └────────┬───────┘
                       ▼
              [J.6: Confidence C]
              C ∈ [0.0, 1.0]
                       │
              [J.7: Routing δ_J]
                       │
         ┌─────────────┼───────────────┐
         ▼             ▼               ▼
    HIGH (C>0.8)   MED (C>0.6)     INFO/SKIP
    Force G+H      Feed ke G+H     One-shot/diam
    "AWAS!"        "Perhatian,"    "Landai depan"
    [type] [h] [dir]
```

## K. Formulasi Mode Darurat (Fail-Safe)

**Referensi:** Flowchart 3.5.5, SD-4a & SD-4b

---

### K.1 Notasi yang Diperbaiki

**K.1 — Jarak minimum seluruh zona:**

$$d_{min} = \min_{r \in \{0,...,7\},\ j \in \{0,...,7\}} z_{r,j}$$

**K.2 — Kondisi buzzer:**

$$\boxed{\delta_{buzz} = \mathbf{1}\!\left[d_{min} < d_{w0}\right]}$$

**K.3 — State machine mode operasi:**

$$\boxed{\text{Mode} = \begin{cases}
\text{Offline}, & t_{disc} > t_{wifi} \\
\text{Gelap}, & t_{disc} \le t_{wifi}\ \wedge\ B_{cam} < B_{th} \\
\text{Smart}, & t_{disc} \le t_{wifi}\ \wedge\ B_{cam} \ge B_{th}
\end{cases}}$$

**Domain:** $d_{min} \in [0, 4000]$ mm, $\delta_{buzz} \in \{0,1\}$

### K.2 Keterangan Variabel

| Simbol | Tipe | Domain | Satuan | Deskripsi |
|---|---|---|---|---|
| $z_{r,j}$ | Input | $[0, 4000]$ | mm | Seluruh 64 zona ToF |
| $d_{min}$ | Intermediat | $[0, 4000]$ | mm | Jarak terdekat dari **semua** zona (konservatif) |
| $d_{w0}$ | Konstanta | — | mm | Threshold buzzer = 1000 mm |
| $\delta_{buzz}$ | **Output** | $\{0,1\}$ | — | **Status buzzer hardware** (1 = bunyi) |
| $t_{disc}$ | Input | $[0, \infty)$ | s | Durasi WiFi putus |
| $t_{wifi}$ | Konstanta | — | s | Toleransi WiFi putus = 5 s |
| $B_{cam}$ | Input | $[0, 255]$ | — | Rata-rata brightness frame kamera |
| $B_{th}$ | Konstanta | — | — | Threshold kecerahan minimum = 40 (dikalibrasi empiris) |

---

### K.3 Cara Kerja Detail

Formula K adalah **lapisan keamanan terakhir yang sepenuhnya independen dari smartphone**. ESP32-S3 menjalankan formula ini secara lokal — tidak perlu WiFi, tidak perlu YOLO, tidak perlu TTS.

**Mengapa $d_{min}$ dari semua 64 zona, bukan hanya $\mathcal{R}_{obj}$?**

Dalam mode darurat, kita tidak tahu ke mana pengguna menghadap, tidak ada tracking YOLO, tidak ada koreksi pitch. Menggunakan semua zona memastikan **tidak ada objek yang terlewat** dari sudut manapun, meskipun dengan risiko false positive lebih tinggi (lantai miring atau langit-langit rendah bisa memicu buzzer). Ini adalah keputusan *safety-first*: lebih baik bunyi berlebihan daripada tidak bunyi saat dibutuhkan.

**State machine tiga mode:**
- **Smart:** Kondisi normal — smartphone aktif, kamera cukup terang, semua formula berjalan
- **Gelap:** WiFi masih ada, tetapi kamera terlalu gelap untuk YOLO. Buzzer tetap aktif, TTS beroperasi dengan data ToF saja (tanpa identifikasi objek)
- **Offline:** WiFi putus > 5 detik. ESP32 bekerja mandiri: hanya buzzer berbasis $d_{min}$

---

### K.4 Asal Usul Formula

**Konsep:** Functional safety dan fail-safe design.

**Penemu/Standar:** Prinsip *fail-safe* berasal dari rekayasa keselamatan abad ke-19, dikaitkan dengan **Thomas Treloar** (insinyur kereta api Inggris, 1870-an) yang merancang sistem sinyal kereta api yang secara otomatis menampilkan tanda "bahaya" jika komponen gagal.

Dalam sistem elektronik modern, prinsip ini dikodifikasikan dalam:
- **IEC 61508:2010** (*Functional Safety of Electrical/Electronic/Programmable Electronic Safety-Related Systems*) — standar internasional utama untuk functional safety. Mendefinisikan *Safety Integrity Level* (SIL) dan mensyaratkan mekanisme fail-safe independen.
- **IEC 62061:2021** (*Safety of Machinery*) — mensyaratkan bahwa kegagalan satu subsistem tidak boleh menghilangkan seluruh perlindungan keselamatan.

**Konsep state machine:** **George H. Mealy** (1955) dan **Edward F. Moore** (1956) memformalisasi dua model state machine. Model Moore (output hanya bergantung pada state):

$$\text{output} = \lambda(s_t), \quad s_{t+1} = \delta(s_t,\ \text{input})$$

State machine Formula K menggunakan model Moore murni — Mode (`Smart`, `Gelap`, `Offline`) adalah state, dan $\delta_{buzz}$ adalah output dari state tersebut. Transisi state ditentukan oleh $t_{disc}$ dan $B_{cam}$:

$$s_{t+1} = \begin{cases} \text{Offline} & t_{disc} > t_{wifi} \\ \text{Gelap} & t_{disc} \le t_{wifi}\ \wedge\ B_{cam} < B_{th} \\ \text{Smart} & \text{lainnya} \end{cases}$$

**Penerapan dalam sistem ini:** Threshold $d_{w0} = 1000$ mm untuk buzzer **tidak adaptif** (berbeda dari Formula G) karena tanpa smartphone, kecepatan $v$ tidak bisa dihitung. Ini adalah trade-off yang disengaja: kesederhanaan dan keandalan lebih penting daripada presisi dalam mode darurat.

---

### K.5 Aliran Variabel

```
[Sensor VL53L5CX]    [WiFi Monitor]    [Kamera OV2640]
    │                     │                  │
    └──→ z_{r,j}          └──→ t_disc (s)   └──→ B_cam
    (64 nilai, mm)             │                  │
         │                    └────────┬──────────┘
         ▼                             ▼
   d_min = min(z_{r,j})       [State Machine K.3]
         │                    Mode ∈ {Smart, Gelap, Offline}
         │                             │
         ▼                             │
   [Formula K.2: Buzzer]               │
   δ_buzz = 𝟏[d_min < 1000mm]         │
         │                             │
         └──────────┬──────────────────┘
                    ▼
           [ESP32 GPIO Output]
           Buzzer hardware ON/OFF
           (independen dari smartphone)
```

---

## Ringkasan & Aliran Data Antar Formula

### Tabel Ringkasan Formula

| # | Formula | Bentuk | Input | Output | Fungsi | Dijalankan di |
|---|---|---|---|---|---|---|
| **A** | Filter IMU (EKF) | EKF quaternion: prediksi giroskop + koreksi akselerometer + estimasi bias | Raw MPU-6050 6-axis | $\theta, \phi, \omega_z, \|\mathbf{a}_{lin}\|$ | Estimasi orientasi kepala adaptif | **ESP32-S3** |
| **B** | Centroid | $x_c = \frac{x_{min}+x_{max}}{2}$ | BBox YOLO | $x_c$ (px) | Posisi horizontal objek | Smartphone |
| **C** | Arah Jam | Piecewise $h(x_c)$ dengan $b_k = D_{left}+kW_z$ | $x_c$ | $h \in \{10,11,12,1,2\}$ | Arah intuisi tunanetra | Smartphone |
| **D** | Grid Binning | $j = \text{sat}(\lfloor(x_c-D_{left})/R_{col}\rfloor, 0, N_{col}-1)$ | $x_c$ | $j \in \{0,...,7\}$ | Kolom sensor ToF | Smartphone |
| **E** | Jarak ToF | Filter error ($z=0$, $z=65535$); $d_{obj} = \frac{1}{|\mathcal{R}_{valid}|}\sum z_{r,j}$ | $z_{r,j}$, $j$, $\theta$ | $d_{obj}$ (mm) | Jarak objek (baris dinamis, error dibuang) | Smartphone |
| **F** | Sumber Gerakan + Danger Scoring | Danger score $D^{(i)} = w_d \cdot d\_\text{score} + w_v \cdot v\_\text{score} + w_s \cdot \text{src\_score}$; routing per pool HIGH/MED/LOW | $\|\mathbf{a}_{lin}\|$ (MPU), $\Delta d^{(i)}$, $v^{(i)}$ per tracking ID | $D^{(i)} \in [0,1]$, pool $\in$ {HIGH, MEDIUM, LOW} | Multi-objek danger-weighted router ke Formula G/H | Smartphone |
| **G** | Threshold Adaptif | $T = \min(d_{w0}+v \cdot t_r,\ d_{max})$; $\Delta t=\text{sat}(\Delta t_{raw}, \Delta t_{min}, \Delta t_{max})$; guard frame-1 | $d_{obj}^{(t)}, d_{obj}^{(t-1)}, ts_{esp}, \omega_x^{corr}$ | $\delta_G \in \{0,1\}$ | Peringatan dini berbasis kecepatan | Smartphone |
| **H** | One-shot Alert | $\delta_H = \mathbf{1}[d_{obj}<d_{w0}]\cdot\mathbf{1}[f_{obj}=0]$; reset $|\Delta d|>\varepsilon_{noise}$; known limitation: ID loss | $d_{obj}$, $f_{obj}$ | $\delta_H \in \{0,1\}$ | Cegah spam TTS saat diam | Smartphone |
| **I** | TTC Multi-Dimensional | Moving avg area; $\text{TTC\_score} = w_A \cdot \text{area\_score} + w_{AR} \cdot \text{ar\_score} + w_{dist} \cdot \text{dist\_score}$; bobot kelas YOLO | BBox $t$, $t-1$, $t-2$; class label; $d_{obj}$ ToF | $\text{TTC\_weighted} \in [0,1]$, pool $\in$ {IMMINENT, PROBABLE, POSSIBLE} | Deteksi kendaraan >4m berbasis tiga fitur; false-positive rotasi diminimalkan | Smartphone |
| **J** | Klasifikasi Terrain | 4-zona vertikal; 6 fitur ($\Delta z_v$, $\Delta z_t$, $\sigma_j$, $R$, $\xi$, pattern); decision tree terrain | $z_{r,j}$, $\theta$ | type $\in$ {DOWN/UP/HOLE/RAMP/SAFE}, $h_{est}$ (mm), dir (jam), $C \in [0,1]$, $\delta_J$ | Klasifikasi 5 tipe terrain + estimasi kedalaman + arah | Smartphone |
| **K** | Fail-Safe | $\delta_{buzz} = \mathbf{1}[d_{min}<d_{w0}]$ | $z_{r,j}$ semua zona | $\delta_{buzz} \in \{0,1\}$ | Buzzer independen dari HP | **ESP32-S3** |

---

### Alur Data Lengkap Antar Formula

```
═══════════════════════════════════════════════════════════════════════════
                         SUMBER DATA HARDWARE
═══════════════════════════════════════════════════════════════════════════

  [OV2640 Kamera]      [VL53L5CX ToF]        [MPU-6050 via I2C @100Hz]
  x_min, x_max          z_{r,j} (8×8)          ax,ay,az (m/s²)
  y_min, y_max          via I2C @ 30Hz          ωx,ωy,gz (°/s)
  via YOLOv11           ─────────────          ──────────────────────
  ─────────────               │                          │
        │                     │                          │
        │                     │           ┌──────────────┘
        │                     │           ▼
        │                     │   ┌───────────────────────────────┐
        │                     │   │    ESP32-S3 WROOM N16R8       │
        │                     │   │                               │
        │                     │   │ [Formula A: Filter IMU]       │
        │                     │   │ θ, φ, ω_z, ‖a_lin‖           │
        │                     │   │                               │
        │                     │   │ [Formula K: Fail-Safe]        │
        │                     │←──┤ d_min → buzzer GPIO           │
        │                     │   │                               │
        │                     │   └──────────────┬────────────────┘
        │                     │                  │
        │                     │         [WebSocket JSON @30Hz]
        │                     │         {θ, φ, ω_z, ‖a_lin‖, tof[8×8], ts}
        │                     │                  │
        │                     │                  │
═══════╪═════════════════════╪══════════════════╪══════════════════════
                         LAYER 1: PREPROCESSING (Smartphone)
═══════╪═════════════════════╪══════════════════╪══════════════════════
        │                     │                  │
        ▼                     │           ┌──────┴───────────────┐
  [Formula B]                 │           ▼                      ▼
  x_c = (x_min+x_max)/2       │    θ → [Formula E]       ‖a_lin‖,Δd
        │                     │    θ → [Formula J]        → [Formula F]
        ▼                     │           │                      │
  [Formula D]                 │           │          ┌───────────┴───────────┐
  j = sat(⌊(x_c-80)/60⌋,0,7) │           │          ▼           ▼           ▼
        │                     │           │        user       object   receding/static
        │                     │           │          └─────┬─────┘           │
        │                     │           │                │                 │
═══════╪═════════════════════╪═══════════╪════════════════╪═════════════════╪═══
                         LAYER 2: CORE SENSING (Smartphone)
═══════╪═════════════════════╪═══════════╪════════════════╪═════════════════╪═══
        │                     │           │                │                 │
        ▼                     ▼           ▼                │                 │
  [Formula C]        [Formula E]     [Formula J]           │                 │
  h(x_c) → Jam       d_obj=avg(      R=z̄_low/z̄_mid        │                 │
     ↓ TTS            z_{R_obj,j})    baris dinamis         │                 │
  "Jam sebelas"            │          guard d_guard         │                 │
                           │               ↓ TTS            │                 │
                           │          "Tangga/Lubang"       │                 │
                           │                                │                 │
═══════════════════════════════════════════════════════════╪═════════════════╪═══
                         LAYER 3: ALERT LOGIC (Smartphone)
═══════════════════════════════════════════════════════════╪═════════════════╪═══
                                                           ▼                 ▼
                                                   [Formula G]        [Formula H]
                                                   T=min(d_w0           d_obj<d_w0
                                                    +v_bersih·t_r,      ∧ f_obj=0
                                                    d_max)              ∧ |Δd|≤ε
                                                   v_bersih=v-v_head
                                                     ↓ TTS                 ↓ TTS
                                               "Peringatan,           "Objek dekat"
                                                jarak X meter"        (sekali saja)

  [OV2640, BBox area t & t-1]
         │
         ▼
  [Formula I]
  ΔA=(A_t-A_{t-1})/A_{t-1}×100%
  guard: A_{t-1}>0
         ↓ TTS
  "Kendaraan mendekat"

═══════════════════════════════════════════════════════════════════════════
                    LAYER 4: FAIL-SAFE (ESP32-S3 LOKAL)
═══════════════════════════════════════════════════════════════════════════

  [VL53L5CX semua zona]    [WiFi Monitor]    [Kamera Brightness]
           │                     │                   │
           ▼                     └──────┬────────────┘
  d_min = min(z_{r,j})                  ▼
           │                    State Machine
           ▼                    Smart/Gelap/Offline
  [Formula K]                           │
  δ_buzz = 𝟏[d_min < 1000mm]           │
           │                            │
           └────────────────────────────┘
                    ▼
           [GPIO Buzzer ESP32]
           Bunyi: INDEPENDEN dari WiFi/HP
```

---

### Ringkasan Asal Usul Konsep Matematis

| Formula | Konsep Inti | Penemu Utama | Tahun | Referensi Seminal |
|---|---|---|---|---|
| **A** | Extended Kalman Filter untuk estimasi orientasi IMU 6DOF + quaternion + bias estimation | Rudolf Kálmán; Stanley F. Schmidt; A.M. Sabatini | 1960; 1966; 2006 | ASME J. Basic Engineering; NASA Apollo Nav; IEEE Trans. Biomedical Engineering |
| **B** | Centroid / rata-rata aritmatika | Archimedes | ~250 SM | *On the Equilibrium of Planes* |
| **C** | Clock direction O&M tunanetra | Richard E. Hoover | ~1944–1950 | Valley Forge General Hospital |
| **D** | Uniform scalar quantization + spatial binning | Joel Max; Dalal & Triggs | 1960; 2005 | IRE Trans. IT; CVPR 2005 |
| **E** | Least squares averaging (MVUE) + pitch-aware row selection | Carl Friedrich Gauss | 1795/1809 | *Theoria Motus* |
| **F** | Activity recognition via IMU wearable + Weighted Sum Model (MCDM) + Alert hierarchy IEC 62682 | Kwapisz et al.; Churchman et al.; IEC TC65 | 2011; 1957; 2014 | ACM SIGKDD; *Operations Research*; IEC 62682 |
| **G** | Kinematic safety distance + adaptive threshold + head compensation | Newton; WHO; ISO 22179 | 1687; 2018; 2009 | *Principia*; WHO Road Safety; FSRA Standard |
| **H** | Debounce / one-shot state machine + noise-aware reset | William Eccles & F.W. Jordan; David Harel | 1919; 1987 | Flip-flop circuit; Statecharts paper |
| **I** | Time-to-contact (tau theory) + perspektif invariansi aspect ratio + cross-domain validation | David N. Lee; Hartley & Zisserman; Horn & Schunck | 1976; 2003; 1981 | *Perception*; *Multiple View Geometry*; *AI Journal* |
| **J** | Ratio detection + SPC + multi-feature terrain classification + edge detection + Dempster-Shafer confidence | Pearson; Shewhart; Canny; Dempster & Shafer | 1895; 1931; 1986; 1967–76 | CV; *Econ. Control*; IEEE PAMI; Ann. Math. Stats. |
| **K** | Fail-safe design + state machine | Thomas Treloar; Mealy/Moore | 1870s; 1955–56 | Railway signalling; Bell System Tech. J. |

---

*Dokumen ini adalah revisi dari `formula-matematis-v7.md` dengan penyempurnaan keamanan multi-objek: danger-weighted multi-object routing (Formula F), TTC multi-dimensional dengan validasi aspect ratio dan ToF (Formula I), klasifikasi terrain 5-tipe dengan estimasi kedalaman dan arah spasial (Formula J), serta penambahan konstanta sistem baru untuk semua fitur tersebut.*

*Hardware: ESP32-S3 WROOM N16R8 · OV2640 2MP · VL53L5CX V2 · **MPU-6050 GY-521***

*Arsitektur: ESP32-S3 (EKF IMU + sensor hub + fail-safe) ↔ WebSocket WiFi ↔ Smartphone (YOLO11 + Formula B–J + TTS + K fail-safe di ESP32)*

*Versi: 8.0 | Tanggal revisi: 2026-05-24*
