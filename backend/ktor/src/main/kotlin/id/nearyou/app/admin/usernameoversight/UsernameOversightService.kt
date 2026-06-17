package id.nearyou.app.admin.usernameoversight

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.ratelimit.UsernameOversightActionRateLimiter
import id.nearyou.data.repository.UsernameFlagOverrideRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import javax.sql.DataSource

/**
 * Service layer for the Premium Username Change Oversight writes
 * (`admin-premium-username-oversight` capability). Owns the single-transaction
 * orchestration for the two POST actions; the route does CSRF + role + UUID-parse
 * + the `resolution` allowlist BEFORE calling in.
 *
 * Each write opens ONE transaction and (mirroring `admin-report-queue-resolution-
 * actions` design D4 + the reserved-username editor):
 *  1. checks the per-admin audit-log-COUNT rate cap on that connection (over-cap →
 *     in-band reject, no mutation, no audit row — never a 5xx);
 *  2. runs the conditional `WHERE`-guarded mutation (zero rows = benign no-op:
 *     already-resolved / already-released, no audit row — this also serializes the
 *     two-admins-on-the-same-row race);
 *  3. on rows-affected > 0 AND (for resolve) `accept_flagged_username` with a usable
 *     candidate, writes the per-candidate override via the SHARED
 *     [UsernameFlagOverrideRepository.upsertApproval] (the live coupling — the
 *     SAME repo the `PATCH /api/v1/user/username` gate consults/consumes, NOT a
 *     duplicate); and
 *  4. writes exactly one immutable `admin_actions_log` row,
 * committing — or rolling back leaving nothing (no override or resolution without
 * its audit row, no audit row without the state change).
 *
 * The `accept` side-effect is per-candidate + one-shot by construction:
 * `upsertApproval(targetId, candidate.lowercase(), adminId)` whitelists exactly the
 * approved handle (candidate stored normalized lowercase) and re-arms `consumed_at`
 * to NULL, so it grants exactly one future pass for that one handle — never a
 * blanket per-user moderation bypass (design Decision 2).
 */
