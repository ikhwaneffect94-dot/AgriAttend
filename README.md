# AgriAttend Android Ready v1.7.4

AgriAttend is an Android companion client for AgriConnect HRIS.

## SIMKOPDES Verval

AgriAttend reads the placement hierarchy and assigned cooperative from the AgriConnect employee profile. The lookup distinguishes Province, Regency/City, Kecamatan, and the cooperative search phrase.

Expected example for this assignment:
- Province: Kalimantan Tengah
- Regency: Kabupaten Kapuas
- Kecamatan: Pulau Petak
- Cooperative search phrase: Teluk Palinget

The cooperative search phrase is created separately from the raw KDKMP code: remove the KDKMP prefix, remove known region components, and retain the final two cooperative-name words. The automation sends Province → Regency/City → Kecamatan to the official SIMKOPDES Verval page before entering the shared passcode.

The official page is opened inside a transparent, non-interactive WebView. The app removes the old Batch 1–4-only URL query so the portal can expose Non Prioritas. Detected Non Prioritas is scanned first, followed by other detected categories if the assigned cooperative is not found. Table filtering and matching are still dependent on the live portal structure and the account's permission.

The app does not bypass sign-in, CAPTCHA, or server-side restrictions. If the portal does not expose cooperative-level records to the current account, AgriAttend reports the result as unavailable rather than fabricating a match.

## v1.7.4 changes

- Added a separate Kecamatan field to placement extraction and the official portal selection flow.
- Added a Kecamatan selector to the manual fallback region picker.
- Corrected cooperative-name parsing to search the final two words, such as **Teluk Palinget**, instead of the KDKMP identifier.
- Removed the Batch 1–4-only query parameter from the official Verval URL.
- Removed the Home statistic card for saved work locations.
- Added a small `© ikhwan_effect` footer credit.
- Checked user-facing source strings for explicit AI-related wording; none was found in the inspected UI files.

## AgriConnect

Authentication, profile/assignment, timesheet, attendance submission, correction requests, and payroll use the configured AgriConnect tenant APIs.
