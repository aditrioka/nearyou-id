#!/usr/bin/env bash
# Flip a staging user's `subscription_status` (or other column-set in the
# allowed list — see SUPPORTED_FIELDS) by spawning a one-shot Cloud Run Job
# that runs psql against the staging Supabase instance.
#
# WHY this exists (the painful path of discovery is captured here so the
# next person doesn't repeat it):
#   - Staging Supabase's primary host (`db.<ref>.supabase.co`) resolves to
#     IPv6 only. Local workstations, Cloud Build runners, and Cloud Shell
#     SSH all fail with "Address not available" / "Cannot assign requested
#     address" — none of those have working IPv6 outbound to AWS.
#   - The Supabase pooler at `aws-0-<region>.pooler.supabase.com:6543` is
#     IPv4-reachable but rejects this project's tenant ("tenant/user not
#     found") on every region — pooler isn't enabled on the Free tier
#     project. Probed: ap-southeast-{1,2}, ap-northeast-1, us-{east-1,
#     west-1, west-2}, eu-{west-{1,2,3}, central-1}, ca-central-1, ap-
#     south-1, sa-east-1, ap-east-1.
#   - The ONLY confirmed working path from outside Cloud Run's network
#     namespace is to spawn a transient Cloud Run Job (Cloud Run's IPv6
#     egress to AWS works). This script wraps that dance.
#
# Required GCP IAM (caller running this script needs):
#   - secretAccessor on `staging-db-url`, `staging-db-user`,
#     `staging-db-password` in `nearyou-staging`.
#   - run.developer on `nearyou-staging` (jobs.create / jobs.run / jobs.delete).
#
# Usage:
#   dev/scripts/promote-staging-user.sh <user-uuid> [<status>]
#     <status> defaults to `premium_active`; allowed values per the
#     `users.subscription_status` CHECK constraint:
#         free | premium_active | premium_billing_retry
#
# Example: promote `10d600e9-df39-48ec-9493-7e3d445493f1` to Premium for the
# next premium-search smoke:
#
#     dev/scripts/promote-staging-user.sh 10d600e9-df39-48ec-9493-7e3d445493f1
#
# Or revert to Free after smoke:
#
#     dev/scripts/promote-staging-user.sh 10d600e9-df39-48ec-9493-7e3d445493f1 free
#
# Idempotent: re-running on a user already in the target status is a clean
# no-op (`UPDATE 0` instead of `UPDATE 1`; script still exits 0).
#
# Exit codes:
#   0 — UPDATE applied (or already in target state); state verified.
#   1 — Job ran but UPDATE didn't reach the target state (verify mismatch).
#   2 — Usage error (bad args, missing UUID, invalid status).
#   3 — gcloud / Cloud Run Job creation or execution failed.

set -euo pipefail

PROJECT="nearyou-staging"
REGION="asia-southeast1"
PROMOTER_JOB="dev-promote-staging-user"
VERIFIER_JOB="dev-verify-staging-user"

