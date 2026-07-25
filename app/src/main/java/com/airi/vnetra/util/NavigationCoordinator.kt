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
        // Payload IMU dari firmware (per field):
        //   [0]=theta (pitch°)  [1]=phi (roll°)
        //   [2]=wx_corr (pitch rate °/s — nodding atas/bawah, sumbu X lokal sensor)
        //   [3]=wy_corr (roll  rate °/s — miring kiri/kanan, sumbu Y lokal sensor)
        //   [4]=wz_corr (yaw   rate °/s — putar kiri/kanan, sumbu Z lokal sensor)
        //   [5]=aLinMag  [6]=tsEspMs  [7]=vHeadBase  [8]=isConverged
        val pitchRate = imuData?.getOrElse(2) { 0f } ?: 0f  // [2] wx_corr = pitch rate (nod)
        val rollRate  = imuData?.getOrElse(3) { 0f } ?: 0f  // [3] wy_corr = roll  rate (tilt)
        val yawRate   = imuData?.getOrElse(4) { 0f } ?: 0f  // [4] wz_corr = yaw   rate (turn)
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
        val pitchRate = imuData?.getOrElse(2) { 0f } ?: 0f  // [2] wx_corr = pitch rate (nod)
        val rollRate  = imuData?.getOrElse(3) { 0f } ?: 0f  // [3] wy_corr = roll  rate (tilt)
        val yawRate   = imuData?.getOrElse(4) { 0f } ?: 0f  // [4] wz_corr = yaw   rate (turn)
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
        

        val isMovingForward = movingForwardConsecutiveFrames >= 3
        val rawTheta = imuData?.getOrElse(0) { 0f } ?: 0f
        // rawTheta = pitch angle absolut dari firmware (theta dari Mahony quaternion).
        // JANGAN dikurangi 20° di sini — TofDepthEstimator.calculate() sudah menambahkan
        // MOUNT_PITCH_DEG (20°) secara internal sehingga kalkulasi mount offset cukup dilakukan
        // satu tempat saja (prinsip DRY). Pengurangan di sini sebelumnya self-cancelling.
        val thetaDeg = rawTheta

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
                val isPaving = label.startsWith("paving")
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


