# Android dev-environment setup — checklist

Resumable checklist for the Claude Code cloud-sandbox Android environment
(build APK + dispatch instrumented tests to a device farm; **no local emulator**).
Update each box as a step lands so a dropped session can resume without redoing work.

## Repo facts (detected, not assumed)

- Android module: **`:mobile:app`** (`android.namespace = id.nearyou.app`).
- Flavor dimension `env`: **`dev` / `staging` / `production`**; build types `debug` / `release`.
  - Device-farm flavor = **`staging`** (`API_BASE_URL = https://api-staging.nearyou.id`,
    side-by-side install via `.staging` suffix, real staging Google OAuth client).
    `dev` points at `10.0.2.2` (emulator loopback) — useless on a real device farm.
- `compileSdk = targetSdk = 36`, `minSdk = 24` (from `gradle/libs.versions.toml`).
- Gradle JVM toolchain = **21** (`build-logic/.../nearyou.kotlin.jvm.gradle.kts → jvmToolchain(21)`);
  JDK 21 is pre-installed at `/usr/lib/jvm/java-21-openjdk-amd64` and auto-detected by Gradle.
- AGP `8.11.2`, Kotlin `2.3.21`, Gradle wrapper `8.14.3`.
- Variant build tasks:
  - app APK: `:mobile:app:assembleStagingDebug`
  - test APK: `:mobile:app:assembleStagingDebugAndroidTest`

## Hard constraints honored

- JDK 17 installed + `JAVA_HOME` set to it (Gradle daemon runs on 17; compile toolchain = pre-installed JDK 21).
- SDK packages: `platform-tools`, `platforms;android-35`, `build-tools;35.0.0` (constraint baseline)
  **plus** `platforms;android-36`, `build-tools;36.0.0` (what `compileSdk=36` actually needs).
- **No `emulator`, no `system-images`** packages installed.
- All scripts idempotent (skip work already present; reuse env caching).
- Env vars persisted to `$CLAUDE_ENV_FILE`.
- No hard-coded credentials — referenced as env vars only.

## Checklist

- [x] 1. Detect Android module + gradle tasks (read `build.gradle.kts`, not assumed).
- [x] 2. `scripts/setup_android.sh` — JDK 17, cmdline-tools, SDK packages, licenses, env persistence.
- [x] 3. `scripts/verify_env.sh` — assert `java`, `sdkmanager`, `gradlew`, `adb`; non-zero on any miss.
- [x] 4. `scripts/test_firebase.sh` — build debug + androidTest APKs, dispatch to Firebase Test Lab.
- [x] 5. `scripts/test_browserstack.sh` — build debug + androidTest APKs, dispatch to BrowserStack Espresso.
- [x] 6. `.claude/settings.json` — SessionStart hook running `verify_env.sh`.
- [x] 7. CLAUDE.md — "Android build & test (cloud sandbox)" section.
- [x] 8. This checklist file.
- [x] 9. Ran `setup_android.sh` end-to-end in the live sandbox — JDK 17 installed, cmdline-tools + all SDK packages installed, env persisted; `verify_env.sh` reports **Environment OK** and `java -version` resolves to 17.
- [x] 10. Committed + pushed to `claude/android-dev-env-nearbyid-379enc`.

> Bonus validation: `./gradlew :mobile:app:tasks` ran **BUILD SUCCESSFUL** with the JDK 17
> daemon + auto-detected JDK 21 compile toolchain. A full `assembleStagingDebug` +
> `assembleStagingDebugAndroidTest` build then ran **BUILD SUCCESSFUL**, producing real APKs
> (`app-staging-debug.apk` 19M, `app-staging-debug-androidTest.apk`). The network allowlist in
> this environment is already fully open (build repos + Firebase + BrowserStack all reachable).

## Device-farm / real-device run (added for vibe-code-from-phone)

- [x] 11. `scripts/_gcloud_lib.sh` — shared idempotent gcloud install + service-account auth + artifact pull.
- [x] 12. `scripts/run_on_device.sh` — build APK + Firebase Test Lab **Robo run** on a real device + download screenshots/video to `dev/device-runs/<ts>/`.
- [x] 13. `scripts/test_android.sh` — parity wrapper (local adb `connectedAndroidTest` vs farm dispatch).
- [x] 14. Refactor `test_firebase.sh` onto the shared gcloud lib.
- [x] 15. `dev/docs/device-farm.md` — device-farm runbook + one-time Firebase operator setup + cost.
- [x] 16. `.gitignore` += `dev/device-runs/`; CLAUDE.md "Android build & test" section updated.
- [ ] 17. **Operator (one-time, UI/GCP):** create/enable Firebase Test Lab project + service account, then add `GCP_SA_KEY_JSON` (or `GOOGLE_APPLICATION_CREDENTIALS`) + `FIREBASE_PROJECT_ID` as environment secrets. See `dev/docs/device-farm.md`. Until this lands, the farm scripts are ready but cannot dispatch (no live end-to-end run possible without credentials).

## Network allowlist required (add in Create-environment UI)

```
dl.google.com
*.gradle.org
repo.maven.apache.org
*.maven.org
plugins.gradle.org
*.googleapis.com
*.gstatic.com
api-cloud.browserstack.com
api.browserstack.com
```

## Environment variables the user must supply (device-farm dispatch only)

```
# Firebase Test Lab (test_firebase.sh)
GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json   # or GCP_SA_KEY_JSON (raw JSON)
FIREBASE_PROJECT_ID=nearyou-id-staging

# BrowserStack (test_browserstack.sh)
BROWSERSTACK_USERNAME=...
BROWSERSTACK_ACCESS_KEY=...
```
