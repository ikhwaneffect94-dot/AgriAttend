# AgriAttend API Integration Requirements v1.6.9

## SIMKOPDES Verval full-category retrieval

AgriAttend starts the official SIMKOPDES Verval page in a hidden internal WebView. The user-facing province and regency/city selectors remain in AgriAttend. The official form handles passcode verification and produces the result table; the internal page runtime reads that result and returns normalized rows to the AgriAttend card.

### Retrieval coverage

- Inspect visible tabs, buttons, links, custom tabs, and select options for Batch 1–4, Prioritas, and Non Prioritas.
- Visit every detected category/filter one by one.
- Collect visible table rows after each selection; do not stop when the first table becomes non-empty.
- Follow enabled pagination controls and collect subsequent result pages, up to 50 pages per category.
- Parse category/batch/prioritas columns alongside cooperative name, status, ownership, surveyor, coordinates, and asset count when provided.
- Deduplicate identical rows and consolidate multiple rows of the same cooperative before matching the assignment.
- Keep Non Prioritas distinct from Batch 1–4 in the result badge.
- Return coverage metadata. If the live portal does not expose all required category controls, the app marks coverage as incomplete rather than claiming that every category was searched.

### Matching

Normalize the KDKMP name from AgriConnect and compare against all records collected across categories. Records are grouped by cooperative ID where available, otherwise by normalized cooperative name. A match in Non Prioritas is valid and must be displayed as Non Prioritas.

### Limits and validation

The portal DOM may change. The hidden WebView automation reports safe metadata about tabs, dropdown options, inputs, buttons, and categories found. It does not bypass CAPTCHA, portal login, or server-side permission checks. A successful APK build proves compilation only; category completeness and result matching must be confirmed against the live portal on an Android device.