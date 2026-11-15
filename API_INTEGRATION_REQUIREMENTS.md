# AgriAttend API Integration Requirements v1.6.6

## AgriConnect

The app continues to use the AgriConnect tenant API for authentication, employee profile/assignment, timesheet, attendance, correction/backdate requests, shift-change requests, payroll, and personal request status. Those calls use the current authenticated AgriConnect session.

## SIMKOPDES Verval — direct HTTPS, no SIMKOPDES WebView

Verval calls are made by the Android native HTTP client. It does not open the SIMKOPDES portal, create a hidden WebView, or submit the shared page passcode.

### Direct read-only endpoints

1. `GET https://api.simkopdes.go.id/api/cooperative-assets/get-all`
   - Public nested asset tree grouped by province → district → subdistrict → village.
   - Documented fields include `asset_id`, `cooperative` (name), `status`, `surveyor`, `latitude`, and `longitude`.
   - AgriAttend filters by selected province/district, assignment subdistrict hint, and normalized Kopdes name from AgriConnect.
   - Multiple asset rows for the same cooperative are grouped into one cooperative result with an asset count and combined status labels.

2. `GET https://api.simkopdes.go.id/api/statistics/asset-verification-validation`
   - Aggregate verification/construction statistics.
   - `provinces[]` contains `province_id`, `province_name`, `total_cooperative`, `completed_construction`, `completed_construction_desa`, `completed_construction_kelurahan`, `under_construction`, `under_verification`, `pending_verification`, `verified`, `verification_detail`, and `total_institution_team_with_sk`.
   - `verification_detail` includes the categories `layak_digunakan_dan_siap_operasional`, `layak_digunakan_dengan_catatan_perbaikan`, and `belum_layak`.
   - In the app this is clearly labelled as province-level aggregate data, not a status of the individual assigned cooperative.

3. `GET https://api.simkopdes.go.id/api/statistics/national/phase-2`
   - Phase-two aggregate reporting.
   - `data.national_totals`, `data.province_distribution`, `data.monthly_member_growth`, `data.cooperative_outlets`, and `data.regional_distribution` are documented by the public data extractor.
   - `province_distribution` is province × Satgas region, so a province can have more than one row. AgriAttend filters all rows for the selected province and shows Satgas, active/inactive outlets, partnership and loan-application counts where present.
   - This is phase-two reporting and must not be relabelled as Verval batch 1–4.

### Optional account-scoped assets

`GET https://api.simkopdes.go.id/api/cooperative-assets/by-regional-facilitator-id/{fid}` can return asset details for the logged-in regional facilitator when supplied with that account's authorized Bearer token. It is optional, is never required for public statistics, and uses a separate encrypted Android Keystore entry. The facilitator ID and Bearer token are not the shared Verval page passcode. The token is sent only to `api.simkopdes.go.id`; it is not sent to page/bundle requests. The endpoint response is filtered by `cooperative_id` after mapping the assigned cooperative from `/cooperatives/get-all-nested`.

### Encryption discovery

The public API uses an encrypted `data` envelope. AgriAttend follows the public extractor's discovery flow: it fetches `/pers/dashboard`, reads its `/_next/static/chunks/*.js` entries, and extracts the arguments to the `.C)(payload, secret, iv)` decrypt call. The AES-256 key is SHA-256(secret), the IV is the UTF-8 IV string, and ciphertext is decrypted as AES-CBC/NoPadding followed by strict PKCS7 removal. The candidate is validated only against the two bulk routes documented by the public extractor: `/cooperatives/get-all-nested` (`grouped[]`) and `/cooperative-assets/get-all` (`grouped` object). The Phase-2 statistics route `/statistics/national/phase-2` has returned HTTP 403 and is removed from cipher validation and normal retrieval. The public asset tree is fetched before optional provincial verification statistics, so optional-statistics denial cannot block the cooperative result. The previously observed uploaded-bundle key is retained only as a last fallback. PKCS7 unpadding mirrors the public extractor and CryptoJS implementation: the final byte determines the number of bytes removed, without an additional byte-by-byte padding consistency check. Candidate keys are deduplicated using the derived SHA-256 key and IV bytes. API GETs use four bounded attempts with a short backoff. If a user has saved an authorized SIMKOPDES API Bearer token, it is sent with all SIMKOPDES API requests from initial cipher validation onward, and only to `api.simkopdes.go.id`. A 403 on a required route is reported with its endpoint path.

The Verval region picker preselects the province/district inferred from the active AgriConnect placement and restores the user's last confirmed selection when reopening the dialog. The province/district list remains editable, has a bounded 12-second network lookup, and includes local fallback lists for Kalimantan Tengah and Kalimantan Selatan.

## Explicit data limits

The endpoints above do **not** document per-cooperative Verval batch 1–4 or the protected page's non-priority category. The statistics endpoint provides province aggregates, while the asset endpoint provides asset-level status. The app keeps these concepts separate and never infers batch or non-priority from asset status, construction progress, or phase-two totals. Exact protected-page batch data requires a separately verified endpoint/response.

## Validation boundaries

- Endpoint and response-field research is based on the independent public SIMKOPDES data extractor and a separate SIMKOPDES integration implementation; it is not an official API contract.
- GitHub Actions run `43680394178` successfully built and verified the AgriAttend v1.6.6 debug APK. This confirms source checks and Android packaging, not live SIMKOPDES data correctness.
- Live HTTP/decryption and matching against a real assigned cooperative still require testing on an authorized Android device/network.
- The main app shell still uses its bundled local WebView to render AgriAttend UI; the SIMKOPDES function itself makes direct native HTTPS calls and never opens SIMKOPDES in a WebView.
