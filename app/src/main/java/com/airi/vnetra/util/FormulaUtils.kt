package com.airi.vnetra.util

/**
 * FormulaUtils — Utilitas pipeline Formula B, C, D (v9.4)
 *
 * Formula B: Centroid bounding box (sumbu X)
 *   x_c = (x_min + x_max) / 2
 *
 * Formula C: Pemetaan posisi horizontal → arah jam
 *   h(x_c) ∈ {10, 11, 12, 1, 2}
 *
 * Formula D: Pemetaan posisi horizontal → indeks kolom ToF
 *   j = sat(floor((x_c - D_left) / R_col), 0, N_col-1)
 *
 * Semua fungsi bersifat pure (tidak ada state internal).
 * Konstanta mengacu ke Konstanta Sistem formula-matematis-v9.4.md.
 */
object FormulaUtils {

    // ── Konstanta Sistem — disesuaikan dengan layout activity_camera_stream.xml ──
    //
    // Geometri layar (portrait):
    //   ivCameraFrame  = rasio 4:3, fitCenter, lebar = W_screen
    //   gridTof        = width_percent=0.69, rasio 1:1, di-center horizontal terhadap ivCameraFrame
    //
    // Konversi ke koordinat gambar asli (640×480 firmware):
    //   W_grid_di_layar = 0.69 × W_camera_view
    //   Dead zone kiri = dead zone kanan = (1 - 0.69) / 2 × W_camera = 0.155 × W_camera
    //
    //   D_left  = round(0.155 × 640) = 99 px
    //   D_right = round(0.155 × 640) = 99 px
    //   W_TOF   = 640 - 99 - 99 = 442 px  (lebar coverage ToF dalam koordinat gambar)
    //   R_COL   = floor(W_TOF / N_COL) = floor(442/8) = 55 px/kolom
    //
    // Batas zona arah jam (5 zona × W_Z):
    //   W_Z_EFF = W_TOF / 3 ≈ 147 px per zona JAM 11/12/1
    //   Dead zone kiri (JAM 10) = D_left = 99px
    //   Dead zone kanan (JAM 2) = 640 - (D_left + W_TOF) = 640 - 541 = 99px
    //
    // Catatan: Konstanta ini digunakan untuk YOLO detection (koordinat gambar 640×480).
    // Untuk render UI (koordinat layar dp), gunakan gridTof.width / N_COL langsung.

    const val W_CAM   = 640   // px — lebar gambar kamera OV2640 (VGA)
    const val H_CAM   = 480   // px — tinggi gambar kamera OV2640 (VGA)

    // Fraksi grid ToF terhadap lebar kamera (dari XML: width_percent="0.69")
    const val TOF_GRID_FRAC = 0.69f  // gridTof.width / ivCameraFrame.width

    // Dead zone horizontal (area yang tidak di-cover sensor ToF)
    // = (1 - TOF_GRID_FRAC) / 2 × W_CAM = 0.155 × 640 = ~99px
    const val D_LEFT  = 99    // px
    const val D_RIGHT = 99    // px

    // Area aktif ToF dalam koordinat gambar 640px
    const val W_TOF   = W_CAM - D_LEFT - D_RIGHT  // = 442 px

    // Jumlah kolom dan baris sensor (8×8 atau 4×4 tergantung mode)
    const val N_COL   = 8
    const val N_ROW   = 8

    // Lebar satu kolom ToF dalam koordinat gambar — untuk mode default 8×8
    // floor(442/8) = 55 px — pakai Int agar mapping j selalu integer
    const val R_COL   = W_TOF / N_COL  // = 55 px/kolom (integer division, sisa 2px di ujung)

    // Lebar satu zona arah jam dalam koordinat gambar
    // 3 zona aktif (11, 12, 1) membagi W_TOF = 442px → ~147px/zona
    const val W_Z     = W_TOF / 3     // = 147 px/zona (integer division)

    // ── Konstanta untuk mode 4×4 ───────────────────────────────────────────────
    // Geometri horizontal tidak berubah (D_LEFT, W_TOF sama), hanya jumlah kolom.
    // R_COL_4 = floor(442/4) = 110 px/kolom
    const val N_COL_4  = 4
    const val R_COL_4  = W_TOF / N_COL_4  // = 110 px/kolom

    // Batas zona arah jam — diderivasi dari Konstanta Sistem, bukan angka arbitrer
    // b_k = D_left + k × W_z,  k ∈ {0, 1, 2, 3}
    // Dengan D_LEFT=99 dan W_Z=147:
    val B0 = D_LEFT               // 99  — batas kiri zona JAM 11
    val B1 = D_LEFT + W_Z         // 246 — batas kiri zona JAM 12
    val B2 = D_LEFT + 2 * W_Z    // 393 — batas kiri zona JAM 1
    val B3 = D_LEFT + 3 * W_Z    // 540 — batas kanan zona JAM 1 (= batas kiri dead zone kanan)

    // ── Formula B: Centroid bounding box ─────────────────────────────────────
    /**
     * Hitung posisi horizontal centroid bounding box.
     *
     * Domain input:  x_min, x_max ∈ [0, W_CAM-1] = [0, 639] px, x_max > x_min
     * Range output:  x_c ∈ (0, 639) px
     *
     * @param xMin batas kiri bounding box dari YOLO (px)
     * @param xMax batas kanan bounding box dari YOLO (px)
     * @return x_c — posisi horizontal centroid
     */
    fun centroidX(xMin: Float, xMax: Float): Float = (xMin + xMax) / 2f

