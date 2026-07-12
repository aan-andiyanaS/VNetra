# ADR-011: Penghapusan Filter Spasial "Manusia di dalam Mobil" pada Pemrosesan Dataset

## Status
Proposed

## Date
2026-07-11

## Context
Pada `dataset_vnetra_yolov11n_colab-FIX.ipynb` terdapat blok kode *Spatial Passenger Filter* yang secara otomatis menghapus (menandai `dropped = True`) kotak pembatas kelas `person` (manusia) apabila:
1. Luas area manusia < 25% dari luas kendaraan.
2. Lebih dari 70% area manusia tersebut tumpang tindih (berada di dalam) *bounding box* kendaraan (`car`, `bus`, `truck`).

Pengguna (*user*) mengeluhkan bahwa filter ini justru menghancurkan akurasi (*mAP*) dari kedua kelas tersebut saat model digunakan pada Android. Kenapa hal ini bisa terjadi? 
Model pendeteksi objek berbasis YOLO bergantung erat pada konsistensi visual label. Jika mesin melihat objek manusia menempel/duduk di mobil (seperti pengendara motor atau penumpang yang terlihat jelas dari kaca depan) tetapi data labelnya dihapus, YOLO akan bingung dan menganggap fitur tubuh manusia tersebut sebagai bagian natural dari mobil biasa (mengakibatkan *false negative* untuk manusia, dan *noisy feature* untuk mobil). 

## Decision (Ponytail & Doubt-Driven)
*Ponytail approach:* Jika sebuah filter pintar malah merusak keaslian label dari COCO secara destruktif, maka hapus sepenuhnya. Kita tidak perlu mencoba men-*tweak* toleransi persentase *IoU*-nya. Biarkan mesin YOLO mempelajari dan membedakan bentuk tumpang-tindih manusia dan kendaraan secara alami melalui *Loss Function* miliknya.

Saya akan menghapus keseluruhan logika **SPATIAL PASSENGER FILTER** dari skrip Google Colab (`dataset_vnetra_yolov11n_colab-FIX.ipynb`).

## Proposed Changes
### `notebooks/dataset_vnetra_yolov11n_colab-FIX.ipynb`
- **Hapus blok kode berikut** (sekitar 15 baris) dari dalam *loop* parsing label:
  ```python
  # --- SPATIAL PASSENGER FILTER ---
  vehicle_classes = {'car', 'bus', 'truck'}
  for p_obj in (o for o in parsed_objs if o['orig_class'] == 'person' and not o['dropped']):
      pxc, pyc, pw, ph = p_obj['box']
      pxmin, pymin, pxmax, pymax = pxc - pw/2, pyc - ph/2, pxc + pw/2, pyc + ph/2
      p_area = pw * ph
      for v_obj in (o for o in parsed_objs if o['orig_class'] in vehicle_classes and not o['dropped']):
          vxc, vyc, vw, vh = v_obj['box']
          v_area = vw * vh
          if p_area == 0 or v_area == 0 or (p_area / v_area > 0.25): continue
          vxmin, vymin, vxmax, vymax = vxc - vw/2, vyc - vh/2, vxc + vw/2, vyc + vh/2
          ixmin, iymin, ixmax, iymax = max(pxmin, vxmin), max(pymin, vymin), min(pxmax, vxmax), min(pymax, vymax)
          if ixmax > ixmin and iymax > iymin and ((ixmax - ixmin) * (iymax - iymin)) / p_area > 0.7:
              p_obj['dropped'] = True
              break
  ```
- Biarkan *Critical Distance / Bbox Size Filter* dan *Per-Class Limit Enforcement* tetap utuh, karena fungsi tersebut menjaga agar label yang terlalu kecil secara fisik tidak masuk.

## Consequences
- **Positif:** Peningkatan akurasi (*Recall*) untuk kelas *person* dan kendaraan. Model YOLO tidak akan lagi kebingungan saat melihat manusia yang berkerumun dengan atau berada di dalam kendaraan.
- **Negatif:** Kelas *person* akan sedikit bertambah ke dalam total kuota dataset, namun batasan keras (`max_samples_per_class`) yang ada di dalam skrip akan tetap menjaga keseluruhan dataset *balanced*.

