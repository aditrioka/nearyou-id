#!/usr/bin/env bash
# Smoke test for `premium-search` — verifies the staging deploy of the new
# `GET /api/v1/search` endpoint behaves correctly for the Premium happy path,
# the length guard, the offset bound, and the 60/hour rate limit.
#
# Per `openspec/changes/premium-search/tasks.md` § 8: pre-archive smoke against
# a manual branch staging deploy (NOT post-merge auto-deploy from main). The
# staging deploy is triggered separately:
#
#   gh workflow run deploy-staging.yml --ref premium-search
#
# Then this script exercises the deployed surface against the dedicated test
# user.
#
# Why this scope (and what's NOT smoked):
#  - Premium happy path (200 + result shape) — confirms FTS query, V13
#    migration, GIN indexes, visible_posts view refresh, and the canonical
#    SET LOCAL pg_trgm.similarity_threshold all work end-to-end.
#  - Length guard (`q=` → 400) — confirms the NFKC-trim path is wired.
#  - Offset bound (`offset=10001` → 400) — confirms route-layer DoS guard.
#  - Rate limit (60 → 200, 61 → 429 + Retry-After) — confirms Redis wiring,
#    SearchRateLimiter key form, and the canonical RateLimiter.Outcome
#    plumbing. Mirrors the like-rate-limit lesson (PR #43/44/47): the smoke
#    catches Redis-secret-slot drift + TLS scheme + lazy-connect bugs that
#    only surface in the deployed stack.
#  - NOT covered (validated by integration tests, no deploy-config surface):
#    Free → 403, guest → 401, kill switch → 503, block exclusion, privacy
#    gate, NFKC, case-fold matching.
#
# Adapted from `dev/scripts/smoke-reply-rate-limit.sh` — primary differences:
#  - Endpoint: GET /api/v1/search (vs POST /replies)
#  - User tier: Premium required (vs Free for replies)
#  - Cap: 60/hour (vs 20/day)
#  - Success code: 200 OK (vs 201 Created)
#  - Body: query string parameters (vs JSON body)
#  - No content-creation side effects — read-only, idempotent.
#
# Usage:
#   dev/scripts/smoke-premium-search.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     [--query jakarta] \
#     <premium-user-uuid>
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification.
#
# Prerequisites (per the like-smoke lessons in
# `openspec/changes/archive/2026-04-25-like-rate-limit/tasks.md` task 9.7):
#  - The user UUID points to an existing user in the target environment whose
#    `users.subscription_status IN ('premium_active', 'premium_billing_retry')`.
#    A freshly-signed-up user is `'free'` by default — they must be promoted
#    out-of-band (e.g., `psql ... -c "UPDATE users SET subscription_status =
#    'premium_active' WHERE id = '<uuid>'"` against staging Postgres) before
#    the smoke can run. Document the staging Premium test-user UUID in
#    `dev/README.md` if not already done.
#  - The user has not exhausted their hourly search cap (the smoke fires 61
#    queries; if the bucket already has hits, the 429 trip count moves).
#  - JWT is minted via `dev/scripts/mint-dev-jwt.sh`. For staging the script
#    needs `KTOR_RSA_PRIVATE_KEY` set to the staging RSA key:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#        dev/scripts/smoke-premium-search.sh <user-uuid>
#  - The query (`--query jakarta` by default) matches at least one post in the
#    target environment. If the staging corpus is empty, the happy-path
#    assertion `results.length >= 0` will trivially pass; that's a feature
#    (smoke is about deploy-config, not corpus correctness).
#
# Exit codes:
#   0 — smoke passed: 60 OKs + 61st 429 + Retry-After present + length-guard
#       400 + offset-bound 400.
#   1 — smoke failed: at least one assertion mismatched.
#   2 — usage error (missing args, JWT mint failure, etc.).

set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
QUERY="jakarta"

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base)
            API_BASE="$2"
            shift 2
            ;;
        --query)
            QUERY="$2"
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
    echo "usage: $0 [--api-base <url>] [--query <q>] <premium-user-uuid>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

echo "==> Smoke — premium-search (60/hour Premium)"
echo "    API base:  $API_BASE"
echo "    User UUID: $USER_UUID"
echo "    Query:     $QUERY"
echo

# ----------------------------------------------------------------------------
# 1. Mint a JWT for the Premium user.
# ----------------------------------------------------------------------------
echo "==> Minting JWT for user $USER_UUID..."
JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$USER_UUID")"
if [[ -z "$JWT" ]]; then
    echo "ERROR: JWT mint failed — empty token returned. Check KTOR_RSA_PRIVATE_KEY." >&2
    exit 2
fi
echo "    JWT minted (${#JWT} chars)."
echo

# Helper: GET /api/v1/search and capture status + (optionally) body.
search_get() {
    local q="$1"
    local offset_param="${2:-}"
    local out_body="${3:-/dev/null}"
    local query_string="?q=$(printf '%s' "$q" | jq -sRr @uri)"
    if [[ -n "$offset_param" ]]; then
        query_string="$query_string&offset=$offset_param"
    fi
    curl -sS -o "$out_body" -w "%{http_code}" \
        -H "Authorization: Bearer $JWT" \
        "$API_BASE/api/v1/search$query_string"
}

