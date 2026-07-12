## ADR-025: Penghapusan Dead Code `ws_mutex`
- **Status:** Dieksekusi (12 Juli 2026)

### 1. Konteks
Pada laporan *code review* (poin 2 di `report.md`), ditemukan bahwa variabel `SemaphoreHandle_t ws_mutex` masih dideklarasikan secara global di `firmware-vnetra.ino`. Namun, sejak implementasi ADR-023 yang menghapus perlindungan *semaphore* untuk WebSocket, inisialisasi *mutex* ini (`ws_mutex = xSemaphoreCreateMutex()`) sudah dihapus dari fungsi `setup()`.
Akibatnya, variabel ini bernilai `NULL` dan berstatus sebagai *dead code*. Jika ada pengembang yang mencoba menggunakan *mutex* ini di masa depan tanpa menyadari bahwa ia tidak pernah diinisialisasi, hal ini dapat menyebabkan *crash* pada RTOS.

### 2. Keputusan (Incremental Implementation & Ponytail)
Menggunakan pendekatan *ponytail* (menyelesaikan masalah tanpa menyentuh bagian yang tidak perlu), kita akan menghapus deklarasi variabel `ws_mutex` ini sepenuhnya.

### 3. Konsekuensi
- **Positif:** Mengurangi ambiguitas dan *dead code* di lingkup global.
- **Positif:** Mencegah potensi *crash* di masa depan akibat penggunaan *semaphore* yang tidak valid (`NULL`).
