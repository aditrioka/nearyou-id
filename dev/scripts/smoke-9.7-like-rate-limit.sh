#!/usr/bin/env bash
# Smoke test for `like-rate-limit` task 9.7 — verifies the 10/day Free cap
# fires on the 11th like with HTTP 429 + Retry-After header.
#
# Per `openspec/changes/archive/2026-04-25-like-rate-limit/tasks.md` task 9.7:
# "Wait for staging deploy green; smoke test: hit 10 likes from a Free synthetic
#  account, expect 11th to return 429 + Retry-After."
#
# Usage:
#   dev/scripts/smoke-9.7-like-rate-limit.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     <user-uuid>
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification before merging to main.
#
# Prerequisites:
#   - The user UUID points to an existing Free user in the target environment.
#     Free means `users.subscription_status = 'free'` (the migration default,
#     so a freshly-signed-up user is Free unless the RevenueCat webhook has
#     fired).
#   - The user has not already exhausted their daily cap in the target
#     environment for the day this runs (the WIB-staggered reset means the
#     bucket clears at the user-specific reset moment; if you ran this
#     script earlier today against the same user, give them a fresh user
#     UUID or wait until tomorrow's reset).
#   - The script mints a JWT via `dev/scripts/mint-dev-jwt.sh`. For staging
#     that script needs `KTOR_RSA_PRIVATE_KEY` set to the staging RSA key
#     (fetch via `gcloud secrets versions access latest
#       --secret=staging-ktor-rsa-private-key --project=nearyou-staging`).
#   - The script needs a small helper to fetch 11 distinct visible post
#     UUIDs from the global timeline. If the staging DB has fewer than 11
#     posts, seed more first or override with --posts.
#
# Override the post list explicitly:
#   dev/scripts/smoke-9.7-like-rate-limit.sh \
#     --posts "uuid1,uuid2,...,uuid11" \
#     <user-uuid>
#
# Exit codes:
#   0 — smoke passed: 10 likes returned 204, 11th returned 429 with Retry-After.
#   1 — smoke failed: at least one assertion mismatched.
#   2 — usage error (missing args, JWT mint failure, etc.).

set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
POSTS_OVERRIDE=""

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base)
            API_BASE="$2"
            shift 2
            ;;
        --posts)
            POSTS_OVERRIDE="$2"
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
    echo "usage: $0 [--api-base <url>] [--posts <comma-separated-uuids>] <user-uuid>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

echo "==> Smoke 9.7 — like rate limit (10/day Free + 500/hour burst)"
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
# 2. Resolve 11 distinct visible post UUIDs.
# ----------------------------------------------------------------------------
if [[ -n "$POSTS_OVERRIDE" ]]; then
    IFS=',' read -ra POST_UUIDS <<<"$POSTS_OVERRIDE"
    if [[ "${#POST_UUIDS[@]}" -lt 11 ]]; then
        echo "ERROR: --posts must contain at least 11 UUIDs (got ${#POST_UUIDS[@]})." >&2
        exit 2
    fi
    echo "==> Using ${#POST_UUIDS[@]} posts from --posts override (need >= 11)."
else
    echo "==> Fetching 11 distinct posts from $API_BASE/api/v1/timeline/global..."
    GLOBAL_RESPONSE="$(curl -fsS \
        -H "Authorization: Bearer $JWT" \
        "$API_BASE/api/v1/timeline/global?limit=20")"
    # Parse post IDs (assumes the timeline DTO has `id` at the root of each
    # entry; adjust the jq filter if the production response shape differs).
    mapfile -t POST_UUIDS < <(echo "$GLOBAL_RESPONSE" | jq -r '.posts[].id' | head -11)
    if [[ "${#POST_UUIDS[@]}" -lt 11 ]]; then
        echo "ERROR: only ${#POST_UUIDS[@]} posts visible; need >= 11. Seed more or pass --posts." >&2
        exit 2
    fi
    echo "    Got 11 post UUIDs from global timeline."
fi
echo

# ----------------------------------------------------------------------------
# 3. Hit 10 likes — assert 204.
# ----------------------------------------------------------------------------
echo "==> Sending 10 likes (expect HTTP 204 each)..."
for i in 0 1 2 3 4 5 6 7 8 9; do
    PID="${POST_UUIDS[$i]}"
    STATUS="$(curl -fsS -o /dev/null -w "%{http_code}" -X POST \
        -H "Authorization: Bearer $JWT" \
        "$API_BASE/api/v1/posts/$PID/like" || echo "FAIL")"
    if [[ "$STATUS" == "204" ]]; then
        printf "    %2d/10  %s  ✓ 204\n" "$((i + 1))" "$PID"
    else
        printf "    %2d/10  %s  ✗ got %s (expected 204)\n" "$((i + 1))" "$PID" "$STATUS"
        echo "FAIL: like #$((i + 1)) returned $STATUS, not 204" >&2
        exit 1
    fi
done
echo

# ----------------------------------------------------------------------------
# 4. Hit the 11th like — assert 429 + Retry-After.
# ----------------------------------------------------------------------------
echo "==> Sending 11th like (expect HTTP 429 + Retry-After header)..."
PID="${POST_UUIDS[10]}"
RESP_HEADERS="$(curl -sS -o /tmp/smoke-9.7-body.json -D - -X POST \
    -H "Authorization: Bearer $JWT" \
    "$API_BASE/api/v1/posts/$PID/like" 2>/dev/null)"
STATUS="$(echo "$RESP_HEADERS" | head -1 | awk '{print $2}')"
RETRY_AFTER="$(echo "$RESP_HEADERS" | grep -i '^retry-after:' | awk '{print $2}' | tr -d '\r')"

if [[ "$STATUS" != "429" ]]; then
    echo "FAIL: 11th like returned $STATUS, expected 429" >&2
    cat /tmp/smoke-9.7-body.json >&2
    exit 1
fi

if [[ -z "$RETRY_AFTER" ]]; then
    echo "FAIL: 11th like returned 429 but Retry-After header is missing" >&2
    echo "Response headers:" >&2
    echo "$RESP_HEADERS" >&2
    exit 1
fi

# Verify response body has error.code = "rate_limited"
ERROR_CODE="$(jq -r '.error.code // ""' /tmp/smoke-9.7-body.json 2>/dev/null || echo "")"
if [[ "$ERROR_CODE" != "rate_limited" ]]; then
    echo "FAIL: 11th like body error.code is '$ERROR_CODE', expected 'rate_limited'" >&2
    cat /tmp/smoke-9.7-body.json >&2
    exit 1
fi

printf "    11/11  %s  ✓ 429  Retry-After: %s s  error.code: %s\n" \
    "$PID" "$RETRY_AFTER" "$ERROR_CODE"
echo

# ----------------------------------------------------------------------------
# 5. Pass.
# ----------------------------------------------------------------------------
echo "==> ✅ Smoke 9.7 PASSED."
echo "    10 likes: 204 each."
echo "    11th like: 429 + Retry-After: $RETRY_AFTER s + error.code: $ERROR_CODE."
echo
echo "Tick 9.7 in openspec/changes/archive/2026-04-25-like-rate-limit/tasks.md"
echo "with a small follow-up commit on main:"
echo
echo "  - [x] 9.7 Smoke verified $(date -u +%Y-%m-%dT%H:%M:%SZ) on $API_BASE for"
echo "       user $USER_UUID: 10 likes 204, 11th 429 + Retry-After: $RETRY_AFTER s."
exit 0
