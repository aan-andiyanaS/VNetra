package com.airi.vnetra.util

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * TerrainDetector — Deteksi Anomali Medan via Formula J (v9.4)
 *
 * Input:  matriks ToF 8x8 (64 nilai) atau 4x4 (16 nilai) + pitch theta dari IMU
 * Output: TerrainResult (tipe terrain, confidence, estimasi kedalaman, arah, level alert)
 *
 * Alur per frame:
 *   J.0   Hitung batas baris zona dari theta
 *         8x8: 4 zona (high, mid, low, ultra)
 *         4x4: 2 zona (high, low)
 *   J.0b  Filter sentinel (0 / -1 / 65535 -> D_MAX)
 *   J.1   Rata-rata zona vertikal
 *   J.2   Ekstrak fitur: dz_v, dz_t, sigma_j, R, xi, pattern
 *   J.3   Decision tree -> TerrainType
 *   J.4   Estimasi h_est (mm)
 *   J.5   Arah spatial (kolom -> arah jam 11/12/1)
 *   J.6   Confidence scoring (4 komponen)
 *   J.7   Routing ke AlertLevel (HIGH / MED / INFO / NONE)
 *
 * Resolusi yang didukung:
 *   8x8 (64 nilai): 4 zona, threshold HIGH=0.80, MED=0.60
 *   4x4 (16 nilai): 2 zona, threshold HIGH=0.70, MED=0.55 (SNR lebih baik)
 *
 * Referensi: formula-matematis-v9.4.md §J
 */
class TerrainDetector {

    companion object {
        private const val TAG = "TerrainDetector"

        // Konstanta Sistem (§J)
        const val DELTA_Z_STEP = 500f   // mm  — ambang gradien vertikal STAIR/HOLE
        const val R_TH_HI      = 0.8f   // —   — threshold rasio z̄_low/z̄_mid (atas)
        const val R_TH_LO      = 0.7f   // —   — threshold rasio (bawah, hysteresis)
        const val EDGE_TH      = 300f   // mm  — batas edge sharpness (tajam = cliff)
        const val D_GUARD      = 2500f  // mm  — guard terrain terbuka (z̄_mid ≥ D_GUARD → OPEN)
        const val D_CONT       = 800f   // mm  — guard kontaminasi (z̄_low < D_CONT → objek, bukan lantai)
        const val SIGMA_COL_TH = 200f   // mm  — threshold std dev kolom untuk deteksi anomali lokal
        const val C_HIGH       = 0.80f  // —   — confidence untuk force HIGH alert
        const val C_MID        = 0.60f  // —   — confidence untuk MED alert
        const val D_MAX        = 4000f  // mm  — sentinel "tidak terdeteksi"
        const val ALPHA_MOUNT  = 20f    // °   — sudut mounting sensor ke bawah dari horizontal

        // Jumlah frame historis untuk komponen confidence temporal
        const val HISTORY_SIZE = 3
    }

    /** Tipe terrain yang terdeteksi */
    enum class TerrainType {
        SAFE,         // Lantai normal, aman
        OPEN,         // Area terbuka / tidak ada pembacaan valid
        CONTAMINATED, // Zona tengah terbaca dekat → kemungkinan ada objek di depan, bukan lantai
        STAIR_DOWN,   // Tangga turun / permukaan lebih rendah
        STAIR_UP,     // Tangga naik / hambatan ke atas
        HOLE          // Lubang / drop lokal
    }

    /**
     * Hasil deteksi terrain untuk satu frame.
     * @param type        tipe terrain terdeteksi
     * @param confidence  keyakinan ∈ [0, 1]
     * @param hEst        estimasi kedalaman/ketinggian anomali (mm) — 0 untuk SAFE/OPEN
     * @param direction   arah jam dominan anomali: 11, 12, atau 1
     */
    data class TerrainResult(
        val type:       TerrainType = TerrainType.SAFE,
        val confidence: Float       = 0f,
        val hEst:       Float       = 0f,
        val direction:  Int         = 12,
        val distance:   Float       = 0f
    )

