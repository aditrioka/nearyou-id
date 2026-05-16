## Context

The admin panel is the Phase 3.5 MVP-readiness gap. Three existing operational tables (`reports`, `moderation_queue`, `chat_messages`) carry nullable UUID columns with explicit "deferred to the Phase 3.5 admin-users migration" markers in their V9/V15 deployment comments. The `csrf_token_hash` requirement and the "admin-user FK `ON DELETE SET NULL`" invariant are both Detekt-enforced today, but neither has shipping schema to validate against. A long-standing FOLLOW_UPS entry (`suspension-unban-worker-audit-log-after-phase-3.5`) blocks on the admin schema reaching `main`.

The canonical schema is fully specified in [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) § Admin Users Schema (lines 636-703) + § Admin Actions Log Schema (lines 1184-1208) — five tables, three FK backfills, two partial indexes, three secondary indexes. The work is mechanical: one Flyway migration + schema-level integration tests. The design decisions in this document are about **where to draw the scope boundary**, not about schema shape (which is verbatim from the docs).

## Goals / Non-Goals

**Goals:**

- Ship the five admin tables (`admin_users`, `admin_webauthn_credentials`, `admin_sessions`, `admin_webauthn_challenges`, `admin_actions_log`) verbatim against [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) — column shapes, CHECK constraints, indexes, FK clauses, and partial-index predicates.
- Backfill the three deferred operational-table FKs (`reports.reviewed_by`, `moderation_queue.resolved_by`, `chat_messages.redacted_by`) to `admin_users(id) ON DELETE SET NULL` using the `NOT VALID + VALIDATE CONSTRAINT` pattern.
- Validate the `AdminSessionCsrfTokenRule` Detekt invariant by shipping `csrf_token_hash TEXT NOT NULL` at the DB layer (defense-in-depth).
- Provide schema-level Kotest coverage: column shapes, CHECK behavior, FK behavior, UNIQUE behavior, and partial-index existence.
- Unblock `suspension-unban-worker-audit-log-after-phase-3.5` and Admin #2 / Admin #3 in the scaffolding menu.

**Non-Goals:**

- Admin UI, admin REST routes, admin login flow — Admin #2 (`admin-panel-ktor-htmx-bootstrap`) and Admin #3 (`admin-login-argon2-totp`).
- The sentinel `system` admin user seed for worker-audit-log FK satisfaction — explicitly deferred to `system-actor-and-worker-audit-rows`.
- `admin_app` DB role GRANT/REVOKE statements — provisioned in Supabase Console per Phase 1 #28, NOT in Flyway.
- RLS policies on admin tables — require an identity source from Admin #2 session middleware.
- Any Kotlin DTOs, repositories, or HTTP routes — schema-only.
- Template engine choice (Pebble vs Freemarker) — out of scope; documented as a deferred decision in Admin #2's menu entry.

## Decisions

### D1: Single V16 migration file vs split per table

**Decision:** Single migration `V16__admin_users.sql` ships all five tables + three FK backfills + comment-replacement statements.

**Rationale:**

- The five admin tables are intrinsically coupled — `admin_webauthn_credentials`, `admin_sessions`, `admin_webauthn_challenges`, and `admin_actions_log` all FK to `admin_users`. The three FK backfills also reference `admin_users(id)`. A split (e.g., V16 = admin_users only, V17 = others) would force the FK backfills to live in V17 or later, adding ordering dependencies for no operational benefit.
- Flyway migrations are append-only and idempotent per version; an atomic five-table migration matches the precedent of V9 (`V9__reports_moderation.sql` ships `reports` + `moderation_queue` + their related triggers/views together) and V15 (`V15__chat_foundation.sql` ships conversations + chat_messages + indexes together).
- A single migration also reduces CI lane count: one Flyway invocation, one rollback story.

**Alternatives considered:**

