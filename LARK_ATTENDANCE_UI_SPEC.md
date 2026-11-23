# Lark Attendance UI Workflow — AgriAttend

Source: three user-provided screenshots of the Lark Android app, dated 2026-11-23. This document records only what is visible in those screenshots; it is not an API specification.

## 1. Attendance screen

Visible elements:
- Date selector: `2026.11.23`.
- Employee greeting and profile name.
- Work schedule summary: `Working hours: 8`.
- `Hours worked so far` counter.
- Separate `Clock in` and `Clock out` sections.
- Live clock display.
- In the supplied example, Clock in is `Unavailable` and the message is `Out of range`, with a `Check` action.
- Bottom navigation: Attendance, Requests, Reports, Settings.
- A `Rules` link appears near the employee profile.

Implementation notes:
- AgriAttend may reproduce this layout and provide an attendance status panel.
- The `Out of range` state must be treated as a server/tenant geofence restriction. The alternative app must not bypass or falsify that validation.
- Work schedule, hours worked, rule text, clock-in eligibility, and accepted location should come from an authorized Lark source when available; they must not be hardcoded.

## 2. Requests screen

Visible request categories:
- Correction
- Leave
- Overtime
- Out of office
- Business trip
- Shift swap
- Request records

Implementation notes:
- These are separate workflows. They should not be collapsed into one generic request form.
- Each category needs its own verified form schema, approval behavior, and API route before it can be enabled.

## 3. Clock-in/out correction form

Visible required fields:
- Date of error
- Original record
- Replenishment time
- Reason for correction
- Submit action
- Approval Process section, whose details are not shown until required information is filled in.

Implementation notes:
- Keep the required fields and validation behavior.
- Approval routing and submission status must be retrieved from Lark/tenant configuration or approved workflow API.
- Do not fabricate approval status or create a local-only correction that appears submitted.

## 4. Evidence still required for end-to-end integration

The screenshots establish the visible user workflow, but they do not disclose:
- Lark App ID / App Secret or OAuth configuration.
- Tenant ID, authorized API scopes, or employee identity mapping.
- Attendance punch API endpoint and request schema available to this tenant.
- Geofence/rule configuration, schedule identifiers, Wi-Fi/BSSID checks, selfie/face rules, or whether clock-in requires other device integrity signals.
- Request form field identifiers, approval definitions, and API endpoints.

Before enabling actual Clock in/Clock out, obtain a tenant-approved custom app and backend OAuth configuration, then validate the permitted punch API with an administrator in a test tenant. Never extract or reuse private session cookies, tokens, or secrets from the Lark client APK.

## 5. AgriAttend implementation sequence

1. Preserve the existing AgriConnect login and attendance path.
2. Add a distinct **Login Lark** flow using official OAuth/mobile authorization and a backend callback.
3. Show Lark account identity only after the backend verifies it.
4. Implement read-only attendance status/rules/schedule first, if the tenant exposes the required APIs.
5. Test the authorized punch endpoint and geofence behavior.
6. Enable Clock in/Clock out only after server-confirmed success.
7. Add Correction and other request categories independently after their form schema and approval API are verified.
