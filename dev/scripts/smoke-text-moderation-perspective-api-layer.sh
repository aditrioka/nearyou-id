#!/usr/bin/env bash
# Smoke test for `text-moderation-perspective-api-layer` — verifies the staging
# deploy of Layer 3 (OpenAI Moderation API toxicity classifier wired as an
# async post-INSERT dispatch).
#
# Per `openspec/changes/text-moderation-perspective-api-layer/tasks.md` Phase 14:
#  - 14.5 Creates a test post with high-toxicity content, polls Cloud Logging
#    for the structured `event=layer3_high_score_applied` log line that
#    proves the orchestrator received a >0.8 aggregate score AND the
#    transactional UPDATE+INSERT DB write completed successfully.
#  - 14.6 Operator runbook reminder for baseline-latency comparison.
#  - 14.7 Operator runbook reminder for Sentry ERROR scan.
#
# Why Cloud Logging instead of direct psql polling: the staging Supabase
# Postgres is IPv6-only (Cloud Run has both stacks; this script runs from a
# laptop that typically does not). The orchestrator's
# `event=layer3_high_score_applied` log line is emitted AFTER the
# transactional writer succeeds — so its presence in Cloud Logging is
# equivalent to confirming the DB write. Operators MAY still verify directly
# via Supabase Studio or a Cloud Run psql shell per the runbook reminders below.
#
# Usage:
#   KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#     --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#     dev/scripts/smoke-text-moderation-perspective-api-layer.sh <user-uuid>
#
# Prerequisites:
#  - `staging-openai-api-key` secret slot provisioned in GCP Secret Manager
#    AND wired into `.github/workflows/deploy-staging.yml --set-secrets` as
#    `OPENAI_API_KEY=staging-openai-api-key:latest`. Without this, the Cloud
#    Run boot fails-fast at `event=layer3_init_failed reason=missing_secret`.
#  - `perspective_api_enabled = TRUE` in staging Firebase Remote Config Server
#    template. Flag name retains historical "perspective" branding (already
#    seeded in Firebase Console; underlying classifier is now OpenAI). Idempotent
#    verification per Pre-Phase 1 §18.
#  - The user-uuid references an existing Free user in staging.
#  - The user has NOT exhausted today's posts cap.
#  - `gcloud` CLI installed + `gcloud auth login` configured.
#  - `jq` installed for JSON parsing.
#
# Exit codes:
#   0 — all automated assertions passed.
#   1 — smoke failed: at least one assertion mismatched.
#   2 — usage error.

set -euo pipefail

API_BASE="${API_BASE:-https://api-staging.nearyou.id}"
GCP_PROJECT="${GCP_PROJECT:-nearyou-staging}"
# Window the orchestrator-side DB write should land in Cloud Logging.
# The orchestrator's 500ms `withTimeoutOrNull` budget + the queue INSERT + the
# JSON-encoding log emit + Cloud Logging shipping ≈ 1–3s p95. 15s budget
# accommodates outliers (Cloud Logging ingestion lag, slow OpenAI Moderation response).
DISPATCH_WAIT_SECONDS="${DISPATCH_WAIT_SECONDS:-15}"

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <user-uuid>" >&2
    exit 2
fi

if [[ -z "${KTOR_RSA_PRIVATE_KEY:-}" ]]; then
    echo "error: KTOR_RSA_PRIVATE_KEY env var is required (mint via gcloud secrets versions access)" >&2
    exit 2
fi

if ! command -v gcloud >/dev/null 2>&1; then
    echo "error: gcloud not installed — required for Cloud Logging assertions." >&2
    exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
    echo "error: jq not installed — required for JSON parsing." >&2
    exit 2
fi

USER_UUID="$1"

# Smoke sentinel content — a phrase the upstream classifier (OpenAI Moderation
# `omni-moderation-latest`) scores well above 0.8 on harassment+threatening +
# violence categories but that's clearly a test (so operators reading the staging
# moderation_queue don't mistake it for a real harassment incident). The
# "SMOKE-TEST" prefix makes the test origin explicit; the rest is a generic
# hostile phrase OpenAI Moderation consistently scores >0.9. Operators MAY clean
# these test rows up periodically via the queue cleanup script.
HIGH_TOX_MESSAGE="SMOKE-TEST Layer 3: kill yourself you idiot, this is automated test content"

