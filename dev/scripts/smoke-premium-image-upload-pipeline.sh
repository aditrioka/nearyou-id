#!/usr/bin/env bash
# Smoke test for `premium-image-upload-pipeline` — verifies the deploy is safe while
# the feature is flag-dark (the Month-6 launch hasn't flipped image_upload_enabled and
# Cloudflare Images / Vision are unprovisioned): POST /api/v1/images returns HTTP 403
# with error.code = "image_upload_disabled" — NOT a 500/crash — and the app booted with
# the two new :infra:* modules (a control route still serves, proving the Dockerfile
# COPY list is correct and the deploy didn't silently break — the PR #247 precedent).
#
# Per `openspec/changes/premium-image-upload-pipeline/tasks.md` Section 11 + the
# pre-archive smoke convention (`openspec/project.md` § Staging deploy timing).
#
# Why 403 (not 503): the flag gate is FIRST in the validation chain (design D9), so a
# default-FALSE flag short-circuits to 403 before any premium / quota / Vision / Cloudflare
# work — independent of caller tier and of whether the infra secrets are provisioned. A 503
# would mean the flag is ON but infra is unconfigured (a different, post-launch state); a
# 500 means a real crash (smoke fails).
#
# Usage:
#   dev/scripts/smoke-premium-image-upload-pipeline.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     <user-uuid>
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification.
#
# Prerequisites:
#  - The user UUID points to an existing user in the target environment (any tier — the
#    flag gate fires before the premium gate).
#  - The script mints a JWT via `dev/scripts/mint-dev-jwt.sh`. For staging:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#        dev/scripts/smoke-premium-image-upload-pipeline.sh <user-uuid>
#
# Exit codes:
#   0 — smoke passed: POST /api/v1/images → 403 image_upload_disabled; control route 200.
#   1 — smoke failed: an assertion mismatched (e.g., a 500 crash, or wrong error code).
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

echo "==> Smoke premium-image-upload-pipeline (flag-dark deploy safety)"
echo "    API base:  $API_BASE"
echo "    User UUID: $USER_UUID"
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

# ----------------------------------------------------------------------------
# 2. Control: the app booted with the new :infra:* modules → /health/ready is 200.
#    A failed deploy from a missing Dockerfile COPY would not serve this at all.
# ----------------------------------------------------------------------------
echo "==> Control — GET /health/ready (app booted with the new modules)..."
READY_CODE="$(curl -s -o /dev/null -w '%{http_code}' "$API_BASE/health/ready")"
if [[ "$READY_CODE" != "200" ]]; then
    echo "ERROR: /health/ready returned $READY_CODE (expected 200) — deploy may be broken." >&2
    exit 1
fi
echo "    /health/ready → 200 ✓"
echo

# ----------------------------------------------------------------------------
# 3. POST /api/v1/images with the flag dark → expect 403 image_upload_disabled.
#    A tiny multipart file; the flag gate fires before the body is read.
# ----------------------------------------------------------------------------
echo "==> POST /api/v1/images (expect 403 image_upload_disabled, flag dark)..."
TMP_IMG="$(mktemp -t smoke-img-XXXXXX.png)"
trap 'rm -f "$TMP_IMG"' EXIT
printf '\x89PNG\r\n\x1a\n' > "$TMP_IMG" # minimal PNG signature; never reaches Safe Search

RESP="$(curl -s -w '\n%{http_code}' \
    -H "Authorization: Bearer $JWT" \
    -F "file=@$TMP_IMG;type=image/png" \
    "$API_BASE/api/v1/images")"
CODE="$(echo "$RESP" | tail -n1)"
BODY="$(echo "$RESP" | sed '$d')"

echo "    HTTP $CODE"
echo "    body: $BODY"

if [[ "$CODE" == "500" ]]; then
    echo "FAIL: POST /api/v1/images returned 500 — the endpoint crashed." >&2
    exit 1
fi
if [[ "$CODE" != "403" ]]; then
    echo "FAIL: expected 403 (flag-dark short-circuit); got $CODE." >&2
    echo "      (503 would mean the flag is ON but infra unconfigured — not the flag-dark state.)" >&2
    exit 1
fi
ERR_CODE="$(echo "$BODY" | jq -r '.error.code // empty' 2>/dev/null || true)"
if [[ "$ERR_CODE" != "image_upload_disabled" ]]; then
    echo "FAIL: 403 body error.code was '$ERR_CODE' (expected 'image_upload_disabled')." >&2
    exit 1
fi
echo "    403 image_upload_disabled ✓"
echo
echo "==> SMOKE PASSED — flag-dark deploy is safe (403 not 500; app booted)."