# ADR-012: Penggantian Sumber Dataset Kelas Person & Konfigurasi Global
## Status
Proposed

## Date
2026-07-11

## Context
Pada `dataset_vnetra_yolov11n_colab-FIX.ipynb`, kelas `person` sebelumnya didapatkan langsung dari dataset Microsoft COCO. Pengguna meminta agar sumber untuk kelas `person` dipisahkan menggunakan dataset spesifik dari `yolov8segworkspace/person-mfa1g` untuk mendapatkan representasi data yang lebih baik. Selain itu, parameter penyaringan (`MAX_INSTANCES` dan `MIN_PIXEL_SIZE`) masih tertanam (*hardcoded*) di dalam fungsi, sehingga menyulitkan *tuning* eksperimen yang cepat.

## Decision (Doubt-Driven & Ponytail)
*Ponytail approach:* Alih-alih membuat fungsi baru yang kompleks untuk mengunduh dataset secara paralel, kita cukup:
1. Menambahkan 1 baris sintaks unduhan Roboflow untuk `person-mfa1g` di *cell* pengunduhan.
2. Menghapus *mapping* `"person": "person"` dari bagian COCO, dan memanggil fungsi `merge_dataset()` yang sudah ada secara khusus untuk hasil unduhan `person-mfa1g`.
3. Menarik variabel `MAX_INSTANCES_GLOBAL` dan `MIN_PIXEL_SIZE_GLOBAL` ke atas (bagian *Konfigurasi*) lalu menggunakan variabel tersebut pada fungsi utama. Solusi paling minim baris, paling cepat dimodifikasi, dan paling rendah risiko.

## Proposed Changes
### `notebooks/dataset_vnetra_yolov11n_colab-FIX.ipynb`
- **Cell 2 (Konfigurasi):** Menambahkan variabel global `MAX_INSTANCES_GLOBAL = 10` dan `MIN_PIXEL_SIZE_GLOBAL = {'person': 139, 'motorcycle': 30, 'car': 50, 'bus': 50}`.
- **Cell 3 (Download):** Menambahkan `dataset_person = rf.workspace("yolov8segworkspace").project("person-mfa1g").version(1).download("yolov11")`.
- **Cell 5 (Merge):**
  - Mengubah nilai fungsi *filtering* agar merujuk ke variabel global yang baru.
  - Membuang pemrosesan `"person"` dari `coco_rf_mapping`.
  - Menambahkan blok pemrosesan mandiri: `merge_dataset(dataset_person.location, {"person": "person"}, max_instances_per_class=MAX_INSTANCES_GLOBAL)`.

## Consequences
- **Positif:** Konfigurasi parameter ukuran objek (piksel) dan kepadatan instans sangat mudah diakses (berada di atas notebook). Kualitas dataset `person` dapat diganti secara independen tanpa memengaruhi sisa dataset COCO.
- **Negatif:** Waktu *download* awal di Colab akan bertambah beberapa detik untuk menarik dataset `person` baru.

# ADR-013: Pencegahan False Negatives dengan Image-Based Soft-Limit & Sentralisasi Konfigurasi
## Status
Proposed

## Date
2026-07-12

## Context
Pada `dataset_vnetra_yolov11n_colab-FIX.ipynb`, terdapat bug arsitektural pada implementasi *Global Class Limits*. Skrip versi awal menghentikan injeksi label (`dropped = True`) jika kuota batas suatu kelas (misal: `person`: 6000) tercapai di tengah-tengah pemrosesan suatu gambar. Akibatnya, gambar tersebut tetap disimpan demi kelas lain (misal: `car`), tetapi objek `person` di dalamnya tidak memiliki kotak pembatas. Hal ini berpotensi membingungkan model YOLO (menciptakan *False Negatives*) karena bentuk manusia secara visual diajarkan sebagai *background*.
Selain itu, pengguna meminta agar pengaturan (*config*) terkait batas dan ukuran diletakkan di sel (*cell*) yang sama persis dengan blok proses *merging* agar tidak perlu *scroll* jauh ke atas, dan menginginkan agar tidak ada lagi logika `max_samples` yang tercecer.

