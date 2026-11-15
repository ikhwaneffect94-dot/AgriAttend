# AgriAttend Android Ready v1.6.4

AgriAttend is an Android companion client for AgriConnect HRIS. Its app shell renders the bundled local PWA. SIMKOPDES Verval is retrieved with direct native HTTPS API requests; it does not open the SIMKOPDES website in a browser or nested WebView.

## Pantauan Verval

The Verval dialog detects the assigned province/district from the active AgriConnect placement and preselects it when available. The last confirmed region selection is retained, the user can still change it, and the lookup has a 12-second timeout. If the regional lookup service is unavailable, fallback district lists are provided for Kalimantan Tengah and Kalimantan Selatan. AgriAttend then fetches:

- `cooperative-assets/get-all` for public asset records and the assigned cooperative match.
- `statistics/asset-verification-validation` for province-level asset verification/construction totals and `verification_detail` categories.
- `statistics/national/phase-2` for phase-two province × Satgas aggregate rows.

The API response decryptor follows the live public extractor flow: it reads JavaScript chunks from `/pers/dashboard`, extracts the exact `.C)(` secret/IV call, derives SHA-256(secret), and decrypts AES-256-CBC before strict PKCS7 removal. The key is validated first against `/cooperatives/get-all-nested`, whose `grouped[]` response shape is used by the same extractor. The prior uploaded-bundle parameter is only a final fallback. The assignment code is normalized. Example: `KDKMP_Kapuas_Pulau Petak_Teluk Palinget` is matched against the cooperative name while using `Pulau Petak` as the subdistrict hint. Multiple asset rows belonging to one cooperative are consolidated into one card.

There is an optional advanced account-scoped source:

- `cooperative-assets/by-regional-facilitator-id/{fid}` with an authorized Bearer token and the user's own regional-facilitator ID. It is optional and stored encrypted with Android Keystore. The shared Verval webpage passcode is not an API Bearer token.

### Important limitation

Current documented public endpoints do not expose individual Verval batch 1–4 or the protected Verval page's non-priority category. The app therefore displays asset statuses and province-level verification statistics separately; it does not infer batch/non-priority from those fields.

## Build

Open in Android Studio or upload the source to GitHub. Workflow: `.github/workflows/android.yml`. It builds `:app:assembleDebug` and uploads the debug APK artifact.

See `API_INTEGRATION_REQUIREMENTS.md` for endpoint scope, response fields, security, and validation limits.
