---
title: "fix(YoloDetector): Smart Fallback Cascade NPU/GPU/CPU & Audit Over-Engineering"
labels: enhancement, performance
---

## Ringkasan

Perubahan ini memperbaiki bug di mana model YOLO selalu berjalan di **CPU** meskipun kode memiliki logika GPU/NPU. Sekaligus menyertakan hasil audit *over-engineering* seluruh kode proyek.

---

## 🐛 Bug Root Cause: YoloDetector Selalu Fallback ke CPU

### Masalah

Kode sebelumnya memiliki logika pemilihan delegate (`AUTO`/`MANUAL`) sepanjang ~80 baris `if-else` bersarang. Setelah logika tersebut menentukan delegate yang akan digunakan, kode membuat `Interpreter`:

```kotlin
// Kode LAMA (bug)
activeDelegate = DelegateMode.GPU
gpuDelegate = GpuDelegate()
options.addDelegate(gpuDelegate)
// ... lalu:
interpreter = Interpreter(modelBuffer, options) // ← jika ini throw, tidak ada fallback!
```

Jika `Interpreter()` melempar exception (karena GPU Mali-G57 menolak layer YOLOv8 tertentu), sistem masuk ke blok `catch` di luar, langsung mengatur `modelStatus = NONE`. **Model tidak berjalan sama sekali.** Atau dalam kasus lebih baik, `activeDelegate = CPU` namun tanpa retry ke delegate CPU yang sebenarnya.

### Solusi: Fallback Cascade Loop

```kotlin
// Kode BARU (fix)
val fallbackOrder = if (finalModelName == MODEL_INT8) {
    listOf(DelegateMode.NPU, DelegateMode.CPU)
} else {
    listOf(DelegateMode.GPU, DelegateMode.CPU)
}

for (delegate in fallbackOrder) {
    try {
        // setup delegate...
        interpreter = Interpreter(modelBuffer, options)
        delegateSuccess = true
        break
    } catch (e: Throwable) {
        gpuDelegate?.close() // cegah memory leak
        options.setUseNNAPI(false)
        // lanjut ke delegate berikutnya
    }
}
```

### Mengapa Tidak GPU untuk INT8?

`GpuDelegate` pada TensorFlow Lite **hanya mendukung FP32/FP16**. Memaksakan model INT8 ke GPU akan menyebabkan:
- `UnsupportedOperationException` pada sebagian besar arsitektur Android
- Atau konversi implisit INT8 → FP32 yang justru lebih lambat dari CPU

Tabel keputusan:

| Model | Urutan Fallback | Alasan |
|---|---|---|
| `best_int8.tflite` | NPU → CPU | NPU dioptimalkan untuk integer, GPU tidak support |
| `best_fp32.tflite` | GPU → CPU | GPU Mali-G57 sangat efisien untuk FP32 |

---

## 🔍 Hasil Audit Over-Engineering

Audit menyeluruh dilakukan pada semua 19 file Kotlin di proyek.

### Verdict: Proyek TIDAK Over-Engineering

Setiap file memiliki justifikasi keberadaannya:

| File | Ukuran | Status |
|---|---|---|
| `CameraStreamService.kt` | 682 baris | ✅ Wajar — WebSocket + binary protocol 4 tipe frame |
| `TtsAlertManager.kt` | 597 baris | ✅ Wajar — TTS + Formula G (adaptive threshold) + A2DP |
| `TerrainDetector.kt` | 349 baris | ✅ Wajar — Formula J: 8 step, 7 konstanta, 2 resolusi grid |
| `CameraStreamActivity.kt` | 1169 baris | ⚠️ Besar, tapi justified — orchestrator tunggal semua pipeline sensor |

### Mengapa CameraStreamActivity Tidak Dipecah Lebih Lanjut?

`tofCollectJob` (277 baris inline) memproses pipeline berurutan yang saling bergantung ketat:

```
ToF data → resolve grid size (Main) → smooth data (Default) 
→ update UI (Main) → Formula E/G (Default) → terrain (Default) → TTS
```

Memecah pipeline ini ke class terpisah akan:
1. **Meningkatkan GC pressure** — setiap "pass" data antar kelas menciptakan objek baru (10Hz × 277 baris = 2770 alokasi/detik menjadi lebih banyak jika dipecah)
2. **Memperumit koordinasi state** — `latestImuData`, `latestTofData`, `latestDetections` harus dibagi antar kelas
3. **Menyalahi YAGNI** — tidak ada manfaat konkret yang terukur, hanya "terlihat lebih bersih"

Ini konsisten dengan keputusan yang sama di **ADR-040** (membatalkan ekstraksi `VisionPipeline`).

---

## File yang Diubah

- `app/src/main/java/com/airi/vnetra/model/YoloDetector.kt` — 78 baris dikurangi menjadi ~45 baris, logika delegate diperbaiki
- `docs/decisions/adr-041-yolo-delegate-fallback.md` — dokumentasi keputusan arsitektur

## Commit

`3d40330` — `fix(YoloDetector): smart fallback cascade NPU/GPU/CPU based on model precision`
