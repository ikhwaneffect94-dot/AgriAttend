# AgriAttend Android Ready v1.6.7

AgriAttend is an Android companion client for AgriConnect HRIS. The main app shell renders the bundled local PWA. Pantauan Verval now opens the **official SIMKOPDES Verval page inside AgriAttend** instead of trying to reconstruct protected Verval data from unrelated public APIs.

## Pantauan Verval — official portal flow

From the AgriAttend Verval card, tap **Buka Portal Verval**. The app opens:

`https://simkopdes.go.id/pers/asset/verval?batch_verification=1%2C2%2C3%2C4`

Follow the official portal sequence:

1. Select the province.
2. Select the regency/city.
3. Enter the Verval passcode on the official SIMKOPDES page.
4. Submit/verify and view the result tables returned by SIMKOPDES.

The passcode is entered directly into the official page. AgriAttend does not prefill, store, log, or convert it into a Bearer token. The in-app WebView enables JavaScript, DOM storage, and cookies so the official site can run its own form and preserve its own session. A toolbar provides Back and Reload.

The Verval result tables currently remain rendered by the official SIMKOPDES page inside the app. AgriAttend does not yet parse those protected tables back into the compact cooperative summary fields; that mapping must be based on a real successful result page, not guessed field names.

## AgriConnect

Authentication, employee profile/assignment, timesheet, attendance, correction/backdate requests, shift-change requests, payroll, and personal request status continue to use the authenticated AgriConnect tenant API.

## Build

Open in Android Studio or push the source branch. Workflow: `.github/workflows/android.yml`. It builds `:app:assembleDebug` and uploads the debug APK artifact.

See `API_INTEGRATION_REQUIREMENTS.md` for the official portal flow and validation boundaries.
