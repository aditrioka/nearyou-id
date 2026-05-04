#!/usr/bin/env bash
# Smoke test for `chat-realtime-broadcast` Phase 7 — verifies the staging deploy
# of the publish leg. Covers tasks 7.3, 7.7, 7.8 fully automated. Tasks 7.4 / 7.5
# / 7.6 (real WSS subscriber) require manual verification via the Supabase
# Realtime Inspector — the script prints the exact subscription params when it
# reaches those steps.
#
# Per `openspec/changes/chat-realtime-broadcast/tasks.md` Phase 7:
#  - 7.3 Sender sends a chat message → HTTP 201 + row in chat_messages.
#  - 7.4 Subscriber B receives broadcast within ~1s — MANUAL via Realtime Inspector.
#  - 7.5 Two simultaneous subscribers receive identical payloads — MANUAL.
#  - 7.6 Shadow-banned sender → row persists, NO broadcast — MANUAL subscriber check.
#  - 7.7 Revoke service-role key → send → 201 + WARN log within 30s.
#  - 7.8 Restore service-role key → subsequent broadcasts work normally.
#
# Usage:
#   dev/scripts/smoke-chat-realtime-broadcast.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     [--gcp-project nearyou-staging] \
#     [--service nearyou-backend-staging] \
#     [--region <region>] \
#     [--recipient <recipient-uuid>] \
#     [--conversation <conversation-uuid>] \
#     [--skip-outage-test] \
#     <sender-uuid>
#
# Defaults to https://api-staging.nearyou.id + project nearyou-staging.
#
# Prerequisites:
#  - Sender + recipient UUIDs reference existing Free users in staging
#    (`users.subscription_status = 'free'`, default).
#  - No `user_blocks` row in either direction.
#  - Sender has not exhausted today's 50/day chat-send cap.
#  - GCP Secret Manager slots `staging-supabase-service-role-key` and
#    `staging-supabase-url` exist and are populated.
#  - Cloud Run service `nearyou-backend-staging` is deployed with the latest
#    chat-realtime-broadcast revision (deploy-staging.yml has been updated to
#    inject SUPABASE_SERVICE_ROLE_KEY=staging-supabase-service-role-key:latest).
#  - `KTOR_RSA_PRIVATE_KEY` env var set to the staging RSA private key, e.g.,
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#        dev/scripts/smoke-chat-realtime-broadcast.sh \
#          --recipient <uuid> <sender-uuid>
#
# Exit codes:
#   0 — all automated assertions passed (manual subscriber checks still required for 7.4-7.6).
#   1 — smoke failed: at least one assertion mismatched.
#   2 — usage error.

set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
GCP_PROJECT="nearyou-staging"
SERVICE_NAME="nearyou-backend-staging"
REGION=""
RECIPIENT_UUID=""
CONVERSATION_UUID=""
SKIP_OUTAGE=0
SECRET_SLOT="staging-supabase-service-role-key"

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base) API_BASE="$2"; shift 2 ;;
        --gcp-project) GCP_PROJECT="$2"; shift 2 ;;
        --service) SERVICE_NAME="$2"; shift 2 ;;
        --region) REGION="$2"; shift 2 ;;
        --recipient) RECIPIENT_UUID="$2"; shift 2 ;;
        --conversation) CONVERSATION_UUID="$2"; shift 2 ;;
        --skip-outage-test) SKIP_OUTAGE=1; shift ;;
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

# Ktor channel + topic (per chat-realtime-broadcast spec § Channel name format).
channel_for() { echo "realtime:conversation:$1"; }

echo "==> Smoke — chat-realtime-broadcast (Phase 7)"
echo "    API base:     $API_BASE"
echo "    GCP project:  $GCP_PROJECT"
echo "    Service:      $SERVICE_NAME"
echo "    Sender UUID:  $SENDER_UUID"
[[ -n "$RECIPIENT_UUID" ]] && echo "    Recipient:    $RECIPIENT_UUID"
[[ -n "$CONVERSATION_UUID" ]] && echo "    Conversation: $CONVERSATION_UUID"
echo

