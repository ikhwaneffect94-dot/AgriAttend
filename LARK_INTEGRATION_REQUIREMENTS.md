# Integrasi Lark untuk AgriAttend

Status: fondasi integrasi / menunggu konfigurasi dan persetujuan tenant.

## Tujuan

Menambahkan akun Lark sebagai jalur login kedua di AgriAttend tanpa merusak alur AgriConnect yang sudah ada. Setelah integrasi resmi disiapkan, identitas Lark akan dipakai untuk mengakses fitur attendance yang disetujui oleh administrator tenant.

## Temuan pada repository saat ini

- AgriAttend saat ini login ke AgriConnect HRIS melalui `https://agriconnect-master.starconnect.id/api/auth` dan `/api/auth/login`.
- Data absensi saat ini dikirim ke endpoint tenant AgriConnect `/api/user/self-service/check-in-out/v1/request`.
- Belum ada konfigurasi App ID Lark, OAuth callback, backend token broker, atau pemetaan akun AgriConnect ↔ Lark.
- Karena itu, login AgriConnect yang ada tidak boleh dianggap sebagai login Lark, dan endpoint absensi yang ada tidak boleh dianggap sebagai endpoint Attendance Lark.

## Arsitektur yang akan dipakai

1. Pengguna menekan **Masuk dengan Lark** dari AgriAttend.
2. AgriAttend membuka URL login milik backend integrasi (HTTPS).
3. Backend mengarahkan pengguna ke otorisasi resmi Lark dan memvalidasi parameter `state`.
4. Lark mengembalikan authorization code ke callback HTTPS backend.
5. Backend menukar code menjadi token melalui Lark Open Platform. App Secret tetap berada di backend dan tidak pernah dimasukkan ke APK.
6. Backend mengambil identitas Lark dan membuat tiket aplikasi sekali pakai dengan masa berlaku singkat.
7. Backend mengarahkan kembali ke deep link AgriAttend. Aplikasi menukar tiket tersebut menjadi sesi AgriAttend dan menyimpan sesi dengan Android Keystore.
8. Permintaan baca/tulis Attendance Lark dilakukan melalui backend. Token tenant dan App Secret tidak disimpan di APK.

## Batasan keamanan dan absensi

- Dilarang menyalin cookie, token sesi, App Secret, atau kredensial internal dari APK Lark milik pihak lain.
- Jangan mengirim username/password Lark ke AgriAttend. Login dilakukan melalui halaman/SDK resmi Lark.
- Jangan menganggap `attendance:task` otomatis berarti aplikasi pihak ketiga dapat melakukan alur clock-in karyawan seperti aplikasi Lark. Izin tersebut perlu diberikan dan diuji oleh administrator tenant.
- Salah satu API Lark yang ditemukan untuk menulis data punch adalah API pengimporan attendance flow. API impor tidak boleh langsung dipakai sebagai pengganti clock-in langsung sebelum admin Agrinas/Lark mengonfirmasi bahwa cara tersebut memang diperbolehkan dan hasilnya diterima dalam kebijakan absensi perusahaan.
- Validasi geofence, foto, Wi-Fi/BSSID, face recognition, offsite attendance, dan aturan jadwal harus mengikuti konfigurasi tenant. Aplikasi tidak boleh memalsukan koordinat, waktu, perangkat, atau status kehadiran.
- Ketika server Lark sedang overload, aplikasi alternatif tetap bergantung pada layanan Lark. Antrean lokal hanya aman digunakan untuk menyimpan draf/permintaan yang belum dikirim; jangan mengaku absensi sudah tercatat sampai server mengonfirmasi.

## Yang dibutuhkan sebelum mengaktifkan login Lark

Minta administrator Lark/Agrinas menyiapkan:

1. Custom App di Lark Open Platform khusus AgriAttend, dengan fitur Mobile App Login atau OAuth yang didukung untuk Android.
2. App ID untuk konfigurasi aplikasi.
3. Backend HTTPS yang dikelola dan disetujui perusahaan untuk menerima callback OAuth, menukar code, menyimpan token dengan aman, dan mengeluarkan tiket sesi satu kali.
4. Redirect/callback URL yang didaftarkan di Lark Developer Console.
5. Persetujuan tenant atas scope yang diperlukan. Untuk operasi tulis attendance, admin perlu memverifikasi scope `attendance:task`, tipe token yang didukung, batas akses, dan metode API yang diizinkan.
6. Pemetaan identitas karyawan ke Lark user ID/open ID, menggunakan identifier yang disetujui administrator.

## Konfigurasi yang tidak boleh dimasukkan ke repository publik

- App Secret
- Tenant Access Token
- User Access Token / Refresh Token
- Client secret backend
- Cookie, session ID, atau kredensial pengguna

Semua rahasia harus disimpan pada backend melalui secret/environment variables. APK hanya menerima tiket sesi aplikasi atau token berumur pendek yang memang dirancang untuk klien.

## Tahap implementasi berikutnya

- Tambahkan tombol dan status akun Lark sebagai jalur login kedua.
- Implementasikan callback Android dan sesi terenkripsi setelah callback URL resmi ditentukan.
- Implementasikan endpoint broker backend setelah domain, App ID, dan kontrak respons disetujui.
- Uji endpoint Attendance yang benar di tenant uji sebelum mengaktifkan tombol Absen Masuk/Pulang Lark.
- Pertahankan alur AgriConnect saat ini sampai integrasi Lark lolos uji end-to-end.

## Referensi resmi

- Lark Mobile App Login: https://open.larksuite.com/document/uAjLw4CM/uYjL24iN/mobile-app/mobile-app-overview
- OAuth / login app: https://open.larksuite.com/document/server-docs/getting-started/authen-v1/build-login-free-system-
- Lark Attendance app setup dan scope: https://open.larksuite.com/document/home/quick-start-of-personnel-and-attendance-management-system/step-1-create-and-configure-an-application
- Lark API terminology dan jenis access token: https://open.larksuite.com/document/server-docs/getting-started/terminology
