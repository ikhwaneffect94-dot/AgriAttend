# AgriAttend API Integration Requirements v1.6.8

## AgriConnect

The app continues to use the authenticated AgriConnect tenant API for authentication, employee profile/assignment, timesheet, attendance, correction/backdate requests, shift-change requests, payroll, and personal request status.

## SIMKOPDES Verval — hidden official form flow

The active Verval action does not open a visible SIMKOPDES website or external browser. It launches a 1x1 invisible Android WebView used as an internal page runtime. The official Verval route remains the source of truth for province and regency/city option lists, shared passcode validation, and result-table generation.

The PWA presents province and regency/city selectors inside AgriAttend. After the user selects both, a native Android activity loads the official route:

https://simkopdes.go.id/pers/asset/verval?batch_verification=1%2C2%2C3%2C4

The internal page runtime attempts the same sequence as the official page: select province, wait for district options, select district, fill the configured Verval passcode, submit the official validation form, wait for result tables, parse table headers and rows, and return normalized records to the AgriAttend card.

### Security and limits

- The shared Verval passcode is embedded in the APK to support automatic entry. It is not shown in the UI or logged, but it can be recovered by reverse-engineering the APK; rotate it if necessary.
- The hidden WebView does not bypass CAPTCHA, account login, access-denied responses, or other server-side restrictions.
- The automation depends on the official page's DOM. If labels, inputs, or buttons change, it returns a safe diagnostic summary (page title, visible selects and options, input attributes, and button labels) so selectors can be adjusted without exposing field values.
- The passcode is submitted only to the official SIMKOPDES Verval page; it is not used as a Bearer token for the public API.
- The app parses visible official table rows and maps available fields. Missing fields remain empty; it never invents batch/status/ownership/surveyor data.
- Multiple rows with the same cooperative name should be consolidated before the summary card is updated.

## Validation boundaries

- The previously supplied 1.65 MB JavaScript text file did not contain the Verval route, batch_verification, or passcode markers. Therefore this implementation uses runtime DOM discovery on the official route and needs on-device verification.
- A successful Android build proves compilation and packaging only. It does not prove that the current portal DOM selectors match or that the shared passcode is accepted.