    // State temporal: riwayat tipe per frame untuk C_temporal
    private val typeHistory  = ArrayDeque<TerrainType>(HISTORY_SIZE)

    // z̄_low dari frame SAFE sebelumnya — digunakan J.4 untuk estimasi h_est (Δz relative to baseline)
    private var prevZLowSafe = 0f

    /**
     * Proses satu frame data ToF + pitch IMU.
     * Mendukung resolusi 8×8 (64 nilai) dan 4×4 (16 nilai).
     * Dipanggil dari tofCollectJob (Dispatchers.Default) — tidak ada operasi UI.
     *
     * @param tofData  array flat: 64 nilai (8×8) atau 16 nilai (4×4); -1 = sentinel
     * @param thetaDeg pitch kepala (°) dari latestImuData[0]
     * @return TerrainResult untuk frame ini
     */
    fun process(tofData: IntArray, thetaDeg: Float): TerrainResult = when (tofData.size) {
        64 -> process8x8(tofData, thetaDeg)
        16 -> process4x4(tofData, thetaDeg)
        else -> TerrainResult()  // ukuran tidak dikenal
    }

    // ═════════════════════════ 8×8 IMPLEMENTATION ═══════════════════════════

    /**
     * Implementasi Formula J.0–J.7 untuk resolusi 8×8.
     */
    private fun process8x8(tofData: IntArray, thetaDeg: Float): TerrainResult {
        // ── J.0: Hitung batas baris zona dari θ ──────────────────────────────
        val deltaTheta = 5.625f  // °/baris untuk 8×8 (45°/8)
        val rCenter    = (3.5f + thetaDeg / deltaTheta).toInt().coerceIn(0, 7)

        // Zona: r_high (atas), r_mid (tengah), r_low (lantai dekat), r_ultra (lantai sangat dekat)
        // Guard non-overlap (post-audit A5): r_mid tidak boleh overlap dengan r_high
        val rHighEnd  = (rCenter - 2).coerceAtLeast(0)
        val rHighRows = (0..rHighEnd).toList().takeIf { it.isNotEmpty() } ?: listOf(0)

        val rMidRows = listOf(
            (rCenter - 1).coerceIn(0, 7),
            rCenter
        ).distinct()

        val rLowRows  = listOf((rCenter + 1).coerceIn(0, 7))

        val rUltraStart = (rCenter + 2).coerceAtMost(7)
        val rUltraRows  = (rUltraStart..7).toList().takeIf { it.isNotEmpty() } ?: listOf(7)

        // ── J.0b: Fungsi filter sentinel ─────────────────────────────────────
        // Nilai -1 (firmware sentinel = 0xFFFF), 0 (no target), <30mm (noise floor) → D_MAX
        fun filtered(row: Int, col: Int): Float {
            val idx = row * 8 + col
            if (idx < 0 || idx >= tofData.size) return D_MAX
            val v = tofData[idx]
            return if (v >= 30 && v <= D_MAX.toInt()) v.toFloat() else D_MAX
        }

        // ── J.1: Rata-rata zona (hanya nilai valid, bukan D_MAX) ──────────────
        fun zoneAvg(rows: List<Int>): Float {
            var sum = 0f; var cnt = 0
            for (r in rows) for (c in 0..7) {
                val v = filtered(r, c)
                if (v < D_MAX) { sum += v; cnt++ }
            }
            return if (cnt > 0) sum / cnt else D_MAX
        }

        val zHigh  = zoneAvg(rHighRows)
        val zMid   = zoneAvg(rMidRows)
        val zLow   = zoneAvg(rLowRows)
        val zUltra = zoneAvg(rUltraRows)

        // ── J.2: Ekstrak 6 fitur ──────────────────────────────────────────────

        // Fitur 1: Gradien vertikal Δz_v = z̄_high - z̄_low
        // Positif = zona atas lebih jauh dari zona bawah → kemungkinan drop/lubang
        val dzV = zHigh - zLow

        // Fitur 2: Perubahan temporal Δz_t = z̄_low(t) - z̄_low(t-1)
        // Digunakan sebagai modifier confidence HOLE (J.6 post-audit C5)
        val dzT = zLow - prevZLowSafe

        // Fitur 3: Std dev per kolom di zona rLow (σ_j)
        val sigmaJ = FloatArray(8)
        for (c in 0..7) {
            val vals = rLowRows.map { r -> filtered(r, c) }.filter { it < D_MAX }
            if (vals.isEmpty()) { sigmaJ[c] = 0f; continue }
            val mean = vals.average().toFloat()
            val variance = vals.map { (it - mean) * (it - mean) }.average().toFloat()
            sigmaJ[c] = sqrt(variance)
        }

        // Fitur 4: Depth ratio R = z̄_low / z̄_mid
        // Tinggi → z_low >> z_mid → kemungkinan terrain drop
        val R = if (zMid > 0f && zMid < D_MAX) (zLow / zMid) else 0f

        // Fitur 5: Edge sharpness ξ = max |zona_i - zona_{i+1}|
        val zoneMeans = listOf(zHigh, zMid, zLow, zUltra)
        var xi = 0f
        for (i in 0 until zoneMeans.size - 1) {
            val diff = abs(zoneMeans[i] - zoneMeans[i + 1])
            if (diff > xi) xi = diff
        }

        // Fitur 6: Pattern distribusi anomali (berdasarkan kolom yang melebihi σ_col_th)
        val anomalyCols = (0..7).filter { c -> sigmaJ[c] > SIGMA_COL_TH }
        val pattern = when {
            anomalyCols.isEmpty()  -> "SAFE"
            anomalyCols.size >= 4  -> "UNIFORM"    // anomali menyebar luas → STAIR_DOWN
            anomalyCols.size <= 2  -> "LOCALIZED"  // anomali sempit → HOLE
            else                   -> "MIXED"
        }

        // ── J.3: Decision Tree ────────────────────────────────────────────────
        val type = when {
            // Guard 1: terrain terbuka (z̄_mid sangat jauh → tidak ada pembacaan valid)
            zMid >= D_GUARD -> TerrainType.OPEN

            // Guard 2: kontaminasi — zona tengah membaca badan objek berdiri, bukan lantai
            zLow < D_CONT   -> TerrainType.CONTAMINATED

            // Tangga turun / lubang: gradien besar ke bawah, R tinggi
            dzV > DELTA_Z_STEP && R > R_TH_HI && pattern == "UNIFORM"   -> TerrainType.STAIR_DOWN
            dzV > (DELTA_Z_STEP * 1.5f) && R > R_TH_HI && pattern == "LOCALIZED" -> TerrainType.HOLE

            // Tangga naik / hambatan: gradien besar ke atas, tepi tajam
            dzV < -DELTA_Z_STEP && xi > EDGE_TH -> TerrainType.STAIR_UP

            // Default: lantai normal
            else -> TerrainType.SAFE
        }

        // Update riwayat temporal
        if (typeHistory.size >= HISTORY_SIZE) typeHistory.removeFirst()
        typeHistory.addLast(type)

        // ── J.4: Estimasi h_est ───────────────────────────────────────────────
        val cosAlpha = Math.cos(Math.toRadians(ALPHA_MOUNT.toDouble())).toFloat()
        val hEst = when (type) {
            TerrainType.STAIR_DOWN ->
                if (prevZLowSafe > 0f) abs(zLow - prevZLowSafe) / cosAlpha else abs(dzV) / cosAlpha
            TerrainType.HOLE       -> abs(zLow - zMid)
            TerrainType.STAIR_UP   -> abs(zMid - zLow)
            else                   -> 0f
        }.coerceAtLeast(0f)

        // Update baseline z_low (hanya saat SAFE agar tidak drift karena terrain anomali)
        if (type == TerrainType.SAFE && zLow < D_MAX) prevZLowSafe = zLow

        // ── J.5: Arah spatial (dominant anomaly column → arah jam) ───────────
        val direction = when {
            anomalyCols.isEmpty()            -> 12
            anomalyCols.average() < 2.5      -> 11   // dominan kiri
            anomalyCols.average() > 5.5      ->  1   // dominan kanan
            else                             -> 12   // tengah
        }

        // ── J.6: Confidence scoring ───────────────────────────────────────────
        // C_R: seberapa jauh R dari threshold — makin jauh, makin yakin
        val cR = when (type) {
            TerrainType.STAIR_DOWN, TerrainType.HOLE ->
                ((R - R_TH_HI) / (1f - R_TH_HI)).coerceIn(0f, 1f)
            TerrainType.STAIR_UP ->
                (xi / (EDGE_TH * 2f)).coerceIn(0f, 1f)
            else -> 0f
        }

        // C_spatial: proporsi kolom anomali
        val cSpatial = when (pattern) {
            "UNIFORM"   -> 0.9f
            "LOCALIZED" -> 0.8f
            "MIXED"     -> 0.5f
            else        -> 0.1f
        }

        // C_temporal: konsistensi tipe dalam HISTORY_SIZE frame
        val cTemporal = if (typeHistory.isEmpty()) 0f else
            typeHistory.count { it == type }.toFloat() / typeHistory.size

        // C_edge: dukungan edge sharpness terhadap keputusan
        val cEdge = when (type) {
            TerrainType.STAIR_UP  -> (xi / (EDGE_TH * 2f)).coerceIn(0f, 1f)
            TerrainType.HOLE      ->
                // HOLE diperkuat jika Δz_t besar (objek mendekat cepat ke lubang) — post-audit C5
                if (abs(dzT) > DELTA_Z_STEP * 0.5f) 0.8f else 0.5f
            else                  -> 0.5f
        }

        // Gabungan: 40% C_R + 30% C_spatial + 20% C_temporal + 10% C_edge
        val confidence = (0.40f * cR + 0.30f * cSpatial + 0.20f * cTemporal + 0.10f * cEdge)
            .coerceIn(0f, 1f)

        return TerrainResult(
            type       = type,
            confidence = confidence,
            hEst       = hEst,
            direction  = direction,
            distance   = zLow
        )
    }

