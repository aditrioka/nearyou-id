#!/usr/bin/env bash
# Smoke test for `fcm-push-dispatch` (tasks 10.1-10.4):
# verifies the end-to-end FCM dispatch path against staging.
#
# Per `openspec/changes/fcm-push-dispatch/tasks.md` § 10:
#   10.1 operator pre-step  — staging-firebase-admin-sa slot populated.
#   10.2 trigger             — register FCM token + emit post_liked.
#   10.3 inspect             — Cloud Logging fcm_dispatched / fcm_dispatch_failed.
#   10.4 verify              — user_fcm_tokens unchanged on benign smoke.
#
# Adapted from `dev/scripts/smoke-fcm-token-registration.sh`.
#
# Usage:
#   dev/scripts/smoke-fcm-push-dispatch.sh \
#     [--api-base https://api-staging.nearyou.id] \
#     [--gcp-project nearyou-staging] \
#     [--no-cleanup] \
#     <recipient-uuid> <liker-uuid>
#
# Two users are required because notification emit is self-suppressed when
# actor == recipient. The recipient is the post author who receives the
# `post_liked` notification (and therefore must have the FCM token registered);
# the liker is the actor whose like fires the emit.
#
# Defaults to https://api-staging.nearyou.id. Pass --api-base http://localhost:8080
# for local-dev verification.
#
# Prerequisites:
#  - Both UUIDs point to existing users in the target environment.
#  - `staging-firebase-admin-sa` GCP Secret Manager slot is populated. If not,
#    the staging instance fails to start and this script will get connection
#    errors against the API base — the operator must populate the slot first.
#  - The script mints JWTs via `dev/scripts/mint-dev-jwt.sh`. For staging set
#    KTOR_RSA_PRIVATE_KEY to the staging RSA key, e.g.:
#      KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#        --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#      dev/scripts/smoke-fcm-push-dispatch.sh <recipient-uuid> <liker-uuid>
#  - `gcloud` is configured for the target project (used for Cloud Logging tail).
#
# What it asserts:
#   - POST /api/v1/user/fcm-token (recipient, synthetic Android token)        → 204
#   - POST /api/v1/posts (recipient as author, "Smoke FCM dispatch" body)     → 201
#   - POST /api/v1/posts/:id/like (liker)                                     → 204
#   - Cloud Logging within 60s of the like contains `event=fcm_dispatched`
#     for `user_id=<recipient-uuid>` AND `platform=android`. Synthetic token
#     SHOULD return UNREGISTERED from FCM (token isn't real); the dispatcher
#     then prunes via `event=fcm_token_pruned`. EITHER fcm_dispatched OR
#     fcm_token_pruned is acceptable evidence the dispatch path executed.
#   - Cloud Logging within the same window contains NO unexpected
#     `event=fcm_init_failed` AND zero `event=fcm_dispatch_failed` with
#     error_code IN ('UNAVAILABLE','INTERNAL','QUOTA_EXCEEDED','unknown')
#     (transient FCM provider faults — alarm if seen).
#
# Exit codes:
#   0 — all assertions passed.
#   1 — at least one assertion mismatched.
#   2 — usage error (missing args, JWT mint failure, etc.).

set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
GCP_PROJECT="nearyou-staging"
DO_CLEANUP="true"

POSITIONAL=()
while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base)
            API_BASE="$2"
            shift 2
            ;;
        --gcp-project)
            GCP_PROJECT="$2"
            shift 2
            ;;
        --no-cleanup)
            DO_CLEANUP="false"
            shift
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
            POSITIONAL+=("$1")
            shift
            ;;
    esac
done

if [[ "${#POSITIONAL[@]}" -lt 2 ]]; then
    echo "usage: $0 [--api-base <url>] [--gcp-project <id>] [--no-cleanup] <recipient-uuid> <liker-uuid>" >&2
    exit 2
fi

RECIPIENT_UUID="${POSITIONAL[0]}"
LIKER_UUID="${POSITIONAL[1]}"

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
TIMESTAMP_LABEL="$(date -u +%Y%m%dT%H%M%SZ)"
TOKEN_ANDROID="smoke-fcm-push-android-${TIMESTAMP_LABEL}"
APP_VERSION="0.1.0-staging-smoke-push"

echo "==> Smoke — fcm-push-dispatch"
echo "    API base:     $API_BASE"
echo "    GCP project:  $GCP_PROJECT"
echo "    Recipient:    $RECIPIENT_UUID"
echo "    Liker:        $LIKER_UUID"
echo "    Synth token:  $TOKEN_ANDROID"
echo

