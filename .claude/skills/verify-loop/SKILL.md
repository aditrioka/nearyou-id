---
name: verify-loop
description: Bring up and verify a nearyou-id change end-to-end by running the REAL app and observing behavior — routes to the right surface (Ktor backend + admin panel, Android emulator/device, iOS simulator) with the project's known unblockers (KTOR_ENV=test fail-soft, admin bootstrap + TOTP, staging-flavor-on-device, simctl location, pod-install order) and a verification loop. Self-improving: append every new blocker you hit. Use whenever the task is to verify a change manually, confirm a fix works in the real app, run/launch/screenshot the app, or smoke-test before pushing.
---

This is nearyou-id's **single, self-improving verification skill** (per the "Stop babysitting your agents" verification-loop pattern). It teaches Claude to check its own work by running the real app, not just the unit suite. The built-in `/run` and `/verify` skills should defer to the surface recipes here.

## The loop (the whole point)

Get into a **hill-climbing loop**: write → bring up the stack → drive it → observe (UI / logs / DB) → if it fails, read the failure, fix, repeat → stop only when the success criterion is observably met. Don't hand a change back as "done" until you've watched it work on at least one real surface.

## Self-improving rule (do this every run)

When you hit a blocker that is **not already documented below**, fix it, then **append the fix to the relevant surface section before you finish** — exact command, root cause, one line. This file is committed to the repo, so your fix unblocks the next session and the next teammate. Treat a new blocker as a skill bug, not a one-off.

## Step 0 — Pick the surface from the diff

| Diff touches | Verify on |
|---|---|
| `:backend:ktor` REST / admin / workers / Flyway SQL | **Backend + admin panel** (§A) — plus `:backend:ktor:test` |
| `:mobile:app` screens / Compose UI / navigation | **Android emulator** (§B, fast) and, if platform actuals or fonts/resources changed, **iOS sim** (§C) |
| `:shared` / KMP logic with `platform`/`actual` impls | **Both** Android (§B) and iOS (§C) — JVM-only tests miss Kotlin/Native gaps |
| Lint rule / Detekt / build-logic / CI / docs | **Gate only** (§D) — no app bring-up needed |

Always also run the **gate** (§D) before declaring done.

> **Worktree note:** if you're in a `.claude/worktrees/*` checkout, Gradle needs a `local.properties` with `sdk.dir=` (gitignored). Copy it from the main checkout first, or Android tasks fail to resolve the SDK.

---

## §A — Backend + admin panel (Ktor)

`KTOR_ENV=test` is the magic switch: Firebase Admin SDK (FCM), OpenAI key, and Supabase service-role key are hard startup requirements unless `ktor.environment == "test"`, which fail-softs all three and binds NoOp Redis/cache. Without it, boot dies on missing cloud creds.

**Always-required boot env** (NOT env-gated — `?: error(...)` at `Application.kt`):
`KTOR_RSA_PRIVATE_KEY` (gen: `dev/scripts/generate-rsa-keypair.sh`), `SUPABASE_JWT_SECRET` (any base64), `SUPABASE_URL` + `INTERNAL_OIDC_AUDIENCE` (any dummy URL), `DB_URL` / `DB_USER` / `DB_PASSWORD`. Local dev Postgres: `localhost:5433`, `postgres`/`postgres`/`nearyou_dev`.

**Boot:**
```bash
set -a; . envfile; set +a
KTOR_ENV=test ./gradlew --no-daemon :backend:ktor:run   # --no-daemon so the forked app inherits exported env; serves :8080
```
Flyway runs at app startup, so migrations auto-apply.

