# Technical Release Notes: YOLOv11 & ToF VL53L5CX Sensor Fusion (Phase 2)

**Date:** June 26, 2026  
**Status:** `RELEASED`  
**Scope:** Transitioning from static obstacle avoidance (Phase 1) to dynamic, object-based spatial mapping (Phase 2).

---

## 1. Problem Statement & Context
Previously, the VNetra system relied on a naive static obstacle monitoring pipeline (Phase 1). Obstacles were detected purely based on raw distance data from the center columns of the ToF sensor, triggering a generic "rintangan" (obstacle) Text-to-Speech (TTS) alert. Although the YOLOv11n object detector was running, its outputs were only rendered visually on the UI overlay and not integrated with the rangefinder pipeline.

To solve this, we implemented **Formula B (Centroid Bounding Box)** to map the semantic output of the computer vision model with the depth data of the ToF sensor. This enables the system to tell *what* the object is, *how far* it is, and *which direction* it lies in a single, unified pipeline.

---

## 2. Architecture & Implementation Details

All modifications were applied to [VNetra/app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt](app/src/main/java/com/airi/vnetra/ui/CameraStreamActivity.kt). The implementation details are outlined below:

### A. Cross-Thread Pipeline Sync
Since the camera frame processing (`frameCollectJob`) and the ToF data collection (`tofCollectJob`) run on different threads and variable sample rates (~15Hz camera vs ~10Hz ToF), we established a thread-safe bridge:
* Declared a `@Volatile private var latestDetections: List<DetectionResult>` state.
* The camera pipeline publishes the latest inference results into this state immediately after the YOLO model finishes processing.

### B. Refactoring the ToF Processing Loop (`tofCollectJob`)
Instead of reading static columns, the ToF thread now processes spatial coordinates dynamically:
1. **Centroid Extraction (Formula B):** Calculates the horizontal center of each detected object:
   $$x_c = \frac{x_{min} + x_{max}}{2}$$
2. **FoV Filtering (Guard Condition):** Checks if the object's centroid falls inside the ToF active zone. If it's outside (ToF has a narrower horizontal FoV compared to the camera), it gets ignored immediately to prevent false depth calculations.
3. **Auditory Clock Mapping (Formula C):** Converts the pixel centroid `x_c` into an auditory clock direction (e.g., 10, 11, 12, 1, 2 o'clock).
4. **Column Binning (Formula D):** Maps the camera-space coordinate `x_c` to the corresponding ToF sensor column ($j \in [0..7]$).
5. **Head-Tilt Compensation & Depth Extraction (Formula E):** Utilizes the head pitch angle ($\theta$) from the MPU6050 IMU to dynamically shift the ToF rows. This ensures the rangefinder is always looking forward relative to the horizon, not the ground, even when the user is looking down.
6. **TTS Dispatcher (Formula H):** Feeds the semantically labeled object (`className`), calculated distance, and clock direction into the TTS engine.

### C. Fallback Strategy (Failsafe)
If YOLO fails to detect any objects (due to poor lighting, motion blur, or model limitations), the system automatically falls back to Phase 1 monitoring. This prevents the system from going silent in front of unknown barriers:
```kotlin
if (detections.isNotEmpty()) {
    // Phase 2: Dynamic Semantics + Distance + Direction
} else {
    // Phase 1 (Fallback): Static center ToF columns monitoring
    // Alerts the user of a generic "rintangan" at 12 o'clock
}
```

---

## 3. Engineering Fixes & Optimization

### ✅ [FIXED] MPU6050 Inverted Hardware Correction
* **Issue:** The MPU6050 sensor was physically mounted upside down on the glasses (components facing downwards). This inverted the Z-axis vector, causing the EKF (Extended Kalman Filter) and Formula E pitch compensation to calculate offsets in the wrong direction.
* **Resolution:** Instead of rebuilding the hardware, we refactored the firmware in [VNetra/firmware-vnetra/firmware-vnetra/firmware-vnetra.ino](firmware-vnetra/firmware-vnetra/firmware-vnetra.ino). We introduced an `MPU_MOUNTING_INVERTED` flag and wrapped the sensor reads in a custom `getMpuEvent()` function. When activated, the firmware mathematically negates the Z-axis and X-axis (maintaining a Right-Handed coordinate system) before feeding the values into the EKF, resolving the issue transparently.

### ✅ [FIXED] Resolution Resiliency (VGA vs. QVGA scaling)
* **Issue:** The ESP32 firmware falls back to `FRAMESIZE_QVGA` (320x240) if PSRAM is disabled or missing (standard is `FRAMESIZE_VGA` 640x480). Because `FormulaUtils.kt` hardcodes mapping coordinates assuming a 640px camera width, a 320px frame would shift the right half of the screen into a permanent dead zone.
* **Resolution:** Added a volatile `latestFrameWidth` tracker in the Android app. The raw centroid `xcRaw` is now scaled dynamically to the virtual 640px space before running mapping formulas:
  $$x_c = x_{c\_raw} \times \frac{640}{W_{frame}}$$
  This decouples the fusion algorithm from the camera hardware output resolution.

### ✅ [OPTIMIZED] TTS Natural Distance & Speed Tuning
* **Distance Conversion:** Natively, the VL53L5CX measures depth in millimeters (mm). Hearing *"seribu seratus milimeter"* (1100 mm) is counterintuitive and slow to digest. The pipeline now converts values to centimeters by dividing the raw depth by 10 (`dObj / 10`), announcing a cleaner *"110 sentimeter"*.
* **Speed Acceleration:** Increased the TTS speech rate from `1.05f` to **`1.3f`** in `TtsAlertManager.kt`. This 30% speedup makes the voice notifications noticeably more prompt and responsive during movement.

### ✅ [REFACTOR] Functional Renaming (Removing "Formula" Confusion)
* **Issue:** The generic "Formula" naming convention (`FormulaUtils`, `FormulaE`, `FormulaH`) introduced cognitive overhead and made it hard to grasp the underlying functional purpose of each helper file.
* **Resolution:** Reorganized the file structure and internal class/object names to be semantic and self-documenting:
  * `FormulaUtils.kt` $\rightarrow$ [VNetra/app/src/main/java/com/airi/vnetra/util/SpatialMappingUtils.kt](app/src/main/java/com/airi/vnetra/util/SpatialMappingUtils.kt) (Centroid & clock/column mapping)
  * `FormulaE.kt` $\rightarrow$ [VNetra/app/src/main/java/com/airi/vnetra/util/TofDepthEstimator.kt](app/src/main/java/com/airi/vnetra/util/TofDepthEstimator.kt) (Row depth averaging with pitch offset)
  * `FormulaH.kt` $\rightarrow$ [VNetra/app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt](app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt) (One-shot alert flags & TTS engine wrapper)
  All imports and calls inside `CameraStreamActivity.kt` were refactored accordingly, and the project build compiles successfully.

---

## 4. Next Action Items
- [ ] Conduct field tests with fast-moving targets to evaluate latency sync between YOLO detections and TTS output.
- [ ] Calibrate the threshold value ($D_{W0}$) for TTS triggers under different walking speeds.
