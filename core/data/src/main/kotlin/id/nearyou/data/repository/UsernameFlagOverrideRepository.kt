package id.nearyou.data.repository

import java.sql.Connection
import java.util.UUID

/**
 * One-shot, per-candidate admin-approval store for flagged Premium username changes
 * — the `username_flag_overrides` table (V23). Shared between the two halves of
 * `admin-premium-username-oversight`:
 *  - the admin oversight surface writes approvals via [upsertApproval] when an
 *    operator accepts a standing `username_flagged` flag; and
 *  - the live `PATCH /api/v1/user/username` moderation gate
 *    ([id.nearyou.app.user.UsernameChangeService]) consults via [findUnconsumed]
 *    BEFORE rejecting a moderation hit and consumes via [consume] inside its
 *    `SELECT … FOR UPDATE` change transaction.
 *
 * Every `candidate` argument MUST already be normalized lowercase by the caller (the
 * gate lowercases the candidate it is about to set; the admin writer lowercases the
 * flagged candidate it approves) — the override is scoped to exactly the handle the
 * admin judged, never a blanket per-user moderation bypass (design Decision 2).
 *
 * All methods take the caller's [Connection] so they participate in the caller's
 * transaction — there is no internal pool. This mirrors the
 * [ModerationQueueRepository] caller-supplied-connection idiom.
 */
interface UsernameFlagOverrideRepository {
    /**
     * `SELECT 1 FROM username_flag_overrides WHERE user_id = ? AND candidate = ? AND
     * consumed_at IS NULL LIMIT 1`.
     *
     * `true` when a non-consumed approval matches `(userId, candidate)` — the gate
     * SKIPS the moderation rejection for this exact handle. `false` when no such
     * approval exists (no row, or the only matching row is already consumed).
     *
     * `candidate` MUST be normalized lowercase.
     */
    fun findUnconsumed(
        conn: Connection,
        userId: UUID,
        candidate: String,
    ): Boolean

    /**
     * Conditional, rows-affected-gated consume:
     * `UPDATE username_flag_overrides SET consumed_at = NOW() WHERE user_id = ? AND
     * candidate = ? AND consumed_at IS NULL`.
     *
     * Returns the number of rows updated: `1` when this call spent the override, `0`
     * when no non-consumed override matched (already consumed — e.g. a concurrent
     * same-user change won the `FOR UPDATE` lock and spent it first, OR no override
     * existed). The `WHERE consumed_at IS NULL` predicate — not merely running inside
     * a transaction — is what enforces the one-shot guarantee: two concurrent
     * same-user changes serialize on the per-user row lock and at most one observes a
     * rows-affected of 1.
     *
     * The gate MUST run this INSIDE the same `SELECT … FOR UPDATE` change transaction
     * (not the pre-lock read connection) and re-moderate the candidate if it returns
     * `0`, so consume + rename + history + notification commit or roll back together.
     *
     * `candidate` MUST be normalized lowercase.
     */
    fun consume(
        conn: Connection,
        userId: UUID,
        candidate: String,
    ): Int

    /**
     * Upsert an admin approval (the `accept_flagged_username` side-effect):
     * `INSERT INTO username_flag_overrides (user_id, candidate, approved_by) VALUES
     * (?, ?, ?) ON CONFLICT (user_id, candidate) DO UPDATE SET approved_by =
     * EXCLUDED.approved_by, approved_at = NOW(), consumed_at = NULL`.
     *
     * `ON CONFLICT … DO UPDATE` re-arms an existing `(user_id, candidate)` row — a
     * re-accept of the same candidate (whether the prior approval was consumed or not)
     * resets `consumed_at` to NULL and re-stamps `approved_by`/`approved_at`, granting
     * a fresh one-shot pass.
     *
     * `candidate` MUST be normalized lowercase. `approvedBy` is the acting admin id
     * (FK to `admin_users`, `ON DELETE SET NULL`).
     */
    fun upsertApproval(
        conn: Connection,
        userId: UUID,
        candidate: String,
        approvedBy: UUID,
    )
}
