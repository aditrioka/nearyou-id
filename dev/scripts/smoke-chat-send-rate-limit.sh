#!/usr/bin/env bash
# Smoke test for `chat-rate-limit` task 6.3 — verifies the 50/day Free chat send
# cap fires on the 51st message with HTTP 429 + Retry-After header.
#
# Per `openspec/changes/chat-rate-limit/tasks.md` task 6.3:
# "Run smoke against https://api-staging.nearyou.id for synthetic Free user.
#  Expect: 50 chat sends return 201, 51st returns 429 + Retry-After in expected
#  WIB-staggered window + error.code = 'rate_limited'."
#
# Adapted from `dev/scripts/smoke-reply-rate-limit.sh` — the differences from
# the reply smoke are:
#  - Endpoint: POST /api/v1/chat/{conversation_id}/messages (vs /posts/{id}/replies).
#  - Setup: POST /api/v1/conversations to create-or-return one 1:1 conversation
#    (the cap is per-sender keyed, so all 51 sends hit the SAME conversation).
#  - Cap: 50/day Free (vs 20/day for replies; same daily-only shape, no burst).
#  - Body shape unchanged: {"content":"..."}.
#  - Success code: 201 Created (same as reply).
#
# Usage:
#   dev/scripts/smoke-chat-send-rate-limit.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     [--recipient <recipient-uuid>] \
#     [--conversation <conversation-uuid>] \
#     <sender-uuid>
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification.
#
# `--conversation` skips the setup step and reuses an existing conversation.
# `--recipient` (required if --conversation is omitted) names the other party
# in the create-or-return call.
#
# Prerequisites (per chat-foundation contract + the like-smoke lessons in
# `openspec/changes/archive/2026-04-25-like-rate-limit/tasks.md` task 9.7):
#  - The sender UUID points to an existing Free user in the target environment
#    (`users.subscription_status = 'free'`, the migration default).
#  - The recipient UUID points to an existing user with no `user_blocks` row in
#    EITHER direction with the sender (the canonical bidirectional block check
#    rejects with 403 + "Tidak dapat mengirim pesan ke user ini" if blocked).
#  - The sender has not exhausted their daily chat-send cap today (WIB-staggered
#    reset).
#  - The script mints a JWT via `dev/scripts/mint-dev-jwt.sh`. For staging
#    the script needs `KTOR_RSA_PRIVATE_KEY` set to the staging RSA key:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#      dev/scripts/smoke-chat-send-rate-limit.sh \
#        --recipient <recipient-uuid> <sender-uuid>
#
# Exit codes:
#   0 — smoke passed: 50 sends returned 201, 51st returned 429 with Retry-After ≥ 60s.
#   1 — smoke failed: at least one assertion mismatched.
#   2 — usage error (missing args, JWT mint failure, conversation setup failure, etc.).

set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
RECIPIENT_UUID=""
CONVERSATION_UUID=""

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base)
            API_BASE="$2"
            shift 2
            ;;
        --recipient)
            RECIPIENT_UUID="$2"
            shift 2
            ;;
        --conversation)
            CONVERSATION_UUID="$2"
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
            SENDER_UUID="$1"
            shift
            ;;
    esac
done

if [[ -z "${SENDER_UUID:-}" ]]; then
    echo "usage: $0 [--api-base <url>] [--recipient <uuid> | --conversation <uuid>] <sender-uuid>" >&2
    exit 2
fi

if [[ -z "$RECIPIENT_UUID" && -z "$CONVERSATION_UUID" ]]; then
    echo "ERROR: must pass either --recipient <uuid> (to create-or-return a conversation) or --conversation <uuid> (to reuse one)" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

echo "==> Smoke — chat send rate limit (50/day Free)"
echo "    API base:    $API_BASE"
echo "    Sender UUID: $SENDER_UUID"
if [[ -n "$RECIPIENT_UUID" ]]; then
    echo "    Recipient:   $RECIPIENT_UUID"
fi
if [[ -n "$CONVERSATION_UUID" ]]; then
    echo "    Conversation (reuse): $CONVERSATION_UUID"
fi
echo

# ----------------------------------------------------------------------------
# 1. Mint a JWT for the sender.
# ----------------------------------------------------------------------------
echo "==> Minting JWT for sender $SENDER_UUID..."
JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$SENDER_UUID")"
if [[ -z "$JWT" ]]; then
    echo "ERROR: JWT mint failed — empty token returned. Check KTOR_RSA_PRIVATE_KEY." >&2
    exit 2
fi
echo "    JWT minted (${#JWT} chars)."
echo

# ----------------------------------------------------------------------------
# 2. Resolve the conversation UUID — either reuse or create-or-return.
# ----------------------------------------------------------------------------
if [[ -z "$CONVERSATION_UUID" ]]; then
    echo "==> Creating-or-returning 1:1 conversation with recipient $RECIPIENT_UUID..."
    CREATE_BODY="{\"recipient_user_id\":\"$RECIPIENT_UUID\"}"
    CREATE_RESP="$(curl -sS -X POST \
        -H "Authorization: Bearer $JWT" \
        -H "Content-Type: application/json" \
        --data-raw "$CREATE_BODY" \
        "$API_BASE/api/v1/conversations" 2>/dev/null)"
    CONVERSATION_UUID="$(echo "$CREATE_RESP" | jq -r '.conversation.id // ""' 2>/dev/null || echo "")"
    if [[ -z "$CONVERSATION_UUID" ]]; then
        echo "ERROR: conversation create-or-return failed — couldn't extract .conversation.id from response:" >&2
        echo "$CREATE_RESP" >&2
        exit 2
    fi
    echo "    Conversation: $CONVERSATION_UUID"
