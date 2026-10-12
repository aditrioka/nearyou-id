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
#       Google-OIDC identity token minted by the invoker SA `scheduler-invoker-<env>`. The #544
#       caller allowlist (INTERNAL_OIDC_ALLOWED_PRINCIPALS, deploy-staging.yml) rejects ANY other
#       principal with 403, your own `gcloud auth print-identity-token` included, so against
#       staging this script force-runs the real job (`gcloud scheduler jobs run $SCHEDULER_JOB`,
#       provisioned by provision-schedulers.sh) instead of calling the worker itself. The caller
#       needs roles/cloudscheduler.jobRunner (or Owner) on the project. The run is async, so
#       step 4's poll waits for the worker. The job defaults to staging's ONLY when --api-base is
#       the staging host. For any other target, set SCHEDULER_JOB + PROJECT_ID to that env's job,
#       or set OIDC_TOKEN to POST the worker directly (a local backend: a Google-signed token
#       minted with --include-email and audience = the API base, for an SA listed in that
#       backend's INTERNAL_OIDC_ALLOWED_PRINCIPALS); otherwise the script exits 2 before it
#       enqueues anything. Set TRIGGER_WORKER=0 to skip the trigger and wait for the
#       `45 * * * *` WIB cadence instead (the default poll bound then stretches to 66 min, and
#       the 15-min JWT is re-minted once on a 401 mid-poll).
#   Test user:
#     * an existing staging users.id (NOT banned/suspended). Defaults to the canonical
#       smoke-test user; override via arg 1. The JWT is minted via mint-dev-jwt.sh with the
#       staging RSA key (mirrors smoke-premium-search.sh) — see step 1. For staging:
#         KTOR_RSA_PRIVATE_KEY="$(gcloud secrets versions access latest \
#           --secret=staging-ktor-rsa-private-key --project=nearyou-staging)" \
#           dev/scripts/smoke-account-data-export.sh
#
# Usage:
#   dev/scripts/smoke-account-data-export.sh [--api-base <url>] [<user-uuid>]
#     --api-base   defaults to https://api-staging.nearyou.id (pass http://localhost:8080 for dev)
#     <user-uuid>  defaults to the canonical staging smoke-test user
#   Env:
#     KTOR_RSA_PRIVATE_KEY   staging RSA signing key for the JWT mint (see step 1).
#     TRIGGER_WORKER=0       skip the worker trigger; wait for the scheduled run instead.
#     SCHEDULER_JOB / PROJECT_ID / REGION   the job to force-run (defaults, staging --api-base
#                            only: nearyou-data-export-worker-staging / nearyou-staging;
#                            REGION asia-southeast1 everywhere).
#     OIDC_TOKEN             POST /internal/data-export-worker directly with this bearer instead
#                            of force-running a job (see "Worker trigger" above).
#     POLL_TRIES / POLL_SLEEP   ready-poll bound (default 40 × 6s = 4 min after a force-run;
#                            660 × 6s = 66 min with TRIGGER_WORKER=0, one full hourly cadence).
#
# Exit codes: 0 pass · 1 assertion failed · 2 usage / mint / prerequisite error.
set -euo pipefail

STAGING_API_BASE="https://api-staging.nearyou.id"
API_BASE="$STAGING_API_BASE"
# Canonical staging smoke-test user (smoketest_adi); mirror mint-staging-jwt.sh's default.
USER_UUID="986142e3-0f12-43fd-92a5-cf40e1b70bd4"
TRIGGER_WORKER="${TRIGGER_WORKER:-1}"
REGION="${REGION:-asia-southeast1}"
# A force-run is async (cold start + gather + R2 upload; 180s attempt deadline), so 4 min covers
# it. Without one, the next `45 * * * *` tick can be up to an hour away.
if [[ "$TRIGGER_WORKER" == "1" ]]; then POLL_TRIES="${POLL_TRIES:-40}"; else POLL_TRIES="${POLL_TRIES:-660}"; fi
POLL_SLEEP="${POLL_SLEEP:-6}"

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --api-base) API_BASE="$2"; shift 2 ;;
        --help|-h)  sed -n '2,/^$/p' "$0" | sed 's/^# \?//'; exit 0 ;;
        --*)        echo "Unknown flag: $1" >&2; exit 2 ;;
        *)          USER_UUID="$1"; shift ;;
    esac
done

