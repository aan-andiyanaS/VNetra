package com.airi.vnetra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.airi.vnetra.R
import com.airi.vnetra.ui.CameraStreamActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * CameraStreamService v5 — Simplified & Stable
 *
 * Flow:
 *  - Start: terima IP → mulai WebSocket loop dengan exponential backoff
 *  - Stop: ACTION_STOP → bersihkan WS + notifikasi → stopSelf()
 *  - Auto-reconnect: loop terus sampai berhasil (jika bukan dari ACTION_STOP)
 *  - Restart setelah app dibuka ulang: MainActivity langsung ke CameraStreamActivity
 *    yang akan start service kembali dengan IP tersimpan
 */
class CameraStreamService : Service() {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        private const val TAG               = "CameraStreamService"
        private const val NOTIF_CH_FG       = "camera_stream_channel"
        private const val NOTIF_CH_ALERT    = "esp32_connected_alert"
        private const val NOTIF_ID_FG       = 1001
        private const val NOTIF_ID_ALERT    = 1002
        private const val NOTIF_ID_STOPPED  = 1003
        private const val FRAME_TYPE_JPEG   = 0x01.toByte()
        private const val FRAME_TYPE_IMU    = 0x02.toByte()
        private const val FRAME_TYPE_HBEAT  = 0x03.toByte()
        private const val FRAME_TYPE_TOF    = 0x04.toByte()
        private const val FRAME_HEADER_SZ   = 9
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS  = 8_000L

        const val EXTRA_IP    = "esp32_ip"
        const val ACTION_STOP = "com.example.phase4_camera_eps_s3_mobile.ACTION_STOP"
        const val ACTION_EXIT_APP = "com.example.phase4_camera_eps_s3_mobile.ACTION_EXIT_APP"

        fun createStartIntent(ctx: Context, ip: String) =
            Intent(ctx, CameraStreamService::class.java).apply { putExtra(EXTRA_IP, ip) }

