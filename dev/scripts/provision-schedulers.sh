#!/usr/bin/env bash
# Provision one Cloud Scheduler job per OIDC-gated `/internal/*` worker
# (issue #535, 2026-10-03 architecture review BARU-11).
#
# WHY this exists:
#   Every `/internal/*` worker is inert until a Cloud Scheduler job invokes it
#   (docs/07-Operations.md § Internal worker schedules). Before this script, the
#   jobs were hand-made per change (unban, privacy-flip, login-anomaly on
#   staging) and the other six workers had none, so suspension expiry, the
#   30-day deletion grace, the retention purges, data export, referral grants,
#   the CSAM archive purge and orphan-image cleanup never ran on a schedule.
#   This script is the single declared source for all nine schedules, the same
#   for staging now and production later. Runbook: dev/docs/cloud-scheduler.md.
#
# WHAT it does (idempotent; re-running converges every job to the table below):
#   1. Enables the Cloud Scheduler API.
#   2. Creates the invoker service account `scheduler-invoker-<env>` if missing.
#      This SA email MUST be listed in the caller allowlist
#      `INTERNAL_OIDC_ALLOWED_PRINCIPALS` (#544), otherwise every job gets 403.
#   3. Grants that SA `roles/run.invoker` on the Cloud Run service.
#   4. Creates each job, or updates it if it already exists: schedule, time zone,
#      URI, POST, OIDC SA + audience (= INTERNAL_OIDC_AUDIENCE), retry policy;
#      an update also clears any hand-added headers / body.
#      An existing job keeps its ENABLED/PAUSED state, so a deliberate pause
#      (the rollback path) survives a re-run.
#   5. With --run-now: force-runs every ENABLED job (paused ones are skipped),
#      then prints the Scheduler-originated `/internal/*` request log lines
#      (the first-invocation evidence).
#   On a brand-new project a step right after the API enable / SA create can
#   fail while IAM propagates; re-run the script.
#
# WHO can run this:
#   Owner, or: serviceusage.serviceUsageAdmin + iam.serviceAccountAdmin +
#   run.admin (for the service IAM binding) + cloudscheduler.admin +
#   iam.serviceAccountUser on the invoker SA (to attach it to the jobs).
#
# Usage:
#   dev/scripts/provision-schedulers.sh                  # staging
#   dev/scripts/provision-schedulers.sh --dry-run        # print the mutations only
#   dev/scripts/provision-schedulers.sh --run-now        # provision, then force-run all jobs
#   ENV_NAME=prod PROJECT_ID=<prod-project> API_HOST=api.nearyou.id \
#     dev/scripts/provision-schedulers.sh                # production (once it exists)
#
# Overrides (env): ENV_NAME (staging), PROJECT_ID, API_HOST, REGION (asia-southeast1),
#   SERVICE (nearyou-backend-<env>), INTERNAL_OIDC_AUDIENCE (https://<API_HOST>),
#   SA_NAME (scheduler-invoker-<env>). Deliberately not PROJECT / HOST: zsh sets
#   HOST to the machine name, and a stray export would silently retarget the jobs.
#
# Project IDs, hosts and SA emails are non-sensitive (CLAUDE.md § Public
# repository posture). No secret is read or written.

set -euo pipefail

DRY_RUN=false
RUN_NOW=false
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        --run-now) RUN_NOW=true ;;
        -h|--help) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $arg (see --help)" >&2; exit 64 ;;
    esac
done

ENV_NAME="${ENV_NAME:-staging}"
if [[ "$ENV_NAME" == staging ]]; then
    PROJECT_ID="${PROJECT_ID:-nearyou-staging}"
    API_HOST="${API_HOST:-api-staging.nearyou.id}"
fi
# Production has no defaults on purpose: a forgotten override must not point
# prod's schedules at staging.
: "${PROJECT_ID:?set PROJECT_ID for ENV_NAME=$ENV_NAME}"
: "${API_HOST:?set API_HOST for ENV_NAME=$ENV_NAME}"
case "$API_HOST" in
    *.nearyou.id|*.run.app) ;;
    *) echo "API_HOST=$API_HOST is neither a nearyou.id nor a run.app host; refusing to point OIDC-signed jobs at it." >&2; exit 64 ;;
esac
REGION="${REGION:-asia-southeast1}"
SERVICE="${SERVICE:-nearyou-backend-$ENV_NAME}"
INTERNAL_OIDC_AUDIENCE="${INTERNAL_OIDC_AUDIENCE:-https://$API_HOST}"
SA_NAME="${SA_NAME:-scheduler-invoker-$ENV_NAME}"
SA_EMAIL="$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com"

# One row per worker: job-id | path | cron | time zone.
# The job name is nearyou-<job-id>-<env>, which matches the three jobs created by
# hand before this script, so they are updated in place rather than duplicated.
# The daily sweeps sit on distinct half-hours of the 02:00–05:00 WIB low-traffic
# window so they never overlap each other; the light hourly pair at :00 may
# coincide with one of them, which is harmless (one warm instance serves both).
JOBS=(
    # suspension-unban-worker § "Schedule is daily at 04:00 WIB": the spec gives the cron in UTC.
    "unban-worker|/internal/unban-worker|0 21 * * *|UTC"
    # privacy-flip-worker: hourly.
    "privacy-flip-worker|/internal/privacy-flip-worker|0 * * * *|Asia/Jakarta"
    # auth-login-anomaly-detection: trailing one-hour window, so hourly.
    "login-anomaly-check|/internal/login-anomaly-check|0 * * * *|Asia/Jakarta"
    # account-data-export: the spec sets no cadence, only a 7-day SLA. Hourly keeps
    # delivery well inside it; :45 is a minute no other job uses, so the heaviest
    # worker never shares an invocation window.
    "data-export-worker|/internal/data-export-worker|45 * * * *|Asia/Jakarta"
    # csam-detection: daily archive purge.
    "csam-archive-purge|/internal/csam-archive-purge|0 2 * * *|Asia/Jakarta"
    # orphan-image-cleanup: daily, same SA as /internal/cleanup (spec requirement).
    "cleanup-orphan-images|/internal/cleanup-orphan-images|30 2 * * *|Asia/Jakarta"
    # scheduled-retention-cleanup: one daily job for every sweep (design D2).
    "retention-cleanup|/internal/cleanup|0 3 * * *|Asia/Jakarta"
    # account-hard-delete-worker: daily.
    "account-hard-delete-worker|/internal/account-hard-delete-worker|30 3 * * *|Asia/Jakarta"
    # referral-grant-worker: daily. It runs after the 04:00 unban so an inviter whose
    # suspension lapsed overnight is not voided on a stale is_banned flag.
    "referral-activity-check|/internal/referral-activity-check|30 4 * * *|Asia/Jakarta"
)

