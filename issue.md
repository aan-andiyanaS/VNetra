# ADR-002: Lokalisasi & Reduksi Kata Label YOLO untuk Akselerasi TTS

## Status
Proposed

## Date
2026-07-11

## Context
Sistem saat ini menggunakan label bahasa Inggris secara bawaan (seperti `person`, `tactile_paving_straight`, `stairs_up`) sebagai keluaran dari model YOLOv11. Label-label ini kemudian diumpankan langsung ke mesin Text-to-Speech (TTS) yang dikonfigurasi untuk berbahasa Indonesia.
Masalah yang muncul:
1. **Prononsiasi Salah:** Mesin TTS Indonesia akan mengeja kata bahasa Inggris dengan dialek lokal, menghasilkan suara yang aneh dan sulit dimengerti (misal: "person" dibaca "per-son").
2. **Durasi Eksekusi Suara (Latency):** Label seperti `tactile_paving_straight` terlalu panjang. Bagi tunanetra, informasi harus tersampaikan dalam hitungan milidetik. Kata yang panjang memperlambat waktu reaksi.

## Decision
Menerjemahkan *array* `CLASSES` di `YoloDetector.kt` secara langsung ke dalam bahasa Indonesia menggunakan **kosakata paling singkat dan padat**. Dengan memodifikasinya di tingkat Model (YOLO), seluruh sistem turunan (termasuk `CameraDepthEstimator` dan `CameraStreamActivity`) otomatis akan menerima string ini, sehingga tidak perlu membuat *mapping* kamus (*dictionary*) baru di tingkat UI/TTS.

### Kamus Translasi Singkat
| Index | Label Asli (Inggris) | Label Baru (Singkat) | Alasan & Catatan |
|---|---|---|---|
| 0 | `person` | **orang** | Jelas dan singkat. |
| 1 | `car` | **mobil** | Universal. |
| 2 | `motorcycle` | **motor** | Lebih cepat diucapkan daripada "sepeda motor". |
| 3 | `bus` | **bus** | Universal. |
| 4 | `pole` | **tiang** | Jelas. |
| 5 | `tactile_paving_straight`| **lurus** | Konteks paving blok sudah diketahui tunanetra. |
| 6 | `tactile_paving_turn` | **belok** | Singkat, instruksional. |
| 7 | `tactile_paving_3way` | **simpang 3** | Cepat dilafalkan TTS sebagai "simpang tiga". |
| 8 | `tactile_paving_4way` | **simpang 4** | Cepat dilafalkan TTS sebagai "simpang empat". |
| 9 | `tactile_paving_stop` | **stop** | Universal dan darurat. |
| 10 | `stairs_up` | **tangga naik** | Tetap dua kata agar tidak rancu. |
| 11 | `stairs_down` | **tangga turun**| Tetap dua kata agar tidak rancu. |
| 12 | `crosswalk` | **zebra cross** | Istilah paling umum di Indonesia. |
| 13 | `tree` | **pohon** | Jelas. |

## Consequences (File yang Terdampak)
Karena nama *string* label berubah dari akar sumber (*source*), kita wajib memperbarui *hardcode* pengecekan _string_ di file-file lain:

### 1. `app/src/main/java/com/airi/vnetra/model/YoloDetector.kt`
- Ubah seluruh _string_ di dalam `val CLASSES = arrayOf(...)` menjadi nama baru sesuai kamus di atas.

### 2. `app/src/main/java/com/airi/vnetra/util/CameraDepthEstimator.kt`
- Ubah _keys_ di dalam *Map* `CLASS_HEIGHTS_MM` menggunakan nama baru (misal: `"person"` menjadi `"orang"`).

### 3. `app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt`
- Ubah logika validasi fusi tangga dari: 
  `it.className == "stairs_up" || it.className == "stairs_down"` 
  menjadi:
  `it.className == "tangga naik" || it.className == "tangga turun"`
