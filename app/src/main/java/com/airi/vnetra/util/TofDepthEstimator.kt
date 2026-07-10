package com.airi.vnetra.util

import kotlin.math.roundToInt

/**
 * TofDepthEstimator — Estimasi jarak objek dari sensor ToF (v9.4)
 *
 * Alur (per objek per frame):
 *   E.0  Filter sentinel: z ∈ [ε_noise, d_max] → valid; 0, -1, 65535 → invalid
 *   E.1  r_center = round(3.5 + θ/δθ)          — baris pusat, kompensasi pitch
 *   E.2  R_obj = {sat(r_c-1,0,N-1), r_c, sat(r_c+1,0,N-1)} — 3 baris window
 *   E.3  d_obj = rata-rata baris valid di kolom j;  fallback = D_MAX jika semua invalid
 *
 * Catatan konversi nilai firmware → IntArray di CameraStreamService:
 *   - Firmware mengirim int16 little-endian; toInt() tanpa mask → -1 = sentinel 0xFFFF
 *   - 0 = no target; nilai < ε_noise dianggap di bawah noise floor sensor
 *   - Filter E.0 menangani semua kasus ini secara seragam.
 *
 * Referensi: formula-matematis-v9.4.md §E
 */
object TofDepthEstimator {

    // Konstanta Sistem
    const val D_MAX     = 4000   // mm — jangkauan maksimum sensor
    const val EPS_NOISE = 30     // mm — noise floor VL53L5CX
    const val TOF_FOV_V = 45f    // ° — FoV vertikal total VL53L5CX
    
    // Kemiringan mekanis dudukan perangkat (menunduk)
    const val MOUNT_PITCH_DEG = 20f

    /**
     * Hitung jarak objek dari matriks ToF pada kolom [j], dengan kompensasi pitch [thetaDeg].
     *
     * @param tofData  array flat baris-major: index = row × resolution + col
     *                 Nilai -1 = sentinel firmware (0xFFFF); 0 = no target
     * @param j        indeks kolom ToF ∈ {0..resolution-1} dari Formula D
     * @param thetaDeg pitch kepala (°) dari IMU — positif = menunduk, negatif = mendongak
     * @param resolution resolusi aktif: 4 (4×4) atau 8 (8×8)
     * @return d_obj ∈ [EPS_NOISE, D_MAX] mm; D_MAX jika semua baris invalid
     */
    fun calculate(
        tofData: IntArray,
        j: Int,
        thetaDeg: Float,
        resolution: Int = 8
    ): Int {
        // Guard: kolom di luar range → fallback D_MAX (tidak memicu peringatan palsu)
        if (j < 0 || j >= resolution) return D_MAX

        // E.1: Baris pusat berdasarkan pitch (δθ = FOV/N per baris)
        // Center row = (N-1)/2 agar simetris:
        //   8×8: center = (8-1)/2 = 3.5  → r_center range [0..7]
        //   4×4: center = (4-1)/2 = 1.5  → r_center range [0..3]
        val deltaTheta = TOF_FOV_V / resolution   // °/baris: 5.625° (8×8) atau 11.25° (4×4)
        val centerRow  = (resolution - 1) / 2.0f
        
        val totalPitch = thetaDeg + MOUNT_PITCH_DEG
        val rCenter = (centerRow + totalPitch / deltaTheta)
            .roundToInt()
            .coerceIn(0, resolution - 1)

        // E.2: Himpunan 3 baris di sekitar r_center
        // distinct() kritis saat rCenter=0 atau rCenter=N-1:
        //   rCenter=0 → {-1.coerceIn→0, 0, 1}.distinct() = {0, 1}  (bukan {0, 0, 1})
        val rows = listOf(
            (rCenter - 1).coerceIn(0, resolution - 1),
            rCenter,
            (rCenter + 1).coerceIn(0, resolution - 1)
        ).distinct()

        // E.0 + E.3: Filter sentinel dan rata-rata
        var sum   = 0
        var count = 0
        for (r in rows) {
            val idx = r * resolution + j
            if (idx < 0 || idx >= tofData.size) continue
            val z = tofData[idx]
            // E.0: -1 (sentinel firmware), 0 (no target), <EPS_NOISE (di bawah noise floor)
            if (z >= EPS_NOISE && z <= D_MAX) {
                sum   += z
                count++
            }
        }

        // E.3 fallback: jika semua baris invalid → D_MAX (tidak ada objek terdeteksi)
        return if (count > 0) sum / count else D_MAX
    }
}
