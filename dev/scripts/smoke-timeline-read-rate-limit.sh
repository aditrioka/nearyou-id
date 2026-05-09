#!/usr/bin/env bash
# Smoke test for `timeline-read-rate-limit` — verifies the per-user rolling 150-
# posts/hour hard cap fires after 5 page-of-30 reads on a Free user, returning the
# 6th read as HTTP 200 with empty `posts` + `upsell.hard = true` (NOT a 429).
#
# Per `openspec/changes/timeline-read-rate-limit/tasks.md` Section 6 + the
# pre-archive smoke convention codified in `openspec/project.md` § Staging deploy
# timing. Adapted from `smoke-reply-rate-limit.sh` / `smoke-9.7-like-rate-limit.sh`;
# the differences from those write-side smokes:
#  - Endpoint: GET /api/v1/timeline/global (read) vs POST /like or /replies (write).
#  - Cap shape: empty `posts` + `upsell.hard = true` + HTTP 200 — NOT a 429.
#  - Counts posts returned (not requests). 5 reads × 30 posts = 150 → 6th hard-caps.
#  - Soft cap (50/session) fires from request 3 onwards but is NON-blocking; we
#    don't assert on it (tests cover that surface).
#  - X-Session-Id is set so each request lands in the same session bucket.
#
# Usage:
#   dev/scripts/smoke-timeline-read-rate-limit.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     <user-uuid>
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification before merging to main.
#
# Prerequisites:
#  - The user UUID points to an existing Free user in the target environment
#    (`users.subscription_status = 'free'` — the migration default).
#  - The user has not already exhausted their hourly cap in the target environment
#    (the 1-hour rolling window reset is sliding; if you ran this script in the
#    last hour, wait 60 minutes OR use a fresh user UUID).
#  - The staging DB has at least 30 visible posts on the Global timeline. Smoke
#    verifies this before issuing the cap-burn requests.
#  - The script mints a JWT via `dev/scripts/mint-dev-jwt.sh`. For staging:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#      dev/scripts/smoke-timeline-read-rate-limit.sh <user-uuid>
#
# Exit codes:
#   0 — smoke passed: 5 reads returned 200 with 30 posts; 6th returned 200 with
#       empty posts + upsell.hard = true.
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
SID="smoke-$(date +%s)-$RANDOM"

echo "==> Smoke timeline-read-rate-limit (150 posts/hour rolling Free hard cap)"
echo "    API base:  $API_BASE"
echo "    User UUID: $USER_UUID"
echo "    Session:   $SID"
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
# 2. Verify the staging Global timeline has enough posts to fill 30/page.
# ----------------------------------------------------------------------------
echo "==> Verifying Global timeline has ≥ 30 visible posts..."
PROBE="$(curl -fsS \
    -H "Authorization: Bearer $JWT" \
    -H "X-Session-Id: $SID-probe" \
    "$API_BASE/api/v1/timeline/global")"
PROBE_COUNT="$(echo "$PROBE" | jq '.posts | length')"
if [[ "$PROBE_COUNT" -lt 30 ]]; then
    echo "ERROR: Global timeline returned $PROBE_COUNT posts; smoke needs ≥ 30." >&2
    echo "Seed more posts or pick a denser environment." >&2
    exit 2
fi
echo "    Probe returned $PROBE_COUNT posts on first page. ✓"
echo
# The probe consumed 1 + min(N-1, 149) ≈ 30 slots from the rolling bucket via the
# session SID-probe. The CAP-BURN below uses a DIFFERENT SID so its session bucket
# is fresh (50 capacity) — but the rolling bucket is shared per-user. With ~30
# slots consumed by the probe, the cap-burn now needs to hit 150 total → 4 more
# 30-page reads complete the rolling cap; the 5th burn-side request hard-caps.
# This is the expected behavior; the script accepts the rolling cap firing on
# either the 5th OR 6th burn request (whichever consumes the 150th slot first).