class UsernameOversightService(
    private val dataSource: DataSource,
    private val repository: UsernameOversightRepository,
    private val flagOverrides: UsernameFlagOverrideRepository,
    private val rateLimiter: UsernameOversightActionRateLimiter,
    private val auditLogger: AdminAuditLogger,
) {
    /**
     * Resolve a pending `username_flagged` queue row in ONE transaction (steps
     * above). On `accept_flagged_username` with a non-blank candidate, additionally
     * upserts the override for `(target_id, candidate)`. An accept on a flag with a
     * blank/absent `notes` candidate (e.g. a legacy pre-V23 flag) resolves + audits
     * but writes NO override and surfaces [FlagResolveOutcome.AcceptedNoCandidate]
     * (an in-band note, never a 5xx).
     *
     * Server-side allowlist of [resolution] ∈ {accept_flagged_username,
     * reject_flagged_username} is the route's responsibility (rejected BEFORE this
     * call); [resolution] arriving here is already a valid [FlagResolution].
     */
    fun resolveFlag(
        queueId: UUID,
        resolution: FlagResolution,
        actingAdminId: UUID,
        ip: String,
        userAgent: String?,
    ): FlagResolveOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // (1) Rate-limit gate — at/over cap rejects with NO mutation, NO
                // audit row. Checked BEFORE the conditional update so a capped
                // admin's attempt leaves the queue untouched.
                if (rateLimiter.isAtOrOverCap(
                        conn,
                        actingAdminId,
                        UsernameOversightActionRateLimiter.ACTION_FLAG_RESOLVED,
                        UsernameOversightActionRateLimiter.FLAG_RESOLVE_CAP,
                    )
                ) {
                    conn.rollback()
                    return FlagResolveOutcome.RateLimited
                }

                // (2) Conditional update under the row lock.
                val result =
                    repository.resolveFlag(conn, queueId, resolution.wire, actingAdminId)
                        ?: run {
                            conn.rollback()
                            return FlagResolveOutcome.NotFound
                        }

                // Out-of-scope: the id resolved to a row whose trigger isn't
                // username_flagged (a content trigger owned by admin-report-queue).
                // The conditional WHERE already left it untouched (rowsAffected 0);
                // surface a distinct outcome so the route renders the right message.
                if (result.trigger != TRIGGER_USERNAME_FLAGGED) {
                    conn.rollback()
                    return FlagResolveOutcome.NotUsernameFlagged
                }

                if (result.rowsAffected == 0) {
                    // username_flagged but already resolved → benign no-op, no audit.
                    conn.rollback()
                    return FlagResolveOutcome.NoOpAlreadyResolved
                }

                // (3) Accept side-effect: write the per-candidate override (only when
                // there is a usable candidate to approve).
                val candidate = result.candidate?.trim()?.takeIf { it.isNotEmpty() }
                val approvedNoCandidate =
                    resolution == FlagResolution.ACCEPT && candidate == null
                if (resolution == FlagResolution.ACCEPT && candidate != null) {
                    flagOverrides.upsertApproval(conn, result.targetId, candidate.lowercase(), actingAdminId)
                }

                // (4) One audit row. after_state records the resolution and, on a
                // candidate-bearing accept, the approved (normalized) candidate.
                auditLogger.logUsernameFlagResolved(
                    conn = conn,
                    adminId = actingAdminId,
                    targetUserId = result.targetId,
                    beforeState = beforeFlagState(),
                    afterState = afterFlagState(resolution, if (resolution == FlagResolution.ACCEPT) candidate?.lowercase() else null),
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                if (approvedNoCandidate) FlagResolveOutcome.AcceptedNoCandidate else FlagResolveOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /**
     * Force-release a held handle in ONE transaction: rate-limit gate (5/hour
     * `username_hold_released`), the conditional release, then on rows-affected > 0
     * one `username_hold_released` audit row with the prior `released_at` in
     * `before_state`. An already-elapsed hold is a benign no-op (no audit row).
     */
    fun releaseHold(
        historyId: UUID,
        actingAdminId: UUID,
        ip: String,
        userAgent: String?,
    ): HoldReleaseOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                if (rateLimiter.isAtOrOverCap(
                        conn,
                        actingAdminId,
                        UsernameOversightActionRateLimiter.ACTION_HOLD_RELEASED,
                        UsernameOversightActionRateLimiter.HOLD_RELEASE_CAP,
                    )
                ) {
                    conn.rollback()
                    return HoldReleaseOutcome.RateLimited
                }

                val result =
                    repository.releaseHold(conn, historyId)
                        ?: run {
                            conn.rollback()
                            return HoldReleaseOutcome.NotFound
                        }
                if (result.rowsAffected == 0) {
                    conn.rollback()
                    return HoldReleaseOutcome.NoOpAlreadyReleased
                }

                auditLogger.logUsernameHoldReleased(
                    conn = conn,
                    adminId = actingAdminId,
                    historyId = historyId,
                    beforeState = beforeHoldState(result.priorReleasedAt.toString()),
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                HoldReleaseOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    private companion object {
        const val TRIGGER_USERNAME_FLAGGED = "username_flagged"

        fun beforeFlagState(): JsonObject =
            buildJsonObject {
                put("status", JsonPrimitive("pending"))
            }

        fun afterFlagState(
            resolution: FlagResolution,
            approvedCandidate: String?,
        ): JsonObject =
            buildJsonObject {
                put("status", JsonPrimitive("resolved"))
                put("resolution", JsonPrimitive(resolution.wire))
                approvedCandidate?.let { put("candidate", JsonPrimitive(it)) }
            }

        fun beforeHoldState(priorReleasedAt: String): JsonObject =
            buildJsonObject {
                put("released_at", JsonPrimitive(priorReleasedAt))
            }
    }
}

/**
 * The two IN-SCOPE flag resolutions accepted by `POST /admin/username-oversight/
 * flags/{queue_id}/resolve`. The route maps the `resolution` form field onto this
 * enum via [fromWire] BEFORE the service call (server-side allowlist) — anything
 * else (the `admin-report-queue` content resolutions, `delete`, or garbage) maps
 * to null → rejected with no DB write, so an out-of-allowlist value never reaches
 * the `moderation_queue.resolution` CHECK as a 5xx. This capability OWNS these two
 * values (which `admin-report-queue`'s endpoint explicitly rejects as out-of-scope).
 */
enum class FlagResolution(val wire: String) {
    ACCEPT("accept_flagged_username"),
    REJECT("reject_flagged_username"),
    ;

    companion object {
        fun fromWire(value: String?): FlagResolution? = entries.firstOrNull { it.wire == value }
    }
}

/** Typed result of [UsernameOversightService.resolveFlag]. */
sealed interface FlagResolveOutcome {
    /** Resolved + (on candidate-bearing accept) override written + one audit row. */
    data object Applied : FlagResolveOutcome

    /** Accept on a flag with no usable `notes` candidate: resolved + audited, NO
     *  override (nothing to approve) — surfaced as an in-band note, not a 5xx. */
    data object AcceptedNoCandidate : FlagResolveOutcome

    /** The flag was already `resolved` — benign no-op, no second audit row. */
    data object NoOpAlreadyResolved : FlagResolveOutcome

    /** No `moderation_queue` row resolves to the id. */
    data object NotFound : FlagResolveOutcome

    /** The id resolves to a row whose `trigger` is NOT `username_flagged` (a
     *  content trigger owned by `admin-report-queue`); no mutation, no audit. */
    data object NotUsernameFlagged : FlagResolveOutcome

    /** Acting admin is at/over the 10/hour cap; queue stays `pending`, no audit. */
    data object RateLimited : FlagResolveOutcome
}

/** Typed result of [UsernameOversightService.releaseHold]. */
sealed interface HoldReleaseOutcome {
    /** The hold was released (`released_at = NOW()`) + one audit row. */
    data object Applied : HoldReleaseOutcome

    /** The hold had already elapsed (`released_at <= NOW()`) — benign no-op, no
     *  audit row. */
    data object NoOpAlreadyReleased : HoldReleaseOutcome

    /** No `username_history` row resolves to the id. */
    data object NotFound : HoldReleaseOutcome

    /** Acting admin is at/over the 5/hour cap; the hold is unchanged, no audit. */
    data object RateLimited : HoldReleaseOutcome
}
