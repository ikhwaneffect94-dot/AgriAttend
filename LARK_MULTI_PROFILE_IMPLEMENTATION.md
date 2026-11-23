# Lark Multi-Profile Login and Attendance — AgriAttend

Status: verified integration design; tenant-specific OAuth and attendance access are not configured yet.

## 1. What the user sees in Lark

Lark's help documentation confirms that when an email or phone number is associated with more than one Lark account, the user completes verification and then can select which account to log in to. Two-step verification can add a further verification step depending on the account's security configuration.

That means AgriAttend must not build a fake account selector based only on email text. The login UI is controlled by Lark. AgriAttend should receive the OAuth result for the account the user actually selected.

The two profiles described by the user can represent either:
- Two separate Lark accounts registered with the same email; or
- One Lark identity that is a member of more than one organization (tenant).

Both cases need tenant-aware validation. The exact case is determined by the authorization response from the official Lark login flow.

## 2. Supported authentication routes

### Route A — Android Mobile App Login (preferred)

1. User enters their registered Lark email in AgriAttend.
2. AgriAttend invokes the official Lark Mobile App Login SDK.
3. The Lark client handles any needed email/password/OTP verification and any account/identity choice that its own sign-in flow presents.
4. User approves AgriAttend's authorization request.
5. Lark returns a short-lived authorization code to the Android app.
6. AgriAttend sends the code and a one-time state/PKCE verifier to the HTTPS integration backend.
7. Backend validates state, exchanges the code using App ID/App Secret, retrieves the authorized user's identity, and maps the returned tenant context.
8. Backend issues a short-lived AgriAttend session. App Secret and Lark refresh/access tokens stay on the backend.

The Mobile App Login feature must be enabled in the Custom App and Android app information configured in Lark Developer Console.

Important: The Mobile App Login documentation describes invoking the Lark app and returning an authorization code after the user approves. It does not guarantee that the SDK will always show the email-and-OTP screen if a Lark account is already signed in. If it uses the current active Lark account, switch to the Agrinas identity in the Lark client and authorize again. Do not rely on undocumented SDK flags to force an account picker.

### Route B — Official browser OAuth (fallback)

1. AgriAttend opens Lark's official authorization/login page using the registered redirect URL and a random state.
2. If the browser requires authentication, Lark handles email/OTP/password and any multi-account chooser.
3. Lark redirects to the registered HTTPS callback with an authorization code and state.
4. Backend validates state and exchanges the code for user authorization.
5. Backend checks the tenant context and issues an AgriAttend session only for the approved Agrinas tenant.

This route is useful when the native Lark app is not available or the mobile SDK cannot be integrated. It is still Lark-hosted authentication; AgriAttend never receives the Lark password or OTP.

Lark's documented web authorization code is short-lived (5 minutes) and single-use. A new login must create a new state and authorization code.

## 3. How AgriAttend identifies the Agrinas profile

Email is not the tenant identity and must not be used as the Attendance user key.

The backend must verify the identity data returned by Lark:
- `tenant_key`: identifies the organization/tenant.
- `user_id`: employee identity inside that tenant; the same person may have a different user_id in another tenant.
- `open_id`: identity of the user inside this specific app.
- `union_id`: user identity across apps under the same app provider, when available and appropriate.

Required logic:
1. Configure the verified Agrinas tenant key on the backend as `AGRINAS_TENANT_KEY`.
2. After OAuth, compare the returned tenant context to this exact value.
3. Require the tenant to have installed/approved the AgriAttend Custom App.
4. Require a valid tenant-scoped employee `user_id` for Attendance operations.
5. If the selected account is the main/personal profile or any tenant other than Agrinas, reject the Attendance session with a clear message: "Profil yang dipilih bukan profil Agrinas. Kembali ke Lark, pilih profil Agrinas, lalu masuk lagi."
6. Do not show an active Clock In/Clock Out control until the backend confirms the correct tenant and employee mapping.

Do not guess the Agrinas tenant key from the company name. It must be read from an authorized Lark app-install event/admin console or a verified OAuth response for an authorized Agrinas identity.

## 4. Attendance API finding — critical distinction

The official Lark API permission list describes `attendance:task` as permission to write punch data.

The documented `POST /open-apis/attendance/v1/user_flows/batch_create` operation is described as **import attendance flow records**. Its schema includes tenant-scoped `user_id`, `creator_id`, `location_name`, `check_time`, `comment`, optional `ssid`/`bssid`, `is_field`, `is_wifi`, and a record `type`. The operation uses a `tenant_access_token`, not just the employee's user token. Lark says the attendance result is calculated from the employee's attendance group/shift rules after importing.

