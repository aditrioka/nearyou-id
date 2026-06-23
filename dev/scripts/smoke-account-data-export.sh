#!/usr/bin/env bash
# Smoke for account-data-export against a staging deploy.
#
# Unlike the no-creds gate smoke (smoke-account-deletion-tombstone.sh, which only asserts
# routes are mounted + auth-gated), this is the FULL authenticated producer loop: enqueue an
# export, drive the worker, wait for `ready`, confirm the in-app notification carries the signed
# URL, and download the ZIP. It is the end-to-end deploy-config check (R2 + Resend secret-slot
# drift, the Cloud-Scheduler OIDC gate, the 24h signed-URL TTL, the bucket lifecycle) — the
# integration tests cover correctness; this proves the deployed stack is wired together.
#
# ─── PREREQUISITES (operator infra — this script does NOT provision any of it) ───────────────
#   Object storage (Cloudflare R2):
#     * an export bucket with a 24h object-lifecycle (expire/delete) rule, so a past-TTL or
#       leaked signed URL resolves to nothing (spec § "The download link is time-limited …").
#     * secret slots:  r2-account-id, r2-access-key-id, r2-secret-access-key, r2-export-bucket
#       (env-namespaced, e.g. `staging-r2-export-bucket`; see infra/r2/…/R2Config.kt).
#   Email (Resend):
#     * a verified sending domain.
#     * secret slot:   resend-api-key   (the email is best-effort; its failure does NOT fail the
#                      export — the in-app notification is the durable channel, so the smoke
#                      asserts the NOTIFICATION, not the email).
#   Peer-id HMAC:
#     * secret slot:   export-peer-hash-secret  (keyed HMAC for peer references in the archive).
#   Worker trigger:
#     * the production trigger is Cloud Scheduler → `POST /internal/data-export-worker` with a
#       Google-OIDC identity token whose audience == the API base URL. This script mints that
#       token locally with `gcloud auth print-identity-token --audiences=<API_BASE>` (the
#       invoking principal needs roles/run.invoker on the service); set TRIGGER_WORKER=0 to skip
#       the worker push and rely on the real Cloud Scheduler cadence instead (then the poll in
#       step 4 just waits for the scheduled run).
#   Test user:
#     * an existing staging users.id (NOT banned/suspended). Defaults to the canonical
#       smoke-test user; override via arg 1. The JWT is minted via mint-dev-jwt.sh with the
#       staging RSA key (mirrors smoke-premium-search.sh) — see step 1.
#
# Usage:
#   dev/scripts/smoke-account-data-export.sh [--api-base <url>] [<user-uuid>]
#     --api-base   defaults to https://api-staging.nearyou.id (pass http://localhost:8080 for dev)
#     <user-uuid>  defaults to the canonical staging smoke-test user
#   Env:
#     KTOR_RSA_PRIVATE_KEY   staging RSA signing key for the JWT mint (see step 1).
#     TRIGGER_WORKER=0       skip the OIDC worker push; wait for the scheduled run instead.
#     POLL_TRIES / POLL_SLEEP   ready-poll bound (default 10 × 3s).
#
# Exit codes: 0 pass · 1 assertion failed · 2 usage / mint / prerequisite error.
set -euo pipefail

API_BASE="https://api-staging.nearyou.id"
# Canonical staging smoke-test user (smoketest_adi); mirror mint-staging-jwt.sh's default.
USER_UUID="986142e3-0f12-43fd-92a5-cf40e1b70bd4"
TRIGGER_WORKER="${TRIGGER_WORKER:-1}"
POLL_TRIES="${POLL_TRIES:-10}"
POLL_SLEEP="${POLL_SLEEP:-3}"

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base) API_BASE="$2"; shift 2 ;;
        --help|-h)  sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;;
        --*)        echo "Unknown flag: $1" >&2; exit 2 ;;
        *)          USER_UUID="$1"; shift ;;
    esac
done

# Colored pass/fail output (mirrors the sibling smokes' style; degrades to plain if not a TTY).
if [[ -t 1 ]]; then GRN=$'\033[0;32m'; RED=$'\033[0;31m'; YEL=$'\033[0;33m'; NC=$'\033[0m'; else GRN=""; RED=""; YEL=""; NC=""; fi
ok()   { echo "  ${GRN}ok${NC}   $*"; }
warn() { echo "  ${YEL}note${NC} $*"; }
die()  { echo "  ${RED}FAIL${NC} $*" >&2; exit 1; }

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

echo "== account-data-export smoke @ $API_BASE =="
echo "   user: $USER_UUID"
echo

# ----------------------------------------------------------------------------
# 0. App boots + DB reachable (the V30 data_export_requests migration applied).
# ----------------------------------------------------------------------------
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$API_BASE/health/ready" || echo "000")
[[ "$HEALTH" == "200" ]] || die "health/ready → $HEALTH (want 200; deploy down or migration failed)"
ok "health ready (200)"

