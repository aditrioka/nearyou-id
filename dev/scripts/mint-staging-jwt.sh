#!/usr/bin/env sh
# Mint a STAGING access JWT for a seeded staging users.id and print the ready-to-paste
# `adb shell am start` deep-link that drops an authenticated session into the staging (debug)
# app — reaching the authenticated staging Home on a real device (or in Maestro E2E) WITHOUT the
# interactive Google sign-in against api-staging.nearyou.id.
#
# Usage:
#   dev/scripts/mint-staging-jwt.sh [user-uuid] [token-version]
#
# Defaults: user-uuid     = the canonical staging smoke-test user (smoketest_adi)
#           token-version = 0
# Env overrides: STAGING_PROJECT, STAGING_RSA_SECRET, STAGING_TEST_USER_ID, STAGING_APP_ID,
#                DEEP_LINK_SCHEME.
#
# WHY THIS IS SAFE (full threat model + operator sign-off:
# mobile/app/maestro/PHASE-3-staging-test-login.md): the staging backend validates the bearer
# JWT's RSA signature on EVERY request (auth/AuthPlugin.kt). A forged token is accepted only if
# it is signed by the staging private key — so this script pulls `staging-ktor-rsa-private-key`
# from GCP Secret Manager and reuses the REAL backend issuer (`:backend:ktor:mintDevJwt` ->
# JwtIssuer.issueAccessToken) so the claim set can never drift from what the server expects
# (sub = users.id, token_version, exp). The deep-link activity only WRITES the token; the
# signature check is the real gate. The token is short-lived (900s, per OWASP access-token
# guidance) — re-run this script to refresh.
#
# PREREQUISITE — the target user MUST already exist in the staging DB: a public.users row whose
# id == <user-uuid>, with matching token_version, NOT banned and NOT suspended (these are the
# AuthPlugin auth gates: is_banned + suspended_until). For a useful demo the user should also be
# NOT shadow-banned and NOT soft-deleted, so the Home actually shows content (visibility, not auth).
# This script does NOT write to staging. Confirm (or seed) the user via the staging Supabase access
# patterns in the PHASE-3 note, e.g. the Supabase MCP:
#   execute_sql: SELECT id, username, token_version, is_banned, suspended_until,
#                       is_shadow_banned, deleted_at
#                FROM users WHERE id = '<user-uuid>';
# or the pooler psql fallback documented there. The default user (smoketest_adi) is already seeded.
set -eu

# --- config (overridable via env) ---------------------------------------------------------
STAGING_PROJECT="${STAGING_PROJECT:-nearyou-staging}"
STAGING_RSA_SECRET="${STAGING_RSA_SECRET:-staging-ktor-rsa-private-key}"
# Canonical staging smoke-test user (smoketest_adi). Override with arg 1 or $STAGING_TEST_USER_ID.
DEFAULT_TEST_USER_ID="${STAGING_TEST_USER_ID:-986142e3-0f12-43fd-92a5-cf40e1b70bd4}"
STAGING_APP_ID="${STAGING_APP_ID:-id.nearyou.app.staging}"
DEEP_LINK_SCHEME="${DEEP_LINK_SCHEME:-nearyou-staging}"

user_id="${1:-$DEFAULT_TEST_USER_ID}"
token_version="${2:-0}"

script_dir="$(cd "$(dirname "$0")" && pwd)"

# --- pull the staging signing key from GCP Secret Manager ---------------------------------
command -v gcloud >/dev/null 2>&1 || {
    echo "error: gcloud not found. Install it + 'gcloud auth login' (needs read access to" \
        "secret '$STAGING_RSA_SECRET' in project '$STAGING_PROJECT')." >&2
    exit 1
}
echo "# pulling '$STAGING_RSA_SECRET' from GCP Secret Manager (project '$STAGING_PROJECT')…" >&2
staging_key="$(gcloud secrets versions access latest \
    --secret="$STAGING_RSA_SECRET" --project="$STAGING_PROJECT")" || {
    echo "error: could not read secret '$STAGING_RSA_SECRET' from '$STAGING_PROJECT'" \
        "(check 'gcloud auth login' + IAM Secret Accessor)." >&2
    exit 1
}

# --- mint via the real backend issuer (zero claim drift) ----------------------------------
# Pre-set KTOR_RSA_PRIVATE_KEY so mint-dev-jwt.sh signs with the STAGING key and skips dev/.env
# (its guard only sources dev/.env when KTOR_RSA_PRIVATE_KEY is unset).
echo "# minting staging access JWT for user=$user_id token_version=$token_version…" >&2
# MINT_NO_DAEMON=1 makes mint-dev-jwt.sh add `--no-daemon` so the real staging signing key does not
# linger in a long-lived Gradle daemon JVM's environment after this one-shot mint exits.
access="$(KTOR_RSA_PRIVATE_KEY="$staging_key" MINT_NO_DAEMON=1 \
    "$script_dir/mint-dev-jwt.sh" "$user_id" "$token_version")"

if [ -z "$access" ]; then
    echo "error: minting produced an empty token (is the gradle mintDevJwt task healthy?)." >&2
    exit 1
fi

# --- derive the absolute expiry (epoch millis) for the deep-link's `exp` hint -------------
# The activity falls back to now+15min if `exp` is omitted; set it to the token's REAL exp so the
# client's staleness check aligns. Prefer python3 to decode the base64url JWT payload; else
# approximate as now + 900s (the access TTL).
exp_ms=""
if command -v python3 >/dev/null 2>&1; then
    exp_ms="$(printf '%s' "$access" | python3 -c 'import sys, base64, json
payload = sys.stdin.read().strip().split(".")[1]
payload += "=" * (-len(payload) % 4)
print(json.loads(base64.urlsafe_b64decode(payload))["exp"] * 1000)' 2>/dev/null || true)"
fi
if [ -z "$exp_ms" ]; then
    exp_ms=$(( $(date +%s) * 1000 + 900000 ))
fi

# --- emit the ready-to-paste deep-link ----------------------------------------------------
# Two forms: the RAW url (for Maestro `openLink`), and an adb command whose `&` are backslash-
# escaped so they survive the on-device shell re-parse (adb joins argv with spaces and drops
# quoting, so an unescaped `&` would background the command on the device).
raw_deep_link="${DEEP_LINK_SCHEME}://test-login?access=${access}&refresh=${access}&exp=${exp_ms}"
adb_deep_link="${DEEP_LINK_SCHEME}://test-login?access=${access}\\&refresh=${access}\\&exp=${exp_ms}"
{
    echo "# staging test-login ready — target app: $STAGING_APP_ID (install a staging DEBUG build first)."
    echo "# token exp epoch-ms: $exp_ms  (~15 min; re-run this script to refresh)."
    echo "#"
    echo "# Maestro openLink (raw URL):"
    echo "#   $raw_deep_link"
    echo "#"
    echo "# Manual paste (adb; '&' escaped for the on-device shell):"
} >&2
printf 'adb shell am start -a android.intent.action.VIEW -d "%s" %s\n' "$adb_deep_link" "$STAGING_APP_ID"
