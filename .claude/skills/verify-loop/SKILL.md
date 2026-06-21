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
| `:mobile:app` screens / Compose UI / navigation | **Android** (§B) — emulator locally / **Firebase Test Lab in the cloud sandbox** (see the routing note below; via `scripts/test_android.sh` / `scripts/run_on_device.sh`) — and, if platform actuals or fonts/resources changed, **iOS sim** (§C, local-only) |
| `:shared` / KMP logic with `platform`/`actual` impls | **Both** Android (§B) and iOS (§C) — JVM-only tests miss Kotlin/Native gaps |
| Lint rule / Detekt / build-logic / CI / docs | **Gate only** (§D) — no app bring-up needed |

Always also run the **gate** (§D) before declaring done.

> **Local vs cloud — the mobile surface is context-routed (do NOT hand-pick).** §B/§C assume a **local** machine that can boot an Android emulator / iOS simulator. In the **Claude Code cloud sandbox** there is no emulator (headless, no KVM), so the mobile bring-up runs the change on a **real device via Firebase Test Lab** instead. Don't decide this yourself — let the wrappers route it:
> - **Instrumented verification:** `scripts/test_android.sh` (local+device → `connectedAndroidTest`; cloud → Firebase Test Lab; local+no-device → it tells you to boot an emulator).
> - **"See it run" + screenshots/video evidence:** `scripts/run_on_device.sh` (Robo run on a real device; pulls screenshots/video to `dev/device-runs/<ts>/`). In CI this is automatic per mobile PR via `.github/workflows/device-run.yml`, which posts the result + console link as a PR comment — that comment satisfies the §5 DoD evidence requirement for a cloud session.
>
> Routing logic lives in [`scripts/_testing_context.sh`](../../../scripts/_testing_context.sh) (capability-first; robust under `claude --remote-control`, which stays LOCAL). The `SessionStart` hook prints the active context. **iOS has no farm equivalent** — iOS-sim verification (§C) is local-only; in a cloud session, cover iOS via the unit/`linkDebugFrameworkIosSimulatorArm64` gate and note the sim bring-up as owed on the next local session.

> **Worktree note:** if you're in a `.claude/worktrees/*` checkout, Gradle needs a `local.properties` with `sdk.dir=` (gitignored). Copy it from the main checkout first, or Android tasks fail to resolve the SDK.

---

## §A — Backend + admin panel (Ktor)

`KTOR_ENV=test` is the magic switch: Firebase Admin SDK (FCM), OpenAI key, and Supabase service-role key are hard startup requirements unless `ktor.environment == "test"`, which fail-softs all three and binds NoOp Redis/cache. Without it, boot dies on missing cloud creds.

**Always-required boot env** (NOT env-gated — `?: error(...)` at `Application.kt`):
`KTOR_RSA_PRIVATE_KEY` (gen: `dev/scripts/generate-rsa-keypair.sh`), `SUPABASE_JWT_SECRET` (any base64), `SUPABASE_URL` + `INTERNAL_OIDC_AUDIENCE` (any dummy URL), `INVITE_CODE_SECRET` (any base64), `JITTER_SECRET` (base64 of **exactly 32 bytes** — `openssl rand -base64 32`; a `require(size == 32)` rejects any other length), `DB_URL` / `DB_USER` / `DB_PASSWORD`. Local dev Postgres: `localhost:5433`, `postgres`/`postgres`/`nearyou_dev`.