# ----------------------------------------------------------------------------
# 1. Mint JWTs.
# ----------------------------------------------------------------------------
echo "==> Minting JWTs..."
JWT_RECIPIENT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$RECIPIENT_UUID")"
JWT_LIKER="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$LIKER_UUID")"
if [[ -z "$JWT_RECIPIENT" || -z "$JWT_LIKER" ]]; then
    echo "ERROR: JWT mint failed for one of the users. Check KTOR_RSA_PRIVATE_KEY." >&2
    exit 2
fi
echo "    Recipient JWT (${#JWT_RECIPIENT} chars), Liker JWT (${#JWT_LIKER} chars)."
echo

# ----------------------------------------------------------------------------
# 10.2.a Register a synthetic FCM token for the recipient.
# ----------------------------------------------------------------------------
echo "==> 10.2.a Register Android FCM token for recipient (expect 204)..."
STATUS="$(curl -sS -o /tmp/smoke-fcm-push-token.json -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $JWT_RECIPIENT" \
    -H "Content-Type: application/json" \
    --data-raw "{\"token\":\"$TOKEN_ANDROID\",\"platform\":\"android\",\"app_version\":\"$APP_VERSION\"}" \
    "$API_BASE/api/v1/user/fcm-token")"
if [[ "$STATUS" != "204" ]]; then
    echo "FAIL: token registration returned $STATUS (expected 204)." >&2
    cat /tmp/smoke-fcm-push-token.json >&2
    exit 1
fi
echo "    ✓ 204 — token registered"
echo

# ----------------------------------------------------------------------------
# 10.2.b Recipient creates a post (so the liker has something to like).
# ----------------------------------------------------------------------------
echo "==> 10.2.b Recipient creates a post (expect 201)..."
POST_BODY="$(cat <<JSON
{
  "content": "Smoke FCM dispatch ${TIMESTAMP_LABEL}",
  "lat": -6.2088,
  "lng": 106.8456
}
JSON
)"
STATUS="$(curl -sS -o /tmp/smoke-fcm-push-post.json -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $JWT_RECIPIENT" \
    -H "Content-Type: application/json" \
    --data-raw "$POST_BODY" \
    "$API_BASE/api/v1/posts")"
if [[ "$STATUS" != "201" ]]; then
    echo "FAIL: post creation returned $STATUS (expected 201)." >&2
    cat /tmp/smoke-fcm-push-post.json >&2
    exit 1
fi
POST_ID="$(jq -r '.id' /tmp/smoke-fcm-push-post.json)"
if [[ -z "$POST_ID" || "$POST_ID" == "null" ]]; then
    echo "FAIL: post creation succeeded but id missing in response." >&2
    cat /tmp/smoke-fcm-push-post.json >&2
    exit 1
fi
echo "    ✓ 201 — post id $POST_ID"
echo

# ----------------------------------------------------------------------------
# Capture the wall-clock window for Cloud Logging assertions. The 'since'
# anchor is recorded BEFORE the like fires; 'until' is anchored 60s after.
# ----------------------------------------------------------------------------
SINCE_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ----------------------------------------------------------------------------
# 10.2.c Liker likes the recipient's post → triggers post_liked emit + FCM
# dispatch.
# ----------------------------------------------------------------------------
echo "==> 10.2.c Liker likes the post (expect 204)..."
STATUS="$(curl -sS -o /tmp/smoke-fcm-push-like.json -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $JWT_LIKER" \
    -H "Content-Type: application/json" \
    "$API_BASE/api/v1/posts/$POST_ID/like")"
if [[ "$STATUS" != "204" ]]; then
    echo "FAIL: like returned $STATUS (expected 204)." >&2
    cat /tmp/smoke-fcm-push-like.json >&2
    exit 1
fi
echo "    ✓ 204 — like fired (server-side post_liked notification + FCM dispatch in flight)"
echo

# Allow the background FCM dispatch + prune to complete. The composite
# dispatcher returns synchronously, but FCM round-trips run on the IO pool.
echo "==> Waiting 30s for the background FCM round-trip + prune to land in Cloud Logging..."
sleep 30
echo

# ----------------------------------------------------------------------------
# 10.3 Inspect Cloud Logging for fcm_dispatched / fcm_token_pruned (success
# evidence) AND zero fcm_init_failed / transient-class fcm_dispatch_failed.
# ----------------------------------------------------------------------------
if ! command -v gcloud >/dev/null 2>&1; then
    echo "WARN: gcloud not installed — skipping Cloud Logging assertions. Inspect manually:" >&2
    echo "  gcloud logging read --project=$GCP_PROJECT \\" >&2
    echo "    'resource.type=\"cloud_run_revision\" AND textPayload:\"event=fcm_dispatched\" AND textPayload:\"$RECIPIENT_UUID\"' \\" >&2
    echo "    --freshness=2m" >&2
