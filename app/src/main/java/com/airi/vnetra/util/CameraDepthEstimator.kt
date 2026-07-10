package com.airi.vnetra.util

import android.graphics.RectF
import kotlin.math.tan

/**
 * CameraDepthEstimator — Estimasi jarak cadangan (fallback) berbasis kamera monokuler (v9.4)
 * menggunakan metode Triangle Similarity (Known Object Size).
 *
 * Digunakan ketika sensor ToF mengalami kegagalan baca (saturasi matahari, dll).
 */
object CameraDepthEstimator {

    // Asumsi FoV Vertikal Kamera OV2640 = 41 derajat
    private const val CAMERA_FOV_V_DEG = 41.0f

    // Ukuran tinggi asli rata-rata objek di dunia nyata (dalam mm)
    private val CLASS_HEIGHTS_MM = mapOf(
        "person" to 1700f,
        "car" to 1500f,
        "motorcycle" to 1100f,
        "bus" to 3000f,
        "pole" to 2500f,
        "tree" to 3000f,
        "stairs_up" to 1500f,
        "stairs_down" to 1500f,
        "crosswalk" to 1000f, // Tinggi vertikal tampak di perspektif
        "tactile_paving_straight" to 300f,
        "tactile_paving_turn" to 300f,
        "tactile_paving_3way" to 300f,
        "tactile_paving_4way" to 300f,
        "tactile_paving_stop" to 300f
    )

    // Fallback jika label kelas tidak terdaftar
    private const val DEFAULT_HEIGHT_MM = 1200f

    /**
     * Estimasi jarak menggunakan Triangle Similarity berdasarkan tinggi Bounding Box.
     *
     * Formula: D = (H_real * F) / H_pixel
     * di mana F = H_image / (2 * tan(FoV_v / 2))
     *
     * @param className label kelas deteksi YOLO
     * @param boundingBox bounding box objek dari YOLO
     * @param imageHeight tinggi frame gambar asli (dalam pixel)
     * @param thetaDeg sudut kemiringan kepala (pitch) dalam derajat dari MPU6050
     * @return estimasi jarak dalam mm (clamped ke [30..4000] mm)
     */
    fun estimateDistance(
        className: String,
        boundingBox: RectF,
        imageHeight: Int,
        thetaDeg: Float = 0f
    ): Int {
        val hPixel = boundingBox.height()
        if (hPixel <= 0f || imageHeight <= 0) return 4000 // Fallback D_MAX jika data tidak valid

        // Ambil tinggi asli referensi untuk kelas ini
        val hReal = CLASS_HEIGHTS_MM[className] ?: DEFAULT_HEIGHT_MM

        // Hitung focal length vertikal dalam pixel
        val fovVRad = Math.toRadians(CAMERA_FOV_V_DEG.toDouble())
        val focalLengthPixel = imageHeight / (2.0 * tan(fovVRad / 2.0))

        // Estimasi jarak dasar
        val distanceBase = (hReal * focalLengthPixel) / hPixel

        // Kompensasi kemiringan kepala (pitch) menggunakan MPU6050:
        // Tinggi proyeksi objek berkurang sebanding dengan cos(theta) saat menunduk/mendongak.
        // Kalikan hasil estimasi jarak dengan cos(theta) untuk mengoreksi foreshortening.
        val thetaRad = Math.toRadians(thetaDeg.toDouble())
        val distanceCorrected = distanceBase * kotlin.math.cos(thetaRad)

        // Clamp hasil estimasi ke range VL53L5CX untuk menjaga konsistensi dengan filter Formula E
        return distanceCorrected.coerceIn(30.0, 4000.0).toInt()
    }
}
