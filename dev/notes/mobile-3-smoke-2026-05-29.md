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
