# Running the mobile app on real devices (cloud sandbox → device farm)

The Claude Code cloud sandbox is a headless Linux VM (no KVM/GPU), so it cannot
run an emulator or show a screen. To **see a change running on a real device**
— including when you're vibe-coding from your phone — the sandbox builds the
APK and dispatches it to **Firebase Test Lab** (Google's real-device cloud), then
pulls the screenshots/video back. BrowserStack is wired as an optional fallback.

This is the parity model: local dev keeps its fast `adb` inner loop; cloud (and
local-without-a-device) share the exact same APK + farm path. One wrapper picks
the right runner automatically.

## Scripts

| Script | What it does |
|---|---|
| `scripts/run_on_device.sh` | **Build APK + Robo run on a real device** (auto UI-crawl, no test code) + download screenshots/video to `dev/device-runs/<ts>/`. This is the "show me my change on a device" command. |
| `scripts/test_android.sh` | Parity wrapper: adb device attached → `connectedAndroidTest`; else → farm (`FARM=local\|firebase\|browserstack`). |
| `scripts/test_firebase.sh` | Build APKs + run the **instrumented test suite** on Firebase Test Lab. |
| `scripts/test_browserstack.sh` | Same, on BrowserStack App Automate (fallback). |
| `scripts/setup_android.sh` / `verify_env.sh` | Toolchain install / health check (both run from `scripts/session_start.sh`; the SDK is pre-installed by the environment setup script, see [`cloud-environment.md`](cloud-environment.md)). |

`gcloud` is installed on demand by `scripts/_gcloud_lib.sh` (idempotent); no CLI
is needed for BrowserStack (REST via `curl`).

## One-time Firebase setup (operator)

The sandbox can't provision GCP for you — do this once, then drop two secrets in
the environment config and everything works hands-off.

**Project:** reuse the existing **`nearyou-staging`** GCP project (project number
`27815942904`) — it's already Firebase-enabled, so Test Lab device runs share its
free quota. (Override to a dedicated project with `FIREBASE_PROJECT_ID` if you'd
rather isolate the quota.)

**The easy path — run the provisioning script** in Cloud Shell or any
gcloud authenticated as Owner/Editor on the project:

```bash
PROJECT_ID=nearyou-staging dev/scripts/provision-test-lab-sa.sh
```

It idempotently: enables the Cloud Testing + Cloud Tool Results APIs, creates a
least-privilege `test-lab-runner@nearyou-staging.iam.gserviceaccount.com` service
account (role `editor` — Firebase's documented requirement so the run can
initialize the Test Lab default results bucket), mints a JSON
key, and prints exactly what to paste.

**Then add to the Claude Code environment** (Settings → environment secrets —
never commit):

```dotenv
FIREBASE_PROJECT_ID=nearyou-staging
GCP_SA_KEY_JSON={"type":"service_account",...}   # the whole key JSON, OR
GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json # if mounted as a file
```

`FIREBASE_PROJECT_ID` already defaults to `nearyou-staging` in the scripts, so in
practice you only need to supply the key.

That's the entire human-only step. After it, from your phone you can ask the
agent to "run my change on a device" and it will build, Robo-run on a real Pixel,
and send the screenshots back.

> Manual equivalent (if you'd rather not run the script): `gcloud services enable
> testing.googleapis.com toolresults.googleapis.com`; create the service account;
> grant `roles/editor`; `gcloud iam service-accounts keys create`.

### Cost (as of 2026-06)

- **Spark (free):** 5 physical + 5 virtual device runs/day — enough for iteration.
- **Blaze (pay-as-you-go):** 30 min/day physical free, then ~$5/hr physical (~$1/hr virtual).

A single Robo run is a few minutes, so day-to-day iteration typically sits inside
the free quota. Verify current numbers at
<https://firebase.google.com/docs/test-lab/usage-quotas-pricing>.

## CI: auto-run every mobile PR on a real device

`.github/workflows/device-run.yml` builds the staging-debug APK and does a Robo
run on a real device for every PR that touches `mobile/**` / `shared/**`, then
posts a comment with the result, screenshot count, a link to the live Firebase
console results (screenshots + video), and uploads the captured artifacts to the
workflow run. Manual runs via **workflow_dispatch** (pick device + flavor); opt a
PR out with the `skip-device-run` label.

Credentials: the workflow uses `secrets.GCP_TESTLAB_SA_KEY` if set (the
least-privilege `test-lab-runner` key from `provision-test-lab-sa.sh`), else falls
back to the existing `secrets.GCP_SA_KEY`. Whichever service account is used must
hold `roles/editor` (so Test Lab can initialize the default results bucket) — the
deploy SA behind `GCP_SA_KEY` does **not** by default, so either set
`GCP_TESTLAB_SA_KEY` (recommended) or grant the deploy SA `roles/editor`.

It's quota-aware: superseded runs are cancelled and only mobile-code pushes
trigger it, keeping within the 5-physical-runs/day free tier.

## Google Sign-In on the device: the shared staging-debug keystore

Google Sign-In only works for an APK whose signing-cert SHA-1 is registered for
its package. A cloud session or CI runner generates a fresh random
`~/.android/debug.keystore`, so its builds were rejected on the device
(`not registered to use OAuth2.0`, error `28444`, shown in the app as the
connectivity error on the sign-in screen).

So cloud and CI device runs sign `stagingDebug` with one shared keystore:

- **Secret Manager** (`nearyou-staging`): `staging-android-debug-keystore` (the
  PKCS12 file, base64, alias `nearyou-staging-debug`) and
  `staging-android-debug-keystore-password`. `test-lab-runner` holds
  `roles/secretmanager.secretAccessor` on both, so the key the scripts already use
  can read them. No GitHub secret is involved.
- **Firebase** Android app `id.nearyou.app.staging`: its SHA-1
  `E5:CC:49:40:64:D3:24:61:2E:DA:19:6D:CC:91:5B:BB:E9:4A:D1:80` is registered next
  to the operator's own debug SHA-1 (Firebase creates the matching OAuth client).
- **Wiring:** `run_on_device.sh` / `test_firebase.sh` call
  `fetch_staging_debug_keystore` (`scripts/_gcloud_lib.sh`) right after
  `gcloud_auth`. It writes the keystore to a 0600 temp file, exports
  `NEARYOU_STAGING_DEBUG_KEYSTORE` / `_PASSWORD`, prints the SHA-1, and the file
  is removed on exit. `mobile/app/build.gradle.kts` applies it to `stagingDebug`
  only. Without those variables (a local build) nothing changes, and without
  Secret Manager access the scripts warn and fall back to the default keystore.
  `test_browserstack.sh` does not fetch it.

To rotate or recreate it (Cloud Shell, as project Owner), then add the printed
SHA-1 under Firebase **Project settings → General → `id.nearyou.app.staging` →
Add fingerprint** and remove the old one:

```bash
gcloud config set project nearyou-staging
PW=$(openssl rand -base64 24)
keytool -genkeypair -keystore staging-debug.p12 -storetype PKCS12 \
  -alias nearyou-staging-debug -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PW" -dname "CN=NearYou Staging Debug"
keytool -list -v -keystore staging-debug.p12 -storepass "$PW" | grep 'SHA1:'
base64 -w0 staging-debug.p12 | gcloud secrets versions add staging-android-debug-keystore --data-file=-
printf '%s' "$PW" | gcloud secrets versions add staging-android-debug-keystore-password --data-file=-
rm staging-debug.p12; unset PW
```

(First-time setup used `gcloud secrets create` plus `gcloud secrets
add-iam-policy-binding ... --member=serviceAccount:test-lab-runner@nearyou-staging.iam.gserviceaccount.com
--role=roles/secretmanager.secretAccessor` on both secrets.)

## Choosing a device

`--device` specs use Test Lab model ids. List them with:

```
gcloud firebase test android models list
```

Defaults in the scripts target a recent Pixel (`model=shiba` = Pixel 8, API 34);
override with `DEVICE=...` (run_on_device) or `FTL_DEVICE=...` (test_firebase).

## Network allowlist

Already-open in the current environment (verified): `dl.google.com`,
`*.googleapis.com`, `*.gstatic.com` (Firebase) and `api*.browserstack.com`
(BrowserStack), plus the build repos (`*.gradle.org`, maven, google). If you spin
up a fresh environment, add those to its allowlist.