# ----------------------------------------------------------------------------
# 1. Mint a JWT for the test user (staging RSA key via KTOR_RSA_PRIVATE_KEY).
#    Mirrors smoke-premium-search.sh step 1.
# ----------------------------------------------------------------------------
JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$USER_UUID")"
[[ -n "$JWT" ]] || { echo "ERROR: JWT mint failed (check KTOR_RSA_PRIVATE_KEY)." >&2; exit 2; }
AUTH=(-H "Authorization: Bearer $JWT")
ok "JWT minted (${#JWT} chars)"
echo

# ----------------------------------------------------------------------------
# 2. POST /api/v1/account/export → 202 {requestId, status=pending}; re-POST is
#    idempotent (same requestId, no 2nd active row).
#    Wire DTO is camelCase: {requestId, status}.
# ----------------------------------------------------------------------------
RESP1=$(mktemp)
CODE=$(curl -s -o "$RESP1" -w "%{http_code}" --max-time 15 "${AUTH[@]}" -X POST "$API_BASE/api/v1/account/export")
[[ "$CODE" == "202" ]] || { cat "$RESP1" >&2; die "POST export → $CODE (want 202)"; }
REQ_ID=$(jq -r '.requestId' "$RESP1")
STATUS=$(jq -r '.status' "$RESP1")
[[ -n "$REQ_ID" && "$REQ_ID" != "null" ]] || die "POST export returned no requestId"
[[ "$STATUS" == "pending" ]] || warn "first POST status=$STATUS (expected pending; a prior request may already be processing)"
ok "POST export → 202 (requestId=$REQ_ID, status=$STATUS)"

RESP2=$(mktemp)
CODE=$(curl -s -o "$RESP2" -w "%{http_code}" --max-time 15 "${AUTH[@]}" -X POST "$API_BASE/api/v1/account/export")
[[ "$CODE" == "202" ]] || { cat "$RESP2" >&2; die "re-POST export → $CODE (want 202)"; }
REQ_ID2=$(jq -r '.requestId' "$RESP2")
[[ "$REQ_ID2" == "$REQ_ID" ]] || die "re-POST returned a DIFFERENT requestId ($REQ_ID2 ≠ $REQ_ID) — idempotency/single-active broken"
ok "re-POST export → same requestId (idempotent, no 2nd active row)"
echo

# ----------------------------------------------------------------------------
# 3. Trigger the worker: POST /internal/data-export-worker with a Google-OIDC
#    identity token (audience == API base). Mirrors how Cloud Scheduler invokes
#    the /internal/* workers. Skippable via TRIGGER_WORKER=0.
# ----------------------------------------------------------------------------
if [[ "$TRIGGER_WORKER" == "1" ]]; then
    OIDC=$(gcloud auth print-identity-token --audiences="$API_BASE" 2>/dev/null || true)
    if [[ -z "$OIDC" ]]; then
        warn "no OIDC token (gcloud auth print-identity-token failed) — skipping worker push; relying on the scheduled run"
    else
        WRESP=$(mktemp)
        CODE=$(curl -s -o "$WRESP" -w "%{http_code}" --max-time 60 \
            -H "Authorization: Bearer $OIDC" -X POST "$API_BASE/internal/data-export-worker")
        [[ "$CODE" == "200" ]] || { cat "$WRESP" >&2; die "worker POST → $CODE (want 200; check OIDC audience + run.invoker IAM)"; }
        ok "worker dispatched (200, processed=$(jq -r '.processed // "?"' "$WRESP") ready=$(jq -r '.ready // "?"' "$WRESP"))"
    fi
else
    warn "TRIGGER_WORKER=0 — not pushing the worker; waiting for the Cloud Scheduler cadence"
fi
echo

# ----------------------------------------------------------------------------
# 4. Poll GET /api/v1/account/export until status=ready (bounded). Then assert
#    downloadExpiresAt ≈ now+24h. Wire DTO: {status, downloadExpiresAt, downloadUrl}.
# ----------------------------------------------------------------------------
SRESP=$(mktemp)
READY=0
for ((i = 1; i <= POLL_TRIES; i++)); do
    curl -s -o "$SRESP" --max-time 15 "${AUTH[@]}" "$API_BASE/api/v1/account/export" || true
    ST=$(jq -r '.status' "$SRESP" 2>/dev/null || echo "")
    if [[ "$ST" == "ready" ]]; then READY=1; break; fi
    if [[ "$ST" == "failed" || "$ST" == "expired" ]]; then die "status read returned terminal '$ST' (worker did not produce a ready export)"; fi
    echo "   …poll $i/$POLL_TRIES status=$ST"
    sleep "$POLL_SLEEP"
