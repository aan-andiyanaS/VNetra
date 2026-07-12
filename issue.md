## ADR-027: Penyelesaian Bug & Optimasi Kotlin (Report Points 4-9)
- **Status:** Dieksekusi (12 Juli 2026)

### 1. Konteks
Berdasarkan hasil laporan *code review* (`report.md`), terdapat serangkaian masalah performa dan potensi bug di sisi Android (Kotlin) yang perlu dituntaskan:
1. **(Point 4)** Double resize bitmap di `YoloDetector.kt` yang membuang alokasi memori.
2. **(Point 5)** Potensi *race condition* pada state `isInferencing` di `CameraStreamActivity.kt`.
3. **(Point 6)** Potensi `IndexOutOfBoundsException` saat array `tofViews` di-*rebuild* secara asinkron.
4. **(Point 7)** Kalkulasi *padding* YOLO yang kurang akurat untuk rasio gambar non-persegi.
5. **(Point 8)** Coroutine `PING` di `CameraStreamService.kt` yang tidak dilacak pembatalannya.
6. **(Point 9)** Penggunaan `Math.*` Java yang tidak idiomatis di Kotlin.

### 2. Keputusan (Incremental Implementation & Ponytail)
Untuk meminimalisir interupsi dan memaksimalkan efisiensi, kita menggabungkan seluruh optimasi Kotlin ini dalam satu eksekusi sapu jagat (*batch processing*):
- **YoloDetector.kt**: Menghapus `Bitmap.createScaledBitmap` awal yang tak terpakai, memperbaiki kalkulasi `padX/padY` dengan nilai sesudah *scaling*, dan mengganti `Math.min/max` dengan `minOf/maxOf`.
- **CameraStreamActivity.kt**: Mengganti `isInferencing` menjadi `AtomicBoolean`, menambahkan pengaman batas index `if (i >= tofViews.size)` pada perulangan ToF, dan mengganti `Math.abs` dengan ekstensi `.absoluteValue`.
- **CameraStreamService.kt**: Menyimpan referensi `pingJob` dan membatalkannya secara eksplisit saat koneksi terputus.
- Mengganti seluruh sisa fungsi `Math.*` Java dengan fungsi Kotlin *native* di class-class utility.

### 3. Konsekuensi
- **Positif:** Beban CPU & alokasi memori (terutama saat pemrosesan *frame* 10 FPS) berkurang secara terukur.
- **Positif:** Aplikasi kebal terhadap potensi *crash* konkurensi (rebuild ToF dan thread inferensi YOLO).
- **Positif:** *Bounding box* YOLO lebih presisi di perangkat layar panjang.