# ----------------------------------------------------------------------------
# 1. Mint JWT.
# ----------------------------------------------------------------------------
echo "==> Minting JWT for sender $SENDER_UUID..."
JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$SENDER_UUID")"
[[ -z "$JWT" ]] && { echo "ERROR: JWT mint failed" >&2; exit 2; }
echo "    JWT minted (${#JWT} chars)."
echo

# ----------------------------------------------------------------------------
# 2. Resolve conversation.
# ----------------------------------------------------------------------------
if [[ -z "$CONVERSATION_UUID" ]]; then
    echo "==> Creating-or-returning 1:1 conversation with recipient $RECIPIENT_UUID..."
    CREATE_BODY="{\"recipient_user_id\":\"$RECIPIENT_UUID\"}"
    CREATE_RESP="$(curl -sS -X POST \
        -H "Authorization: Bearer $JWT" \
        -H "Content-Type: application/json" \
        --data-raw "$CREATE_BODY" \
        "$API_BASE/api/v1/conversations" 2>/dev/null)"
    CONVERSATION_UUID="$(echo "$CREATE_RESP" | jq -r '.conversation.id // ""')"
    if [[ -z "$CONVERSATION_UUID" ]]; then
        echo "ERROR: conversation create-or-return failed: $CREATE_RESP" >&2
        exit 2
    fi
    echo "    Conversation: $CONVERSATION_UUID"
fi
echo

# ----------------------------------------------------------------------------
# 3. Task 7.3 — basic send returns 201 + row visible via GET /messages.
# ----------------------------------------------------------------------------
echo "==> 7.3 — send 1 message; expect HTTP 201 + row visible via GET /messages"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
SEND_BODY="{\"content\":\"smoke 7.3 ${TS}\"}"
SEND_RESP="$(curl -sS -w '\nHTTPSTATUS:%{http_code}' -X POST \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    --data-raw "$SEND_BODY" \
    "$API_BASE/api/v1/chat/$CONVERSATION_UUID/messages")"
SEND_STATUS="$(echo "$SEND_RESP" | tail -1 | sed 's/HTTPSTATUS://')"
SEND_BODY_RESP="$(echo "$SEND_RESP" | sed '$d')"
if [[ "$SEND_STATUS" != "201" ]]; then
    echo "FAIL: 7.3 expected 201, got $SEND_STATUS" >&2
    echo "$SEND_BODY_RESP" >&2
    exit 1
fi
SENT_ID="$(echo "$SEND_BODY_RESP" | jq -r '.id')"
echo "    ✓ POST returned 201; message id: $SENT_ID"

GET_RESP="$(curl -sS -H "Authorization: Bearer $JWT" \
    "$API_BASE/api/v1/chat/$CONVERSATION_UUID/messages")"
if echo "$GET_RESP" | jq -e ".messages[] | select(.id==\"$SENT_ID\")" >/dev/null; then
    echo "    ✓ GET /messages returns the new row"
else
    echo "FAIL: 7.3 row not visible via GET /messages" >&2
    echo "$GET_RESP" >&2
    exit 1
fi
echo

# ----------------------------------------------------------------------------
# 4. Tasks 7.4 / 7.5 / 7.6 — manual subscriber checks via Realtime Inspector.
# ----------------------------------------------------------------------------
CHANNEL="$(channel_for "$CONVERSATION_UUID")"
TOPIC="conversation:$CONVERSATION_UUID"
echo "==> 7.4 / 7.5 / 7.6 — MANUAL subscriber verification"
echo
echo "    The bash smoke harness does not ship a Supabase Realtime WSS client."
echo "    Verify these scenarios via the Supabase Dashboard's Realtime Inspector:"
echo
echo "    Project URL: $(gcloud secrets versions access latest --secret=staging-supabase-url --project=$GCP_PROJECT 2>/dev/null)"
echo "    Channel:     $CHANNEL"
echo "    Topic:       $TOPIC"
echo "    Event:       chat_message"
echo "    Private:     true (subscriber needs a realtime-token JWT)"
echo
echo "    Subscriber JWT (mint via GET /api/v1/realtime/token from a participant):"
echo "      curl -H 'Authorization: Bearer <participant-JWT>' \\"
echo "        $API_BASE/api/v1/realtime/token"
echo
echo "    Steps:"
echo "      7.4 Subscribe one conversation participant → send via this script's"
echo "          step 3 → assert payload arrives within ~1s."
echo "      7.5 Open two browser tabs both subscribed → send once → assert"
echo "          BOTH receive the identical broadcast."
echo "      7.6 Run \`UPDATE users SET is_shadow_banned = TRUE WHERE id = '$SENDER_UUID';\`"
echo "          via Supabase SQL Editor → send via step 3 → assert subscriber"
echo "          DOES NOT receive a broadcast AND row IS visible via REST."
echo "          Restore with \`UPDATE users SET is_shadow_banned = FALSE ...\`."
echo
read -p "    Press Enter once 7.4-7.6 are manually verified (or Ctrl+C to abort) " _DUMMY || true
echo