**Observe:** hit the endpoint (`curl localhost:8080/...`), grep the app log for the expected line, and/or check DB state directly. For the **admin panel** (the one genuinely web surface — drive it with a browser MCP: `browsermcp` or `Claude_Preview`):
1. Admin-login secrets (lazy, needed at login not boot): `ADMIN_TOTP_SECRET_AES_KEY` + `ADMIN_CSRF_HMAC_KEY` (base64 of 32 bytes each). Secret-name→env mapping is `name.uppercase().replace('-','_')` (`config/Secrets.kt`).
2. Bootstrap an admin (reuse the SAME AES key so login can decrypt the TOTP) — the task wires `standardInput = System.in`, so pipe the password:
   ```bash
   echo 'pass' | ADMIN_TOTP_AES_KEY_BASE64="$AES_B64" ./gradlew --quiet --no-configuration-cache \
     :backend:ktor:adminBootstrap --args="--email x@local.test --display-name Y --role owner"
   ```
   It prints the base32 TOTP secret (once) + an `INSERT INTO admin_users` SQL. Apply the INSERT to the dev DB via `psql -f <file>` (the `$argon2id$` hash mangles in an unquoted heredoc).
3. Browser: `http://localhost:8080/admin/login` — email + password + `oathtool --totp -b <base32>` (codes rotate every 30s) → lands on `/admin/`.

**Cleanup:** `lsof -ti tcp:8080 | xargs kill`; delete the test admin's `admin_actions_log` rows FIRST (`admin_id` FK is NO-ACTION), then the admin; keep the V18 `system` sentinel row.

**Staging DB (for state checks against real data):** creds via `gcloud secrets ... staging-db-*`; direct host is IPv6-only → use the pooler `aws-1-ap-southeast-1` (user `postgres.<ref>`, port 6543). The classifier blocks agent destructive writes — a human runs any `DELETE` from the Supabase dashboard SQL editor.

---

## §B — Android (emulator first, device when needed)

**Emulator = `dev` flavor.** `API_BASE_URL=http://10.0.2.2:8080` is the emulator-only host-loopback alias to your local backend. (On an emulator, local `http://` also needs a cleartext config.)

```bash
./gradlew :mobile:app:installDevDebug    # build + install to a running emulator
adb shell am start -n <applicationId>/.MainActivity   # or launch from the launcher
adb exec-out screencap -p > /tmp/and.png              # prove the UI rendered — Read the PNG
adb logcat -d | grep -i <expected|Exception>          # observe
```

**Drive the UI with Maestro (don't hand-script taps):** `dev/scripts/maestro-run.sh <flow> --app-id id.nearyou.app.dev [--record]`. Reads the accessibility tree (tap by `testTag`, not pixels) and captures screenshots + mp4 + `maestro.log` into `mobile/app/maestro/artifacts/<run>/` for human review. Flows live in `mobile/app/maestro/flows/` (see that dir's [README](../../../mobile/app/maestro/README.md)); builds on the official [Maestro AI-agent skill](https://github.com/mobile-dev-inc/Maestro/discussions/2985). After a run, write a plain-English summary (what you tested, which screenshot proves it, what the log says). `auth-gated` flows need the Phase 2 dev test-login.

**Physical device + staging backend = `staging` flavor** (`installStagingDebug`): `https://api-staging.nearyou.id`, real Google OAuth client (debug SHA-1 registered → Credential Manager sign-in works).

**Physical device + LOCAL backend = `dev` flavor + adb reverse** (wired 2026-06-11):
```bash
adb reverse tcp:8080 tcp:8080     # device localhost:8080 → host 8080 over USB (re-run after replug)
./gradlew :mobile:app:installDevDebug -PdevApiBaseUrl=http://localhost:8080
```
`devApiBaseUrl` overrides the dev flavor's default `10.0.2.2` (emulator-only host alias). Cleartext to `localhost`/`127.0.0.1`/`10.0.2.2` is allowlisted dev-only via `mobile/app/src/dev/res/xml/network_security_config.xml` (referenced by the dev manifest overlay; staging/production untouched). Auth without Google: `dev/scripts/mint-dev-jwt.sh <local-users.id>` + the `nearyou-dev://test-login` deep link (same params as staging). **Deep-link quoting from zsh:** build the URI in a var and pass `-d "'$URI'"` (single quotes survive to the device shell). Do NOT hand-escape `&` as `\&` inside double quotes — zsh keeps the backslash, the token stores with a trailing `\`, and every request then carries a malformed Authorization header.

**One command for all of the above (backend already booted):** `dev/scripts/dev-device-login.sh` — checks :8080, applies `adb reverse`, seeds the fixed dev-login user (idempotent), mints, fires the deep link with safe quoting. Re-run it whenever the session expires (~15 min; the test-login refresh token is deliberately bogus).

**Auth-gated screen without signing in:** temporarily edit `mobile/app/src/commonMain/.../App.kt` to `Navigator(<TargetScreen>())` (boot straight in, bypassing `RootRouterScreen`), build dev-debug, verify, then **`git restore App.kt` before commit**. Real platform actuals still bind.

**Session-lifecycle / terminal-401 / proactive-refresh verification (staging flavor, NO DB write, NO Google sign-in)** — `dev/scripts/mint-staging-jwt.sh [user] [token_version]` mints a signature-valid staging JWT and emits a `nearyou-staging://test-login?access=<jwt>&refresh=<jwt>&exp=<ms>` deep-link. Two facts make this a full terminal-401 / proactive-refresh test rig without touching the staging DB:
- **`refresh` = the access JWT (a BOGUS refresh token):** so ANY `POST /auth/refresh` against staging is rejected → a deterministic terminal 401. No need to revoke a real refresh token.
- **The deep-link `exp` IS the client's `accessExpiresAtEpochMillis`** (the proactive trigger's only input) — override it freely; the real JWT `exp` (15 min) is unaffected.

