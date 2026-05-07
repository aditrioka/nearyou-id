#!/usr/bin/env bash
# Smoke test for `content-moderation-keyword-lists` — verifies the staging deploy
# of the text moderation pipeline (KeywordMatcher + ModerationListLoader +
# TextModerator + integration into POST /api/v1/posts, replies, chat send).
#
# Per `openspec/changes/content-moderation-keyword-lists/tasks.md` Phase 11:
#  - 11.3 Smoke covers profanity-Reject, UU-ITE-Flag, Allowed for posts +
#    replies + chat send.
#  - 11.5 Verifies the boot-time loader-priming WARN/INFO events for the
#    placeholder fallback files (Sentry-side, manual check).
#  - 11.6 Fail-open verification: clear staging Remote Config to [] and
#    confirm a profanity-sentinel post returns 201 (manual operator step).
#
# Usage:
#   KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#     --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#     dev/scripts/smoke-content-moderation-keyword-lists.sh <user-uuid>
#
# The script ASSUMES staging Remote Config has been primed with sentinel values
# in the SERVER template tab (NOT Client) per tasks.md 11.4:
#   moderation_profanity_list  = ["sentinel-profanity"]
#   moderation_uu_ite_list     = ["sentinel-uuite-1", "sentinel-uuite-2", "sentinel-uuite-3"]
#   moderation_match_threshold = 3
#
# Backend reads via FirebaseRemoteConfig.getServerTemplate() — the Server template
# is the architectural home for backend-evaluated parameters per Firebase's
# official server-side Remote Config guidance (SDK 9.7.0+).
#
# Prerequisites:
#  - The user-uuid references an existing Free user in staging.
#  - The user has NOT exhausted today's posts/replies/chat-send caps.
#  - Cloud Run staging revision is the content-moderation-keyword-lists deploy.
#  - `gcloud auth application-default login` configured (used to mint a JWT).
#  - `jq` installed (used for JSON parsing in assertions).
#
# Exit codes:
#   0 — all automated assertions passed.
#   1 — smoke failed: at least one assertion mismatched.
#   2 — usage error.

set -euo pipefail

API_BASE="${API_BASE:-https://api-staging.nearyou.id}"

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <user-uuid>" >&2
    exit 2
fi

if [[ -z "${KTOR_RSA_PRIVATE_KEY:-}" ]]; then
    echo "error: KTOR_RSA_PRIVATE_KEY env var is required (mint via gcloud secrets versions access)" >&2
    exit 2
fi

USER_UUID="$1"
SENTINEL_PROFANITY="sentinel-profanity"
SENTINEL_UUITE_1="sentinel-uuite-1"
SENTINEL_UUITE_2="sentinel-uuite-2"
SENTINEL_UUITE_3="sentinel-uuite-3"

# JWT minting helper — reuses the same dev/MintDevJwt main class that the other
# smoke scripts use. The -t flag emits the access token to stdout.
mint_jwt() {
    local user_id="$1"
    cd "$(dirname "$0")/../.."
    KTOR_RSA_PRIVATE_KEY="$KTOR_RSA_PRIVATE_KEY" \
        ./gradlew --quiet :backend:ktor:run \
            --args="dev-mint-jwt $user_id" 2>/dev/null \
        | grep -E '^eyJ' | head -1
}

JWT="$(mint_jwt "$USER_UUID")"
if [[ -z "$JWT" ]]; then
    echo "error: failed to mint JWT for user $USER_UUID" >&2
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

assert_json_field() {
    local expected="$1"
    local body="$2"
    local jq_path="$3"
    local description="$4"
    local actual
    actual="$(echo "$body" | jq -r "$jq_path")"
    if [[ "$actual" != "$expected" ]]; then
        echo "FAIL: $description — expected '$expected' at $jq_path, got '$actual'" >&2
        echo "      body: $body" >&2
        exit 1
    fi
    echo "PASS: $description ($jq_path = '$actual')"
}

post_json() {
    local path="$1"
    local body="$2"
    curl --silent --show-error --write-out '\n%{http_code}' \
        --request POST "$API_BASE$path" \
        --header "Authorization: Bearer $JWT" \
        --header "Content-Type: application/json" \
        --data "$body"
}

echo "=== smoke: POST /api/v1/posts profanity-sentinel ==="
RESP="$(post_json /api/v1/posts \
    "{\"content\":\"$SENTINEL_PROFANITY hits the blocklist\",\"latitude\":-6.2,\"longitude\":106.8}")"
