#!/usr/bin/env bash
# _gcloud_lib.sh — shared helpers for the Google Cloud SDK, sourced by
# test_firebase.sh and run_on_device.sh. Not meant to run standalone.
#
# Functions:
#   ensure_gcloud           install gcloud idempotently + put it on PATH
#   gcloud_auth             activate the service account + set the project
#   fetch_staging_debug_keystore  materialize the shared staging-debug signing
#                           keystore from Secret Manager (after gcloud_auth)
#   gcloud_cleanup_key      remove the temp key / keystore files written here
#
# Credentials are env-var-only (never hard-coded):
#   GOOGLE_APPLICATION_CREDENTIALS  path to a GCP service-account JSON, OR
#   GCP_SA_KEY_JSON                 the raw JSON (written to a 0600 temp file)
#   FIREBASE_PROJECT_ID             GCP/Firebase project id

_GCLOUD_TMP_KEY=""
_GCLOUD_TMP_KEYSTORE=""

ensure_gcloud() {
  if command -v gcloud >/dev/null 2>&1; then return 0; fi
  local home parent
  home="${GCLOUD_HOME:-$HOME/google-cloud-sdk}"
  parent="$(dirname "$home")"
  if [[ ! -x "$home/bin/gcloud" ]]; then
    echo "[gcloud] installing Google Cloud SDK (needs dl.google.com)..."
    curl -fsSL https://dl.google.com/dl/cloudsdk/channels/rapid/downloads/google-cloud-cli-linux-x86_64.tar.gz \
      -o /tmp/gcloud.tgz || { echo "[gcloud] download failed (allowlist dl.google.com)"; return 1; }
    tar -xzf /tmp/gcloud.tgz -C "$parent"
    "$home/install.sh" -q --path-update false >/dev/null
  fi
  export PATH="$home/bin:$PATH"
  command -v gcloud >/dev/null 2>&1 || { echo "[gcloud] still not on PATH after install"; return 1; }
}

gcloud_auth() {
  local key="${GOOGLE_APPLICATION_CREDENTIALS:-}"
  if [[ -z "$key" && -n "${GCP_SA_KEY_JSON:-}" ]]; then
    key="$(mktemp)"; chmod 600 "$key"
    printf '%s' "$GCP_SA_KEY_JSON" > "$key"
    _GCLOUD_TMP_KEY="$key"
  fi
  if [[ -z "$key" || ! -f "$key" ]]; then
    echo "[gcloud] ERROR: set GOOGLE_APPLICATION_CREDENTIALS (path) or GCP_SA_KEY_JSON (raw JSON)." >&2
    return 1
  fi
  # Default to the staging GCP project (project number 27815942904) — already
  # Firebase-enabled; reused for Test Lab so device runs share its free quota.
  # Override with FIREBASE_PROJECT_ID for a dedicated project.
  : "${FIREBASE_PROJECT_ID:=nearyou-staging}"
  # A Claude Code cloud session exports CLOUDSDK_AUTH_ACCESS_TOKEN as a proxy
  # placeholder, and gcloud prefers that variable over any activated account:
  # every call would go out with the placeholder and fail UNAUTHENTICATED.
  # Drop it (callers source this lib, so the unset reaches their gcloud calls)
  # so the service-account key below is what authenticates.
  unset CLOUDSDK_AUTH_ACCESS_TOKEN
  gcloud auth activate-service-account --key-file="$key" --quiet || return 1
  gcloud config set project "$FIREBASE_PROJECT_ID" --quiet || return 1
  echo "[gcloud] authenticated; project=$FIREBASE_PROJECT_ID"
}