Recipes (build `:mobile:app:installStagingDebug`; ALWAYS `adb shell pm clear id.nearyou.app.staging` first + screenshot to confirm the clean state shows Sign-In with NO session-expired notice — the false-positive guard):
- **Proactive refresh on resume (D3) ISOLATED:** mint `token_version=0` (valid → fetches 200) + set `exp = now+90s` (< 5-min window). Fire the deep-link. Cold-start `ON_RESUME` fires a proactive `POST /auth/refresh` (the FIRST request in logcat, BEFORE any 401) → bogus refresh → 401 → re-route to Sign-In with "Sesi kamu berakhir…". The unread-badge fetch 200s, proving the re-route is from the proactive refresh alone, not a fetch 401.
- **Fetch terminal-401 (D4, the original mislabel bug) ISOLATED:** mint `token_version=99` (mismatch → backend 401s every request) + set `exp = now+900s` (> 5 min → proactive refresh stays OUT). Timeline fetch → 401 → ONE reactive `/auth/refresh` (single-flight coalesces the concurrent unread-badge + timeline 401s) → 401 → terminal → Sign-In with the notice, NEVER "Tidak bisa terhubung. Periksa koneksi internet kamu."
- **Observe:** `adb logcat -c` before firing; the staging-debug build logs Ktor at `HEADERS` to `System.out`, so `adb logcat -d | grep -E "REQUEST:|RESPONSE:|FROM:|auth/refresh|timeline"` gives the exact request/response sequence. Coordinate query params show as `lat=***&lng=***` (CoordinateMaskingLogger working). Then `exec-out screencap` + Read the PNG.
- **Limits (cover via the test suite, can't reproduce with test-login):** the SUCCESSFUL proactive refresh (no 401 flash) needs a REAL refresh token (test-login's is bogus) → unit + iOS-sim ON_RESUME tests; the sub-second timeline redirect placeholder ("Mengalihkan…") flashes before the reliable re-route → Robolectric/iOS screen tests; destination-preservation while on a non-Home screen → `PendingReturnDestinationTest`.

---

## §C — iOS simulator