## Decision (Doubt-Driven & Ponytail)
1. **Doubt-Driven (Soft-Limit):** Skrip diubah agar melakukan pengecekan limit pada level gambar (bukan per objek individual). Jika gambar tersebut memiliki **minimal 1 kelas** yang masih butuh kuota, gambar tersebut diselamatkan dan **SELURUH LABEL** valid di dalam gambar tersebut (meskipun kelasnya sudah melampaui limit) akan disimpan. Ini memastikan nol (*zero*) *False Negatives*, dengan kompensasi batas kelas mungkin "kebablasan" sedikit (menjadi *Soft-Limit*).
2. **Ponytail (Sentralisasi):** Membuang seluruh argumen `max_samples` dari pemanggilan fungsi di bawah. Membuat `GLOBAL_CLASS_LIMITS` komprehensif yang mendaftar ke-14 *master class*. Memindahkan seluruh blok konfigurasi (`MAX_INSTANCES_GLOBAL`, `MIN_PIXEL_SIZE_GLOBAL`, `GLOBAL_CLASS_LIMITS`) langsung ke bagian teratas Cell 5 (sel fungsi *merging*).

## Consequences
- **Positif:** Mengamankan akurasi model dari racun *False Negatives*. Memberikan pengalaman pengguna yang sangat ringkas dan intuitif (satu sel penuh kendali limit, batas ukuran, dan eksekusi gabungan).
- **Negatif:** Batasan `GLOBAL_CLASS_LIMITS` tidak lagi bersifat kaku (*strict*). Jika limit adalah 6000, hasil akhir mungkin 6010 atau 6015 (sangat tidak masalah dalam konteks *Machine Learning* skala besar).

# ADR-014: Pencabutan Kelas "Person" dari Arsitektur Sistem VNetra
## Status
Proposed

## Date
2026-07-12

## Context
Aplikasi VNetra merupakan asisten tunanetra yang bertugas mendeteksi rintangan dan marka jalan. Pada versi sebelumnya, kelas `person` (manusia) disertakan sebagai salah satu objek yang dideteksi. Pengguna secara spesifik meminta eksperimen (*Doubt-Driven Development*) untuk menghilangkan sama sekali kelas `person` dari deteksi, karena sistem yang terus-menerus memberikan peringatan "ada orang" bisa menyebabkan *information overload* (kebisingan peringatan) saat pengguna berada di area publik yang ramai.

## Decision (Ponytail Approach)
Kita mengambil langkah paling minimalis namun ekstrem:
1. **Dataset Pipeline:** Menghapus string `"person"` dari `master_classes`, serta seluruh filter kepadatan dan ukuran yang berkaitan dengan kelas tersebut dari notebook pembuatan dataset (`dataset_vnetra_yolov11n_colab-FIX.ipynb`). Hal ini otomatis menurunkan total kelas dari 14 menjadi 13.
2. **Android App (YoloDetector.kt):** Karena `person` (dengan nama ID "orang") dulunya menempati indeks ke-0, penghapusan ini mengakibatkan kelas `car` (mobil) naik menempati indeks ke-0. Jika kode Android tidak disesuaikan, deteksi YOLOv11 yang baru akan menghasilkan *out-of-bounds error* atau misklasifikasi parah. Oleh karena itu, kita mengubah konstan `NUM_CLASSES = 13` dan menghapus `"orang"` dari _array_ `CLASSES` di `YoloDetector.kt`.

## Consequences
- **Positif:** Mengurangi beban komputasi secara minor. Membebaskan pengguna tunanetra dari *spam* peringatan keberadaan pejalan kaki di sekitarnya. Arsitektur tetap sinkron antara *backend* pelatih (Colab) dan *frontend* (Android).
- **Negatif:** VNetra kini sama sekali buta terhadap rintangan berupa manusia (meskipun manusia tersebut berada tepat di depan pengguna).
