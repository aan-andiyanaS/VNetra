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
    val boundingBox: RectF
)

enum class ModelStatus {
    NONE, FP16, INT8, FULL
}

enum class DelegateMode {
    AUTO, NPU, GPU, CPU
}

enum class ModelPreference {
    AUTO, FP16, INT8
}

class YoloDetector(
    private val context: Context, 
    private val delegateMode: DelegateMode = DelegateMode.AUTO,
    private val modelPreference: ModelPreference = ModelPreference.AUTO
) {
    companion object {
        private const val TAG = "YoloDetector"
        private const val MODEL_FP16 = "best_fp16.tflite"
        private const val MODEL_INT8 = "best_int8.tflite"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 23
        private const val OUTPUT_BOXES = 8400
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f

        val CLASSES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck", "train", 
            "bench", "pothole", "open_drain", "puddle", "pole", 
            "hanging_branch", "tactile_paving_straight", "tactile_paving_turn", 
            "tactile_paving_3way", "tactile_paving_4way", "tactile_paving_stop", 
            "stairs_up", "stairs_down", "crosswalk", "tree", "fence"
        )
    }

    var modelStatus: ModelStatus = ModelStatus.NONE
        private set

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // Output tensor shape: [1, 34, 8400] (30 classes + 4 bbox coords)
    // For INT8, it might be quantized, but usually TFLite converts it back to float if we don't use quantized inputs
    // Wait, we need to check if the export is fully integer or float input/output. YOLO export usually keeps float I/O even for int8.
    private val outputBuffer = Array(1) { Array(4 + NUM_CLASSES) { FloatArray(OUTPUT_BOXES) } }
    private val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    init {
        setupModel()
    }

    private fun setupModel() {
        val hasFp16 = hasAsset(MODEL_FP16)
        val hasInt8 = hasAsset(MODEL_INT8)

        if (hasFp16 && hasInt8) modelStatus = ModelStatus.FULL
        else if (hasFp16) modelStatus = ModelStatus.FP16
        else if (hasInt8) modelStatus = ModelStatus.INT8
        else modelStatus = ModelStatus.NONE

        if (modelStatus == ModelStatus.NONE) {
            Log.w(TAG, "No YOLO model found. Operating in INFERENCE_BYPASS_MODE.")
            return
        }

        try {
            val options = Interpreter.Options()

            val supportNpu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
            val supportGpu = CompatibilityList().isDelegateSupportedOnThisDevice

            var targetDelegate = delegateMode
            
            // 1. Logika awal mode AUTO untuk Hardware
            if (targetDelegate == DelegateMode.AUTO) {
                // AUTO: Prioritaskan NPU(INT8) > GPU(FP16) > CPU(INT8/FP16)
                targetDelegate = if (supportNpu && hasInt8) DelegateMode.NPU
                else if (supportGpu && hasFp16) DelegateMode.GPU
                else DelegateMode.CPU
            }

            // 2. Pemilihan Model (Mempertimbangkan preferensi manual pengguna)
            val finalModelName = if (modelStatus == ModelStatus.FULL) {
                if (modelPreference == ModelPreference.FP16) {
                    Log.i(TAG, "Kondisi FULL Model: Pengguna memaksa pilihan FP16.")
                    MODEL_FP16
                } else if (modelPreference == ModelPreference.INT8) {
                    Log.i(TAG, "Kondisi FULL Model: Pengguna memaksa pilihan INT8.")
                    MODEL_INT8
                } else {
                    // Jika preferensi model AUTO, pilih berdasarkan hardware
                    if (targetDelegate == DelegateMode.NPU || targetDelegate == DelegateMode.CPU) {
                        Log.i(TAG, "Kondisi FULL Model: Mengutamakan INT8 karena eksekutor adalah $targetDelegate.")
                        MODEL_INT8
                    } else {
                        Log.i(TAG, "Kondisi FULL Model: Mengutamakan FP16 karena eksekutor adalah GPU.")
                        MODEL_FP16
                    }
                }
            } else {
                if (hasFp16) MODEL_FP16 else MODEL_INT8
            }

            // 3. Evaluasi akhir Hardware vs Model yang terpilih
            if (targetDelegate == DelegateMode.GPU && finalModelName == MODEL_INT8) {
                Log.w(TAG, "GPU tidak bisa dipilih untuk model INT8. Fallback ke NPU/CPU.")
                targetDelegate = if (supportNpu) DelegateMode.NPU else DelegateMode.CPU
            }

            if (targetDelegate == DelegateMode.NPU && !supportNpu) {
                Log.w(TAG, "NPU dipilih tetapi API < 27. Fallback ke GPU/CPU.")
                targetDelegate = if (supportGpu && finalModelName == MODEL_FP16) DelegateMode.GPU else DelegateMode.CPU
            }
            
            if (targetDelegate == DelegateMode.GPU && !supportGpu) {
                Log.w(TAG, "GPU dipilih tetapi tidak didukung perangkat ini. Fallback ke CPU.")
                targetDelegate = DelegateMode.CPU
            }

            // 4. Menerapkan opsi TFLite sesuai target delegate akhir
            when (targetDelegate) {
                DelegateMode.NPU -> {
                    options.setUseNNAPI(true)
                    Log.i(TAG, "Menggunakan NPU (NNAPI) Delegate dengan model $finalModelName.")
                }
                DelegateMode.GPU -> {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                    Log.i(TAG, "Menggunakan GPU Delegate dengan model $finalModelName.")
                }
                DelegateMode.CPU, DelegateMode.AUTO -> {
                    options.setNumThreads(4)
                    Log.i(TAG, "Menggunakan CPU Delegate (4 threads) dengan model $finalModelName.")
                }
            }

            val modelBuffer = loadModelFile(finalModelName)
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "Loaded model $finalModelName successfully.")

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing TFLite Interpreter: ${e.message}")
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
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        convertBitmapToByteBuffer(resizedBitmap)

        // 2. Inference
        try {
            interpreter?.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            return emptyList()
        }

        // 3. Postprocess (Extract Boxes & NMS)
        return postprocessBoxes(bitmap.width, bitmap.height)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val valPixel = intValues[pixel++]
                // Normalize 0..255 to 0.0..1.0
                inputBuffer.putFloat(((valPixel shr 16) and 0xFF) / 255.0f)
                inputBuffer.putFloat(((valPixel shr 8) and 0xFF) / 255.0f)
                inputBuffer.putFloat((valPixel and 0xFF) / 255.0f)
            }
        }
    }

    private fun postprocessBoxes(originalWidth: Int, originalHeight: Int): List<DetectionResult> {
        val results = mutableListOf<DetectionResult>()
        val scaleX = originalWidth.toFloat() / INPUT_SIZE
        val scaleY = originalHeight.toFloat() / INPUT_SIZE

        // outputBuffer is [1][27][8400]
        val output = outputBuffer[0]
        
        for (i in 0 until OUTPUT_BOXES) {
            var maxClassConf = 0f
            var classId = -1

            // Output format YOLOv8/11: 
            // indices 0..3: cx, cy, w, h
            // indices 4..26: class confidences
            for (c in 0 until NUM_CLASSES) {
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

                // Scale to original image
                val left = (cx - w / 2) * scaleX
                val top = (cy - h / 2) * scaleY
                val right = (cx + w / 2) * scaleX
                val bottom = (cy + h / 2) * scaleY

                val rect = RectF(
                    left.coerceAtLeast(0f),
                    top.coerceAtLeast(0f),
                    right.coerceAtMost(originalWidth.toFloat()),
                    bottom.coerceAtMost(originalHeight.toFloat())
                )

                results.add(DetectionResult(classId, CLASSES[classId], maxClassConf, rect))
            }
        }

        return applyNMS(results)
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
            Math.max(box1.left, box2.left),
            Math.max(box1.top, box2.top),
            Math.min(box1.right, box2.right),
            Math.min(box1.bottom, box2.bottom)
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
