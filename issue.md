# Laporan ADR: VNetra (ADR-022 - Toleransi ToF Outdoor)

## ADR-022: Relaksasi Filter `target_status` Sensor ToF untuk Toleransi Lingkungan Outdoor

- **Status:** Diterapkan (12 Juli 2026)

### 1. Konteks
Sistem VNetra sangat bergantung pada sensor Time-of-Flight (VL53L5CX) untuk mendeteksi rintangan dan meraba bentuk permukaan jalan (*Terrain Detector*). Untuk meminimalisir gangguan cahaya matahari, perangkat keras telah dilengkapi dengan **IR Narrow Bandpass Filter 940nm**. 

Meski demikian, ketika diuji di luar ruangan (*outdoor*) di bawah sinar matahari langsung, aplikasi Android masih sering mendadak menampilkan simbol `--` (data jarak tidak valid). Hasil investigasi menunjukkan bahwa masalah ini bukan berasal dari sensor yang gagal membaca jarak, melainkan dari logika *sanity check* di dalam fungsi pengemasan UDP pada *firmware* ESP32 yang terlalu agresif.

Cahaya matahari secara drastis meningkatkan *ambient noise* pada foton inframerah yang dipancarkan. Sensor VL53L5CX sebenarnya masih berhasil menghitung jarak objek dengan tepat, namun ia memberikan label `target_status` yang berbeda untuk memperingatkan adanya *noise* tinggi, yakni:
- `13`: *Target Valid with High Ambient Noise* (Khas kondisi *outdoor* siang hari).
- `12`: *Target Valid, No Wrap Around Check*.
- `10`: *Target Close*.

Kode sebelumnya secara eksplisit menolak status-status ini dan membuang datanya (menggantinya dengan `-1`), menyebabkan aplikasi menganggap bahwa sensor sedang buta, padahal jarak yang terdeteksi valid.

### 2. Keputusan
Kita melonggarkan batas filter `target_status` pada rutin pengiriman data UDP di dalam ESP32 (`firmware-vnetra.ino`).

**Mekanisme Perbaikan:**
Kita memperluas daftar status yang diterima (*whitelisted statuses*) untuk mengakomodasi degradasi sinyal yang wajar (*graceful degradation*) di lingkungan luar ruangan.
Status `10`, `12`, dan `13` kini secara resmi diterima dan diteruskan ke *client* Android sebagai data valid.

```cpp
// Sebelum: Terlalu kaku, membuang data yang mengandung noise matahari
bool statusOk = (st == 5 || st == 6 || st == 9);

// Sesudah: Toleransi terhadap noise outdoor
// Terima status 5 (valid), 6 (wrap-around), 9 (merged pulse)
// + 10 (target close), 12 (no wrap check), 13 (high ambient noise - ciri khas outdoor)
bool statusOk = (st == 5 || st == 6 || st == 9 || st == 10 || st == 12 || st == 13);
```

### 3. Konsekuensi
- **Positif:** Ketersediaan data ToF (*uptime*) di bawah sinar matahari terik meningkat drastis. Simbol `--` akan jarang muncul karena aplikasi tidak lagi menolak pengukuran valid yang hanya memiliki skor *confidence* lebih rendah akibat cahaya luar.
- **Positif (Stabilitas Keamanan):** Sistem *Terrain Detector* tetap aman dari *false-positive*. Sel sensor yang benar-benar rusak (misal akibat *multipath* optik) akan menghasilkan status `1` (Sigma Fail), `2` (Signal Fail), atau `4` (Phase Fail). Status cacat fatal ini tetap **tidak dimasukkan** ke dalam *whitelist*, sehingga data halusinasi tetap diganti dengan `-1`.
- **Negatif:** Resolusi kepastian absolut sedikit berkurang pada kondisi *outdoor*, namun hal ini dapat diatasi oleh *Momentum Buffer* dan interpolasi pada aplikasi Android.
