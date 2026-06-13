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

1. **Pick / create a GCP project** for Test Lab (e.g. reuse the staging project).
2. **Enable the APIs**: Cloud Testing API (`testing.googleapis.com`) and Cloud
   Tool Results API (`toolresults.googleapis.com`).
   `gcloud services enable testing.googleapis.com toolresults.googleapis.com`
3. **Create a service account** (e.g. `test-lab-runner@<project>.iam.gserviceaccount.com`)
   and grant it the **Firebase Test Lab Admin** role plus read/write on the
   results GCS bucket (the auto-created `test-lab-*` bucket, or a dedicated one).
   Download a JSON key.
4. **Add these to the Claude Code environment** (env vars / secrets — never commit):

   ```dotenv
   GCP_SA_KEY_JSON={"type":"service_account",...}   # paste the whole key JSON, OR
   GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json # if mounted as a file
   FIREBASE_PROJECT_ID=<your-test-lab-project-id>
   ```

That's the entire human-only step. After it, from your phone you can ask the
agent to "run my change on a device" and it will build, Robo-run on a real
Pixel, and send the screenshots back.

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
