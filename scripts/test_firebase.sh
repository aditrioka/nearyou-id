#!/usr/bin/env bash
# test_firebase.sh — build the :mobile:app debug + androidTest APKs and run the
# instrumented test suite on Firebase Test Lab (real physical/virtual devices in
# Google's farm — no local emulator needed).
#
# Required env (no credentials are hard-coded):
#   GOOGLE_APPLICATION_CREDENTIALS  path to a GCP service-account JSON, OR
#   GCP_SA_KEY_JSON                 the raw service-account JSON (written to a temp file)
#   FIREBASE_PROJECT_ID             GCP/Firebase project id (default: nearyou-id-staging)
#
# Optional env:
#   FLAVOR        product flavor to build (default: staging)
#   FTL_DEVICE    --device spec (default: a recent Pixel / API 33)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
# shellcheck source=/dev/null
source "$REPO_ROOT/scripts/_build_apks.sh"

FIREBASE_PROJECT_ID="${FIREBASE_PROJECT_ID:-nearyou-id-staging}"
FTL_DEVICE="${FTL_DEVICE:-model=shiba,version=34,locale=en_US,orientation=portrait}"

# --- 1. Build the APKs -------------------------------------------------------
build_apks   # sets APP_APK + TEST_APK, exits non-zero on build failure

# --- 2. gcloud CLI (install idempotently; needs *.googleapis.com / dl.google.com) ---
if ! command -v gcloud >/dev/null 2>&1; then
  echo "[firebase] installing Google Cloud SDK..."
  GCLOUD_HOME="${GCLOUD_HOME:-$HOME/google-cloud-sdk}"
  if [[ ! -x "$GCLOUD_HOME/bin/gcloud" ]]; then
    curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-linux-x86_64.tar.gz \
      -o /tmp/gcloud.tgz || { echo "[firebase] gcloud download failed (allowlist dl.google.com)"; exit 1; }
    tar -xzf /tmp/gcloud.tgz -C "$(dirname "$GCLOUD_HOME")"
    "$GCLOUD_HOME/install.sh" -q --path-update false >/dev/null
  fi
  export PATH="$GCLOUD_HOME/bin:$PATH"
fi
echo "[firebase] gcloud: $(gcloud --version 2>/dev/null | head -1)"

# --- 3. Authenticate via service account ------------------------------------
SA_KEY="${GOOGLE_APPLICATION_CREDENTIALS:-}"
cleanup_key=""
if [[ -z "$SA_KEY" && -n "${GCP_SA_KEY_JSON:-}" ]]; then
  SA_KEY="$(mktemp)"; cleanup_key="$SA_KEY"
  printf '%s' "$GCP_SA_KEY_JSON" > "$SA_KEY"
fi
[[ -n "$SA_KEY" && -f "$SA_KEY" ]] || {
  echo "[firebase] ERROR: set GOOGLE_APPLICATION_CREDENTIALS (path) or GCP_SA_KEY_JSON (raw JSON)."; exit 1; }
trap '[[ -n "$cleanup_key" ]] && rm -f "$cleanup_key"' EXIT

gcloud auth activate-service-account --key-file="$SA_KEY" --quiet
gcloud config set project "$FIREBASE_PROJECT_ID" --quiet

# --- 4. Dispatch the instrumentation run ------------------------------------
echo "[firebase] dispatching instrumentation run on $FTL_DEVICE (project=$FIREBASE_PROJECT_ID)..."
set +e
gcloud firebase test android run \
  --type instrumentation \
  --app "$APP_APK" \
  --test "$TEST_APK" \
  --device "$FTL_DEVICE" \
  --timeout 15m \
  --quiet
rc=$?
set -e

echo
if [[ $rc -eq 0 ]]; then
  echo "[firebase] RESULT: PASS — all instrumented tests passed on Test Lab."
else
  echo "[firebase] RESULT: FAIL (gcloud exit $rc) — see the Test Lab results URL printed above."
fi
exit $rc