    // ── Formula C: Pemetaan arah jam ──────────────────────────────────────────
    /**
     * Petakan posisi horizontal x_c ke notasi arah jam.
     *
     * Pembagian zona berdasarkan arsitektur hardware (width_percent=0.69 dari XML):
     *   ┌──────────┬──────────────┬──────────────┬──────────────┬──────────┐
     *   │  99 px   │   147 px     │   147 px     │   147 px     │  99 px   │
     *   │  JAM 10  │   JAM 11     │   JAM 12     │   JAM 1      │  JAM 2   │
     *   │ [0, 98]  │ [99, 245]    │ [246, 392]   │ [393, 539]   │[540,639] │
     *   │ ❌ no ToF│  ✅ ToF      │  ✅ ToF      │  ✅ ToF      │ ❌ no ToF│
     *   └──────────┴──────────────┴──────────────┴──────────────┴──────────┘
     *
     * Domain input:  x_c ∈ [0, 639] px
     * Range output:  h(x_c) ∈ {10, 11, 12, 1, 2}
     *   10 = kiri jauh (dead zone, tanpa data ToF yang presisi)
     *   11 = kiri-depan
     *   12 = tepat-depan
     *    1 = kanan-depan
     *    2 = kanan jauh (dead zone)
     */
    fun mapToClockDirection(xc: Float): Int = when {
        xc < B0 -> 10
        xc < B1 -> 11
        xc < B2 -> 12
        xc < B3 ->  1
        else    ->  2
    }

    /**
     * Konversi nilai arah jam ke string Bahasa Indonesia untuk TTS.
     */
    fun clockDirectionToTts(direction: Int): String = when (direction) {
        10 -> "jam sepuluh"
        11 -> "jam sebelas"
        12 -> "jam dua belas"
         1 -> "jam satu"
         2 -> "jam dua"
        else -> "depan"
    }

    // ── Formula D: Indeks kolom ToF ───────────────────────────────────────────
    /**
     * Petakan posisi horizontal x_c ke indeks kolom sensor ToF.
     *
     * Formula: j = sat(floor((x_c - D_left) / R_col), 0, N_col-1)
     * Saturasi wajib karena tanpa sat(), x_c=560 → floor(8)=8 → out-of-range!
     *
     * Domain input:  x_c ∈ [D_LEFT, D_LEFT + W_TOF) = [99, 540] px (zona aktif ToF)
     * Range output:  j ∈ {0, 1, 2, 3, 4, 5, 6, 7}
     *
     * Tabel mapping (R_COL=55px, W_TOF=442px):
     *   j=0:  99–153px   j=1: 154–208px  j=2: 209–263px  j=3: 264–318px
     *   j=4: 319–373px   j=5: 374–428px  j=6: 429–483px  j=7: 484–539px
     *   (sisa 2px [540–541] jatuh ke j=7 karena coerceIn)
     *
     * @param xc posisi horizontal centroid (px) dari Formula B
     * @return j — indeks kolom ToF ∈ {0..7}
     */
    fun mapToTofColumn(xc: Float): Int {
        val raw = ((xc - D_LEFT) / R_COL).toInt()
        return raw.coerceIn(0, N_COL - 1)
    }

    // ── Helper resolusi-aware ─────────────────────────────────────────────────

    /**
     * Kembalikan lebar kolom ToF (px di koordinat gambar) sesuai resolusi aktif.
     * @param resolution 4 atau 8
     */
    fun rCol(resolution: Int): Int = when (resolution) {
        4    -> R_COL_4   // 110 px/kolom
        else -> R_COL     // 55  px/kolom (8×8 default)
    }

    /**
     * Kembalikan jumlah kolom sensor sesuai resolusi aktif.
     */
    fun nCol(resolution: Int): Int = when (resolution) {
        4    -> N_COL_4
        else -> N_COL
    }

    /**
     * Petakan posisi horizontal x_c ke indeks kolom sensor ToF,
     * dengan dukungan resolusi 4×4 dan 8×8.
     *
     * @param xc         posisi horizontal centroid dari Formula B (px)
     * @param resolution resolusi aktif: 4 atau 8
     * @return j ∈ {0..resolution-1}
     */
    fun mapToTofColumn(xc: Float, resolution: Int = 8): Int {
        val raw = ((xc - D_LEFT) / rCol(resolution)).toInt()
        return raw.coerceIn(0, nCol(resolution) - 1)
    }

    /**
     * Kembalikan daftar indeks kolom "tepat depan" (zona JAM 12)
     * sesuai resolusi aktif. Digunakan di tofCollectJob sebagai proxy objek
     * di depan ketika YOLO belum tersedia.
     *
     * - 8×8: kolom 3 dan 4 (tengah dari 0..7)
     * - 4×4: kolom 1 dan 2 (tengah dari 0..3)
     */
    fun centerColumns(resolution: Int): List<Int> = when (resolution) {
        4    -> listOf(1, 2)
        else -> listOf(3, 4)
    }

    /**
     * Cek apakah posisi x_c berada dalam zona aktif ToF (bukan dead zone).
     * Tidak bergantung pada resolusi — dead zone selalu [0, D_LEFT) dan [D_LEFT+W_TOF, 640).
     */
    fun isInTofZone(xc: Float): Boolean =
        xc >= D_LEFT && xc < (D_LEFT + W_TOF)
}
