# Formula G — Adaptive Alert Threshold (v9.4)

> **Dokumen:** Spesifikasi matematika lengkap mekanisme threshold adaptif peringatan rintangan
> **Versi:** 9.4 | **Tanggal:** 2026-07-25
> **Referensi implementasi:** `TtsAlertManager.kt` · `TtcManager.kt` · `firmware-vnetra.ino`

---

## 1. Tiga Sumber $d_\text{obj}$

Sebelum Formula G dijalankan, jarak objek $d_\text{obj}$ diperoleh dari **tiga jalur**:

| Jalur | Asal | Keterangan |
|-------|------|------------|
| **A** — ToF + Centroid YOLO | `tofCollectJob` Tahap 1 | Formula E: rata-rata 3 baris ToF di kolom $j$, kompensasi pitch via $\theta_\text{mount}$ |
| **B** — ToF + Bounding Box YOLO | `processInstantYoloTts` Tahap 2 | Formula E + override Formula I jika ToF buta |
| **C** — Terrain | `tofCollectJob` Terrain | Formula H/J untuk paving dan selokan |

Ketiganya menghasilkan $d_\text{obj} \in [\varepsilon_\text{noise},\ D_\text{max}]$ dalam satuan **mm**.

### 1.1 Formula I — Override ToF (Jalur B saja)

Ketika ToF gagal (outdoor/siang), Formula I mengestimasi ancaman dari pelebaran bounding box YOLO:

$$\Delta A = \frac{\overline{A}_\text{now} - \overline{A}_\text{prev}}{\overline{A}_\text{prev}}$$

$$s_\text{area} = \text{clamp}\!\left(\frac{\Delta A}{0.5},\ 0,\ 1\right)$$

$$s_\text{AR} = \max\!\left(0,\ 1 - \frac{|\Delta \text{AR}|}{0.2}\right)$$

$$s_\text{dist} = \begin{cases} 1.0 & \text{if } d_\text{prev} - d_\text{obj} > 30 \text{ mm} \\ 0.5 & \text{if ToF invalid/blind} \\ 0.0 & \text{otherwise} \end{cases}$$

**Lateral drift guard** (kompensasi yaw head dari IMU):

$$\Delta C_x^\text{drift} = \left| C_x - \left(C_{x,\text{prev}} + \omega_z \cdot \Delta t_\text{frame} \cdot p_{\deg}\right) \right|$$

$$s_\text{drift} = \text{clamp}\!\left(\frac{\Delta C_x^\text{drift}}{60},\ 0,\ 1\right), \quad g_\text{drift} = s_\text{drift} \cdot \mathbf{1}[s_\text{dist} \le 0.5]$$

**TTC Score gabungan** dengan bobot kelas $m_c$:

$$\text{TTC}_\text{score} = \bigl(0.50\, s_\text{area} + 0.25\, s_\text{AR} + 0.25\, s_\text{dist}\bigr)\cdot(1 - g_\text{drift})$$

$$\text{TTC}_w = \text{clamp}\!\left(\text{TTC}_\text{score} \cdot m_c,\ 0,\ 1\right)$$

$$d_\text{obj}^\text{override} = \begin{cases} 500\ \text{mm} & \text{if } \text{TTC}_w > 0.75 \quad (\textit{IMMINENT}) \\ 1000\ \text{mm} & \text{if } \text{TTC}_w > 0.40 \quad (\textit{PROBABLE}) \\ \text{—} & \text{if } \text{TTC}_w \le 0.40 \quad (\textit{POSSIBLE, diabaikan}) \end{cases}$$

---

## 2. Input Formula G

| Simbol | Sumber | Satuan | Keterangan |
|--------|--------|--------|------------|
| $d_\text{obj}[t]$ | ToF / Formula I | mm | Jarak objek frame ini |
| $d_\text{obj}[t-1]$ | State per tracking ID | mm | Jarak objek frame sebelumnya |
| $\Delta t$ | $(ts_\text{ESP}[t] - ts_\text{ESP}[t-1]) / 1000$ | s | Interval waktu; $\Delta t \in [0.001,\ 0.5]$ |
| $v_\text{head\_base}$ | `imuData[7]` — firmware | rad/s | Kecepatan semu kepala (berbasis gyroscope) |
| $a_\text{lin}$ | `imuData[5]` — firmware | m/s² | Akselerasi linear dinamis tubuh (berbasis accelerometer) |

---

## 3. Asal Sinyal IMU dari Firmware ESP32

### 3.1 $v_\text{head\_base}$ — Berbasis Gyroscope $\omega_x$

Dihitung di firmware pada laju **200 Hz** ($\Delta t_\text{IMU} = 5\ \text{ms}$):

