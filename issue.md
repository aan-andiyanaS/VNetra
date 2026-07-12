### ADR-020: Perbaikan Bug Pola Anomali Kolom (8x8 & 4x4) pada TerrainDetector
- **Status:** Diterapkan (12 Juli 2026)
- **Konteks:** Sistem peringatan Terrain (HOLE dan STAIR_DOWN) bergantung pada pola anomali kolom. Namun, pada resolusi 8x8 dan 4x4, kalkulasi menggunakan standar deviasi (sigmaJ) gagal karena zona rLowRows terkadang hanya terdiri dari 1 baris, sehingga variansi menjadi 0 dan terrain berbahaya selalu ter-bypass menjadi SAFE.
- **Keputusan:** Membuang kalkulasi variansi/standar deviasi. Menggantinya dengan perhitungan **Selisih Relatif Absolut**.
- **Mekanisme Perbaikan:**
  1. **8x8**: Sistem kini membandingkan langsung nilai tiap kolom di rLowRows[0] dengan rata-rata zona aman (zMid). Jika selisihnya > 20cm (SIGMA_COL_TH), kolom tersebut dianggap anomali.
  2. **4x4**: Sistem membandingkan rata-rata nilai tiap kolom di rLowRows dengan rata-rata zona aman atas (zHigh), mempertahankan ketahanan deteksi bahkan saat sensor otomatis beralih resolusi di cuaca terang.
  3. Beban CPU berkurang karena loop variansi ganda dan operasi Math.sqrt() telah dihilangkan.


### ADR-021: Stabilitas Koneksi & Toleransi Kegagalan Sensor (Report.md Points 4 & 5)
- **Status:** Diterapkan (12 Juli 2026)
- **Konteks:** Laporan peninjauan mutu (
eport.md) menyoroti dua masalah stabilitas:
  1. *Holdover Frame* ToF yang terlalu singkat (0.5s) menyebabkan peringatan bergetar/terputus di luar ruangan saat aspal gelap menyerap gelombang inframerah.
  2. Kebocoran port AsyncUDP pada ESP32 yang berisiko membuat *Boot Loop* (LoadStoreError) jika mikrokontroler mencoba menghubungkan ulang WiFi tanpa mematikan ikatan port lama.
- **Keputusan:** Meningkatkan ambang batas toleransi pembacaan ToF dan menerapkan *graceful shutdown* pada soket jaringan ESP32.
- **Mekanisme Perbaikan:**
  1. **Aplikasi VNetra (CameraStreamActivity.kt)**: Mengubah toleransi kegagalan pembacaan dari 5 frame menjadi 15 frame (~1.5 detik).
     ```kotlin
     private val HOLDOVER_FRAMES = 15 // Naik dari 5 frame
     ```
  2. **Firmware ESP32 (firmware-vnetra.ino)**: Menambahkan instruksi pembongkaran port pada awal siklus inisialisasi server untuk menjamin tidak ada port yang bertabrakan.
     ```cpp
     void startCameraServer() {
         // Graceful shutdown untuk mencegah crash (LoadStoreError)
         server.end();
         udpSensor.close();
         
         ws.onEvent(onWsEvent);
         // ...
     }
     ```
