package com.airi.vnetra.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.airi.vnetra.model.DetectionResult

class BoundingBoxOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var results: List<DetectionResult> = emptyList()

    // Assuming the camera frame aspect ratio (usually 4:3 from ESP32).
    // We will get the scale based on the view's width and height.
    // However, YoloDetector scales to original image size (e.g. 640x480).
    // We need to scale from original image size to this View's size.
    private var sourceWidth = 640f
    private var sourceHeight = 480f

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        textSize = 36f
    }

    fun setResults(newResults: List<DetectionResult>, srcW: Float, srcH: Float) {
        results = newResults
        sourceWidth = srcW
        sourceHeight = srcH
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (results.isEmpty()) return

        val scaleX = width / sourceWidth
        val scaleY = height / sourceHeight

        for (result in results) {
            val left = result.boundingBox.left * scaleX
            val top = result.boundingBox.top * scaleY
            val right = result.boundingBox.right * scaleX
            val bottom = result.boundingBox.bottom * scaleY

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Draw label
            val labelString = "${result.className} ${(result.confidence * 100).toInt()}%"
            val textBounds = Rect()
            textPaint.getTextBounds(labelString, 0, labelString.length, textBounds)

            val textWidth = textBounds.width()
            val textHeight = textBounds.height()

            // Draw background for text
            canvas.drawRect(
                left,
                top - textHeight - 8f,
                left + textWidth + 8f,
                top,
                textBackgroundPaint
            )

            // Draw text
            canvas.drawText(labelString, left + 4f, top - 4f, textPaint)
        }
    }
}
