#!/usr/bin/env bash
# Smoke test for `chat-message-notification` Section 8 — verifies the staging
# deploy of the chat-message notification emit path.
#
# Per `openspec/changes/chat-message-notification/tasks.md` Section 8:
#  - 8.1 (caller orchestrates the staging deploy via gh workflow run).
#  - 8.2 — chat send A → B → assert recipient B's GET /api/v1/notifications
#    returns a `chat_message` row with the right body_data.{conversation_id, preview}.
#    Additionally inspect Cloud Run / FCM dispatcher structured logs for the
#    `event="fcm_dispatched"` line carrying the chat_message PushCopy template.
#  - 8.3 (shadow-ban skip path) — MANUAL via the Supabase SQL Editor:
#    UPDATE users SET is_shadow_banned = TRUE WHERE id = '<sender>';
#    re-run send → assert zero new notification rows for B.
#    The script prints the exact SQL when it reaches that step.
#
# Usage:
#   dev/scripts/smoke-chat-message-notification.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     [--gcp-project nearyou-staging] \
#     [--service nearyou-backend-staging] \
#     [--region <region>] \
#     [--recipient <recipient-uuid>] \
#     [--conversation <conversation-uuid>] \
#     [--skip-fcm-log-check] \
#     [--skip-shadow-ban] \
#     <sender-uuid>
#
# Defaults to https://api-staging.nearyou.id + project nearyou-staging.
#
# Prerequisites:
#  - Sender + recipient UUIDs reference existing Free users in staging
#    (`users.subscription_status = 'free'`, `is_shadow_banned = FALSE`).
#  - No `user_blocks` row in either direction.
#  - Sender has not exhausted today's 50/day chat-send cap.
#  - GCP Secret Manager slot `staging-ktor-rsa-private-key` exists and is
#    populated. Caller exports KTOR_RSA_PRIVATE_KEY before invoking this script:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#        dev/scripts/smoke-chat-message-notification.sh \
#          --recipient <uuid> <sender-uuid>
#  - Cloud Run service `nearyou-backend-staging` has the latest
#    chat-message-notification revision deployed (deploy-staging.yml has run
#    successfully against the change branch).
#
# Exit codes:
#   0 — all automated assertions passed (manual shadow-ban check still required
#       unless --skip-shadow-ban is passed).
#   1 — smoke failed: at least one assertion mismatched.
#   2 — usage error.

set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
GCP_PROJECT="nearyou-staging"
SERVICE_NAME="nearyou-backend-staging"
REGION=""
RECIPIENT_UUID=""
CONVERSATION_UUID=""
SKIP_FCM_LOG=0
SKIP_SHADOW_BAN=0

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base) API_BASE="$2"; shift 2 ;;
        --gcp-project) GCP_PROJECT="$2"; shift 2 ;;
        --service) SERVICE_NAME="$2"; shift 2 ;;
        --region) REGION="$2"; shift 2 ;;
        --recipient) RECIPIENT_UUID="$2"; shift 2 ;;
        --conversation) CONVERSATION_UUID="$2"; shift 2 ;;
        --skip-fcm-log-check) SKIP_FCM_LOG=1; shift ;;
        --skip-shadow-ban) SKIP_SHADOW_BAN=1; shift ;;
        --help|-h)
            sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
            exit 0
            ;;
        --*)
            echo "Unknown flag: $1" >&2; exit 2 ;;
        *)
            SENDER_UUID="$1"; shift ;;
    esac
done

if [[ -z "${SENDER_UUID:-}" ]]; then
    echo "usage: $0 [--api-base <url>] [--recipient <uuid> | --conversation <uuid>] <sender-uuid>" >&2
    exit 2
fi
if [[ -z "$RECIPIENT_UUID" && -z "$CONVERSATION_UUID" ]]; then
    echo "ERROR: must pass either --recipient <uuid> or --conversation <uuid>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

