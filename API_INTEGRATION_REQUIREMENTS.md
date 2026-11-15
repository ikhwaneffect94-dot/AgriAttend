# AgriAttend API Integration Requirements v1.7.1

## SIMKOPDES Verval targeted retrieval

AgriAttend obtains the assigned cooperative identifier/name and location from the AgriConnect placement data already shown on the Verval card. When the card supplies province, regency/city, and cooperative assignment, no additional region picker is shown. A manual picker is available only as a fallback if any of those fields are missing.

The official SIMKOPDES Verval form is executed inside a transparent, non-interactive, full-viewport WebView. The visible user interface remains AgriAttend. The WebView uses the portal's own region/passcode form and reads the resulting official table.

### Search sequence

1. Read province, regency/city, and cooperative assignment from AgriConnect.
2. Load the official Verval route.
3. Select the target province and regency/city in the official form.
4. Submit the shared Verval passcode.
5. Visit detected Batch 1–4, Prioritas, and Non Prioritas controls.
6. Read each category table and follow enabled pagination up to six pages per category.
7. Deduplicate rows, consolidate records for the same cooperative, match the AgriConnect assignment, and return the record to the AgriAttend card.

### v1.7.1 reliability changes

- The injected JavaScript previously contained a duplicated runner wrapper; it is now emitted once.
- The internal WebView has a full responsive viewport. It remains transparent to the user but no longer renders the page in a 1×1 viewport.
- Result callbacks now render the card after success, not-found, ambiguity, or failure.
- Failure clears the checking state, restores the button, and displays error text instead of leaving the card stuck.
- A 70-second PWA watchdog resets any request that does not return a native result.
- The native automation has a 60-second timeout.
- CI checks the injected JavaScript extracted from the Java text block with node --check.

### Accuracy boundaries

The workflow searches categories and pages exposed by the current live portal DOM. Six pages per category is an intentional speed limit, not proof that a deeper result set has been exhausted. If the portal changes its form, table, tabs, or pagination markup, the request returns a diagnostic/failure state rather than silently leaving the card in checking. The app reports incomplete coverage when it cannot verify all expected categories or page traversal.

The automation does not bypass CAPTCHA, login, or server-side permissions. Data fields not present in the official table remain blank and are not fabricated.

## AgriConnect API

Authentication, employee profile/assignment, timesheet, attendance, correction/backdate requests, shift-change requests, payroll, and request status continue to use the authenticated AgriConnect tenant API.