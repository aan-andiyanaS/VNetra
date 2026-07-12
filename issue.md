
### ADR-016: Implementasi Penuh Formula G (Adaptive Distance Threshold)
- **Status:** Diterapkan (12 Juli 2026)
- **Konteks:** Sistem peringatan jarak jauh sebelumnya bersifat pasif dan hanya mengandalkan nilai statis (1000 mm) sebagai batas bahaya (`D_W0`). Jika ada motor mendekat sangat cepat dari jarak 3000 mm, pengguna tidak akan diperingatkan sampai motor itu masuk ke batas 1000 mm, yang mana sudah terlambat karena waktu reaksi manusia ~2 detik.
- **Keputusan:** Kami mengimplementasikan logika **Formula G** secara utuh ke dalam *engine* utama TTS (`TtsAlertManager.kt`). Implementasi ini berlaku universal untuk objek yang dideteksi oleh YOLO maupun halangan fisik (Tembok) dari sensor ToF.
- **Mekanisme:**
  - Mengambil data EKF (`ts_esp`, `v_head_base`, `is_converged`) langsung dari paket WebSocket IMU.
  - Menghitung $\Delta t$ dan mengkompensasinya dengan batas aman `[0.001, 0.5]` detik (Formula G.0).
  - Menghitung efek ilusi kecepatan rotasi kepala ($v_{head}$) untuk meredam *false positive* saat pengguna menoleh (Formula G.1).
  - Mengekstrak *Approach Velocity* (kecepatan pendekatan $v_{raw}$) dan meratakannya menggunakan *Moving Average* 3-frame untuk meredam *jitter* sinyal WiFi (Formula G.2).
  - Mengubah *threshold* batas bahaya statis ($1000$ mm) menjadi batas adaptif $T = \min(1000 + ar{v} 	imes 2.0,\ 4000)$.
- **Konsekuensi:** 
  - Kini VNetra memiliki **kesadaran spasial-temporal (4D)**. Objek yang bergerak cepat menabrak pengguna dari kejauhan akan langsung memicu peringatan seketika, memberikan waktu reaksi minimal 2 detik terlepas dari seberapa cepat mereka mendekat.
  - Di sisi lain, *overhead* kalkulasi per frame meningkat sedikit (meskipun menggunakan algoritma *moving average* konstan $O(1)$ untuk menekan beban CPU Android).