# Mint a JWT for the test user via the canonical helper (mirrors other smoke scripts).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JWT="$("$SCRIPT_DIR/mint-dev-jwt.sh" "$USER_UUID")"
if [[ -z "$JWT" ]]; then
    echo "error: failed to mint JWT for user $USER_UUID — check KTOR_RSA_PRIVATE_KEY" >&2
    exit 1
fi

assert_status() {
    local expected="$1"
    local actual="$2"
    local description="$3"
    if [[ "$actual" != "$expected" ]]; then
        echo "FAIL: $description — expected status $expected, got $actual" >&2
        exit 1
    fi
    echo "PASS: $description (status $actual)"
}

# Captures the timestamp BEFORE the POST so the Cloud Logging query window
# only sees the log lines emitted by this smoke invocation. Format matches
# gcloud's accepted RFC 3339 form (`YYYY-MM-DDTHH:MM:SS.fffZ`).
SINCE_TS="$(date -u +'%Y-%m-%dT%H:%M:%S.000Z')"

echo "=== smoke: POST /api/v1/posts with high-toxicity sentinel content ==="
echo "INFO: api_base=$API_BASE  user_uuid=$USER_UUID  since_ts=$SINCE_TS"

RESP="$(curl --silent --show-error --write-out '\n%{http_code}' \
    --request POST "$API_BASE/api/v1/posts" \
    --header "Authorization: Bearer $JWT" \
    --header "Content-Type: application/json" \
    --data "{\"content\":\"$HIGH_TOX_MESSAGE\",\"latitude\":-6.2,\"longitude\":106.8}")"
STATUS="$(echo "$RESP" | tail -1)"
# `sed '$d'` deletes the last line (the status code appended via curl --write-out).
# POSIX-portable; macOS `head -n -1` doesn't support negative counts.
BODY="$(echo "$RESP" | sed '$d')"
assert_status 201 "$STATUS" "high-toxicity post → 201 (Layer 1+2 allow; Layer 3 async)"
POST_ID="$(echo "$BODY" | jq -r .id)"
if [[ -z "$POST_ID" || "$POST_ID" == "null" ]]; then
    echo "FAIL: response body did not contain a post id." >&2
    echo "      body: $BODY" >&2
    exit 1
fi
echo "INFO: created post $POST_ID"

# Layer 3 is fire-and-forget. The orchestrator's 500ms withTimeoutOrNull budget
# bounds the per-dispatch latency; Cloud Logging shipping adds 1–3s p95. Wait
# long enough that the log line will be queryable.
echo
echo "==> Waiting ${DISPATCH_WAIT_SECONDS}s for the async Layer 3 dispatch + Cloud Logging ingestion..."
sleep "$DISPATCH_WAIT_SECONDS"
echo

# ----------------------------------------------------------------------------
# Cloud Logging assertion: orchestrator emitted `event=layer3_high_score_applied`
# for the target. This proves: (a) the upstream classifier (OpenAI Moderation)
# returned an aggregate score >0.8;
# (b) the transactional UPDATE posts SET is_auto_hidden = TRUE + INSERT INTO
# moderation_queue completed (the log emits AFTER the writer succeeds per
# DefaultLayer3Moderator.applyAutoHide).
# ----------------------------------------------------------------------------
echo "=== smoke: Cloud Logging — looking for layer3_high_score_applied for post $POST_ID ==="

LOGS_HIGH="$(gcloud logging read --project="$GCP_PROJECT" \
    "resource.type=\"cloud_run_revision\" AND timestamp>=\"$SINCE_TS\" AND textPayload:\"event=layer3_high_score_applied\" AND textPayload:\"target_id=$POST_ID\"" \
    --freshness=5m --limit=10 --format='value(textPayload)' 2>&1 || true)"

if [[ -z "$LOGS_HIGH" ]]; then
    echo "FAIL: no layer3_high_score_applied log entry found for target_id=$POST_ID since $SINCE_TS." >&2
    echo "      Possible causes:" >&2
    echo "        1. OpenAI Moderation scored the content <= 0.8 (smoke phrase is no longer triggering)." >&2
    echo "        2. perspective_api_enabled = FALSE in staging Remote Config (flag name historical; toggle still works)." >&2
    echo "        3. OPENAI_API_KEY missing or invalid — check for event=layer3_dispatch_failed." >&2
    echo "        4. Cloud Logging ingestion lag exceeded ${DISPATCH_WAIT_SECONDS}s — bump DISPATCH_WAIT_SECONDS." >&2
    echo
    echo "      Diagnostic queries:" >&2
    echo "        gcloud logging read --project=$GCP_PROJECT --freshness=5m \\" >&2
    echo "          'resource.type=\"cloud_run_revision\" AND textPayload:\"target_id=$POST_ID\"'" >&2
    exit 1
