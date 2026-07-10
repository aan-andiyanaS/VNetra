# ADR-009: Peningkatan Akurasi Akselerometer (Mahony LPF & Kp Tuning)
[... See previous history ...]

---

# ADR-010: Dynamic DC Bias Removal (High-Pass Filter) untuk Akselerometer

## Status
Proposed

## Date
2026-07-11

## Context
Meski kita telah menerapkan *Low-Pass Filter* (EMA) dan *Noise Gate* pada ADR-009, pengguna mendapati bahwa nilai `a_lin_mag` mentah tetap bertengger di angka ~0.5 m/s² saat kacamata tidak digerakkan sama sekali. Hal ini menyebabkan sensor *ToF / YOLO* mengira pengguna terus bergerak maju (*isMovingForward* tersangkut di logika `> 0.3`). 
Ini terjadi karena ada penyimpangan mekanis statis (*DC Offset / Bias*) sebesar 0.05 G pada sensor fisik MPU6050 murah yang belum pernah dikalibrasi sempurna.

## Doubt-Driven Analysis & Literature Search
Berdasarkan pencarian metode komputasi termurah untuk sensor MEMS murah melalui AI Perplexity dan riset pustaka, sebuah LPF standar tidak akan pernah menghilangkan offset statis, ia hanya akan *meratakannya* (0.5 yang bergetar menjadi 0.5 yang mulus rata). 
Untuk mendapatkan kecepatan dinamik (*true dynamic linear acceleration*), kita memerlukan **High-Pass Filter (HPF)** atau metode integrasi bocor (*Leaky Integrator* / DC-Blocker). 

## Decision (Ponytail Approach)
Solusi paling ringan tanpa perhitungan matriks yang berat adalah menggunakan sepasang filter bertingkat:
1. **Fast LPF:** (`a_lin_smooth`) untuk mematikan *jitter* elektrik berfrekuensi tinggi.
2. **Super Slow LPF:** (`a_lin_dc_bias`) untuk menangkap nilai 0.5 m/s² secara perlahan, hanya saat tidak ada goncangan ekstrem.
3. **Dynamic Output:** Pengurangan simpel `a_lin_dynamic = a_lin_smooth - a_lin_dc_bias`. 

Dengan ini, apapun nilai kasarnya saat diam (0.5, 0.8, 1.2 m/s²), *Super Slow LPF* akan mengejar angka tersebut, menolkan hasil akhir ke 0.0 m/s². Saat pengguna tiba-tiba melangkah maju, pergerakan tajam tersebut akan lolos sebagai m/s² bersih.

## Proposed Changes
### `firmware-vnetra.ino`
- **Di dalam fungsi `IMU_Task` (baris kalkulasi `a_lin_mag`):**
  - Ganti logika pemulusan statis dengan logika pemotongan *DC Offset* dinamis:
    ```cpp
    static float a_lin_smooth = 0.0f;
    static float a_lin_dc_bias = 0.0f;
    
    a_lin_smooth = (0.1f * a_lin_mag_raw) + (0.9f * a_lin_smooth);
    
    // Hanya tangkap bias saat relatif diam (cegah goncangan berjalan merusak titik 0)
    if (a_lin_smooth < 1.5f) {
        a_lin_dc_bias = (0.005f * a_lin_smooth) + (0.995f * a_lin_dc_bias);
    }
    
    float a_lin_dynamic = a_lin_smooth - a_lin_dc_bias;
    if (a_lin_dynamic < 0.0f) a_lin_dynamic = 0.0f; // Clamp lantai
    
    if (a_lin_dynamic < 0.2f) {
        a_lin_dynamic = 0.0f; // Noise gate final
    }
    ```
  - Masukkan `a_lin_dynamic` ke dalam payload UDP `[5]`.

## Consequences
- **Positif:** Angka `a_lin` di aplikasi Android akan 100% stabil di 0.00 m/s² saat statis, bebas dari anomali *spam* arah maju.
- **Positif:** 0% penggunaan memori tambahan. CPU mikrokontroler hanya melakukan penambahan/pengurangan sederhana tanpa kalkulasi matriks 7x7 yang lambat.
