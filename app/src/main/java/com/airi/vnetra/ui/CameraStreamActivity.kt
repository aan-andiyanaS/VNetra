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
import com.airi.vnetra.util.FormulaE
import com.airi.vnetra.util.FormulaH
import com.airi.vnetra.util.FormulaUtils
import com.airi.vnetra.util.SessionManager
import com.airi.vnetra.util.TerrainDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // ── Formula H — One-shot alert + TTS Engine (P3.3) ────────────────
    private lateinit var formulaH: FormulaH

    // ── Formula J — Terrain Detector (P6) ───────────────────────
    private val terrainDetector = TerrainDetector()
    private var lastTerrainAlertTime  = 0L
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

        // P3.3: Inisialisasi FormulaH + TTS Engine
        // Dipanggil di onCreate agar TTS punya cukup waktu init sebelum sensor aktif
        formulaH = FormulaH(this)
        formulaH.initTts()
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
        if (::formulaH.isInitialized) formulaH.shutdown()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    val bitmap = runCatching {
                        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                    }.getOrNull() ?: return@collect

                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring) {
                            binding.ivCameraFrame.setImageBitmap(bitmap)
                            updateFpsCounter(jpegBytes.size)
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
                    // P1.7: Simpan state ToF terbaru untuk Formula E dan J
                    latestTofData = tofData
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring
                            && ::tofViews.isInitialized) {

                            // Jika ukuran data tidak cocok dengan jumlah cell grid,
                            // firmware sedang transisi mode — skip frame ini dan tunggu.
                            if (tofData.size != tofViews.size) {
                                smoothedTofData = null // Reset smoothing array jika resolusi berubah
                                return@withContext
                            }

                            if (smoothedTofData == null || smoothedTofData!!.size != tofData.size) {
                                smoothedTofData = FloatArray(tofData.size) { i -> tofData[i].toFloat() }
                                holdoverCount   = null  // Inisialisasi ulang saat ukuran berubah
                            }

                            // Inisialisasi holdover counter jika belum ada atau ukuran berubah
                            if (holdoverCount == null || holdoverCount!!.size != tofData.size) {
                                holdoverCount = IntArray(tofData.size) { HOLDOVER_FRAMES }
                            }

                            val alpha = 0.3f // Faktor smoothing EMA

                            for (i in tofData.indices) {
                                val rawDistance = tofData[i]

                                if (rawDistance <= 0) {
                                    // rawDistance == -1 : sentinel firmware (cell status tidak valid)
                                    // rawDistance ==  0 : tidak ada target terdeteksi
                                    //
                                    // TEMPORAL HOLDOVER: jangan langsung tampilkan "—".
                                    // Kurangi counter dulu. Cell terluar sering berganti valid/invalid
                                    // secara selang-seling karena SNR rendah di sudut FoV — holdover
                                    // mencegah flicker "—" yang tidak nyaman ditampilkan ke pengguna.
                                    val remaining = holdoverCount!![i]
                                    if (remaining > 0) {
                                        // Masih dalam masa toleransi: tahan nilai EMA terakhir
                                        holdoverCount!![i] = remaining - 1
                                        val held = smoothedTofData!![i].toInt()
                                        if (held > 0) {
                                            // Tampilkan nilai terakhir yang valid (dengan warna sedikit redup)
                                            tofViews[i].text = "$held"
                                            tofViews[i].setBackgroundColor(
                                                getColorForDistance(held, dimmed = true)
                                            )
                                        }
                                        // Jika belum pernah ada nilai valid (held == 0), biarkan tampil apa adanya
                                    } else {
                                        // Counter habis: cell benar-benar tidak ada target → tampilkan "—"
                                        tofViews[i].text = "—"
                                        tofViews[i].setBackgroundColor(colorInvalidCell)
                                        smoothedTofData!![i] = 0f
                                    }
                                } else {
                                    // Data valid: reset holdover counter, update EMA
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
                     // Formula E+H+J berjalan di Dispatchers.Default (scope collect)



                    // ── Formula E + H: One-shot alert objek di depan (tanpa YOLO) ───
                    // Berjalan di Dispatchers.Default, TIDAK di Main thread.
                    // Tahap 1: gunakan kolom tengah (3 dan 4) sebagai proxy "objek di depan".
                    // Tahap 2 (YOLO): ganti dengan loop per detection, trackingId = YOLO ID.
                    if (::formulaH.isInitialized) {
                        val imuSnap  = latestImuData
                        val rawTheta = imuSnap?.getOrElse(0) { 0f } ?: 0f
                        
                        // Kompensasi sudut kemiringan fisik ToF (20 derajat ke bawah)
                        // Karena MPU6050 tetap lurus (0 derajat), tetapi ToF menunduk 20 derajat,
                        // kita kurangi 20 derajat dari raw pitch agar formula memahami orientasi aktual ToF.
                        val thetaDeg = rawTheta - 20f

                        // Kolom "tepat depan" bergantung resolusi:
                        // 8×8 → kolom 3 & 4 (tengah 0..7)
                        // 4×4 → kolom 1 & 2 (tengah 0..3)
                        for (col in FormulaUtils.centerColumns(currentTofMode)) {
                            val dObj = FormulaE.calculate(
                                tofData    = tofData,
                                j          = col,
                                thetaDeg   = thetaDeg,
                                resolution = currentTofMode
                            )
                            formulaH.process(
                                trackingId    = col,   // proxy ID = indeks kolom
                                dObj          = dObj,
                                clockDirection = 12    // kolom tengah = selalu JAM 12
                            )
                        }

                        // Formula J: Terrain Detection — process() menangani 8×8 dan 4×4
                        // Guard hanya untuk array size yang benar: 64 (8×8) atau 16 (4×4)
                        val expectedSize = currentTofMode * currentTofMode
                        if (tofData.size == expectedSize) {
                            val terrainResult = terrainDetector.process(
                                tofData   = tofData,
                                thetaDeg  = thetaDeg
                            )

                            if (terrainResult.alertLevel != TerrainDetector.AlertLevel.NONE) {
                                val nowMs    = System.currentTimeMillis()
                                val isHigh   = terrainResult.alertLevel == TerrainDetector.AlertLevel.HIGH
                                val cooldown = (nowMs - lastTerrainAlertTime) > TERRAIN_ALERT_COOLDOWN_MS

                                // HIGH selalu dibunyikan; MED/INFO hanya jika sudah lewat cooldown
                                if (isHigh || cooldown) {
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
                                        TerrainDetector.TerrainType.RAMP       -> "landai"
                                        else -> ""
                                    }

                                    val msg = when (terrainResult.alertLevel) {
                                        TerrainDetector.AlertLevel.HIGH ->
                                            "Awas! $typeText, sekitar $hCm sentimeter, $dirText!"
                                        TerrainDetector.AlertLevel.MED  ->
                                            "Perhatian, $typeText, $hCm sentimeter, $dirText"
                                        TerrainDetector.AlertLevel.INFO ->
                                            "Landai $dirText"
                                        else -> ""
                                    }

                                    if (msg.isNotEmpty()) {
                                        if (isHigh) formulaH.speak(msg)
                                        else formulaH.speakAdd(msg)
                                    }
                                }
                            }
                        }
                    } // end if (formulaH.isInitialized)
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
    }

    /**
     * Bersihkan tampilan sensor saat ESP32 disconnect / sedang reconnect.
     * Mencegah angka lama (stale) masih terlihat ketika tidak ada data masuk.
     */
    private fun clearStaleSensorDisplay() {
        if (isDestroyed || isFinishing) return
        // P3.3 + P6.3: Reset state sensor dan formula saat disconnect
        if (::formulaH.isInitialized) {
            formulaH.stopSpeaking()    // hentikan TTS yang mungkin sedang berjalan
            formulaH.resetAllFlags()   // siap diperingatkan lagi saat reconnect
        }
        lastTerrainAlertTime = 0L      // reset cooldown terrain
        runOnUiThread {
            runCatching {
                binding.tvImuPitch.text = "Pitch: —"
                binding.tvImuRoll.text  = "Roll: —"
                binding.tvImuAccel.text = "Accel: —"
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
        val badgeParams = binding.badgeSwipeContainer.layoutParams as? FrameLayout.LayoutParams ?: return

        if (isToolbarVisible) {
            val actionBarHeight = getActionBarHeight()
            imuParams.topMargin = currentTopInset + actionBarHeight + 8.dpToPx()
            badgeParams.topMargin = currentTopInset + actionBarHeight + 12.dpToPx()
        } else {
            imuParams.topMargin = currentTopInset + 8.dpToPx()
            badgeParams.topMargin = currentTopInset + 12.dpToPx()
        }

        binding.layoutImu.layoutParams = imuParams
        binding.badgeSwipeContainer.layoutParams = badgeParams
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
