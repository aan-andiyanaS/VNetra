# Plan Perbaikan Bug: Fragmentasi Heap PSRAM (Alokasi Memori Dinamis)

**Berdasarkan Laporan**: report.md (Poin 3)
**Tingkat Keparahan**: Sedang (Dapat menyebabkan _Out-Of-Memory_ / *Kernel Panic* pada penggunaan non-stop berjam-jam)
**Lokasi File**: `firmware-vnetra/firmware-vnetra/firmware-vnetra.ino`

## 1. Latar Belakang Masalah
Di dalam fungsi `captureAndSend()` pada file firmware ESP32, terdapat logika alokasi buffer pengiriman WebSocket (`g_wsBuf`) yang bersifat dinamis. Meskipun menggunakan metode *high-water mark* (hanya dialokasikan ulang jika ukuran frame JPEG baru melebihi kapasitas buffer sebelumnya), proses ini masih mengandalkan siklus fungsi `heap_caps_free()` dan `heap_caps_malloc()`.

Meskipun modul ESP32-S3 WROOM N16R8 memiliki PSRAM berukuran sangat besar (8MB) yang dapat menutupi efek memori penuh untuk durasi yang jauh lebih lama dibandingkan SRAM biasa, memanggil prosedur dealokasi dan alokasi ulang di dalam *fast loop* per-frame (10-30 FPS) tetap berisiko menimbulkan **Heap Fragmentation (Memori Berlubang)** seiring berjalannya waktu dan menghabiskan siklus pemrosesan CPU OS (FreeRTOS) yang mengatur pemetaan memori.

## 2. Tujuan Perbaikan
Menghapus logika _Dynamic Allocation_ dan menggantinya dengan _Static Pre-Allocation_. Kita akan mengalokasikan memori penampung dengan kapasitas maksimal secara permanen di awal, dan sistem hanya akan melempar (_drop_) frame yang secara langka ukurannya melebihi kapasitas tersebut. Hal ini akan menghasilkan:
1. **0% Fragmentasi Memori** (stabilitas terjamin mutlak untuk _uptime_ berhari-hari).
2. **Pengurangan beban CPU** (mengurangi *jitter/micro-stutter* kamera karena CPU tidak perlu lagi mengeksekusi memori re-allocation dan pencarian _heap pointer_).

## 3. Langkah Implementasi secara Detail

### 3.1. Lokasi Kode yang Akan Diubah
Buka file: `firmware-vnetra/firmware-vnetra/firmware-vnetra.ino`
Cari blok inisiasi memori di dalam fungsi `captureAndSend()` (di sekitar baris 519 - 531).

### 3.2. Referensi Kode Saat Ini (Sebelum Diperbaiki)
```cpp
    if (!g_wsBuf || total > g_wsBufSize) {
        if (g_wsBuf) heap_caps_free(g_wsBuf);
        g_wsBuf = (uint8_t*)heap_caps_malloc(
            total + 8192, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT
        );
        if (!g_wsBuf) g_wsBuf = (uint8_t*)malloc(total + 4096);
        g_wsBufSize = g_wsBuf ? total + 8192 : 0;
        if (!g_wsBuf) {
            if (fb)             esp_camera_fb_return(fb);
            else if (converted) free(jpg_buf);
            return;
        }
    }
```

### 3.3. Kode Solusi (Setelah Diperbaiki)
Ubah blok kode di atas menjadi logika pre-alokasi statis menggunakan ukuran absolut konstan `WS_BUF_MAX` (yang telah didefinisikan sebelumnya sebesar `130*1024` / 130KB, ukuran ini sudah lebih dari cukup untuk frame OV2640 VGA).

```cpp
    // Pre-alokasi permanen satu kali eksekusi
    if (!g_wsBuf) {
        // Alokasikan memori konstan secara absolut
        g_wsBufSize = WS_BUF_MAX; 
        g_wsBuf = (uint8_t*)heap_caps_malloc(
            g_wsBufSize, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT
        );
        // Fallback jika PSRAM gagal, gunakan SRAM internal walau kecil kemungkinannya
        if (!g_wsBuf) g_wsBuf = (uint8_t*)malloc(g_wsBufSize);
        
        if (!g_wsBuf) {
            Serial.println("[MEM] GAGAL mengalokasikan buffer statis!");
            g_wsBufSize = 0;
            if (fb)             esp_camera_fb_return(fb);
            else if (converted) free(jpg_buf);
            return;
        }
        Serial.printf("[MEM] Buffer WebSocket Statis Dialokasikan: %u Bytes\n", g_wsBufSize);
    }

    // Jika secara langka ada frame anomali yang lebih besar dari wadah (misal >130KB), buang frame tersebut (Drop)
    if (total > g_wsBufSize) {
        Serial.printf("[CAM] Frame terlalu besar (%u bytes) melebihi buffer statis (%u bytes). Frame didrop.\n", total, g_wsBufSize);
        if (fb)             esp_camera_fb_return(fb);
        else if (converted) free(jpg_buf);
        return;
    }
```

## 4. Evaluasi Keseluruhan Struktur Kode
Saya telah mengecek seluruh struktur pengiriman data di dalam fungsi tersebut untuk memastikan penerapan logika statis ini aman dan tidak akan merusak fitur lainnya:
* **Keamanan Pengiriman Jaringan**: Perintah transmisi WebSocket `client.binary(g_wsBuf, total);` pada baris akhir fungsi tetap menggunakan batas ukuran `total` dari variabel internal bukan `g_wsBufSize`. Hal ini menjamin bahwa **hanya data JPEG asli (misalnya 45KB) yang akan dikirim**, bukan seluruh alokasi 130KB. Tidak ada ancaman perlambatan koneksi WiFi.
* **Pencegahan Overflow**: Proteksi validasi `if (total > g_wsBufSize)` telah diletakkan tepat di atas `memcpy`, memastikan penyalinan data `memcpy(g_wsBuf + FRAME_HEADER_SZ, jpg_buf, jpg_len);` aman dan mustahil menyebabkan tumpukan memori (_Memory Overflow_).

**Status Eksekusi**: *Plan ini hanya dibuat untuk reviu pengguna dan belum dieksekusi secara aktual pada C++ source code.*
