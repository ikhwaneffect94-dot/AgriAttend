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


## Lark Attendance correction / backdate (补卡)

This is separate from the existing AgriConnect backdate request. The Lark workflow is an Attendance correction request (補卡 / missed-punch correction), not a direct edit of an already approved attendance row.

### Employee workflow in Lark

When the tenant enables corrections for the employee's attendance group, the employee can submit a correction from:
- Attendance home: choose the affected date → Resolve → Correction Request.
- Attendance → Requests → Correction.
- A missed-punch bot notification, when the tenant has enabled missed-punch notifications.
- Personal Reports → affected date → Resolve → Correction Request.

The request includes the corrected date/time and a reason. The attendance group owner/approver reviews it; once approved, Lark updates the corresponding attendance record. Whether corrections are available, the allowed date window, and monthly correction count are tenant rules. AgriAttend must display server-provided eligibility and must not fabricate or bypass those limits.

### Relevant Lark OpenAPI discovered

The public API definitions describe a supported bridge for companies that use Lark Attendance but use a third-party approval system instead of Lark Approval:

1. `POST /open-apis/attendance/v1/user_task_remedys/query_user_allowed_remedys`
   - Query which punch slots can be corrected for a user on a given `yyyyMMdd` date.
   - Request includes `user_id`, `remedy_date`, and `employee_type`.

2. `POST /open-apis/attendance/v1/user_task_remedys`
   - Register a third-party correction approval request in Lark Attendance as pending approval.
   - Request fields include `user_id`, `remedy_date` (`yyyyMMdd`), `punch_no` (0-based punch sequence), `work_type` (1=in, 2=out; free shift uses 0), `remedy_time` (`yyyy-MM-dd HH:mm`), and `reason`.
   - It does not itself prove that an employee has been approved or create a final accepted punch. The external approval outcome must be synced separately.

3. `POST /open-apis/attendance/v1/approval_infos/process`
   - Sync the external approval outcome back to Lark using `approval_type=remedy` and `status=1` (rejected), `2` (approved), or `4` (withdrawn), with the `approval_id` returned by the correction-request creation flow.

4. `POST /open-apis/attendance/v1/user_task_remedys/query`
   - Read remedy records and their states: 0 pending, 1 rejected, 2 passed, 3 cancelled, 4 withdrawn.

The above remedy APIs are documented as using a **tenant access token**. The API reference states that the record-query endpoint has no matching end-user page function. These interfaces are not a way to reuse a regular employee login cookie or an arbitrary user token.

### Product decision for AgriAttend

- If Agrinas uses Lark's built-in Approval integration, the safest lightweight client path is to keep authentication in the official Lark flow and open the official Attendance correction request page (or an officially supported deep link, if Lark exposes one for this tenant). Do not recreate the private mobile endpoints by copying session cookies.
- If Agrinas uses a third-party approval workflow, AgriAttend can host a correction form only after an approved integration backend has tenant authorization. The backend must check allowed punch slots, create the pending remedy, run/observe the external approval, then sync the approved/rejected/withdrawn status to Lark.
- Do not implement the Lark correction action by calling AgriConnect's `/backdate/v1/request` endpoint. It is a different system.
- A Lark correction UI may collect the date, time, punch sequence, work type, and reason; it must show a pending status until Lark confirms the approval result. No local-only record should be presented as a completed Lark correction.

## APK inspection note

The uploaded Lark Android APK contains Attendance-related components, including GPS/Wi-Fi clock-in and a speed-clock-in feature. The APK alone does not reveal the Agrinas tenant's current attendance-group policy, approval form, allowed correction window, or whether the organization uses Lark Approval or an external approval system. Those are server-side tenant settings, so they cannot be inferred from the APK package alone.

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