**ALWAYS pod-install via `dev/scripts/ios-pod-install.sh`, NEVER raw `pod install`.** CMP `compose-resources` (Plus Jakarta Sans `plus_jakarta_sans.ttf`, strings, drawables) are a BUILD artifact; CocoaPods only wires the `[CP] Copy Pods Resources` phase when that dir is already populated at install time. Raw `pod install` on an empty resources dir STRIPS the phase → launch aborts with `MissingResourceException: ...plus_jakarta_sans.ttf` (NearYouTheme `FontFamilyResolver.preload`). The script populates resources (needs `ARCHS=arm64 PLATFORM_NAME=iphonesimulator CONFIGURATION=Debug`) THEN installs.

- **UTF-8 locale** or `pod install` dies (`Unicode Normalization not appropriate for ASCII-8BIT`): `export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8` (script sets this).
- **Sim runtime ≥ 16.0** (the deliberate deployment floor since the 2026-06-10 audit; was an accidental 18.2). **Bundle id = `id.nearyou.app.staging`.**
- **Scheme names are flavor-qualified:** `iosApp (Dev)` / `iosApp (Staging)` / `iosApp (Production)` (project shared schemes; there is NO plain `iosApp` scheme). The workspace ALSO lists a KMP-generated `app` scheme — building it "SUCCEEDS" having only run the Gradle framework sync and produces NO .app; if `find <dd> -name '*.app'` comes up empty, you built the wrong scheme. With a named scheme, `-configuration` is unnecessary.
- **Never pipe xcodebuild through `head`/early-exiting filters** — SIGPIPE kills the build mid-flight and the half-written DerivedData can make the NEXT run report `BUILD SUCCEEDED` with no product. Redirect to a log file, then grep the file.
- **Clean rebuild:** `rm -rf iosApp/Pods iosApp/Podfile.lock iosApp/iosApp.xcworkspace mobile/app/build` + fresh `-derivedDataPath /tmp/<x>`, THEN `dev/scripts/ios-pod-install.sh`, THEN `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme 'iosApp (Staging)' -destination 'platform=iOS Simulator,id=<simId>' -derivedDataPath <dd> build > /tmp/xc.log 2>&1` (use a concrete `id=` from the destination list — `simctl list` ids can be stale).
- **Resource-presence proof:** `find <dd>/Build/Products/Debug-iphonesimulator/NearYouID.app -path '*compose-resources*'`.
- **Observe:** boot sim → `simctl install` → `simctl spawn <dev> log stream --predicate 'processImagePath CONTAINS "NearYouID"'` to a file → `simctl launch` → grep for `MissingResource`/`Throwable` → `simctl io <dev> screenshot /tmp/x.png` and Read it. Crash `.ips` reports land in `~/Library/Logs/DiagnosticReports/`.
- **Location-gated screens:** `xcrun simctl privacy <udid> grant|revoke location <bundle>` + `xcrun simctl location <udid> set <lat>,<lng>`.
- **Drive the UI with Maestro:** works on the iOS sim too — `dev/scripts/maestro-run.sh <flow> --app-id id.nearyou.app.staging`. Caveat: Compose→Skia makes the iOS a11y tree sparse, so add `Modifier.testTag(...)` to key elements or expect coordinate-fallback taps.
- **Kotlin/Native compile gaps CI can't catch** (CI is Linux): run `:module:linkDebugFrameworkIosSimulatorArm64` locally. ObjC category members (e.g. `NSDate.timeIntervalSinceNow`) need an explicit `import platform.<Fw>.<symbol>`. iOS unit tests use `kotlin.test @Test` (not Kotest, which doesn't run on K/N) and live in `src/iosTest` (not `commonTest`); K/N forbids `,()#` in test fn names.

---

## §D — The gate (run before declaring done)

This gate implements [`docs/11-Engineering-Standards.md`](../../../docs/11-Engineering-Standards.md) §5 (Definition of Done). For UI-affecting changes the DoD additionally requires the §B/§C bring-up with screenshot evidence in the PR body — the test gate below alone does not clear it (`/opsx:apply` step 7.5 is the enforcement point).

