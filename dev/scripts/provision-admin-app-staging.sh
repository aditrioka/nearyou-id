#!/usr/bin/env bash
# Provision the `admin_app` Postgres role on staging Supabase per the
# Pre-Phase 1 #28 step in docs/08-Roadmap-Risk.md + the access-control
# contract in docs/07-Operations.md § Data Access Pattern.
#
# WHY this exists:
#   - The admin-schema-bootstrap change (V16) shipped the five admin
#     tables, but role-level access control (CREATE ROLE + scoped
#     GRANTs + the immutability REVOKE on admin_actions_log) was
#     deliberately KEPT OUT of Flyway per `admin-schema-bootstrap/
#     design.md` D4 — Supabase Console is the canonical surface for
#     role permissions; including conditional REVOKE logic in Flyway
#     would muddy the migration with role-aware shape that fails in
#     the integration-test Postgres where admin_app doesn't exist.
#   - This script is the operational counterpart to D4: it lives in
#     `dev/scripts/` (not Flyway), applies idempotently, and runs via
#     the same one-shot Cloud Run Job pattern as
#     `dev/scripts/promote-staging-user.sh` (Supabase direct host is
#     IPv6-only — local IPv4 workstations can't reach it).
#
# WHAT it does (idempotent):
#   1. CREATE ROLE admin_app LOGIN with the password held in
#      `staging-admin-app-db-password` Secret Manager slot (or ALTER
#      ROLE with same password if the role already exists — keeps the
#      secret + role in sync on re-runs).
#   2. GRANT USAGE ON SCHEMA public TO admin_app.
#   3. GRANT scoped per-table privileges on the 23 operational tables
#      (V1-V16) — enumerated explicitly, NOT `ON ALL TABLES`. New
#      tables shipped in future migrations require an explicit grant
#      added here (the per-migration cost is the price of least-
#      privilege auditability).
#   4. GRANT SELECT on the two views (visible_posts, visible_users)
#      so admin code can use shadow-ban-aware reads.
#   5. REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app —
#      audit-log immutability invariant per
#      docs/05-Implementation.md:1208.
#   6. NO `ALTER DEFAULT PRIVILEGES` — future tables require explicit
#      decision (anti-pattern: auto-granting on schema-future-additions).
#   7. NO `BYPASSRLS` — admin_app is subject to RLS like every other
#      role.
#
# WHAT it does NOT do:
#   - Doesn't grant on admin_regions WRITE — it's reference data
#     (kabupaten polygons), DDL-managed via V11/V12. SELECT only.
#   - Doesn't grant on Supabase-managed schemas (`auth`, `realtime`,
#     `storage`, `extensions`) — admin only needs `public`.
#   - Doesn't add `LOGIN` to existing admin_users data rows — those
#     are application records, not Postgres roles.
#   - Doesn't wire admin_app into the deploy-staging.yml workflow —
#     that wiring happens when Admin #2 (`admin-panel-ktor-htmx-
#     bootstrap`) ships the admin Ktor module that consumes the
#     connection string.
#
# WHO can run this:
#   - Caller needs: secretAccessor on `staging-db-{url,user,password}`
#     + `staging-admin-app-db-password` in nearyou-staging, run.developer
#     on nearyou-staging (jobs.create / jobs.run / jobs.delete).
#   - Re-running is safe (idempotent CREATE/ALTER + REVOKE).
#
# Precedence: ran 2026-05-17 via this script for staging Supabase.
# Production: re-run this script against production with PROJECT=
# `nearyou-production` after the production Supabase project is
# bootstrapped (env-var override below).

set -euo pipefail

PROJECT="${PROJECT_OVERRIDE:-nearyou-staging}"
REGION="${REGION_OVERRIDE:-asia-southeast1}"
JOB_NAME="dev-provision-admin-app"

PASSWORD_SLOT="${PASSWORD_SLOT:-staging-admin-app-db-password}"
URL_SLOT="${URL_SLOT:-staging-db-url}"
USER_SLOT="${USER_SLOT:-staging-db-user}"
FLYWAY_PASSWORD_SLOT="${FLYWAY_PASSWORD_SLOT:-staging-db-password}"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
    exit 0
fi

echo "==> Provision admin_app role"
echo "    Project: $PROJECT"
echo "    Region:  $REGION"
echo "    Password slot: $PASSWORD_SLOT (must exist)"
echo

