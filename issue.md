# Refactor Inisialisasi Grid ToF Menggunakan Layout Weight

## Masalah
Sebelumnya, ukuran lebar dan tinggi sel grid ToF VL53L5CX (matriks 8x8) dihitung secara manual dengan membagi dimensi `gridTof` (lebar / 8 dan tinggi / 8) setelah layout selesai dirender (`binding.gridTof.post`). Pendekatan ini rentan terhadap masalah presisi (adanya celah pixel akibat pembulatan integer) dan ketergantungan pada waktu rendering (`.post`). Hal ini juga dapat menyebabkan grid tidak terinisialisasi dengan benar jika terjadi delay rendering.

## Solusi
Melakukan refaktor pada inisialisasi grid ToF (`initTofGrid`) di `CameraStreamActivity.kt` dengan memanfaatkan fitur bawaan Android `GridLayout.spec` menggunakan bobot (`weight` sebesar `1f`). Dengan menetapkan:
- `width = 0`
- `height = 0`
- `columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)`
- `rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)`

Android secara otomatis dan dinamis akan membagi ruang `gridTof` menjadi 8 kolom dan 8 baris dengan rasio yang benar-benar sama rata, tanpa celah pembulatan dan tanpa bergantung pada callback `.post`.

## Kriteria Selesai
1. Grid terbagi rata secara otomatis oleh sistem layout Android.
2. Tidak ada penundaan/delay rendering sel yang bergantung pada `.post`.
3. Rasio grid tetap terjaga pada 1:1 secara sempurna di berbagai resolusi layar.
