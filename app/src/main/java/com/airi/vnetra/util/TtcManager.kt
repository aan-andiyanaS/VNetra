package com.airi.vnetra.util

import com.airi.vnetra.model.DetectionResult
import kotlin.math.abs

enum class TtcStatus {
    IMMINENT, PROBABLE, POSSIBLE
}

/**
 * TtcManager — Implementasi Formula I (Time-To-Contact) v9.4
 * Menghitung tingkat ancaman (Threat Level) berbasis Optical Expansion 
 * untuk menutupi kelemahan sensor ToF (jarak terbatas outdoor siang hari).
 */
class TtcManager {
    // Thresholds and configuration based on Formula I v8
    private val deltaANorm = 0.5f // 50%
    private val deltaARThresh = 0.2f // 20%
    private val ttcHigh = 0.75f
    private val ttcMid = 0.40f

    // Class weights (m_class)
    private val classWeights = mapOf(
        "bus" to 1.6f,
        "truck" to 1.6f,
        "car" to 1.5f,
        "motorcycle" to 1.2f,
        "person" to 1.0f,
        "bicycle" to 0.8f
    )
    
    // Memory for object history
    private class ObjectHistory {
        val areas = mutableListOf<Float>()
        var lastAR: Float = -1f
        var lastDObj: Int = -1
        var lastCx: Float = -1f
    }

    private val objectMemory = mutableMapOf<Int, ObjectHistory>()

    /**
     * Evaluasi tingkat ancaman objek berdasarkan pelebaran kotak (area growth),
     * stabilitas rasio aspek (aspect ratio), dan konfirmasi jarak ToF.
     */
    fun evaluateThreat(det: DetectionResult, dObj: Int): TtcStatus {
        if (det.trackId == -1) return TtcStatus.POSSIBLE

        val history = objectMemory.getOrPut(det.trackId) { ObjectHistory() }
        
        val width = det.boundingBox.width()
        val height = det.boundingBox.height()
        
        // Guard degenerate bounding box
        if (width <= 0 || height <= 0) return TtcStatus.POSSIBLE
        
        val currentArea = width * height
        val currentAR = width / height

        // Update area history (keep max 3 frames for moving average)
        history.areas.add(currentArea)
        if (history.areas.size > 3) {
            history.areas.removeAt(0)
        }

        val cx = det.boundingBox.centerX()

        // Need at least 2 frames of moving average to compare
        if (history.areas.size < 2) {
            history.lastAR = currentAR
            history.lastDObj = dObj
            history.lastCx = cx
            return TtcStatus.POSSIBLE
        }

        // Calculate current and previous moving average
        val currentMA = history.areas.average().toFloat()
        val previousMA = history.areas.dropLast(1).average().toFloat()

        // I.2: Area Growth (Delta A)
        val deltaA = if (previousMA > 0) (currentMA - previousMA) / previousMA else 0f
        
        val areaScore = when {
            deltaA < 0 -> 0.0f
            deltaA in 0f..deltaANorm -> deltaA / deltaANorm
            else -> 1.0f
        }

        // I.3: Aspect Ratio Stability (Delta AR)
        val deltaAR = if (history.lastAR > 0) abs(currentAR - history.lastAR) / history.lastAR else 0f
        
        val arScore = if (deltaAR <= deltaARThresh) {
            1.0f - (deltaAR / deltaARThresh)
        } else {
            0.0f
        }

        // I.4: ToF Distance Consistency
        // dObj <= 0 means ToF is blind/invalid (e.g. outdoors > 1m)
        val distScore = if (history.lastDObj > 0 && dObj > 0) {
            val deltaD = history.lastDObj - dObj
            val epsilon = 30 // Noise margin in mm
            when {
                deltaD > epsilon && deltaA > 0 -> 1.0f
                abs(deltaD) <= epsilon && deltaA > 0 -> 0.5f
                else -> 0.0f
            }
        } else {
            // ToF is blind or invalid -> Neutral fallback (0.5)
            // Allows Formula I to override if areaScore + arScore are high enough
            0.5f 
        }

        // I.5 & I.8: Lateral Drift Guard (v9.5) - Menggantikan latScore lama
        val deltaCx = if (history.lastCx >= 0) abs(cx - history.lastCx) else 0f
        val driftNorm = 60f // R_col (lebar 1 kolom ToF)
        val driftScore = (deltaCx / driftNorm).coerceIn(0f, 1f)
        
        // Guard: Jika ToF yakin jarak berkurang (distScore > 0.5), drift diabaikan (objek menabrak miring)
        val driftGuard = if (distScore <= 0.5f) driftScore else 0f

        // I.6: TTC Combined Score (dengan post-hoc modifier drift_guard)
        val baseTtcScore = (0.50f * areaScore) + (0.25f * arScore) + (0.25f * distScore)
        val ttcScore = baseTtcScore * (1f - driftGuard)

        // I.7: Weighted by Class
        val weight = classWeights[det.className] ?: 1.0f
        val ttcWeighted = (ttcScore * weight).coerceIn(0f, 1f)

        // Update history for next frame
        history.lastAR = currentAR
        history.lastDObj = dObj
        history.lastCx = cx

        // I.7: Final Pool Condition
        return when {
            ttcWeighted > ttcHigh -> TtcStatus.IMMINENT
            ttcWeighted > ttcMid -> TtcStatus.PROBABLE
            else -> TtcStatus.POSSIBLE
        }
    }
    
    /**
     * Membersihkan memori pelacakan objek yang sudah hilang dari layar.
     */
    fun cleanup(activeTrackIds: Set<Int>) {
        objectMemory.keys.retainAll(activeTrackIds)
    }
}