Important limits in that API schema:
- It is an import/write-record interface, not documented as the same live mobile "Clock in" transaction used by the Lark Attendance client.
- The `photo_urls` field is marked unsupported in the current generated API schema.
- The documented request does not expose the same native mobile GPS/geofence challenge and photo-upload flow shown in the Lark Attendance app.
- It therefore must not be presented as a fully equivalent replacement for native Lark Clock In/Clock Out until Agrinas HR/Lark administrators explicitly approve this specific integration pattern and confirm its operational rules.

This gives two possible approved attendance implementations:
- **Native-equivalent route:** use the official Lark Attendance client for the final punch. AgriAttend can be the lightweight companion for account/profile checks and launch the supported Lark flow, if Lark provides an approved app link/SDK entry point for the tenant.
- **Admin-approved API-import route:** only if Agrinas HR authorizes attendance record imports, the backend uses a tenant token, maps the authenticated employee to the exact Agrinas user_id, validates actual GPS/Wi-Fi and timing against the official rule configuration, records the imported nature of the flow, and reports success only after Lark confirms acceptance. This is not the same as bypassing geofence checks.

No private Lark client endpoints, session cookies, APK-extracted tokens, or spoofed device/location signals are to be used.

## 5. Keep sign-in and app performance light

- Use Lark's native Mobile App Login when available; fallback to the official browser OAuth page.
- Keep App Secret and token exchange on a small HTTPS backend.
- Store only a short-lived AgriAttend session on Android Keystore.
- If `offline_access` is approved, refresh tokens on the backend and rotate them safely; do not ask the user for OTP at every app launch.
- Cache only non-sensitive display data such as name/avatar/tenant label, and refresh when needed.
- Keep AgriConnect login/session separate from Lark login/session.
- Do not mark an offline or queued punch as completed until Lark confirms that the server has accepted it.

## 6. Minimum administrator inputs

1. Custom App App ID.
2. App Secret, shared through an approved secrets channel only (never committed to GitHub or sent in chat).
3. Mobile App Login feature enabled, Android package/signing details and official callback/redirect URL configured.
4. App installed/approved in the Agrinas tenant.
5. Verified Agrinas `tenant_key`.
6. Approved user scopes, at minimum the scopes required for identity and refresh (for example `auth:user.id:read` and `offline_access`, where the relevant API requires them).
7. Tenant approval for `attendance:task` and an explicit decision whether imported attendance flows are an accepted business process.
8. Confirmed native Attendance launch URL/SDK entry point if native Lark punching must be retained.
9. Confirmed GPS/Wi-Fi, photo, face-recognition, shift and out-of-office policies.

## 7. Test matrix before release

- Email with one Lark account.
- Email with both main and Agrinas account choices.
- Choose main profile: backend must reject it for Agrinas Attendance.
- Choose Agrinas profile: backend must verify tenant_key and employee user_id.
- Existing Lark session already active under main profile: verify switch/re-authorization behavior.
- Lark app unavailable: test official browser OAuth fallback.
- Expired access token: rotate refresh token on backend.
- Wrong tenant, missing user_id, revoked app access, or uninstalled Custom App: show actionable error and never create an Attendance record.
- Out-of-range, missing Wi-Fi, required photo/face verification, and network outage: follow tenant policy and never report false success.

## References

- Lark Mobile App Login: https://open.larksuite.com/document/uAjLw4CM/uYjL24iN/mobile-app/mobile-app-overview
- Log in and out; multiple accounts bound to same email: https://www.larksuite.com/hc/en-US/articles/360048488469-log-in-and-log-out-of-your-lark-account
- User identification and tenant-scoped user_id: https://open.larksuite.com/document/home/user-identity-introduction
- Lark OAuth login flow: https://open.larksuite.com/document/ukTMukTMukTM/uETOwYjLxkDM24SM5AjN
- Lark attendance permission and app setup: https://open.larksuite.com/document/home/quick-start-of-personnel-and-attendance-management-system/step-1-create-and-configure-an-application
- Official OpenAPI attendance flow schema: https://github.com/larksuite/lark-openapi-mcp/blob/main/src/mcp-tool/tools/zh/gen-tools/zod/attendance_v1.ts
- Refresh user_access_token: https://open.larksuite.com/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/refresh-user-access-token
