# AgriAttend API Integration Requirements v1.6.7

## AgriConnect

The app continues to use the AgriConnect tenant API for authentication, employee profile/assignment, timesheet, attendance, correction/backdate requests, shift-change requests, payroll, and personal request status. Those calls use the current authenticated AgriConnect session.

## SIMKOPDES Verval — official in-app portal

The active Verval flow no longer attempts to reconstruct the protected verification tables from the public bulk asset/statistics APIs. The previous direct-API attempt did not reproduce the page's required region/passcode workflow and repeatedly failed to return the user's full Verval result.

The Verval button opens the official route inside `SimkopdesVervalActivity`:

`https://simkopdes.go.id/pers/asset/verval?batch_verification=1%2C2%2C3%2C4`

The user follows the portal's own order:
1. Select province.
2. Select regency/city.
3. Enter the Verval passcode in the official page.
4. Verify and view the official result tables.

### WebView behavior

- JavaScript and DOM storage are enabled for the official application.
- WebView cookies are accepted, including third-party cookies, so the official site can keep its own session.
- Only HTTPS pages hosted on `simkopdes.go.id` or its subdomains remain in the in-app Verval activity as top-level navigations. External top-level links open with Android's normal URL handler.
- Passcode entry is handled by the official page. AgriAttend does not store, log, prefill, or send the passcode as an API authorization token.
- The app does not fabricate Batch 1–4, non-priority, or other protected results from public aggregates.

### Result presentation boundary

Version 1.6.7 displays the complete official Verval page/table inside AgriAttend. It does not yet translate individual portal table cells back into the compact summary card. That follow-on mapping requires a screenshot or sample of a successful, authorized result page so the real row/column structure can be matched accurately.

## Legacy direct SIMKOPDES client

The previous read-only direct client remains in source for reference but is no longer called by the Verval button. Its public asset tree and aggregate statistics are not treated as substitutes for the passcode-protected Verval page.

## Build and validation

- GitHub Actions validates JavaScript syntax and Android manifest/version metadata, compiles the APK, verifies the APK output, and uploads the artifact.
- A successful build proves packaging only. Live website navigation, passcode acceptance, and result rendering must be verified on an Android device with network access.