        fun createStopIntent(ctx: Context) =
            Intent(ctx, CameraStreamService::class.java).apply { action = ACTION_STOP }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CameraStreamService = this@CameraStreamService
    }

    private val binder       = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job?             = null
    private var activeWebSocket: WebSocket? = null
    private var reconnectAttempts           = 0
    private var ipAddress                   = ""
    private var stopped                     = false   // flag: service sedang/sudah di-stop

    // Anti-putus locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock?  = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        // Tidak gunakan pingInterval — ESPAsyncWebServer tidak membalas WebSocket ping (opcode 0x9)
        // sehingga OkHttp akan force-disconnect setelah timeout.
        // Heartbeat sudah ditangani firmware via FRAME_TYPE_HBEAT setiap 10 detik.
        .build()

    // DROP_OLDEST: selalu tampilkan frame terbaru, tidak ada lag buffer
    private val _frameFlow = MutableSharedFlow<ByteArray>(
        replay              = 0,
        extraBufferCapacity = 4,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val frameFlow: SharedFlow<ByteArray> = _frameFlow

    private val _imuFlow = MutableSharedFlow<FloatArray>(
        replay              = 0,
        extraBufferCapacity = 4,  // buffer lebih besar agar tidak drop saat Android busy render
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val imuFlow: SharedFlow<FloatArray> = _imuFlow

    private val _tofFlow = MutableSharedFlow<IntArray>(
        replay              = 0,
        extraBufferCapacity = 4,  // buffer lebih besar agar tidak drop saat Android busy render
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val tofFlow: SharedFlow<IntArray> = _tofFlow

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ── ACTION_STOP: bersihkan semua dan tutup service ──────────────────
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received")
            stopped = true
            
            // Kirim broadcast agar CameraStreamActivity ikut keluar
            runCatching {
                sendBroadcast(Intent(ACTION_EXIT_APP).apply {
                    setPackage(packageName)
                })
            }

            stopStreamAndRelease()
            cancelAllNotifications()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            showStoppedNotification(ipAddress)
            stopSelf()
            return START_NOT_STICKY
        }

        // ── Start/restart service dengan IP ────────────────────────────────
        val ip = intent?.getStringExtra(EXTRA_IP)
        val ipChanged = !ip.isNullOrEmpty() && ip != ipAddress
        if (!ip.isNullOrEmpty()) ipAddress = ip

        if (ipAddress.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        // Reset stopped flag saat service di-start ulang
        stopped = false

        createNotificationChannels()

        if (wakeLock?.isHeld != true) acquireWakeLock()
        if (wifiLock?.isHeld != true) acquireWifiLock()
        if (networkCallback == null) registerNetworkCallback()

        startForeground(NOTIF_ID_FG, buildForegroundNotif())

        // Hanya mulai/restart streaming jika IP berubah atau stream sudah mati
        val streamIsActive = streamJob?.isActive == true
        if (ipChanged || !streamIsActive) {
            Log.d(TAG, "Starting stream to $ipAddress (ipChanged=$ipChanged)")
            startStreaming(ipAddress)
        } else {
            Log.d(TAG, "Stream already active, skip restart")
        }

        return START_STICKY
    }

    fun stopStreamAndRelease() {
        runCatching { streamJob?.cancel() };       streamJob = null
        runCatching { activeWebSocket?.close(1000, "Stopped") }; activeWebSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    private fun cancelAllNotifications() {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID_FG)
            nm.cancel(NOTIF_ID_ALERT)
        }
    }

    // ── Streaming loop ──────────────────────────────────────────────────────

    private fun startStreaming(ip: String) {
        streamJob?.cancel()
        runCatching { activeWebSocket?.cancel() }
        activeWebSocket   = null
        reconnectAttempts = 0

        streamJob = serviceScope.launch {
            while (isActive && !stopped) {
                val done = CompletableDeferred<Unit>()
                setConnectionState(ConnectionState.CONNECTING)
                Log.d(TAG, "Connecting ws://$ip/ws (attempt #${reconnectAttempts + 1})")

                try {
                    client.newWebSocket(
                        Request.Builder().url("ws://$ip/ws").build(),
                        object : WebSocketListener() {

                        override fun onOpen(ws: WebSocket, r: Response) {
                            runCatching {
                                activeWebSocket   = ws
                                reconnectAttempts = 0
                                setConnectionState(ConnectionState.CONNECTED)
                                sendConnectedHeadsUp()
                            }
                        }

                        override fun onMessage(ws: WebSocket, bytes: ByteString) {
                            if (stopped) return
                            runCatching {
                                val raw = bytes.toByteArray()
                                if (raw.size < FRAME_HEADER_SZ) {
                                    Log.w(TAG, "Frame terlalu kecil: ${raw.size}B < $FRAME_HEADER_SZ")
                                    return
                                }
                                val type    = raw[0]
                                val payload = raw.copyOfRange(FRAME_HEADER_SZ, raw.size)

                                // Log setiap tipe frame yang diterima (kecuali JPEG dan IMU yang frekuensinya tinggi)
                                when (type) {
                                    FRAME_TYPE_JPEG -> { /* high freq, no log */ }
                                    FRAME_TYPE_IMU  -> { /* high freq, no log */ }
                                    FRAME_TYPE_TOF  -> Log.d(TAG, "TOF frame diterima: payload=${payload.size}B")
                                    FRAME_TYPE_HBEAT -> Log.d(TAG, "Heartbeat diterima")
                                    else -> Log.w(TAG, "Frame tidak dikenal: type=0x%02X size=${raw.size}B".format(type.toInt() and 0xFF))
                                }

                                serviceScope.launch {
                                    when (type) {
                                        FRAME_TYPE_JPEG -> _frameFlow.emit(payload)
                                        FRAME_TYPE_IMU  -> {
                                            if (payload.size >= 24) {
                                                val floats = FloatArray(6)
                                                java.nio.ByteBuffer.wrap(payload)
                                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                                    .asFloatBuffer().get(floats)
                                                _imuFlow.emit(floats)
                                            }
                                        }
                                        FRAME_TYPE_TOF  -> {
                                            if (payload.size >= 128) {
                                                val ints = IntArray(64)
                                                val buf  = java.nio.ByteBuffer.wrap(payload)
                                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                                    .asShortBuffer()
                                                var allZero = true
                                                
                                                val targetStatus = if (payload.size >= 192) {
                                                    payload.copyOfRange(128, 192)
                                                } else null

                                                for (i in 0 until 64) {
                                                    ints[i] = buf.get(i).toInt() and 0xFFFF
                                                    if (ints[i] == 0 && targetStatus != null) {
                                                        // Jika jarak 0, tampilkan status sensor sebagai nilai negatif
                                                        val status = targetStatus[i].toInt() and 0xFF
                                                        ints[i] = status * -1
                                                    }
                                                    if (ints[i] > 0) allZero = false
                                                }
                                                _tofFlow.emit(ints)
                                                if (allZero) {
                                                    Log.w(TAG, "TOF WARNING: Frame berisi 0 (atau error status) semua!")
                                                }
                                            } else {
                                                Log.e(TAG, "TOF payload terlalu kecil: ${payload.size}B < 128B!")
                                            }
                                        }
                                        // FRAME_TYPE_HBEAT (0x03) diabaikan — sudah cukup sebagai keepalive
                                    }
                                }
                            }
                        }

                        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                            runCatching {
                                Log.e(TAG, "WS failure: ${t.message}")
                                activeWebSocket = null
                                setConnectionState(ConnectionState.DISCONNECTED)
                                if (!done.isCompleted) done.complete(Unit)
                            }
                        }

                        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                            runCatching { ws.close(1000, null) }
                        }

                        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                            runCatching {
                                activeWebSocket = null
                                setConnectionState(ConnectionState.DISCONNECTED)
                                if (!done.isCompleted) done.complete(Unit)
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "WS setup error: ${e.message}")
                    if (!done.isCompleted) done.complete(Unit)
                }

                try { done.await() }
                catch (e: CancellationException) {
                    runCatching { activeWebSocket?.close(1000, "Cancelled") }
                    break
                }

                // Jika stopped atau job sudah di-cancel, keluar loop
                if (!isActive || stopped) break

                // Exponential backoff: 1s → 2s → 4s → 8s (max)
                val wait = minOf(
                    RECONNECT_BASE_MS * (1L shl reconnectAttempts.coerceAtMost(3)),
                    RECONNECT_MAX_MS
                )
                reconnectAttempts++
                Log.d(TAG, "Reconnect in ${wait}ms (attempt ${reconnectAttempts})")
                delay(wait)
            }
        }
    }

    // ── Locks ────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        runCatching {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraStream::WakeLock")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }.onFailure { Log.e(TAG, "WakeLock failed: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "CameraStream::WifiLock")
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.e(TAG, "WifiLock failed: ${it.message}") }
    }

    // ── NetworkCallback ───────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        runCatching {
            val cm  = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available")
                    // Hanya reconnect jika streaming sudah mati dan service belum di-stop
                    if (!stopped && streamJob?.isActive != true && ipAddress.isNotEmpty()) {
                        serviceScope.launch { delay(500); startStreaming(ipAddress) }
                    }
                }
                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost")
                    runCatching { activeWebSocket?.cancel() }
                    activeWebSocket = null
                    setConnectionState(ConnectionState.DISCONNECTED)
                }
            }
            cm.registerNetworkCallback(req, networkCallback!!)
        }.onFailure { Log.e(TAG, "NetworkCallback failed: ${it.message}") }
    }

    // ── Notifikasi ────────────────────────────────────────────────────────────

    private fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_FG, buildForegroundNotif())
        }
    }

    private fun sendConnectedHeadsUp() {
        runCatching {
            val pi = PendingIntent.getActivity(
                this, 0,
                CameraStreamActivity.createIntent(this, ipAddress),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, NOTIF_CH_ALERT)
                .setContentTitle("ESP32-S3 Terkoneksi 🟢")
                .setContentText("Streaming kamera aktif ($ipAddress)")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_ALERT, notif)
        }
    }

    private fun showStoppedNotification(ip: String) {
        if (ip.isEmpty()) return
        runCatching {
            val mainIntent = Intent(this, com.airi.vnetra.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 2,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, NOTIF_CH_FG)
                .setContentTitle("Sesi Kamera Dihentikan")
                .setContentText("Ketuk untuk terhubung kembali ke kamera ESP32-S3")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_STOPPED, notif)
        }
    }

    private fun buildForegroundNotif(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            CameraStreamActivity.createIntent(this, ipAddress),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text) = when (_connectionState.value) {
            ConnectionState.CONNECTED    -> "ESP32-S3 Terkoneksi"  to "Streaming kamera aktif ($ipAddress)"
            ConnectionState.CONNECTING   -> "ESP32-S3 Camera"      to "Menghubungkan ke $ipAddress..."
            ConnectionState.DISCONNECTED -> "ESP32-S3 Camera"      to "Terputus — mencoba reconnect..."
        }

        return NotificationCompat.Builder(this, NOTIF_CH_FG)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Akhiri", stopPi)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            NOTIF_CH_FG, "Camera Stream Status", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(
            NOTIF_CH_ALERT, "ESP32 Connection Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App di-swipe dari recent: service tetap jalan (notifikasi masih ada)
        Log.d(TAG, "Task removed — service tetap jalan di background")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreamAndRelease()
        cancelAllNotifications()
        runCatching { serviceScope.cancel() }
    }
}
