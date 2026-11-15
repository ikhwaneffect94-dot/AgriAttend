# AgriAttend Android Ready v1.6.8

AgriAttend is an Android companion client for AgriConnect HRIS. Its main interface remains the bundled local PWA.

## Pantauan Verval — automatic in-app retrieval

The user selects province and regency/city in AgriAttend. The app then runs the official SIMKOPDES Verval page flow in an invisible internal WebView. No SIMKOPDES page or browser UI is shown.

The hidden workflow:
1. Opens the official Verval route with the requested batch parameter.
2. Detects and selects the official province option.
3. Waits for and selects the official regency/city option.
4. Fills the shared Verval passcode in the official page's passcode field.
5. Submits the official verification form.
6. Reads visible result table rows and maps them into AgriAttend records.
7. Consolidates multiple asset rows under the same cooperative and matches the assignment name from AgriConnect.

The passcode is not displayed in the AgriAttend UI and is not written to application logs. It is embedded in the APK for automatic submission; anyone able to reverse-engineer the APK may be able to recover it, so rotate the shared passcode if its exposure becomes a concern.

If the portal changes its DOM, the automation returns safe diagnostics about visible dropdowns, input attributes, and buttons. It does not bypass CAPTCHA, account login, denied access, or other server-side access controls. Those conditions are reported as a failure and require an authorized workflow.

## Data presentation

On success, AgriAttend maps the matching cooperative's official table row into its Verval card, including available batch, status, asset count, coordinates, ownership, and surveyor fields. Fields absent from the official result remain blank instead of being fabricated.

## AgriConnect

Authentication, employee profile/assignment, timesheet, attendance, correction/backdate requests, shift-change requests, payroll, and personal request status continue to use the authenticated AgriConnect tenant API.

## Build

Open in Android Studio or push the source branch. The GitHub Actions workflow builds the debug APK and uploads the artifact.