# Verify the password slot exists upfront — fail-fast instead of waiting for
# Cloud Run Job to fail with a confusing error.
if ! gcloud secrets describe "$PASSWORD_SLOT" --project="$PROJECT" >/dev/null 2>&1; then
    echo "ERROR: secret slot '$PASSWORD_SLOT' not found in project '$PROJECT'." >&2
    echo "       Create it first with a strong password (e.g., openssl rand -hex 32)" >&2
    echo "       and store it via:" >&2
    echo "         openssl rand -hex 32 | gcloud secrets create $PASSWORD_SLOT \\" >&2
    echo "           --project=$PROJECT --data-file=-" >&2
    exit 2
fi

# Cloud Run Job CREATE requires the runtime SA to have secretAccessor on
# every slot bound via --set-secrets — at create time, not execute time
# (gcloud validates the IAM upfront per docs/07-Operations.md § Secret
# Management Runbook). The flyway_migrator password slot was granted long
# ago in Pre-Phase 1; the admin_app password slot was just created and
# needs the grant here so the job below can bind it.
RUNTIME_SA="27815942904-compute@developer.gserviceaccount.com"
echo "==> Pre-flight: granting secretAccessor on $PASSWORD_SLOT to $RUNTIME_SA..."
gcloud secrets add-iam-policy-binding "$PASSWORD_SLOT" \
    --project="$PROJECT" \
    --member="serviceAccount:$RUNTIME_SA" \
    --role=roles/secretmanager.secretAccessor \
    --quiet >/dev/null 2>&1 || {
    echo "WARNING: IAM bind on $PASSWORD_SLOT failed — verify slot exists + caller has secretmanager.admin role" >&2
    exit 2
}

# Resolve the flyway_migrator DSN (the role with DDL privileges to CREATE ROLE).
DB_URL="$(gcloud secrets versions access latest --secret="$URL_SLOT" --project="$PROJECT" 2>/dev/null)"
DB_USER="$(gcloud secrets versions access latest --secret="$USER_SLOT" --project="$PROJECT" 2>/dev/null)"
HOST_PORT_DB="$(echo "$DB_URL" | sed -E 's|jdbc:postgresql://([^?]+).*|\1|')"
FLYWAY_DSN="postgresql://${DB_USER}@${HOST_PORT_DB}?sslmode=require"

# The full provisioning SQL. Enumerated grants per operational table.
# IMPORTANT: gcloud `--args` comma-splits CSV values, so each statement here
# MUST be free of commas inside the value. The DO $$ block + per-statement
# GRANTs are deliberately split this way; semicolons are the statement
# delimiter inside the single --args value, which is fine because psql -c
# processes the whole string as one SQL script.
SQL=$(cat <<'PROVISION_SQL'
-- 1. CREATE or ALTER admin_app with the password from Secret Manager
--    (injected into the job process as $ADMIN_APP_PW env var; the DO $$
--    block reads it via current_setting via a parameterized EXECUTE).
DO $do$
DECLARE
  pw text := current_setting('admin_app.password');
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'admin_app') THEN
    EXECUTE format('CREATE ROLE admin_app LOGIN PASSWORD %L', pw);
  ELSE
    EXECUTE format('ALTER ROLE admin_app WITH LOGIN PASSWORD %L', pw);
  END IF;
END
$do$;

-- 2. Schema usage.
GRANT USAGE ON SCHEMA public TO admin_app;

-- 3. Enumerated per-table grants. NEW TABLES require an explicit line here.
--    Naming convention: full CRUD unless commented otherwise.

-- 3a. Identity + auth.
GRANT SELECT ON users TO admin_app;
GRANT INSERT ON users TO admin_app;
GRANT UPDATE ON users TO admin_app;
GRANT DELETE ON users TO admin_app;
GRANT SELECT ON refresh_tokens TO admin_app;
GRANT INSERT ON refresh_tokens TO admin_app;
GRANT UPDATE ON refresh_tokens TO admin_app;
GRANT DELETE ON refresh_tokens TO admin_app;
GRANT SELECT ON reserved_usernames TO admin_app;
GRANT INSERT ON reserved_usernames TO admin_app;
GRANT UPDATE ON reserved_usernames TO admin_app;
GRANT DELETE ON reserved_usernames TO admin_app;
GRANT SELECT ON rejected_identifiers TO admin_app;
GRANT INSERT ON rejected_identifiers TO admin_app;
GRANT UPDATE ON rejected_identifiers TO admin_app;
GRANT DELETE ON rejected_identifiers TO admin_app;
GRANT SELECT ON username_history TO admin_app;
GRANT INSERT ON username_history TO admin_app;
GRANT UPDATE ON username_history TO admin_app;
GRANT DELETE ON username_history TO admin_app;

