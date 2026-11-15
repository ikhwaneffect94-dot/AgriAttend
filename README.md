# AgriAttend Android Ready v1.7.6

AgriAttend is an Android companion client for AgriConnect HRIS.

## SIMKOPDES Verval

AgriAttend reads the assigned cooperative and placement hierarchy from the AgriConnect employee profile. It separates Province, Regency/City, Kecamatan, and the cooperative name used for search.

Example target:
- Province: Kalimantan Tengah
- Regency: Kabupaten Kapuas
- Kecamatan: Pulau Petak
- Cooperative search phrase: Teluk Palinget

The assignment parser requires a real KDKMP/KDMP/cooperative value or a meaningful assignment field. Generic loading labels such as "Memuat data penugasan" are not accepted as cooperative names.

The official Verval page runs in an internal transparent, non-interactive WebView. Region selection uses Province → Regency/City → Kecamatan before the shared passcode is entered. Non Prioritas is scanned first, followed by other detected categories. The official page's current structure and the account's access rights determine whether individual rows are available.

## v1.7.6 changes

- Add the missing `normalizeRegionName()` helper as a shared alias to `normalizeRegionText()`. This prevents profile hydration and login from failing with a JavaScript `ReferenceError` when placement-region matching runs.
- Bump the service-worker cache and Android version so installed clients fetch the corrected JavaScript bundle.

## v1.7.5 changes

- Preserve an encrypted AgriConnect session when profile/tenant data requests fail temporarily; a transient network error no longer deletes the saved session.
- Rehydrate a saved Android session when the user opens Verval and the in-memory session is absent.
- Restore cached employee/profile placement fields before requesting a fresh profile.
- Reject generic loading/status text as a KDKMP placement name.
- Re-render the placement card after profile details have loaded.
- Invalidate the stale v1.7.1 service-worker cache and activate the updated app shell.
- Keep the Home saved-work-location statistic removed and the small `© ikhwan_effect` footer credit.

The application does not bypass login, CAPTCHA, or server-side permission checks. No individual Verval result is fabricated when the official portal does not return a permitted record.

## AgriConnect

Authentication, profile/assignment, timesheet, attendance submission, correction requests, and payroll use the configured AgriConnect tenant APIs.
