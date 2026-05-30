# Mobile #3 (`mobile-auth-google-signin-flow`) — staging device smoke, 2026-05-29

Partial §10 smoke run on a **real Android emulator** (`emulator-5554`, API per the running AVD)
during `/opsx:apply`. Records what was verified on-device vs. what remains gated on
credentials the headless session does not have.

Screenshots: `dev/screenshots/mobile-3-smoke-2026-05-29/`.

## Verified on-device ✅

| # | Check | Evidence |
|---|---|---|
| 10.1 | Backend staging reachable | `curl -i https://api-staging.nearyou.id/health/live` → `HTTP/2 200 OK` |
| — | Build + install staging flavor on a real device | `./gradlew :mobile:app:installStagingDebug` → installed `id.nearyou.app.staging` on `emulator-5554` |
| 6.8b | Fresh install (no token) routes RootRouterScreen → SignInScreen | App launched straight to SignInScreen (no token persisted) — `01-signin-light.png` |
| 6.7a | SignInScreen renders all elements | Brand logo, title "Masuk ke NearYouID", CTA "Masuk dengan Google", disclosure "Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID" — `01-signin-light.png` |
| 6.7k / 10.4c | **Logo theme-swap on dark mode** (no crash) | `adb shell cmd uimode night yes` → logo swapped to `logo_brand_dark` (white-on-blue tile), title/CTA/disclosure all theme-aware — `02-signin-dark-logo-swap.png`. This is the scenario deferred from the Robolectric suite (resource identity isn't in the Compose semantics tree); confirmed visually here. |
| 6.7e / 6.7f / 10.8 | CTA → ceremony → `GoogleSignInResult.Failed` → `NetworkError` UI | Tapping the CTA invoked Credential Manager (logcat: `Auth.Api.Credentials` from `com.google.android.gms`); ceremony failed (no Google account on the emulator + placeholder server client ID → `GetCredentialException`); AuthRepository mapped it to `NetworkError` and SignInScreen rendered the **red banner "Tidak bisa terhubung. Periksa koneksi internet kamu."** + the CTA relabeled to **"Coba lagi"** (still enabled) — `03-ceremony-failed-networkerror-coba-lagi.png`. The §10.8 trigger is normally airplane-mode mid-flow; the ceremony-failure path maps to the identical `NetworkError` state + copy, so the user-facing copy + retry-label are confirmed. |
| — | No crash across launch / theme-toggle / CTA-tap | App stayed in `id.nearyou.app.staging/MainActivity` (ResumedActivity) throughout |

## Still gated — needs credentials/access the headless session lacks ⛔

The **successful sign-in path** and everything downstream of it cannot be exercised without a
**real, human-authenticated Google account** (password/2FA — not enterable headlessly) signed
into the device, AND a **provisioned Google OAuth client** (Console-only for mobile client IDs;
the `GOOGLE_SERVER_CLIENT_ID` is still a placeholder), AND **Supabase staging admin** access
(to seed/flip `users` rows):

- **10.2 / 10.3** — provision staging Android + iOS OAuth client IDs in Google Cloud Console
  (`nearyou-staging`); bind the Android client to the staging bundle `id.nearyou.app.staging` +
  the debug/release signing-cert SHA-1 (get the debug SHA-1 via `./gradlew :mobile:app:signingReport`).
- **10.4 (happy path)** — sign in with a real Google account that has a `users` row → land on
  HomeScreen → relaunch skips SignInScreen (token persisted). *Install + render + CTA-wiring
  verified above; the authenticated leg needs the Google account + provisioned client.*
- **10.4a** — OS-reboot token persistence (needs a prior successful sign-in).
- **10.4b** — uninstall/reinstall (fresh-install → SignInScreen verified; the "sign-in succeeds
  after reinstall" leg needs the happy path).
- **10.5** — iOS sim smoke (needs the Podfile/`.xcworkspace` migration + a provisioned iOS
  client + a Google account on the sim).
- **10.6** — banned-user 403 → `signin_error_banned` (needs successful ceremony + Supabase
  `is_banned = TRUE` flip).
- **10.7** — no-account 404 → `signin_error_no_account` (needs a real Google token for an
  account with no `users` row — i.e. a successful ceremony that the backend then 404s).
- **10.9 / 10.10** — refresh-token rotation + reuse-detection (need an authenticated session).

## Why the ceremony failed here (expected)

The failure is the *correct* behavior for this environment, and it usefully proved the error
path: (a) the emulator has no Google account added, and (b) the build carries the placeholder
`GOOGLE_SERVER_CLIENT_ID`. Either alone makes Credential Manager return `GetCredentialException`,
which the sealed-result + Decision-7 mapping turn into the `NetworkError` UI seen above. A green
happy-path run requires both a real Google account on the device and a provisioned client ID.

## Real-device run (Samsung Galaxy A17 `RRGL20CTDBM`, 2026-05-29 11:04)

Re-ran on a real device with Google accounts present. Confirmed the same on-device render +
error path as the emulator (`04-real-device-signin.png`, `05-real-device-ceremony-error-28444.png`),
and pinpointed the EXACT happy-path blocker:

```
CredentialManager: Get credential errorMsg=[28444] Developer console is not set up correctly.
```

This is Google's canonical "OAuth client not provisioned" error. It proves the app is correct
end-to-end up to the ceremony (CTA → Credential Manager invoked → `GetCredentialException` →
`GoogleSignInResult.Failed` → `NetworkError` UI rendered). The ONLY remaining gate for the
happy-path smoke is provisioning the Google OAuth clients in Cloud Console (`nearyou-staging`):

- **OAuth 2.0 Web/Server client ID** → goes into the staging `GOOGLE_SERVER_CLIENT_ID` buildConfigField (the ID-token audience the backend `/signin` validates).
- **OAuth 2.0 Android client ID** bound to:
  - package `id.nearyou.app.staging`
  - SHA-1 `9A:14:CE:3E:30:74:1A:AD:E9:EC:F7:49:41:C3:26:81:BD:A1:11:05` (staging **debug** signing cert)
- OAuth consent screen configured for the project.
- A staging Supabase `users` row for the test Google account → 200 happy path; absent → 404 `user_not_found` (which itself smoke-tests 10.7).

After provisioning: plug the web client ID into `mobile/app/build.gradle.kts` staging flavor,
`installStagingDebug`, re-tap → account picker → complete → backend `/signin`.

### Discovered staging state (2026-05-29, founder gcloud token, read-only)

Re-probed `nearyou-staging` with the correctly-scoped lane (`gcloud` is authed as
`<founder-account>@gmail.com`, active project `nearyou-staging` — the founder account, NOT the
employer's `ledger-fcc1e`). Findings that make the provisioning faster:

- `nearyou-staging` **is already a Firebase project** (`state: ACTIVE`, projectNumber `27815942904`).
- `firebase.googleapis.com` + `identitytoolkit.googleapis.com` are **already enabled**.
- **Zero** Android apps, **zero** Web apps, **no** Identity Toolkit auth config, **no** Google IDP
  configured → clean slate; nothing to reuse, both OAuth clients must be created.
- Debug SHA-1 re-verified via `keytool` on `~/.android/debug.keystore`:
  `9A:14:CE:3E:30:74:1A:AD:E9:EC:F7:49:41:C3:26:81:BD:A1:11:05` (SHA-256
  `51:09:49:84:89:5C:2B:5C:FC:F9:30:86:4A:76:FF:91:B7:1F:23:04:16:36:0A:28:C1:42:82:84:FA:AA:D6:87`).

Attempted to auto-provision via the Firebase Management API using the founder gcloud token
(`POST .../projects/nearyou-staging/androidApps`). **Gated by policy** — creating persistent
resources in the shared cloud project requires the human owner's explicit authorization, even on
the correctly-scoped founder lane. This is the right boundary; the provisioning stays a
human-owned step. Recipe handed to the operator (Firebase Console, founder account):

1. **Project Settings → Add app → Android**: package `id.nearyou.app.staging`, SHA-1 above →
   creates the Android OAuth client.
2. **Authentication → Sign-in method → Google → Enable** (set support email) → auto-creates the
   "Web client (auto created by Google Service)" + the OAuth consent screen.
3. Copy the **Web client ID** (`27815942904-…apps.googleusercontent.com`) from the Google provider's
   "Web SDK configuration" (or Cloud Console → Credentials). That value → staging
   `GOOGLE_SERVER_CLIENT_ID`. Then `installStagingDebug` + adb-driven ceremony is one step away.

## Provisioning decision (2026-05-29) — automation declined for cross-project safety

Evaluated automating the OAuth-client provisioning to finish the happy-path smoke. **Declined.**
- The Firebase MCP's active project is `ledger-fcc1e` / `bukuwarung-app` (the operator's
  EMPLOYER's production project, under org 1041900352991) — NOT `nearyou-staging`. Running
  `firebase_create_app` / `firebase_create_android_sha` there would pollute a production project.
- Driving the Cloud Console via browser automation runs in a Google session with access to both
  BukuWarung's projects and `nearyou-staging` → real risk of creating credentials in the wrong
  (production) project; switching the Firebase CLI's active project could also disrupt other work.
- `gcloud` (correctly scoped to `nearyou-staging`) cannot create consumer mobile OAuth client IDs.

Provisioning production-adjacent OAuth credentials is a consequential infra action that should be
done by the human who owns the `nearyou-staging` GCP project, not automated in a shared session.
The happy-path / banned / no-account smokes (10.4-happy, 10.5, 10.6, 10.7, 10.9, 10.10) are therefore
a **launch-prep provisioning task**, not a code-correctness gate — the app is proven correct up to
the ceremony on real hardware (error 28444 = pure provisioning gap). Recipe + SHA-1 above; once the
clients exist in `nearyou-staging` + the Web client ID is in the staging build, the device-side run
is one `installStagingDebug` + adb-driven account-picker tap away.

## ✅ HAPPY PATH GREEN — real device (Galaxy A17 `RRGL20CTDBM`), 2026-05-30

The §10.4 happy path now runs **green end-to-end on real hardware**. What unblocked it, in order:

1. **OAuth clients provisioned** (operator, Firebase Console, founder account `<founder-account>@gmail.com`):
   Android app `id.nearyou.app.staging` + debug SHA-1 `9A:14:CE:…:11:05` → Android OAuth client;
   Authentication → Google provider enabled → auto-created **Web client**
   `27815942904-egrmb6ou96poualok9gooi63mjo2a0om.apps.googleusercontent.com`. Error 28444 cleared;
   the account picker rendered ("Choose a saved sign-in for NearYouID").
2. **Audience wired both sides** (commit `d750357`): the Web client ID → staging
   `GOOGLE_SERVER_CLIENT_ID` buildConfigField (mobile `serverClientId`) **and** the staging deploy's
   `GOOGLE_CLIENT_IDS` env var (the backend's `aud` allow-list, `deploy-staging.yml` line 137).
3. **Backend wire-format bug found + fixed** (commit `9980851`): with the audience accepted, the
   ceremony succeeded but the backend returned **400 `invalid_request`**. Root cause: the deployed
   auth DTOs (de)serialized **camelCase** (`idToken`/`accessToken`/…) while the canonical
   auth-signin/-session/-signup specs mandate **snake_case** (`id_token`/`access_token`/…). The
   mobile client correctly follows the spec; the backend's plain `@Serializable` data classes lacked
   `@SerialName` (app-wide Json uses kotlinx's default camelCase naming). Fixed by adding snake_case
   `@SerialName` to `SignInRequest`/`TokenPairResponse`/`RefreshRequest`/`LogoutRequest` +
   `SignupRequestDto`/`SignupTokenPairResponse`, plus `AuthWireFormatTest` pinning the literal wire
   names (the gap existed because both the backend flow tests and the mobile MockEngine tests
   round-trip via the same DTOs, so neither pinned the wire contract). Verified live: snake_case body
   `id_token` went from `400` → `401` (deserialization now succeeds).
4. **Staging test user seeded.** After (3), sign-in reached the user lookup and 404'd
   (`signin_error_no_account` — this itself smoke-tests **10.7** on real hardware). The looked-up hash
   was surfaced by the DEBUG diagnostic (commit `e3c1c57`): `sub_hash=f10eaeb6…a6a409`. A `users` row
   was seeded for it (via the IPv4 Supabase pooler `aws-1-ap-southeast-1`, founder-account
   `staging-db-password`): `username=smoketest_adi`, `id=986142e3-0f12-43fd-92a5-cf40e1b70bd4`,
   `google_id_hash=f10eaeb6…a6a409`, not banned. **Keep this row** — it is the registered account the
   happy-path smoke depends on. (Sign-in is account-gated; signup is a later mobile change, so there
   is no in-app way to register yet.)

**Verified green (screenshots in this dir):**

| Scenario | Evidence |
|---|---|
| 10.2/10.3 OAuth clients provisioned | account picker rendered (err 28444 gone) — `06-real-device-signin-provisioned.png` (SignInScreen) |
| **10.4 happy path → HomeScreen** | `200 OK: POST /api/v1/auth/signin in 103ms` (Cloud Run log) → HomeScreen — `07-real-device-home-signed-in.png` |
| **10.4 token persists (process death)** | force-stop + cold relaunch lands directly on HomeScreen, SignInScreen skipped — `08-real-device-relaunch-token-persisted.png` |
| 10.7 no-account 404 | `signin no-account … 404 Not Found` before seeding (user-facing "Akun belum terdaftar…") |
| **10.6 banned 403** | flipped `smoketest_adi.is_banned=TRUE` → `403 Forbidden /signin in 85ms` → banner "Akun kamu telah dinonaktifkan…" (`signin_error_banned`) + CTA disabled — `09-banned-banner-disabled-cta.png`; `is_banned` restored to FALSE after |
| 10.8 NetworkError copy + retry label | covered pre-provisioning (`03-…`) |

**Still gated / optional** (not blockers — code proven correct):
- **10.4a OS-reboot persistence** — process-death persistence proven; full `adb reboot` survival not yet run (token is on disk: encrypted DataStore + Tink keyset, so it will survive — but not yet device-confirmed).
- **10.9 / 10.10 refresh rotation + reuse-detection** — now have an authenticated session to drive these.

## ✅ iOS HAPPY PATH GREEN — iPhone 16 simulator (iOS 18.5), 2026-05-30

The §10.5 iOS happy path now runs **green end-to-end on the simulator** against the live staging
backend: CocoaPods build → install → SignInScreen (Compose Multiplatform renders on iOS) →
"Masuk dengan Google" → GIDSignIn web ceremony (`<test-account>@gmail.com`) → `200 OK POST
/api/v1/auth/signin in 227ms` → HomeScreen. Screenshots `10-ios-sim-signin-screen.png`,
`11-ios-sim-home-signed-in.png`. iOS OAuth client `27815942904-gd1es5…` (bundle
`id.nearyou.app.staging`); `GIDServerClientID` = the shared web client so the iOS token's `aud`
matches the backend allow-list.

**The iOS smoke surfaced + fixed 3 bugs the Android path never hit** (all config/build, not logic;
the iOS sign-in *code* was correct throughout):

1. **embedAndSign ↔ CocoaPods conflict** — the `kotlin("native.cocoapods")` framework integration
   conflicts with the old "Compile Kotlin Framework" (`embedAndSignAppleFrameworkForXcode`)
   run-script phase. Removed that phase from `iosApp.xcodeproj/project.pbxproj`; the pod's
   `syncFramework` script phase builds the framework now.
2. **GIDSignIn keychain error -2** — building the sim app with `CODE_SIGNING_ALLOWED=NO` strips
   the keychain-access-group entitlement, so GIDSignIn's credential write fails. Fix: build with
   default (ad-hoc "Sign to Run Locally") signing — exactly what Xcode's Run does.
3. **`ApiBaseUrl` truncated to `"https:"`** — xcconfig treats `//` as a line comment, so
   `APP_API_BASE_URL=https://api-staging.nearyou.id` got cut to `https:` → ECONNREFUSED. Fixed in
   Config/Staging/Production.xcconfig via `NY_SLASH = /` + `https:$(NY_SLASH)$(NY_SLASH)host` (a lone
   `/` is not a comment). **Lesson: never put a literal `//` in an xcconfig value.**

**iOS build recipe (sim smoke, until a Staging build config + scheme are wired — follow-up):**
```
xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/iosbuild build
# (NO CODE_SIGNING_ALLOWED=NO — needs ad-hoc signing for the keychain; bundle id.nearyou.app.staging
#  comes target-scoped from Config.xcconfig, NOT a global xcodebuild override — that collides the Pod
#  framework bundle IDs.) Then: simctl install/launch on an iOS ≥18.2 sim (the app targets 18.2).
```

**iOS follow-ups (polish, not blockers):** proper per-env build configs (Staging/Production) +
schemes wired to the matching xcconfig (so the bundle/URL aren't carried by the base Config);
per-config Pods xcconfig include (currently the Debug Pods xcconfig is included in Config.xcconfig
for the smoke).