$$k_\text{damp} = \begin{cases} 0.5 & \text{if } |\omega_{x,\text{corr}}| > 5\ {}^\circ\!/\text{s} \\ 1.0 & \text{otherwise} \end{cases}$$

$$\boxed{v_\text{head\_base} = k_\text{damp} \cdot |\omega_{x,\text{corr}}| \cdot \cos\theta}$$

di mana $\omega_{x,\text{corr}} = \omega_x - b_{\omega x}$ adalah pitch rate setelah bias removal (rad/s), dan $\theta$ adalah pitch angle dari filter Mahony (rad).

**Interpretasi fisika:** Saat kepala mengangguk dengan kecepatan $\omega_x$ pada jarak $r \approx d_\text{obj}$, sensor ToF bergerak busur sehingga jarak ke objek diam berubah secara semu. Perubahan jarak semu per satuan waktu $\approx \omega_x \cdot d_\text{obj} \cdot \cos\theta$.

### 3.2 $a_\text{lin}$ — Berbasis Accelerometer (3-Tahap)

**Tahap 1 — Gravity Removal:**

$$\vec{g}_\text{rot} = g \begin{pmatrix} 2(q_x q_z - q_w q_y) \\ 2(q_w q_x + q_y q_z) \\ q_w^2 - q_x^2 - q_y^2 + q_z^2 \end{pmatrix}$$

$$a_\text{lin,raw} = \left\| \vec{a}_\text{corr} - \vec{g}_\text{rot} \right\|$$

**Tahap 2 — EMA Smoothing** ($\alpha = 0.4$):

$$a_\text{smooth}[t] = \alpha \cdot a_\text{lin,raw} + (1 - \alpha)\cdot a_\text{smooth}[t-1]$$

**Tahap 3 — DC Bias Removal** (hanya aktif saat $a_\text{smooth} < 1.5\ \text{m/s}^2$):

$$b_\text{DC}[t] = \begin{cases} 0.005 \cdot a_\text{smooth} + 0.995 \cdot b_\text{DC}[t-1] & \text{if } a_\text{smooth} < 1.5 \\ b_\text{DC}[t-1] & \text{otherwise} \end{cases}$$

$$\boxed{a_\text{lin} = \max\!\bigl(0,\ a_\text{smooth} - b_\text{DC}\bigr)}$$

---

## 4. Formula G — Derivasi Lengkap

### G.0 — Guard Konvergensi Mahony

$$T = \begin{cases} D_{W_0} & \text{if } c_\text{conv} \le 0.5 \quad \text{(warming up, skip G.1–G.3)} \\ \text{hitung G.1–G.3} & \text{otherwise} \end{cases}$$

Filter Mahony membutuhkan $\pm 100$ frame ($\approx 5$ detik) untuk konvergen setelah boot.

---

### G.1 — Kecepatan Semu Kepala

Konversi artefak angular ke kecepatan linear pada jarak $d_\text{obj}$ (kecepatan busur $= \omega \cdot r$):

$$\boxed{v_\text{head} = v_\text{head\_base} \cdot d_\text{obj}[t] \quad [\text{mm/s}]}$$

---

### G.2 — Kecepatan Pendekatan Bersih

$$v_\text{raw} = \frac{d_\text{obj}[t-1] - d_\text{obj}[t]}{\Delta t} - v_\text{head}$$

$$\boxed{v_\text{raw} \leftarrow \max(0,\ v_\text{raw}) \quad [\text{mm/s}]}$$

**Komponen:**
- $\dfrac{d_\text{obj}[t-1] - d_\text{obj}[t]}{\Delta t}$ — kecepatan pendekatan kasar dari selisih ToF
- $- v_\text{head}$ — dikurangi artefak nod kepala

---

### G.2a — Noise Gate $a_\text{lin}$ (ADR-017)

Untuk objek **statis** (tembok / paving), berlaku prinsip fisika:

> *"Jika pengguna diam, kecepatan pendekatan objek statis pasti noise sensor."*

$$\text{Jika } \text{isStatic}(id) \text{ DAN } a_\text{lin} < a_\text{gate} = 2.94\ \text{m/s}^2 \text{ :}$$

$$\boxed{v_\text{raw} \leftarrow 0}$$

Threshold $a_\text{gate} = 2.94\ \text{m/s}^2 = 0.3\,g$ adalah batas empiris antara diam/berjalan.

---

### G.2b — Moving Average 3-Frame

$$v_\text{avg} = \frac{v_\text{raw}[t] + v_\text{raw}[t-1] + v_\text{raw}[t-2]}{|\{v_\text{raw}[k] > 0 : k \in \{t, t-1, t-2\}\}|}$$

Mengurangi jitter ToF antar frame.

