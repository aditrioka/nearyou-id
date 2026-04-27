#!/usr/bin/env bash
# Smoke test for `fcm-token-registration` (tasks 8.5-8.10):
# verifies POST /api/v1/user/fcm-token against staging.
#
# Per `openspec/changes/fcm-token-registration/tasks.md` § 8:
#   8.5  positive 1 — new registration (Android)            → 204
#   8.6  positive 2 — idempotent refresh, last_seen_at >    → 204 (1 row, app_version updated)
#   8.7  positive 3 — multi-platform same user (iOS)        → 204 (2 rows for user)
#   8.8  negative 1 — invalid platform "web"                → 400 invalid_platform
#   8.9  negative 2 — empty token                           → 400 empty_token
#   8.10 negative 3 — no JWT                                → 401
#
# Adapted from `dev/scripts/smoke-reply-rate-limit.sh`.
#
# Usage:
#   dev/scripts/smoke-fcm-token-registration.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     <user-uuid>
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification.
#
# Prerequisites:
#  - The user UUID points to an existing user in the target environment.
#  - The script mints a JWT via `dev/scripts/mint-dev-jwt.sh`. For staging
#    set KTOR_RSA_PRIVATE_KEY to the staging RSA key, e.g.:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#      dev/scripts/smoke-fcm-token-registration.sh <user-uuid>
#  - `psql` reachable to the target Postgres if you want SQL-side verification
#    (the script will print verification queries for you to run separately).
#
# Exit codes:
#   0 — all six steps passed.
#   1 — at least one assertion mismatched.
#   2 — usage error (missing args, JWT mint failure, etc.).

set -euo pipefail

API_BASE="https://api-staging.nearyou.id"

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base)
            API_BASE="$2"
            shift 2
            ;;
        --help|-h)
            sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
            exit 0
            ;;
        --*)
            echo "Unknown flag: $1" >&2
            exit 2
            ;;
        *)
            USER_UUID="$1"
            shift
            ;;
    esac
done

if [[ -z "${USER_UUID:-}" ]]; then
    echo "usage: $0 [--api-base <url>] <user-uuid>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
TIMESTAMP_LABEL="$(date -u +%Y%m%dT%H%M%SZ)"
TOKEN_ANDROID="smoke-fcm-android-${TIMESTAMP_LABEL}"
TOKEN_IOS="smoke-fcm-ios-${TIMESTAMP_LABEL}"
APP_VERSION_INITIAL="0.1.0-staging-smoke"
APP_VERSION_REFRESH="0.1.1-staging-smoke"

echo "==> Smoke — fcm-token-registration"
echo "    API base:  $API_BASE"
echo "    User UUID: $USER_UUID"
echo "    Tokens:    $TOKEN_ANDROID / $TOKEN_IOS"
echo

# ----------------------------------------------------------------------------
# 1. Mint a JWT for the user.
# ----------------------------------------------------------------------------
echo "==> Minting JWT for user $USER_UUID..."
JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$USER_UUID")"
if [[ -z "$JWT" ]]; then
    echo "ERROR: JWT mint failed — empty token returned. Check KTOR_RSA_PRIVATE_KEY." >&2
    exit 2
fi
echo "    JWT minted (${#JWT} chars)."
echo

post_fcm() {
    local body="$1"
    local extra_headers=()
    if [[ "${2:-with-jwt}" == "with-jwt" ]]; then
        extra_headers=(-H "Authorization: Bearer $JWT")
    fi
    curl -sS -o /tmp/smoke-fcm-body.json -w "%{http_code}" -X POST \
        "${extra_headers[@]}" \
        -H "Content-Type: application/json" \
        --data-raw "$body" \
        "$API_BASE/api/v1/user/fcm-token"
}

# ----------------------------------------------------------------------------
# 8.5 positive — new Android registration.
# ----------------------------------------------------------------------------
echo "==> 8.5 positive 1 — new Android registration (expect 204)..."
STATUS="$(post_fcm "{\"token\":\"$TOKEN_ANDROID\",\"platform\":\"android\",\"app_version\":\"$APP_VERSION_INITIAL\"}")"
if [[ "$STATUS" != "204" ]]; then
    echo "FAIL: 8.5 returned $STATUS (expected 204)." >&2
    cat /tmp/smoke-fcm-body.json >&2
    exit 1
