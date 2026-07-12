## ADR-026: Pembersihan Variabel Lama `TARGET_FRAME_MS`, `last_frame_time`, `last_ack_time`
- **Status:** Dieksekusi (12 Juli 2026)

### 1. Konteks
Pada laporan *code review* (poin 3 di `report.md`), ditemukan tiga variabel di tingkat global dalam `firmware-vnetra.ino` yang kini menjadi yatim piatu (*orphaned*):
1. `TARGET_FRAME_MS` (konstanta)
2. `last_frame_time` (variabel penyimpan *timestamp*)
3. `last_ack_time` (variabel penyimpan *timestamp* balasan ACK)

Ketiga *state* ini dulunya digunakan oleh fungsi pembatas FPS internal serta sistem *acknowledgement* (pengiriman frame berdasar balasan Android). Sejak diimplementasikannya ADR-023 (penyerahan aliran kontrol ke mekanisme antrean *native* jaringan TCP LwIP dan loop waktu utama), variabel-variabel ini sama sekali tidak digunakan (*dead code*).

### 2. Keputusan (Incremental Implementation & Ponytail)
Menggunakan filosofi *ponytail* (bersihkan yang memang tidak dipakai, sekecil apapun), kita menghapus deklarasi ketiga variabel ini secara langsung di *global scope*.

### 3. Konsekuensi
- **Positif:** Menghemat sedikit ruang memori dan merapikan kode *firmware* tanpa memengaruhi logika aplikasi atau akurasi sistem.
- **Positif:** Menghilangkan kebingungan (*cognitive load*) bagi pengembang di masa mendatang ketika membaca alur waktu pengambilan gambar.