---

### G.3 — Threshold Adaptif

$$\text{momentum\_buffer} = k_m \cdot a_\text{lin} \quad [\text{mm}], \quad k_m = 200\ \text{mm}\cdot\text{s}^2/\text{m}$$

$$\boxed{T = \min\!\Bigl(D_{W_0} + v_\text{avg} \cdot t_R + k_m \cdot a_\text{lin},\ T_\text{max}\Bigr) \quad [\text{mm}]}$$

**Dekomposisi tiga komponen:**

$$T = \underbrace{D_{W_0}}_{\text{minimum statis}} + \underbrace{v_\text{avg} \cdot t_R}_{\text{ekstensi kecepatan objek}} + \underbrace{k_m \cdot a_\text{lin}}_{\text{ekstensi kecepatan user}}$$

| Komponen | Rentang Tipikal | Peran |
|----------|----------------|-------|
| $D_{W_0} = 1000\ \text{mm}$ | konstan | Jarak aman minimum |
| $v_\text{avg} \cdot t_R$ | $0 - 3000\ \text{mm}$ | Makin cepat objek mendekat → threshold lebih jauh |
| $k_m \cdot a_\text{lin}$ | $0 - 2000\ \text{mm}$ | Makin cepat user berlari → threshold lebih jauh |

---

## 5. Kondisi Trigger Alert

$$\text{ALERT} \iff d_\text{obj}[t] < T \quad \text{DAN} \quad \text{flag}[id] = \texttt{false}$$

$$\text{RESET} \iff d_\text{obj}[t] > D_\text{reset} = D_{W_0} + \varepsilon_\text{noise} = 1500\ \text{mm}$$

Hysteresis $D_\text{reset} > T$ mencegah rapid-fire jika $d_\text{obj}$ berosilasi di sekitar batas.

---

## 6. Skenario Numerik

| Kondisi | $a_\text{lin}$ | $v_\text{avg}$ | $T$ |
|---------|---------------|----------------|-----|
| Diam, objek diam | $0\ \text{m/s}^2$ | $0\ \text{mm/s}$ | $1000\ \text{mm}$ |
| Berjalan pelan, objek diam | $2\ \text{m/s}^2$ | $0\ \text{mm/s}$ | $1400\ \text{mm}$ |
| Berjalan normal, objek mendekat | $3\ \text{m/s}^2$ | $200\ \text{mm/s}$ | $2000\ \text{mm}$ |
| Berlari, objek mendekat cepat | $6\ \text{m/s}^2$ | $500\ \text{mm/s}$ | $3200\ \text{mm}$ |

---

## 7. Peran Dua Sinyal IMU — Ringkasan

$$v_\text{head\_base} \xrightarrow{\text{G.1}} \text{hapus artefak nod kepala dari } v_\text{raw}$$

$$a_\text{lin} \xrightarrow{\text{G.2a}} \text{gate biner: } v_\text{raw} = 0 \text{ saat user diam}$$

$$a_\text{lin} \xrightarrow{\text{G.3}} T \mathrel{+}= k_m \cdot a_\text{lin} \text{ (buffer momentum berjalan)}$$

| Sinyal | Hardware | Step | Peran |
|--------|----------|------|-------|
| $v_\text{head\_base}$ | Gyroscope $\omega_x$ | G.1 | Kompensasi artefak nod kepala pada kecepatan pendekatan |
| $a_\text{lin}$ | Accelerometer | G.2a | Gate: paksa $v_\text{raw}=0$ untuk objek statis saat diam |
| $a_\text{lin}$ | Accelerometer | G.3 | Buffer: perbesar $T$ proporsional intensitas gerakan user |

---

## 8. Konstanta Sistem

| Simbol | Nilai | Satuan | Keterangan |
|--------|-------|--------|------------|
| $D_{W_0}$ | 1000 | mm | Threshold minimum statis |
| $D_\text{reset}$ | 1500 | mm | Batas hysteresis reset flag |
| $\varepsilon_\text{noise}$ | 500 | mm | Margin hysteresis |
| $t_R$ | 2.0 | s | Waktu reaksi manusia |
| $k_m$ | 200 | mm·s²/m | Koefisien scaling $a_\text{lin}$ |
| $T_\text{max}$ | 4000 | mm | Batas atas threshold (jangkauan ToF) |
| $a_\text{gate}$ | 2.94 | m/s² | Noise gate "user diam" $(= 0.3\,g)$ |
| $\alpha_\text{EMA}$ | 0.4 | — | Koefisien smoothing $a_\text{lin}$ |
| $\Omega_\text{lim}$ | 5.0 | °/s | Batas $k_\text{damp}$ pada $v_\text{head\_base}$ |