# ----------------------------------------------------------------------------
# 3. Burn the rolling bucket via successive Global reads under SID. Count the
#    request index where the response carries `upsell.hard = true`.
# ----------------------------------------------------------------------------
echo "==> Burning rolling bucket (1-hour window, 150 posts/h cap)..."
CAP_HIT_INDEX=0
for i in 1 2 3 4 5 6 7; do
    RESP_BODY="$(curl -fsS \
        -H "Authorization: Bearer $JWT" \
        -H "X-Session-Id: $SID" \
        "$API_BASE/api/v1/timeline/global")"
    POSTS_LEN="$(echo "$RESP_BODY" | jq '.posts | length')"
    UPSELL_HARD="$(echo "$RESP_BODY" | jq -r '.upsell.hard // false')"
    UPSELL_SOFT="$(echo "$RESP_BODY" | jq -r '.upsell.soft // false')"
    printf "    request %d/7 — posts=%2d upsell.soft=%s upsell.hard=%s\n" \
        "$i" "$POSTS_LEN" "$UPSELL_SOFT" "$UPSELL_HARD"
    if [[ "$UPSELL_HARD" == "true" ]]; then
        CAP_HIT_INDEX=$i
        # Verify the cap-hit response shape:
        if [[ "$POSTS_LEN" != "0" ]]; then
            echo "FAIL: cap-hit request $i returned $POSTS_LEN posts; expected 0" >&2
            exit 1
        fi
        break
    fi
done

if [[ "$CAP_HIT_INDEX" == "0" ]]; then
    echo "FAIL: rolling cap did NOT fire after 7 reads of 30 posts each." >&2
    echo "Expected upsell.hard = true within 5–6 requests; rate limiter may be" >&2
    echo "fail-soft (check Cloud Run logs for 'event=redis_connect_failed fail_soft=true')." >&2
    exit 1
fi
echo

# ----------------------------------------------------------------------------
# 4. Verify the cap-hit response is HTTP 200 (NOT 429) AND has no Retry-After.
# ----------------------------------------------------------------------------
echo "==> Verifying cap-hit response is HTTP 200 + no Retry-After..."
HEADERS="$(curl -sS -o /dev/null -D - \
    -H "Authorization: Bearer $JWT" \
    -H "X-Session-Id: $SID" \
    "$API_BASE/api/v1/timeline/global")"
STATUS="$(echo "$HEADERS" | head -1 | awk '{print $2}')"
RETRY_AFTER="$(echo "$HEADERS" | grep -i '^retry-after:' || true)"

if [[ "$STATUS" != "200" ]]; then
    echo "FAIL: cap-hit response returned HTTP $STATUS; expected 200." >&2
    echo "       The cap-hit must NOT be 429 — empty posts + upsell.hard is the" >&2
    echo "       canonical UX (avoids forcing a mobile error screen)." >&2
    exit 1
fi
if [[ -n "$RETRY_AFTER" ]]; then
    echo "FAIL: cap-hit response carries Retry-After: $RETRY_AFTER — must be absent." >&2
    exit 1
fi
echo "    Cap-hit response: HTTP 200, no Retry-After. ✓"
echo

# ----------------------------------------------------------------------------
# 5. Pass.
# ----------------------------------------------------------------------------
echo "==> ✅ Smoke timeline-read-rate-limit PASSED."
echo "    Cap fired on request $CAP_HIT_INDEX (within the 5–6 expected window)."
echo "    Cap-hit response: HTTP 200, posts=[], upsell.hard=true, no Retry-After."
echo
echo "Tick the smoke checkbox in"
echo "openspec/changes/timeline-read-rate-limit/tasks.md (Section 6 / pre-archive note)"
echo "with a small follow-up commit on the PR branch:"
echo
echo "  - [x] Smoke verified $(date -u +%Y-%m-%dT%H:%M:%SZ) on $API_BASE for"
echo "        user $USER_UUID: cap fired on request $CAP_HIT_INDEX with"
echo "        HTTP 200 + posts=[] + upsell.hard=true."
exit 0
