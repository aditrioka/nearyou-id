#!/usr/bin/env bash
# Smoke test for `mobile-analytics-consent-screen` (backend half: the
# `analytics-consent-update` capability) — verifies `PATCH /api/v1/user/consent`
# on a real deploy:
#   1. Authed PATCH with the full {analytics, crash, ads_personalization} triple
#      → HTTP 200 echoing the stored triple (proves the route is wired + the
#      own-row UPDATE executed without error).
#   2. PATCH with NO Authorization → HTTP 401 (auth gate enforced).
#   3. Authed PATCH with a missing key → HTTP 400 (non-nullable DTO validation).
#
# Per `openspec/changes/mobile-analytics-consent-screen/tasks.md` Section 11 + the
# pre-archive smoke convention codified in `openspec/project.md` § Staging deploy
# timing. Adapted from `smoke-fcm-token-registration.sh` (the sibling authed-self
# `/api/v1/user/*` route). This change adds NO new secret slot, NO migration, and
# NO new DB connection (it reuses the existing dataSource + user JWT auth), so the
# deploy-config risk this smoke guards is low — it confirms the new route is wired
# and the authed PATCH round-trips on the target environment.
#
# Usage:
#   dev/scripts/smoke-mobile-analytics-consent-screen.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     <user-uuid>
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification.
#
# Prerequisites:
#  - The user UUID points to an existing user in the target environment.
#  - The script mints a JWT via `dev/scripts/mint-dev-jwt.sh`. For staging:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#      dev/scripts/smoke-mobile-analytics-consent-screen.sh <user-uuid>
#
# Exit codes:
#   0 — smoke passed: authed PATCH → 200 + echo; unauth → 401; missing key → 400.
#   1 — smoke failed: at least one assertion mismatched.
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
URL="$API_BASE/api/v1/user/consent"
FAIL=0

echo "==> Smoke mobile-analytics-consent-screen (PATCH /api/v1/user/consent)"
echo "    API base:  $API_BASE"
echo "    User UUID: $USER_UUID"
echo

echo "==> Minting JWT for user $USER_UUID..."
JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$USER_UUID")"
if [[ -z "$JWT" ]]; then
    echo "ERROR: JWT mint failed — empty token returned. Check KTOR_RSA_PRIVATE_KEY." >&2
    exit 2
fi
echo "    JWT minted (${#JWT} chars)."
echo

# ----------------------------------------------------------------------------
# 1. Authed PATCH with the full triple → 200 + echo.
# ----------------------------------------------------------------------------
echo "==> 1. Authed PATCH {analytics:true, crash:false, ads_personalization:true} → expect 200 + echo"
RESP="$(curl -sS -X PATCH \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"analytics":true,"crash":false,"ads_personalization":true}' \
    -w $'\n%{http_code}' "$URL")"
BODY="$(echo "$RESP" | sed '$d')"
CODE="$(echo "$RESP" | tail -1)"
echo "    HTTP $CODE — body: $BODY"
if [[ "$CODE" != "200" ]]; then
    echo "    FAIL: expected 200, got $CODE" >&2
    FAIL=1
else
    A="$(echo "$BODY" | jq -r '.analytics')"
    C="$(echo "$BODY" | jq -r '.crash')"
    D="$(echo "$BODY" | jq -r '.ads_personalization')"
    if [[ "$A" == "true" && "$C" == "false" && "$D" == "true" ]]; then
        echo "    PASS: 200 + echoed {analytics:true, crash:false, ads_personalization:true} ✓"
    else
        echo "    FAIL: echo mismatch (analytics=$A crash=$C ads=$D)" >&2
        FAIL=1
    fi
fi
echo

# ----------------------------------------------------------------------------
# 2. Unauthenticated PATCH → 401.
# ----------------------------------------------------------------------------
echo "==> 2. PATCH with NO Authorization → expect 401"
CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH \
    -H "Content-Type: application/json" \
    -d '{"analytics":true,"crash":true,"ads_personalization":true}' "$URL")"
echo "    HTTP $CODE"
if [[ "$CODE" == "401" ]]; then
    echo "    PASS: unauthenticated request rejected with 401 ✓"
else
    echo "    FAIL: expected 401, got $CODE" >&2
    FAIL=1
fi
echo

# ----------------------------------------------------------------------------
# 3. Malformed body (missing a key) → 400.
# ----------------------------------------------------------------------------
echo "==> 3. Authed PATCH with a missing key → expect 400"
CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"analytics":true,"crash":false}' "$URL")"
echo "    HTTP $CODE"
if [[ "$CODE" == "400" ]]; then
    echo "    PASS: missing-key body rejected with 400 ✓"
else
    echo "    FAIL: expected 400, got $CODE" >&2
    FAIL=1
fi
echo

if [[ "$FAIL" -eq 0 ]]; then
    echo "==> SMOKE PASSED ✓"
    exit 0
else
    echo "==> SMOKE FAILED ✗" >&2
    exit 1
fi