fi
echo "PASS: orchestrator wrote AutoHide outcome for post $POST_ID"
echo "$LOGS_HIGH" | head -3 | sed 's/^/      /'
echo

# ----------------------------------------------------------------------------
# Cloud Logging assertion: NO unexpected init failures or kill-switch ERROR
# events since the smoke window opened.
# ----------------------------------------------------------------------------
echo "=== smoke: Cloud Logging — verifying no layer3_init_failed / kill_switch_unavailable ERROR events ==="
LOGS_INIT_FAILED="$(gcloud logging read --project="$GCP_PROJECT" \
    "resource.type=\"cloud_run_revision\" AND timestamp>=\"$SINCE_TS\" AND textPayload:\"event=layer3_init_failed\"" \
    --freshness=5m --limit=5 --format='value(textPayload)' 2>&1 || true)"
if [[ -n "$LOGS_INIT_FAILED" ]]; then
    echo "FAIL: layer3_init_failed seen — OpenAI Moderation API key is missing or invalid." >&2
    echo "$LOGS_INIT_FAILED" | head -3 | sed 's/^/      /' >&2
    exit 1
fi
echo "PASS: no layer3_init_failed entries (Cloud Run boot succeeded)"

LOGS_KILLSWITCH_ERR="$(gcloud logging read --project="$GCP_PROJECT" \
    "resource.type=\"cloud_run_revision\" AND timestamp>=\"$SINCE_TS\" AND textPayload:\"event=layer3_kill_switch_unavailable\"" \
    --freshness=5m --limit=5 --format='value(textPayload)' 2>&1 || true)"
if [[ -n "$LOGS_KILLSWITCH_ERR" ]]; then
    echo "WARN: layer3_kill_switch_unavailable seen — Firebase Remote Config read failed during this window." >&2
    echo "      Layer 3 fails OPEN (enabled=true) on this path, but operators MUST investigate Remote Config availability." >&2
    echo "$LOGS_KILLSWITCH_ERR" | head -3 | sed 's/^/      /' >&2
    # Not a smoke failure — Decision 12 is fail-OPEN. Just surface for operator review.
fi
echo

# ----------------------------------------------------------------------------
# Reminder summary for manual operator verifications (tasks 14.6, 14.7, +
# direct DB inspection).
# ----------------------------------------------------------------------------
echo "=== ALL AUTOMATED SMOKE STEPS PASSED ==="
echo
echo "Manual operator verifications (Section 14.6 + 14.7):"
echo "  1. Direct DB inspection (Supabase Studio or Cloud Run psql shell, since"
echo "     Supabase Postgres is IPv6-only — direct laptop psql won't reach it):"
echo "       SELECT id, is_auto_hidden, deleted_at FROM posts WHERE id = '$POST_ID';"
echo "       -- expected: is_auto_hidden = TRUE, deleted_at = NULL"
echo "       SELECT target_type, target_id, trigger, status, priority"
echo "         FROM moderation_queue"
echo "         WHERE target_id = '$POST_ID' AND trigger = 'perspective_api_high_score';"
echo "       -- expected: exactly ONE row, status = 'pending', priority = 5"
echo
echo "  2. Latency baseline (task 14.6): compare POST /api/v1/posts p95 in the"
echo "     staging Grafana dashboard against the pre-Layer-3 baseline. Layer 3"
echo "     is fire-and-forget so the user-visible latency MUST NOT regress."
echo "     Expected delta: ≤ 10ms (cost of the dispatch call only)."
echo
echo "  3. Sentry ERROR scan (task 14.7): confirm Sentry shows zero unexpected"
echo "     layer3_threshold_misconfigured / layer3_kill_switch_unavailable"
echo "     ERROR events from the smoke window. WARN-level layer3_dispatch_failed"
echo "     events are expected if anyone exercised the timeout/5xx test cases."
echo
echo "  4. Cleanup (optional): delete the smoke post + queue row to keep the"
echo "     staging moderation_queue tidy:"
echo "       DELETE FROM moderation_queue WHERE target_id = '$POST_ID';"
echo "       DELETE FROM posts WHERE id = '$POST_ID';"
exit 0