STATUS="$(echo "$RESP" | tail -1)"
BODY="$(echo "$RESP" | head -n -1)"
assert_status 400 "$STATUS" "profanity post → 400"
assert_json_field "content_moderated_profanity" "$BODY" ".error.code" "profanity post → error.code"

echo "=== smoke: POST /api/v1/posts UU-ITE-sentinel (3 hits) ==="
RESP="$(post_json /api/v1/posts \
    "{\"content\":\"$SENTINEL_UUITE_1 plus $SENTINEL_UUITE_2 plus $SENTINEL_UUITE_3\",\"latitude\":-6.2,\"longitude\":106.8}")"
STATUS="$(echo "$RESP" | tail -1)"
BODY="$(echo "$RESP" | head -n -1)"
assert_status 201 "$STATUS" "UU ITE post → 201"
POST_ID="$(echo "$BODY" | jq -r .id)"
echo "INFO: created post $POST_ID — operator MUST confirm a moderation_queue row exists with target_id=$POST_ID, trigger='uu_ite_keyword_match' (psql via the Cloud Run JDBC tunnel or Supabase dashboard)."

echo "=== smoke: POST /api/v1/posts allowed content ==="
RESP="$(post_json /api/v1/posts \
    "{\"content\":\"halo dunia ini post yang baik dan biasa saja\",\"latitude\":-6.2,\"longitude\":106.8}")"
STATUS="$(echo "$RESP" | tail -1)"
BODY="$(echo "$RESP" | head -n -1)"
assert_status 201 "$STATUS" "allowed post → 201"
ALLOWED_POST_ID="$(echo "$BODY" | jq -r .id)"
echo "INFO: allowed post id = $ALLOWED_POST_ID — operator MUST confirm NO moderation_queue row exists for this id."

# Replies smoke
echo "=== smoke: POST /api/v1/posts/{post_id}/replies profanity-sentinel ==="
RESP="$(post_json "/api/v1/posts/$ALLOWED_POST_ID/replies" \
    "{\"content\":\"reply with $SENTINEL_PROFANITY in it\"}")"
STATUS="$(echo "$RESP" | tail -1)"
BODY="$(echo "$RESP" | head -n -1)"
assert_status 400 "$STATUS" "profanity reply → 400"
assert_json_field "content_moderated_profanity" "$BODY" ".error.code" "profanity reply → error.code"

echo "=== smoke: POST /api/v1/posts/{post_id}/replies UU-ITE-sentinel (3 hits) ==="
RESP="$(post_json "/api/v1/posts/$ALLOWED_POST_ID/replies" \
    "{\"content\":\"reply $SENTINEL_UUITE_1 and $SENTINEL_UUITE_2 and $SENTINEL_UUITE_3\"}")"
STATUS="$(echo "$RESP" | tail -1)"
BODY="$(echo "$RESP" | head -n -1)"
assert_status 201 "$STATUS" "UU ITE reply → 201"
REPLY_ID="$(echo "$BODY" | jq -r .id)"
echo "INFO: created reply $REPLY_ID — operator MUST confirm a moderation_queue row exists with target_id=$REPLY_ID, target_type='reply', trigger='uu_ite_keyword_match'."

# Chat send smoke
echo "=== smoke: chat send is INDIRECT — requires a 1:1 conversation handshake ==="
echo "INFO: chat-send moderation is structurally identical to posts/replies via the"
echo "      shared TextModerator + ContentModeratedProfanityException + Verdict.Flag"
echo "      writer to moderation_queue. The chat-send path is exercised by the"
echo "      backend integration tests (PostgreSQL + ChatService.sendMessage)."
echo "      A manual chat-send smoke would require seeding a recipient + creating"
echo "      a conversation; we defer that to the operator runbook in"
echo "      docs/07-Operations.md § Moderation Runbook."

# Sentry verification reminder
echo
echo "=== ALL AUTOMATED SMOKE STEPS PASSED ==="
echo "Manual operator verifications still required:"
echo "  1. Sentry shows boot-time loader-priming events for the placeholder fallback"
echo "     files (event=moderation_loader_boot_prime_complete env=staging)."
echo "  2. moderation_queue rows exist for posts $POST_ID + reply $REPLY_ID."
echo "  3. moderation_queue row does NOT exist for allowed post $ALLOWED_POST_ID."
echo "  4. Optional fail-open verification: clear staging moderation_profanity_list"
echo "     Remote Config to [] → POST a profanity-sentinel post → expect 201"
echo "     (fail-open works because empty list = no possible matches). Restore Remote"
echo "     Config; verify next request after 5-min TTL elapse rejects the same content."
exit 0