    // ------------------------- 4�4 IMPLEMENTATION ---------------------------
    private fun process4x4(tofData: IntArray, thetaDeg: Float): TerrainResult {
        val N = 4
        val deltaTheta = 45f / N
        val centerRow  = (N - 1) / 2.0f
        val rCenter    = (centerRow + thetaDeg / deltaTheta).toInt().coerceIn(0, N - 1)
        val rHighRows = (0 until rCenter).toList().takeIf { it.isNotEmpty() } ?: listOf(0)
        val rLowRows  = ((rCenter + 1) until N).toList().takeIf { it.isNotEmpty() } ?: listOf(N - 1)
        fun f(row: Int, col: Int): Float {
            val idx = row * N + col
            if (idx < 0 || idx >= tofData.size) return D_MAX
            val v = tofData[idx]
            return if (v >= 30 && v <= D_MAX.toInt()) v.toFloat() else D_MAX
        }
        fun avg(rows: List<Int>): Float {
            var s = 0f; var c = 0
            for (r in rows) for (col in 0 until N) { val v = f(r, col); if (v < D_MAX) { s += v; c++ } }
            return if (c > 0) s / c else D_MAX
        }
        val zHigh = avg(rHighRows)
        val zLow  = avg(rLowRows)
        val dzV = zHigh - zLow
        val dzT = zLow - prevZLowSafe
        val sigmaJ4 = FloatArray(N)
        for (c in 0 until N) {
            val vals = rLowRows.map { r -> f(r, c) }.filter { it < D_MAX }
            if (vals.isEmpty()) continue
            val mean = vals.average().toFloat()
            sigmaJ4[c] = sqrt(vals.map { (it - mean) * (it - mean) }.average().toFloat())
        }
        val R  = if (zHigh > 0f && zHigh < D_MAX) zLow / zHigh else 0f
        val xi = abs(zHigh - zLow)
        val anomalyCols4 = (0 until N).filter { c -> sigmaJ4[c] > SIGMA_COL_TH }
        val pattern4 = when {
            anomalyCols4.isEmpty() -> "SAFE"
            anomalyCols4.size >= 2 -> "UNIFORM"
            anomalyCols4.size == 1 -> "LOCALIZED"
            else                   -> "MIXED"
        }
        val type = when {
            zHigh >= D_GUARD                                                  -> TerrainType.OPEN
            zLow  <  D_CONT                                                   -> TerrainType.CONTAMINATED
            dzV   >  DELTA_Z_STEP && R > R_TH_HI && pattern4 == "UNIFORM"   -> TerrainType.STAIR_DOWN
            dzV   >  (DELTA_Z_STEP * 1.5f) && R > R_TH_HI && pattern4 == "LOCALIZED" -> TerrainType.HOLE
            dzV   < -DELTA_Z_STEP && xi > EDGE_TH                            -> TerrainType.STAIR_UP
            else                                                              -> TerrainType.SAFE
        }
        if (typeHistory.size >= HISTORY_SIZE) typeHistory.removeFirst()
        typeHistory.addLast(type)
        val cosAlpha = Math.cos(Math.toRadians(ALPHA_MOUNT.toDouble())).toFloat()
        val hEst = when (type) {
            TerrainType.STAIR_DOWN -> if (prevZLowSafe > 0f) abs(zLow - prevZLowSafe) / cosAlpha else abs(dzV) / cosAlpha
            TerrainType.HOLE, TerrainType.STAIR_UP -> abs(zHigh - zLow)
            else -> 0f
        }.coerceAtLeast(0f)
        if (type == TerrainType.SAFE && zLow < D_MAX) prevZLowSafe = zLow
        val direction = when {
            anomalyCols4.isEmpty()        -> 12
            anomalyCols4.average() < 1.5  -> 11
            anomalyCols4.average() >= 2.5 ->  1
            else                          -> 12
        }
        val cR = when (type) {
            TerrainType.STAIR_DOWN, TerrainType.HOLE -> ((R - R_TH_HI) / (1f - R_TH_HI)).coerceIn(0f, 1f)
            TerrainType.STAIR_UP -> (xi / (EDGE_TH * 2f)).coerceIn(0f, 1f)
            else                 -> 0f
        }
        val cSpatial  = when (pattern4) { "UNIFORM" -> 0.9f; "LOCALIZED" -> 0.8f; "MIXED" -> 0.5f; else -> 0.1f }
        val cTemporal = if (typeHistory.isEmpty()) 0f else typeHistory.count { it == type }.toFloat() / typeHistory.size
        val cEdge = when (type) {
            TerrainType.STAIR_UP -> (xi / (EDGE_TH * 2f)).coerceIn(0f, 1f)
            TerrainType.HOLE     -> if (abs(dzT) > DELTA_Z_STEP * 0.5f) 0.8f else 0.5f
            else                 -> 0.5f
        }
        val confidence = (0.40f * cR + 0.30f * cSpatial + 0.20f * cTemporal + 0.10f * cEdge).coerceIn(0f, 1f)
        return TerrainResult(type = type, confidence = confidence, hEst = hEst, direction = direction, distance = zLow)
    }
}