-- 3b. Social graph + posts.
GRANT SELECT ON posts TO admin_app;
GRANT INSERT ON posts TO admin_app;
GRANT UPDATE ON posts TO admin_app;
GRANT DELETE ON posts TO admin_app;
GRANT SELECT ON post_likes TO admin_app;
GRANT INSERT ON post_likes TO admin_app;
GRANT UPDATE ON post_likes TO admin_app;
GRANT DELETE ON post_likes TO admin_app;
GRANT SELECT ON post_replies TO admin_app;
GRANT INSERT ON post_replies TO admin_app;
GRANT UPDATE ON post_replies TO admin_app;
GRANT DELETE ON post_replies TO admin_app;
GRANT SELECT ON follows TO admin_app;
GRANT INSERT ON follows TO admin_app;
GRANT UPDATE ON follows TO admin_app;
GRANT DELETE ON follows TO admin_app;
GRANT SELECT ON user_blocks TO admin_app;
GRANT INSERT ON user_blocks TO admin_app;
GRANT UPDATE ON user_blocks TO admin_app;
GRANT DELETE ON user_blocks TO admin_app;

-- 3c. Chat.
GRANT SELECT ON conversations TO admin_app;
GRANT INSERT ON conversations TO admin_app;
GRANT UPDATE ON conversations TO admin_app;
GRANT DELETE ON conversations TO admin_app;
GRANT SELECT ON conversation_participants TO admin_app;
GRANT INSERT ON conversation_participants TO admin_app;
GRANT UPDATE ON conversation_participants TO admin_app;
GRANT DELETE ON conversation_participants TO admin_app;
GRANT SELECT ON chat_messages TO admin_app;
GRANT INSERT ON chat_messages TO admin_app;
GRANT UPDATE ON chat_messages TO admin_app;
GRANT DELETE ON chat_messages TO admin_app;

-- 3d. Moderation queue + reports.
GRANT SELECT ON reports TO admin_app;
GRANT INSERT ON reports TO admin_app;
GRANT UPDATE ON reports TO admin_app;
GRANT DELETE ON reports TO admin_app;
GRANT SELECT ON moderation_queue TO admin_app;
GRANT INSERT ON moderation_queue TO admin_app;
GRANT UPDATE ON moderation_queue TO admin_app;
GRANT DELETE ON moderation_queue TO admin_app;

-- 3e. Notifications + push.
GRANT SELECT ON notifications TO admin_app;
GRANT INSERT ON notifications TO admin_app;
GRANT UPDATE ON notifications TO admin_app;
GRANT DELETE ON notifications TO admin_app;
GRANT SELECT ON user_fcm_tokens TO admin_app;
GRANT INSERT ON user_fcm_tokens TO admin_app;
GRANT UPDATE ON user_fcm_tokens TO admin_app;
GRANT DELETE ON user_fcm_tokens TO admin_app;

-- 3f. Admin self-management (the V16 admin schema).
GRANT SELECT ON admin_users TO admin_app;
GRANT INSERT ON admin_users TO admin_app;
GRANT UPDATE ON admin_users TO admin_app;
GRANT DELETE ON admin_users TO admin_app;
GRANT SELECT ON admin_webauthn_credentials TO admin_app;
GRANT INSERT ON admin_webauthn_credentials TO admin_app;
GRANT UPDATE ON admin_webauthn_credentials TO admin_app;
GRANT DELETE ON admin_webauthn_credentials TO admin_app;
GRANT SELECT ON admin_sessions TO admin_app;
GRANT INSERT ON admin_sessions TO admin_app;
GRANT UPDATE ON admin_sessions TO admin_app;
GRANT DELETE ON admin_sessions TO admin_app;
GRANT SELECT ON admin_webauthn_challenges TO admin_app;
GRANT INSERT ON admin_webauthn_challenges TO admin_app;
GRANT UPDATE ON admin_webauthn_challenges TO admin_app;
GRANT DELETE ON admin_webauthn_challenges TO admin_app;

-- 3g. admin_actions_log: SELECT + INSERT only (append-only).
GRANT SELECT ON admin_actions_log TO admin_app;
GRANT INSERT ON admin_actions_log TO admin_app;

-- 3h. Reference data: SELECT only (admin_regions is DDL-managed via V11/V12).
GRANT SELECT ON admin_regions TO admin_app;

-- 4. Views: SELECT only (shadow-ban-aware reads).
GRANT SELECT ON visible_posts TO admin_app;
GRANT SELECT ON visible_users TO admin_app;

-- 5. Immutability invariant: REVOKE UPDATE + DELETE on admin_actions_log.
--    Belt-and-suspenders — 3g grants only SELECT + INSERT above; this
--    line is the named-invariant enforcement that catches drift if a
--    later 3g edit accidentally adds UPDATE/DELETE.
REVOKE UPDATE ON admin_actions_log FROM admin_app;
REVOKE DELETE ON admin_actions_log FROM admin_app;