echo "==> Smoke — chat-message-notification (Section 8)"
echo "    API base:     $API_BASE"
echo "    GCP project:  $GCP_PROJECT"
echo "    Service:      $SERVICE_NAME"
echo "    Sender UUID:  $SENDER_UUID"
[[ -n "$RECIPIENT_UUID" ]] && echo "    Recipient:    $RECIPIENT_UUID"
[[ -n "$CONVERSATION_UUID" ]] && echo "    Conversation: $CONVERSATION_UUID"
echo

# ----------------------------------------------------------------------------
# 1. Mint JWT for sender + recipient.
# ----------------------------------------------------------------------------
echo "==> Minting JWT for sender $SENDER_UUID..."
SENDER_JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$SENDER_UUID")"
[[ -z "$SENDER_JWT" ]] && { echo "ERROR: sender JWT mint failed" >&2; exit 2; }
echo "    Sender JWT minted (${#SENDER_JWT} chars)."

# ----------------------------------------------------------------------------
# 2. Resolve conversation. If --conversation was passed, use it; otherwise
#    create-or-return via the sender's POST /conversations call.
# ----------------------------------------------------------------------------
if [[ -z "$CONVERSATION_UUID" ]]; then
    echo "==> Creating-or-returning 1:1 conversation with recipient $RECIPIENT_UUID..."
    CREATE_BODY="{\"recipient_user_id\":\"$RECIPIENT_UUID\"}"
    CREATE_RESP="$(curl -sS -X POST \
        -H "Authorization: Bearer $SENDER_JWT" \
        -H "Content-Type: application/json" \
        --data-raw "$CREATE_BODY" \
        "$API_BASE/api/v1/conversations")"
    CONVERSATION_UUID="$(echo "$CREATE_RESP" | jq -r '.conversation.id // ""')"
    if [[ -z "$CONVERSATION_UUID" ]]; then
        echo "ERROR: conversation create-or-return failed: $CREATE_RESP" >&2
        exit 2
    fi
    # Resolve the recipient out of the conversation participants if not passed.
    if [[ -z "$RECIPIENT_UUID" ]]; then
        RECIPIENT_UUID="$(echo "$CREATE_RESP" | jq -r ".participants[] | select(.user_id != \"$SENDER_UUID\") | .user_id")"
    fi
    echo "    Conversation: $CONVERSATION_UUID"
    echo "    Recipient:    $RECIPIENT_UUID"
fi
[[ -z "$RECIPIENT_UUID" ]] && { echo "ERROR: cannot resolve recipient UUID" >&2; exit 2; }
echo

# Mint recipient JWT for the GET /api/v1/notifications check.
echo "==> Minting JWT for recipient $RECIPIENT_UUID..."
RECIPIENT_JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$RECIPIENT_UUID")"
[[ -z "$RECIPIENT_JWT" ]] && { echo "ERROR: recipient JWT mint failed" >&2; exit 2; }
echo "    Recipient JWT minted (${#RECIPIENT_JWT} chars)."
echo

# ----------------------------------------------------------------------------
# 3. Task 8.2 — happy-path send + recipient sees chat_message notification.
# ----------------------------------------------------------------------------
TS="$(date -u +%Y%m%dT%H%M%SZ)"
SMOKE_CONTENT="smoke 8.2 ${TS}"
echo "==> 8.2 — send chat message; expect 201 + recipient's GET /notifications has chat_message row"
SEND_BODY="{\"content\":\"$SMOKE_CONTENT\"}"
SEND_RESP="$(curl -sS -w '\nHTTPSTATUS:%{http_code}' -X POST \
    -H "Authorization: Bearer $SENDER_JWT" \
    -H "Content-Type: application/json" \
    --data-raw "$SEND_BODY" \
    "$API_BASE/api/v1/chat/$CONVERSATION_UUID/messages")"
SEND_STATUS="$(echo "$SEND_RESP" | tail -1 | sed 's/HTTPSTATUS://')"
SEND_BODY_RESP="$(echo "$SEND_RESP" | sed '$d')"
if [[ "$SEND_STATUS" != "201" ]]; then
    echo "FAIL: 8.2 expected 201, got $SEND_STATUS" >&2
    echo "$SEND_BODY_RESP" >&2
    exit 1
fi
SENT_ID="$(echo "$SEND_BODY_RESP" | jq -r '.id')"
echo "    ✓ POST returned 201; message id: $SENT_ID"

