# Laporan Analisis: Batas Jarak ToF di Bawah Sinar Matahari (Outdoor)

**Tanggal:** 2026-07-18
**Konteks:** Sensor ToF (VL53L5CX) hanya mampu mendeteksi objek pada jarak maksimal ~1 meter saat siang hari / terik matahari. Objek di luar jarak tersebut ditampilkan sebagai `"--"`.

## 1. Analisis Akar Masalah (Fisika & Hardware)

Gejala ini **bukanlah sebuah *bug* pada kode program (software)**, melainkan keterbatasan fisik absolut dari teknologi *Time-of-Flight* (ToF) berbasis SPAD (*Single Photon Avalanche Diode*) yang beroperasi di lingkungan *outdoor*.

1. **Cara Kerja ToF:** Sensor VL53L5CX menembakkan laser inframerah (IR VCSEL) pada panjang gelombang 940nm. Ia kemudian menghitung waktu pantulan foton laser tersebut saat kembali ke sensor.
2. **Hukum Kuadrat Terbalik (*Inverse Square Law*):** Intensitas pantulan laser yang kembali akan melemah secara eksponensial seiring bertambahnya jarak.
3. **Saturasi Cahaya Matahari (*Ambient Noise*):** Sinar matahari terik mengandung spektrum inframerah 940nm yang sangat masif (bisa mencapai 80.000 - 100.000 *lux*). 
4. **SNR (*Signal-to-Noise Ratio*) Hancur:** Ketika objek berada di jarak > 1 meter, intensitas pantulan laser dari sensor sudah sangat lemah. Foton pantulan tersebut "tenggelam" sepenuhnya oleh lautan foton inframerah dari sinar matahari. Sensor menjadi "buta" (tersaturasi) karena tidak bisa membedakan mana foton lasernya sendiri dan mana foton matahari.

*(Referensi: Datasheet resmi STMicroelectronics untuk VL53L5CX menyebutkan secara eksplisit bahwa jarak deteksi maksimal akan turun dari 400cm (indoor/gelap) menjadi hanya sekitar 80cm - 120cm di bawah ambient light yang sangat tinggi).*

## 2. Bedah Kode Firmware (Doubt-Driven Development)

Mari kita bedah apa yang terjadi di tingkat kode pada ESP32 (`firmware-vnetra.ino`).

Saat SNR (rasio sinyal terhadap noise) hancur akibat matahari, sensor VL53L5CX sebenarnya tidak mendadak mati. Ia masih merespons, tetapi memberikan **Target Status** yang menunjukkan bahwa datanya berupa "sampah" (noise acak).

Pada baris `1176 - 1184` di firmware:
```cpp
// Kita hanya menerima status yang dijamin keakuratannya oleh STMicroelectronics:
// 5, 6, 9, 10, 12, dan 13 (Valid dengan High Ambient Noise).
bool statusOk = (st == 5 || st == 6 || st == 9 || st == 10 || st == 12 || st == 13);
bool rangeOk  = (dist >= (int16_t)TOF_MIN_DIST_MM && dist <= (int16_t)TOF_MAX_DIST_MM);

if (statusOk && rangeOk) {
    filtered_dist[ci] = dist;
} else {
    // -1 = sentinel: "tidak ada target valid"
    filtered_dist[ci] = -1; 
}
```

**Apa yang terjadi saat terik matahari jarak > 1 meter?**
Sensor akan memuntahkan status seperti `1` (*Sigma fail*), `2` (*Signal fail - low SNR*), atau `255` (*No target*). 
Karena status ini gagal melewati seleksi `statusOk`, firmware secara defensif me-reset nilai jarak tersebut menjadi `-1` (Sentinel). Di aplikasi Android, nilai jarak `<= 0` (yang dipicu oleh `-1`) akan di-render sebagai `"--"`.

## 3. Filosofi Ponytail & Clean Code: Kenapa dibiarkan "--" ?

*Ponytail Senior Dev akan bertanya: "Bisa nggak status error dari sensor itu kita paksa anggap valid saja biar layarnya nggak nampilin -- ?"*

**Jawabannya: BISA, TAPI SANGAT BERBAHAYA.**

Jika kita menghapus filter `statusOk` dan memaksa menerima *Sigma/Signal fail*:
- Sensor akan melempar angka *random* (noise). Sel A mungkin menunjukkan 200mm, lalu sedetik kemudian 3500mm, lalu 50mm. 
- Aplikasi Android akan mengira ada tembok di jarak 5cm (padahal tidak ada).
- Smartphone akan memberikan *haptic feedback* (getaran) peringatan halangan dengan sangat kencang.
- Pengguna tunanetra akan ketakutan/kebingungan mendapat peringatan palsu (*False Positives*) terus menerus di siang hari.

Oleh karena itu, membuang data noise dan menggantinya dengan `"--"` (yang berarti "Sensor sedang tidak bisa melihat objek valid di sel ini") adalah **keputusan engineering yang paling aman, paling bersih (clean), dan paling masuk akal (sensible)**.

## 4. Upaya Mitigasi yang Sudah Dilakukan di Kode

Apakah kita sudah berusaha memaksimalkan jangkauan di siang hari? **Sudah.**

Pada baris `1305` di `firmware-vnetra.ino`:
```cpp
myImager.setIntegrationTime(tofResolution == 4 ? 20 : 30);
```
Kita telah menurunkan *Integration Time* (waktu eksposur) menjadi `30ms` (untuk mode 8x8). Ini dilakukan secara spesifik agar reseptor SPAD tidak terlalu cepat "kepenuhan" (saturasi) saat dihantam sinar matahari. Jika *integration time* di-set ke *default* (50ms+), sensor justru akan buta total (0 meter) di bawah terik matahari. 

## Kesimpulan

1. **Bukan Bug Kode:** Jarak mentok 1 meter saat siang bolong adalah limitasi fisika sensor *Time-of-Flight* konvensional.
2. **Software Bekerja dengan Benar:** Pemunculan `"--"` adalah hasil dari sistem *filtering* (penyaringan status target) yang berjalan dengan sempurna untuk memblokir *noise*.
3. **Jangan Diubah:** Mencoba "mengakali" batasan ini di *software* hanya akan membahayakan pengguna karena memicu peringatan halangan palsu. Biarkan sistem kamera (*YOLO*) yang mengambil alih peran penglihatan jarak jauh (>1 meter) di siang hari.
