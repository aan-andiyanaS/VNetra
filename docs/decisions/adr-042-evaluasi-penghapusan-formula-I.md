# ADR-042: Evaluasi Penghapusan Formula I (TtcManager) dari Sistem VNetra

## Status
PROPOSED — Menunggu Keputusan

## Date
2026-07-19

## Context

### Latar Belakang
Formula I (`TtcManager.kt`) adalah lapisan deteksi optik jarak jauh yang menggunakan **pertumbuhan bounding box** (Optical Expansion / TTC = Time-to-Contact) untuk mendeteksi kendaraan yang sedang mendekati pengguna. Formula ini dirancang untuk mengisi celah kebutaan sensor ToF di jarak > 1 meter saat siang hari.

Namun, dalam sesi review arsitektur (2026-07-19), muncul pertanyaan fundamental:

> *"Apakah Formula I sesuai dengan tujuan utama VNetra?"*

**Tujuan utama VNetra** (berdasarkan pernyataan eksplisit pengembang) adalah:
> "Membantu tunanetra menghindari **rintangan statis** saat berjalan di trotoar — bukan mendeteksi apakah kendaraan di jalan sedang melaju atau tidak."

Formula I dirancang untuk skenario **kendaraan bergerak** (ADAS-style), bukan navigasi trotoar.

---

## Audit Kode: Peta Penggunaan Formula I

Berikut adalah **seluruh titik** di mana Formula I digunakan dalam kodebase (audit per 2026-07-19):

### File Utama

| File | Baris | Peran |
|---|---|---|
| `TtcManager.kt` | L1–146 | **Implementasi inti Formula I** — Seluruh logika area_score, ar_score, dist_score, latScore |
| `NavigationCoordinator.kt` | L15, L87–103 | **Orkestrator** — Memanggil `evaluateThreat()`, menggunakan `TtcStatus.IMMINENT/PROBABLE` untuk **memaksa override nilai `dObj`** |
| `CameraStreamActivity.kt` | L40, L163, L287, L562 | **Penginisialisasi** — Membuat instance `TtcManager`, meneruskannya ke `NavigationCoordinator`, memanggil `cleanup()` |

### Logika Kritis yang Bergantung pada Formula I

```kotlin
// NavigationCoordinator.kt — L87-103
val ttcStatus = ttcManager.evaluateThreat(det, dObj)
if (dObj >= TofDepthEstimator.D_MAX) {
    // ToF BUTA — Formula I mengambil alih kendali penuh:
    if (ttcStatus == TtcStatus.IMMINENT) dObj = 500  // "Paksa Bahaya"
    if (ttcStatus == TtcStatus.PROBABLE) dObj = 1000 // "Paksa Siaga"
    // else: abaikan sama sekali
} else {
    // ToF Aktif — Formula I masih bisa OVERRIDE nilai ToF jika IMMINENT:
    if (ttcStatus == TtcStatus.IMMINENT && dObj > 1000) dObj = 500
}
```

Ini adalah titik paling kritis: **Formula I tidak hanya memberi "skor", tetapi secara aktif memalsukan nilai `dObj`** (jarak sensor ToF) ke 500mm atau 1000mm agar seluruh pipeline peringatan di `TtsAlertManager` terpicu.

---

## Analisis: Jika Formula I Dihapus

### Manfaat yang Didapat

1. **Eliminasi sumber utama false positive dari jalan raya**
   Kendaraan yang melaju di jalan raya (bukan di trotoar) tidak akan pernah memicu peringatan lagi, karena Formula I-lah yang memaksa `dObj = 500` untuk kendaraan yang areanya membesar — meski kendaraan tersebut aman di jalurnya sendiri.

2. **Pipeline logika lebih bersih dan mudah di-debug**
   `dObj` yang masuk ke `TtsAlertManager.process()` akan selalu mencerminkan bacaan sensor ToF yang nyata — tidak pernah dipalsukan. Ini membuat perilaku sistem dapat diprediksi dan direproduksi.

3. **Pengurangan kompleksitas signifikan**
   Hapus `TtcManager.kt` (146 baris), sederhanakan `NavigationCoordinator.kt` (~30 baris), hapus dependency di `CameraStreamActivity.kt`. Total: ~200 baris dihapus.

4. **Konsistensi dengan tujuan utama sistem**
   Sistem kembali ke prinsip dasarnya: "benda keras dekat saya = bahaya", bukan "benda yang tampaknya bergerak mendekati saya = bahaya".

### Risiko yang Muncul

