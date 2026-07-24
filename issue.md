# ADR-048: Portrait-Only Lock & UI Responsiveness

## Status
Diterima (Diimplementasikan pada commit `a1006ce`, branch `fixing`)

## Konteks

App VNetra tidak memiliki `screenOrientation` di `AndroidManifest.xml`. Jika user mengaktifkan auto-rotate di sistem Android, app bisa berputar ke landscape — menyebabkan layout berantakan karena tidak ada `layout-land/` variant.

---

## Hasil Audit UI Responsiveness

Audit menyeluruh dilakukan terhadap seluruh layout dan activity sebelum implementasi:

| Komponen | Status | Keterangan |
|----------|--------|------------|
| Aspect ratio kamera | ✅ Sudah benar | `constraintDimensionRatio="4:3"` |
| Bottom navigation bar insets | ✅ Sudah ditangani | `ViewCompat.setOnApplyWindowInsetsListener` L.254 |
| Toolbar top inset | ✅ Sudah ditangani | `setPadding(0, systemBars.top, 0, 0)` |
| Controls bottom padding | ✅ Sudah ditangani | `systemBars.bottom + 16.dpToPx()` |
| Layout responsif berbagai layar | ✅ Sudah benar | ConstraintLayout + match_parent |
| **Portrait lock** | ✅ **Diperbaiki** | Tambah `screenOrientation="portrait"` |

---

## Perubahan yang Dilakukan

Tambah `android:screenOrientation="portrait"` ke semua 3 activity di `AndroidManifest.xml`:

```xml
<!-- MainActivity -->
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:screenOrientation="portrait"
    android:label="@string/app_name"
    android:theme="@style/Theme.ESP32Config">

<!-- DeviceConfigActivity -->
<activity
    android:name=".ui.DeviceConfigActivity"
    android:screenOrientation="portrait"
    android:theme="@style/Theme.ESP32Config" />

<!-- CameraStreamActivity -->
<activity
    android:name=".ui.CameraStreamActivity"
    android:screenOrientation="portrait"
    android:launchMode="singleTop"
    android:theme="@style/Theme.ESP32Config" />
```

---

## Konsekuensi

- App selalu portrait meskipun auto-rotate device diaktifkan
- Tidak ada Activity recreation saat device dirotasi = sesi streaming tidak terputus
- Tidak perlu `layout-land/` variant — lebih sederhana (YAGNI)
- Berlaku untuk semua ukuran layar (5" hingga 7")

---

## Terkait
- ADR-047: Hardware-Adaptive Performance Optimization
- ADR-046: Camera Stutter Fix (bitmap double buffer)
