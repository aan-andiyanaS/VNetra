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

/**
 * TtsAlertManager — Peringatan Objek Statis One-Shot (v9.4)
 *
 * Logika one-shot per tracking ID:
 *   - Kondisi trigger: d_obj < D_W0 (1000 mm) DAN flag[id] == false
 *     → suarakan peringatan, set flag[id] = true
 *   - Kondisi reset: d_obj > D_RESET (1030 mm) DAN flag[id] == true
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
        const val EPS_NOISE = 150   // mm — noise floor ToF untuk hysteresis reset (diperlebar untuk stabilitas)
        const val D_RESET   = D_W0 + EPS_NOISE  // 1150 mm — batas reset flag
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
                    val isPavingObj = objectLabel in listOf("lurus", "belok", "simpang 3", "simpang 4", "stop")
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
                    val vAvg = if (validCount > 0) {
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


        // Aturan khusus untuk Paving (Guiding Block)
        val isPaving = objectLabel in listOf("lurus", "belok", "simpang 3", "simpang 4", "stop")
        val finalLabel = if (isPaving) "paving $objectLabel" else objectLabel
        
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
                // Kondisi: masuk zona bahaya, belum pernah diperingatkan → one-shot
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
                
                // Mencegah ToF noise mereset one-shot saat pengguna diam (ADR-030 revision)
                val isStaticObjectH = trackingId == SpatialMappingUtils.WALL_TRACKING_ID || trackingId == SpatialMappingUtils.TERRAIN_TRACKING_ID || isPaving
                if (isStaticObjectH && imuData != null && imuData[5] < 2.94f) {
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
                    if (now - lastSpoken > 1500L) { // Spam tiap 1.5 detik jika terus bergerak maju ke tembok
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
                // Kondisi: objek pergi dari zona bahaya → reset flag (siap diperingatkan lagi)
                alertFlags[trackingId] = false
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
        if (!ttsReady.get() || tts == null) {
            Log.w(TAG, "TTS belum siap, skip: \"$text\"")
            return
        }
        val uid = "vnetra_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
    }

    /**
     * Suarakan teks (tambahkan ke antrian, tidak mengganggu yang sedang berjalan).
     * Gunakan untuk peringatan prioritas normal (MEDIUM/INFO).
     *
     * @param text teks Bahasa Indonesia yang akan disuarakan
     */
    fun speakAdd(text: String) {
        if (!ttsReady.get() || tts == null) return
        val uid = "vnetra_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, uid)
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
         */
        fun processNavigationState(isDanger: Boolean, isMovingForward: Boolean, isTurning: Boolean) {
            val now = System.currentTimeMillis()

            if (isDanger) {
                clearCandidateTime = 0L // Reset candidate timer jika halangan muncul lagi
                
                // KONDISI: Deteksi Tembok (WARNING)
                if (currentState == NavState.PATH_CLEAR) {
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
                    // Tetap di WALL_WARNING
                    if (isMovingForward && !isTurning) {
                        // User memaksakan maju ke arah tembok -> Beri peringatan berulang (tiap 3 detik)
                        if (now - lastWarningTime > 3000L) {
                            speak("Awas, masih ada tembok")
                            lastWarningTime = now
                        }
                    }
                    // Jika user diam atau sedang menengok -> Diam (tidak ada bahaya mendesak / sedang proses cari jalan)
                }
            } else {
                // KONDISI: Jalan Kosong (CLEAR)
                if (currentState == NavState.WALL_WARNING) {
                    // Transisi dari WARNING ke CLEAR (user berhasil menemukan jalan kosong saat menengok)
                    // IMPLEMENTASI DELAY (ADR-031):
                    if (clearCandidateTime == 0L) {
                        clearCandidateTime = now // Mulai menghitung durasi jalan kosong
                    } else if (now - clearCandidateTime > 500L) { // Harus konstan 0.5 detik
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
                        // User sedang maju di jalan kosong -> Diam (kondisi ideal, no spam)
                        // Perbarui waktu clear dan reset flag agar siap memperingatkan lagi jika user tiba-tiba berhenti lama
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