CI runs **both** lint frameworks; passing only one is insufficient. `:mobile:app` has flavors, so test tasks MUST be flavor-qualified (`testDebugUnitTest` alone is ambiguous and fails graph resolution).

```bash
# Backend / lint-rule changes:
./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test
# Mobile changes (add these):
./gradlew ktlintCheck detekt :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest
```
`detekt` is a ROOT-level task (no `:mobile:app:detekt`). The **Release** variant guards the `*ScreenTest` exclude: ui-test-manifest's host activity is debug-only, so a new Robolectric `*ScreenTest` must be added to the release-variant exclude or `testDevReleaseUnitTest` throws.

## Known blockers (grow this list)

- **§D gate while a local `:backend:ktor:run` is alive → `SQLTransientConnectionException` on unrelated DB-tagged tests:** the live server's Hikari pool (10) plus the suite's per-spec pools exceed the dev Postgres connection budget — same "too many clients" mechanism as the CI memory, reproduced locally 2026-06-11 (16 connections with server up → gate red; 6 after kill → gate green). Kill the server (`lsof -ti tcp:8080 | xargs kill`) before running the gate; reboot it after if device testing continues.

- **Robolectric async-repo screen test never settles:** real `MockEngine` network submit isn't awaited by `waitForIdle` (a synchronous Fake flow is) → poll the end state with `waitUntil`.
- **Source-scan guard test trips on its own KDoc:** strip comments before a forbidden-token scan, else the file's own "MUST NOT println" doc trips it.
- **CI heavy lanes skipped after a force-push:** CI's path filter reads `github.event.before`; a rebase orphans it → "bad object" → empty diff → code lanes skip. Fix with a tiny fast-forward re-poke commit.
- **Docs-only commit cancels in-progress code CI:** `cancel-in-progress` + the docs path filter can leave a code commit with zero CI signal — don't push a docs tick before the code commit's CI finishes.
- **Auth/test-login verification false positive:** `adb install -r` PRESERVES app data, so a leftover session makes the app land on Home regardless of whether your login/injection actually worked. ALWAYS start auth-flow verification from a wiped state (`adb shell pm clear <appId>` or Maestro `clearState`) and first confirm the clean state shows Sign-In — otherwise "it reached Home" proves nothing. (Caught in the Phase 2 dev test-login verification.)
- **Offline "render the shell" harness bounces to Sign-In on the staging/prod flavor:** seeding `HomeRoute` in `App.kt` + Koin-overriding the feed flows is NOT enough — the authenticated shell fires a one-shot **unread-badge** fetch (`NotificationsFlow.unreadCount()`) on composition; against a real backend with no session that 401s, the Auth plugin clears the token store, and `SessionExpiryEffect` re-routes to Sign-In before you see Home. Fix: ALSO override **every** flow that makes a network call on shell composition (`NotificationsFlow` too, not just Nearby/Global), or harness on the `dev` flavor (talks to `10.0.2.2`, no real 401). To Koin-override from `MainActivity`: `initKoin { androidContext(...); allowOverride(true); modules(harnessModule) }` (Koin 4.2). (Caught harnessing `mobile-home-shell-redesign` 10.7.)
- **Emulator segfaults at launch (exit 139) on this host:** the API-30 arm64 playstore AVD crashes in the gfxstream/SwiftShader-Vulkan path with BOTH default and `-gpu host` flags (macOS, emulator 35.6.11). Don't burn time on GPU flags — use the physical-device-to-local-backend path above (adb reverse + `-PdevApiBaseUrl`).
- **A `Throwable` catch-all in StatusPages eats Ktor's built-in 4xx mappings:** `BadRequestException` (malformed Authorization header; malformed JSON body via `receive`) became a 500 `unhandled_exception`. The built-in client-error types are re-registered in `common/AppStatusPages.kt`; regression test `StatusPagesClientErrorTest`. When adding any catch-all-style handler, check which default mapping it shadows.
- **`gradlew :backend:ktor:run` dies mid-verification when ANY other Gradle build runs (xcodebuild KMP sync, `installDevDebug`):** the sync issues a daemon `--stop` → "Gradle build daemon has been stopped" kills the server. Host the backend OUTSIDE Gradle: `./gradlew :backend:ktor:installDist` once, then `JAVA_HOME=<jdk21> backend/ktor/build/install/ktor/bin/ktor` (the start script otherwise picks system Java 17 → `UnsupportedClassVersionError` class 65 vs 61) — detach with `nohup … &` (harness-managed background tasks get SIGTERM-reaped on session resume). If :8080 is occupied by a process you didn't start (check `ps -p $(lsof -ti tcp:8080)` — it may be the operator's own server from the main checkout), boot on `PORT=8081` and point the app there (`-PdevApiBaseUrl=http://localhost:8081`) instead of killing it.
- **Session-resume cwd reset → STALE-TREE build/install (the falsest of false greens):** after a session interruption the shell cwd silently resets to the MAIN repo; `installDevDebug` then "succeeds" by building the OLD code and the device runs pre-change UI (here: the new app bar "missing" for 40 minutes). Re-`cd` into the worktree in EVERY command AND verify the installed APK actually contains a NEW symbol: `adb shell pm path <id>` → `adb pull` → `unzip -o 'classes*.dex'` → `grep -ac <newTestTag>` (grep on the APK zip itself always returns 0 — dex entries are deflated).
- **App.kt auth harness + test-login deep link are mutually exclusive:** the harness `LaunchedEffect` OVERWRITES the deep-link-written TokenPair at composition — if its hardcoded JWT has expired you get an instant terminal 401 ("Sesi kamu berakhir") on every launch no matter how fresh the deep-link mint was. Android: use the deep link, no harness. iOS: there is NO test-login path at all (Dev/StagingTestLoginActivity are Android source-set Activities; iOS `onOpenURL` only feeds GIDSignIn) — the harness IS the iOS-sim auth route: gate composition until `TokenStore.write(...)` completes, build, screenshot, `git restore App.kt`.
- **Emulator on this host, second data point:** API-30 arm64 playstore AVD segfaults (above), but a FRESH `android-36;google_apis;arm64-v8a` AVD (`avdmanager create avd -n verify36 -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6`) boots fine headless (`-no-window -no-audio -no-boot-anim -no-snapshot`) and renders the app — prefer it over burning time on the playstore image. `adb emu geo fix <lng> <lat>` feeds the fused provider (re-send after the app's first fetch if Nearby errored).
- **Maestro drives the CMP iOS sim by TEXT:** `maestro --udid <sim> test <flow.yaml>` with `tapOn: "Global"` / regex `text:` works (a11y exposes Text nodes); per-flow `takeScreenshot` gives evidence frames without simctl gymnastics. On ANDROID, avoid `maestro hierarchy` mid-verification — its driver bounce can disturb the app session; prefer plain `input tap` + `screencap` when a session must stay alive.
- **Nearby tab shows "Tidak bisa terhubung" on a real device with good internet:** the Nearby feed needs a device **GPS coordinate** BEFORE it calls `/timeline/nearby`; if the Fused provider has no cached fix (common indoors) the coordinate acquisition fails and maps to the existing retryable `NetworkError` — whose copy is the generic "check your connection." It is NOT a connectivity bug. Confirm via logcat: no `REQUEST: …/timeline/nearby` line at all, while `…/timeline/global` returns `RESPONSE: 200` (Global needs no location). To verify Nearby content, warm up a location fix (open Maps once) or use Global; permission alone (`pm grant … ACCESS_*_LOCATION`, `settings get secure location_mode`=3) isn't sufficient without an actual fix.

---

When you finish a verification run, if you learned something new, **edit this file** before you summarize. That is the difference between a verification skill and a one-off.
