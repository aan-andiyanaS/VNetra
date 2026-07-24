package com.airi.vnetra.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/**
 * TtsAlertManager — Peringatan Objek Statis One-Shot (v9.4)
 *
 * Logika one-shot per tracking ID:
 *   - Kondisi trigger: d_obj < D_W0 (1000 mm) DAN flag[id] == false
 *     → suarakan peringatan, set flag[id] = true
 *   - Kondisi reset: d_obj > D_RESET (1500 mm) DAN flag[id] == true
 *     → reset flag[id] = false (objek sudah pergi dari zona bahaya)
 *
 * Hysteresis D_RESET = D_W0 + EPS_NOISE mencegah flag reset-set-reset
 * berulang saat d_obj berfluktuasi tepat di batas 1000 mm.
 *
 * Juga berperan sebagai TTS engine yang digunakan Formula J (terrain alert).
 *
 * Untuk Tahap 1 (tanpa YOLO): tracking ID = indeks kolom ToF sebagai proxy.
 * Untuk Tahap 2 (dengan YOLO): tracking ID = YOLO tracking ID per objek.
 *
 * Referensi: formula-matematis-v9.4.md §H
 */
class TtsAlertManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsAlertManager"

        // Konstanta Sistem (§H + §G)
        const val D_W0      = 1000  // mm — threshold jarak aman minimum
        // ADR-035 (Hysteresis): EPS_NOISE diperlebar ke 500mm berdasarkan literatur mekatronika.
        // Margin 500mm >> noise ToF (±5–15% ≈ ±50–150mm pada jarak 1000mm), sehingga
        // bacaan sensor yang berosilasi di sekitar threshold tidak dapat mereset flag peringatan.
        // Referensi: VL53L1X App Note, PMC8196976 (hysteresis dual-threshold recommendation).
        const val EPS_NOISE      = 500   // mm — hysteresis margin untuk D_RESET (noise ToF guard)
        const val EPS_CLEAR_ZONE = 150   // mm — zona abu-abu untuk allClear (terpisah dari D_RESET)
        const val D_RESET        = D_W0 + EPS_NOISE  // 1500 mm — batas reset flag
    }

    // State one-shot per tracking ID (atau kolom ToF sebagai proxy di Tahap 1).
    // ConcurrentHashMap aman untuk akses concurrent dari coroutine ToF dan IMU.
    private val alertFlags = ConcurrentHashMap<Int, Boolean>()

    // Waktu terakhir setiap tracking ID terdeteksi aktif di zona bahaya
    private val lastSeenTime = ConcurrentHashMap<Int, Long>()

    // Waktu terakhir setiap tracking ID diucapkan (diperingatkan)
    private val lastSpokenTime = ConcurrentHashMap<Int, Long>()

    // State Formula G (Per Tracking ID)
    private val dObjPrev = ConcurrentHashMap<Int, Int>()
    private val tsEspPrev = ConcurrentHashMap<Int, Float>()
    private val vRawHistory = ConcurrentHashMap<Int, FloatArray>()
    private val lastCalculatedT = ConcurrentHashMap<Int, Int>()

    fun getAdaptiveThreshold(trackingId: Int): Int = lastCalculatedT[trackingId] ?: D_W0



    // TTS Engine
    private var tts: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)
    private val isInitialized: Boolean get() = ttsReady.get()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ttsFlow = MutableSharedFlow<String>(extraBufferCapacity = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    
    @Volatile
    var isMuted: Boolean = false

    // A2DP Keep-Alive (Zero Wake-Up Delay)
    private var silentAudioTrack: AudioTrack? = null

    /**
     * Inisialisasi TextToSpeech engine.
     * Panggil dari onCreate() — async, tidak memblokir UI thread.
     */
    fun initTts() {
        if (ttsReady.get()) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Konfigurasi prioritas aksesibilitas (anti-ducking)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)

                // Coba set Bahasa Indonesia; fallback ke locale default jika tidak tersedia
                val result = tts?.setLanguage(Locale("id", "ID"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                    Log.w(TAG, "TTS: Bahasa Indonesia tidak tersedia, fallback ke ${Locale.getDefault()}")
                }
                tts?.setSpeechRate(1.6f)   // Dipercepat menjadi 1.6f agar lebih responsif
                ttsReady.set(true)
                Log.d(TAG, "TTS engine siap")

                // Mulai mekanisme A2DP Keep-Alive agar Bluetooth tidak masuk mode sleep/sniff
                startA2dpKeepAlive(audioAttributes)

                // Listener flow TTS
                scope.launch {
                    ttsFlow.collect { text ->
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vnetra_${System.currentTimeMillis()}")
                    }
                }
            } else {
                Log.e(TAG, "TTS init gagal: status=$status")
            }
        }
    }

    /**
     * Memutar loop audio senyap untuk memaksa headset Bluetooth (A2DP) tetap dalam status Active.
     * Mencegah "wake-up delay" 500-1000ms yang memotong kata pertama peringatan.
     */
    private fun startA2dpKeepAlive(attributes: AudioAttributes) {
        try {
            val sampleRate = 16000
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // Gunakan MODE_STATIC untuk me-loop buffer pendek secara kontinu tanpa membebani CPU
            silentAudioTrack = AudioTrack(
                attributes,
                format,
                minBufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            val silentBuffer = ShortArray(minBufferSize / 2) // Default berisi nilai 0 (senyap)
            silentAudioTrack?.write(silentBuffer, 0, silentBuffer.size)
            silentAudioTrack?.setLoopPoints(0, silentBuffer.size, -1) // -1 berarti infinite loop
            silentAudioTrack?.play()
            
            Log.d(TAG, "A2DP Keep-Alive berhasil diaktifkan")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mengaktifkan A2DP Keep-Alive", e)
        }
    }

    /**
     * Proses satu objek: periksa kondisi one-shot dan kembalikan teks peringatan jika perlu.
     *
     * Dipanggil dari tofCollectJob (Dispatchers.Default) atau triggerInstantYoloTts.
     *
     * @param trackingId ID unik objek — kolom ToF [0..7] di Tahap 1, YOLO ID di Tahap 2
     * @param dObj       jarak objek (mm) dari Formula E ∈ [EPS_NOISE, D_MAX]
     * @param clockDirection arah jam dari Formula C ∈ {10, 11, 12, 1, 2}
     * @param objectLabel label objek untuk TTS (default "rintangan")
     * @return String peringatan jika baru dipicu pada frame ini, atau null jika tidak perlu bersuara
     */
    fun process(
        trackingId: Int,
        dObj: Int,
        clockDirection: Int,
        objectLabel: String = "rintangan",
        isMovingForward: Boolean = true,
        imuData: FloatArray? = null
    ): String? {
        val alreadyAlerted = alertFlags[trackingId] ?: false
        val now = System.currentTimeMillis()

        // =================================================================
        // FORMULA G: Adaptive Threshold & Approach Velocity (v9.4)
        // =================================================================
        var T = D_W0 // Default threshold
        var vAvg = 0f 
        
        if (imuData != null && imuData.size >= 9) {
            val tsEsp = imuData[6]
            val vHeadBase = imuData[7]
            val isConverged = imuData[8] > 0.5f
            
            if (isConverged) {
                val dPrev = dObjPrev[trackingId]
                val tsPrev = tsEspPrev[trackingId]
                
                if (dPrev != null && tsPrev != null && tsEsp != tsPrev) {
                    // G.0: Guard Interval Waktu (Δt)
                    var dt = (tsEsp - tsPrev) / 1000f
                    if (dt < 0.001f) dt = 0.001f
                    if (dt > 0.5f) dt = 0.5f
                    
                    // G.1b: Kecepatan semu (v_head)
                    val vHead = vHeadBase * dObj
                    
                    // G.2: Kecepatan pendekatan bersih (v_raw)
                    var vRaw = ((dPrev - dObj) / dt) - vHead
                    if (vRaw < 0f) vRaw = 0f
                    
                    // FUSI ACCEL: Noise Gate untuk Objek Statis (Ponytail ADR-017)
                    val aLin = imuData[5]
                    val isPavingObj = objectLabel.startsWith("paving")
                    val isStaticObject = trackingId == SpatialMappingUtils.WALL_TRACKING_ID || trackingId == SpatialMappingUtils.TERRAIN_TRACKING_ID || isPavingObj
                    if (isStaticObject && aLin < 2.94f) {
                        vRaw = 0f // Pengguna diam, kecepatan objek statis pasti noise
                    }
                    
                    // G.2b: Moving Average 3-frame
                    val history = vRawHistory.getOrPut(trackingId) { FloatArray(3) }
                    history[2] = history[1]
                    history[1] = history[0]
                    history[0] = vRaw
                    
                    val validCount = history.count { it > 0f } // simplified check for initialized
                    vAvg = if (validCount > 0) {
                        (history[0] + (if (history[1] > 0f) history[1] else history[0]) + (if (history[2] > 0f) history[2] else history[0])) / 3f
                    } else vRaw
                    
                    // G.3: Threshold Adaptif (T)
                    val tR = 2.0f // Waktu reaksi manusia 2 detik
                    val momentumBuffer = imuData[5] * 200f // Tambahan jarak berdasarkan akselerasi linear tubuh
                    T = (D_W0 + (vAvg * tR) + momentumBuffer).toInt()
                    if (T > 4000) T = 4000
                    
                    Log.v(TAG, "Formula G [id=$trackingId]: dt=${String.format("%.3f", dt)} vRaw=${String.format("%.1f", vRaw)} vAvg=${String.format("%.1f", vAvg)} T=$T")
                }
                
                // Update State Formula G
                dObjPrev[trackingId] = dObj
                tsEspPrev[trackingId] = tsEsp
            } else {
                // Mahony filter belum konvergen, lewati kalkulasi G (T = D_W0)
                Log.v(TAG, "Formula G [id=$trackingId]: Mahony warming up, T=$D_W0")
            }
        }
        lastCalculatedT[trackingId] = T
        // =================================================================


        val isPaving = objectLabel.startsWith("paving")

        // ADR-039: Tambahan kata "mendekat" jika objek secara nyata bergerak maju ke arah pengguna (vAvg > 500 mm/s)
        val finalLabel = if (!isPaving && vAvg > 500f) "$objectLabel mendekat" else objectLabel
        
        val dirText  = SpatialMappingUtils.clockDirectionToTts(clockDirection)
        
        // Konversi jarak ke kategori adaptif (proporsional terhadap T Formula G)
        val distText = when {
            dObj < T * 0.5 -> "jarak dekat"
            dObj < T * 1.5 -> "jarak sedang"
            else -> "jarak jauh"
        }

        // Jika objek adalah paving dan jaraknya dekat, hiraukan sebut jarak
        val textToSpeak = if (isPaving && dObj < 500) {
            "$finalLabel, $dirText"
        } else {
            "$finalLabel, $distText, $dirText"
        }

        return when {
            dObj < T && !alreadyAlerted -> {
                // ADR-035 (Ponytail): Blokir peringatan BARU apa pun saat kepala berotasi.
                // Jika user menunduk, lantai akan terbaca < T. Jika user menoleh, objek di pinggir
                // akan masuk. Semua ini memicu TTS palsu. Blokir sejak dini.
                val pitchRate = imuData?.getOrElse(2) { 0f } ?: 0f
                val rollRate  = imuData?.getOrElse(3) { 0f } ?: 0f
                val yawRateImu = imuData?.getOrElse(4) { 0f } ?: 0f
                val isHeadRotatingNow = kotlin.math.abs(pitchRate) > 5f ||
                    kotlin.math.abs(yawRateImu) > 5f ||
                    kotlin.math.abs(rollRate) > 5f
                
                if (isHeadRotatingNow) return null
                
                // Ponytail Pitch Bug Fix: 
                // Jika pengguna menunduk tajam (pitch > 20 derajat), ToF akan mengenai lantai di dekat kaki (< 1000mm).
                // Abaikan peringatan tembok statis dalam kondisi ini agar tidak diteriaki "Tembok" saat melihat sepatu.
                val pitchAngle = imuData?.getOrElse(0) { 0f } ?: 0f
                val isStaticObst = trackingId == SpatialMappingUtils.WALL_TRACKING_ID || 
                                   trackingId == SpatialMappingUtils.TERRAIN_TRACKING_ID
                if (isStaticObst && pitchAngle > 20f) return null
                
                // ADR-035: Cooldown minimum antar re-trigger untuk tracking ID yang sama.
                // Mencegah siklus rapid-fire: nod-down (fake close) → nod-up (flag reset) → nod-down lagi.
                val lastSpokenMs = lastSpokenTime[trackingId] ?: 0L
                if (now - lastSpokenMs < 3000L) return null
                
                // Mute Bug Fix (Amnesia Sementara):
                // Jika TTS di-mute, JANGAN hafalkan rintangan ini. 
                // Saat auto-unmute dipicu (karena berjalan), sistem akan menganggap objek ini baru dan langsung mengingatkan.
                if (isMuted) return null

                // Ponytail Spam Fix (Zero-Delay Responsive Scanning):
                // Jika objek statis (Tembok/Terrain) DAN user sedang diam (!isMovingForward):
                // Tunda pencatatan ke memori. Sistem tetap bisu sampai user mengambil langkah maju, 
                // yang akan memicu alert instan tanpa delay 1 detik.
                val isStaticObstacle = trackingId == SpatialMappingUtils.WALL_TRACKING_ID || 
                                       trackingId == SpatialMappingUtils.TERRAIN_TRACKING_ID
                if (isStaticObstacle && !isMovingForward) return null

                // Kondisi: masuk zona bahaya, belum pernah diperingatkan -> one-shot
                alertFlags[trackingId] = true
                lastSeenTime[trackingId] = now
                lastSpokenTime[trackingId] = now
                Log.d(TAG, "One-shot triggered: id=$trackingId d=${dObj}mm dir=$clockDirection")
                textToSpeak
            }
            dObj < T && alreadyAlerted -> {
                // Objek masih di zona bahaya
                lastSeenTime[trackingId] = now
                val lastSpoken = lastSpokenTime[trackingId] ?: 0L
                
                // Formula H Asli: Reset jika bergerak signifikan (ADR-018)
                val deltaD = kotlin.math.abs((dObjPrev[trackingId] ?: dObj) - dObj)
                var isMoving = deltaD > 30 // epsilon_noise
                
                // PONYTAIL FIX (ADR-034):
                // Jika pengguna secara fisik TIDAK sedang berjalan maju, JANGAN pernah me-reset peringatan.
                // Ini secara tuntas mencegah false-positive saat mengangguk/mendongak (rotasi kepala)
                // yang memicu sweeping proyektif pada jarak absolut ToF dan mengorupsi kecepatan sintetis (vAvg).
                if (!isMovingForward) {
                    isMoving = false
                }
                
                // Jika bergerak dan sudah 2 detik sejak peringatan terakhir (Ponytail Cooldown)
                if (isMoving && (now - lastSpoken > 2000L)) {
                    alertFlags[trackingId] = false // Trigger reset!
                    Log.d(TAG, "Formula H Reset: Objek $trackingId bergerak (delta=$deltaD)")
                    return null
                }
                
                val isWall = trackingId == SpatialMappingUtils.WALL_TRACKING_ID || trackingId == SpatialMappingUtils.TERRAIN_TRACKING_ID
                
                if (isWall && isMovingForward) {
                    val lastSpoken = lastSpokenTime[trackingId] ?: 0L
                    if (now - lastSpoken > 1000L) { // Spam tiap 1 detik jika terus bergerak maju ke tembok
                        lastSpokenTime[trackingId] = now
                        Log.d(TAG, "Wall Spam (Moving Forward): id=$trackingId")
                        return textToSpeak
                    }
                }
                
                // Stationary Paving Reminder
                if (isPaving && !isMovingForward) {
                    val lastSpoken = lastSpokenTime[trackingId] ?: 0L
                    if (now - lastSpoken > 6000L) {
                        lastSpokenTime[trackingId] = now
                        Log.d(TAG, "Stationary Paving Reminder: id=$trackingId")
                        return textToSpeak
                    }
                }
                null
            }
            dObj > D_RESET && alreadyAlerted -> {
                // ADR-035: Jangan reset flag saat kepala sedang berotasi.
                val pitchRate = imuData?.getOrElse(2) { 0f } ?: 0f
                val rollRate  = imuData?.getOrElse(3) { 0f } ?: 0f
                val yawRateImu = imuData?.getOrElse(4) { 0f } ?: 0f
                val isHeadRotatingNow = kotlin.math.abs(pitchRate) > 10f ||
                    kotlin.math.abs(yawRateImu) > 10f ||
                    kotlin.math.abs(rollRate) > 10f
                if (!isHeadRotatingNow) {
                    // ADR-035 (fix final): Untuk rintangan STATIS (tembok/terrain), hanya reset flag
                    // jika pengguna benar-benar berjalan menjauh (isMovingForward=true).
                    // Ini mencegah noise ToF (dObj osilasi di sekitar D_RESET=1150mm saat diam)
                    // dari menyebabkan flag reset dan memicu peringatan ulang berulang-ulang.
                    val isStaticObstacle = trackingId == SpatialMappingUtils.WALL_TRACKING_ID ||
                        trackingId == SpatialMappingUtils.TERRAIN_TRACKING_ID
                    val shouldReset = !isStaticObstacle || isMovingForward
                    if (shouldReset) {
                        alertFlags[trackingId] = false
                        Log.d(TAG, "Flag reset (D_RESET): id=$trackingId d=${dObj}mm moving=$isMovingForward")
                    }
                }
                null
            }
            else -> null
        }
    }

    /**
     * Lakukan pembersihan berkala terhadap flag one-shot untuk objek YOLO yang tidak terdeteksi.
     * Jika suatu objek terdeteksi aktif sebelumnya tetapi sekarang tidak terdeteksi selama > 3 detik,
     * reset flag one-shot objek tersebut agar siap dideteksi lagi.
     *
     * @param activeClasses Set ID kelas objek YOLO yang terdeteksi aktif pada frame ini.
     */
    fun postProcessDetections(activeClasses: Set<Int>) {
        val now = System.currentTimeMillis()

        for (classId in activeClasses) {
            lastSeenTime[classId] = now
        }

        for ((trackingId, alerted) in alertFlags) {
            // Kita tidak me-reset WALL_TRACKING_ID di sini karena reset tembok
            // dikendalikan langsung oleh jarak ToF di tofCollectJob.
            if (alerted && trackingId != SpatialMappingUtils.WALL_TRACKING_ID) {
                val lastSeen = lastSeenTime[trackingId] ?: 0L
                if (now - lastSeen > 3000L) {
                    alertFlags[trackingId] = false
                    dObjPrev.remove(trackingId)
                    tsEspPrev.remove(trackingId)
                    vRawHistory.remove(trackingId)
                    lastCalculatedT.remove(trackingId)
                    Log.d(TAG, "Reset flag & Formula G state untuk trackingId=$trackingId karena absensi (>3s)")
                }
            }
        }
    }

    /**
     * Suarakan teks (ganti antrian yang sedang berjalan).
     * Gunakan untuk peringatan prioritas tinggi (HIGH).
     *
     * @param text teks Bahasa Indonesia yang akan disuarakan
     */
    fun speak(text: String) {
        if (isMuted || !isInitialized) return
        scope.launch { ttsFlow.emit(text) }
    }

    /**
     * Suarakan teks (tambahkan ke antrian, tidak mengganggu yang sedang berjalan).
     * Gunakan untuk peringatan prioritas normal (MEDIUM/INFO).
     *
     * @param text teks Bahasa Indonesia yang akan disuarakan
     */
    fun speakAdd(text: String) {
        if (isMuted || !isInitialized) return
        if (tts?.isSpeaking == true) return
        scope.launch { ttsFlow.emit(text) }
    }

    /**
     * Memaksa peringatan bersuara meskipun dalam status isMuted = true.
     * Digunakan khusus untuk konfirmasi status Mute ON/OFF.
     */
    fun speakForce(text: String) {
        if (!isInitialized) return
        scope.launch { ttsFlow.emit(text) }
    }

    /**
     * Hapus semua flag one-shot.
     * Panggil saat koneksi ESP32 terputus, resolusi ToF berubah, atau activity restart.
     */
    fun resetAllFlags() {
        alertFlags.clear()
        lastSeenTime.clear()
        lastSpokenTime.clear()
        Log.d(TAG, "Semua flag one-shot di-reset (${alertFlags.size} entries)")
    }

    /**
     * Hentikan TTS yang sedang berjalan (tidak shutdown engine).
     * Panggil saat koneksi putus agar tidak ada suara yang menggantung.
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    /**
     * Bersihkan semua resource. Panggil dari onDestroy().
     */
    fun shutdown() {
        silentAudioTrack?.stop()
        silentAudioTrack?.release()
        silentAudioTrack = null
        
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady.set(false)
        alertFlags.clear()
        lastSeenTime.clear()
        lastSpokenTime.clear()
        Log.d(TAG, "TTS engine shutdown")
    }

    // =========================================================================
    // SMART NAVIGATION TTS
    // =========================================================================

    enum class NavState {
        PATH_CLEAR, WALL_WARNING
    }

    /**
     * Smart Navigation TTS: State Machine untuk navigasi cerdas
     * (mempertimbangkan user diam, maju, dan menengok)
     */
    inner class SmartNavigationTts {

        private var currentState = NavState.PATH_CLEAR
        private var lastWarningTime = 0L
        private var lastClearTime = System.currentTimeMillis()
        private var hasGivenSecondClearWarning = true
        private var clearCandidateTime = 0L

        /**
         * Panggil setiap kali ada pembaruan data ToF dan IMU.
         *
         * @param isDanger true jika ToF mendeteksi halangan signifikan (Kuning/Merah)
         * @param isMovingForward true jika kecepatan maju (v_head_base) cukup besar
         * @param isTurning true jika kecepatan menengok (yaw rate) cukup besar
         * @param isHeadRotating true jika kepala sedang berotasi (pitch/yaw/roll > 10 deg/s)
         */
        fun processNavigationState(
            isDanger: Boolean,
            isMovingForward: Boolean,
            isTurning: Boolean,
            isHeadRotating: Boolean = false
        ) {
            val now = System.currentTimeMillis()

            if (isDanger) {
                clearCandidateTime = 0L // Reset candidate timer jika halangan muncul lagi
                
                // KONDISI: Deteksi Tembok (WARNING)
                if (currentState == NavState.PATH_CLEAR) {
                    // ADR-035: Blokir transisi BARU PATH_CLEAR→WALL_WARNING saat kepala berotasi.
                    // Ini mencegah false-alarm "Awas tembok" akibat floor-sweep (lantai terbaca
                    // sebagai rintangan saat sensor menyapu saat mengangguk/menoleh).
                    // Jika kepala diam sebentar dan tembok masih ada, transisi akan terjadi normal.
                    if (isHeadRotating) return
                    
                    // Transisi dari CLEAR ke WARNING
                    currentState = NavState.WALL_WARNING
                    lastWarningTime = now
                    
                    if (!isTurning) {
                        // Jika tidak menengok, peringatkan seketika
                        speak("Awas, tembok di depan")
                    } else {
                        // Jika sedang menengok mencari jalan, tetap diam agar tidak spam
                    }
                } else {
                    // Tetap di WALL_WARNING — peringatan keselamatan tetap aktif walau kepala bergerak
                    if (isMovingForward && !isTurning) {
                        // User memaksakan maju ke arah tembok → Beri peringatan berulang (tiap 1 detik)
                        if (now - lastWarningTime > 1000L) {
                            speak("Awas, masih ada tembok")
                            lastWarningTime = now
                        }
                    }
                    // Jika user diam atau sedang menengok → Diam (tidak ada bahaya mendesak / sedang proses cari jalan)
                }
            } else {
                // KONDISI: Jalan Kosong (CLEAR)
                // ADR-035: Blokir transisi CLEAR saat kepala berotasi.
                // Saat menunduk, lantai menghilang → dObj naik → isDanger=false palsu
                // → "Jalan di depan kosong" padahal masih ada tembok nyata.
                if (isHeadRotating) return
                
                if (currentState == NavState.WALL_WARNING) {
                    // Transisi dari WARNING ke CLEAR (user berhasil menemukan jalan kosong)
                    // IMPLEMENTASI DELAY (ADR-031):
                    if (clearCandidateTime == 0L) {
                        clearCandidateTime = now // Mulai menghitung durasi jalan kosong
                    } else if (now - clearCandidateTime > 100L) { // Sangat responsif (0.1 detik)
                        currentState = NavState.PATH_CLEAR
                        lastClearTime = now
                        hasGivenSecondClearWarning = false
                        clearCandidateTime = 0L
                        
                        // Langsung beritahu bahwa jalan kosong 1 kali
                        speak("Jalan di depan kosong")
                    }
                } else {
                    // Tetap di PATH_CLEAR
                    clearCandidateTime = 0L // Pastikan clear

                    if (!isMovingForward) {
                        // User masih ragu / belum maju setelah beberapa waktu (misal 6 detik)
                        if (!hasGivenSecondClearWarning && now - lastClearTime > 6000L) {
                            speak("Jalan aman, silakan maju")
                            hasGivenSecondClearWarning = true // Jaminan tidak ada spam lagi selama user diam
                        }
                    } else {
                        // User sedang maju di jalan kosong → Diam (kondisi ideal, no spam)
                        lastClearTime = now
                        hasGivenSecondClearWarning = false
                    }
                }
            }
        }
        
        fun resetState() {
            currentState = NavState.PATH_CLEAR
            lastWarningTime = 0L
            lastClearTime = System.currentTimeMillis()
            hasGivenSecondClearWarning = true
            clearCandidateTime = 0L
        }
    }

    val smartNavigation = SmartNavigationTts()
}
