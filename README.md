# AgriAttend Android Ready v1.6.9

AgriAttend is an Android companion client for AgriConnect HRIS.

## SIMKOPDES Verval — full category search

AgriAttend presents province and regency/city selectors in its own UI. A hidden internal WebView runs the official SIMKOPDES Verval page flow, submits the shared passcode, and reads the official result table. No SIMKOPDES browser/page UI is shown to the user.

Version 1.6.9 expands result retrieval beyond the first visible table:
- Detect and visit visible tabs, buttons, links, and select options for Batch 1, Batch 2, Batch 3, Batch 4, Prioritas, and Non Prioritas.
- Collect table rows after each category/filter change instead of stopping after the first non-empty table.
- Follow visible pagination controls and gather rows from subsequent pages, with a per-category page limit.
- Preserve category labels on each row and collect coverage metadata.
- Deduplicate repeated rows, then consolidate rows belonging to the same cooperative before matching the AgriConnect assignment.
- Display Non Prioritas as its own category in the Verval card; do not relabel it as a numbered batch.
- If the portal does not expose a category control, report which categories were detected and do not claim complete coverage.

The automation still depends on the live portal DOM and requires a device test. It does not bypass CAPTCHA, account login, or server-side access restrictions. The shared passcode remains embedded in the APK for automatic entry and can be recovered by reverse engineering.

## AgriConnect

Authentication, employee profile/assignment, timesheet, attendance, correction/backdate requests, shift-change requests, payroll, and request status use the authenticated AgriConnect tenant API.

## Build

GitHub Actions builds :app:assembleDebug and uploads the debug APK artifact.