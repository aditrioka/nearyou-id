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

**Physical device = `staging` flavor** (`installStagingDebug`). The `dev` flavor CANNOT reach a local backend from a device: `10.0.2.2` is emulator-only AND there's no `usesCleartextTraffic`, so Android blocks the cleartext (`UnknownServiceException: CLEARTEXT ... not permitted`). Staging is `https://api-staging.nearyou.id` with a real Google OAuth client (debug SHA-1 registered → Credential Manager sign-in works).

**Auth-gated screen without signing in:** temporarily edit `mobile/app/src/commonMain/.../App.kt` to `Navigator(<TargetScreen>())` (boot straight in, bypassing `RootRouterScreen`), build dev-debug, verify, then **`git restore App.kt` before commit**. Real platform actuals still bind.

---

## §C — iOS simulator

**ALWAYS pod-install via `dev/scripts/ios-pod-install.sh`, NEVER raw `pod install`.** CMP `compose-resources` (Plus Jakarta Sans `plus_jakarta_sans.ttf`, strings, drawables) are a BUILD artifact; CocoaPods only wires the `[CP] Copy Pods Resources` phase when that dir is already populated at install time. Raw `pod install` on an empty resources dir STRIPS the phase → launch aborts with `MissingResourceException: ...plus_jakarta_sans.ttf` (NearYouTheme `FontFamilyResolver.preload`). The script populates resources (needs `ARCHS=arm64 PLATFORM_NAME=iphonesimulator CONFIGURATION=Debug`) THEN installs.

- **UTF-8 locale** or `pod install` dies (`Unicode Normalization not appropriate for ASCII-8BIT`): `export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8` (script sets this).
- **Sim runtime ≥ 18.2** (deployment target); an 18.1 sim refuses install. **Bundle id = `id.nearyou.app.staging`.**
- **Clean rebuild:** `rm -rf iosApp/Pods iosApp/Podfile.lock iosApp/iosApp.xcworkspace mobile/app/build` + fresh `-derivedDataPath /tmp/<x>`, THEN `dev/scripts/ios-pod-install.sh`, THEN `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=<simId>' -derivedDataPath <dd> build` (use a concrete `id=` from `xcodebuild -list`/destination list — `simctl list` ids can be stale).
- **Resource-presence proof:** `find <dd>/Build/Products/Debug-iphonesimulator/NearYouID.app -path '*compose-resources*'`.
- **Observe:** boot sim → `simctl install` → `simctl spawn <dev> log stream --predicate 'processImagePath CONTAINS "NearYouID"'` to a file → `simctl launch` → grep for `MissingResource`/`Throwable` → `simctl io <dev> screenshot /tmp/x.png` and Read it. Crash `.ips` reports land in `~/Library/Logs/DiagnosticReports/`.
- **Location-gated screens:** `xcrun simctl privacy <udid> grant|revoke location <bundle>` + `xcrun simctl location <udid> set <lat>,<lng>`.
- **Drive the UI with Maestro:** works on the iOS sim too — `dev/scripts/maestro-run.sh <flow> --app-id id.nearyou.app.staging`. Caveat: Compose→Skia makes the iOS a11y tree sparse, so add `Modifier.testTag(...)` to key elements or expect coordinate-fallback taps.
- **Kotlin/Native compile gaps CI can't catch** (CI is Linux): run `:module:linkDebugFrameworkIosSimulatorArm64` locally. ObjC category members (e.g. `NSDate.timeIntervalSinceNow`) need an explicit `import platform.<Fw>.<symbol>`. iOS unit tests use `kotlin.test @Test` (not Kotest, which doesn't run on K/N) and live in `src/iosTest` (not `commonTest`); K/N forbids `,()#` in test fn names.

---

## §D — The gate (run before declaring done)

CI runs **both** lint frameworks; passing only one is insufficient. `:mobile:app` has flavors, so test tasks MUST be flavor-qualified (`testDebugUnitTest` alone is ambiguous and fails graph resolution).

```bash
# Backend / lint-rule changes:
./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test
# Mobile changes (add these):
./gradlew ktlintCheck detekt :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest
```
`detekt` is a ROOT-level task (no `:mobile:app:detekt`). The **Release** variant guards the `*ScreenTest` exclude: ui-test-manifest's host activity is debug-only, so a new Robolectric `*ScreenTest` must be added to the release-variant exclude or `testDevReleaseUnitTest` throws.

## Known blockers (grow this list)

- **Robolectric async-repo screen test never settles:** real `MockEngine` network submit isn't awaited by `waitForIdle` (a synchronous Fake flow is) → poll the end state with `waitUntil`.
- **Source-scan guard test trips on its own KDoc:** strip comments before a forbidden-token scan, else the file's own "MUST NOT println" doc trips it.
- **CI heavy lanes skipped after a force-push:** CI's path filter reads `github.event.before`; a rebase orphans it → "bad object" → empty diff → code lanes skip. Fix with a tiny fast-forward re-poke commit.
- **Docs-only commit cancels in-progress code CI:** `cancel-in-progress` + the docs path filter can leave a code commit with zero CI signal — don't push a docs tick before the code commit's CI finishes.

---

When you finish a verification run, if you learned something new, **edit this file** before you summarize. That is the difference between a verification skill and a one-off.
