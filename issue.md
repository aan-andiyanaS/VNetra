# ADR-004: Mekanisme A2DP Keep-Alive (Zero Wake-Up Delay) untuk Bluetooth TTS

## Status
Proposed

## Date
2026-07-11

## Context
Perangkat *headset* Bluetooth (khususnya TWS modern) menggunakan protokol *power saving* bernama *Sniff Mode* pada protokol A2DP. Jika tidak ada suara yang diputar dari HP selama beberapa detik, koneksi Bluetooth akan "tertidur" untuk menghemat baterai. 
Ketika modul YOLO atau ToF memicu `TtsAlertManager.speak()`, HP Android harus "membangunkan" jalur audio Bluetooth (transisi dari *Sniff Mode* ke *Active Mode*). Proses transisi ini (*wake-up delay*) umumnya memakan waktu **500 ms hingga 1000 ms**, yang menyebabkan efek:
1. Kata pertama dari peringatan terpotong (misal: "Tangga" hanya terdengar "...gga").
2. Suara masuk sangat terlambat (jauh dari target 150-200 ms *end-to-end* yang kita hitung).

Bagi penyandang tunanetra, jeda setengah detik ini berbahaya karena bisa menyebabkan keterlambatan respon fisik.

## Decision
Menerapkan teknik **A2DP Keep-Alive** dengan cara memutar aliran audio PCM statis (diam total / *silent buffer*) yang berulang secara terus-menerus (di-loop) di *background*.
1. **AudioTrack Taktis:** Membuat objek `AudioTrack` di `TtsAlertManager` yang memompa sampel angka 0 secara kontinu. Hal ini "menipu" protokol Bluetooth agar mengira lagu sedang diputar, sehingga jalur audio tidak pernah tidur.
2. **Audio Attributes Khusus:** Mengatur properti TTS Engine agar diprioritaskan menggunakan `USAGE_ASSISTANCE_ACCESSIBILITY` dan `CONTENT_TYPE_SPEECH`.

## Consequences
- **Keuntungan:** Waktu tunda bangun (*wake-up delay*) Bluetooth akan menjadi **0 milidetik (nol)**. Suara TTS akan langsung menembus seketika begitu *inference* YOLO selesai. Tidak ada kata terpotong.
- **Kelemahan:** Baterai *headset* Bluetooth akan sedikit lebih boros karena radio Bluetooth dipaksa untuk terus aktif (*Active Mode*) selama aplikasi VNetra berjalan. Namun, ini adalah pertukaran (*trade-off*) mutlak demi keselamatan pengguna tunanetra.
