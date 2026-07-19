# ADR-043: Zero-Delay TTS State Machine

## Status
Accepted

## Date
2026-07-19

## Context
Terdapat empat masalah utama terkait logika `TtsAlertManager` dan State Machine yang mengganggu kenyamanan dan responsivitas interaksi pengguna:
1. **Spam Duduk:** Sistem terus meneriakkan peringatan secara berulang (menggaung) jika pengguna goyang sedikit saat duduk menghadap tembok, karena objek keluar-masuk batas _threshold_.
2. **Bug Mute/Auto-Unmute:** Saat sistem di-_mute_, sistem tetap menghafal rintangan (mencatatnya ke dalam flag `alreadyAlerted`). Akibatnya, saat _auto-unmute_ aktif (dipicu ketika melangkah), sistem mengira pengguna sudah diberi tahu dan gagal meneriakkan peringatan secara instan.
3. **Bug Menunduk (Floor Detection):** Saat pengguna menunduk melebihi 20 derajat (misalnya untuk meraba jalan atau sepatu), sensor ToF membaca lantai sebagai dinding berjarak dekat (< 1000mm), memicu peringatan "Tembok" palsu.
4. **Delay Scanning:** Adanya jeda peringatan (_cooldown_) saat beralih dari diam ke melangkah akibat _state machine_ yang menunda respons untuk menghindari *spam*.

## Decision
Menerapkan konsep **Ponytail Zero-Delay Responsive Scanning** dengan menyederhanakan _state machine_:

1. **Penerapan Amnesia Sementara (Mute Fix):** Memeriksa `isMuted` sebelum menetapkan flag `alreadyAlerted`. Jika sistem dalam keadaan _mute_, tidak ada rintangan yang dimasukkan ke dalam memori. Saat di-_unmute_, semua rintangan akan dianggap sebagai ancaman baru.
2. **Zero-Delay Scanning (Spam Fix):** Memeriksa jika rintangan bersifat statis (Tembok/Terrain) dan pengguna dalam keadaan diam (`!isMovingForward`), maka peringatan dan pencatatan flag ditahan (`return null`). Peringatan akan langsung menyala secara instan pada milidetik ketika pengguna mengambil langkah pertama.
3. **Kompensasi Pitch Ekstrem:** Memanfaatkan IMU Data. Jika sudut `pitch > 20` derajat ke bawah, rintangan statis (Tembok/Terrain) diabaikan sepenuhnya agar lantai tidak memicu peringatan palsu.

## Alternatives Considered
- **Menyesuaikan nilai threshold cooldown (`lastSpokenMs`):** Ditolak karena tetap akan ada delay 1 detik. Delay sekecil apa pun saat pengguna mulai melangkah menuju tembok adalah berbahaya.
- **Menggeser `rCenter` ToF ke baris atas saat menunduk:** Ditolak karena pada sudut > 30 derajat, seluruh baris FOV sensor ToF (bahkan baris paling atas) sudah menghadap ke lantai. Abaikan melalui flag State Machine jauh lebih aman dan robust.

## Consequences
- _Head scanning_ (mencari jalan dengan menoleh) bekerja sempurna dan bisu, hanya berbunyi saat pengguna memutuskan untuk melangkah ke suatu arah.
- Menghilangkan gangguan _spam_ suara saat pengguna sedang duduk atau beristirahat menghadap halangan.
- Keamanan saat menunduk terjamin tanpa mengorbankan _awareness_ spasial.