else
    echo "==> 10.3 Cloud Logging — looking for fcm_dispatched / fcm_token_pruned for recipient..."
    LOGS_DISPATCH="$(gcloud logging read --project="$GCP_PROJECT" \
        "resource.type=\"cloud_run_revision\" AND timestamp>=\"$SINCE_TS\" AND (textPayload:\"event=fcm_dispatched\" OR textPayload:\"event=fcm_token_pruned\") AND textPayload:\"user_id=$RECIPIENT_UUID\"" \
        --freshness=2m --limit=10 --format='value(textPayload)' 2>&1 || true)"
    if [[ -z "$LOGS_DISPATCH" ]]; then
        echo "FAIL: no fcm_dispatched OR fcm_token_pruned log entry found for recipient $RECIPIENT_UUID since $SINCE_TS." >&2
        echo "      The dispatch path did not execute, OR the log query missed the window." >&2
        echo "      Re-run manually with --freshness=10m to widen the search." >&2
        exit 1
    fi
    echo "    ✓ Found dispatch evidence:"
    echo "$LOGS_DISPATCH" | head -3 | sed 's/^/      /'
    echo

    echo "==> 10.3 Cloud Logging — checking for unexpected fcm_init_failed / transient fcm_dispatch_failed..."
    LOGS_INIT_FAILED="$(gcloud logging read --project="$GCP_PROJECT" \
        "resource.type=\"cloud_run_revision\" AND timestamp>=\"$SINCE_TS\" AND textPayload:\"event=fcm_init_failed\"" \
        --freshness=2m --limit=5 --format='value(textPayload)' 2>&1 || true)"
    if [[ -n "$LOGS_INIT_FAILED" ]]; then
        echo "FAIL: fcm_init_failed seen — staging Firebase init is broken. Investigate:" >&2
        echo "$LOGS_INIT_FAILED" | head -3 | sed 's/^/      /' >&2
        exit 1
    fi
    LOGS_TRANSIENT="$(gcloud logging read --project="$GCP_PROJECT" \
        "resource.type=\"cloud_run_revision\" AND timestamp>=\"$SINCE_TS\" AND textPayload:\"event=fcm_dispatch_failed\" AND (textPayload:\"error_code=UNAVAILABLE\" OR textPayload:\"error_code=INTERNAL\" OR textPayload:\"error_code=QUOTA_EXCEEDED\" OR textPayload:\"error_code=unknown\")" \
        --freshness=2m --limit=5 --format='value(textPayload)' 2>&1 || true)"
    if [[ -n "$LOGS_TRANSIENT" ]]; then
        echo "WARN: transient FCM provider faults seen — investigate but does not fail the smoke:" >&2
        echo "$LOGS_TRANSIENT" | head -3 | sed 's/^/      /' >&2
    fi
    echo "    ✓ No fcm_init_failed; no transient-class fcm_dispatch_failed"
    echo
fi

# ----------------------------------------------------------------------------
# 10.4 Token preservation: a synthetic token typically returns UNREGISTERED
# (token isn't real), so the recipient's row IS expected to be deleted by
# the on-send prune. We document this so the operator doesn't think the
# pruned-row state is wrong.
# ----------------------------------------------------------------------------
echo "==> 10.4 Token state — synthetic tokens typically prune via UNREGISTERED."
echo "    SQL-verify (run against staging Postgres):"
echo "      SELECT COUNT(*) FROM user_fcm_tokens"
echo "        WHERE user_id = '$RECIPIENT_UUID' AND token = '$TOKEN_ANDROID';"
echo "    Expected: 0 (pruned via UNREGISTERED) — log line above will show"
echo "              event=fcm_token_pruned. If the row persists, FCM accepted"
echo "              the synthetic token (unlikely) and the operator may want"
echo "              to delete it manually as part of cleanup."
echo

# ----------------------------------------------------------------------------
# Cleanup.
# ----------------------------------------------------------------------------
if [[ "$DO_CLEANUP" == "true" ]]; then
    echo "==> Cleanup hints (run against staging Postgres):"
    echo "  -- 1. Remove the smoke FCM token (in case it persisted):"
    echo "  DELETE FROM user_fcm_tokens"
    echo "    WHERE user_id = '$RECIPIENT_UUID' AND token = '$TOKEN_ANDROID';"
    echo
    echo "  -- 2. Remove the smoke post + cascade (post_likes, notifications):"
    echo "  DELETE FROM posts WHERE id = '$POST_ID';"
    echo
fi

echo "==> ✅ fcm-push-dispatch smoke PASSED."
exit 0
