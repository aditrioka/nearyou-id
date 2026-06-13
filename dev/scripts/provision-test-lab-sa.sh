#!/usr/bin/env bash
# Provision a least-privilege Firebase Test Lab runner service account so the
# Claude Code cloud sandbox can build the :mobile:app APK and run it on real
# devices (Robo runs via scripts/run_on_device.sh, instrumented tests via
# scripts/test_firebase.sh).
#
# WHY this exists:
#   The headless cloud sandbox can't show a screen, so "see my change on a
#   device" is dispatched to Firebase Test Lab. That needs a service-account
#   key the sandbox holds as the GCP_SA_KEY_JSON env secret. This script is the
#   operator's one-time, idempotent setup — run it once in an authenticated
#   gcloud (Cloud Shell, or a workstation with Owner/Editor on the project).
#
# WHAT it does (idempotent):
#   1. Enable the Cloud Testing API + Cloud Tool Results API.
#   2. Create the `test-lab-runner` service account (skip if it exists).
#   3. Grant least-privilege roles:
#        - roles/cloudtestservice.admin   (create/monitor test matrices)
#        - roles/storage.admin            (fetch/access the Test Lab default
#          results bucket + read Robo screenshots/video — objectViewer alone is
#          insufficient: the run needs storage.buckets.get on the default bucket)
#      (Editor would also work but is intentionally broader; we stay scoped.)
#   4. Mint a JSON key and print exactly what to paste into the Claude Code
#      environment secrets.
#
# Project IDs + SA emails are non-sensitive per CLAUDE.md § Public-repo posture.
# The minted KEY is a secret — it is written to a local file you delete after
# pasting; it is NEVER committed.
#
# Usage:
#   PROJECT_ID=nearyou-staging dev/scripts/provision-test-lab-sa.sh
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-nearyou-staging}"        # staging GCP project (number 27815942904)
SA_NAME="${SA_NAME:-test-lab-runner}"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
KEY_OUT="${KEY_OUT:-./test-lab-runner-key.json}"

command -v gcloud >/dev/null || { echo "gcloud not found — run this in Cloud Shell or install the SDK."; exit 1; }

echo "==> Project: $PROJECT_ID"
gcloud config set project "$PROJECT_ID" --quiet

echo "==> 1/4 Enabling APIs (testing + toolresults)..."
gcloud services enable testing.googleapis.com toolresults.googleapis.com --quiet

echo "==> 2/4 Service account: $SA_EMAIL"
if gcloud iam service-accounts describe "$SA_EMAIL" --quiet >/dev/null 2>&1; then
  echo "    exists — skipping create."
else
  gcloud iam service-accounts create "$SA_NAME" \
    --display-name="Firebase Test Lab runner (cloud-sandbox device runs)" --quiet
fi

echo "==> 3/4 Granting least-privilege roles..."
for role in roles/cloudtestservice.admin roles/storage.admin; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:$SA_EMAIL" --role="$role" \
    --condition=None --quiet >/dev/null
  echo "    + $role"
done

echo "==> 4/4 Minting JSON key -> $KEY_OUT"
gcloud iam service-accounts keys create "$KEY_OUT" --iam-account="$SA_EMAIL" --quiet

cat <<EOF

Done. Now add these to the Claude Code environment (Settings -> environment secrets):

  FIREBASE_PROJECT_ID=$PROJECT_ID
  GCP_SA_KEY_JSON=<paste the entire contents of $KEY_OUT>

For the GitHub Actions device-run workflow (.github/workflows/device-run.yml),
also add the same key as a repo secret named GCP_TESTLAB_SA_KEY (least-privilege;
the workflow falls back to the broader GCP_SA_KEY if it's unset):

  gh secret set GCP_TESTLAB_SA_KEY < $KEY_OUT

Then delete the local key file so it never lingers:

  rm -f $KEY_OUT

After that, from your phone you can ask the agent to "run my change on a device",
and every mobile PR auto-posts a real-device screenshot run.
EOF
