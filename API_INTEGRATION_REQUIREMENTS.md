# AgriAttend v1.5.3 — API dan otomasi Verval

## AgriConnect (dipertahankan)

- Autentikasi resmi melalui `POST /api/auth`, `POST /api/auth/login`, refresh token, profil, employment detail, kalender/timesheet, status permohonan, dan check-in/out sesuai endpoint yang diaudit dari APK resmi.
- Tenant requests membawa `X-TenantID`, Bearer Authorization, dan AuthorizationToken. OTP yang diwajibkan server tidak dilewati.

## Simkopdes Verval

- Portal: `https://simkopdes.go.id/pers/asset/verval?batch_verification=1%2C2%2C3%2C4`.
- Proses yang dibuat: WebView internal pada domain HTTPS Simkopdes, identifikasi kabupaten/kota dari field penempatan/profil AgriConnect, klik baris daerah yang cocok, isi passcode pada input password/akses atau prompt browser yang terdeteksi, pilih opsi Semua pada select filter status/batch jika tersedia, baca tabel koperasi dan koordinat/peta, lalu kembalikan hasil ke modul Verval.
- Data yang dipertahankan: nama koperasi, batch (1–4 bila tertera), hasil pemeriksaan, koordinat, tautan Google Maps, kabupaten/provinsi sumber, dan waktu pembaruan. Nama pengurus dan Babinsa tidak dikumpulkan.
- Passcode disimpan pada SharedPreferences hanya dalam bentuk AES-GCM ciphertext; kunci AES dibuat dan dikelola Android Keystore. Passcode tidak diserialisasi ke state web/PWA.
- Hasil baru disimpan hanya jika portal mengembalikan sedikitnya satu baris koperasi valid. Data lama tidak dihapus otomatis bila refresh gagal.
- Tidak ada endpoint Simkopdes privat yang di-hardcode karena permintaan jaringan situs tidak dapat diperiksa dari lingkungan pengembangan. Implementasi memakai DOM otomasi WebView dan perlu uji portal live.

## Keamanan dan batas verifikasi

- Tidak mengubah status koperasi atau mengirim transaksi perubahan ke Simkopdes.
- Tidak menyamarkan identitas pengguna atau menonaktifkan mekanisme autentikasi portal.
- WebView otomatis membatasi navigasi utama pada `simkopdes.go.id` dan subdomain HTTPS.
- Build statis hanya menguji struktur source dan sintaks JavaScript. Keberhasilan akses, selectors DOM, password prompt aktual, pagination/lazy loading, dan hasil server belum terverifikasi live.


## Endpoint tambahan v1.5.3 (berdasarkan bundle APK AgriConnect yang dianalisis)
- Slip gaji: `POST /api/user/my-profile/pin/validation` dengan `{pin}`; `GET /api/user/self-service/payslip`; detail `GET /api/user/self-service/payslip/detail?period=...&runtypeid=...`; unduhan menggunakan `/public/core-ws/payslip-report/payslip-result?runtypeid=...&period=...&token=...`. PIN tidak disimpan dan harus diverifikasi oleh server.
- Perubahan jam kerja: lookup shift `GET /api/lookup/shift/shift-change`; pengajuan `POST /api/user/self-service/shift-change` dengan `remark` dan `details[]` berisi `scheduleDateFrom`, `scheduleDateTo`, `shiftId`.
- Status shift-change pribadi dibaca dari `/api/user/self-service/shift-change/status/my-request` bila route tersedia pada tenant. Aplikasi hanya membaca status milik pengguna, bukan endpoint approval atasan.
- Absensi: `outOfOffice=false` untuk di kantor; `true` untuk di luar kantor. Mode di luar kantor mengirim pilihan `purpose` dan keterangan; mode di kantor mengikuti perilaku APK yang memakai remark `In The Office`. GPS aktual selalu berasal dari perangkat.

Endpoint di atas berasal dari analisis statis bundle APK; respons setiap tenant dan route yang dikonfigurasi tetap perlu diverifikasi pada akun pengguna.


## Penempatan status permintaan v1.5.3
- Status permintaan pribadi ditempatkan pada menu **Pengajuan** dan dimuat otomatis saat halaman dibuka.
- Tombol **Muat ulang** memanggil ulang daftar status; tidak digunakan untuk mengambil approval inbox atau menjalankan approve/reject.
- Kalender tetap memuat timesheet dan detail tanggal saja.
