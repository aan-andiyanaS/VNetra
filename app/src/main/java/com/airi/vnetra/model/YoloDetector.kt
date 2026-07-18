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
        private const val NUM_CLASSES = 14
        private const val OUTPUT_BOXES = 8400
        private const val CONFIDENCE_THRESHOLD = 0.30f
        private const val IOU_THRESHOLD = 0.45f

        val CLASSES = arrayOf(
            "orang", "mobil", "motor", "bus", "tiang",
            "lurus", "belok", 
            "simpang 3", "simpang 4", "stop", 
            "tangga naik", "tangga turun", "zebra cross", "pohon"
        )
    }

    var modelStatus: ModelStatus = ModelStatus.NONE
        private set

    var activeDelegate: DelegateMode? = null
        private set

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

            var targetDelegate = delegateMode
            var finalModelName = ""
            
            if (targetDelegate == DelegateMode.AUTO) {
                // Logika Mode AUTO: Prioritas NPU(INT8) > GPU(FP32) > CPU
                if (hasInt8 && supportNpu && modelPreference != ModelPreference.FP32) {
                    targetDelegate = DelegateMode.NPU
                    finalModelName = MODEL_INT8
                    Log.i(TAG, "Mode AUTO: Memprioritaskan NPU dengan model INT8.")
                } else if (hasFp32 && modelPreference != ModelPreference.INT8) {
                    targetDelegate = DelegateMode.GPU
                    finalModelName = MODEL_FP32
                    Log.i(TAG, "Mode AUTO: Memprioritaskan GPU dengan model FP32.")
                } else {
                    targetDelegate = DelegateMode.CPU
                    finalModelName = if (hasInt8 && modelPreference != ModelPreference.FP32) MODEL_INT8 else (if (hasFp32) MODEL_FP32 else MODEL_INT8)
                    Log.i(TAG, "Mode AUTO: Fallback ke CPU dengan model $finalModelName.")
                }
            } else {
                // Logika Mode MANUAL (NPU / GPU / CPU sudah ditentukan)
                // 1. Pemilihan Model (Mempertimbangkan preferensi manual pengguna)
                finalModelName = if (modelStatus == ModelStatus.FULL) {
                    if (modelPreference == ModelPreference.FP32) {
                        Log.i(TAG, "Kondisi FULL Model: Pengguna memaksa pilihan FP32.")
                        MODEL_FP32
                    } else if (modelPreference == ModelPreference.INT8) {
                        Log.i(TAG, "Kondisi FULL Model: Pengguna memaksa pilihan INT8.")
                        MODEL_INT8
                    } else {
                        // Jika preferensi model AUTO, pilih berdasarkan hardware
                        if (targetDelegate == DelegateMode.NPU || targetDelegate == DelegateMode.CPU) {
                            Log.i(TAG, "Kondisi FULL Model: Mengutamakan INT8 karena eksekutor adalah $targetDelegate.")
                            MODEL_INT8
                        } else {
                            Log.i(TAG, "Kondisi FULL Model: Mengutamakan FP32 karena eksekutor adalah GPU.")
                            MODEL_FP32
                        }
                    }
                } else {
                    if (hasFp32) MODEL_FP32 else MODEL_INT8
                }

                // 2. Evaluasi akhir Hardware vs Model yang terpilih
                if (targetDelegate == DelegateMode.GPU && finalModelName == MODEL_INT8) {
                    Log.w(TAG, "GPU tidak bisa dipilih untuk model INT8. Fallback ke NPU/CPU.")
                    targetDelegate = if (supportNpu) DelegateMode.NPU else DelegateMode.CPU
                }

                if (targetDelegate == DelegateMode.NPU && !supportNpu) {
                    Log.w(TAG, "NPU dipilih tetapi API < 27. Fallback ke GPU/CPU.")
                    targetDelegate = if (finalModelName == MODEL_FP32) DelegateMode.GPU else DelegateMode.CPU
                }
            }

            // 4. Menerapkan opsi TFLite sesuai target delegate akhir
            activeDelegate = targetDelegate
            when (targetDelegate) {
                DelegateMode.NPU -> {
                    options.setUseNNAPI(true)
                    Log.i(TAG, "Menggunakan NPU (NNAPI) Delegate dengan model $finalModelName.")
                }
                DelegateMode.GPU -> {
                    try {
                        gpuDelegate = GpuDelegate()
                        options.addDelegate(gpuDelegate)
                        Log.i(TAG, "Menggunakan GPU Delegate dengan model $finalModelName.")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Gagal inisialisasi GPU Delegate: ${e.message}. Fallback ke CPU.")
                        options.setNumThreads(4)
                        activeDelegate = DelegateMode.CPU
                    }
                }
                DelegateMode.CPU -> {
                    Log.i(TAG, "Menggunakan CPU Delegate (4 threads) dengan model $finalModelName.")
                    options.setNumThreads(4)
                }
                DelegateMode.AUTO -> {
                    // Sudah di-handle di atas (diubah ke GPU atau CPU). Tidak akan pernah tereksekusi di sini.
                }
            }

            val modelBuffer = loadModelFile(finalModelName)
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "Loaded model $finalModelName successfully.")

            val inputTensor = interpreter?.getInputTensor(0)
            Log.i(TAG, "Input Tensor: DataType=${inputTensor?.dataType()}, Shape=${inputTensor?.shape()?.contentToString()}")
            
            val outputTensor = interpreter?.getOutputTensor(0)
            val shape = outputTensor?.shape()
            Log.i(TAG, "Output Tensor: DataType=${outputTensor?.dataType()}, Shape=${shape?.contentToString()}")
            
            if (shape != null && shape.size == 3) {
                // shape could be [1, boxes, num_classes+4] OR [1, num_classes+4, boxes]
                // We know output boxes is usually large (e.g. 8400). Number of classes+4 is small (e.g. 25, 84).
                if (shape[1] > shape[2]) {
                    // Transposed: [1, 8400, classes+4]
                    dynamicOutputBoxes = shape[1]
                    val coordsAndClasses = shape[2]
                    dynamicNumClasses = coordsAndClasses - 4
                    isTransposedOutput = true
                    outputBufferTransposed = Array(1) { Array(dynamicOutputBoxes) { FloatArray(coordsAndClasses) } }
                    Log.i(TAG, "Model uses transposed output shape: [1, $dynamicOutputBoxes, $coordsAndClasses] ($dynamicNumClasses classes)")
                } else {
                    // Standard: [1, classes+4, 8400]
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
        Log.i(TAG, "YOLO Detection completed. Found ${results.size} bounding boxes.")
        if (results.isNotEmpty()) {
            val topConf = results.maxOf { it.confidence }
            Log.i(TAG, "Top confidence: $topConf, Class: ${results.maxByOrNull { it.confidence }?.className}")
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
        val intValues = IntArray(newWidth * newHeight)
        resizedBitmap.getPixels(intValues, 0, newWidth, 0, 0, newWidth, newHeight)
        
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
                    val cx = output[i][0]
                    val cy = output[i][1]
                    val w = output[i][2]
                    val h = output[i][3]

                    val rect = buildDetectionRect(cx, cy, w, h, padX, padY, scale, originalWidth, originalHeight)

                    val className = if (classId in CLASSES.indices) CLASSES[classId] else "obj_$classId"
                    results.add(DetectionResult(classId, className, maxClassConf, rect))
                }
                
                if (maxClassConf > highestFrameConf) {
                    highestFrameConf = maxClassConf
                }
            }
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

                if (maxClassConf > CONFIDENCE_THRESHOLD) {
                    val cx = output[0][i]
                    val cy = output[1][i]
                    val w = output[2][i]
                    val h = output[3][i]

                    // Log the first confident box's raw coordinates
                    if (results.isEmpty()) {
                        Log.i(TAG, "Raw Box: cx=$cx, cy=$cy, w=$w, h=$h (classId=$classId, conf=$maxClassConf)")
                    }

                    // Convert from Letterbox coords back to original image coords
                    // If coordinates are normalized (0..1), multiply them by INPUT_SIZE first!
                    // Let's dynamically handle normalized vs absolute
                    val rect = buildDetectionRect(cx, cy, w, h, padX, padY, scale, originalWidth, originalHeight)

                    val className = if (classId in CLASSES.indices) CLASSES[classId] else "obj_$classId"
                    results.add(DetectionResult(classId, className, maxClassConf, rect))
                }
            }
        }

        return applyNMS(results)
    }


    private fun buildDetectionRect(
        cx: Float, cy: Float, w: Float, h: Float,
        padX: Float, padY: Float, scale: Float,
        originalWidth: Int, originalHeight: Int
    ): RectF {
        val cxAbsolute = if (cx < 2.0f) cx * INPUT_SIZE else cx
        val cyAbsolute = if (cy < 2.0f) cy * INPUT_SIZE else cy
        val wAbsolute = if (w < 2.0f) w * INPUT_SIZE else w
        val hAbsolute = if (h < 2.0f) h * INPUT_SIZE else h

        val left = (cxAbsolute - wAbsolute / 2 - padX) / scale
        val top = (cyAbsolute - hAbsolute / 2 - padY) / scale
        val right = (cxAbsolute + wAbsolute / 2 - padX) / scale
        val bottom = (cyAbsolute + hAbsolute / 2 - padY) / scale

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