# The default job is STAGING's: apply it only when the target is staging, so a dev/prod --api-base
# never force-runs the staging worker and then polls a backend that job never touches.
if [[ "$API_BASE" == "$STAGING_API_BASE" ]]; then
    SCHEDULER_JOB="${SCHEDULER_JOB:-nearyou-data-export-worker-staging}"
    PROJECT_ID="${PROJECT_ID:-nearyou-staging}"
fi
if [[ "$TRIGGER_WORKER" == "1" && -z "${OIDC_TOKEN:-}" && (-z "${SCHEDULER_JOB:-}" || -z "${PROJECT_ID:-}") ]]; then
    echo "ERROR: no worker trigger for $API_BASE. Set SCHEDULER_JOB + PROJECT_ID (that env's job)," >&2
    echo "       OIDC_TOKEN (direct POST; see --help), or TRIGGER_WORKER=0 (wait for its scheduler)." >&2
    exit 2
fi

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
# curl already writes 000 for an unreachable host; `|| true` only absorbs its non-zero exit
# (an `|| echo 000` fallback appended a second 000 → "000000").
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$API_BASE/health/ready" || true)
[[ "$HEALTH" == "200" ]] || die "health/ready → $HEALTH (want 200; deploy down or migration failed)"
ok "health ready (200)"

