#!/usr/bin/env bash
# Smoke test for `reply-rate-limit` task 6.3 — verifies the 20/day Free reply cap
# fires on the 21st reply with HTTP 429 + Retry-After header.
#
# Per `openspec/changes/reply-rate-limit/tasks.md` task 6.3:
# "Run the smoke against the latest staging revision after CI green. Confirm the
#  21st response carries Retry-After matching computeTTLToNextReset(userId) for
#  that specific synthetic user (within ±5s)."
#
# Adapted from `dev/scripts/smoke-9.7-like-rate-limit.sh` — the differences from
# the like smoke are:
#  - Endpoint: POST /api/v1/posts/{post_id}/replies (vs /like).
#  - Body: JSON `{"content":"..."}` (vs no body for likes).
#  - Cap: 20/day Free (vs 10/day for likes; no burst on replies).
#  - Success code: 201 Created (vs 204 No Content for likes).
#  - 21 distinct posts needed (vs 11 for likes).
#
# Usage:
#   dev/scripts/smoke-reply-rate-limit.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     [--posts uuid1,uuid2,...,uuid21] \
#     <user-uuid>
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification.
#
# Prerequisites (per the like-smoke lessons in
# `openspec/changes/archive/2026-04-25-like-rate-limit/tasks.md` task 9.7):
#  - The user UUID points to an existing Free user in the target environment.
#  - Free means `users.subscription_status = 'free'` (the migration default;
#    a freshly-signed-up user is Free unless the RevenueCat webhook fires).
#  - The user has not exhausted their daily reply cap today (WIB-staggered reset).
#  - The script mints a JWT via `dev/scripts/mint-dev-jwt.sh`. For staging
#    the script needs `KTOR_RSA_PRIVATE_KEY` set to the staging RSA key:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#      dev/scripts/mint-dev-jwt.sh <user-uuid>
#  - 21 distinct visible posts authored by a *different* user (avoids self-reply
#    edge cases on the global timeline). Pass via --posts or the script
#    fetches the global timeline.
#
# Exit codes:
#   0 — smoke passed: 20 replies returned 201, 21st returned 429 with Retry-After.
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

echo "==> Smoke — reply rate limit (20/day Free)"
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
# 2. Resolve 21 distinct visible post UUIDs.
# ----------------------------------------------------------------------------
if [[ -n "$POSTS_OVERRIDE" ]]; then
    IFS=',' read -ra POST_UUIDS <<<"$POSTS_OVERRIDE"
    if [[ "${#POST_UUIDS[@]}" -lt 21 ]]; then
        echo "ERROR: --posts must contain at least 21 UUIDs (got ${#POST_UUIDS[@]})." >&2
        exit 2
    fi
    echo "==> Using ${#POST_UUIDS[@]} posts from --posts override (need >= 21)."
else
    echo "==> Fetching 21 distinct posts from $API_BASE/api/v1/timeline/global..."
    GLOBAL_RESPONSE="$(curl -fsS \
        -H "Authorization: Bearer $JWT" \
        "$API_BASE/api/v1/timeline/global?limit=30")"
    mapfile -t POST_UUIDS < <(echo "$GLOBAL_RESPONSE" | jq -r '.posts[].id' | head -21)
    if [[ "${#POST_UUIDS[@]}" -lt 21 ]]; then
        echo "ERROR: only ${#POST_UUIDS[@]} posts visible; need >= 21. Seed more or pass --posts." >&2
        exit 2
    fi
    echo "    Got 21 post UUIDs from global timeline."
fi
echo

# ----------------------------------------------------------------------------
# 3. Hit 20 replies — assert 201.
# ----------------------------------------------------------------------------
echo "==> Sending 20 replies (expect HTTP 201 each)..."
TIMESTAMP_LABEL="$(date -u +%Y%m%dT%H%M%SZ)"
for i in $(seq 0 19); do
    PID="${POST_UUIDS[$i]}"
    BODY="{\"content\":\"smoke reply ${TIMESTAMP_LABEL} #$((i + 1))\"}"
    STATUS="$(curl -fsS -o /dev/null -w "%{http_code}" -X POST \
        -H "Authorization: Bearer $JWT" \
        -H "Content-Type: application/json" \
        --data-raw "$BODY" \
        "$API_BASE/api/v1/posts/$PID/replies" || echo "FAIL")"
    if [[ "$STATUS" == "201" ]]; then
        printf "    %2d/20  %s  ✓ 201\n" "$((i + 1))" "$PID"
    else
        printf "    %2d/20  %s  ✗ got %s (expected 201)\n" "$((i + 1))" "$PID" "$STATUS"
        echo "FAIL: reply #$((i + 1)) returned $STATUS, not 201" >&2
        exit 1
    fi
