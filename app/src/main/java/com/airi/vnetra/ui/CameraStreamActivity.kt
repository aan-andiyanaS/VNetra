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
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
import com.airi.vnetra.util.SimpleTracker
import com.airi.vnetra.util.TtcManager
import com.airi.vnetra.util.TtcStatus
import com.airi.vnetra.util.DatasetManager
import com.airi.vnetra.util.NavigationCoordinator
import com.airi.vnetra.util.ToFGridRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
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
    private lateinit var datasetManager: DatasetManager

    private var isDatasetModeActive      = false
    private var streamService:   CameraStreamService? = null
    private var isBound          = false
    private var frameCollectJob: Job? = null
    private var stateCollectJob: Job? = null
    private var imuCollectJob:   Job? = null
    private var tofCollectJob:   Job? = null
    private var ipAddress:       String = ""

    private var currentTopInset = 0
    private var currentBottomInset = 0

    // Mode ToF aktif: 4 atau 8 (4x4 atau 8x8)
    // Di-load dari SharedPreferences agar persisten antar sesi
    private var currentTofMode: Int = 8

    // ── State sensor terbaru — diakses oleh Formula E, G, J (P1.5) ────────
    // @Volatile: ditulis dari Dispatchers.Default, dibaca dari coroutine lain.
    // Tidak perlu synchronized karena assignment reference bersifat atomic di JVM.
    @Volatile private var latestImuData: FloatArray? = null  // 9 field: [θ,φ,ωx,ωy,ωz,a,ts,vBase,conv]
    @Volatile private var lastImuReceivedAt: Long = 0L
    @Volatile private var latestTofData: IntArray?   = null  // 16 atau 64 nilai (mm), -1 = invalid

    // Guard (Staleness Check): Kembalikan null jika IMU terputus/lag > 200ms
    private val safeImuData: FloatArray?
        get() = if (System.currentTimeMillis() - lastImuReceivedAt > 200L) null else latestImuData

    // ── Latency (Ping) Monitor State (L1.2) ───────────────────────────
    @Volatile private var pingCamera:     Long = 0
    @Volatile private var pingTofSmooth:  Long = 0
    @Volatile private var pingFormulaEH:  Long = 0
    @Volatile private var pingTerrain:    Long = 0
    @Volatile private var pingTotalTof:   Long = 0
    @Volatile private var pingWebsocket:  Long = -1L

    private var latencyMonitorJob: Job? = null
    private var pingWebsocketJob: Job? = null
    private var muteToggleJob: Job? = null

    // ── TTS Alert Manager (P3.3) ────────────────
    private lateinit var ttsAlertManager: TtsAlertManager
    @Volatile private var initialYawOffset: Float? = null  // ditulis dari imuCollectJob (Default)
    private lateinit var navigationCoordinator: NavigationCoordinator
    private lateinit var tofGridRenderer: ToFGridRenderer

    // ── Formula J — Terrain Detector (P6) ───────────────────────
    private val terrainDetector = TerrainDetector()
    
    @Volatile private var isBlockedState = false
    // Cooldown: cegah terrain alert flood (min. 3 detik antar peringatan, kecuali HIGH yang selalu langsung)
    

    // Temporal holdover: tahan nilai terakhir yang valid selama N frame sebelum tampil "—".
    // Dipindahkan ke local state di tofCollectJob (thread-safe by design, hanya satu coroutine).
    private val HOLDOVER_FRAMES = 15

    // FPS counter
    private var frameCount     = 0
    private var fpsWindowStart = 0L

    // Swipe gesture untuk badge koneksi
    private var badgeSwipeRevealed = false

    // Guard: cegah double-execute akhiriProses
    // @Volatile: ditulis dari Main thread, dibaca dari Dispatchers.Default coroutines.
    @Volatile private var isAkhiring = false

    // AI Detector
    private var yoloDetector: YoloDetector? = null
    private val tracker = SimpleTracker(maxAge = 5)
    private val ttcManager = TtcManager()
    @Volatile private var latestDetections: List<DetectionResult> = emptyList()
    @Volatile private var latestFrameWidth: Int = 640
    @Volatile private var latestFrameHeight: Int = 480

    // ADR-046: Double buffer bitmap — eliminasi alokasi heap 600KB per frame (GC pause fix).
    // bitmapBuffer[0] = front (sedang ditampilkan), bitmapBuffer[1] = back (sedang di-decode).
    // bufferIndex menunjuk ke slot yang BARU saja selesai di-decode (aktif di ImageView).
    private val bitmapBuffer = arrayOfNulls<Bitmap>(2)
    private var bufferIndex  = 0

    // ADR-035: Debounce state for forward movement validation handled by NavigationCoordinator
    private val isInferencing = java.util.concurrent.atomic.AtomicBoolean(false)
    // Fix 1.5: cache grid size to avoid withContext(Main) on every ToF frame (common path).
    // Updated on Main thread after rebuildGrid; read on Default thread (volatile for visibility).
    @Volatile private var cachedTofGridSize = 0

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
        
        tofGridRenderer = ToFGridRenderer(this, binding.gridTof)

        // Set support action bar with the custom toolbar
        setSupportActionBar(binding.toolbar)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ipAddress = intent.getStringExtra(EXTRA_IP) ?: run {
            Toast.makeText(this, "IP address tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sessionManager = SessionManager(this)
        datasetManager = DatasetManager(this)

        supportActionBar?.title = "Live Camera — $ipAddress"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // ADR-035 (Tambahan): Mencegah Android modern menghancurkan aplikasi 
        // saat tombol / gesture Back ditekan, sehingga pemrosesan latar belakang tetap aman.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

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
        // Ambil saved mode ToF (default 8) lalu render grid
        currentTofMode = loadTofMode()
        tofGridRenderer.initializeGrid(currentTofMode)
        updateTofModeButtons(currentTofMode)
        showStreamStateSafe(StreamState.CONNECTING)

        requestNotificationPermission()
        requestBatteryOptimizationBypass()

        // P3.3: Inisialisasi TtsAlertManager + TTS Engine
        // Dipanggil di onCreate agar TTS punya cukup waktu 
        ttsAlertManager = TtsAlertManager(this)
        ttsAlertManager.initTts()
        navigationCoordinator = NavigationCoordinator(ttsAlertManager, ttcManager)

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
        
        // PONYTAIL ADR-035: Hindari re-binding jika sudah ter-bind 
        // (karena sekarang tidak di-unbind di onStop)
        if (!isBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(exitReceiver) }
        
        // PONYTAIL: ADR-035
        // Biarkan semua coroutine (YOLO, ToF, TTS) dan binding tetap berjalan 
        // di latar belakang (background) saat aplikasi diminimalkan (onStop).
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // ADR-035: Matikan semua background processing saat aplikasi benar-benar ditutup
        cancelAllJobs()
        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound       = false
            streamService = null
        }
        // P3.3: Bebaskan resource TTS — mencegah leak AudioTrack di background
        if (::ttsAlertManager.isInitialized) ttsAlertManager.shutdown()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        yoloDetector?.close()
        // ADR-046: Recycle double bitmap buffer
        bitmapBuffer[0]?.recycle(); bitmapBuffer[0] = null
        bitmapBuffer[1]?.recycle(); bitmapBuffer[1] = null
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

        // ── Tombol Dataset ───────────────────────────────────────────────────
        binding.btnModeDataset.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
                    buttonView.isChecked = false
                    return@setOnCheckedChangeListener
                }
            }

            isDatasetModeActive = isChecked
            if (::ttsAlertManager.isInitialized) {
                ttsAlertManager.isMuted = isChecked
                if (isChecked) {
                    Toast.makeText(this, "Mode Dataset Aktif. TTS dimatikan.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Mode Dataset Nonaktif. TTS menyala kembali.", Toast.LENGTH_SHORT).show()
                }
            }
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
                    return if (kotlin.math.abs(diffX) > 80f && kotlin.math.abs(velocityX) > 100f) {
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
                    
                    if (isDatasetModeActive) {
                        datasetManager.saveFrameIfNeeded(jpegBytes)
                    }

                    val startTime = System.currentTimeMillis()

                    // ADR-046: Double buffer — decode ke slot 'back' (yang tidak sedang di-render).
                    // GUARD: jangan reuse buffer jika YOLO masih membaca pixelnya (CPU inference bisa >133ms).
                    // Jika isInferencing=true, inBitmap=null → alokasi baru frame ini (GC normal, tidak crash).
                    val backIdx = 1 - bufferIndex
                    options.inBitmap = if (!isInferencing.get()) bitmapBuffer[backIdx] else null

                    val bitmap = runCatching {
                        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                    }.getOrElse {
                        // inBitmap gagal (frame pertama atau ukuran beda) — fallback tanpa reuse
                        options.inBitmap = null
                        runCatching {
                            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                        }.getOrNull()
                    }
                    pingCamera = System.currentTimeMillis() - startTime

                    if (bitmap == null) return@collect
                    latestFrameWidth  = bitmap.width
                    latestFrameHeight = bitmap.height

                    // Simpan ke slot back; jika bitmap baru dialokasikan (fallback), recycle slot lama.
                    if (bitmap !== bitmapBuffer[backIdx]) {
                        bitmapBuffer[backIdx]?.recycle()
                        bitmapBuffer[backIdx] = bitmap
                    }
                    bufferIndex = backIdx  // swap: back menjadi front

                    // Fix 1.1: UI hanya setImageBitmap + updateFpsCounter — sesederhana mungkin di Main thread.
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring) {
                            binding.ivCameraFrame.setImageBitmap(bitmap)
                            updateFpsCounter(jpegBytes.size)
                        }
                    }

                    // Fix 1.1: YOLO diluncurkan di luar withContext(Main).
                    // AtomicBoolean.compareAndSet dan lifecycleScope.launch keduanya thread-safe.
                    if (!isDestroyed && !isFinishing && !isAkhiring &&
                        yoloDetector?.modelStatus != ModelStatus.NONE &&
                        isInferencing.compareAndSet(false, true)) {
                        val detector = yoloDetector
                        if (detector != null) {
                            lifecycleScope.launch(Dispatchers.Default) {
                                try {
                                    val startTime = android.os.SystemClock.elapsedRealtime()
                                    val rawResults = detector.detect(bitmap)
                                    val inferenceTime = android.os.SystemClock.elapsedRealtime() - startTime
                                    val trackedResults = tracker.process(rawResults)
                                    ttcManager.cleanup(trackedResults.map { it.trackId }.toSet())
                                    latestDetections = trackedResults
                                    navigationCoordinator.processInstantYoloTts(
                                        detections = trackedResults,
                                        tofData = latestTofData,
                                        imuData = safeImuData,
                                        frameWidth = bitmap.width,
                                        tofMode = currentTofMode
                                    )
                                    withContext(Dispatchers.Main) {
                                        if (!isDestroyed && !isFinishing && !isAkhiring) {
                                            binding.boundingBoxOverlay.setResults(trackedResults, bitmap.width.toFloat(), bitmap.height.toFloat())
                                            
                                            // Update YOLO Debug UI
                                            val maxConf = (yoloDetector?.lastMaxConfidence ?: 0f) * 100
                                            val boxesCount = trackedResults.size
                                            val yoloFps = if (inferenceTime > 0) 1000f / inferenceTime else 0f
                                            binding.tvYoloDebug.text = String.format("YOLO: %d boxes | Max Conf: %.1f%% | %dms (%.1f FPS)", boxesCount, maxConf, inferenceTime, yoloFps)
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e !is kotlinx.coroutines.CancellationException) {
                                        android.util.Log.e("CameraStreamActivity", "Error during AI inference", e)
                                    } else {
                                        throw e
                                    }
                                } finally {
                                    // Fix P4: AtomicBoolean.set() thread-safe — tidak perlu withContext(Main+NonCancellable).
                                    isInferencing.set(false)
                                }
                            }
                        } else {
                            isInferencing.set(false)
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
        muteToggleJob?.cancel()

        // Reset state latency
        pingCamera = 0
        pingTofSmooth = 0
        pingFormulaEH = 0
        pingTerrain = 0
        pingTotalTof = 0
        pingWebsocket = -1L

        // Start latency monitor polling job (5Hz = 200ms)
        latencyMonitorJob = lifecycleScope.launch {
            while (isActive) {
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

        muteToggleJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                svc.muteToggleFlow.collect {
                    if (::ttsAlertManager.isInitialized) {
                        ttsAlertManager.isMuted = !ttsAlertManager.isMuted
                        if (ttsAlertManager.isMuted) {
                            ttsAlertManager.speakForce("Suara dimatikan sementara")
                        } else {
                            ttsAlertManager.speakForce("Suara diaktifkan kembali")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CameraStreamActivity", "Mute toggle collect error", e)
            }
        }

        imuCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                svc.imuFlow.collect { imuData ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect
                    // P1.6: Simpan state IMU terbaru untuk Formula E, G, J
                    latestImuData = imuData
                    lastImuReceivedAt = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring && imuData.size >= 6) {
                            val converged = imuData.getOrElse(8) { 0f } > 0.5f
                            
                            if (converged) {
                                if (initialYawOffset == null) {
                                    initialYawOffset = imuData[2]
                                }
                                binding.tvImuAccel.text = "Accel     : %6.2f m/s²".format(imuData[5])
                            } else {
                                binding.tvImuAccel.text = "Mahony: warming up..."
                            }
                            binding.tvImuPitch.text     = "Pitch     : %5.1f°".format(imuData[0])
                            binding.tvImuRoll.text      = "Roll      : %5.1f°".format(imuData[1])
                            binding.tvImuPitchRate.text = "Pitch Rate: %5.1f°/s".format(imuData[2])  // [2]=ωx_corr = Pitch Rate
                            binding.tvImuRollRate.text  = "Roll Rate : %5.1f°/s".format(imuData[3])  // [3]=ωy_corr = Roll Rate
                            binding.tvImuYaw.text       = "Yaw Rate  : %5.1f°/s".format(imuData[4])
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
            // Local state: hanya diakses dari satu coroutine ini (thread-safe by design).
            // Tidak perlu @Volatile karena tidak ada thread lain yang menyentuhnya.
            var localSmoothed: FloatArray? = null
            var localHoldover: IntArray?   = null

            try {
                svc.tofFlow.collect { tofData ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect
                    latestTofData = tofData

                    // ── Fase 1: Guard resolusi berubah (perlu Main karena rebuildTofGrid adalah UI op) ──
                    val startSmooth = System.currentTimeMillis()
                    // Fix 1.5: Fast path — baca cachedTofGridSize tanpa context switch ke Main.
                    // withContext(Main) hanya terjadi saat init pertama atau mode berubah (jarang).
                    if (cachedTofGridSize == 0 || tofData.size != cachedTofGridSize) {
                        withContext(Dispatchers.Main) {
                            if (!::tofGridRenderer.isInitialized) return@withContext
                            val currentSize = tofGridRenderer.getGridSize()
                            if (tofData.size != currentSize) {
                                val detectedMode = if (tofData.size == 16) 4 else 8
                                if (currentTofMode != detectedMode) {
                                    currentTofMode = detectedMode
                                    saveTofMode(detectedMode)
                                    tofGridRenderer.rebuildGrid(detectedMode)
                                    updateTofModeButtons(detectedMode)
                                }
                                localSmoothed = null
                                localHoldover = null
                                // cachedTofGridSize tetap 0: sinyal skip frame berikutnya juga
                            } else {
                                cachedTofGridSize = currentSize // Grid valid, cache ukurannya
                            }
                        }
                        pingTofSmooth = System.currentTimeMillis() - startSmooth
                        if (cachedTofGridSize == 0) return@collect // Grid belum siap atau baru di-rebuild
                    }

                    // ── Fase 2: KOMPUTASI di Default thread (tidak menyentuh View) ──
                    if (localSmoothed == null || localSmoothed!!.size != tofData.size) {
                        localSmoothed = FloatArray(tofData.size) { i -> tofData[i].toFloat() }
                        localHoldover = null
                    }
                    if (localHoldover == null || localHoldover!!.size != tofData.size) {
                        localHoldover = IntArray(tofData.size) { HOLDOVER_FRAMES }
                    }

                    val smoothed = localSmoothed!!
                    val holdover = localHoldover!!
                    val alpha = 0.3f
                    val currentDetections = latestDetections
                    val currentFrameWidth  = latestFrameWidth.coerceAtLeast(1)
                    val currentFrameHeight = latestFrameHeight.coerceAtLeast(1)
                    val mode = currentTofMode

                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring && ::tofGridRenderer.isInitialized) {
                            tofGridRenderer.updateGrid(
                                tofData = tofData,
                                mode = mode,
                                smoothed = smoothed,
                                holdover = holdover,
                                currentDetections = currentDetections,
                                currentFrameWidth = currentFrameWidth,
                                currentFrameHeight = currentFrameHeight,
                                alpha = alpha
                            )
                        }
                    }
                    pingTofSmooth = System.currentTimeMillis() - startSmooth

                    val imuSnap  = safeImuData
                    val rawTheta = imuSnap?.getOrElse(0) { 0f } ?: 0f
                    val thetaDeg = rawTheta - 20f

                    val startFormula = System.currentTimeMillis()
                    var closeThreatExists = false
                    var allClear = true

                    navigationCoordinator.updateMovementState(imuSnap)
                    val isMovingForward = navigationCoordinator.movingForwardConsecutiveFrames >= 3
                    val yawRate = imuSnap?.getOrElse(4) { 0f } ?: 0f
                    // isTurning: threshold 10°/s agar menoleh pelan (mencari jalan) juga dikenali
                    // sebagai 'searching', sehingga SmartNavigation diam (tidak spam) saat proses pencarian.
                    val isTurning = kotlin.math.abs(yawRate) > 10f
                    // isHeadRotating: threshold 15°/s — cukup ketat untuk menangkap nodding/sweep lantai
                    // (false positive terrain), tapi cukup longgar agar langsung unblock setelah selesai menoleh.
                    val isHeadRotating = navigationCoordinator.isHeadRotating(imuSnap, 15f)

                    // ADR-035: Auto-Unmute (Mute Cerdas)
                    if (isMovingForward && ::ttsAlertManager.isInitialized && ttsAlertManager.isMuted) {
                        ttsAlertManager.isMuted = false
                        ttsAlertManager.speakForce("Pergerakan terdeteksi, suara diaktifkan kembali")
                    }


                    var hasCloseYoloThreat = false
                    if (::ttsAlertManager.isInitialized) {
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
                                
                                // Jika ToF gagal membaca jarak (D_MAX), lewati objek ini
                                if (dObj >= TofDepthEstimator.D_MAX) continue
                                
                                // Catatan: ttsAlertManager.process untuk YOLO kini ditangani penuh secara instan
                                // oleh triggerInstantYoloTts. Di sini kita hanya mengupdate state deteksi ancaman.

                                val adaptiveT = ttsAlertManager.getAdaptiveThreshold(det.classId)
                                if (dObj < adaptiveT) {
                                    hasCloseYoloThreat = true
                                    closeThreatExists  = true
                                }
                                if (dObj < adaptiveT + TtsAlertManager.EPS_CLEAR_ZONE) {
                                    allClear = false
                                }
                            }
                        }

                        // Jika tidak ada deteksi YOLO yang berada di dekat (< D_W0),
                        // cek apakah ToF mendeteksi tembok datar ATAU halangan umum di depannya.
                        val wallDetected = SpatialMappingUtils.isWall(tofData, currentTofMode)
                        
                        var genericObstacleDistance = Int.MAX_VALUE
                        val centerCols = SpatialMappingUtils.centerColumns(currentTofMode)
                        for (c in centerCols) {
                            val d = TofDepthEstimator.calculate(tofData, c, thetaDeg, currentTofMode)
                            if (d < genericObstacleDistance) {
                                genericObstacleDistance = d
                            }
                        }

                        if (!hasCloseYoloThreat && (wallDetected || genericObstacleDistance < 2000)) {
                            val obstacleDist = if (wallDetected) {
                                // ponytail: fold avoids List allocation (was: filter{}.average() at 10Hz)
                                var sum = 0L; var count = 0
                                for (d in tofData) { if (d in 30..1500) { sum += d; count++ } }
                                if (count > 0) (sum / count).toInt() else Int.MAX_VALUE
                            } else {
                                genericObstacleDistance
                            }
                            
                            val obstacleAlert = ttsAlertManager.process(
                                trackingId      = SpatialMappingUtils.WALL_TRACKING_ID,
                                dObj            = obstacleDist,
                                clockDirection  = 12,    // halangan selalu didepan
                                objectLabel     = if (wallDetected) "tembok" else "halangan",
                                isMovingForward = isMovingForward,
                                imuData         = safeImuData
                            )
                            if (obstacleAlert != null) {
                                ttsAlertManager.speak(obstacleAlert)
                            }

                            val adaptiveT = ttsAlertManager.getAdaptiveThreshold(SpatialMappingUtils.WALL_TRACKING_ID)
                            if (obstacleDist < adaptiveT) {
                                closeThreatExists = true
                            }
                            if (obstacleDist < adaptiveT + TtsAlertManager.EPS_CLEAR_ZONE) {
                                allClear = false
                            }
                        } else {
                            // Jika tidak ada tembok terdeteksi (atau tertutup objek YOLO dekat), 
                            // panggil process dengan jarak aman agar flag tembok di-reset
                            ttsAlertManager.process(
                                trackingId      = SpatialMappingUtils.WALL_TRACKING_ID,
                                dObj            = 2000, // jarak aman > D_RESET
                                clockDirection  = 12,
                                objectLabel     = "tembok",
                                isMovingForward = isMovingForward,
                                imuData         = safeImuData
                            )
                        }

                        // =========================================================
                        // Logika Peringatan Smart Navigation TTS (Jalan Kosong / Tembok)
                        // =========================================================
                        
                        // Bahaya jika ada tembok yang mendekat (closeThreatExists)
                        // Atau jika seluruh ToF mendeteksi halangan < D_RESET (allClear == false)
                        // allClear = false berarti ada sesuatu di jarak < 1150 mm
                        val isDanger = closeThreatExists || !allClear

                        if (::ttsAlertManager.isInitialized) {
                            ttsAlertManager.smartNavigation.processNavigationState(
                                isDanger = isDanger,
                                isMovingForward = isMovingForward,
                                isTurning = isTurning,
                                isHeadRotating = isHeadRotating  // ADR-035: blokir transisi state saat mengangguk
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

                            // ADR-035: Lewati terrain detection saat kepala berotasi.
                            // Menunduk menyebabkan ToF menyapu lantai → false positive CONTAMINATED/HOLE.
                            if (!isHeadRotating &&
                                terrainResult.type != TerrainDetector.TerrainType.SAFE &&
                                terrainResult.type != TerrainDetector.TerrainType.OPEN &&
                                terrainResult.confidence >= 0.55f) {
                                
                                val typeText = when (terrainResult.type) {
                                    TerrainDetector.TerrainType.STAIR_DOWN -> "tangga turun"
                                    TerrainDetector.TerrainType.STAIR_UP   -> "tangga naik"
                                    TerrainDetector.TerrainType.HOLE       -> "lubang"
                                    TerrainDetector.TerrainType.CONTAMINATED -> "objek dekat"
                                    else -> ""
                                }

                                if (typeText.isNotEmpty()) {
                                    // Validasi YOLO khusus untuk Tangga (HOLE, CONTAMINATED bypass YOLO)
                                    var yoloValidated = true
                                    val isStair = terrainResult.type == TerrainDetector.TerrainType.STAIR_DOWN || terrainResult.type == TerrainDetector.TerrainType.STAIR_UP
                                    
                                    if (isStair) {
                                        val currentDetections = latestDetections
                                        yoloValidated = currentDetections.any { it.className == "tangga naik" || it.className == "tangga turun" }
                                    }

                                    if (yoloValidated) {
                                        // Lempar ke TtsAlertManager agar mematuhi Formula G (Adaptive Threshold) & Formula H (Reset Cooldown)
                                        val alertMsg = ttsAlertManager.process(
                                            trackingId = SpatialMappingUtils.TERRAIN_TRACKING_ID,
                                            dObj = terrainResult.distance.toInt(),
                                            clockDirection = terrainResult.direction,
                                            objectLabel = typeText,
                                            isMovingForward = isMovingForward,
                                            imuData = safeImuData
                                        )
                                        
                                        if (alertMsg != null) {
                                            val isHigh = terrainResult.confidence >= 0.70f
                                            if (isHigh) ttsAlertManager.speak(alertMsg)
                                            else ttsAlertManager.speakAdd(alertMsg)
                                        }
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
            ModelStatus.FP32 -> "Model: FP32"
            ModelStatus.INT8 -> "Model: INT8"
            ModelStatus.FULL -> "Model: FULL"
            null -> "Model: NONE"
        }
        
        val delegateText = yoloDetector?.activeDelegate?.name ?: "NONE"
        val finalText = "$statusText | Mesin: $delegateText"
        
        binding.tvAiModelStatus.text = finalText
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
        runCatching { frameCollectJob?.cancel() };   frameCollectJob   = null
        runCatching { stateCollectJob?.cancel() };   stateCollectJob   = null
        runCatching { imuCollectJob?.cancel() };     imuCollectJob     = null
        runCatching { tofCollectJob?.cancel() };     tofCollectJob     = null
        runCatching { latencyMonitorJob?.cancel() }; latencyMonitorJob = null
        runCatching { pingWebsocketJob?.cancel() };  pingWebsocketJob  = null
        runCatching { muteToggleJob?.cancel() };     muteToggleJob     = null
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
        
        isBlockedState = false         // reset status terhalang
        initialYawOffset = null

        pingCamera = 0
        pingTofSmooth = 0
        pingFormulaEH = 0
        pingTerrain = 0
        pingTotalTof = 0

        runOnUiThread {
            runCatching {
                binding.tvImuPitch.text = "Pitch: —"
                binding.tvImuRoll.text  = "Roll:  —"
                binding.tvImuYaw.text   = "Yaw:   —"
                binding.tvImuAccel.text = "Accel: —"
                binding.tvLatencyMonitor.text = "=== SYSTEM PING MONITOR ===\nCam Decode : —\nToF Total  : —\n---------------------------\n► MAX BOTTLENECK : —\n\n[Sequential ToF Details]\n├─ Smoothing : —\n├─ Formula E/H : —\n└─ Terrain J : —\n==========================="
                binding.ivCameraFrame.setImageResource(android.R.color.transparent)
                if (::tofGridRenderer.isInitialized) {
                    tofGridRenderer.clearGrid()
                }
            }
        }
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
        tofGridRenderer.rebuildGrid(resolution)
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
