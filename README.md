# AgriAttend Android Ready v1.6.2

AgriAttend is an Android companion client for AgriConnect HRIS. Its app shell renders the bundled local PWA. SIMKOPDES Verval is retrieved with direct native HTTPS API requests; it does not open the SIMKOPDES website in a browser or nested WebView.

## Pantauan Verval

After choosing province and district/city, AgriAttend fetches:

- `cooperative-assets/get-all` for public asset records and the assigned cooperative match.
- `statistics/asset-verification-validation` for province-level asset verification/construction totals and `verification_detail` categories.
- `statistics/national/phase-2` for phase-two province × Satgas aggregate rows.

The assignment code is normalized. Example: `KDKMP_Kapuas_Pulau Petak_Teluk Palinget` is matched against the cooperative name while using `Pulau Petak` as the subdistrict hint. Multiple asset rows belonging to one cooperative are consolidated into one card.

There is an optional advanced account-scoped source:

- `cooperative-assets/by-regional-facilitator-id/{fid}` with an authorized Bearer token and the user's own regional-facilitator ID. It is optional and stored encrypted with Android Keystore. The shared Verval webpage passcode is not an API Bearer token.

### Important limitation

Current documented public endpoints do not expose individual Verval batch 1–4 or the protected Verval page's non-priority category. The app therefore displays asset statuses and province-level verification statistics separately; it does not infer batch/non-priority from those fields.

## Backdate and photo picker v1.6.2

- The attendance photo selector opens the Android system Galeri/File picker; it no longer forces the front camera.
- Backdate correction remains a formal AgriConnect request and is not a direct edit of a completed attendance record.
- The form accepts image/PDF evidence, previews selected filenames and sizes, validates each file below 5 MB, and rejects future date/time entries.
- A successful local status is recorded only after the tenant endpoint responds successfully. HR approval remains a separate server workflow.

## Build

Open in Android Studio or upload the source to GitHub. Workflow: `.github/workflows/android.yml`. It builds `:app:assembleDebug` and uploads the debug APK artifact.

See `API_INTEGRATION_REQUIREMENTS.md` for endpoint scope, response fields, security, and validation limits.
