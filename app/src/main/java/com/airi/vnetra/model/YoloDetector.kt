package com.airi.vnetra.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class DetectionResult(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val boundingBox: RectF,
    var trackId: Int = -1
)

enum class ModelStatus {
    NONE, FP32, INT8, FULL
}

enum class DelegateMode {
    AUTO, NPU, GPU, CPU
}

enum class ModelPreference {
    AUTO, FP32, INT8
}

class YoloDetector(
    private val context: Context, 
    private val delegateMode: DelegateMode = DelegateMode.AUTO,
    private val modelPreference: ModelPreference = ModelPreference.AUTO
) {
    companion object {
        private const val TAG = "YoloDetector"
        private const val MODEL_FP32 = "best_fp32.tflite"
        private const val MODEL_INT8 = "best_int8.tflite"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 9
        private const val OUTPUT_BOXES = 8400
        private const val CONFIDENCE_THRESHOLD = 0.30f
        private const val IOU_THRESHOLD = 0.45f

        // Nama kelas sesuai urutan output model (Bahasa Inggris, urutan alphabetical dari dataset)
        // KRITIS: Harus sesuai persis dengan data.yaml nc & names yang digunakan saat training.
        private val CLASSES = arrayOf(
            "car", "drain", "motorcycle", "person", "pole",
            "tactile_paving_alert", "tactile_paving_straight", "trading_cart", "tree"
        )

        // Peta translasi: nama kelas model (Inggris) → label TTS (Bahasa Indonesia)
        // Ini satu-satunya tempat di mana mapping ini didefinisikan.
        private val CLASS_LABELS_ID = mapOf(
            "car"                      to "mobil",
            "drain"                    to "selokan",
            "motorcycle"               to "motor",
            "person"                   to "orang",
            "pole"                     to "tiang",
            "tactile_paving_alert"     to "paving peringatan",
            "tactile_paving_straight"  to "paving lurus",
            "trading_cart"             to "gerobak",
            "tree"                     to "pohon"
        )
    }

    var modelStatus: ModelStatus = ModelStatus.NONE
        private set

    var activeDelegate: DelegateMode? = null
        private set

    var lastMaxConfidence: Float = 0f

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    private var isTransposedOutput = false
    private var outputBufferTransposed: Array<Array<FloatArray>>? = null
    private var outputBufferStandard: Array<Array<FloatArray>>? = null
    
    // Default fallback, but will be dynamically updated based on the model's actual shape
    private var dynamicNumClasses = NUM_CLASSES
    private var dynamicOutputBoxes = OUTPUT_BOXES
    
    private val outputBuffer = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(OUTPUT_BOXES) } }
    private val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    // P2: Pre-alokasi IntArray sekali di init untuk menghindari alokasi 1.2MB per frame inference.
    // INPUT_SIZE² = 640×640 = 409.600 elemen — cukup untuk input bitmap apa pun ≤ 640×640.
    private val cachedIntValues = IntArray(INPUT_SIZE * INPUT_SIZE)

    init {
        setupModel()
    }

    private fun setupModel() {
        val hasFp32 = hasAsset(MODEL_FP32)
        val hasInt8 = hasAsset(MODEL_INT8)

        if (hasFp32 && hasInt8) modelStatus = ModelStatus.FULL
        else if (hasFp32) modelStatus = ModelStatus.FP32
        else if (hasInt8) modelStatus = ModelStatus.INT8
        else modelStatus = ModelStatus.NONE

        if (modelStatus == ModelStatus.NONE) {
            Log.w(TAG, "No YOLO model found. Operating in INFERENCE_BYPASS_MODE.")
            return
        }

        try {
            val options = Interpreter.Options()

            val supportNpu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1

            val finalModelName = if (hasFp32) MODEL_FP32 else MODEL_INT8
            val modelBuffer = loadModelFile(finalModelName)

            var delegateSuccess = false
            
            // Logika Fallback Cerdas yang lebih ketat:
            // - Jika INT8: NPU sangat optimal -> NPU, lalu CPU (GPU tidak mendukung INT8 murni)
            // - Jika FP32: GPU sangat optimal -> GPU, lalu CPU (NPU kurang efisien untuk FP32)
            val fallbackOrder = if (finalModelName == MODEL_INT8) {
                listOf(DelegateMode.NPU, DelegateMode.CPU)
            } else {
                listOf(DelegateMode.GPU, DelegateMode.CPU)
            }

            for (delegate in fallbackOrder) {
                try {
                    when (delegate) {
                        DelegateMode.NPU -> {
                            if (!supportNpu) continue
                            options.setUseNNAPI(true)
                            activeDelegate = DelegateMode.NPU
                        }
                        DelegateMode.GPU -> {
                            // ADR-047 Fix A: SUSTAINED_SPEED mencegah GPU frekuensi drop saat thermal throttle.
                            // Pada Exynos 990, ini menstabilkan inference dari ~55ms → ~35ms saat device panas.
                            // Jika driver tidak mendukung hint ini, opsi diabaikan secara diam-diam.
                            val gpuOptions = GpuDelegate.Options().apply {
                                inferencePreference = GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
                            }
                            gpuDelegate = GpuDelegate(gpuOptions)
                            options.addDelegate(gpuDelegate)
                            activeDelegate = DelegateMode.GPU
                        }
                        DelegateMode.CPU -> {
                            // ADR-047 Fix B: Adaptive thread count — deteksi core aktual saat runtime.
                            // coerceIn(2, 6): minimum 2 (device 2-core), maksimum 6 (flagship 12-core).
                            // Contoh: 4-core → 2 thread, 8-core → 4 thread, 12-core → 6 thread.
                            val cores = Runtime.getRuntime().availableProcessors()
                            val threads = (cores / 2).coerceIn(2, 6)
                            options.setNumThreads(threads)
                            Log.i(TAG, "CPU fallback: $threads threads (dari $cores core tersedia)")
                            activeDelegate = DelegateMode.CPU
                        }
                        else -> continue
                    }

                    interpreter = Interpreter(modelBuffer, options)
                    delegateSuccess = true
                    Log.i(TAG, "Berhasil inisialisasi model $finalModelName dengan delegate $activeDelegate")
                    break // Berhasil, keluar dari loop fallback
                } catch (e: Throwable) {
                    Log.w(TAG, "Gagal inisialisasi $delegate: ${e.message}. Mencoba fallback selanjutnya...")
                    // Bersihkan delegate yang gagal
                    gpuDelegate?.close()
                    gpuDelegate = null
                    options.setUseNNAPI(false)
                }
            }

            if (!delegateSuccess) {
                Log.e(TAG, "Semua percobaan delegate gagal!")
                modelStatus = ModelStatus.NONE
                return
            }

            val inputTensor = interpreter?.getInputTensor(0)
            Log.i(TAG, "Input Tensor: DataType=${inputTensor?.dataType()}, Shape=${inputTensor?.shape()?.contentToString()}")
            
            val outputTensor = interpreter?.getOutputTensor(0)
            val shape = outputTensor?.shape()
            Log.i(TAG, "Output Tensor: DataType=${outputTensor?.dataType()}, Shape=${shape?.contentToString()}")
            
            if (shape != null && shape.size == 3) {
                // shape could be [1, boxes, num_classes+4] OR [1, num_classes+4, boxes]
                if (shape[1] > shape[2]) {
                    // Transposed
                    dynamicOutputBoxes = shape[1]
                    val coordsAndClasses = shape[2]
                    dynamicNumClasses = coordsAndClasses - 4
                    isTransposedOutput = true
                    outputBufferTransposed = Array(1) { Array(dynamicOutputBoxes) { FloatArray(coordsAndClasses) } }
                    Log.i(TAG, "Model uses transposed output shape: [1, $dynamicOutputBoxes, $coordsAndClasses] ($dynamicNumClasses classes)")
                } else {
                    // Standard
                    dynamicOutputBoxes = shape[2]
                    val coordsAndClasses = shape[1]
                    dynamicNumClasses = coordsAndClasses - 4
                    isTransposedOutput = false
                    outputBufferStandard = Array(1) { Array(coordsAndClasses) { FloatArray(dynamicOutputBoxes) } }
                    Log.i(TAG, "Model uses standard output shape: [1, $coordsAndClasses, $dynamicOutputBoxes] ($dynamicNumClasses classes)")
                }
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing TFLite Interpreter: ${e.message}", e)
            interpreter = null
            modelStatus = ModelStatus.NONE
        }
    }

    private fun hasAsset(fileName: String): Boolean {
        return try {
            context.assets.open(fileName).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        if (interpreter == null) return emptyList()

        // 1. Preprocess
        convertBitmapToByteBuffer(bitmap)

        // 2. Inference
        try {
            if (isTransposedOutput && outputBufferTransposed != null) {
                interpreter?.run(inputBuffer, outputBufferTransposed!!)
            } else if (!isTransposedOutput && outputBufferStandard != null) {
                interpreter?.run(inputBuffer, outputBufferStandard!!)
            } else {
                interpreter?.run(inputBuffer, outputBuffer)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            return emptyList()
        }

        // 3. Postprocess (Extract Boxes & NMS)
        val results = postprocessBoxes(bitmap.width, bitmap.height)
        Log.d(TAG, "YOLO Detection completed. Found ${results.size} bounding boxes.")
        if (results.isNotEmpty()) {
            val topConf = results.maxOf { it.confidence }
            Log.d(TAG, "Top confidence: $topConf, Class: ${results.maxByOrNull { it.confidence }?.className}")
        }
        return results
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        
        // Use Letterbox padding to preserve aspect ratio
        val scale = minOf(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        // P2: Gunakan cachedIntValues (pre-alokasi) — tidak ada alokasi 1.2MB per frame.
        val intValues = cachedIntValues
        resizedBitmap.getPixels(intValues, 0, newWidth, 0, 0, newWidth, newHeight)
        // Recycle only if createScaledBitmap returned a new instance (it may return the original
        // when dimensions already match — do not recycle the caller's bitmap).
        if (resizedBitmap !== bitmap) resizedBitmap.recycle()
        
        val padX = (INPUT_SIZE - newWidth) / 2
        val padY = (INPUT_SIZE - newHeight) / 2
        
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                if (x >= padX && x < padX + newWidth && y >= padY && y < padY + newHeight) {
                    val pixelX = x - padX
                    val pixelY = y - padY
                    val valPixel = intValues[pixelY * newWidth + pixelX]
                    // Normalize 0..255 to 0.0..1.0
                    inputBuffer.putFloat(((valPixel shr 16) and 0xFF) / 255.0f)
                    inputBuffer.putFloat(((valPixel shr 8) and 0xFF) / 255.0f)
                    inputBuffer.putFloat((valPixel and 0xFF) / 255.0f)
                } else {
                    // Padding color (114 = gray)
                    inputBuffer.putFloat(114f / 255.0f)
                    inputBuffer.putFloat(114f / 255.0f)
                    inputBuffer.putFloat(114f / 255.0f)
                }
            }
        }
    }

    private fun postprocessBoxes(originalWidth: Int, originalHeight: Int): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val scale = minOf(INPUT_SIZE.toFloat() / originalWidth, INPUT_SIZE.toFloat() / originalHeight)
        val padX = (INPUT_SIZE - (originalWidth * scale).toInt()) / 2f
        val padY = (INPUT_SIZE - (originalHeight * scale).toInt()) / 2f

        if (isTransposedOutput && outputBufferTransposed != null) {
            val output = outputBufferTransposed!![0]
            var highestFrameConf = 0f
            
            for (i in 0 until dynamicOutputBoxes) {
                var maxClassConf = 0f
                var classId = -1

                for (c in 0 until dynamicNumClasses) {
                    val conf = output[i][4 + c]
                    if (conf > maxClassConf) {
                        maxClassConf = conf
                        classId = c
                    }
                }

                if (maxClassConf > CONFIDENCE_THRESHOLD) {
                    var cx = output[i][0]
                    var cy = output[i][1]
                    var w = output[i][2]
                    var h = output[i][3]

                    // Auto-detect Normalized vs Absolute
                    if (w <= 1.5f && h <= 1.5f) {
                        cx *= INPUT_SIZE
                        cy *= INPUT_SIZE
                        w *= INPUT_SIZE
                        h *= INPUT_SIZE
                    }

                    val rect = buildDetectionRect(cx, cy, w, h, padX, padY, scale, originalWidth, originalHeight)

                    val classNameEn = if (classId in CLASSES.indices) CLASSES[classId] else "obj_$classId"
                    val className = CLASS_LABELS_ID[classNameEn] ?: classNameEn
                    results.add(DetectionResult(classId, className, maxClassConf, rect))
                }
                
                if (maxClassConf > highestFrameConf) {
                    highestFrameConf = maxClassConf
                }
            }
            lastMaxConfidence = highestFrameConf
            Log.d(TAG, "[Transposed] Frame diproses. Confidence tertinggi di frame ini: $highestFrameConf")
        } else {
            val output = if (outputBufferStandard != null) outputBufferStandard!![0] else outputBuffer[0]
            var highestFrameConf = 0f
            
            for (i in 0 until dynamicOutputBoxes) {
                var maxClassConf = 0f
                var classId = -1

                for (c in 0 until dynamicNumClasses) {
                    val conf = output[4 + c][i]
                    if (conf > maxClassConf) {
                        maxClassConf = conf
                        classId = c
                    }
                }

                if (maxClassConf > highestFrameConf) {
                    highestFrameConf = maxClassConf
                }

                if (maxClassConf > CONFIDENCE_THRESHOLD) {
                    var cx = output[0][i]
                    var cy = output[1][i]
                    var w = output[2][i]
                    var h = output[3][i]

                    // Auto-detect Normalized vs Absolute
                    if (w <= 1.5f && h <= 1.5f) {
                        cx *= INPUT_SIZE
                        cy *= INPUT_SIZE
                        w *= INPUT_SIZE
                        h *= INPUT_SIZE
                    }

                    // Log the first confident box's raw coordinates
                    if (results.isEmpty()) {
                        Log.d(TAG, "Raw Box: cx=$cx, cy=$cy, w=$w, h=$h (classId=$classId, conf=$maxClassConf)")
                    }

                    val rect = buildDetectionRect(cx, cy, w, h, padX, padY, scale, originalWidth, originalHeight)

                    val classNameEn = if (classId in CLASSES.indices) CLASSES[classId] else "obj_$classId"
                    val className = CLASS_LABELS_ID[classNameEn] ?: classNameEn
                    results.add(DetectionResult(classId, className, maxClassConf, rect))
                }
            }
            lastMaxConfidence = highestFrameConf
            Log.d(TAG, "[Standard] Frame diproses. Confidence tertinggi di frame ini: $highestFrameConf")
        }

        return applyNMS(results)
    }


    private fun buildDetectionRect(
        cx: Float, cy: Float, w: Float, h: Float,
        padX: Float, padY: Float, scale: Float,
        originalWidth: Int, originalHeight: Int
    ): RectF {
        // YOLOv8 TFLite export outputs absolute coordinates in [0, INPUT_SIZE] space.
        // Do NOT apply a normalized-vs-absolute heuristic — it misfires for boxes
        // near the left/top edge where cx or cy can be between 1.0 and 2.0, causing
        // a second x640 multiplication that puts coords far off-screen (clipped to 0).
        val left   = (cx - w / 2 - padX) / scale
        val top    = (cy - h / 2 - padY) / scale
        val right  = (cx + w / 2 - padX) / scale
        val bottom = (cy + h / 2 - padY) / scale

        return RectF(
            left.coerceAtLeast(0f),
            top.coerceAtLeast(0f),
            right.coerceAtMost(originalWidth.toFloat()),
            bottom.coerceAtMost(originalHeight.toFloat())
        )
    }

    private fun applyNMS(boxes: List<DetectionResult>): List<DetectionResult> {
        val sortedBoxes = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selectedBoxes = mutableListOf<DetectionResult>()

        while (sortedBoxes.isNotEmpty()) {
            val bestBox = sortedBoxes.removeAt(0)
            selectedBoxes.add(bestBox)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val box = iterator.next()
                if (box.classId == bestBox.classId && calculateIoU(bestBox.boundingBox, box.boundingBox) > IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }
        return selectedBoxes
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

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
    }
}