1. **Celah deteksi di zona buta ToF (1m–20m) saat siang hari**
   Ini adalah risiko nyata. Jika ada kendaraan yang **keluar dari jalan dan naik ke trotoar** pada jarak 3–10 meter, Formula G (ToF) belum bisa mendeteksinya (maksimal 4m, dan sering buta di siang hari). Formula I adalah satu-satunya lapisan yang mengisi celah ini.

   *Frekuensi kejadian:* Jarang, tapi konsekuensinya fatal.

2. **Kerentanan di skenario persimpangan (menyeberang jalan)**
   Saat pengguna menyeberang, kendaraan bergerak adalah ancaman utama. Tanpa Formula I, sistem sepenuhnya bergantung pada ToF (yang sering buta) untuk mendeteksi kendaraan yang mendekat.

3. **False Negative baru: Kendaraan bergerak vs. kendaraan parkir diperlakukan sama**
   Setelah penghapusan, mobil yang bergerak cepat ke arah pengguna di jarak 5m diperlakukan sama dengan mobil yang parkir diam di jarak 5m — keduanya hanya memicu peringatan saat `dObj < 1000mm`.

---

## Matriks False Positive & False Negative

| Skenario | Dengan Formula I (Saat Ini) | Tanpa Formula I |
|---|---|---|
| Mobil melaju di jalan raya (jarak 5m, aman) | FALSE POSITIVE — dObj dipaksa 500mm → IMMINENT | Tidak ada alert (dObj ToF > 4000, diabaikan) |
| Motor naik trotoar, jarak 3m | IMMINENT terdeteksi via area growth | FALSE NEGATIVE — ToF buta siang hari, tidak ada alert sampai < 1m |
| Pohon/tiang di depan, jarak 0.8m | Alert via ToF (Formula G) | Alert via ToF (Formula G) — tidak terpengaruh |
| Saat menyeberang, mobil dari jarak 10m | Alert PROBABLE via area growth | FALSE NEGATIVE — tidak terdeteksi |
| Motor parkir di trotoar jarak 0.5m | Alert via ToF (Formula G) | Alert via ToF — tidak terpengaruh |

**Kesimpulan matriks:** Menghapus Formula I menukar *false positive* yang sering (kendaraan di jalan raya) dengan *false negative* yang jarang tapi lebih fatal (kendaraan yang benar-benar masuk ke jalur pengguna).

---

## Alternatif yang Dipertimbangkan

### Opsi A: Hapus Formula I Sepenuhnya
- **Pro:** Eliminasi semua false positive kendaraan di jalan raya, kode jauh lebih simpel
- **Kontra:** Meninggalkan celah keamanan nyata di zona ToF buta
- **Cocok jika:** Pengguna HANYA berjalan di trotoar yang jelas terpisah dari jalan, tidak pernah menyeberang

### Opsi B: Nonaktifkan Default, Aktifkan di Mode Menyeberang (DIREKOMENDASIKAN)
- **Pro:** Tidak ada false positive saat berjalan di trotoar; perlindungan penuh saat menyeberang
- **Kontra:** Membutuhkan mekanisme mode-switching (tombol fisik atau gesture)
- **Implementasi:** Tambahkan `boolean isCrossingMode` di `NavigationCoordinator`. Saat `false`, panggilan `ttcManager.evaluateThreat()` dilewati dan `dObj` selalu berasal dari ToF murni

### Opsi C: Turunkan Sensitivitas (ttcHigh: 0.75 → 0.95)
- **Pro:** Tidak ada perubahan arsitektur
- **Kontra:** Kalibrasi heuristik tanpa dasar empiris; masalah fundamental tidak terselesaikan
- **Cocok jika:** Tidak ada waktu untuk refactoring

---

## Keputusan yang Direkomendasikan

**Opsi B — Nonaktifkan default, aktifkan eksplisit.**

Formula I adalah fitur yang valid untuk skenario persimpangan, tetapi salah penempatan saat ini (aktif terus-menerus saat berjalan di trotoar). Solusi yang benar bukan menghapusnya, tetapi mengisolasi konteksnya.

## Consequences

Jika Opsi B dipilih:
- `TtcManager.kt` tetap ada tetapi tidak aktif by default
- `NavigationCoordinator.kt` mendapat parameter `isCrossingMode: Boolean`
- Tidak ada perubahan pada `TtsAlertManager.kt`
- False positive dari kendaraan di jalan raya tereliminasi
- Perlindungan di persimpangan dipertahankan

> **Catatan:** ADR ini adalah dokumen analisis. Tidak ada perubahan kode yang dilakukan. Keputusan final ada pada pengembang.