# Every worker is idempotent, so retries are safe. Retry policy from the
# suspension-unban-worker spec (≥3 attempts, backoff 30s–5min), applied to all:
# up to 3 retries after the first attempt, no overall retry-duration cap.
# The attempt deadline is above Cloud Run's 60s request timeout, so a slow run
# is recorded as Cloud Run's 504 rather than the Scheduler's own timeout.
RETRY_FLAGS=(--max-retry-attempts=3 --min-backoff=30s --max-backoff=300s --max-doublings=5 --max-retry-duration=0s --attempt-deadline=180s)

# Dry-run output goes to stderr so callers' `>/dev/null` doesn't hide it.
run() {
    if $DRY_RUN; then
        { printf '+'; printf ' %q' "$@"; printf '\n'; } >&2
    else
        "$@"
    fi
}

command -v gcloud >/dev/null || { echo "gcloud not found — run this in Cloud Shell or install the SDK." >&2; exit 1; }

echo "==> Cloud Scheduler for ENV_NAME=$ENV_NAME"
echo "    project=$PROJECT_ID region=$REGION service=$SERVICE"
echo "    audience=$INTERNAL_OIDC_AUDIENCE"
echo "    invoker SA=$SA_EMAIL"
$DRY_RUN && echo "    (dry run: mutations are printed, not executed)"

echo "==> 1/4 Enabling the Cloud Scheduler API"
run gcloud services enable cloudscheduler.googleapis.com --project="$PROJECT_ID" --quiet

echo "==> 2/4 Invoker service account"
if gcloud iam service-accounts describe "$SA_EMAIL" --project="$PROJECT_ID" >/dev/null 2>&1; then
    echo "    exists — skipping create."
else
    run gcloud iam service-accounts create "$SA_NAME" --project="$PROJECT_ID" \
        --display-name="Cloud Scheduler invoker for /internal/* workers ($ENV_NAME)" --quiet
fi

echo "==> 3/4 roles/run.invoker on $SERVICE"
run gcloud run services add-iam-policy-binding "$SERVICE" \
    --project="$PROJECT_ID" --region="$REGION" \
    --member="serviceAccount:$SA_EMAIL" --role=roles/run.invoker --quiet >/dev/null

echo "==> 4/4 Scheduler jobs"
runnable=()
for row in "${JOBS[@]}"; do
    IFS='|' read -r id path cron tz <<<"$row"
    job="nearyou-$id-$ENV_NAME"
    state="$(gcloud scheduler jobs describe "$job" --project="$PROJECT_ID" --location="$REGION" \
        --format='value(state)' 2>/dev/null || true)"
    if [[ -n "$state" ]]; then
        verb=update
        extra=(--clear-headers --clear-message-body)
    else
        verb=create
        extra=()
    fi
    # A paused job stays paused (rollback path) and is never force-run.
    [[ "$state" == PAUSED ]] || runnable+=("$job")
    echo "    $verb $job  [$cron $tz]  POST $path${state:+  ($state)}"
    run gcloud scheduler jobs "$verb" http "$job" \
        --project="$PROJECT_ID" --location="$REGION" \
        --schedule="$cron" --time-zone="$tz" \
        --uri="https://$API_HOST$path" --http-method=POST \
        --oidc-service-account-email="$SA_EMAIL" \
        --oidc-token-audience="$INTERNAL_OIDC_AUDIENCE" \
        --description="$path ($ENV_NAME) — managed by dev/scripts/provision-schedulers.sh" \
        "${RETRY_FLAGS[@]}" ${extra[@]+"${extra[@]}"} --quiet >/dev/null
done

if $RUN_NOW; then
    echo "==> Force-running every enabled job (all workers are idempotent; paused jobs skipped)"
    for job in ${runnable[@]+"${runnable[@]}"}; do
        run gcloud scheduler jobs run "$job" --project="$PROJECT_ID" --location="$REGION" --quiet
    done
    if ! $DRY_RUN; then
        echo "    waiting 45s for the requests to land in Cloud Logging…"
        sleep 45
        gcloud logging read \
            "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"$SERVICE\" AND httpRequest.requestUrl:\"/internal/\" AND httpRequest.userAgent:\"Google-Cloud-Scheduler\"" \
            --project="$PROJECT_ID" --freshness=10m --limit=50 \
            --format='table(timestamp,httpRequest.status,httpRequest.requestUrl)'
    fi
fi

echo "==> Done. Current jobs:"
gcloud scheduler jobs list --project="$PROJECT_ID" --location="$REGION" \
    --format='table(name.basename(),schedule,timeZone,state,httpTarget.oidcToken.serviceAccountEmail)' \
    || true
