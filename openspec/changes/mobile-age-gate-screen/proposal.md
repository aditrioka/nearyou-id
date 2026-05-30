## Why

Mobile #3 (`mobile-auth-google-signin-flow`, archived 2026-05-30) deliberately dead-ends new users: when `POST /api/v1/auth/signin` returns `404 user_not_found`, `AuthRepository` maps it to `SignInOutcome.NoAccount` and shows a temporary banner — "*Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya.*" ("register via the next app update"). **This change is that next app update.** It ships the signup-new-user flow so a verified Google identity with no existing account can create one through an 18+ age gate. Mobile #3 built `RootRouterScreen` precisely so this change interjects an `AgeGateScreen` between `SignInScreen` and `HomeScreen` by adding one router branch (no rework), and it resolves the tracked `FOLLOW_UPS.md` entry `mobile-auth-signin-404-route-to-age-gate`.

The 18+ age gate is mandated by `docs/06-Security-Privacy.md` § Age Gate (UU PDP compliance, 18+ only) and is regulatory table-stakes for this app category in Indonesia: PP 17/2025 ("PP TUNAS", the child-protection electronic-systems regulation, in effect since ~March 2026) specifically governs apps that let children communicate with anonymous/pseudonymous strangers — which a location-based, pseudonymous-username, nearby-discovery + 1:1-chat app is. An 18+ posture is the cleanest way to comply.

## What Changes

- **NEW: `AgeGateScreen` (Voyager `Screen`) in `:mobile:app` commonMain** — a date-of-birth picker (Material 3 `DatePicker`) + create-account CTA, theme-aware and consistent with `SignInScreen`/`HomeScreen`. Reached when sign-in reports no existing account.
- **NEW: signup orchestration in `AuthRepository`** — `signUpWithGoogle(dateOfBirth)` composing the verified Google identity → `POST /api/v1/auth/signup` → token persistence → a `SignUpOutcome` sealed type with no generic fallthrough. New `AuthApiClient` signup request DTO (snake_case `@SerialName`, `device_fingerprint_hash` omitted).
- **NEW: routing branch** — `RootRouterScreen` / navigator interjects `AgeGateScreen` between `SignInScreen` and `HomeScreen`.
- **MODIFIED routing of the `404 user_not_found` path** — instead of rendering the `signin_error_no_account` banner and staying on `SignInScreen`, sign-in now navigates to `AgeGateScreen` carrying the verified Google identity. The `signin_error_no_account` string is retired (or repurposed to a narrow network-edge-case path).
- **NEW: Bahasa Indonesia strings** in `:shared:resources` (`composeResources/values/strings.xml`, accessed via `stringResource(Res.string.X)`): age-gate title, DOB label/picker CTA, create-account CTA, under-18/blocked copy ("*Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas.*"), account-exists, network, and token-invalid error copy. No hardcoded UI string literals.
- **NO new backend work, NO new library pins** — backend `age-gate` + `auth-signup` capabilities shipped; `kotlinx-datetime` and Material 3 are already on `:mobile:app`'s classpath.

## Capabilities

### New Capabilities
- `mobile-age-gate`: the mobile signup-new-user flow — `AgeGateScreen` (DOB picker + 18+ gate), `AuthRepository.signUpWithGoogle` orchestration against `POST /api/v1/auth/signup`, the `SignUpOutcome` → user-facing-copy mapping (201 / 403 `user_blocked` / 409 `user_exists` / 401 `invalid_id_token` / 5xx-network), and the routing that places it between sign-in and Home.

### Modified Capabilities
- `mobile-auth-signin`: the `404 user_not_found` handling in the "Backend error codes mapped to user-facing copy" requirement changes from "show `signin_error_no_account` banner, stay on `SignInScreen`" to "navigate to `AgeGateScreen` carrying the verified Google identity."
- `shared-resources`: the "Foundational Bahasa Indonesia string surface" requirement is extended with the Mobile #4 age-gate / signup strings (`age_gate_title`, `age_gate_explainer`, `age_gate_dob_label`, `age_gate_dob_picker_cta`, `cta_create_account`, `age_gate_under18_blocked`, `signup_error_account_exists`, `signup_loading`); no Mobile #2/#3 string is altered.

## Impact

- **Code**: `:mobile:app` commonMain (`AgeGateScreen`, `AuthRepository`, `AuthApiClient` signup DTO, `RootRouterScreen` branch, Koin `mobileModule`); `:shared:resources` strings. No `androidMain`/`iosMain` platform-actual changes are required beyond what Mobile #3 already shipped (the Google ID token is reused from the sign-in ceremony).
- **APIs consumed**: `POST /api/v1/auth/signup` (already shipped, snake_case wire contract pinned by `AuthWireFormatTest`). No backend changes.
- **Dependencies**: none added — `kotlinx-datetime` (`mobile/app/build.gradle.kts:77`) and Material 3 `DatePicker` already in use.
- **Security surface**: client-side age-gate UX, anti-DOB-shopping correctness (under-18 DOB must reach the server so `rejected_identifiers` populates — the client must not hard-block), privacy-preserving rejection (byte-identical `403` bodies → one generic blocked message), and PII discipline (no Google email/`displayName` in age-gate UI).
- **FOLLOW_UPS.md**: resolves `mobile-auth-signin-404-route-to-age-gate` (at archive); adds `mobile-age-gate-stronger-verification` (Apple Declared Age Range API / Google Play Families SDK cross-checks deferred — self-declared DOB ships first).
- **Out of scope**: Apple Sign-In iOS (`mobile-auth-signin-apple-ios`, stays a separate change); stronger age *verification*; attestation `device_fingerprint_hash`; **the post-age-gate Analytics & Tracking Consent screen and the Location Permission screen** that `docs/03-UX-Design.md` § User Onboarding Flow sequences after the age gate — Mobile #4 routes a successful signup straight to `HomeScreen` (parity with Mobile #3's sign-in terminus); those screens are later mobile changes (see the design Open Question + the FOLLOW_UPS entry this change adds for the deferred onboarding sequence).
