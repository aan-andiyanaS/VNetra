package com.airi.vnetra.util

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.airi.vnetra.model.DetectionResult

class ToFGridRenderer(
    private val context: Context,
    private val gridLayout: GridLayout
) {
    private var tofViews: Array<TextView> = emptyArray()
    private val hsvTemp = FloatArray(3) { 1f }
    private val colorInvalidCell = Color.parseColor("#444444")
    
    // Constant
    private val HOLDOVER_FRAMES = 5
    private val TOF_FOV_V = 45f
    private val CAMERA_FOV_V = 41f

    fun getGridSize(): Int = tofViews.size

    fun initializeGrid(resolution: Int) {
        rebuildGrid(resolution)
    }

    fun rebuildGrid(resolution: Int) {
        val numCells = resolution * resolution
        val textSizeSp = if (resolution == 4) 11f else 7.5f

        // Remove all views before changing dimensions
        gridLayout.removeAllViews()
        gridLayout.columnCount = resolution
        gridLayout.rowCount = resolution

        tofViews = Array(numCells) { i ->
            val row = i / resolution
            val col = i % resolution
            TextView(context).apply {
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(row, 1f),
                    GridLayout.spec(col, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(1, 1, 1, 1)
                }
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = textSizeSp
                text = "—"
                setBackgroundColor(colorInvalidCell)
            }.also { gridLayout.addView(it) }
        }

        // Adjust Y offset
        val overlapFraction = (TOF_FOV_V - CAMERA_FOV_V) / 2f / TOF_FOV_V
        gridLayout.post {
            gridLayout.translationY = -(gridLayout.height.toFloat() * overlapFraction)
        }
    }

    fun updateGrid(
        tofData: IntArray,
        mode: Int,
        smoothed: FloatArray,
        holdover: IntArray,
        currentDetections: List<DetectionResult>,
        currentFrameWidth: Int,
        currentFrameHeight: Int,
        alpha: Float = 0.3f
    ) {
        if (tofViews.isEmpty() || tofData.size != tofViews.size) return

        val cellTexts = Array(tofData.size) { "" }
        val cellColors = IntArray(tofData.size) { colorInvalidCell }

        for (i in tofData.indices) {
            val row = i / mode
            val col = i % mode

            // Centroid check: apakah sel ini bertumpang-tindih dengan deteksi YOLO?
            var isYoloCentroid = false
            for (det in currentDetections) {
                val xcRaw = SpatialMappingUtils.centroidX(det.boundingBox.left, det.boundingBox.right)
                val xc = xcRaw * (SpatialMappingUtils.W_CAM.toFloat() / currentFrameWidth)
                val j = SpatialMappingUtils.mapToTofColumn(xc, mode)
                val ycRaw = (det.boundingBox.top + det.boundingBox.bottom) / 2f
                val yc = ycRaw * (SpatialMappingUtils.H_CAM.toFloat() / currentFrameHeight)
                val r = SpatialMappingUtils.mapToTofRow(yc, mode)
                if (j == col && r == row) { isYoloCentroid = true; break }
            }

            val rawDistance = tofData[i]
            if (rawDistance <= 0) {
                val remaining = holdover[i]
                if (remaining > 0) {
                    holdover[i] = remaining - 1
                    val held = smoothed[i].toInt()
                    if (held > 0) {
                        cellTexts[i] = "$held"
                        var color = getColorForDistance(held, dimmed = true)
                        if (isYoloCentroid) {
                            color = ColorUtils.blendARGB(color, Color.BLUE, 0.4f)
                        }
                        cellColors[i] = color
                    }
                } else {
                    cellTexts[i] = "—"
                    smoothed[i] = 0f
                }
            } else {
                holdover[i] = HOLDOVER_FRAMES
                smoothed[i] = if (smoothed[i] <= 0f) rawDistance.toFloat()
                              else alpha * rawDistance + (1f - alpha) * smoothed[i]
                val d = smoothed[i].toInt()
                cellTexts[i] = "$d"
                var color = getColorForDistance(d)
                if (isYoloCentroid) {
                    color = ColorUtils.blendARGB(color, Color.BLUE, 0.4f)
                }
                cellColors[i] = color
            }
        }

        render(cellTexts, cellColors)
    }

    private fun render(cellTexts: Array<String>, cellColors: IntArray) {
        for (i in cellTexts.indices) {
            if (i >= tofViews.size) break
            tofViews[i].text = cellTexts[i]
            tofViews[i].setBackgroundColor(cellColors[i])
        }
    }

    fun clearGrid() {
        tofViews.forEach {
            it.text = "—"
            it.setBackgroundColor(colorInvalidCell)
        }
    }

    private fun getColorForDistance(distance: Int, dimmed: Boolean = false): Int {
        if (distance <= 0) return colorInvalidCell
        val minDistance = 200f
        val maxDistance = 2000f
        val clampedDistance = distance.coerceIn(minDistance.toInt(), maxDistance.toInt()).toFloat()
        val ratio = (clampedDistance - minDistance) / (maxDistance - minDistance)
        hsvTemp[0] = ratio * 120f
        val alphaChannel = if (dimmed) 48 else 96
        return Color.HSVToColor(alphaChannel, hsvTemp)
    }
}