fi
echo "    ✓ 204"
echo

# ----------------------------------------------------------------------------
# 8.6 positive — idempotent refresh (same triple, new app_version).
# ----------------------------------------------------------------------------
echo "==> 8.6 positive 2 — idempotent refresh (expect 204; verify SQL afterwards)..."
sleep 1
STATUS="$(post_fcm "{\"token\":\"$TOKEN_ANDROID\",\"platform\":\"android\",\"app_version\":\"$APP_VERSION_REFRESH\"}")"
if [[ "$STATUS" != "204" ]]; then
    echo "FAIL: 8.6 returned $STATUS (expected 204)." >&2
    cat /tmp/smoke-fcm-body.json >&2
    exit 1
fi
echo "    ✓ 204"
echo "    SQL-verify (run against staging Postgres):"
echo "      SELECT created_at, last_seen_at, app_version"
echo "        FROM user_fcm_tokens WHERE token = '$TOKEN_ANDROID';"
echo "      Expected: 1 row, last_seen_at > created_at, app_version = '$APP_VERSION_REFRESH'"
echo

# ----------------------------------------------------------------------------
# 8.7 positive — multi-platform iOS registration.
# ----------------------------------------------------------------------------
echo "==> 8.7 positive 3 — iOS registration for same user (expect 204)..."
STATUS="$(post_fcm "{\"token\":\"$TOKEN_IOS\",\"platform\":\"ios\",\"app_version\":\"$APP_VERSION_INITIAL\"}")"
if [[ "$STATUS" != "204" ]]; then
    echo "FAIL: 8.7 returned $STATUS (expected 204)." >&2
    cat /tmp/smoke-fcm-body.json >&2
    exit 1
fi
echo "    ✓ 204"
echo "    SQL-verify (run against staging Postgres):"
echo "      SELECT COUNT(*) FROM user_fcm_tokens WHERE user_id = '$USER_UUID';"
echo "      Expected: 2"
echo

# ----------------------------------------------------------------------------
# 8.8 negative — invalid platform.
# ----------------------------------------------------------------------------
echo "==> 8.8 negative 1 — invalid platform 'web' (expect 400 invalid_platform)..."
STATUS="$(post_fcm '{"token":"x","platform":"web"}')"
ERR="$(jq -r '.error // ""' /tmp/smoke-fcm-body.json 2>/dev/null || echo "")"
if [[ "$STATUS" != "400" || "$ERR" != "invalid_platform" ]]; then
    echo "FAIL: 8.8 status=$STATUS error=$ERR (expected 400 invalid_platform)." >&2
    cat /tmp/smoke-fcm-body.json >&2
    exit 1
fi
echo "    ✓ 400 invalid_platform"
echo

# ----------------------------------------------------------------------------
# 8.9 negative — empty token.
# ----------------------------------------------------------------------------
echo "==> 8.9 negative 2 — empty token (expect 400 empty_token)..."
STATUS="$(post_fcm '{"token":"","platform":"android"}')"
ERR="$(jq -r '.error // ""' /tmp/smoke-fcm-body.json 2>/dev/null || echo "")"
if [[ "$STATUS" != "400" || "$ERR" != "empty_token" ]]; then
    echo "FAIL: 8.9 status=$STATUS error=$ERR (expected 400 empty_token)." >&2
    cat /tmp/smoke-fcm-body.json >&2
    exit 1
fi
echo "    ✓ 400 empty_token"
echo

# ----------------------------------------------------------------------------
# 8.10 negative — no JWT.
# ----------------------------------------------------------------------------
echo "==> 8.10 negative 3 — no Authorization header (expect 401)..."
STATUS="$(post_fcm '{"token":"x","platform":"android"}' no-jwt)"
if [[ "$STATUS" != "401" ]]; then
    echo "FAIL: 8.10 returned $STATUS (expected 401)." >&2
    cat /tmp/smoke-fcm-body.json >&2
    exit 1
fi
echo "    ✓ 401"
echo

# ----------------------------------------------------------------------------
# Pass.
# ----------------------------------------------------------------------------
echo "==> ✅ fcm-token-registration smoke PASSED."
echo
echo "Cleanup (run against staging Postgres):"
echo "  DELETE FROM user_fcm_tokens"
echo "    WHERE token IN ('$TOKEN_ANDROID', '$TOKEN_IOS');"
exit 0