# The shared staging-debug signing keystore (mobile/app/build.gradle.kts applies
# it to stagingDebug). Its SHA-1 is registered on the Firebase Android app for
# id.nearyou.app.staging, so an APK signed with it can complete Google Sign-In
# on a Test Lab device; the random ~/.android/debug.keystore a cloud session or
# CI runner generates cannot. Call after gcloud_auth and before build_apks.
# Best-effort: without access it warns and the build keeps the default keystore.
fetch_staging_debug_keystore() {
  [[ "${FLAVOR:-staging}" == "staging" ]] || return 0
  if [[ -n "${NEARYOU_STAGING_DEBUG_KEYSTORE:-}" && -n "${NEARYOU_STAGING_DEBUG_KEYSTORE_PASSWORD:-}" ]]; then
    echo "[keystore] using NEARYOU_STAGING_DEBUG_KEYSTORE from the environment"
    return 0
  fi
  local project="nearyou-staging" ks pw sha1
  ks="$(mktemp)"; chmod 600 "$ks"
  gcloud secrets versions access latest --secret=staging-android-debug-keystore \
    --project="$project" 2>/dev/null | base64 -d > "$ks" 2>/dev/null || true
  pw="$(gcloud secrets versions access latest --secret=staging-android-debug-keystore-password \
    --project="$project" 2>/dev/null || true)"
  if [[ ! -s "$ks" || -z "$pw" ]]; then
    rm -f "$ks"
    echo "[keystore] WARN: staging-debug keystore not readable from Secret Manager; signing with" \
      "the default debug keystore, so Google Sign-In will fail on the device." >&2
    return 0
  fi
  _GCLOUD_TMP_KEYSTORE="$ks"
  export NEARYOU_STAGING_DEBUG_KEYSTORE="$ks" NEARYOU_STAGING_DEBUG_KEYSTORE_PASSWORD="$pw"
  if command -v keytool >/dev/null 2>&1; then
    sha1="$(keytool -list -v -keystore "$ks" -storetype pkcs12 \
      -storepass:env NEARYOU_STAGING_DEBUG_KEYSTORE_PASSWORD 2>/dev/null | sed -n 's/^.*SHA1: *//p' | head -1 || true)"
  fi
  echo "[keystore] signing stagingDebug with the shared staging-debug keystore (SHA-1 ${sha1:-unknown})"
}

gcloud_cleanup_key() {
  [[ -n "$_GCLOUD_TMP_KEY" && -f "$_GCLOUD_TMP_KEY" ]] && rm -f "$_GCLOUD_TMP_KEY"
  [[ -n "$_GCLOUD_TMP_KEYSTORE" && -f "$_GCLOUD_TMP_KEYSTORE" ]] && rm -f "$_GCLOUD_TMP_KEYSTORE"
  _GCLOUD_TMP_KEY=""
  _GCLOUD_TMP_KEYSTORE=""
}

# Best-effort: pull a Test Lab run's artifacts (screenshots, video, logs) from
# the GCS results path that `gcloud firebase test android run` prints, into a
# local directory. Args: <gcloud-output-logfile> <dest-dir>. Never hard-fails.
pull_testlab_artifacts() {
  local logf="$1" dest="$2" gcs
  # The CLI prints either a gs:// URL or a console storage-browser URL.
  # NOTE the trailing `|| true`: under `set -euo pipefail` a command-substitution
  # assignment whose pipeline exits non-zero (grep finds no match → 1, amplified
  # by pipefail) ABORTS the whole script. That bug previously killed run_on_device.sh
  # right here — before the verdict/pull ran — so a Passed Robo run still showed ⚠️.
  gcs="$(grep -oE 'gs://[A-Za-z0-9._/-]+' "$logf" 2>/dev/null | head -1 || true)"
  if [[ -z "$gcs" ]]; then
    gcs="$(grep -oE 'storage/browser/[A-Za-z0-9._/-]+' "$logf" 2>/dev/null | head -1 | sed 's#storage/browser/#gs://#' || true)"
  fi
  if [[ -z "$gcs" ]]; then
    echo "[gcloud] could not locate the GCS results path in output — see the Firebase console link above." >&2
    return 0
  fi
  mkdir -p "$dest"
  echo "[gcloud] downloading artifacts from ${gcs%/} ..."
  # Surface stderr (not silent) so a permission/path issue is diagnosable in the
  # CI log. Recurse the whole results dir — Robo stores video.mp4 + screenshots
  # in per-device subfolders, so copy the directory, not just a top-level glob.
  if gcloud storage cp --recursive "${gcs%/}" "$dest/" 2>&1; then
    echo "[gcloud] artifacts saved under: $dest"
  else
    echo "[gcloud] artifact download failed (non-fatal) — browse results at: $gcs" >&2
  fi
  return 0
}
