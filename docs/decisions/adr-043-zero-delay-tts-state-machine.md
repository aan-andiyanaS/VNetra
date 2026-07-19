# 🐛 Bug: Masalah Sensor TTS (Mute, Duduk, dan Pitch)

## 📌 Deskripsi Masalah
Ada 4 masalah utama terkait logika `TtsAlertManager` dan State Machine yang mengganggu kenyamanan pengguna:
1. **Spam Duduk:** Sistem terus meneriakkan peringatan secara berulang (menggaung) jika pengguna goyang sedikit saat duduk menghadap tembok.
2. **Auto-Unmute Gagal:** Saat sistem di-mute, ia tetap menghafal rintangan. Sehingga ketika auto-unmute aktif (saat melangkah), sistem mengira peringatan sudah diberikan dan gagal berteriak tepat waktu.
3. **Bug Menunduk:** Saat pengguna menunduk > 20 derajat untuk melihat jalan/sepatu, sensor ToF membaca lantai sebagai dinding berjarak dekat (< 1000mm) sehingga menyebabkan peringatan "Tembok" palsu.
4. **Responsivitas Scanning:** Adanya jeda peringatan saat beralih dari diam ke melangkah.

## 🛠️ Rencana Perbaikan (Ponytail Zero-Delay)
1. **Penerapan Amnesia Sementara (Mute Fix):** Memeriksa state `isMuted` sebelum menyimpan flag `alreadyAlerted`. Jika sistem mute, tidak ada rintangan yang masuk memori.
2. **Zero-Delay Scanning (Spam Fix):** Memeriksa jika objek bersifat statis (Tembok/Terrain) dan pengguna dalam keadaan diam (`!isMovingForward`), maka peringatan dan flag ditahan. Peringatan akan langsung meledak (0 delay) detik ketika pengguna mulai melangkah.
3. **Kompensasi Pitch Esktrim:** Jika sudut `pitch > 20` derajat, abaikan objek statis agar lantai tidak memicu peringatan Tembok.

## 📄 File yang Diubah
- `app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt`
