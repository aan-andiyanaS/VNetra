package com.airi.vnetra.util

import android.content.Context
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
        const val EPS_NOISE = 30    // mm — noise floor ToF untuk hysteresis reset
        const val D_RESET   = D_W0 + EPS_NOISE  // 1030 mm — batas reset flag
    }

    // State one-shot per tracking ID (atau kolom ToF sebagai proxy di Tahap 1).
    // ConcurrentHashMap aman untuk akses concurrent dari coroutine ToF dan IMU.
    private val alertFlags = ConcurrentHashMap<Int, Boolean>()

    // TTS Engine
    private var tts: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)

    /**
     * Inisialisasi TextToSpeech engine.
     * Panggil dari onCreate() — async, tidak memblokir UI thread.
     */
    fun initTts() {
        if (ttsReady.get()) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Coba set Bahasa Indonesia; fallback ke locale default jika tidak tersedia
                val result = tts?.setLanguage(Locale("id", "ID"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                    Log.w(TAG, "TTS: Bahasa Indonesia tidak tersedia, fallback ke ${Locale.getDefault()}")
                }
                tts?.setSpeechRate(1.3f)   // Dipercepat menjadi 1.3f agar penyampaian instruksi lebih responsif
                ttsReady.set(true)
                Log.d(TAG, "TTS engine siap")
            } else {
                Log.e(TAG, "TTS init gagal: status=$status")
            }
        }
    }

    /**
     * Proses satu objek: periksa kondisi one-shot dan bunyikan peringatan jika perlu.
     *
     * Dipanggil dari tofCollectJob (Dispatchers.Default) — pastikan speak() aman dipanggil
     * dari non-Main thread (TTS.speak() memang thread-safe di Android).
     *
     * @param trackingId ID unik objek — kolom ToF [0..7] di Tahap 1, YOLO ID di Tahap 2
     * @param dObj       jarak objek (mm) dari Formula E ∈ [EPS_NOISE, D_MAX]
     * @param clockDirection arah jam dari Formula C ∈ {10, 11, 12, 1, 2}
     * @param objectLabel label objek untuk TTS (default "rintangan")
     * @return true jika peringatan baru dibunyikan pada frame ini
     */
    fun process(
        trackingId: Int,
        dObj: Int,
        clockDirection: Int,
        objectLabel: String = "rintangan"
    ): Boolean {
        val alreadyAlerted = alertFlags[trackingId] ?: false

        return when {
            dObj < D_W0 && !alreadyAlerted -> {
                // Kondisi: masuk zona bahaya, belum pernah diperingatkan → one-shot
                alertFlags[trackingId] = true
                val dirText  = SpatialMappingUtils.clockDirectionToTts(clockDirection)
                val distCm   = dObj / 10  // mm → cm (lebih natural untuk TTS)
                speak("$objectLabel, $distCm sentimeter, $dirText")
                Log.d(TAG, "One-shot: id=$trackingId d=${dObj}mm dir=$clockDirection")
                true
            }
            dObj > D_RESET && alreadyAlerted -> {
                // Kondisi: objek pergi dari zona bahaya → reset flag (siap diperingatkan lagi)
                alertFlags[trackingId] = false
                false
            }
            else -> false
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
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady.set(false)
        alertFlags.clear()
        Log.d(TAG, "TTS engine shutdown")
    }
}
