# AgriAttend Android v1.5.3

Pendamping Android untuk alur absensi AgriConnect dan pantauan status verifikasi koperasi Simkopdes.

## Fitur utama

- Login dan profil AgriConnect, kalender/timesheet, status permohonan, GPS aktual, foto, CHECKIN/CHECKOUT, dan nomor tiket berdasarkan respons server.
- Modul **Pantauan Verval Kopdes**: mengambil kabupaten/kota dari data employment/profil AgriConnect, membuka portal Simkopdes di dalam aplikasi, mencoba memilih kabupaten/kota penugasan, mengisi passcode yang tersimpan aman, lalu membaca nama koperasi, batch, hasil pemeriksaan, koordinat, dan tautan Google Maps.
- Passcode Simkopdes dimasukkan satu kali pada modul Verval dan disimpan secara terenkripsi menggunakan Android Keystore. Passcode tidak dimasukkan ke localStorage, data ekspor, atau log aplikasi. Pengguna dapat menghapusnya dari perangkat.
- Hasil verval hanya diperbarui bila daftar koperasi berhasil dibaca dari portal. Jika kabupaten tidak ditemukan pada data penugasan, portal meminta langkah tambahan, situs berubah, atau parsing gagal, AgriAttend menampilkan kegagalan dan tidak mengganti data dengan hasil contoh.
- Nama pengurus dan Babinsa tidak dikumpulkan atau ditampilkan.

## Alur Verval

1. Login ke AgriConnect dan buka menu Pantauan Verval.
2. Periksa kabupaten/kota yang dikenali dari detail profil/penempatan.
3. Masukkan passcode Simkopdes satu kali, lalu tekan **Simpan terenkripsi**.
4. Tekan **Refresh Data Verval**. AgriAttend membuka portal dalam WebView internal, memilih lokasi penugasan bila tersedia, mengisi passcode pada field/prompt yang dikenali, dan mencoba membaca seluruh halaman daftar.
5. Setelah data berhasil dibaca, jumlah koperasi, batch, non-prioritas (bila tertulis eksplisit di sumber), hasil pemeriksaan, koordinat, tautan peta, dan waktu pembaruan ditampilkan.

## Batasan yang perlu diketahui

- Struktur DOM dan alur login Simkopdes dapat berubah. Otomatisasi memakai pengenalan elemen umum pada halaman; portal live harus diuji pada perangkat Android dengan akun yang memiliki akses.
- Lokasi penugasan harus tersedia dalam data employment/profil AgriConnect dengan nama kabupaten/kota yang dapat dikenali. Jika API tenant tidak mengirimkan informasi itu, pemilihan otomatis akan berhenti demi menghindari salah kabupaten.
- Build statis tidak membuktikan bahwa passcode diterima atau semua baris/paginasi portal dapat dibaca. Akses portal Simkopdes tidak dapat dijangkau dari lingkungan build ini pada saat pembuatan paket.
- Browser portal hanya mengizinkan navigasi utama ke domain Simkopdes HTTPS. Tautan eksternal tidak akan dibuka di dalam WebView otomatis.
- Fitur tidak mengubah data Simkopdes, tidak mengirim pengajuan verval, dan tidak melewati autentikasi atau kontrol akses portal.

## Build APK

1. Upload ZIP versi ini ke root repository GitHub.
2. Ganti `.github/workflows/android.yml` dengan workflow yang disertakan.
3. Jalankan workflow **Build AgriAttend APK** dari tab Actions.
4. Jika job berhasil, unduh artifact `AgriAttend-debug-APK-v1.5.3`.

Build Android SDK/native, login Simkopdes, pemilihan kabupaten otomatis, pengisian passcode, dan ekstraksi seluruh data masih harus diverifikasi pada perangkat Android sesudah workflow berhasil.


## Perbaikan v1.5.1
- Memperbaiki callback error WebView: `WebViewClientCompat` menetapkan overload Android `WebResourceError` sebagai final; aplikasi sekarang meng-override overload non-final yang memakai `WebResourceErrorCompat`.


## Perubahan v1.5.3
- Form absensi diringkas mengikuti alur check-in/out: GPS aktual, pilihan Di kantor/Di luar kantor, foto, dan keterangan. Keperluan ditampilkan untuk mode Di luar kantor.
- Geofence tenant dipindahkan ke informasi lokasi yang bisa dibuka; koordinat/radius tidak direkayasa bila endpoint tenant kosong.
- Ditambahkan modul slip gaji dengan verifikasi PIN ke server, daftar periode, rincian pendapatan/potongan/tambahan, dan tautan unduh. PIN tidak disimpan.
- Ditambahkan formulir permohonan perubahan jam kerja menggunakan endpoint shift-change dan pemantauan status permintaan pribadi.
- Pantauan verval memiliki filter nama, batch, dan hasil; parser ditambah fallback untuk tampilan kartu responsif. Pengujian portal langsung tetap diperlukan.


## Perubahan v1.5.3
- Memindahkan panel status permintaan ke menu **Pengajuan**, agar status koreksi check-in/out dan perubahan jam kerja berada bersama formulir pengajuannya.
- Menambahkan tombol **Muat ulang** khusus untuk menarik ulang status permintaan pribadi dari AgriConnect.
- Kalender kini fokus pada timesheet dan rincian harian; daftar status permohonan tidak lagi bercampur dengan kalender.
- Nomor versi aplikasi dan cache shell diperbarui ke 1.5.3.