# ----------------------------------------------------------------------------
# 2. Length-guard: q='' → 400 invalid_query_length. Runs FIRST because it
#    short-circuits before the rate limit, so we don't burn a slot.
# ----------------------------------------------------------------------------
echo "==> Length guard: q='' → expect 400 invalid_query_length..."
STATUS="$(search_get "" "" /tmp/smoke-search-empty.json)"
if [[ "$STATUS" != "400" ]]; then
    echo "FAIL: empty query returned $STATUS, expected 400" >&2
    cat /tmp/smoke-search-empty.json >&2
    exit 1
fi
ERR="$(jq -r '.error' /tmp/smoke-search-empty.json 2>/dev/null || echo "")"
if [[ "$ERR" != "invalid_query_length" ]]; then
    echo "FAIL: empty query body error=$ERR, expected invalid_query_length" >&2
    exit 1
fi
echo "    ✓ 400 invalid_query_length"
echo

# ----------------------------------------------------------------------------
# 3. Offset bound: offset=10001 → 400 invalid_offset.
# ----------------------------------------------------------------------------
echo "==> Offset bound: offset=10001 → expect 400 invalid_offset..."
STATUS="$(search_get "$QUERY" "10001" /tmp/smoke-search-offset.json)"
if [[ "$STATUS" != "400" ]]; then
    echo "FAIL: offset=10001 returned $STATUS, expected 400" >&2
    cat /tmp/smoke-search-offset.json >&2
    exit 1
fi
ERR="$(jq -r '.error' /tmp/smoke-search-offset.json 2>/dev/null || echo "")"
if [[ "$ERR" != "invalid_offset" ]]; then
    echo "FAIL: offset=10001 body error=$ERR, expected invalid_offset" >&2
    exit 1
fi
echo "    ✓ 400 invalid_offset"
echo

# ----------------------------------------------------------------------------
# 4. Premium happy path — single query expects 200 with `results` + `next_offset` keys.
# ----------------------------------------------------------------------------
echo "==> Premium happy path: q=$QUERY → expect 200 OK..."
STATUS="$(search_get "$QUERY" "" /tmp/smoke-search-happy.json)"
if [[ "$STATUS" != "200" ]]; then
    echo "FAIL: happy path returned $STATUS, expected 200" >&2
    cat /tmp/smoke-search-happy.json >&2
    exit 1
fi
RESULTS_TYPE="$(jq -r '.results | type' /tmp/smoke-search-happy.json 2>/dev/null || echo "")"
if [[ "$RESULTS_TYPE" != "array" ]]; then
    echo "FAIL: happy path body has no array `.results` (got type=$RESULTS_TYPE)" >&2
    cat /tmp/smoke-search-happy.json >&2
    exit 1
fi
RESULT_COUNT="$(jq '.results | length' /tmp/smoke-search-happy.json)"
echo "    ✓ 200 OK with $RESULT_COUNT result(s)"
echo

# ----------------------------------------------------------------------------
# 5. Rate limit: 60 successful queries (counting the happy-path call from
#    step 4 as #1), then 61st returns 429 with Retry-After. We've already
#    consumed slot 1, so issue 59 more for a total of 60.
# ----------------------------------------------------------------------------
echo "==> Rate limit: 59 more queries (total 60) expect 200 each..."
for i in $(seq 2 60); do
    STATUS="$(search_get "$QUERY" "" /dev/null)"
    if [[ "$STATUS" != "200" ]]; then
        echo "FAIL: query #$i returned $STATUS (expected 200)" >&2
        echo "      hint: hourly cap may already have been consumed pre-smoke." >&2
        exit 1
    fi
done
echo "    ✓ 60 successful queries"
echo

echo "==> Rate limit: 61st query expects 429 + Retry-After..."
RESP_HEADERS="$(curl -sS -o /tmp/smoke-search-429.json -D - \
    -H "Authorization: Bearer $JWT" \
    "$API_BASE/api/v1/search?q=$(printf '%s' "$QUERY" | jq -sRr @uri)" 2>/dev/null)"
STATUS="$(echo "$RESP_HEADERS" | head -1 | awk '{print $2}')"
RETRY_AFTER="$(echo "$RESP_HEADERS" | grep -i '^retry-after:' | awk '{print $2}' | tr -d '\r')"

if [[ "$STATUS" != "429" ]]; then
    echo "FAIL: 61st query returned $STATUS, expected 429" >&2
    cat /tmp/smoke-search-429.json >&2
    echo "      hint: lookout for Redis fail-soft (NoOpRateLimiter bound at" >&2
    echo "      startup) — search the deploy logs for" >&2
    echo "      'event=ratelimiter_noop_fallback' or" >&2
    echo "      'event=redis_connect_failed fail_soft=true'." >&2
    exit 1
fi
if [[ -z "$RETRY_AFTER" ]]; then
    echo "FAIL: 61st query missing Retry-After header" >&2
    echo "$RESP_HEADERS" >&2
    exit 1
fi
echo "    ✓ 429 with Retry-After=$RETRY_AFTER seconds"

# Sanity-check: Retry-After should be > 0 and <= 3600 (the hourly window).
if [[ "$RETRY_AFTER" -lt 1 ]] || [[ "$RETRY_AFTER" -gt 3600 ]]; then
    echo "FAIL: Retry-After=$RETRY_AFTER outside expected range [1, 3600]" >&2
    echo "      hint: a value < 60 typically means Redis fail-softed and the" >&2
    echo "      InMemoryRateLimiter fell back — check Redis URL scheme (rediss://" >&2
    echo "      vs redis://) and connect logs (PR #44 lazy-connect lesson)." >&2
    exit 1
fi
echo "    ✓ Retry-After value within [1, 3600]"
echo

echo "==> Smoke PASSED."
exit 0
