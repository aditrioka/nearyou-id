# Phase 2 — dev-only test-login (auth bypass) for the Maestro harness

Status: **IMPLEMENTED + verified on-device (Android).** Controlled clean-slate verification:
`adb uninstall` → `:mobile:app:clean` rebuild → fresh `adb install` → baseline launch shows **Sign-In**
(clean = unauthenticated) → the dev test-login deep link routes to the **authenticated Home** → the
`create-post` Maestro flow PASSES end-to-end (clearState → deep link → dismiss location dialog → tap FAB
→ composer → type → screenshot). Guard confirmed: `DevTestLoginActivity` is **absent** from the staging
APK manifest + dex and **present** in the dev dex. Lint gate (ktlint + detekt + `testDevDebugUnitTest`)
green. iOS dev-login remains a follow-up (flavor source sets are Android-only). The design below is what
shipped; the `id:` testTag selector note is captured under "Selectors" in the README.

## Why this is needed

`auth-gated` Maestro flows (`flows/auth/*.yaml`) stop at Sign-In because Google/Apple social login can't be
driven by automation (the provider UI leaves the app sandbox + actively blocks bots). The standard fix is a
**dev-only bypass**, never driving the real provider.

## The key security fact (why this is cheap + safe)

Backend session validation (`auth/AuthPlugin.kt:68-86`) requires only: an **RSA-signed JWT** (server key) with a
valid **`sub`** (UUID of a real `users` row) + a matching **`token_version`**. **There is no provider check at
validation time.** So a test session needs only (a) a seeded `users` row and (b) a token signed by the server key —
both already producible offline:

- `dev/scripts/seed-test-user.sh` → `INSERT INTO users … RETURNING id` (direct DB).
- `dev/scripts/mint-dev-jwt.sh` → `:backend:ktor:mintDevJwt` → `JwtIssuer.issueAccessToken(userId, tokenVersion)`,
  signs locally with `KTOR_RSA_PRIVATE_KEY`. Access TTL 900s (`auth/jwt/JwtIssuer.kt:9`) — fine for a test run.

**⇒ The backend is NOT modified. Zero new production attack surface.** (A `/internal-dev/test-login` endpoint was
considered and rejected: more faithful but adds real prod-shipped code + needs its own env-guard + Detekt rule + test.)

## Design (chosen)

Mobile-only, **dev-flavor source-set isolated**, token injected at test time via a deep link:

```
maestro-run (wrapper) ── seed-test-user.sh + mint-dev-jwt.sh ──► access JWT
        │  openLink nearyou-dev://test-login?access=<jwt>&refresh=<jwt>&exp=<ms>
        ▼
[dev APK only] DevTestLoginActivity  ── tokenStore.write(TokenPair(...)) ──► SecureTokenStore (Tink/DataStore)
        │  start MainActivity
        ▼
RootRouterScreen: AuthFlow.isAuthenticated() == true  ──►  HomeScreen / Nearby
```

### The guard

**Flavor source-set isolation** — all new Kotlin + the manifest entry live under `mobile/app/src/dev/`
(Android `dev` flavor). AGP compiles `src/dev/**` **only** into the dev APK; it is **physically absent** from
staging/production. Stronger than a runtime `BuildConfig.DEBUG` check (nothing to bypass / reverse-engineer).
Production `KTOR_ENV` is irrelevant (backend untouched). No Detekt rule strictly required, but optionally add one
forbidding `DevTestLogin*` symbols outside `src/dev/` for defense-in-depth (precedent: `RawFromPostsRule` + allowlist).

## File-by-file plan

1. **`mobile/app/src/dev/AndroidManifest.xml`** (NEW) — declare `DevTestLoginActivity` with an intent-filter
   for scheme `nearyou-dev` host `test-login`. `android:exported="true"` (deep-link entry), dev flavor only.
2. **`mobile/app/src/dev/kotlin/id/nearyou/app/dev/DevTestLoginActivity.kt`** (NEW) — read `access`/`refresh`/`exp`
   query params; resolve `SecureTokenStore` from Koin (`GlobalContext.get()`); in a coroutine
   `tokenStore.write(TokenPair(accessToken, refreshToken, accessExpiresAtEpochMillis=exp))`; then
   `startActivity(Intent(this, MainActivity::class.java))` + `finish()`.
   - Confirm against source before coding: `SecureTokenStore` write signature + `TokenPair` ctor
     (`commonMain/.../auth/SecureTokenStore.kt:9,30,44`); how Koin is started on Android + `MainActivity` package
     (`androidMain/.../MainActivity.kt`); whether `SecureTokenStore` needs `Context` (Android actual).
3. **`mobile/app/maestro/flows/_auth/test-login.yaml`** (NEW) — `- openLink: "nearyou-dev://test-login?access=${TEST_JWT}&refresh=${TEST_REFRESH}&exp=${TEST_EXP}"` then `assertVisible id: nearbyTimelineList`.
4. **`flows/auth/nearby-timeline.yaml` + `create-post.yaml`** — uncomment the `runFlow: ../_auth/test-login.yaml` line.
5. **`dev/scripts/maestro-test-login.sh`** (NEW helper) — seed (idempotent) + mint, echo `TEST_JWT`/`TEST_EXP`;
   the wrapper passes them via `-e`. (Or fold into `maestro-run.sh` behind a `--test-login` flag.)
6. **`mobile/app/build.gradle.kts`** — only if AGP needs the `dev` source set explicitly registered for the KMP
   androidTarget (verify: `src/dev/kotlin` may be auto-created; the manifest merge for `src/dev/AndroidManifest.xml`
   is automatic per-flavor).

## Verification (DO NOT skip — it's an auth bypass)

1. `./gradlew :mobile:app:assembleDevDebug` (worktree needs `local.properties` — already copied).
2. `dev/scripts/maestro-run.sh mobile/app/maestro/flows/auth/nearby-timeline.yaml --app-id id.nearyou.app.dev --record`
   against an emulator with the local backend running (`KTOR_ENV=test` + dev DB) → flow must **land on Nearby**
   (assert `nearbyTimelineList`) + screenshot proves it.
3. **Prod-absence check:** `./gradlew :mobile:app:assembleStagingDebug` then confirm `DevTestLoginActivity` is NOT in
   the merged manifest / APK:
   `aapt dump xmltree app-staging-debug.apk AndroidManifest.xml | grep -i DevTestLogin` → **empty**.
4. Add a tiny guard test (JVM) asserting the dev-login symbol isn't referenced from `commonMain`/`androidMain`.

## iOS gap

Flavor source sets are Android-only (AGP). iOS has no flavor concept; iOS dev-login needs a scheme/xcconfig-gated
`iosMain` path. **Scope Phase 2 to Android** (matches the current Maestro harness app IDs); file iOS dev-login as a
follow-up if/when Maestro must run on the iOS sim.