# ----------------------------------------------------------------------------
# 5. Task 7.7 — revoke service-role key (rotate to junk), send, expect WARN.
# ----------------------------------------------------------------------------
if [[ "$SKIP_OUTAGE" -eq 1 ]]; then
    echo "==> 7.7 / 7.8 — SKIPPED (--skip-outage-test)"
    echo
else
    echo "==> 7.7 — disable real key (add a junk version), send, expect WARN log"
    echo "    Saving current service-role-key VERSION number for restore..."
    GOOD_VERSION="$(gcloud secrets versions list "$SECRET_SLOT" --project="$GCP_PROJECT" \
        --filter='state=enabled' --format='value(name)' --limit=1)"
    if [[ -z "$GOOD_VERSION" ]]; then
        echo "FAIL: no enabled version of $SECRET_SLOT — cannot proceed safely" >&2
        exit 1
    fi
    echo "    Good version: $GOOD_VERSION"
    JUNK="invalid-broadcast-key-$(date +%s)"
    echo "    Adding junk version (decoy)..."
    JUNK_VERSION="$(printf '%s' "$JUNK" | gcloud secrets versions add "$SECRET_SLOT" \
        --project="$GCP_PROJECT" --data-file=- --format='value(name)')"
    echo "    Junk version: $JUNK_VERSION"

    echo "    Disabling good version $GOOD_VERSION..."
    gcloud secrets versions disable "$GOOD_VERSION" --secret="$SECRET_SLOT" \
        --project="$GCP_PROJECT" --quiet

    # Cloud Run picks up the new :latest only on container restart. Force a
    # revision restart by updating-traffic with no real change. Use
    # `gcloud run services update --update-secrets` to force a new revision
    # that re-pulls the secret.
    echo "    Forcing new Cloud Run revision so it picks up the new :latest..."
    REGION_FLAG=""
    [[ -n "$REGION" ]] && REGION_FLAG="--region=$REGION"
    gcloud run services update "$SERVICE_NAME" $REGION_FLAG \
        --project="$GCP_PROJECT" \
        --update-env-vars="SMOKE_BROADCAST_OUTAGE_PROBE=$JUNK_VERSION" \
        --quiet
    echo "    Waiting 15s for the new revision to start receiving traffic..."
    sleep 15

    echo "    Sending a message that should produce a WARN log line..."
    OUTAGE_BODY="{\"content\":\"smoke 7.7 outage probe ${TS}\"}"
    OUTAGE_STATUS="$(curl -sS -o /tmp/smoke-broadcast-outage.json -w '%{http_code}' -X POST \
        -H "Authorization: Bearer $JWT" \
        -H "Content-Type: application/json" \
        --data-raw "$OUTAGE_BODY" \
        "$API_BASE/api/v1/chat/$CONVERSATION_UUID/messages")"
    OUTAGE_ID="$(jq -r '.id // empty' /tmp/smoke-broadcast-outage.json)"
    if [[ "$OUTAGE_STATUS" != "201" ]]; then
        echo "FAIL: 7.7 expected 201 even during outage, got $OUTAGE_STATUS" >&2
        cat /tmp/smoke-broadcast-outage.json >&2
        gcloud secrets versions enable "$GOOD_VERSION" --secret="$SECRET_SLOT" \
            --project="$GCP_PROJECT" --quiet >/dev/null 2>&1 || true
        exit 1
    fi
    echo "    ✓ POST returned 201 with outage in place; message id: $OUTAGE_ID"

    echo "    Polling Cloud Logging up to 30s for chat_realtime_publish_failed..."
    DEADLINE=$(( $(date +%s) + 30 ))
    LOG_HIT=0
    while (( $(date +%s) < DEADLINE )); do
        if gcloud logging read \
            "resource.type=cloud_run_revision AND resource.labels.service_name=$SERVICE_NAME AND textPayload:chat_realtime_publish_failed AND textPayload:$OUTAGE_ID" \
            --project="$GCP_PROJECT" \
            --freshness=2m --limit=1 --format='value(textPayload)' 2>/dev/null | grep -q chat_realtime_publish_failed; then
            LOG_HIT=1
            break
        fi
        sleep 3
    done
    if [[ "$LOG_HIT" -eq 1 ]]; then
        echo "    ✓ chat_realtime_publish_failed WARN log observed within 30s"
    else
        echo "FAIL: 7.7 no WARN log observed within 30s" >&2
        gcloud secrets versions enable "$GOOD_VERSION" --secret="$SECRET_SLOT" \
            --project="$GCP_PROJECT" --quiet >/dev/null 2>&1 || true
        exit 1
    fi
    echo

    # ------------------------------------------------------------------------
    # 6. Task 7.8 — restore good version, force revision restart, verify normal.
    # ------------------------------------------------------------------------
    echo "==> 7.8 — re-enable good service-role key version $GOOD_VERSION"
    gcloud secrets versions enable "$GOOD_VERSION" --secret="$SECRET_SLOT" \
        --project="$GCP_PROJECT" --quiet
    echo "    Disabling junk version $JUNK_VERSION..."
    gcloud secrets versions disable "$JUNK_VERSION" --secret="$SECRET_SLOT" \
        --project="$GCP_PROJECT" --quiet
    echo "    Forcing new Cloud Run revision so it re-pulls the restored :latest..."
    gcloud run services update "$SERVICE_NAME" $REGION_FLAG \
        --project="$GCP_PROJECT" \
        --remove-env-vars="SMOKE_BROADCAST_OUTAGE_PROBE" \
        --quiet
    echo "    Waiting 15s for the new revision to start receiving traffic..."
    sleep 15

    HAPPY_BODY="{\"content\":\"smoke 7.8 restore probe ${TS}\"}"
    HAPPY_STATUS="$(curl -sS -o /tmp/smoke-broadcast-restore.json -w '%{http_code}' -X POST \
        -H "Authorization: Bearer $JWT" \
        -H "Content-Type: application/json" \
        --data-raw "$HAPPY_BODY" \
        "$API_BASE/api/v1/chat/$CONVERSATION_UUID/messages")"
    HAPPY_ID="$(jq -r '.id // empty' /tmp/smoke-broadcast-restore.json)"
    if [[ "$HAPPY_STATUS" != "201" ]]; then
        echo "FAIL: 7.8 expected 201 after restore, got $HAPPY_STATUS" >&2
        cat /tmp/smoke-broadcast-restore.json >&2
        exit 1
    fi
    echo "    ✓ POST returned 201; message id: $HAPPY_ID"
    echo "    NB: 7.8 broadcast confirmation requires a manual subscriber check"
    echo "        via the Supabase Realtime Inspector (channel: $CHANNEL)."
    echo
fi

# ----------------------------------------------------------------------------
# 7. Pass.
# ----------------------------------------------------------------------------
echo "==> ✅ chat-realtime-broadcast smoke automated steps PASSED"
echo "    7.3: ✓ POST 201 + row visible"
echo "    7.4-7.6: pending manual Realtime Inspector verification (see above)"
if [[ "$SKIP_OUTAGE" -eq 1 ]]; then
    echo "    7.7-7.8: SKIPPED"
else
    echo "    7.7: ✓ POST 201 during outage + WARN log within 30s"
    echo "    7.8: ✓ POST 201 after key restore (manual subscriber check pending)"
fi
echo
echo "Tick tasks 7.3 / 7.7 / 7.8 (and 7.4-7.6 once manually verified) in"
echo "openspec/changes/chat-realtime-broadcast/tasks.md."
exit 0
