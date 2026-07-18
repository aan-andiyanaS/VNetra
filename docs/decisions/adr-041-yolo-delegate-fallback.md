# ADR-041: Smart Hardware Delegate Fallback di YoloDetector

**Status:** Accepted  
**Date:** 2026-07-18  
**Commit:** `3d40330`

## Konteks

Aplikasi VNetra sebelumnya melaporkan model YOLO berjalan di **CPU** meskipun kode sudah memiliki logika pemilihan GPU dan NPU. Investigasi menemukan dua bug:

1. **Bug Logika State:** Jika GPU `Interpreter` gagal saat dibuat (bukan saat delegate dibuat), sistem mencatat `activeDelegate = CPU` di antarmuka pengguna — tetapi `Interpreter` sendiri tidak berhasil dibuat sama sekali. Model tidak berjalan.

2. **Bug Arsitektur:** Logika `AUTO/MANUAL` yang panjang (~80 baris `if-else` bersarang) memilih delegate terlebih dahulu berdasarkan logika kondisional, lalu memanggil `Interpreter()`. Tidak ada mekanisme *retry* ke delegate berikutnya jika `Interpreter` melempar exception.

## Keputusan

Mengganti seluruh blok pemilihan delegate dengan **Fallback Cascade Loop** sederhana:

```kotlin
val fallbackOrder = if (finalModelName == MODEL_INT8) {
    listOf(DelegateMode.NPU, DelegateMode.CPU)  // GPU tidak support INT8 murni
} else {
    listOf(DelegateMode.GPU, DelegateMode.CPU)  // NPU kurang efisien untuk FP32
}

for (delegate in fallbackOrder) {
    try {
        // setup delegate option...
        interpreter = Interpreter(modelBuffer, options)
        delegateSuccess = true
        break
    } catch (e: Throwable) {
        gpuDelegate?.close()  // bersihkan memori delegate yang gagal
        options.setUseNNAPI(false)
    }
}
```

### Rasional Pemilihan Delegate per Tipe Model

| Model | Delegate Prioritas | Alasan |
|---|---|---|
| **INT8** | NPU → CPU | NPU dirancang untuk komputasi integer. GPU *tidak* mendukung operasi INT8 murni pada TensorFlow Lite — akan melempar `UnsupportedOperationException` atau melakukan konversi mahal ke FP32. |
| **FP32** | GPU → CPU | GPU (misalnya Mali-G57 di Helio G99) memiliki unit FP32 yang dioptimalkan. NPU pada SoC kelas menengah sering mensimulasikan FP32 lewat ALU utama, yang lebih lambat dari GPU. |

## Konsekuensi

- **Positif:** Sistem tidak pernah *crash* saat inisialisasi delegate gagal. Selalu berhasil berjalan minimal di CPU.
- **Positif:** Kode dari ~125 baris berkurang menjadi ~55 baris, jauh lebih mudah dibaca.
- **Positif:** Memory leak dari `gpuDelegate` yang gagal dicegah dengan memanggil `.close()` sebelum fallback.
- **Netral:** Proses pemilihan delegate hanya terjadi satu kali saat `YoloDetector` diinisialisasi — tidak ada overhead saat inferensi berjalan *real-time*.
- **Catatan:** Karena saat ini hanya `best_fp32.tflite` yang tersedia di `assets/`, sistem akan selalu mencoba **GPU → CPU**. Jika kelak `best_int8.tflite` ditambahkan, sistem akan otomatis beralih ke **NPU → CPU**.