usage() {
    sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi

USER_UUID="${1:-}"
STATUS="${2:-premium_active}"

if [[ -z "$USER_UUID" ]]; then
    echo "ERROR: missing <user-uuid>." >&2
    echo >&2
    usage >&2
    exit 2
fi

# UUID shape sanity check (8-4-4-4-12 lowercase hex). Reject anything else
# upfront so we don't pollute Cloud Run history with broken jobs.
if ! [[ "$USER_UUID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
    echo "ERROR: '$USER_UUID' is not a lowercase UUID." >&2
    exit 2
fi

case "$STATUS" in
    free|premium_active|premium_billing_retry) ;;
    *)
        echo "ERROR: invalid status '$STATUS'. Allowed: free | premium_active | premium_billing_retry." >&2
        exit 2
        ;;
esac

echo "==> Promote staging user $USER_UUID → $STATUS"
echo "    Project: $PROJECT"
echo "    Region:  $REGION"
echo

# ----------------------------------------------------------------------------
# 1. Resolve DB connection from secrets. Cloud Run reads these as env vars at
#    runtime; we resolve them locally to construct the psql DSN that the Job
#    will use, but the password is wired via --set-secrets so it never lands
#    in the Job's command line (or `gcloud run jobs describe` output).
# ----------------------------------------------------------------------------
DB_URL="$(gcloud secrets versions access latest --secret=staging-db-url --project="$PROJECT" 2>/dev/null)"
DB_USER="$(gcloud secrets versions access latest --secret=staging-db-user --project="$PROJECT" 2>/dev/null)"
HOST_PORT_DB="$(echo "$DB_URL" | sed -E 's|jdbc:postgresql://([^?]+).*|\1|')"
DSN="postgresql://${DB_USER}@${HOST_PORT_DB}?sslmode=require"

# ----------------------------------------------------------------------------
# 2. (Re)create + execute the promoter job. Use distinct args (not a single
#    quoted string) so the SQL doesn't get re-split by gcloud's CSV parser
#    on the comma in `RETURNING ...`.
# ----------------------------------------------------------------------------
gcloud run jobs delete "$PROMOTER_JOB" --region="$REGION" --project="$PROJECT" --quiet 2>/dev/null || true

PROMOTE_SQL="UPDATE users SET subscription_status = '${STATUS}' WHERE id = '${USER_UUID}' RETURNING id;"

echo "==> Creating one-shot Cloud Run Job '$PROMOTER_JOB'..."
gcloud run jobs create "$PROMOTER_JOB" \
    --image=postgres:16-alpine \
    --region="$REGION" \
    --project="$PROJECT" \
    --command=psql \
    --args="$DSN" \
    --args="-c" \
    --args="$PROMOTE_SQL" \
    --set-secrets="PGPASSWORD=staging-db-password:latest" \
    --max-retries=0 \
    --task-timeout=60s \
    --quiet >/dev/null

echo "==> Executing promoter job (waits for completion, ~30-60s)..."
if ! gcloud run jobs execute "$PROMOTER_JOB" \
    --region="$REGION" --project="$PROJECT" --wait --quiet >/dev/null 2>&1; then
    echo "ERROR: promoter job execution failed. Recent logs:" >&2
    gcloud logging read \
        "resource.type=cloud_run_job AND resource.labels.job_name=$PROMOTER_JOB" \
        --limit=10 --project="$PROJECT" --format="value(textPayload)" --freshness=2m >&2 || true
    gcloud run jobs delete "$PROMOTER_JOB" --region="$REGION" --project="$PROJECT" --quiet 2>/dev/null || true
    exit 3
fi
echo "    Promoter job completed."

# Inspect logs for the UPDATE row count (purely informational — the verify
# job is the load-bearing assertion).
ROWS_LINE="$(gcloud logging read \
    "resource.type=cloud_run_job AND resource.labels.job_name=$PROMOTER_JOB" \
    --limit=20 --project="$PROJECT" --format="value(textPayload)" --freshness=3m 2>/dev/null \
    | grep -E "^UPDATE [0-9]+" | head -1 || true)"
if [[ -n "$ROWS_LINE" ]]; then
    # `UPDATE 1` = WHERE matched (the user exists; Postgres always counts the
    # row even when the new value equals the old, so this is NOT a flipped-vs-
    # noop signal). `UPDATE 0` = WHERE didn't match — user UUID is missing in
    # staging. The verifier below is the load-bearing assertion regardless.
    echo "    psql reported: $ROWS_LINE  (0 = user UUID not found in staging)"
fi
echo

# ----------------------------------------------------------------------------
# 3. Verifier job — separate job so the SELECT runs after the UPDATE has
#    committed (Cloud Run Jobs don't share connection state across tasks).
# ----------------------------------------------------------------------------
gcloud run jobs delete "$VERIFIER_JOB" --region="$REGION" --project="$PROJECT" --quiet 2>/dev/null || true

VERIFY_SQL="SELECT id || ' | ' || username || ' | ' || subscription_status FROM users WHERE id = '${USER_UUID}';"

echo "==> Creating verifier job..."
gcloud run jobs create "$VERIFIER_JOB" \
    --image=postgres:16-alpine \
    --region="$REGION" \
    --project="$PROJECT" \
    --command=psql \
    --args="$DSN" \
    --args="-tAc" \
    --args="$VERIFY_SQL" \
    --set-secrets="PGPASSWORD=staging-db-password:latest" \
    --max-retries=0 \
    --task-timeout=60s \
    --quiet >/dev/null

echo "==> Executing verifier job..."
gcloud run jobs execute "$VERIFIER_JOB" \
    --region="$REGION" --project="$PROJECT" --wait --quiet >/dev/null 2>&1 || {
        echo "ERROR: verifier job failed." >&2
        gcloud run jobs delete "$VERIFIER_JOB" --region="$REGION" --project="$PROJECT" --quiet 2>/dev/null || true
        exit 3
    }

# Pull the SELECT row from logs.
sleep 2
VERIFIED_ROW="$(gcloud logging read \
    "resource.type=cloud_run_job AND resource.labels.job_name=$VERIFIER_JOB" \
    --limit=20 --project="$PROJECT" --format="value(textPayload)" --freshness=3m 2>/dev/null \
    | grep -E "^${USER_UUID}" | head -1 || true)"

# ----------------------------------------------------------------------------
# 4. Cleanup the throwaway jobs (idempotent; doesn't gate exit).
# ----------------------------------------------------------------------------
gcloud run jobs delete "$PROMOTER_JOB" --region="$REGION" --project="$PROJECT" --quiet 2>/dev/null || true
gcloud run jobs delete "$VERIFIER_JOB" --region="$REGION" --project="$PROJECT" --quiet 2>/dev/null || true

# ----------------------------------------------------------------------------
# 5. Assert the target state.
# ----------------------------------------------------------------------------
if [[ -z "$VERIFIED_ROW" ]]; then
    echo "FAIL: verifier returned no row for user $USER_UUID." >&2
    echo "      The user may not exist in staging — verify with the smoke flow." >&2
    exit 1
fi

CURRENT_STATUS="$(echo "$VERIFIED_ROW" | awk -F' | ' '{print $NF}' | tr -d ' ')"
echo "    Verified row: $VERIFIED_ROW"

if [[ "$CURRENT_STATUS" != "$STATUS" ]]; then
    echo "FAIL: expected subscription_status='$STATUS', got '$CURRENT_STATUS'." >&2
    exit 1
fi

echo
echo "==> SUCCESS — user $USER_UUID is now subscription_status='$STATUS'."
exit 0
