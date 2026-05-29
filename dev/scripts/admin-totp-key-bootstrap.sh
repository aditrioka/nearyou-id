#!/usr/bin/env bash
# Provision the AES-256 key slot for admin_users.totp_secret_encrypted
# (admin-login-argon2-totp / Admin #3) in GCP Secret Manager.
#
# WHY this exists:
#   - The Admin #3 login flow decrypts admin_users.totp_secret_encrypted via
#     AES-256-GCM with a key sourced from the env-namespaced slot
#     `admin-totp-secret-aes-key` (staging: `staging-admin-totp-secret-aes-key`)
#     through the secretKey(env, name) helper.
#   - Like the admin_app DB role (provision-admin-app-staging.sh), the key
#     slot is OPERATIONAL — it lives in Secret Manager, not in Flyway or app
#     config. This script is the idempotent provisioning counterpart.
#
# WHAT it does (idempotent):
#   1. CREATE the secret slot if absent (automatic replication).
#   2. ADD a fresh 256-bit AES key version ONLY if the slot has no enabled
#      version yet (re-runs do NOT rotate the key — rotation is a deliberate,
#      separate operation, since rotating this key orphans every existing
#      totp_secret_encrypted ciphertext).
#   3. GRANT roles/secretmanager.secretAccessor to the Cloud Run runtime SA.
#
# USAGE:
#   Staging (default):
#       dev/scripts/admin-totp-key-bootstrap.sh
#   Production (deferred to the production-bootstrap milestone — do NOT run
#   until prod infra exists):
#       PROJECT_OVERRIDE=nearyou-production SLOT_OVERRIDE=admin-totp-secret-aes-key \
#       RUNTIME_SA_OVERRIDE=<prod-runtime-sa> dev/scripts/admin-totp-key-bootstrap.sh
#
# SAFETY: this script NEVER prints the key material. The `openssl rand` output
# is piped straight into `gcloud secrets versions add --data-file=-`.
set -euo pipefail

PROJECT="${PROJECT_OVERRIDE:-nearyou-staging}"
SLOT="${SLOT_OVERRIDE:-staging-admin-totp-secret-aes-key}"
# Default staging Cloud Run runtime SA (matches provision-admin-app-staging.sh).
RUNTIME_SA="${RUNTIME_SA_OVERRIDE:-27815942904-compute@developer.gserviceaccount.com}"

echo "Project:   $PROJECT"
echo "Slot:      $SLOT"
echo "Runtime SA: $RUNTIME_SA"

# 1. Create the slot if absent.
if gcloud secrets describe "$SLOT" --project="$PROJECT" >/dev/null 2>&1; then
    echo "Slot $SLOT already exists — skipping create."
else
    echo "Creating slot $SLOT ..."
    gcloud secrets create "$SLOT" --project="$PROJECT" --replication-policy=automatic
fi

# 2. Add a key version ONLY if no enabled version exists (no rotation on re-run).
ENABLED_VERSIONS="$(gcloud secrets versions list "$SLOT" --project="$PROJECT" \
    --filter="state=enabled" --format="value(name)" 2>/dev/null | wc -l | tr -d ' ')"
if [ "$ENABLED_VERSIONS" = "0" ]; then
    echo "No enabled version — adding a fresh 256-bit AES key ..."
    # base64 of 32 random bytes; piped directly, never echoed.
    openssl rand -base64 32 | gcloud secrets versions add "$SLOT" --project="$PROJECT" --data-file=-
    echo "Key version added."
else
    echo "Slot already has $ENABLED_VERSIONS enabled version(s) — NOT rotating (would orphan ciphertexts)."
fi

# 3. Grant secretAccessor to the Cloud Run runtime SA.
echo "Granting secretAccessor to $RUNTIME_SA ..."
gcloud secrets add-iam-policy-binding "$SLOT" \
    --project="$PROJECT" \
    --member="serviceAccount:$RUNTIME_SA" \
    --role="roles/secretmanager.secretAccessor" >/dev/null
echo "Done. $SLOT is provisioned + accessible by the runtime SA."
