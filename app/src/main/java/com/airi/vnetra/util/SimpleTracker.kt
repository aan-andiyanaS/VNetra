package com.airi.vnetra.util

import android.graphics.RectF
import com.airi.vnetra.model.DetectionResult

/**
 * SimpleTracker — Pelacak Kecepatan Linear Sederhana (EMA)
 * 
 * Melacak bounding box menggunakan velocity (dx, dy).
 * Jika YOLO kehilangan objek selama beberapa frame (< maxAge), tracker 
 * akan memprediksi lokasinya berdasarkan kecepatan pergerakan terakhirnya.
 * 
 * Sesuai prinsip "ponytail": Sangat efisien, komputasi O(N^2) untuk N kecil,
 * meminimalkan flickering tanpa overhead Kalman Filter (matriks).
 */
class SimpleTracker(
    private val maxAge: Int = 5,
    private val iouThreshold: Float = 0.3f
) {
    data class Track(
        var classId: Int,
        var className: String,
        var confidence: Float,
        var boundingBox: RectF,
        var dx: Float = 0f,
        var dy: Float = 0f,
        var missedFrames: Int = 0
    ) {
        fun predict() {
            boundingBox.offset(dx, dy)
            missedFrames++
        }

        fun update(det: DetectionResult) {
            // Update kecepatan menggunakan EMA (Exponential Moving Average) alpha = 0.5
            val newDx = det.boundingBox.centerX() - boundingBox.centerX()
            val newDy = det.boundingBox.centerY() - boundingBox.centerY()
            
            // Jika ini update pertama (dx == 0), langsung pakai nilai baru
            if (dx == 0f && dy == 0f) {
                dx = newDx
                dy = newDy
            } else {
                dx = 0.5f * dx + 0.5f * newDx
                dy = 0.5f * dy + 0.5f * newDy
            }

            // Update state ke observasi terbaru
            boundingBox = RectF(det.boundingBox)
            classId = det.classId
            className = det.className
            confidence = det.confidence
            missedFrames = 0
        }
    }

    private val tracks = mutableListOf<Track>()

    fun process(detections: List<DetectionResult>): List<DetectionResult> {
        // 1. Prediksi lokasi baru untuk semua track yang ada berdasarkan kecepatan terakhir
        tracks.forEach { it.predict() }

        // 2. Cocokkan deteksi baru dengan track yang ada
        val unmatchedDetections = mutableListOf<DetectionResult>()
        val matchedTracks = mutableSetOf<Track>()

        for (det in detections) {
            var bestTrack: Track? = null
            var bestIou = iouThreshold

            for (track in tracks) {
                if (track.classId == det.classId && track !in matchedTracks) {
                    val iou = calculateIoU(track.boundingBox, det.boundingBox)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestTrack = track
                    }
                }
            }

            if (bestTrack != null) {
                bestTrack.update(det)
                matchedTracks.add(bestTrack)
            } else {
                unmatchedDetections.add(det)
            }
        }

        // 3. Buat track baru untuk deteksi yang tidak cocok dengan track manapun
        for (det in unmatchedDetections) {
            tracks.add(
                Track(
                    classId = det.classId,
                    className = det.className,
                    confidence = det.confidence,
                    boundingBox = RectF(det.boundingBox)
                )
            )
        }

        // 4. Hapus track yang sudah tidak terdeteksi terlalu lama
        tracks.removeAll { it.missedFrames > maxAge }

        // 5. Kembalikan semua track yang masih hidup sebagai DetectionResult
        return tracks.map { track ->
            DetectionResult(
                classId = track.classId,
                className = track.className,
                // Beri sedikit pinalti confidence jika ini hanya hasil tebakan prediksi (missedFrames > 0)
                confidence = if (track.missedFrames > 0) track.confidence * 0.8f else track.confidence,
                boundingBox = RectF(track.boundingBox)
            )
        }
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersection = RectF(
            maxOf(box1.left, box2.left),
            maxOf(box1.top, box2.top),
            minOf(box1.right, box2.right),
            minOf(box1.bottom, box2.bottom)
        )
        if (intersection.width() <= 0 || intersection.height() <= 0) return 0f
        
        val intersectionArea = intersection.width() * intersection.height()
        val area1 = box1.width() * box1.height()
        val area2 = box2.width() * box2.height()
        
        return intersectionArea / (area1 + area2 - intersectionArea)
    }
}
