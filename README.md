# AgriAttend Android Ready v1.7.1

AgriAttend is an Android companion client for AgriConnect HRIS.

## SIMKOPDES Verval — focused and reliable result flow

AgriAttend reads the assigned cooperative, province, and regency/city from the AgriConnect placement card. If all three are available, it starts Verval directly for that region without reopening the province/regency picker. The manual picker remains as a fallback only when the profile does not contain complete placement data.

The official SIMKOPDES Verval form runs in an invisible internal WebView. It submits the shared passcode, searches detected Batch 1–4 and Non Prioritas category controls, collects result-table rows, follows enabled pagination up to six pages per category, and maps the matching cooperative to the AgriAttend Verval card.

### v1.7.1 fixes

- Removed a duplicate run().catch(...) wrapper that could make the injected Verval JavaScript invalid.
- The hidden WebView now receives a full responsive viewport while remaining transparent and non-interactive. The earlier 1×1 viewport could cause the official responsive page to render incorrectly.
- Success, not-found, ambiguous, and error callbacks explicitly update and re-render the Verval card.
- Error responses clear the checking state, restore the action button, and show the actual error on the card.
- A 70-second client watchdog prevents an endless loading state if the native page workflow does not return a result.
- The native WebView workflow has a 60-second timeout and returns a clear failure message.
- GitHub Actions checks the Verval runner count, responsive WebView layout, callback rendering, and watchdog in addition to JavaScript syntax and Android packaging.

### Category search and limits

The page runtime visits detected Batch 1, Batch 2, Batch 3, Batch 4, Prioritas, and Non Prioritas controls and records the categories that were actually found. It follows enabled pagination up to six pages per category for a faster targeted lookup. If a category control is not exposed, or pagination exceeds the six-page limit, the result is marked as incomplete; AgriAttend does not claim that every row was checked.

The automation depends on the live SIMKOPDES page structure. It does not bypass CAPTCHA, account login, or server-side access restrictions. The shared passcode remains embedded in the APK for automatic entry and can be recovered through reverse engineering.

## AgriConnect

Authentication, employee profile/assignment, timesheet, attendance, correction/backdate requests, shift-change requests, payroll, and request status continue to use the authenticated AgriConnect tenant API.

## Build

GitHub Actions builds the debug APK, checks the PWA JavaScript and generated Verval script syntax, verifies Android manifest/version metadata, and uploads the artifact.