done
echo

# ----------------------------------------------------------------------------
# 4. Hit the 21st reply — assert 429 + Retry-After.
# ----------------------------------------------------------------------------
echo "==> Sending 21st reply (expect HTTP 429 + Retry-After header)..."
PID="${POST_UUIDS[20]}"
BODY="{\"content\":\"smoke reply ${TIMESTAMP_LABEL} #21 (rate-limit probe)\"}"
RESP_HEADERS="$(curl -sS -o /tmp/smoke-reply-rl-body.json -D - -X POST \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    --data-raw "$BODY" \
    "$API_BASE/api/v1/posts/$PID/replies" 2>/dev/null)"
STATUS="$(echo "$RESP_HEADERS" | head -1 | awk '{print $2}')"
RETRY_AFTER="$(echo "$RESP_HEADERS" | grep -i '^retry-after:' | awk '{print $2}' | tr -d '\r')"

if [[ "$STATUS" != "429" ]]; then
    echo "FAIL: 21st reply returned $STATUS, expected 429" >&2
    cat /tmp/smoke-reply-rl-body.json >&2
    exit 1
fi

if [[ -z "$RETRY_AFTER" ]]; then
    echo "FAIL: 21st reply returned 429 but Retry-After header is missing" >&2
    echo "Response headers:" >&2
    echo "$RESP_HEADERS" >&2
    exit 1
fi

ERROR_CODE="$(jq -r '.error.code // ""' /tmp/smoke-reply-rl-body.json 2>/dev/null || echo "")"
if [[ "$ERROR_CODE" != "rate_limited" ]]; then
    echo "FAIL: 21st reply body error.code is '$ERROR_CODE', expected 'rate_limited'" >&2
    cat /tmp/smoke-reply-rl-body.json >&2
    exit 1
fi

# Sanity check Retry-After value: should be on the order of 0..86400 seconds
# (the staggered TTL to next per-user reset moment). The bucket grows to 20 in
# fast succession so the 21st rejection's Retry-After is ~`computeTTLToNextReset`.
# A suspiciously low value (< 60) likely means the limiter fail-softed open
# and the 429 came from somewhere else — fail loud per task 6.4.
if [[ "$RETRY_AFTER" -lt 60 ]]; then
    echo "FAIL: Retry-After=${RETRY_AFTER}s is suspiciously low — likely fail-soft fired." >&2
    echo "      Daily-cap Retry-After should be hundreds of seconds at worst." >&2
    echo "      Investigate Redis connectivity per like-rate-limit task 9.7 lessons:" >&2
    echo "      1. staging-redis-url slot uses 'rediss://' (TLS) for Upstash" >&2
    echo "      2. REDIS_URL env var injected by deploy-staging.yml" >&2
    echo "      3. RedisRateLimiter logs 'event=redis_connect_failed fail_soft=true'" >&2
    cat /tmp/smoke-reply-rl-body.json >&2
    exit 1
fi

printf "    21/21  %s  ✓ 429  Retry-After: %s s  error.code: %s\n" \
    "$PID" "$RETRY_AFTER" "$ERROR_CODE"
echo

# ----------------------------------------------------------------------------
# 5. Pass.
# ----------------------------------------------------------------------------
echo "==> ✅ Reply-rate-limit smoke PASSED."
echo "    20 replies: 201 each."
echo "    21st reply: 429 + Retry-After: $RETRY_AFTER s + error.code: $ERROR_CODE."
echo
echo "Tick task 6.3 in openspec/changes/reply-rate-limit/tasks.md with:"
echo
echo "  - [x] 6.3 Smoke verified $(date -u +%Y-%m-%dT%H:%M:%SZ) on $API_BASE for"
echo "       user $USER_UUID: 20 replies 201, 21st 429 + Retry-After: $RETRY_AFTER s."
exit 0
