#!/usr/bin/env bash
# _gcloud_lib.sh — shared helpers for the Google Cloud SDK, sourced by
# test_firebase.sh and run_on_device.sh. Not meant to run standalone.
#
# Functions:
#   ensure_gcloud           install gcloud idempotently + put it on PATH
#   gcloud_auth             activate the service account + set the project
#   gcloud_cleanup_key      remove any temp key written from GCP_SA_KEY_JSON
#
# Credentials are env-var-only (never hard-coded):
#   GOOGLE_APPLICATION_CREDENTIALS  path to a GCP service-account JSON, OR
#   GCP_SA_KEY_JSON                 the raw JSON (written to a 0600 temp file)
#   FIREBASE_PROJECT_ID             GCP/Firebase project id

_GCLOUD_TMP_KEY=""

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
  gcloud auth activate-service-account --key-file="$key" --quiet || return 1
  gcloud config set project "$FIREBASE_PROJECT_ID" --quiet || return 1
  echo "[gcloud] authenticated; project=$FIREBASE_PROJECT_ID"
}

gcloud_cleanup_key() {
  [[ -n "$_GCLOUD_TMP_KEY" && -f "$_GCLOUD_TMP_KEY" ]] && rm -f "$_GCLOUD_TMP_KEY"
  _GCLOUD_TMP_KEY=""
}

# Best-effort: pull a Test Lab run's artifacts (screenshots, video, logs) from
# the GCS results path that `gcloud firebase test android run` prints, into a
# local directory. Args: <gcloud-output-logfile> <dest-dir>. Never hard-fails.
pull_testlab_artifacts() {
  local logf="$1" dest="$2" gcs
  # The CLI prints either a gs:// URL or a console storage-browser URL.
  gcs="$(grep -oE 'gs://[A-Za-z0-9._/-]+' "$logf" 2>/dev/null | head -1)"
  if [[ -z "$gcs" ]]; then
    gcs="$(grep -oE 'storage/browser/[A-Za-z0-9._/-]+' "$logf" 2>/dev/null | head -1 | sed 's#storage/browser/#gs://#')"
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
