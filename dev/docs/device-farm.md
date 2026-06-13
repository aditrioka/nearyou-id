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
| `scripts/setup_android.sh` / `verify_env.sh` | Toolchain install / health check. |

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
account (roles `cloudtestservice.admin` + `storage.objectViewer`), mints a JSON
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
> grant `roles/cloudtestservice.admin` + `roles/storage.objectViewer` (or just
> `roles/editor`); `gcloud iam service-accounts keys create`.

### Cost (as of 2026-06)

- **Spark (free):** 5 physical + 5 virtual device runs/day — enough for iteration.
- **Blaze (pay-as-you-go):** 30 min/day physical free, then ~$5/hr physical (~$1/hr virtual).

A single Robo run is a few minutes, so day-to-day iteration typically sits inside
the free quota. Verify current numbers at
<https://firebase.google.com/docs/test-lab/usage-quotas-pricing>.

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
