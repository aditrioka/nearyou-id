package id.nearyou.app.admin.deletionqueue

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.ratelimit.DeletionQueueExpediteRateLimiter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Read + write repository for the Hard Delete Queue
 * (`admin-hard-delete-queue` capability, admin board frame 15). Lists the
 * `deletion_requests` rows pending hard-delete (`executed_at IS NULL AND
 * cancelled_at IS NULL`) — the account right-to-erasure (UU PDP) population per
 * `docs/07-Operations.md` § Hard Delete Queue — and records the manual "expedite"
 * write that brings a pending deletion's deadline forward for the existing daily
 * hard-delete worker to execute.
 *
 * The 6th instance of the read-only-admin-viewer pattern (clones
 * [id.nearyou.app.admin.subscriptiongrace.SubscriptionGraceRepository]'s keyset
 * pagination + lenient parameterized filters + opaque cursor + `pageSize + 1`
 * has-more probe), extended with ONE mutating write. Deltas vs the grace monitor:
 *  - **Reads `deletion_requests` (not `users`)**, JOINed to `users` for the
 *    username. The existing `deletion_requests_scheduled_idx` partial index
 *    (`WHERE executed_at IS NULL AND cancelled_at IS NULL`) serves the predicate
 *    (design D4 — NO migration). Admin-module raw read of `deletion_requests` /
 *    `users` / `admin_actions_log`: the block-exclusion / raw-`FROM posts` lint
 *    rules do not fire on these tables, so no annotation is required (per the
 *    grace + privacy-flip precedent).
 *  - **ASCENDING `scheduled_hard_delete_at` order** (soonest-deadline-first — the
 *    operational priority for a deadline queue; the one viewer that inverts the
 *    usual newest-first, design D4). The sort column is `NOT NULL`, so the keyset
 *    needs no NULLS handling.
 *  - **`users` JOIN tolerates a soft-deleted target** (`deleted_at IS NOT NULL`):
 *    a pending-deletion user is typically soft-deleted during the grace window;
 *    the FK `ON DELETE CASCADE` guarantees the `users` row is still present until
 *    the worker hard-deletes both rows together (design D3), so the JOIN always
 *    resolves a username and the list applies no `deleted_at` filter.
 *
 * Unlike the grace monitor's no-op bookkeeping expedite, this expedite genuinely
 * **mutates** (design D1): it sets `scheduled_hard_delete_at = NOW()` so the
 * existing worker erases the account on its next run. The deadline advance, the
 * distinct [DeletionQueueExpediteRateLimiter] cap check, and the immutable
 * `deletion_request_expedited` audit row all commit-or-roll-back together in ONE
 * transaction (design atomicity requirement); the route itself never tombstones
 * or cascades.
 */
class DeletionQueueRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
    private val rateLimiter: DeletionQueueExpediteRateLimiter,
) {
    /**
     * Run the filtered + keyset-paginated page query. Fetches `pageSize + 1`
     * rows: the extra (if present) signals a further page and its predecessor
     * becomes the next cursor — no OFFSET, no total-count query. Every applied
     * filter value is a positionally-bound `PreparedStatement` parameter.
     */
    fun query(q: DeletionQuery): DeletionPage {
        val (conditions, params) = filterClauses(q)
        q.cursor?.let {
            // Keyset "next" predicate for the ASC ordering: row-value comparison
            // over (scheduled_hard_delete_at, id), strictly greater. `id` is the
            // deterministic tiebreaker.
            conditions += "(dr.scheduled_hard_delete_at, dr.id) > (?, ?)"
            params += java.sql.Timestamp.from(it.sortAt)
            params += it.id
        }

        val sql =
            buildString {
                append(BASE_SELECT)
                conditions.forEach { append("\n   AND ").append(it) }
                append("\n ORDER BY dr.scheduled_hard_delete_at ASC, dr.id ASC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<DeletionRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) fetched += rs.toDeletionRow()
                }
            }
        }

        val hasNext = fetched.size > q.pageSize
        val display = if (hasNext) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasNext && display.isNotEmpty()) {
                display.last().let { DeletionCursor(it.scheduledHardDeleteAt, it.id) }
            } else {
                null
            }
        return DeletionPage(rows = display, nextCursor = nextCursor)
    }

    /**
     * Total count of the pending-deletion population scoped to the SAME active
     * filters as [query] but IGNORING the keyset cursor and page size — the
     * operational signal (a spike indicates a mass-deletion or webhook problem).
     * One `COUNT(*)` over the filtered set.
     */
    fun summary(q: DeletionQuery): Long {
        val (conditions, params) = filterClauses(q.copy(cursor = null))
        val sql =
            buildString {
                append(COUNT_SELECT)
                conditions.forEach { append("\n   AND ").append(it) }
            }
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "COUNT(*) yielded no row" }
                    rs.getLong(1)
                }
            }
        }
    }

    /**
     * Record a manual expedite for the `deletion_requests` row [requestId] in ONE
     * transaction (design D1 + atomicity requirement): lock the row, reject
     * ([ExpediteOutcome.Ineligible]) when it is not a genuinely-future pending
     * request (unknown / already-executed / cancelled / already-due), gate on the
     * distinct expedite cap ([ExpediteOutcome.RateLimited]), then advance the
     * deadline (`scheduled_hard_delete_at = NOW()`) AND write exactly one immutable
     * `deletion_request_expedited` audit row. The before/after snapshots capture
     * the deadline change (the worker, not this route, performs the erasure).
     * Commit-or-rollback together — a cap rejection or audit-write failure leaves
     * the original deadline intact.
     */
    fun expedite(
        requestId: UUID,
        actingAdminId: UUID,
        reason: String,
        ip: String,
        userAgent: String?,
    ): ExpediteOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val locked = lockEligiblePendingRequest(conn, requestId)
                if (locked == null) {
                    conn.rollback()
                    return ExpediteOutcome.Ineligible
                }
                // Distinct expedite cap (design D2): checked only for an eligible
                // target (after the ineligible no-op), so an ineligible attempt
                // never consumes the cap. At/over the cap → reject, nothing written.
                if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                    conn.rollback()
                    return ExpediteOutcome.RateLimited
                }

                val newDeadline = advanceDeadlineToNow(conn, requestId)

                auditLogger.logDeletionRequestExpedited(
                    conn = conn,
                    adminId = actingAdminId,
                    deletionRequestId = requestId,
                    reason = reason,
                    beforeState =
                        buildJsonObject {
                            put("user_id", JsonPrimitive(locked.userId.toString()))
                            put("scheduled_hard_delete_at", JsonPrimitive(locked.scheduledHardDeleteAt.toString()))
                            put("source", JsonPrimitive(locked.source))
                        },
                    afterState =
                        buildJsonObject {
                            put("user_id", JsonPrimitive(locked.userId.toString()))
                            put("scheduled_hard_delete_at", JsonPrimitive(newDeadline.toString()))
                            put("expedited", JsonPrimitive(true))
                        },
                    ip = ip,
                    userAgent = userAgent,
                )

                conn.commit()
                ExpediteOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /**
     * `SELECT … FOR UPDATE` the pending request, returning its (user_id, deadline,
     * source) only when it is genuinely actionable: `executed_at IS NULL AND
     * cancelled_at IS NULL AND scheduled_hard_delete_at > NOW()`. The guard IS the
     * rejection — an unknown / executed / cancelled / already-due row yields no row
     * → null (design D7). The row lock closes the render→expedite race (the worker
     * or a user-cancel cannot mutate it between this lock and the deadline advance).
     */
    private fun lockEligiblePendingRequest(
        conn: Connection,
        requestId: UUID,
    ): LockedRequest? =
        conn.prepareStatement(
            """
            SELECT user_id, scheduled_hard_delete_at, source
              FROM deletion_requests
             WHERE id = ?
               AND executed_at IS NULL
               AND cancelled_at IS NULL
               AND scheduled_hard_delete_at > NOW()
             FOR UPDATE
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, requestId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                LockedRequest(
                    userId = rs.getObject("user_id", UUID::class.java),
                    scheduledHardDeleteAt = rs.getTimestamp("scheduled_hard_delete_at").toInstant(),
                    source = rs.getString("source"),
                )
            }
        }

    /** Advance the (already locked + verified) request's deadline to `NOW()` and
     *  return the new value for the audit snapshot. */
    private fun advanceDeadlineToNow(
        conn: Connection,
        requestId: UUID,
    ): Instant =
        conn.prepareStatement(
            "UPDATE deletion_requests SET scheduled_hard_delete_at = NOW() WHERE id = ? RETURNING scheduled_hard_delete_at",
        ).use { ps ->
            ps.setObject(1, requestId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "expedite UPDATE returned no row for a locked request" }
                rs.getTimestamp("scheduled_hard_delete_at").toInstant()
            }
        }

    /**
     * The filter half of the WHERE clause shared by [query] and [summary] so the
     * summary counts EXACTLY the filtered set the page query pages over. Every
     * value is bound positionally; nothing is interpolated. A blank `q` / `source`
     * reaches here already dropped by the route (so only meaningful filters appear).
     */
    private fun filterClauses(q: DeletionQuery): Pair<MutableList<String>, MutableList<Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        q.qUserId?.let {
            conditions += "dr.user_id = ?"
            params += it
        }
        q.qUsername?.let {
            conditions += "LOWER(u.username) = LOWER(?)"
            params += it
        }
        q.source?.let {
            // An out-of-enum value simply matches zero rows (the bound literal is
            // never an enum constant), so no validation is needed here.
            conditions += "dr.source = ?"
            params += it
        }
        return conditions to params
    }

    private fun java.sql.ResultSet.toDeletionRow(): DeletionRow =
        DeletionRow(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            username = getString("username"),
            requestedAt = getTimestamp("requested_at").toInstant(),
            scheduledHardDeleteAt = getTimestamp("scheduled_hard_delete_at").toInstant(),
            source = getString("source"),
            expeditedBy = getString("expedited_by"),
            expeditedAt = getTimestamp("expedited_at")?.toInstant(),
        )

    companion object {
        /** Fixed page size (implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50

        // LATERAL: the most-recent expedite bookkeeping row + the acting admin's
        // display name (the handled indicator). LEFT so a never-expedited row
        // still renders.
        private val BASE_SELECT =
            """
            SELECT dr.id, dr.user_id, u.username,
                   dr.requested_at,
                   dr.scheduled_hard_delete_at,
                   dr.source,
                   ex.admin_name   AS expedited_by,
                   ex.expedited_at AS expedited_at
              FROM deletion_requests dr
              JOIN users u ON u.id = dr.user_id
              LEFT JOIN LATERAL (
                  SELECT au.display_name AS admin_name, aal.created_at AS expedited_at
                    FROM admin_actions_log aal
                    JOIN admin_users au ON au.id = aal.admin_id
                   WHERE aal.action_type = 'deletion_request_expedited'
                     AND aal.target_id = dr.id::text
                   ORDER BY aal.created_at DESC
                   LIMIT 1
              ) ex ON TRUE
             WHERE dr.executed_at IS NULL
               AND dr.cancelled_at IS NULL
            """.trimIndent()

        private val COUNT_SELECT =
            """
            SELECT COUNT(*)
              FROM deletion_requests dr
              JOIN users u ON u.id = dr.user_id
             WHERE dr.executed_at IS NULL
               AND dr.cancelled_at IS NULL
            """.trimIndent()
    }
}

/** The locked, verified pending request captured before the deadline advance. */
private data class LockedRequest(
    val userId: UUID,
    val scheduledHardDeleteAt: Instant,
    val source: String,
)

/** A single rendered pending-deletion row (a `deletion_requests` row awaiting hard-delete). */
data class DeletionRow(
    val id: UUID,
    val userId: UUID,
    val username: String,
    val requestedAt: Instant,
    val scheduledHardDeleteAt: Instant,
    /** One of the `deletion_requests.source` CHECK values. */
    val source: String,
    /** Display name of the admin who last expedited this row; null when never expedited. */
    val expeditedBy: String?,
    /** When this row was last expedited; null when never expedited. */
    val expeditedAt: Instant?,
)

/**
 * The active filter set + page size + optional keyset cursor for one query.
 * `qUserId` and `qUsername` are mutually exclusive (the route parses the single
 * `q` param into exactly one of them); `source` filters on the request source.
 */
data class DeletionQuery(
    val qUserId: UUID? = null,
    val qUsername: String? = null,
    val source: String? = null,
    val cursor: DeletionCursor? = null,
    val pageSize: Int = DeletionQueueRepository.PAGE_SIZE,
)

/** One page of results plus the cursor for the next page (null = last page). */
data class DeletionPage(
    val rows: List<DeletionRow>,
    val nextCursor: DeletionCursor?,
)

/**
 * Opaque keyset cursor over `(scheduled_hard_delete_at, id)` for the ASC
 * ordering. Encoded as `base64url("<sortAtEpochMicros>|<id>")`. Opaque but NOT a
 * security boundary — it only encodes a sort position over data the admin is
 * already authorized to read. A malformed token decodes to `null` (→ first page),
 * never throws.
 */
data class DeletionCursor(
    val sortAt: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${sortAt.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): DeletionCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep <= 0 || sep == raw.length - 1) return null
                val micros = raw.substring(0, sep).toLong()
                val id = UUID.fromString(raw.substring(sep + 1))
                DeletionCursor(instantFromEpochMicros(micros), id)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun Instant.toEpochMicros(): Long = epochSecond * 1_000_000 + nano / 1_000

        private fun instantFromEpochMicros(micros: Long): Instant =
            Instant.ofEpochSecond(
                Math.floorDiv(micros, 1_000_000),
                Math.floorMod(micros, 1_000_000) * 1_000L,
            )
    }
}

/** Typed result of [DeletionQueueRepository.expedite]. */
sealed interface ExpediteOutcome {
    /** The expedite advanced the deadline + recorded one immutable audit row. */
    data object Applied : ExpediteOutcome

    /** The target is not a genuinely-future pending request (unknown / executed / cancelled / already-due); nothing written. */
    data object Ineligible : ExpediteOutcome

    /** Acting admin is at/over the expedite cap; nothing written. */
    data object RateLimited : ExpediteOutcome
}