- *Split V16 = admin_users + admin_actions_log, V17 = WebAuthn tables, V18 = FK backfills.* Rejected — creates a multi-deploy operational story where rollback after V17 leaves the schema in a half-shipped state with `admin_webauthn_credentials` FK targets but no `admin_webauthn_challenges` companion. No clean partial-shipped state for ops.
- *V16 = admin tables only, V17 = FK backfills as a separate change.* Rejected — same as above; the FK backfills are the entire point of unblocking the deferred V9/V15 markers. Splitting them adds zero benefit and one extra PR cycle.

### D2: FK backfill pattern — `NOT VALID + VALIDATE` over plain `ADD CONSTRAINT`

**Decision:** Each of the three FK backfills uses:

```sql
ALTER TABLE <table>
    ADD CONSTRAINT <table>_<col>_fkey
    FOREIGN KEY (<col>) REFERENCES admin_users(id) ON DELETE SET NULL
    NOT VALID;

ALTER TABLE <table>
    VALIDATE CONSTRAINT <table>_<col>_fkey;
```

**Rationale:**

- The V9 deferral comment explicitly prescribes this pattern: *"will ADD CONSTRAINT ... NOT VALID + VALIDATE"* ([V9:20-21](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:20)). Following the documented expected shape avoids any "why did you deviate?" reviewer round.
- The pattern matters for **future** operational consistency — `ADD CONSTRAINT NOT VALID` takes a brief `ShareRowExclusiveLock`, while `VALIDATE CONSTRAINT` takes a weaker `ShareUpdateExclusiveLock` that allows concurrent reads and INSERTs. Plain `ADD CONSTRAINT` takes the stronger `AccessExclusiveLock` blocking all reads/writes during the full-table scan.
- All three columns are 100% NULL today, so validation is constant-time either way. The pattern is preserved for the **operational shape** the docs prescribed, not for current performance.

**Alternatives considered:**

- *Plain `ADD CONSTRAINT ... REFERENCES admin_users(id) ON DELETE SET NULL`.* Rejected — diverges from the V9 deferral comment for no schema benefit.

### D3: `admin_actions_log.admin_id` FK — `NO ACTION` (default) over explicit `ON DELETE RESTRICT`

**Decision:** Match [`docs/05-Implementation.md:1191`](../../../docs/05-Implementation.md) verbatim — `admin_id UUID NOT NULL REFERENCES admin_users(id)` with **no** explicit `ON DELETE` clause. Postgres defaults to `NO ACTION`, which rejects orphaning admin deletion with `foreign_key_violation` (functionally equivalent to `RESTRICT` for our use case; the difference matters only when the constraint is `DEFERRABLE`, which this one is not).

**Rationale:**

