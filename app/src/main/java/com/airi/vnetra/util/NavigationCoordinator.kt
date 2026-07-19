package com.airi.vnetra.util

import com.airi.vnetra.model.DetectionResult
import kotlin.math.abs
import android.util.Log

/**
 * NavigationCoordinator
 * 
 * Mengelola logic navigasi, sensor fusion, dan trigger peringatan suara (TTS).
 * Dipisahkan dari CameraStreamActivity untuk mematuhi Single Responsibility Principle.
 */
class NavigationCoordinator(
    private val ttsAlertManager: TtsAlertManager,
    private val ttcManager: TtcManager
) {
    var movingForwardConsecutiveFrames = 0
        private set

    /**
     * Memperbarui state pergerakan pengguna berdasarkan data IMU.
     * Menggunakan threshold akselerasi dan rotasi untuk menentukan apakah pengguna sedang melangkah.
     */
    fun updateMovementState(imuData: FloatArray?) {
        val pitchRate = imuData?.getOrElse(2) { 0f } ?: 0f
        val rollRate  = imuData?.getOrElse(3) { 0f } ?: 0f
        val yawRate   = imuData?.getOrElse(4) { 0f } ?: 0f
        val aLinMag   = imuData?.getOrElse(5) { 0f } ?: 0f
        
        // Sensitivitas 5f agar nodding pelan terdeteksi sebagai rotasi kepala, bukan langkah kaki.
        val isHeadRotating = abs(pitchRate) > 5f || abs(yawRate) > 5f || abs(rollRate) > 5f
        val isAccelerating = (aLinMag > 2.94f) && !isHeadRotating
        
        if (isAccelerating) {
            movingForwardConsecutiveFrames++
        } else {
            movingForwardConsecutiveFrames = 0
        }
    }

    /**
     * Cek apakah kepala sedang berotasi dengan threshold tertentu (default 10f).
     */
    fun isHeadRotating(imuData: FloatArray?, threshold: Float = 10f): Boolean {
        val pitchRate = imuData?.getOrElse(2) { 0f } ?: 0f
        val rollRate  = imuData?.getOrElse(3) { 0f } ?: 0f
        val yawRate   = imuData?.getOrElse(4) { 0f } ?: 0f
        return abs(pitchRate) > threshold || abs(yawRate) > threshold || abs(rollRate) > threshold
    }

    /**
     * Memproses deteksi YOLO secara instan untuk langsung memicu TTS tanpa menunggu ToF loop (100ms).
     * Mengevaluasi ancaman menggunakan TtcManager (Formula I).
     */
    fun processInstantYoloTts(
        detections: List<DetectionResult>,
        tofData: IntArray?,
        imuData: FloatArray?,
        frameWidth: Int,
        tofMode: Int
    ) {
        if (detections.isEmpty()) return
        if (tofData == null) return
        
        // ADR-035: Jika kepala sedang berotasi, lewati seluruh pemrosesan YOLO TTS.
        if (isHeadRotating(imuData, 10f)) {
            Log.v("YOLO_TTS", "Blocked by isHeadRotatingNow")
            return
        }
        
        val isMovingForward = movingForwardConsecutiveFrames >= 3
        val rawTheta = imuData?.getOrElse(0) { 0f } ?: 0f
        val thetaDeg = rawTheta - 20f

        val mappedDetections = detections.mapNotNull { det ->
            val xcRaw = SpatialMappingUtils.centroidX(det.boundingBox.left, det.boundingBox.right)
            val xc = xcRaw * (SpatialMappingUtils.W_CAM.toFloat() / frameWidth.toFloat())
            
            if (!SpatialMappingUtils.isInTofZone(xc)) {
                Log.v("YOLO_TTS", "-> Ditolak: ${det.className} di luar zona ToF (xc=$xc)")
                null
            } else {
                val arahJam = SpatialMappingUtils.mapToClockDirection(xc)
                val j = SpatialMappingUtils.mapToTofColumn(xc, tofMode)
                var dObj = TofDepthEstimator.calculate(tofData, j, thetaDeg, tofMode)
                
                val ttcStatus = ttcManager.evaluateThreat(det, dObj, imuData)
                if (dObj >= TofDepthEstimator.D_MAX) {
                    if (ttcStatus == TtcStatus.IMMINENT) {
                        dObj = 500
                        Log.v("YOLO_TTS", "-> ToF Gagal, tapi TTC IMMINENT! Paksa dObj=500")
                    } else if (ttcStatus == TtcStatus.PROBABLE) {
                        dObj = 1000
                        Log.v("YOLO_TTS", "-> ToF Gagal, TTC PROBABLE. Paksa dObj=1000")
                    } else {
                        Log.v("YOLO_TTS", "-> ToF Gagal, TTC POSSIBLE. Abaikan.")
                    }
                } else {
                    if (ttcStatus == TtcStatus.IMMINENT && dObj > 1000) {
                        dObj = 500
                        Log.v("YOLO_TTS", "-> TTC Override! ToF $dObj tapi objek mendekat cepat, paksa dObj=500")
                    }
                }
                
                det to Triple(dObj, arahJam, det.className)
            }
        }
        
        val closestDetections = mappedDetections
            .groupBy { it.first.classId }
            .mapValues { entry -> entry.value.minByOrNull { it.second.first }!! }

        val activeClasses = closestDetections.keys
        val urgentAlerts = mutableListOf<String>()
        val infoAlerts = mutableListOf<String>()

        for ((classId, detPair) in closestDetections) {
            val dObj = detPair.second.first
            if (dObj >= TofDepthEstimator.D_MAX) continue
            
            val arahJam = detPair.second.second
            val label = detPair.second.third
            val alertMsg = ttsAlertManager.process(
                trackingId     = classId,
                dObj           = dObj,
                clockDirection = arahJam,
                objectLabel    = label,
                isMovingForward = isMovingForward,
                imuData         = imuData
            )
            
            if (alertMsg != null) {
                val isPaving = label in listOf("lurus", "belok", "simpang 3", "simpang 4", "stop")
                val isPeripheral = arahJam == 10 || arahJam == 2
                
                if (isPaving || isPeripheral) {
                    infoAlerts.add(alertMsg)
                } else {
                    urgentAlerts.add(alertMsg)
                }
            }
        }

        if (urgentAlerts.isNotEmpty()) {
            val combinedMsg = urgentAlerts.joinToString(", dan ")
            ttsAlertManager.speak(combinedMsg) // QUEUE_FLUSH
        }
        if (infoAlerts.isNotEmpty()) {
            val combinedMsg = infoAlerts.joinToString(", dan ")
            ttsAlertManager.speakAdd(combinedMsg) // QUEUE_ADD
        }

        ttsAlertManager.postProcessDetections(activeClasses)
    }
}
