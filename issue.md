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
