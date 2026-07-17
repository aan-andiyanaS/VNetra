# ADR-036: Mode Pengumpulan Dataset Langsung dari Kamera ESP32

## Status
Proposed

## Date
2026-07-17

## Context
Aplikasi VNetra menerima aliran video (*stream*) dalam bentuk array byte JPEG mentah yang langsung dipaketkan oleh hardware kamera ESP32. Pengguna sering kali membutuhkan gambar dari dunia nyata untuk melatih ulang (retraining) model AI di kemudian hari. Oleh karena itu, kita membutuhkan fitur untuk mengambil gambar secara otomatis setiap beberapa detik dari stream yang sedang berjalan. Namun, pada saat mode ini diaktifkan, semua fungsi *Text-to-Speech* (TTS) harus dimatikan agar pengguna dapat fokus, dan fungsi penyimpanan tidak boleh membuat tampilan video patah-patah (*lag*).

## Decision (Doubt-Driven & Ponytail Full)
Alih-alih mengambil `Bitmap` lalu me-*re-encode*-nya kembali menjadi JPEG (yang akan boros CPU dan membuat *lag*), kita mengadopsi prinsip **Ponytail**: ambil solusi paling gampang dan *native*. 
Kita akan mencegat `jpegBytes` bawaan dari WebSocket, lalu menuliskannya secara *asynchronous* langsung ke disk menggunakan `FileOutputStream` murni di `Dispatchers.IO`.

### 1. Komponen `DatasetManager.kt`
Ini adalah *helper* sekali pakai dengan fokus hanya menulis data (*Single Responsibility Principle*). Letakkan di direktori `util/`.

```kotlin
// file:///e:/Project/Skripsi/VNetra/app/src/main/java/com/airi/vnetra/util/DatasetManager.kt
package com.airi.vnetra.util

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatasetManager(private val context: Context) {
    private var lastSavedTime = 0L
    private val intervalMs = 3000L // 3 Detik
    
    private var currentCount = -1
    private val MAX_DATASET_COUNT = 1500
    private var limitReached = false

    // ponytail: Cukup gunakan storage external public agar gambar gampang disalin ke PC
    private val storageDir: File? by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "dataset esp32")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    suspend fun saveFrameIfNeeded(jpegBytes: ByteArray) {
        if (limitReached) return

        val now = System.currentTimeMillis()
        if (now - lastSavedTime < intervalMs) return
        lastSavedTime = now

        withContext(Dispatchers.IO) {
            val dir = storageDir ?: return@withContext

            // Hitung jumlah file yang sudah ada sekali saja di awal (Ponytail Lazy Evaluation)
            if (currentCount == -1) {
                currentCount = dir.listFiles { file -> file.isFile && file.extension == "jpg" }?.size ?: 0
            }

            if (currentCount >= MAX_DATASET_COUNT) {
                limitReached = true
                Log.d("DatasetManager", "Batas dataset ($MAX_DATASET_COUNT) tercapai. Pengambilan dihentikan.")
                return@withContext
            }

            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "IMG_${timestamp}.jpg")
                
                FileOutputStream(file).use { output ->
                    output.write(jpegBytes)
                }
                
                currentCount++
                if (currentCount >= MAX_DATASET_COUNT) {
                    limitReached = true
                }
                
                Log.d("DatasetManager", "Saved: ${file.absolutePath} ($currentCount/$MAX_DATASET_COUNT)")
            } catch (e: Exception) {
                Log.e("DatasetManager", "Gagal menyimpan frame", e)
            }
        }
    }
}
```

### 2. Modifikasi UI (`activity_camera_stream.xml`)
Tambahkan ToggleButton di kontainer yang berisikan tombol *Akhiri*.

```xml
<!-- Modifikasi pada layoutControls, letakkan sebelum btnAkhiri -->
<ToggleButton
    android:id="@+id/btnModeDataset"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:textOn="Dataset: ON"
    android:textOff="Dataset: OFF"
    android:textColor="#FFFFFF"
    android:backgroundTint="#FF9800"
    android:textSize="11sp" />
```

### 3. Modifikasi Logika Utama (`CameraStreamActivity.kt`)
Integrasikan manajer baru dan amankan logika pematian suara TTS.

```kotlin
// ... Pada bagian atas deklarasi kelas ...
private lateinit var datasetManager: DatasetManager
private var isDatasetModeActive = false

// ... Di dalam fungsi onCreate() ...
datasetManager = DatasetManager(this)

binding.btnModeDataset.setOnCheckedChangeListener { _, isChecked ->
    isDatasetModeActive = isChecked
    if (::ttsAlertManager.isInitialized) {
        ttsAlertManager.isMuted = isChecked // Langsung nonaktifkan / aktifkan TTS
        if (isChecked) {
            Toast.makeText(this, "Mode Dataset Aktif. TTS dimatikan.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Mode Dataset Nonaktif. TTS menyala kembali.", Toast.LENGTH_SHORT).show()
        }
    }
}

// ... Di dalam fungsi startCollectingFrames(), pada loop pengumpulan svc.frameFlow.collect ...
svc.frameFlow.collect { jpegBytes ->
    if (isDestroyed || isFinishing || isAkhiring) return@collect
    
    // --> TAMBAHKAN BLOK INI <--
    if (isDatasetModeActive) {
        datasetManager.saveFrameIfNeeded(jpegBytes)
    }

    val startTime = System.currentTimeMillis()
// ... Lanjut decode Bitmap seperti biasa ...
```

## Consequences
- **Performansi**: Stabil. Penulisan disk ada di `Dispatchers.IO` dan kita tidak memanggil `bitmap.compress()` lagi. 
- **Kebersihan Kode**: Mengikuti prinsip *Clean Code* karena tanggung jawab I/O dilempar ke kelas `DatasetManager`, tidak bersarang di `CameraStreamActivity`.
- **Doubt Verified (Garbage Collection)**: Waktu henti akibat GC (*Garbage Collection*) sangat minim karena I/O ini sifatnya sekadar membuang (*dump*) `ByteArray` asli.
- **Doubt Verified (Storage Limit)**: Untuk mencegah memori penuh, pengumpulan dibatasi **1500 gambar**. Kita tidak menghitung _size_ folder setiap saat (karena berat/mahal), tapi hanya menghitung isi direktori **satu kali** saat mode pertama kali dijalankan (*Lazy Evaluation* alias Ponytail). Jika limit tercapai, pengumpulan otomatis berhenti (`limitReached = true`).