-- 6. Echo for the job logs (psql prints this on stdout).
SELECT 'admin_app provisioned + grants applied + immutability REVOKE in place' AS status;
PROVISION_SQL
)

# The SQL is passed via --set-env-vars (NOT --args), so gcloud's comma-split
# rule for --args doesn't apply here. Commas in SQL (e.g., `REVOKE UPDATE,
# DELETE ON …`) are fine. The env-vars delimiter we override below is `~`
# (not `@` — the DSN contains `@` between user and host).

# Sanity-check the delimiter character doesn't collide with values.
if [[ "$FLYWAY_DSN" == *"~"* ]] || [[ "$SQL" == *"~"* ]]; then
    echo "ERROR: DSN or SQL contains '~' which collides with the env-vars delimiter. Pick a different delimiter." >&2
    exit 3
fi

gcloud run jobs delete "$JOB_NAME" --region="$REGION" --project="$PROJECT" --quiet 2>/dev/null || true

echo "==> Creating one-shot Cloud Run Job '$JOB_NAME'..."
gcloud run jobs create "$JOB_NAME" \
    --image=postgres:16-alpine \
    --region="$REGION" \
    --project="$PROJECT" \
    --command=sh \
    --args="-c" \
    --args="PGOPTIONS=\"-c admin_app.password=\$ADMIN_APP_PW\" psql \"\$DSN\" -v ON_ERROR_STOP=1 -c \"\$SQL\"" \
    --set-secrets="ADMIN_APP_PW=$PASSWORD_SLOT:latest,PGPASSWORD=$FLYWAY_PASSWORD_SLOT:latest" \
    --set-env-vars="^~^DSN=$FLYWAY_DSN~SQL=$SQL" \
    --max-retries=0 \
    --task-timeout=120s \
    --quiet >/dev/null

echo "==> Executing (waits for completion)..."
if ! gcloud run jobs execute "$JOB_NAME" \
    --region="$REGION" --project="$PROJECT" --wait --quiet >/dev/null 2>&1; then
    echo "ERROR: job execution failed. Recent logs:" >&2
    gcloud logging read \
        "resource.type=cloud_run_job AND resource.labels.job_name=$JOB_NAME" \
        --limit=20 --project="$PROJECT" --format="value(textPayload)" --freshness=3m >&2 || true
    gcloud run jobs delete "$JOB_NAME" --region="$REGION" --project="$PROJECT" --quiet 2>/dev/null || true
    exit 4
fi

echo "==> Provisioning job completed. Output:"
gcloud logging read \
    "resource.type=cloud_run_job AND resource.labels.job_name=$JOB_NAME" \
    --limit=10 --project="$PROJECT" --format="value(textPayload)" --freshness=3m \
    | grep -E "provisioned|GRANT|REVOKE|status|ERROR" | head -20

echo "==> Cleaning up the one-shot job..."
gcloud run jobs delete "$JOB_NAME" --region="$REGION" --project="$PROJECT" --quiet 2>&1 | tail -1

# Grant secretAccessor IAM on the four admin-app secret slots to the Cloud
# Run runtime service account (the same SA the staging deploy uses).
# Per docs/07-Operations.md § Secret Management Runbook: "new slot requires
# an explicit roles/secretmanager.secretAccessor grant."
RUNTIME_SA="27815942904-compute@developer.gserviceaccount.com"
echo "==> Granting secretAccessor on the 4 admin-app slots to $RUNTIME_SA..."
for SLOT in staging-admin-app-db-user staging-admin-app-db-password \
            staging-admin-app-db-url staging-admin-app-db-connection-string; do
    gcloud secrets add-iam-policy-binding "$SLOT" \
        --project="$PROJECT" \
        --member="serviceAccount:$RUNTIME_SA" \
        --role=roles/secretmanager.secretAccessor \
        --quiet 2>&1 | tail -1 || echo "  (warning: IAM bind on $SLOT failed — verify slot exists)"
done

echo
echo "==> DONE. Verification command (run separately):"
echo "    gcloud run jobs deploy dev-verify-admin-app \\"
echo "      --image=postgres:16-alpine --region=$REGION --project=$PROJECT \\"
echo "      --command=psql --args=\"\$(gcloud secrets versions access latest --secret=staging-admin-app-db-connection-string --project=$PROJECT)\" \\"
echo "      --args=-c --args=\"SELECT current_user; SELECT count(*) FROM admin_users;\" \\"
echo "      --max-retries=0 --task-timeout=30s && \\"
echo "    gcloud run jobs execute dev-verify-admin-app --region=$REGION --project=$PROJECT --wait"
echo
echo "Re-running this script is idempotent (CREATE/ALTER + GRANT + REVOKE all converge)."