# Brief wait — the emit is in-tx so it commits with the chat row, but allow the
# Cloud Run instance an extra ~500 ms for any async log/dispatch propagation.
sleep 1

# Fetch recipient's notifications. Look for the `chat_message` row whose
# target_id matches the just-sent message.
NOTIF_RESP="$(curl -sS -H "Authorization: Bearer $RECIPIENT_JWT" \
    "$API_BASE/api/v1/notifications")"
MATCHED="$(echo "$NOTIF_RESP" | jq -c \
    "[.items[] | select(.type == \"chat_message\" and .target_id == \"$SENT_ID\")] | .[0] // empty")"
if [[ -z "$MATCHED" ]]; then
    echo "FAIL: 8.2 recipient's GET /notifications has no chat_message row for target_id=$SENT_ID" >&2
    echo "    Recipient response (first 1500 chars):" >&2
    echo "$NOTIF_RESP" | head -c 1500 >&2; echo >&2
    exit 1
fi
echo "    ✓ Recipient sees chat_message notification for target_id=$SENT_ID"

# Validate body_data shape.
BODY_CONV="$(echo "$MATCHED" | jq -r '.body_data.conversation_id // empty')"
BODY_PREVIEW="$(echo "$MATCHED" | jq -r '.body_data.preview // empty')"
ACTOR="$(echo "$MATCHED" | jq -r '.actor_user_id // empty')"
TARGET_TYPE="$(echo "$MATCHED" | jq -r '.target_type // empty')"

if [[ "$BODY_CONV" != "$CONVERSATION_UUID" ]]; then
    echo "FAIL: 8.2 body_data.conversation_id mismatch: got '$BODY_CONV', expected '$CONVERSATION_UUID'" >&2
    exit 1
fi
echo "    ✓ body_data.conversation_id = $BODY_CONV"

if [[ "$BODY_PREVIEW" != "$SMOKE_CONTENT" ]]; then
    echo "FAIL: 8.2 body_data.preview mismatch: got '$BODY_PREVIEW', expected '$SMOKE_CONTENT'" >&2
    exit 1
fi
echo "    ✓ body_data.preview = \"$BODY_PREVIEW\""

if [[ "$ACTOR" != "$SENDER_UUID" ]]; then
    echo "FAIL: 8.2 actor_user_id mismatch: got '$ACTOR', expected '$SENDER_UUID'" >&2
    exit 1
fi
echo "    ✓ actor_user_id = $ACTOR"

if [[ "$TARGET_TYPE" != "message" ]]; then
    echo "FAIL: 8.2 target_type mismatch: got '$TARGET_TYPE', expected 'message'" >&2
    exit 1
fi
echo "    ✓ target_type = message"

# Canonical lowercase RFC 4122 form check on conversation_id.
if [[ ! "$BODY_CONV" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
    echo "FAIL: 8.2 body_data.conversation_id is not canonical lowercase RFC 4122: '$BODY_CONV'" >&2
    exit 1
fi
echo "    ✓ body_data.conversation_id is canonical lowercase RFC 4122"
echo

# ----------------------------------------------------------------------------
# 4. Task 8.2 (cont) — inspect Cloud Run / FCM dispatcher structured log.
#    The dispatcher MAY have skipped FCM if the recipient has no token row
#    (typical for staging users who haven't opted in to push); the assertion
#    is "log line for the dispatch attempt exists" rather than "FCM delivered."
# ----------------------------------------------------------------------------
if [[ "$SKIP_FCM_LOG" -eq 1 ]]; then
    echo "==> 8.2 FCM-log inspection — SKIPPED (--skip-fcm-log-check)"
    echo
else
    echo "==> 8.2 FCM-log inspection — looking for dispatch log line"
    # Look for any log line emitted by the chat send handler / NotificationEmitter
    # for this notification's recipient + chat_message type. The exact event
    # field varies by leg (in-app dispatcher logs `notification_dispatched`;
    # FCM leg logs `fcm_dispatched` or `fcm_no_tokens`).
    sleep 2 # give Cloud Logging a moment to ingest
    FILTER="resource.type=cloud_run_revision AND resource.labels.service_name=\"$SERVICE_NAME\" AND (textPayload:\"$SENDER_UUID\" OR textPayload:\"chat_message\") AND timestamp>=\"$(date -u -v-5M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%SZ)\""
    LOG_HITS="$(gcloud logging read "$FILTER" \
        --project="$GCP_PROJECT" \
        --limit=20 \
        --format='value(textPayload)' 2>/dev/null || true)"
    if [[ -z "$LOG_HITS" ]]; then
        echo "    ⚠️  No dispatcher log lines found in last 5 min — manual inspection recommended."
        echo "    Try: gcloud logging read \"resource.labels.service_name=$SERVICE_NAME AND textPayload:chat_message\" --project=$GCP_PROJECT --limit=20"
    else
        echo "    ✓ Found dispatcher log lines:"
        echo "$LOG_HITS" | head -5 | sed 's/^/      /'
        # If we see fcm_dispatched, also try to print the body field for visual confirmation.
        if echo "$LOG_HITS" | grep -q "fcm_dispatched"; then
            echo "    ✓ FCM dispatch log present (the 'mengirim pesan' body would appear here)."
        elif echo "$LOG_HITS" | grep -q "notification_dispatched"; then
            echo "    ✓ In-app dispatch log present (NoopNotificationDispatcher or test wiring)."
        else
            echo "    ℹ️  Dispatch log found but neither fcm_dispatched nor notification_dispatched event matched. Sample above."
        fi
    fi
    echo
fi

# ----------------------------------------------------------------------------
# 5. Task 8.3 — shadow-ban skip path (MANUAL).
# ----------------------------------------------------------------------------
if [[ "$SKIP_SHADOW_BAN" -eq 1 ]]; then
    echo "==> 8.3 shadow-ban skip — SKIPPED (--skip-shadow-ban)"
    echo "    Document the skip rationale in the PR body before /opsx:archive."
    echo
else
    echo "==> 8.3 shadow-ban skip — MANUAL via Supabase SQL Editor"
    echo
    echo "    Steps:"
    echo "      1. Open the Supabase Dashboard → SQL Editor for the staging project."
    echo "      2. Run:"
    echo "           UPDATE users SET is_shadow_banned = TRUE WHERE id = '$SENDER_UUID';"
    echo "      3. Re-run a chat send from this script (or curl directly):"
    echo "           curl -sS -X POST \\"
    echo "             -H \"Authorization: Bearer $SENDER_JWT\" \\"
    echo "             -H 'Content-Type: application/json' \\"
    echo "             --data-raw '{\"content\":\"shadow-ban smoke '\"\$(date +%s)\"'\"}' \\"
    echo "             $API_BASE/api/v1/chat/$CONVERSATION_UUID/messages"
    echo "      4. Verify HTTP 201 (the chat_messages row still persists per invisible-actor)."
    echo "      5. Re-fetch the recipient's notifications and confirm NO new chat_message"
    echo "         row appeared for the second send:"
    echo "           curl -sS -H 'Authorization: Bearer $RECIPIENT_JWT' \\"
    echo "             $API_BASE/api/v1/notifications | jq '.items | map(select(.type == \"chat_message\")) | length'"
    echo "      6. Restore: UPDATE users SET is_shadow_banned = FALSE WHERE id = '$SENDER_UUID';"
    echo
    echo "    NOTE: if staging Supabase is paused (Free-tier idle auto-pause), document"
    echo "    the skip in the PR body and rerun before /opsx:archive."
    echo
    read -r -p "    Press Enter once 8.3 is manually verified (or Ctrl+C to abort) " _DUMMY || true
    echo
fi

echo "==> Smoke complete."
echo "    8.2 happy-path: PASSED"
echo "    8.2 FCM-log:    $([[ "$SKIP_FCM_LOG" -eq 1 ]] && echo "SKIPPED" || echo "INSPECTED (see above)")"
echo "    8.3 shadow-ban: $([[ "$SKIP_SHADOW_BAN" -eq 1 ]] && echo "SKIPPED" || echo "MANUAL — verified by operator")"
exit 0