fi
echo

# ----------------------------------------------------------------------------
# 3. Hit 50 chat sends — assert 201 each.
# ----------------------------------------------------------------------------
echo "==> Sending 50 chat messages (expect HTTP 201 each)..."
TIMESTAMP_LABEL="$(date -u +%Y%m%dT%H%M%SZ)"
for i in $(seq 0 49); do
    BODY="{\"content\":\"smoke chat send ${TIMESTAMP_LABEL} #$((i + 1))\"}"
    STATUS="$(curl -fsS -o /dev/null -w "%{http_code}" -X POST \
        -H "Authorization: Bearer $JWT" \
        -H "Content-Type: application/json" \
        --data-raw "$BODY" \
        "$API_BASE/api/v1/chat/$CONVERSATION_UUID/messages" || echo "FAIL")"
    if [[ "$STATUS" == "201" ]]; then
        printf "    %2d/50  ✓ 201\n" "$((i + 1))"
    else
        printf "    %2d/50  ✗ got %s (expected 201)\n" "$((i + 1))" "$STATUS"
        echo "FAIL: chat send #$((i + 1)) returned $STATUS, not 201" >&2
        exit 1
    fi
done
echo

# ----------------------------------------------------------------------------
# 4. Hit the 51st send — assert 429 + Retry-After ≥ 60s + error.code.
# ----------------------------------------------------------------------------
echo "==> Sending 51st chat message (expect HTTP 429 + Retry-After header)..."
BODY="{\"content\":\"smoke chat send ${TIMESTAMP_LABEL} #51 (rate-limit probe)\"}"
RESP_HEADERS="$(curl -sS -o /tmp/smoke-chat-send-rl-body.json -D - -X POST \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    --data-raw "$BODY" \
    "$API_BASE/api/v1/chat/$CONVERSATION_UUID/messages" 2>/dev/null)"
STATUS="$(echo "$RESP_HEADERS" | head -1 | awk '{print $2}')"
RETRY_AFTER="$(echo "$RESP_HEADERS" | grep -i '^retry-after:' | awk '{print $2}' | tr -d '\r')"

if [[ "$STATUS" != "429" ]]; then
    echo "FAIL: 51st chat send returned $STATUS, expected 429" >&2
    cat /tmp/smoke-chat-send-rl-body.json >&2
    exit 1
fi

if [[ -z "$RETRY_AFTER" ]]; then
    echo "FAIL: 51st chat send returned 429 but Retry-After header is missing" >&2
    echo "Response headers:" >&2
    echo "$RESP_HEADERS" >&2
    exit 1
fi

ERROR_CODE="$(jq -r '.error.code // ""' /tmp/smoke-chat-send-rl-body.json 2>/dev/null || echo "")"
if [[ "$ERROR_CODE" != "rate_limited" ]]; then
    echo "FAIL: 51st chat send body error.code is '$ERROR_CODE', expected 'rate_limited'" >&2
    cat /tmp/smoke-chat-send-rl-body.json >&2
    exit 1
fi

# Sanity check Retry-After value: should be on the order of 0..90000 seconds
# (next WIB midnight + per-user offset, max ~25 hours). The bucket grows to 50
# in fast succession so the 51st rejection's Retry-After is ~`computeTTLToNextReset`.
# A suspiciously low value (< 60) likely means the limiter fail-softed open
# and the 429 came from somewhere else — fail loud per task 6.4 (the lessons
# from like-rate-limit task 9.7: TLS scheme on staging-redis-url, secret slot
# value, eager-connect crash modes — same RedisRateLimiter plumbing inherited).
if [[ "$RETRY_AFTER" -lt 60 ]]; then
    echo "FAIL: Retry-After=${RETRY_AFTER}s is suspiciously low — likely fail-soft fired." >&2
    echo "      Daily-cap Retry-After should be hundreds of seconds at worst." >&2
    echo "      Investigate Redis connectivity per like-rate-limit task 9.7 lessons:" >&2
    echo "      1. staging-redis-url slot uses 'rediss://' (TLS) for Upstash" >&2
    echo "      2. REDIS_URL env var injected by deploy-staging.yml" >&2
    echo "      3. RedisRateLimiter logs 'event=redis_connect_failed fail_soft=true'" >&2
    cat /tmp/smoke-chat-send-rl-body.json >&2
    exit 1
fi

printf "    51/51  ✓ 429  Retry-After: %s s  error.code: %s\n" "$RETRY_AFTER" "$ERROR_CODE"
echo

# ----------------------------------------------------------------------------
# 5. Pass.
# ----------------------------------------------------------------------------
echo "==> ✅ Chat-send-rate-limit smoke PASSED."
echo "    50 chat sends: 201 each."
echo "    51st chat send: 429 + Retry-After: $RETRY_AFTER s + error.code: $ERROR_CODE."
echo "    Conversation: $CONVERSATION_UUID"
echo
echo "Tick task 6.3 in openspec/changes/chat-rate-limit/tasks.md with:"
echo
echo "  - [x] 6.3 Smoke verified $(date -u +%Y-%m-%dT%H:%M:%SZ) on $API_BASE for"
echo "       sender $SENDER_UUID: 50 chat sends 201, 51st 429 + Retry-After: $RETRY_AFTER s."
exit 0
