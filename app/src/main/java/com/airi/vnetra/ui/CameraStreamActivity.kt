package com.airi.vnetra.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import com.airi.vnetra.databinding.ActivityCameraStreamBinding
import com.airi.vnetra.service.CameraStreamService
import com.airi.vnetra.util.TofDepthEstimator
import com.airi.vnetra.util.TtsAlertManager
import com.airi.vnetra.util.SpatialMappingUtils
import com.airi.vnetra.util.SessionManager
import com.airi.vnetra.util.TerrainDetector
import com.airi.vnetra.util.CameraDepthEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.airi.vnetra.model.YoloDetector
import com.airi.vnetra.model.DetectionResult
import com.airi.vnetra.model.ModelStatus
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * CameraStreamActivity v5 — Simplified
 *
 * Flow:
 *  - onStart(): bind ke service (service sudah berjalan dari onCreate pertama)
 *  - Tombol "Akhiri": stop service + tutup SEMUA activity (finishAffinity)
 *    IP tetap tersimpan → buka app lagi langsung ke kamera
 *  - Back button: moveTaskToBack (app minimize, service tetap jalan)
 *  - Saat ESP32 mati: service auto-reconnect (exponential backoff)
 *  - Saat ESP32 nyala lagi: service reconnect otomatis, kamera hidup kembali
 */
class CameraStreamActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IP = "esp32_ip"

        fun createIntent(context: Context, ipAddress: String): Intent =
            Intent(context, CameraStreamActivity::class.java).apply {
                putExtra(EXTRA_IP, ipAddress)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }

    private lateinit var binding:        ActivityCameraStreamBinding
    private lateinit var sessionManager: SessionManager

    private var streamService:   CameraStreamService? = null
    private var isBound          = false
    private var frameCollectJob: Job? = null
    private var stateCollectJob: Job? = null
    private var imuCollectJob:   Job? = null
    private var tofCollectJob:   Job? = null
    private var ipAddress:       String = ""

    private var currentTopInset = 0
    private var currentBottomInset = 0

    private lateinit var tofViews: Array<android.widget.TextView>

    // Variabel untuk menyimpan data ToF yang di-smooth (Exponential Moving Average)
    private var smoothedTofData: FloatArray? = null

    // Pre-alokasi untuk mengurangi GC pressure:
    // floatArrayOf() di dalam loop ToF (64 cell × 10Hz = 640 alokasi/detik) menyebabkan GC pause.
    // Gunakan array yang sama dan update nilainya.
    private val hsvTemp = floatArrayOf(0f, 1f, 1f)

    // Warna cell tidak valid (semi-transparan hitam = #60000000).
    // Pre-compute sekali untuk menghindari Color.parseColor() di setiap cell setiap frame
    // (hingga 640 string parse/detik setelah sentinel filter → lebih banyak cell invalid).
    private val colorInvalidCell = android.graphics.Color.argb(96, 0, 0, 0)

    // Mode ToF aktif: 4 atau 8 (4x4 atau 8x8)
    // Di-load dari SharedPreferences agar persisten antar sesi
    private var currentTofMode: Int = 8

    // ── State sensor terbaru — diakses oleh Formula E, G, J (P1.5) ────────
    // @Volatile: ditulis dari Dispatchers.Default, dibaca dari coroutine lain.
    // Tidak perlu synchronized karena assignment reference bersifat atomic di JVM.
    @Volatile private var latestImuData: FloatArray? = null  // 9 field: [θ,φ,ωx,ωy,ωz,a,ts,vBase,conv]
    @Volatile private var latestTofData: IntArray?   = null  // 16 atau 64 nilai (mm), -1 = invalid

    // ── Latency (Ping) Monitor State (L1.2) ───────────────────────────
    @Volatile private var pingCamera:     Long = 0
    @Volatile private var pingTofSmooth:  Long = 0
    @Volatile private var pingFormulaEH:  Long = 0
    @Volatile private var pingTerrain:    Long = 0
    @Volatile private var pingTotalTof:   Long = 0
    @Volatile private var pingWebsocket:  Long = -1L

    private var latencyMonitorJob: Job? = null
    private var pingWebsocketJob: Job? = null

    // ── TTS Alert Manager (P3.3) ────────────────
    private lateinit var ttsAlertManager: TtsAlertManager

    // ── Formula J — Terrain Detector (P6) ───────────────────────
    private val terrainDetector = TerrainDetector()
    private var lastTerrainAlertTime  = 0L
    @Volatile private var isBlockedState = false
    // Cooldown: cegah terrain alert flood (min. 3 detik antar peringatan, kecuali HIGH yang selalu langsung)
    private val TERRAIN_ALERT_COOLDOWN_MS = 3000L

    // Temporal holdover: tahan nilai terakhir yang valid selama N frame sebelum tampil "—".
    // Ini mencegah cell terluar (yang memiliki SNR lebih rendah) flicker antara angka dan "—"
    // karena status sensor (9/255) kadang muncul selang-seling antar frame.
    // Nilai 5 frame @ 10Hz = 0.5 detik toleransi sebelum cell dianggap benar-benar kosong.
    private val HOLDOVER_FRAMES = 5
    private var holdoverCount: IntArray? = null  // countdown per cell; -1 = sudah ditampilkan "—"

    // FPS counter
    private var frameCount     = 0
    private var fpsWindowStart = 0L

    // Swipe gesture untuk badge koneksi
    private var badgeSwipeRevealed = false

    // Guard: cegah double-execute akhiriProses
    private var isAkhiring = false

    // AI Detector
    private var yoloDetector: YoloDetector? = null
    @Volatile private var latestDetections: List<DetectionResult> = emptyList()
    @Volatile private var latestFrameWidth: Int = 640
    @Volatile private var latestFrameHeight: Int = 480
    private var isInferencing = false

    private val exitReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CameraStreamService.ACTION_EXIT_APP) {
                android.util.Log.d("CameraStreamActivity", "Received exit broadcast from service, closing app")
                finishAffinity()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (isDestroyed || isFinishing) return
            val binder = service as? CameraStreamService.LocalBinder ?: return
            streamService = binder.getService()
            isBound       = true
            // startCollectingFrames dan startCollectingSensors tidak dipanggil di sini.
            // Mereka akan dipanggil oleh startObservingConnectionState() saat state
            // berubah ke CONNECTED, termasuk saat reconnect setelah ESP32 restart.
            startObservingConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Service crash/killed — bukan stop normal
            streamService = null
            isBound       = false
            runOnUiThread {
                if (!isDestroyed && !isFinishing && !isAkhiring) {
                    showStreamStateSafe(StreamState.ERROR("Koneksi service terputus. Tekan Reconnect."))
                    hideBadgeSafe()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge layout setup
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set support action bar with the custom toolbar
        setSupportActionBar(binding.toolbar)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ipAddress = intent.getStringExtra(EXTRA_IP) ?: run {
            Toast.makeText(this, "IP address tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sessionManager = SessionManager(this)

        supportActionBar?.title = "Live Camera — $ipAddress"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Apply dynamic safe area margins and padding using Window Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            currentTopInset = systemBars.top
            currentBottomInset = systemBars.bottom
            
            // Set padding to toolbar so it draws behind status bar, but text starts below
            binding.toolbar.setPadding(0, systemBars.top, 0, 0)
            
            // Update upper views (IMU panel & connection badge) margins
            updateUpperViewsMargins()
            
            // Adjust bottom control panel padding to stay above system navigation bar
            binding.layoutControls.setPadding(
                binding.layoutControls.paddingLeft,
                binding.layoutControls.paddingTop,
                binding.layoutControls.paddingRight,
                systemBars.bottom + 16.dpToPx()
            )
            insets
        }

        setupBadgeSwipeGesture()
        setupClickListeners()
        // Load mode tersimpan sebelum init grid
        currentTofMode = loadTofMode()
        initTofGrid()
        updateTofModeButtons(currentTofMode)
        showStreamStateSafe(StreamState.CONNECTING)

        requestNotificationPermission()
        requestBatteryOptimizationBypass()

        // P3.3: Inisialisasi TtsAlertManager + TTS Engine
        // Dipanggil di onCreate agar TTS punya cukup waktu 
        ttsAlertManager = TtsAlertManager(this)
        ttsAlertManager.initTts()

        // Init YOLO Detector (Secara default akan mencoba GPU/NPU karena masalah library sudah diperbaiki)
        yoloDetector = YoloDetector(this)
        updateAiIndicator()
    }

    override fun onStart() {
        super.onStart()
        // Daftarkan receiver untuk keluar aplikasi
        val filter = android.content.IntentFilter(CameraStreamService.ACTION_EXIT_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }

        if (ipAddress.isEmpty()) return
        val serviceIntent = CameraStreamService.createStartIntent(this, ipAddress)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(exitReceiver) }
        cancelAllJobs()
        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound       = false
            streamService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // P3.3: Bebaskan resource TTS — mencegah leak AudioTrack di background
        if (::ttsAlertManager.isInitialized) ttsAlertManager.shutdown()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        yoloDetector?.close()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newIp = intent?.getStringExtra(EXTRA_IP)
        if (!newIp.isNullOrEmpty() && newIp != ipAddress) {
            ipAddress = newIp
            supportActionBar?.title = "Live Camera — $ipAddress"
            val si = CameraStreamService.createStartIntent(this, ipAddress)
            stopService(si); startService(si)
        }
    }

    // Back → minimize, service tetap jalan
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onSupportNavigateUp(): Boolean { moveTaskToBack(true); return true }

    // ──────────────────────────────────────────────────────────────────────────
    // Runtime permissions
    // ──────────────────────────────────────────────────────────────────────────

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* tidak masalah jika ditolak, streaming tetap jalan */ }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Click listeners
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Tombol Reconnect (muncul saat error)
        binding.btnReconnect.setOnClickListener {
            if (isDestroyed || isFinishing) return@setOnClickListener
            showStreamStateSafe(StreamState.CONNECTING)
            hideBadgeSafe()
            val si = CameraStreamService.createStartIntent(this, ipAddress)
            stopService(si)
            startService(si)
            if (!isBound) bindService(si, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Tombol Akhiri di panel bawah
        binding.btnAkhiri.setOnClickListener {
            if (!isDestroyed && !isFinishing) konfirmasiAkhiriProses()
        }

        // Tombol Akhiri yang muncul saat badge di-swipe
        binding.btnAkhiriBadge.setOnClickListener {
            if (!isDestroyed && !isFinishing) konfirmasiAkhiriProses()
        }

        binding.ivCameraFrame.setOnClickListener { toggleFullscreen() }

        // ── Tombol mode ToF ──────────────────────────────────────────────────
        binding.btnTof8x8.setOnClickListener {
            if (currentTofMode == 8) return@setOnClickListener
            switchTofMode(8)
        }
        binding.btnTof4x4.setOnClickListener {
            if (currentTofMode == 4) return@setOnClickListener
            switchTofMode(4)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Swipe gesture badge
    // ──────────────────────────────────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun setupBadgeSwipeGesture() {
        val detector = GestureDetectorCompat(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val diffX = e2.x - (e1?.x ?: e2.x)
                    return if (Math.abs(diffX) > 80f && Math.abs(velocityX) > 100f) {
                        badgeSwipeRevealed = !badgeSwipeRevealed
                        if (!isDestroyed && !isFinishing) {
                            binding.btnAkhiriBadge.visibility =
                                if (badgeSwipeRevealed) View.VISIBLE else View.GONE
                            binding.tvConnectedBadge.text =
                                if (badgeSwipeRevealed) "● Terhubung  ✕ tutup"
                                else "● Menerima data dari ESP32-S3  ‹ geser"
                        }
                        true
                    } else false
                }

                override fun onDown(e: MotionEvent): Boolean = true
            }
        )

        binding.badgeSwipeContainer.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        binding.tvConnectedBadge.setOnTouchListener  { _, event -> detector.onTouchEvent(event) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Observer connectionState dari service
    // ──────────────────────────────────────────────────────────────────────────

    private fun startObservingConnectionState() {
        stateCollectJob?.cancel()
        stateCollectJob = lifecycleScope.launch {
            streamService?.connectionState?.collect { state ->
                if (isDestroyed || isFinishing || isAkhiring) return@collect
                when (state) {
                    CameraStreamService.ConnectionState.CONNECTED -> {
                        showBadgeSafe()
                        showStreamStateSafe(StreamState.STREAMING)
                        startCollectingFrames()
                        startCollectingSensors()
                        // Resend mode command agar firmware ikut mode yang dipilih user
                        // (ESP32 selalu default 8x8 saat boot, jadi perlu dikirim ulang)
                        if (currentTofMode != 8) {
                            streamService?.sendTofModeCommand(currentTofMode)
                            android.util.Log.d("CameraStreamActivity", "Resent TOF mode ${currentTofMode}x${currentTofMode} after reconnect")
                        }
                    }
                    CameraStreamService.ConnectionState.CONNECTING -> {
                        hideBadgeSafe()
                        showStreamStateSafe(StreamState.CONNECTING)
                        clearStaleSensorDisplay()
                    }
                    CameraStreamService.ConnectionState.DISCONNECTED -> {
                        hideBadgeSafe()
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Frame collection
    // ──────────────────────────────────────────────────────────────────────────

    private fun startCollectingFrames() {
        // ── Capture referensi di main thread sebelum pindah ke Dispatchers.Default ──
        val svc = streamService ?: return

        frameCollectJob?.cancel()
        frameCount     = 0
        fpsWindowStart = System.currentTimeMillis()

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inMutable         = true
        }

        frameCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                svc.frameFlow.collect { jpegBytes ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect
                    
                    val startTime = System.currentTimeMillis()
                    val bitmap = runCatching {
                        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                    }.getOrNull()
                    pingCamera = System.currentTimeMillis() - startTime

                    if (bitmap == null) return@collect
                    latestFrameWidth = bitmap.width
                    latestFrameHeight = bitmap.height

                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring) {
                            binding.ivCameraFrame.setImageBitmap(bitmap)
                            updateFpsCounter(jpegBytes.size)

                            // AI Inference
                            if (!isInferencing && yoloDetector?.modelStatus != ModelStatus.NONE) {
                                isInferencing = true
                                val detector = yoloDetector
                                if (detector != null) {
                                    lifecycleScope.launch(Dispatchers.Default) {
                                        try {
                                            // 1. Lakukan proses deteksi di background
                                            val results = detector.detect(bitmap)
                                            latestDetections = results
                                            triggerInstantYoloTts(results)
                                            
                                            // 2. Jika berhasil, update UI di Main Thread
                                            withContext(Dispatchers.Main) {
                                                if (!isDestroyed && !isFinishing && !isAkhiring) {
                                                    binding.boundingBoxOverlay.setResults(results, bitmap.width.toFloat(), bitmap.height.toFloat())
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Tangkap error jika terjadi agar tidak membatalkan seluruh coroutine parent
                                            // jika bukan CancellationException
                                            if (e !is kotlinx.coroutines.CancellationException) {
                                                android.util.Log.e("CameraStreamActivity", "Error during AI inference", e)
                                            } else {
                                                throw e
                                            }
                                        } finally {
                                            // 3. Pastikan flag selalu direset apapun yang terjadi
                                            // Gunakan NonCancellable agar flag tetap di-reset meskipun parent job di-cancel
                                            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                                                isInferencing = false
                                            }
                                        }
                                    }
                                } else {
                                    isInferencing = false
                                }
                            }
                        }
                    }
                }
                // SharedFlow tidak pernah complete secara normal.
                // Jika collect() keluar, berarti coroutine di-cancel — tidak perlu error UI.
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // re-throw: biarkan sistem menangani cancellation, JANGAN tampilkan error
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing && !isAkhiring)
                        showStreamStateSafe(StreamState.ERROR("Error stream: ${e.message}"))
                }
            }
        }
    }

    private fun startCollectingSensors() {
        val svc = streamService ?: return  // capture di main thread

        imuCollectJob?.cancel()
        tofCollectJob?.cancel()
        latencyMonitorJob?.cancel()
        pingWebsocketJob?.cancel()

        // Reset state latency
        pingCamera = 0
        pingTofSmooth = 0
        pingFormulaEH = 0
        pingTerrain = 0
        pingTotalTof = 0
        pingWebsocket = -1L

        // Start latency monitor polling job (5Hz = 200ms)
        latencyMonitorJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(200)
                updateLatencyMonitorUi()
            }
        }

        pingWebsocketJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                svc.pingWebsocketFlow.collect { ping ->
                    pingWebsocket = ping
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CameraStreamActivity", "Ping WS collect error", e)
            }
        }

        imuCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                svc.imuFlow.collect { imuData ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect
                    // P1.6: Simpan state IMU terbaru untuk Formula E, G, J
                    latestImuData = imuData
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring && imuData.size >= 6) {
                            binding.tvImuPitch.text = "Pitch: %.1f°".format(imuData[0])
                            binding.tvImuRoll.text  = "Roll: %.1f°".format(imuData[1])
                            // Tampilkan status EKF: "warming up" selama 5 detik pertama
                            val converged = imuData.getOrElse(8) { 0f } > 0.5f
                            if (converged) {
                                binding.tvImuAccel.text = "Accel: %.2f m/s²".format(imuData[5])
                            } else {
                                binding.tvImuAccel.text = "EKF: warming up..."
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // re-throw cancellation
            } catch (e: Exception) {
                android.util.Log.e("CameraStreamActivity", "IMU collect error", e)
            }
        }

        tofCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                svc.tofFlow.collect { tofData ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect
                    latestTofData = tofData

                    // Fase 1: Smoothing (EMA)
                    val startSmooth = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring
                            && ::tofViews.isInitialized) {

                            if (tofData.size != tofViews.size) {
                                val detectedMode = if (tofData.size == 16) 4 else 8
                                if (currentTofMode != detectedMode) {
                                    currentTofMode = detectedMode
                                    saveTofMode(detectedMode)
                                    rebuildTofGrid(detectedMode)
                                    updateTofModeButtons(detectedMode)
                                }
                                smoothedTofData = null // Reset smoothing array jika resolusi berubah
                                return@withContext
                            }

                            if (smoothedTofData == null || smoothedTofData!!.size != tofData.size) {
                                smoothedTofData = FloatArray(tofData.size) { i -> tofData[i].toFloat() }
                                holdoverCount   = null  // Inisialisasi ulang saat ukuran berubah
                            }

                            if (holdoverCount == null || holdoverCount!!.size != tofData.size) {
                                holdoverCount = IntArray(tofData.size) { HOLDOVER_FRAMES }
                            }

                            val alpha = 0.3f // Faktor smoothing EMA

                            for (i in tofData.indices) {
                                val rawDistance = tofData[i]

                                if (rawDistance <= 0) {
                                    val remaining = holdoverCount!![i]
                                    if (remaining > 0) {
                                        holdoverCount!![i] = remaining - 1
                                        val held = smoothedTofData!![i].toInt()
                                        if (held > 0) {
                                            tofViews[i].text = "$held"
                                            tofViews[i].setBackgroundColor(
                                                getColorForDistance(held, dimmed = true)
                                            )
                                        }
                                    } else {
                                        tofViews[i].text = "—"
                                        tofViews[i].setBackgroundColor(colorInvalidCell)
                                        smoothedTofData!![i] = 0f
                                    }
                                } else {
                                    holdoverCount!![i] = HOLDOVER_FRAMES

                                    if (smoothedTofData!![i] <= 0f) {
                                        smoothedTofData!![i] = rawDistance.toFloat()
                                    } else {
                                        smoothedTofData!![i] = alpha * rawDistance + (1.0f - alpha) * smoothedTofData!![i]
                                    }

                                    val smoothedDistance = smoothedTofData!![i].toInt()
                                    tofViews[i].text = "$smoothedDistance"
                                    tofViews[i].setBackgroundColor(getColorForDistance(smoothedDistance))
                                }
                            }
                        }
                    }   // end withContext(Dispatchers.Main)
                    pingTofSmooth = System.currentTimeMillis() - startSmooth

                    val imuSnap  = latestImuData
                    val rawTheta = imuSnap?.getOrElse(0) { 0f } ?: 0f
                    val thetaDeg = rawTheta - 20f

                    val startFormula = System.currentTimeMillis()
                    var closeThreatExists = false
                    var allClear = true

                    if (::ttsAlertManager.isInitialized) {
                        var hasCloseYoloThreat = false
                        val detections = latestDetections
                        if (detections.isNotEmpty()) {
                            for (det in detections) {
                                // Centroid Bounding Box (Raw)
                                val xcRaw = SpatialMappingUtils.centroidX(det.boundingBox.left, det.boundingBox.right)
                                
                                // Normalisasikan xc ke koordinat virtual 640px (W_CAM) agar kompatibel dengan konstanta fisik
                                val xc = xcRaw * (SpatialMappingUtils.W_CAM.toFloat() / latestFrameWidth.toFloat())
                                
                                // Abaikan jika objek di luar jangkauan horizontal sensor ToF
                                if (!SpatialMappingUtils.isInTofZone(xc)) continue
                                
                                // Pemetaan ke Kolom ToF
                                val j = SpatialMappingUtils.mapToTofColumn(xc, currentTofMode)
                                
                                // Kalkulasi Jarak Geometri
                                var dObj = TofDepthEstimator.calculate(
                                    tofData    = tofData,
                                    j          = j,
                                    thetaDeg   = thetaDeg,
                                    resolution = currentTofMode
                                )
                                
                                // Jika ToF gagal membaca jarak (D_MAX), gunakan estimasi kamera monokuler sebagai cadangan
                                if (dObj >= TofDepthEstimator.D_MAX) {
                                    dObj = CameraDepthEstimator.estimateDistance(
                                        className   = det.className,
                                        boundingBox = det.boundingBox,
                                        imageHeight = latestFrameHeight,
                                        thetaDeg    = thetaDeg
                                    )
                                }
                                
                                // Catatan: ttsAlertManager.process untuk YOLO kini ditangani penuh secara instan
                                // oleh triggerInstantYoloTts. Di sini kita hanya mengupdate state deteksi ancaman.

                                if (dObj < TtsAlertManager.D_W0) {
                                    hasCloseYoloThreat = true
                                    closeThreatExists  = true
                                }
                                if (dObj < TtsAlertManager.D_RESET) {
                                    allClear = false
                                }
                            }
                        }

                        // Jika tidak ada deteksi YOLO yang berada di dekat (< D_W0),
                        // cek apakah ToF mendeteksi tembok di depannya.
                        val wallDetected = SpatialMappingUtils.isWall(tofData, currentTofMode)
                        if (!hasCloseYoloThreat && wallDetected) {
                            val wallDistance = tofData.filter { it in 30..1500 }.average().toInt()
                            val wallAlert = ttsAlertManager.process(
                                trackingId     = SpatialMappingUtils.WALL_TRACKING_ID,
                                dObj           = wallDistance,
                                clockDirection = 12,    // tembok selalu didepan
                                objectLabel    = "tembok"
                            )
                            if (wallAlert != null) {
                                ttsAlertManager.speak(wallAlert)
                            }

                            if (wallDistance < TtsAlertManager.D_W0) {
                                closeThreatExists = true
                            }
                            if (wallDistance < TtsAlertManager.D_RESET) {
                                allClear = false
                            }
                        } else {
                            // Jika tidak ada tembok terdeteksi (atau tertutup objek YOLO dekat), 
                            // panggil process dengan jarak aman agar flag tembok di-reset
                            ttsAlertManager.process(
                                trackingId     = SpatialMappingUtils.WALL_TRACKING_ID,
                                dObj           = 2000, // jarak aman > D_RESET
                                clockDirection = 12,
                                objectLabel    = "tembok"
                            )
                        }

                        // =========================================================
                        // Logika Peringatan Smart Navigation TTS (Jalan Kosong / Tembok)
                        // =========================================================
                        val yawRate = latestImuData?.getOrElse(4) { 0f } ?: 0f
                        val aLinMag = latestImuData?.getOrElse(5) { 0f } ?: 0f
                        
                        val isTurning = Math.abs(yawRate) > 30f // deg/s
                        val isMovingForward = aLinMag > 0.3f    // m/s^2 (terdeteksi ada langkah/guncangan maju)
                        
                        // Bahaya jika ada tembok yang mendekat (closeThreatExists)
                        // Atau jika seluruh ToF mendeteksi halangan < D_RESET (allClear == false)
                        // allClear = false berarti ada sesuatu di jarak < 1150 mm
                        val isDanger = closeThreatExists || !allClear

                        if (::ttsAlertManager.isInitialized) {
                            ttsAlertManager.smartNavigation.processNavigationState(
                                isDanger = isDanger,
                                isMovingForward = isMovingForward,
                                isTurning = isTurning
                            )
                        }
                        
                        // Update legacy state for other potential dependencies
                        if (closeThreatExists) {
                            isBlockedState = true
                        } else if (allClear && isBlockedState) {
                            isBlockedState = false
                        }
                    }
                    pingFormulaEH = System.currentTimeMillis() - startFormula

                    // Fase 3: TerrainDetector
                    val startTerrain = System.currentTimeMillis()
                    if (::ttsAlertManager.isInitialized) {
                        val expectedSize = currentTofMode * currentTofMode
                        if (tofData.size == expectedSize) {
                            val terrainResult = terrainDetector.process(
                                tofData   = tofData,
                                thetaDeg  = thetaDeg
                            )

                            if (terrainResult.type != TerrainDetector.TerrainType.SAFE && 
                                terrainResult.type != TerrainDetector.TerrainType.OPEN &&
                                terrainResult.confidence >= 0.55f) {
                                
                                val nowMs = System.currentTimeMillis()
                                val cooldown = (nowMs - lastTerrainAlertTime) > TERRAIN_ALERT_COOLDOWN_MS
                                val isHigh = terrainResult.confidence >= 0.70f
                                // Validasi: Prioritaskan YOLO. Jika YOLO mendeteksi rintangan dekat, bisukan peringatan Terrain.
                                var yoloValidated = false
                                
                                if (!hasCloseYoloThreat) {
                                    // Validasi YOLO khusus untuk Tangga (HOLE, CONTAMINATED bypass YOLO)
                                    val isStair = terrainResult.type == TerrainDetector.TerrainType.STAIR_DOWN || 
                                                  terrainResult.type == TerrainDetector.TerrainType.STAIR_UP
                                    yoloValidated = !isStair
                                    
                                    if (isStair) {
                                        val currentDetections = latestDetections
                                        yoloValidated = currentDetections.any { 
                                            it.className == "tangga naik" || it.className == "tangga turun" 
                                        }
                                    }
                                }

                                if (yoloValidated && (isHigh || cooldown)) {
                                    lastTerrainAlertTime = nowMs

                                    val hCm      = (terrainResult.hEst / 10).toInt()
                                    val dirText  = when (terrainResult.direction) {
                                        11 -> "kiri depan"
                                         1 -> "kanan depan"
                                        else -> "depan"
                                    }
                                    val typeText = when (terrainResult.type) {
                                        TerrainDetector.TerrainType.STAIR_DOWN -> "tangga turun"
                                        TerrainDetector.TerrainType.STAIR_UP   -> "tangga naik"
                                        TerrainDetector.TerrainType.HOLE       -> "lubang"
                                        TerrainDetector.TerrainType.CONTAMINATED -> "objek dekat"
                                        else -> ""
                                    }

                                    if (typeText.isNotEmpty()) {
                                        val msg = if (isHigh) {
                                            "Awas! $typeText, sekitar $hCm cm, $dirText!"
                                        } else {
                                            "Perhatian, $typeText, $hCm cm, $dirText"
                                        }

                                        if (isHigh) ttsAlertManager.speak(msg)
                                        else ttsAlertManager.speakAdd(msg)
                                    }
                                }
                            }
                        }
                    } // end if (ttsAlertManager.isInitialized)
                    pingTerrain = System.currentTimeMillis() - startTerrain

                    pingTotalTof = pingTofSmooth + pingFormulaEH + pingTerrain
                } // end collect { tofData ->
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // re-throw agar coroutine cancellation bisa propagate (jangan diswallow!)
            } catch (e: Exception) {
                android.util.Log.e("CameraStreamActivity", "TOF collect error", e)
            }
        }
    }

    private fun updateFpsCounter(frameBytes: Int) {
        if (isDestroyed || isFinishing) return
        frameCount++
        val now     = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            runCatching {
                binding.tvStreamStatus.text =
                    "%.1f FPS  •  %d KB/frame".format(frameCount * 1000f / elapsed, frameBytes / 1024)
            }
            frameCount     = 0
            fpsWindowStart = now
        }
    }

    /**
     * Memproses peringatan TTS untuk YOLO secara instan segera setelah inferensi AI selesai.
     * Hal ini memangkas delay ToF Loop (100ms) untuk objek YOLO.
     */
    private fun triggerInstantYoloTts(detections: List<DetectionResult>) {
        if (detections.isEmpty() || !::ttsAlertManager.isInitialized) return
        val tofData = latestTofData ?: return
        val imuSnap = latestImuData
        val rawTheta = imuSnap?.getOrElse(0) { 0f } ?: 0f
        val aLinMag = imuSnap?.getOrElse(5) { 0f } ?: 0f
        val isMovingForward = aLinMag > 0.3f
        val thetaDeg = rawTheta - 20f
        val frameWidth = latestFrameWidth

        // 1. Hitung dObj dan saring deteksi yang berada di zona aktif ToF
        val mappedDetections = detections.mapNotNull { det ->
            val xcRaw = SpatialMappingUtils.centroidX(det.boundingBox.left, det.boundingBox.right)
            val xc = xcRaw * (SpatialMappingUtils.W_CAM.toFloat() / frameWidth.toFloat())
            if (!SpatialMappingUtils.isInTofZone(xc)) null
            else {
                val arahJam = SpatialMappingUtils.mapToClockDirection(xc)
                val j = SpatialMappingUtils.mapToTofColumn(xc, currentTofMode)
                var dObj = TofDepthEstimator.calculate(
                    tofData    = tofData,
                    j          = j,
                    thetaDeg   = thetaDeg,
                    resolution = currentTofMode
                )
                // Jika ToF gagal membaca jarak (D_MAX), gunakan estimasi kamera monokuler
                if (dObj >= TofDepthEstimator.D_MAX) {
                    dObj = CameraDepthEstimator.estimateDistance(
                        className   = det.className,
                        boundingBox = det.boundingBox,
                        imageHeight = latestFrameHeight,
                        thetaDeg    = thetaDeg
                    )
                }
                det to Triple(dObj, arahJam, det.className)
            }
        }

        // 2. Kelompokkan per classId dan pilih hanya objek terdekat per kelas (mencegah spamming multi-instance)
        val closestDetections = mappedDetections
            .groupBy { it.first.classId }
            .mapValues { entry -> entry.value.minByOrNull { it.second.first }!! }

        val activeClasses = closestDetections.keys
        val newAlerts = mutableListOf<String>()

        // 3. Proses deteksi terdekat untuk one-shot alert
        for ((classId, detPair) in closestDetections) {
            val dObj = detPair.second.first
            val arahJam = detPair.second.second
            val label = detPair.second.third
            val alertMsg = ttsAlertManager.process(
                trackingId     = classId,
                dObj           = dObj,
                clockDirection = arahJam,
                objectLabel    = label,
                isMovingForward = isMovingForward
            )
            if (alertMsg != null) {
                newAlerts.add(alertMsg)
            }
        }

        // 4. Gabungkan suara jika ada lebih dari satu peringatan baru pada frame yang sama
        if (newAlerts.isNotEmpty()) {
            val combinedMsg = newAlerts.joinToString(", dan ")
            ttsAlertManager.speak(combinedMsg)
        }

        // 5. Bersihkan berkala flag untuk kelas yang tidak terdeteksi aktif
        ttsAlertManager.postProcessDetections(activeClasses)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Akhiri Proses
    // ──────────────────────────────────────────────────────────────────────────

    private fun konfirmasiAkhiriProses() {
        if (isDestroyed || isFinishing || isAkhiring) return
        AlertDialog.Builder(this)
            .setTitle("Akhiri Proses")
            .setMessage("Yakin ingin menghentikan streaming dan menutup aplikasi?\n\nSaat dibuka kembali, aplikasi akan otomatis terhubung ke ESP32-S3.")
            .setPositiveButton("Akhiri") { _, _ -> akhiriProses() }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Akhiri proses:
     * 1. Hentikan service (WebSocket, notifikasi)
     * 2. IP TETAP tersimpan → buka app lagi langsung ke kamera
     * 3. finishAffinity() → tutup SEMUA activity (keluar dari app)
     *
     * Saat app dibuka lagi → MainActivity cek IP → langsung ke CameraStreamActivity.
     */
    private fun akhiriProses() {
        if (isAkhiring) return
        isAkhiring = true

        cancelAllJobs()

        // Unbind dulu
        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound       = false
            streamService = null
        }

        // Kirim ACTION_STOP ke service: stop WS, hapus notifikasi, stopSelf()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(CameraStreamService.createStopIntent(this))
            } else {
                startService(CameraStreamService.createStopIntent(this))
            }
        }

        // TIDAK hapus savedIp → buka app lagi otomatis ke kamera
        Toast.makeText(this, "Aplikasi dihentikan. Buka kembali untuk terhubung.", Toast.LENGTH_SHORT).show()

        // finishAffinity(): tutup SEMUA activity di stack (keluar app)
        finishAffinity()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun showBadgeSafe() {
        if (isDestroyed || isFinishing) return
        badgeSwipeRevealed = false
        runCatching {
            binding.btnAkhiriBadge.visibility   = View.GONE
            binding.tvConnectedBadge.text       = "● Menerima data dari ESP32-S3  ‹ geser"
            binding.tvConnectedBadge.visibility = View.VISIBLE
        }
    }

    private fun updateAiIndicator() {
        val statusText = when (yoloDetector?.modelStatus) {
            ModelStatus.NONE -> "Model: NONE"
            ModelStatus.FP16 -> "Model: FP16"
            ModelStatus.INT8 -> "Model: INT8"
            ModelStatus.FULL -> "Model: FULL"
            null -> "Model: NONE"
        }
        
        binding.tvAiModelStatus.text = statusText
        if (yoloDetector?.modelStatus == ModelStatus.NONE || yoloDetector == null) {
            binding.tvAiModelStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
        } else {
            binding.tvAiModelStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        }
    }

    private fun hideBadgeSafe() {
        if (isDestroyed || isFinishing) return
        badgeSwipeRevealed = false
        runCatching {
            binding.btnAkhiriBadge.visibility   = View.GONE
            binding.tvConnectedBadge.visibility = View.GONE
        }
    }

    private fun showStreamStateSafe(state: StreamState) {
        if (isDestroyed || isFinishing) return
        runCatching {
            when (state) {
                StreamState.CONNECTING -> {
                    binding.progressStream.visibility = View.VISIBLE
                    binding.tvStreamStatus.text       = "Menghubungkan ke kamera ESP32..."
                    binding.btnReconnect.visibility   = View.GONE
                    binding.tvError.visibility        = View.GONE
                }
                StreamState.STREAMING -> {
                    binding.progressStream.visibility = View.GONE
                    binding.tvError.visibility        = View.GONE
                    binding.btnReconnect.visibility   = View.GONE
                }
                is StreamState.ERROR -> {
                    binding.progressStream.visibility = View.GONE
                    binding.tvError.text              = state.message
                    binding.tvError.visibility        = View.VISIBLE
                    binding.btnReconnect.visibility   = View.VISIBLE
                    binding.tvStreamStatus.text       = "Offline — menunggu ESP32..."
                    hideBadgeSafe()
                }
            }
        }
    }

    private fun cancelAllJobs() {
        runCatching { frameCollectJob?.cancel() }; frameCollectJob = null
        runCatching { stateCollectJob?.cancel() }; stateCollectJob = null
        runCatching { imuCollectJob?.cancel() };   imuCollectJob   = null
        runCatching { tofCollectJob?.cancel() };   tofCollectJob   = null
        runCatching { latencyMonitorJob?.cancel() }; latencyMonitorJob = null
        runCatching { pingWebsocketJob?.cancel() }; pingWebsocketJob = null
    }

    /**
     * Update visual monitor latency (ping) di UI.
     * Dijalankan pada thread utama.
     */
    private fun updateLatencyMonitorUi() {
        if (isDestroyed || isFinishing) return
        val cam = pingCamera
        val ws = if (pingWebsocket >= 0) "$pingWebsocket" else "?"
        val tofTotal = pingTotalTof
        val smooth = pingTofSmooth
        val formula = pingFormulaEH
        val terrain = pingTerrain
        val maxBottleneck = maxOf(cam, tofTotal)

        val text = """
            === SYSTEM PING MONITOR ===
            [Parallel Processing]
            Cam Decode : $cam ms
            WebSocket  : $ws ms
            ToF Total  : $tofTotal ms
            ---------------------------
            ► MAX BOTTLENECK : $maxBottleneck ms
            
            [Sequential ToF Details]
            ├─ Smoothing : $smooth ms
            ├─ Formula E/H : $formula ms
            └─ Terrain J : $terrain ms
            ===========================
        """.trimIndent()
        
        runCatching {
            binding.tvLatencyMonitor.text = text
        }
    }

    /**
     * Bersihkan tampilan sensor saat ESP32 disconnect / sedang reconnect.
     * Mencegah angka lama (stale) masih terlihat ketika tidak ada data masuk.
     */
    private fun clearStaleSensorDisplay() {
        if (isDestroyed || isFinishing) return
        // P3.3 + P6.3: Reset state sensor dan formula saat disconnect
        if (::ttsAlertManager.isInitialized) {
            ttsAlertManager.stopSpeaking()    // hentikan TTS yang mungkin sedang berjalan
            ttsAlertManager.resetAllFlags()   // siap diperingatkan lagi saat reconnect
        }
        lastTerrainAlertTime = 0L      // reset cooldown terrain
        isBlockedState = false         // reset status terhalang

        pingCamera = 0
        pingTofSmooth = 0
        pingFormulaEH = 0
        pingTerrain = 0
        pingTotalTof = 0

        runOnUiThread {
            runCatching {
                binding.tvImuPitch.text = "Pitch: —"
                binding.tvImuRoll.text  = "Roll: —"
                binding.tvImuAccel.text = "Accel: —"
                binding.tvLatencyMonitor.text = "=== SYSTEM PING MONITOR ===\nCam Decode : —\nToF Total  : —\n---------------------------\n► MAX BOTTLENECK : —\n\n[Sequential ToF Details]\n├─ Smoothing : —\n├─ Formula E/H : —\n└─ Terrain J : —\n==========================="
                binding.ivCameraFrame.setImageResource(android.R.color.transparent)
                if (::tofViews.isInitialized) {
                    tofViews.forEach {
                        it.text = "—"
                        it.setBackgroundColor(colorInvalidCell)  // gunakan konstanta, bukan parseColor
                    }
                }
            }
        }
    }

    /**
     * Mengembalikan warna gradasi semi-transparan berdasarkan jarak ToF.
     * Jarak <= 200mm = Merah penuh.
     * Jarak >= 2000mm = Hijau penuh.
     * Jarak di antaranya = Gradasi (Merah -> Oranye -> Kuning -> Hijau).
     *
     * @param dimmed Jika true, warna lebih transparan (alpha 48 ~19%) untuk menandai
     *               bahwa nilai ini sedang dalam masa holdover (data terakhir yang valid,
     *               bukan data segar). Normal alpha = 96 (~37%).
     *
     * OPTIMASI: gunakan hsvTemp (pre-allocated FloatArray) untuk menghindari
     * alokasi objek baru di setiap cell setiap frame.
     */
    private fun getColorForDistance(distance: Int, dimmed: Boolean = false): Int {
        if (distance <= 0) return colorInvalidCell
        val minDistance = 200f
        val maxDistance = 2000f
        val clampedDistance = distance.coerceIn(minDistance.toInt(), maxDistance.toInt()).toFloat()
        val ratio = (clampedDistance - minDistance) / (maxDistance - minDistance)
        hsvTemp[0] = ratio * 120f // 0f (Merah) s.d 120f (Hijau) — hsvTemp[1] & [2] sudah 1f
        // Alpha normal: 96 (~37% opacity) agar grid tidak menutupi gambar kamera
        // Alpha dimmed: 48 (~19% opacity) sebagai petunjuk visual bahwa data sedang holdover
        val alpha = if (dimmed) 48 else 96
        return android.graphics.Color.HSVToColor(alpha, hsvTemp)
    }

    private var isFullscreen = false
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }
        binding.root.post {
            updateUpperViewsMargins()
        }
    }

    private sealed class StreamState {
        object CONNECTING                      : StreamState()
        object STREAMING                       : StreamState()
        data class ERROR(val message: String) : StreamState()
    }

    // ── TOF Mode Switching ────────────────────────────────────────────────────

    /**
     * Ganti mode TOF: kirim command ke service/firmware, rebuild grid, simpan preference.
     */
    private fun switchTofMode(resolution: Int) {
        if (isDestroyed || isFinishing) return
        currentTofMode = resolution
        saveTofMode(resolution)
        // Kirim command ke firmware via service
        streamService?.sendTofModeCommand(resolution)
        // Rebuild grid lokal agar UI langsung responsif
        rebuildTofGrid(resolution)
        updateTofModeButtons(resolution)
        Toast.makeText(this,
            "Mode ToF: ${resolution}x${resolution}" +
            if (resolution == 4) " — SNR lebih baik" else "",
            Toast.LENGTH_SHORT).show()
    }

    /** Simpan mode ke SharedPreferences */
    private fun saveTofMode(mode: Int) {
        runCatching {
            getSharedPreferences("vnetra_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putInt("tof_mode", mode).apply()
        }
    }

    /** Load mode dari SharedPreferences (default 8x8) */
    private fun loadTofMode(): Int {
        return runCatching {
            getSharedPreferences("vnetra_prefs", android.content.Context.MODE_PRIVATE)
                .getInt("tof_mode", 8)
        }.getOrDefault(8)
    }

    /**
     * Update visual tombol mode (aktif = biru terang, tidak aktif = abu-abu gelap).
     */
    private fun updateTofModeButtons(activeMode: Int) {
        if (isDestroyed || isFinishing) return
        runCatching {
            if (activeMode == 8) {
                binding.btnTof8x8.setTextColor(android.graphics.Color.WHITE)
                binding.btnTof8x8.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1565C0"))
                binding.btnTof4x4.setTextColor(android.graphics.Color.parseColor("#90A4AE"))
                binding.btnTof4x4.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#263238"))
            } else {
                binding.btnTof4x4.setTextColor(android.graphics.Color.WHITE)
                binding.btnTof4x4.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1565C0"))
                binding.btnTof8x8.setTextColor(android.graphics.Color.parseColor("#90A4AE"))
                binding.btnTof8x8.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#263238"))
            }
        }
    }

    /**
     * Hapus semua cell lama dari gridTof dan buat ulang sesuai resolusi baru.
     * @param resolution 4 = 4x4 (16 cell), 8 = 8x8 (64 cell)
     */
    private fun rebuildTofGrid(resolution: Int) {
        if (isDestroyed || isFinishing) return
        val numCells   = resolution * resolution
        val textSizeSp = if (resolution == 4) 11f else 7.5f

        // Reset state EMA saat resolusi berubah agar tidak ada data lama dari mode sebelumnya.
        smoothedTofData = null
        holdoverCount   = null  // Reset holdover counter juga agar tidak ada counter stale

        // PENTING: Hapus semua view SEBELUM mengubah columnCount/rowCount.
        // Jika columnCount diubah dari 8→4 sementara 64 view dengan spec col=7 masih ada,
        // GridLayout akan crash di layout pass Choreographer (ArrayIndexOutOfBounds internal Android).
        binding.gridTof.removeAllViews()
        binding.gridTof.columnCount = resolution
        binding.gridTof.rowCount    = resolution

        // Buat cell baru
        tofViews = Array(numCells) { i ->
            val row = i / resolution
            val col = i % resolution
            android.widget.TextView(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(row, 1f),
                    android.widget.GridLayout.spec(col, 1f)
                ).apply {
                    width  = 0
                    height = 0
                    setMargins(1, 1, 1, 1)
                }
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                textSize = textSizeSp
                text     = "—"
                setBackgroundColor(colorInvalidCell)  // gunakan konstanta, bukan parseColor
            }.also { binding.gridTof.addView(it) }
        }

        // Reset translasi dan re-apply offset agar overlay ToF sejajar dengan kamera.
        //
        // FoV Vertikal:
        //   - VL53L5CX (ToF)  : 45°  → ±22.5° dari titik tengah
        //   - OV2640 (Kamera) : 41°  → ±20.5° dari titik tengah
        //
        // Kamera hanya menangkap 41/45 ≈ 91.1% dari rentang vertikal ToF.
        // Selisih FoV di atas/bawah masing-masing = (45° - 41°) / 2 = 2°.
        // Proporsi offset atas = 2° / 45° ≈ 0.0444 dari tinggi grid.
        //
        // Geser grid ke atas sebesar proporsi tersebut sehingga bagian atas ToF
        // yang "melampaui" bingkai kamera tersembunyi di luar tampilan.
        val TOF_FOV_V    = 45f
        val CAMERA_FOV_V = 41f
        val overlapFraction = (TOF_FOV_V - CAMERA_FOV_V) / 2f / TOF_FOV_V  // ≈ 0.0444
        binding.gridTof.post {
            binding.gridTof.translationY = -(binding.gridTof.height.toFloat() * overlapFraction)
        }
    }

    private fun initTofGrid() {
        // Bangun grid sesuai mode yang tersimpan
        rebuildTofGrid(currentTofMode)
    }

    private fun updateUpperViewsMargins() {
        if (!::binding.isInitialized) return
        val isToolbarVisible = binding.toolbar.visibility == View.VISIBLE
        val imuParams = binding.layoutImu.layoutParams as? FrameLayout.LayoutParams ?: return
        val latencyParams = binding.layoutLatencyMonitor.layoutParams as? FrameLayout.LayoutParams ?: return

        if (isToolbarVisible) {
            val actionBarHeight = getActionBarHeight()
            imuParams.topMargin = currentTopInset + actionBarHeight + 8.dpToPx()
            latencyParams.topMargin = currentTopInset + actionBarHeight + 8.dpToPx()
        } else {
            imuParams.topMargin = currentTopInset + 8.dpToPx()
            latencyParams.topMargin = currentTopInset + 8.dpToPx()
        }

        binding.layoutImu.layoutParams = imuParams
        binding.layoutLatencyMonitor.layoutParams = latencyParams
    }

    private fun getActionBarHeight(): Int {
        val tv = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        } else {
            56.dpToPx()
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
