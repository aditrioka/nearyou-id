-- Username flag overrides: the one-shot, per-candidate admin approval store backing
-- the `admin-premium-username-oversight` capability. Canonical per
-- docs/07-Operations.md § Premium Username Change Oversight (Decision 2 of the
-- change design.md).
--
-- When an admin "accepts" a standing `username_flagged` moderation_queue row, the
-- accept side-effect writes ONE row here for the exact (user, candidate) the admin
-- judged a false positive (e.g. a legitimate Indonesian word matching the UU-ITE
-- list in an unrelated sense — docs/06 § Premium Username Customization Moderation).
-- The live `PATCH /api/v1/user/username` moderation gate then:
--   * CONSULTS this table for a non-consumed `(user_id, candidate)` match BEFORE
--     rejecting on a profanity / UU-ITE hit — on a match the moderation rejection
--     is SKIPPED (the admin pre-approved this exact handle); and
--   * CONSUMES the override (`consumed_at = NOW()`) via a conditional, rows-affected
--     -gated UPDATE inside the `SELECT … FOR UPDATE` change transaction, so the pass
--     is spent on first successful use (one-shot — a stale approval can't be reused
--     after the user changes away and back).
--
-- Scope guarantees encoded by the shape:
--   * Per-candidate (NOT per-user): `candidate` is stored normalized lowercase and
--     the gate matches on it, so an accept whitelists exactly one handle — never a
--     blanket per-user moderation bypass (which would be an abuse hole).
--   * One-shot: `consumed_at` (NULL until spent) + the gate's `WHERE consumed_at IS
--     NULL` conditional consume.
--   * UNIQUE(user_id, candidate): one standing approval per (user, candidate). A
--     re-accept of the same candidate re-arms it (`ON CONFLICT … DO UPDATE SET
--     consumed_at = NULL` — the admin service writer).
--
-- Dependency shape:
--   * `user_id REFERENCES users(id) ON DELETE CASCADE` — an approval is meaningless
--     once the user is hard-deleted (matches the V5/V6/V7 user-side CASCADE
--     precedent for per-user rows).
--   * `approved_by REFERENCES admin_users(id) ON DELETE SET NULL` — admin-user FKs
--     are `ON DELETE SET NULL` (critical invariant: never orphan-block an admin
--     account deletion; the approval audit-trail survives in `admin_actions_log`).
--
-- `candidate VARCHAR(60)` matches the `username_history` column width (V3); the
-- app-layer candidate is ≤30 (`UsernameChangeService.MAX_LENGTH`), comfortably under.
--
-- Partial-index-clean: no index here carries a `WHERE` clause, so there is no
-- `NOW()`-in-WHERE (partial-index invariant). The UNIQUE(user_id, candidate)
-- constraint serves both the upsert ON CONFLICT target and the gate's
-- `(user_id, candidate)` consult — no separate index needed at this cardinality.
--
-- Operational grant: `admin_app` gets SELECT/INSERT/UPDATE on this table out-of-band
-- via dev/scripts/provision-admin-app-staging.sh (admin writes approvals; the main
-- API role — the migration owner — already holds DML for the consume path). No
-- Flyway GRANT (matches every other `admin_app` grant; docs/07 § Data Access Pattern).

CREATE TABLE username_flag_overrides (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    candidate   VARCHAR(60) NOT NULL,
    approved_by UUID REFERENCES admin_users(id) ON DELETE SET NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    consumed_at TIMESTAMPTZ,
    UNIQUE (user_id, candidate)
);

COMMENT ON TABLE username_flag_overrides IS
    'One-shot, per-candidate admin approvals for flagged Premium username changes '
    '(admin-premium-username-oversight). Candidate stored normalized lowercase; '
    'consumed_at NULL until the override is spent on a successful change.';