done
[[ "$READY" == "1" ]] || die "export not ready after $((POLL_TRIES * POLL_SLEEP))s (last status=$ST)"

EXPIRES=$(jq -r '.downloadExpiresAt' "$SRESP")
[[ -n "$EXPIRES" && "$EXPIRES" != "null" ]] || die "ready export has no downloadExpiresAt"
# downloadExpiresAt ≈ now + 24h: assert it lands within [now+23h, now+25h] (date parse is
# best-effort across GNU/BSD; on parse failure we just confirm the field is present + non-empty).
if NOW_S=$(date -u +%s 2>/dev/null) && EXP_S=$(date -u -d "$EXPIRES" +%s 2>/dev/null || date -u -j -f "%Y-%m-%dT%H:%M:%S" "${EXPIRES%%.*}" +%s 2>/dev/null); then
    DELTA=$(( EXP_S - NOW_S ))
    if (( DELTA >= 82800 && DELTA <= 90000 )); then
        ok "status=ready, downloadExpiresAt ≈ now+24h (Δ=${DELTA}s)"
    else
        die "downloadExpiresAt Δ=${DELTA}s is outside [23h,25h] of now (expected ≈ 86400s)"
    fi
else
    ok "status=ready, downloadExpiresAt present ($EXPIRES) [Δ not range-checked — date parse unavailable]"
fi
echo

# ----------------------------------------------------------------------------
# 5. Assert a data_export_ready notification exists with a signed_url in body_data.
#    Notifications response: {items: [{type, body_data:{signed_url, expires_at}, …}], …}.
# ----------------------------------------------------------------------------
NRESP=$(mktemp)
CODE=$(curl -s -o "$NRESP" -w "%{http_code}" --max-time 15 "${AUTH[@]}" "$API_BASE/api/v1/notifications?limit=50")
[[ "$CODE" == "200" ]] || { cat "$NRESP" >&2; die "GET notifications → $CODE (want 200)"; }
SIGNED_URL=$(jq -r 'first(.items[] | select(.type == "data_export_ready") | .body_data.signed_url) // empty' "$NRESP")
[[ -n "$SIGNED_URL" ]] || { jq -r '.items[].type' "$NRESP" >&2; die "no data_export_ready notification with body_data.signed_url found"; }
ok "data_export_ready notification present (body_data carries signed_url)"
echo

# ----------------------------------------------------------------------------
# 6. Download the signed URL → 200 + a non-empty application/zip.
#    Prefer the notification's signed_url; fall back to the GET status downloadUrl.
# ----------------------------------------------------------------------------
DL_URL="$SIGNED_URL"
[[ -n "$DL_URL" && "$DL_URL" != "null" ]] || DL_URL=$(jq -r '.downloadUrl' "$SRESP")
[[ -n "$DL_URL" && "$DL_URL" != "null" ]] || die "no signed download URL to fetch"
ZIP=$(mktemp)
DLHDR=$(mktemp)
CODE=$(curl -s -o "$ZIP" -D "$DLHDR" -w "%{http_code}" --max-time 60 "$DL_URL")
[[ "$CODE" == "200" ]] || die "signed URL GET → $CODE (want 200; bucket lifecycle may have already expired it, or TTL drift)"
CTYPE=$(grep -i '^content-type:' "$DLHDR" | tr -d '\r' | awk '{print $2}' || true)
SIZE=$(wc -c < "$ZIP" | tr -d ' ')
[[ "$SIZE" -gt 0 ]] || die "downloaded archive is EMPTY (0 bytes)"
# Magic-byte check (PK\x03\x04) is the strongest cross-platform proof it's a real ZIP; the
# Content-Type header is a softer signal (R2 may serve application/octet-stream).
if [[ "$(head -c 2 "$ZIP")" == "PK" ]]; then
    ok "signed URL → 200, ${SIZE}B ZIP (PK magic; content-type=${CTYPE:-unset})"
else
    die "downloaded ${SIZE}B but it is not a ZIP (no PK magic; content-type=${CTYPE:-unset})"
fi
echo

# ----------------------------------------------------------------------------
# 7. Past-TTL → expired is a MANUAL / time-gated check (cannot be exercised in a
#    bounded smoke without waiting 24h). To verify: re-run GET /api/v1/account/export
#    for this request AFTER download_expires_at has passed → status should read
#    `expired` AND no fresh downloadUrl is handed out, and the signed URL above
#    should 403/404 once the bucket lifecycle has removed the object.
# ----------------------------------------------------------------------------
warn "past-TTL → 'expired' + dead signed URL is a manual/time-gated check (see step 7 comment)"

echo
echo "== ${GRN}SMOKE PASS${NC} =="