- The canonical doc omits the clause by design — preserving audit history at the schema layer without over-specifying the rejection semantics. Adopting `NO ACTION` matches the doc verbatim and aligns with the verbatim-reconciliation rule (no silent schema reshape).
- The application layer is expected to handle admin removal via `is_active = FALSE` flag-flip, not row DELETE. The FK rejection on attempted hard-delete is a defense-in-depth, not the primary mechanism.
- Future tooling that wants to hard-delete an admin (e.g., GDPR-style deletion of a former employee's row) can either (a) re-assign their audit rows to the `system` sentinel before deletion, or (b) introduce an explicit `ON DELETE RESTRICT` clause via a future migration that also adds the data-migration path. Neither belongs in V16.

**Alternatives considered:**

- *Explicit `ON DELETE RESTRICT`.* Rejected — diverges from the verbatim doc. Worth a follow-up amendment to the canonical doc IF reviewers prefer explicit; but in absence of that, verbatim wins.
- *Explicit `ON DELETE NO ACTION`.* Rejected — redundant with Postgres default; adds noise without information.

### D4: `admin_app` REVOKE statements — operational, not Flyway

**Decision:** V16 ships **without** `GRANT` or `REVOKE` statements. Role-level permissions (notably `REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app`) are provisioned in Supabase Console per [`docs/08-Roadmap-Risk.md:38`](../../../docs/08-Roadmap-Risk.md) Pre-Phase 1 #28.

**Rationale:**

- The `admin_app` role does NOT exist in the integration-test Postgres (a vanilla `postgis/postgis:16-3.4` container, see [`dev/docker-compose.yml`](../../../dev/docker-compose.yml)). Including a `REVOKE` against a non-existent role would fail the V16 migration on every local dev / CI run.
- A conditional `DO $$ BEGIN ... EXCEPTION WHEN undefined_object THEN ... END $$` block could no-op the REVOKE when the role is missing, but adds non-trivial complexity for what is fundamentally an operational task (Supabase-side role configuration).
- The repository convention per Phase 1 #28 is that the `admin_app` role lives in Supabase Console, with the connection string in GCP Secret Manager (`admin-app-db-connection-string`). Schema migrations stay environment-portable; role permissions are environment-specific operations.
- The Admin #2 change is the natural place to codify the REVOKE procedure as a documented runbook step (so the first admin Ktor service that connects via the `admin_app` role enforces the immutability of `admin_actions_log` from day one of admin usage).

**Alternatives considered:**

- *Include `REVOKE` wrapped in `DO $$ EXCEPTION WHEN undefined_object`.* Rejected — operational coupling for no schema benefit; muddies the migration with role-aware conditional logic.
- *Add a Phase 3.5 Flyway-managed role provisioning step.* Rejected — fights Supabase's role model (the Supabase admin UI is the canonical surface for role + RLS).
- *Land the REVOKE in V16 unconditionally.* Rejected — breaks local dev and CI integration tests on the very next push.

### D5: RLS on admin tables — defer to Admin #2 era

**Decision:** V16 does NOT enable RLS on any admin table. RLS lands in a separate change that accompanies or follows Admin #2 / Admin #3 (whichever first establishes a session-context identity source).

**Rationale:**

- RLS without a populated identity claim source is effectively deny-all. The integration-test Postgres has no Supabase auth surface, so enabling RLS would block the schema-level tests in this very change.
- The pre-Admin-#2 defenses are layered: (a) the scoped `admin_app` DB role + Phase 1 #28 connection-string isolation, (b) network-layer IAP per [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Security Layer 1, (c) Detekt's RLS-policy-change test invariant ("JWT `sub` not in `public.users` → deny") will apply when admin RLS lands, ensuring it ships with a test from day one.
- Enabling RLS here as a deny-all "tripwire" against pre-Admin-#2 misuse would surface as a confusing failure mode for the first Admin #2 implementer ("why is every query returning empty?"). A clear absence is easier to reason about than a deny-all default.

**Alternatives considered:**

- *Ship deny-all RLS as defense-in-depth.* Rejected — actively breaks future admin work.
- *Ship `service_role`-only RLS.* Rejected — Supabase `service_role` is a higher-privilege role used by triggers and the Realtime publication, not by admin code paths. Mismatched identity layer.

### D6: Sentinel `system` admin user — explicit follow-up deferral

**Decision:** V16 does NOT INSERT any rows. The sentinel `system` admin row required by the `suspension-unban-worker-audit-log-after-phase-3.5` follow-up ships in `system-actor-and-worker-audit-rows`.

**Rationale:**

- The `admin_users.password_hash TEXT NOT NULL` requirement (from the canonical schema) conflicts with the FOLLOW_UPS entry's description of a sentinel "with no `password_hash`". Resolving this requires either (a) amending the canonical schema to allow NULL `password_hash` with an auth-bypass CHECK constraint, or (b) seeding a placeholder hash (e.g., a cryptographically-random Argon2id-shaped string that cannot match any submitted password).
- Either decision belongs to the change that consumes the sentinel, because that change is also the one that introduces the auth-bypass-guard regression test described in the FOLLOW_UPS action items. Shipping the seed in V16 without also shipping the auth-bypass guard creates a brief window where an exploit is technically possible (a NULL `password_hash` matching an empty submission against a naive auth handler — though no admin auth handler exists yet, so this risk is theoretical pre-Admin-#3).
- The FOLLOW_UPS entry already captures this gap explicitly. No new follow-up entry is needed; the existing one's action items remain valid.

**Alternatives considered:**

- *Seed the sentinel + amend `password_hash` to nullable + ship the CHECK constraint in V16.* Rejected — pulls in scope from two future changes without clear benefit; the CHECK constraint design depends on the admin-login query shape, which doesn't exist until Admin #3.
- *Seed the sentinel with a placeholder Argon2id hash.* Rejected — picks the wrong half of the trade-off without the consuming follow-up's analysis. The follow-up has freedom to choose either approach.

### D7: Comment-replacement statements for backfilled FK columns

**Decision:** For each of the three FK-backfilled columns (`reports.reviewed_by`, `moderation_queue.resolved_by`, `chat_messages.redacted_by`), V16 issues a fresh `COMMENT ON COLUMN` statement that replaces the now-obsolete "deferred to the Phase 3.5 admin-users migration" text with a description of the now-shipped FK relationship.

**Rationale:**

- The deferral comments at [V9:73](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:73), [V9:111](../../../backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:111), and [V15:99](../../../backend/ktor/src/main/resources/db/migration/V15__chat_foundation.sql:99) are user-facing documentation in the schema layer. Leaving them stale after the FK lands produces confusing "the comment says deferred but the constraint exists" situations for future schema readers.
- A regression scenario in the spec validates the comment-replacement happened (`pg_description` join confirms the substring "deferred to the Phase 3.5 admin-users migration" is no longer present on the affected columns).
- The Flyway migration text in the V9/V15 files themselves is NOT modified (immutability of past migrations is a Flyway invariant). Only the runtime column comments (`pg_description`) are updated via V16's `COMMENT ON COLUMN` statements.

**Alternatives considered:**

- *Leave the deferral comments in place.* Rejected — guarantees confusion for future readers; the cost of three `COMMENT ON COLUMN` statements is trivial.
- *Modify V9 and V15 source SQL files to remove the deferral comments.* Rejected — violates Flyway's immutability invariant (modifying applied migrations breaks checksum validation on any environment that already ran V9 / V15).

## Risks / Trade-offs

- **Risk:** Flyway VALIDATE CONSTRAINT on the three operational-table FKs could acquire a `ShareUpdateExclusiveLock` that blocks other DDL briefly. → **Mitigation:** rows are 100% NULL today (verified by `SELECT count(*) FROM reports WHERE reviewed_by IS NOT NULL` + analogous on the other two tables, all returning 0); VALIDATE is constant-time, lock window is sub-millisecond. Confirmed pre-merge via the staging deploy smoke step.

- **Risk:** Future hard-delete of an `admin_users` row that has `admin_actions_log` references will be rejected at the schema layer (D3), surfacing as a `foreign_key_violation` SQLState `23503` to any caller attempting the hard delete. → **Mitigation:** intentional — application-layer code must use `is_active = FALSE` flag-flip per the audit-trail-preservation design. The error message is clear and the rejection is the desired behavior. Documented in D3.

- **Risk:** Shipping `csrf_token_hash NOT NULL` before any admin code exists means the schema enforces a constraint that no application path satisfies today. → **Mitigation:** zero rows exist, so the NOT NULL has no immediate enforcement target. The Admin #2 / Admin #3 implementations will need to populate it on every session insert from day one, matching the `AdminSessionCsrfTokenRule` Detekt invariant.

- **Risk:** The deferred operational concerns (`admin_app` REVOKE, RLS policies, sentinel seed, auth-bypass guard CHECK) form a cluster of follow-up work. → **Mitigation:** each is documented explicitly:
  - REVOKE → runbook step landing with Admin #2.
  - RLS → follow-up change accompanying Admin #2 / Admin #3.
  - Sentinel seed + auth-bypass guard → existing FOLLOW_UPS entry `suspension-unban-worker-audit-log-after-phase-3.5`.

- **Trade-off:** Choosing verbatim doc-fidelity over defensive explicit clauses (D3 `NO ACTION` default, D7 column comments) means future readers must read the docs alongside the migration to understand the omitted clauses. → **Mitigation:** the spec explicitly documents the design intent of each omission (in the requirement text + scenario notes), and `design.md` D3 records the rationale for future spelunkers.

## Migration Plan

1. **Author V16**: write `backend/ktor/src/main/resources/db/migration/V16__admin_users.sql` with all five `CREATE TABLE` statements + index creations + three `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID` + three `VALIDATE CONSTRAINT` + three `COMMENT ON COLUMN` replacement statements. Verify file is `\n`-terminated and matches the project's SQL style (consistent with V9 / V15).

2. **Author schema-level Kotest spec**: a new test class under `backend/ktor/src/test/kotlin/id/nearyou/app/admin/AdminSchemaSpec.kt` (location matches the `admin` package convention) exercising every scenario in `specs/admin-schema/spec.md`. The Kotest project config auto-boots Flyway, so the test queries `information_schema` and `pg_indexes` against the post-migration DB.

3. **Validate locally**: run `./gradlew :backend:ktor:test --tests "*AdminSchemaSpec*"` against the dev compose stack. Run the full lint suite (`./gradlew ktlintCheck detekt :lint:detekt-rules:test`) to ensure no rule fires on the new SQL or test.

4. **Push to branch + open PR**: branch name `admin-schema-bootstrap`. PR title `docs(openspec): propose admin-schema-bootstrap` opens via `/next-change`; will retitle to `feat(admin-schema): admin-schema-bootstrap` (or similar) at first feat commit per the one-PR-per-change convention.

5. **Iterate via auto-review**: qodo + sub-agent review the proposal. Apply blocking findings on the same branch.

6. **Implement (via `/opsx:apply`)**: feat commits land on the same branch — `V16__admin_users.sql` + `AdminSchemaSpec.kt`.

7. **Pre-archive smoke step**: trigger staging deploy on the branch via `gh workflow run deploy-staging.yml --ref admin-schema-bootstrap`. Verify the V16 migration applied by querying `SELECT count(*) FROM admin_users` (expect 0) and `SELECT constraint_name FROM information_schema.table_constraints WHERE table_name = 'admin_actions_log' AND constraint_type = 'FOREIGN KEY'` (expect one row referencing admin_users). The migration runs as the `flyway_migrator` role (Cloud Run Jobs path); VALIDATE CONSTRAINT is constant-time because all three operational-table columns are 100% NULL today (verified in tasks.md task 1.4 and `task 6.4` re-verification post-deploy).

8. **Archive (via `/opsx:archive`)**: `openspec archive admin-schema-bootstrap` → moves the change under `openspec/changes/archive/`, syncs `openspec/specs/admin-schema/spec.md` from the change delta. Squash-merge to `main` → auto-deploys staging.

9. **Rollback path** (if discovered post-merge): write a V17 migration that DROPs the three FK constraints, then DROPs the five admin tables in reverse-dependency order (`admin_actions_log` → `admin_webauthn_challenges` → `admin_sessions` → `admin_webauthn_credentials` → `admin_users`). This is the ONLY rollback path — Flyway does not support migration undo in our project. The cost of V17-as-rollback is low because the change ships zero application code that depends on the tables.

## Open Questions

None blocking.

Two soft questions surfaced during proposal authoring; both can be deferred to reviewer comments:

1. **Should the V16 file include a Detekt-allowlist comment in the SQL header** (e.g., `-- @allow-raw-admin-write: schema-bootstrap`) for future consistency, even though no Detekt rule fires on SQL files today? Default answer: no — comments that don't satisfy any current rule are noise. Add when a rule that needs allowlisting actually ships.

2. **Should the deferred `admin_app` REVOKE statements be tracked as a new FOLLOW_UPS entry**? Default answer: no — the operational task is captured in [`docs/07-Operations.md`](../../../docs/07-Operations.md) § Data Access Pattern (already canonical) and will be codified as a runbook step in Admin #2's design. No new entry needed.
