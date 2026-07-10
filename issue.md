# ADR-006: Pemisahan Prioritas Peringatan YOLO Berdasarkan Zona Spasial (Peripheral vs Center)

## Status
Accepted

## Date
2026-07-11

## Context
Aplikasi VNetra memanfaatkan fitur Text-to-Speech (TTS) untuk memberitahukan objek yang dideteksi oleh AI (YOLO). Sebelumnya, semua deteksi dari YOLO dianggap sebagai peringatan bahaya yang mendesak. Sistem mengirimkan peringatan ini melalui antrean `TextToSpeech.QUEUE_FLUSH`, yang secara agresif akan memotong (menginterupsi) suara apa pun yang sedang diucapkan TTS saat itu.

**Permasalahan**:
1. Objek-objek yang berada jauh di pinggiran penglihatan (ujung kiri arah jam 10, atau ujung kanan arah jam 2) sebenarnya belum tentu menjadi ancaman langsung bagi pergerakan tunanetra, kecuali mereka berbelok tajam ke arah sana.
2. Memotong suara secara agresif untuk semua objek (meskipun di pinggir) akan membuat aplikasi terdengar sangat *spammy* dan membingungkan, terutama jika sedang ada peringatan kritis di depan (arah jam 12) yang terpotong.
3. Objek Paving (Guiding Block) sejak awal memang difungsikan sebagai petunjuk jalan (Informasi), bukan rintangan darurat (Peringatan), sehingga perlakuan TTS-nya pun harus berbeda.

## Decision
Kami memutuskan untuk mengklasifikasikan suara TTS menjadi dua prioritas berbeda berdasarkan jenis rintangan dan letak spasial (arah jam) objek:

1. **Prioritas Tinggi (Urgent / Warning) → `QUEUE_FLUSH`**
   - Berlaku untuk rintangan berbahaya (Motor, Orang, Tiang, dsb).
   - Berlaku HANYA jika rintangan tersebut berada di zona depan atau tengah (Arah Jam 11, 12, dan 1).
   - *Behavior*: Akan langsung memotong kalimat TTS yang sedang berjalan agar pengguna segera awas.

2. **Prioritas Normal (Information) → `QUEUE_ADD`**
   - Berlaku untuk semua jenis Paving / Guiding Block (Lurus, Belok, Simpang 3, Simpang 4, Stop).
   - Berlaku juga untuk rintangan (selain paving) yang posisinya berada di zona pinggiran / *peripheral* (Arah Jam 10 dan 2).
   - *Behavior*: Akan masuk antrean secara sopan, diucapkan satu kali ketika objek masuk ke zona, dan tidak akan memotong peringatan darurat yang sedang berjalan.

## Proposed Changes
Modifikasi dilakukan pada file `CameraStreamActivity.kt`, khususnya di fungsi `triggerInstantYoloTts`:
- Membuat dua array penampung terpisah: `urgentAlerts` dan `infoAlerts`.
- Menambahkan validasi `isPeripheral = arahJam == 10 || arahJam == 2`.
- Memasukkan notifikasi ke array `infoAlerts` jika `isPaving || isPeripheral`.
- Eksekusi `ttsAlertManager.speak(combinedMsg)` untuk `urgentAlerts` dan `ttsAlertManager.speakAdd(combinedMsg)` untuk `infoAlerts`.

## Consequences
- **Positif:** Mengurangi polusi suara dan informasi berlebihan bagi tunanetra. Fokus dipertajam ke objek yang benar-benar ada tepat di arah jalan mereka.
- **Positif:** Peringatan untuk rintangan pinggir (seperti tiang listrik di bahu jalan atau motor parkir di pinggir) akan diucapkan hanya sebagai "info tambahan" satu kali, tanpa menimbulkan rasa panik.
- **Negatif:** Tidak ada dampak kinerja komputasi karena evaluasi arah jam (`arahJam`) sudah dihitung di hulu oleh *SpatialMappingUtils*.