# ----------------------------------------------------------------------------
# 1. Mint a JWT for the test user (staging RSA key via KTOR_RSA_PRIVATE_KEY).
#    Mirrors smoke-premium-search.sh step 1.
# ----------------------------------------------------------------------------
# A non-local target means KTOR_RSA_PRIVATE_KEY is a REAL signing key: MINT_NO_DAEMON keeps it out
# of a long-lived Gradle daemon's environment (same guard as mint-staging-jwt.sh).
[[ "$API_BASE" == http://localhost* ]] || export MINT_NO_DAEMON=1
mint() {
    JWT="$("$repo_root/dev/scripts/mint-dev-jwt.sh" "$USER_UUID")"
    [[ -n "$JWT" ]] || { echo "ERROR: JWT mint failed (check KTOR_RSA_PRIVATE_KEY)." >&2; exit 2; }
    AUTH=(-H "Authorization: Bearer $JWT")
}
mint
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
# 3. Trigger the worker by force-running its Cloud Scheduler job, so the call carries
#    the allowlisted scheduler SA's OIDC token (a token you mint yourself gets 403 from
#    the #544 allowlist). Async: step 4 polls for the result. OIDC_TOKEN set → POST the
#    worker directly instead (synchronous). Skippable via TRIGGER_WORKER=0.
# ----------------------------------------------------------------------------
if [[ "$TRIGGER_WORKER" != "1" ]]; then
    warn "TRIGGER_WORKER=0 — not triggering the worker; waiting for the Cloud Scheduler cadence"
elif [[ -n "${OIDC_TOKEN:-}" ]]; then
    WRESP=$(mktemp)
    CODE=$(curl -s -o "$WRESP" -w "%{http_code}" --max-time 60 \
        -H "Authorization: Bearer $OIDC_TOKEN" -X POST "$API_BASE/internal/data-export-worker" || true)
    [[ "$CODE" == "200" ]] || { cat "$WRESP" >&2; die "worker POST → $CODE (want 200; 401 = token audience ≠ API base, 403 = principal not allowlisted)"; }
    ok "worker POSTed directly (200, processed=$(jq -r '.processed' "$WRESP") ready=$(jq -r '.ready' "$WRESP"))"
else
    gcloud scheduler jobs run "$SCHEDULER_JOB" --project="$PROJECT_ID" --location="$REGION" --quiet >/dev/null ||
        { echo "ERROR: gcloud scheduler jobs run $SCHEDULER_JOB failed (needs roles/cloudscheduler.jobRunner; or set TRIGGER_WORKER=0)." >&2; exit 2; }
    ok "scheduler job $SCHEDULER_JOB force-run (async; polling for the worker)"
fi
echo

# ----------------------------------------------------------------------------
# 4. Poll GET /api/v1/account/export until status=ready (bounded). Then assert
#    downloadExpiresAt ≈ now+24h. Wire DTO: {status, downloadExpiresAt, downloadUrl}.
# ----------------------------------------------------------------------------
SRESP=$(mktemp)
READY=0
ST=""
REMINTED=0
for ((i = 1; i <= POLL_TRIES; i++)); do
    CODE=$(curl -s -o "$SRESP" -w "%{http_code}" --max-time 15 "${AUTH[@]}" "$API_BASE/api/v1/account/export" || true)
    # The access JWT lives 15 min, so a cadence wait outlives it: re-mint and poll again. A 401
    # that survives a fresh mint (token_version bump, key rotation) is not expiry: stop.
    if [[ "$CODE" == "401" ]]; then
        [[ "$REMINTED" == "0" ]] || { echo "ERROR: status read → 401 even with a freshly minted JWT (token_version bump or signing-key mismatch)." >&2; exit 2; }
        mint
        REMINTED=1
        continue
    fi
    REMINTED=0
    case "$CODE" in
        403 | 404) cat "$SRESP" >&2; die "status read → HTTP $CODE (403 = test user suspended/banned, 404 = route not mounted)" ;;
    esac
    ST=$(jq -r '.status' "$SRESP" 2>/dev/null || echo "")
    if [[ "$ST" == "ready" ]]; then READY=1; break; fi
    if [[ "$ST" == "failed" || "$ST" == "expired" ]]; then die "status read returned terminal '$ST' (worker did not produce a ready export)"; fi
    echo "   …poll $i/$POLL_TRIES HTTP $CODE status=$ST"
    sleep "$POLL_SLEEP"
done
[[ "$READY" == "1" ]] || die "export not ready after $((POLL_TRIES * POLL_SLEEP))s (last HTTP $CODE, status=$ST)"

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
# 5. Assert THIS export's data_export_ready notification exists with a signed_url in
#    body_data. Notifications response: {items: [{type, body_data:{signed_url, expires_at}, …}], …}.
#    It is tied to this export by body_data.expires_at == the polled downloadExpiresAt (both are
#    the same download_expires_at column, same Instant.toString()), so an earlier run's still-live
#    notification cannot stand in for one this request never emitted.
# ----------------------------------------------------------------------------
NRESP=$(mktemp)
CODE=$(curl -s -o "$NRESP" -w "%{http_code}" --max-time 15 "${AUTH[@]}" "$API_BASE/api/v1/notifications?limit=50")
[[ "$CODE" == "200" ]] || { cat "$NRESP" >&2; die "GET notifications → $CODE (want 200)"; }
SIGNED_URL=$(jq -r --arg exp "$EXPIRES" \
    'first(.items[] | select(.type == "data_export_ready" and .body_data.expires_at == $exp) | .body_data.signed_url) // empty' "$NRESP")
[[ -n "$SIGNED_URL" ]] || { jq -r '.items[] | select(.type == "data_export_ready") | .body_data.expires_at' "$NRESP" >&2; die "no data_export_ready notification for THIS export (body_data.expires_at == $EXPIRES) with a signed_url"; }
ok "data_export_ready notification for this export present (expires_at matches; body_data carries signed_url)"
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
# Documented archive layout (docs/06 § Data Export Scope Matrix → DataExportArchiveService; the
# same list DataExportWorkerTest "8.8" pins). Entry NAMES only are printed, never contents.
ENTRIES=$(unzip -Z1 "$ZIP") || die "unzip could not read the archive's entry list"
while IFS= read -r e; do echo "        $e"; done <<<"$ENTRIES"
DOCUMENTED=$(printf '%s\n' profile.json username_history.csv posts.csv post_edits.csv likes.csv \
    replies.csv follows.csv blocks.csv chat_messages.csv reports.csv notifications.csv \
    moderation_actions.csv session_history.csv subscription_history.csv manifest.json README.txt)
# Set equality, both ways: "missing" = in $2 not $1, "extra" = in $1 not $2 (an extra archive
# entry could be out-of-scope data leaking, which DataExportWorkerTest 8.8 also pins absent).
setdiff() {
    echo "missing=[$(comm -13 <(sort <<<"$1") <(sort <<<"$2") | paste -sd, -)]" \
        "extra=[$(comm -23 <(sort <<<"$1") <(sort <<<"$2") | paste -sd, -)]"
}
[[ "$(sort <<<"$ENTRIES")" == "$(sort <<<"$DOCUMENTED")" ]] ||
    die "archive entries ≠ the 16 documented: $(setdiff "$ENTRIES" "$DOCUMENTED")"
MANIFEST_FILES=$(unzip -p "$ZIP" manifest.json | jq -er '.files[]') || die "manifest.json has no readable files array"
OTHER_ENTRIES=$(grep -vxF manifest.json <<<"$ENTRIES")
[[ "$(sort <<<"$MANIFEST_FILES")" == "$(sort <<<"$OTHER_ENTRIES")" ]] ||
    die "manifest.json files ≠ the archive's other entries: $(setdiff "$MANIFEST_FILES" "$OTHER_ENTRIES")"
ok "archive has exactly the 16 documented entries; manifest.json lists every other entry"
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
