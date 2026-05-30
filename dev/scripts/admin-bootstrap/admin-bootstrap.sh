#!/usr/bin/env sh
# Generate the SQL INSERT for a new admin_users row (Argon2id password hash +
# AES-GCM-encrypted TOTP secret) for the admin-login-argon2-totp flow (Admin #3).
#
# There is NO admin self-signup endpoint by design — the bootstrap admin is
# created out-of-band with this script, then the printed INSERT is applied to
# the target database (staging via the Supabase SQL editor / a one-shot job;
# production once the prod key slot is provisioned).
#
# Usage:
#   dev/scripts/admin-bootstrap/admin-bootstrap.sh \
#       --email oka@nearyou.id --display-name "Oka" --role owner
#
# The script prompts for the password interactively (hidden input).
#
# ── Required: the AES-256 key for totp_secret_encrypted ──────────────────────
# Set ADMIN_TOTP_AES_KEY_BASE64 to the base64 of the 256-bit key from the
# env-namespaced GCP Secret Manager slot:
#   - staging:    staging-admin-totp-secret-aes-key
#   - production: admin-totp-secret-aes-key
#
#   ⚠ SHELL-HISTORY MITIGATION ⚠
#   Resolving the key writes its plaintext to your shell history unless you
#   guard against it. Do BOTH:
#     1. Enable ignorespace once per shell:   export HISTCONTROL=ignorespace
#        (zsh: `setopt HIST_IGNORE_SPACE`)
#     2. Prefix the resolve command with a LEADING SPACE so it is not recorded:
#         export ADMIN_TOTP_AES_KEY_BASE64="$(gcloud secrets versions access latest \
#            --secret=staging-admin-totp-secret-aes-key --project=nearyou-staging)"
#   (note the leading space before `export`).
#
# Output: operator instructions + the base32 TOTP secret (shown ONCE — enter it
# into the authenticator app immediately) + the SQL INSERT, all on stdout.
# DO NOT redirect this output to a file.
set -eu

if [ -z "${ADMIN_TOTP_AES_KEY_BASE64+x}" ] || [ -z "${ADMIN_TOTP_AES_KEY_BASE64}" ]; then
    echo "ADMIN_TOTP_AES_KEY_BASE64 is not set — see the shell-history mitigation in this script's header." >&2
    exit 1
fi

repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$repo_root"

# --quiet strips gradle chrome; the task prints only the operator output.
# --no-configuration-cache matches the mint-dev-jwt / flywayMigrate workaround
# for application-plugin JavaExec tasks.
./gradlew --quiet --no-configuration-cache :backend:ktor:adminBootstrap --args="$*"
