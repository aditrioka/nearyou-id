## Why

The admin panel is the Phase 3.5 MVP-readiness gap (per [`openspec/project.md`](../../project.md) § Mobile + Admin Scaffolding Priority). Multiple shipped capabilities already reference admin tables that **do not yet exist** — `reports.reviewed_by`, `moderation_queue.resolved_by`, and `chat_messages.redacted_by` are nullable UUID columns annotated "FK to `admin_users(id)` deferred to the Phase 3.5 admin-users migration" ([V9:73](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:73), [V9:111](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:111), [V15:99](../../../backend/ktor/src/main/resources/db/migration/V15__chat_foundation.sql:99)). A long-standing FOLLOW_UPS entry (`suspension-unban-worker-audit-log-after-phase-3.5`) is also blocked on this schema. Shipping the canonical admin schema unblocks Admin #2-#5, validates the `csrf_token_hash` Detekt invariant against real shipping code, and closes the Phase 3.5 schema gap as a self-contained, code-free Flyway-only change.

## What Changes

- **NEW**: Flyway migration `V16__admin_users.sql` shipping five admin tables verbatim from canonical sources at [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) § Admin Users Schema (lines 636-703) and § Admin Actions Log Schema (lines 1184-1208):
  - `admin_users` — id, email UNIQUE NOT NULL, display_name VARCHAR(100), password_hash TEXT (Argon2id), totp_secret_encrypted BYTEA, webauthn_enrolled BOOLEAN DEFAULT FALSE, role CHECK IN ('owner', 'admin', 'moderator', 'read_only'), is_active BOOLEAN DEFAULT TRUE, created_at, last_login_at.
  - `admin_webauthn_credentials` — admin_id FK CASCADE, credential_id BYTEA UNIQUE, public_key BYTEA, sign_count BIGINT, device_label, created_at, last_used_at.
  - `admin_sessions` — admin_id FK CASCADE, session_token_hash TEXT UNIQUE, **csrf_token_hash TEXT NOT NULL** (mandatory per the `AdminSessionCsrfTokenRule` Detekt-enforced invariant), ip INET, user_agent, created_at, last_active_at, expires_at, revoked_at; partial index `WHERE revoked_at IS NULL`.
  - `admin_webauthn_challenges` — admin_id FK CASCADE (nullable for registration pre-binding), challenge BYTEA, ceremony CHECK IN ('registration', 'authentication'), created_at, expires_at, consumed_at; partial index `WHERE consumed_at IS NULL`.
  - `admin_actions_log` — admin_id FK (audit-trail-preserving), action_type VARCHAR(64), target_type, target_id TEXT, reason, before_state JSONB, after_state JSONB, ip INET, user_agent, created_at; three secondary indexes.
- **NEW**: FK backfills converting the three nullable UUID columns into validated FKs to `admin_users(id) ON DELETE SET NULL` via `ADD CONSTRAINT … NOT VALID + VALIDATE CONSTRAINT` (matches the V9 deferral comment's prescription):
  - `reports.reviewed_by`
  - `moderation_queue.resolved_by`
  - `chat_messages.redacted_by`
- **NEW**: Detekt allowlist entry for the V16 migration in `RawFromAdminTablesRule` (or analogous rule that may forbid raw admin-table writes from non-admin packages). Verify no such rule exists today; if absent, defer — this is schema-only.
- **NEW**: Schema-level integration tests under `:backend:ktor` validating column shapes, CHECK constraints, FK CASCADE / SET NULL behavior, UNIQUE constraints, and partial-index existence.
- **NOT shipped here** (explicit deferrals):
  - Admin UI, admin REST endpoints, admin login flow (Admin #2-#3).
  - Sentinel `system` admin user seed for worker-audit-log FK + auth-bypass guard CHECK (`system-actor-and-worker-audit-rows` follow-up).
  - `admin_app` DB role GRANT/REVOKE statements (provisioned in Supabase Console per Phase 1 #28; migration MAY include conditional defensive REVOKE — decide in design.md).
  - RLS policies on admin tables (decide in design.md whether to ship deny-all now or defer to Admin #2).
  - Any Kotlin DTOs / repositories / routes (schema-only).

## Capabilities

### New Capabilities
- `admin-schema`: Owns the five admin tables (`admin_users`, `admin_webauthn_credentials`, `admin_sessions`, `admin_webauthn_challenges`, `admin_actions_log`) plus their CHECK / UNIQUE / FK / partial-index constraints, the three FK backfills on operational tables (`reports`, `moderation_queue`, `chat_messages`), and the immutability-by-role design rationale for `admin_actions_log`. Schema-level only; no application-layer behavior.

### Modified Capabilities
None — the three FK-backfill targets (`reports`, `moderation_queue`, `chat_messages`) ship via the existing operational capabilities (`reports`, `moderation-queue`, `chat-conversations`). The current spec files describe nullable-UUID columns annotated as deferred; the V16 migration tightens the constraint but does NOT change requirement-level behavior — admins still SET NULL on user-level deletion, queries still treat the columns as optional. If reviewer feedback flags this as requirement-level (e.g., "the new FK changes the deletion behavior"), promote to MODIFIED.

## Impact

- **Code**: New Flyway migration `V16__admin_users.sql` (single SQL file under `backend/ktor/src/main/resources/db/migration/`). No Kotlin code changes.
- **Tests**: New schema-level Kotest integration tests under `:backend:ktor` exercising the five tables + three FK backfills. Existing tests touching `reports.reviewed_by` / `moderation_queue.resolved_by` / `chat_messages.redacted_by` continue to pass (rows are all NULL today; backfill VALIDATE is fast).
- **CI**: `migrate-supabase-parity` job runs V16 in sequence after V15; needs no parity-init SQL changes (admin tables don't depend on Supabase-provided state).
- **Schema invariants**: `csrf_token_hash` column ships as required, validating the existing `AdminSessionCsrfTokenRule` Detekt rule against shipping code. Admin-user FK `ON DELETE SET NULL` invariant (in `openspec/project.md` § Coding Conventions) is now enforced at the schema layer for all three operational tables.
- **Downstream unblocks**:
  - `suspension-unban-worker-audit-log-after-phase-3.5` FOLLOW_UPS entry — can now ship the `system` sentinel + audit-row writes in a follow-up.
  - Admin #2 (`admin-panel-ktor-htmx-bootstrap`) — Ktor route subtree with session middleware can now reference the `admin_sessions` schema.
  - Admin #3 (`admin-login-argon2-totp`) — login flow can write to `admin_users` + `admin_sessions`.
- **Documentation**: No `docs/` edits required — the canonical sections at [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) § Admin Users Schema + § Admin Actions Log Schema describe the eventual end-state, which this change observes. No drift introduced.
- **Operational/runtime**: None. Migration adds tables + constraints with no traffic-affecting changes. Backfill VALIDATE on three columns that are 100% NULL is constant-time.