**Boot:**
```bash
set -a; . envfile; set +a
KTOR_ENV=test ./gradlew --no-daemon :backend:ktor:run   # --no-daemon so the forked app inherits exported env; serves :8080
```
Flyway runs at app startup **only when `RUN_FLYWAY_ON_STARTUP=true` is in the env** — it is NOT in the always-required list above (gated at `Application.kt`: `if (System.getenv("RUN_FLYWAY_ON_STARTUP") == "true")`). When verifying a **migration** change, ADD `RUN_FLYWAY_ON_STARTUP=true` to your envfile, or the app boots against whatever schema already exists and your new `V<N>` SILENTLY never applies — endpoints then fail on the missing table/column (the falsest of false greens: server boots, an UNRELATED endpoint 200s off the old schema, the new feature is broken). Confirmed 2026-06-15 (premium-post-editing smoke: a boot without the flag left V22/`post_edits` unapplied while create-post still 201'd off V1–V21, masking it — caught only by a direct DB check). Always confirm the migration landed: `docker exec nearyouid-dev-postgres psql -U postgres -d nearyou_dev -tAc "SELECT to_regclass('public.<newtable>')"`.

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

**Driving the panel with the Claude_Preview MCP:** it refuses to attach to an already-running non-preview process on the port — add a `.claude/launch.json` entry (`bash -c 'set -a; . /tmp/<envfile>; set +a; KTOR_ENV=test ./gradlew --no-daemon :backend:ktor:run'`, `"autoPort": false`) and let `preview_start` own the boot. `preview_click` can race a just-navigated page (the click reports success, nothing posts) — drive form actions with `preview_eval` + `form.requestSubmit()` and verify via DB state, not the click result. TOTP without oathtool: stdlib-python RFC-6238 (base32 secret, HMAC-SHA1, 30 s window). For a throwaway DB, point `DB_URL` at a disposable `postgis/postgis:16-3.4` container — Flyway populates it on boot and synthetic seeds can't touch dev data (mind the CHECK enums on `reports.reason_category` / `moderation_queue.trigger` when hand-seeding).

**Exact-width screenshot evidence (PR body):** preview screenshots come back as scaled JPEGs — for pixel-true PNGs, log in with curl (capture `Set-Cookie` manually and send it back as a literal `Cookie:` header; curl's cookie engine drops `Secure` cookies over plain http), save each authenticated page's HTML to a temp dir with `admin.css` + the woff2 next to it, sed asset hrefs relative (absolute `/admin/static/...` breaks under `file://`), then headless-Chrome `--screenshot --window-size=<w>,<h>`. Checkbox-hack UI states (drawer open, filters disclosure) render statically by adding `checked` to the hidden input. Host PR-body images on an orphan `evidence/<change>` branch (raw.githubusercontent URLs pinned to the commit SHA; the branch never merges).

**Staging DB (for state checks against real data):** creds via `gcloud secrets ... staging-db-*`; direct host is IPv6-only → use the pooler `aws-1-ap-southeast-1` (user `postgres.<ref>`, port 6543). The classifier blocks agent destructive writes — a human runs any `DELETE` from the Supabase dashboard SQL editor.

---

## §B — Android (emulator first, device when needed)

> **Cloud sandbox?** Skip the emulator recipe below — there's no emulator. Run `scripts/run_on_device.sh` (Robo + screenshots/video) and/or `scripts/test_android.sh` (instrumented) to verify on a real device via Firebase Test Lab, or rely on the `device-run.yml` PR comment. The rest of this section is the **local** emulator/device recipe.

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

**Verifying notification-driven navigation — or any feature needing seeded rows the empty dev DB lacks (added 2026-06-21, `mobile-notifications-deep-link-targets`):** the local dev DB is often EMPTY (0 users/posts/notifications), so seed FK-correctly before driving the UI: (1) `dev/scripts/seed-test-user.sh --google-id-hash <sha256hex>` for the logged-in user — use `printf '%s' "nearyou-dev-device-login" | shasum -a 256` as the hash so `dev-device-login.sh` logs into THAT user — plus any actor users; (2) create real target rows via the **API**, not hand-INSERTs (`curl -X POST localhost:8080/api/v1/posts -H "Authorization: Bearer $(dev/scripts/mint-dev-jwt.sh <uid>)" -d '{"content":…,"latitude":-6.2,"longitude":106.8}'` — goes through the jitter/`posts_set_city_tg` triggers a raw INSERT skips); (3) `INSERT INTO notifications (user_id,type,actor_user_id,target_type,target_id,body_data,created_at)` pointing at the real targets, plus a bogus-`target_id` row to exercise the unavailable/404 path. Two gotchas caught here: **`docker exec … psql <<HEREDOC` silently no-ops** (no output, rows never apply) — use `docker exec … psql -v ON_ERROR_STOP=1 -c "…"` for seeds/inserts; and **tap rows by EXACT bounds, never screenshot-estimated coords** (screencap is 1080×2400 but the Read view scales it ~×1.2, so visual estimates land on the wrong row): `adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml && adb -s emulator-5554 shell cat /sdcard/ui.xml | tr '<' '\n<' | grep -oE 'text="[^"]+"|bounds="[^"]+"' | paste - -` → tap the target row's bounds-center. (Physical device + emulator both attached → scope every adb call with `-s emulator-5554` / `ANDROID_SERIAL`; the physical device's screencap blanks under secure-lock.)

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
- **Full §D `:backend:ktor:test` against the long-lived dev DB false-fails ~26 isolation-dependent specs** (Search/Nearby/Global timeline, SignupFlow beforeEach FK, TimelineReadRateLimit counts): accumulated dev seed posts + Redis state break specs that assume a clean DB; feature-filtered runs pass because those specs self-clean. Run the gate CI-equivalently instead (reproduced 2026-06-12): `docker run -d -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=nearyou_dev -p 5440:5432 postgis/postgis:16-3.4` + `docker run -d -p 6390:6379 redis:7-alpine`, then `DB_URL=jdbc:postgresql://localhost:5440/nearyou_dev DB_USER=postgres DB_PASSWORD=postgres REDIS_URL=redis://localhost:6390 ./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (Flyway applies on first boot; mirrors ci.yml's service containers). Before blaming your diff, confirm the failing specs share zero surface with it.

- **CI lint lane fails with "admin/static inventory drift" after touching `backend/ktor/.../admin/static/`:** `htmx.min.js.SHA256SUMS` is the SINGLE manifest for that whole directory — the CI lint job hash-verifies every listed file AND requires the dir listing to equal the manifest (catches unvendored extras). Adding a sibling `*.SHA256SUMS` does NOT register a file; append `shasum -a 256 <file>` lines to `htmx.min.js.SHA256SUMS` instead, and refresh the `admin.css` entry on EVERY admin.css edit. Replicate locally: `shasum -a 256 -c htmx.min.js.SHA256SUMS` + compare `awk '{print $2}'` vs `find . -maxdepth 1 -type f ! -name '*.SHA256SUMS'`.
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
- **Scripting against `mint-staging-jwt.sh` output:** its STDOUT is only the ready-to-paste adb line, whose URI carries `\&` escapes — if you extract the access token for curl, strip the backslashes (`… | tr -d '\\'`) or the `Authorization` header arrives malformed and EVERY endpoint 400s `invalid_request "Malformed request"` (the AppStatusPages `BadRequestException` mapping, not an auth failure). For the deep link itself, extract the URI from between the `-d "…"` quotes, `sed 's/\\&/\&/g'`, and fire with the `-d "'$URI'"` single-quote wrap. (Caught verifying `mobile-inline-post-actions`.)
- **iOS App.kt auth harness: use `.value`, not `by` delegation:** App.kt imports no `androidx.compose.runtime.getValue/setValue`, so a pasted `var x by remember { mutableStateOf(false) }` harness fails compilation ("Property delegate must have a getValue method") — write `val x = remember { mutableStateOf(false) }` + `x.value`, and warm-build the workspace BEFORE minting so the harness rebuild is incremental and lands inside the JWT's 15-min window.
- **Maestro iOS text-tap is inconsistent on the sparse CMP a11y tree:** on the same card, `tapOn: "Suka"` (the like affordance's contentDescription) resolved but `tapOn: "Balas"` (the reply affordance's) silently failed and ended the flow — don't trust label taps for every affordance; fall back to `tapOn: point: "12%,40%"` percentages computed from a fresh screenshot. (Caught verifying `mobile-inline-post-actions`.)
- **Nearby tab shows "Tidak bisa terhubung" on a real device with good internet:** the Nearby feed needs a device **GPS coordinate** BEFORE it calls `/timeline/nearby`; if the Fused provider has no cached fix (common indoors) the coordinate acquisition fails and maps to the existing retryable `NetworkError` — whose copy is the generic "check your connection." It is NOT a connectivity bug. Confirm via logcat: no `REQUEST: …/timeline/nearby` line at all, while `…/timeline/global` returns `RESPONSE: 200` (Global needs no location). To verify Nearby content, warm up a location fix (open Maps once) or use Global; permission alone (`pm grant … ACCESS_*_LOCATION`, `settings get secure location_mode`=3) isn't sufficient without an actual fix.

---

When you finish a verification run, if you learned something new, **edit this file** before you summarize. That is the difference between a verification skill and a one-off.
