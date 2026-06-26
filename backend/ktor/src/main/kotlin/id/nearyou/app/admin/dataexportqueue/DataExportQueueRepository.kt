package id.nearyou.app.admin.dataexportqueue

import id.nearyou.app.account.DataExportProcessOutcome
import id.nearyou.app.account.DataExportSingleProcessor
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.ratelimit.DataExportTriggerRateLimiter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Read + write repository for the Data Export Queue (`admin-data-export-queue`
 * capability, admin board frame 16). Lists `data_export_requests` rows (the UU-PDP
 * data-portability lifecycle, V30) JOINed to `users` for the requester username, and
 * records the manual "trigger" write that re-runs a stalled/failed export through the
 * producer's single-request pipeline.
 *
 * Clones the shipped read-only-admin-viewer skeleton (keyset pagination + lenient
 * parameterized filters + opaque cursor + `pageSize + 1` has-more probe — mirrors
 * [id.nearyou.app.admin.deletionqueue.DeletionQueueRepository] /
 * [id.nearyou.app.admin.subscriptiongrace.SubscriptionGraceRepository]), extended with
 * ONE mutating write. Deltas vs the hard-delete queue:
 *  - **Reads `data_export_requests`** (not `deletion_requests`), JOINed to `users` for
 *    the username. The existing `data_export_requests_user_recent_idx` /
 *    `data_export_requests_pending_idx` serve the predicates (design D3 — NO migration).
 *    Admin-module raw read of `data_export_requests` / `users` / `admin_actions_log`:
 *    the block-exclusion / raw-`FROM posts` lint rules do not fire on these tables, so
 *    no annotation is required (per the grace + deletion-queue precedent).
 *  - **DESCENDING `requested_at` order** (newest-first — the usual admin-viewer default,
 *    contrast the deletion queue's soonest-deadline-first inversion).
 *  - **Identity-only PII** (design + spec): the read projection EXCLUDES `r2_object_key`,
 *    the signed download URL, and `download_expires_at` values — only the request
 *    identity (id + user) + lifecycle status are surfaced. The archive is the requester's
 *    own personal data, delivered only to them via a short-lived signed link.
 *
 * Unlike the deletion-queue's deadline-advance, the trigger genuinely RE-RUNS the export:
 * it re-enqueues the row (`failed → pending`) where needed and drives the producer's
 * [DataExportSingleProcessor] seam for that one id (design D1 — one export path, two
 * callers). The distinct [DataExportTriggerRateLimiter] cap check, the conditional
 * re-enqueue, and the immutable `data_export_triggered` audit row commit-or-roll-back
 * together in ONE transaction; the producer drive runs AFTER that transaction commits
 * (its own `claimPending` guard serializes against a racing scheduler).
 */
class DataExportQueueRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
    private val rateLimiter: DataExportTriggerRateLimiter,
    private val processor: DataExportSingleProcessor,
) {
    /**
     * Run the filtered + keyset-paginated page query. Fetches `pageSize + 1` rows: the
     * extra (if present) signals a further page and its predecessor becomes the next
     * cursor — no OFFSET, no total-count query. Every applied filter value is a
     * positionally-bound `PreparedStatement` parameter.
     */
    fun query(q: DataExportQuery): DataExportPage {
        val (conditions, params) = filterClauses(q)
        q.cursor?.let {
            // Keyset "next" predicate for the DESC ordering: row-value comparison over
            // (requested_at, id), strictly less. `id` is the deterministic tiebreaker.
            conditions += "(r.requested_at, r.id) < (?, ?)"
            params += java.sql.Timestamp.from(it.sortAt)
            params += it.id
        }

        val sql =
            buildString {
                append(BASE_SELECT)
                conditions.forEach { append("\n   AND ").append(it) }
                append("\n ORDER BY r.requested_at DESC, r.id DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<DataExportRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) fetched += rs.toDataExportRow()
                }
            }
        }

        val hasNext = fetched.size > q.pageSize
        val display = if (hasNext) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasNext && display.isNotEmpty()) {
                display.last().let { DataExportCursor(it.requestedAt, it.id) }
            } else {
                null
            }
        return DataExportPage(rows = display, nextCursor = nextCursor)
    }

    /**
     * Total count scoped to the SAME active filters as [query] but IGNORING the keyset
     * cursor and page size — the in-window operational signal. One `COUNT(*)` over the
     * filtered set.
     */
    fun summary(q: DataExportQuery): Long {
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
     * Manually trigger a re-run for the `data_export_requests` row [requestId] (design
     * D1 + D4). In ONE transaction: lock the row, classify its status, and —
     *  - `failed`  → re-enqueue (`failed → pending`, clear `started_at`/`error`, keep
     *                `attempt_count`) then audit + commit;
     *  - `pending` → confirm (stuck QUEUED) then audit + commit (no row mutation — the
     *                drive's `claimPending` performs the `pending → processing` transition);
     *  - `processing`/`ready`/`expired`/unknown → benign no-op ([TriggerOutcome.NotTriggerable]
     *                / [TriggerOutcome.Unknown]); NO mutation, NO audit row.
     *
     * The distinct [DataExportTriggerRateLimiter] cap is checked on the transaction's
     * connection for a triggerable target only (an ineligible attempt never consumes the
     * cap); at/over → [TriggerOutcome.RateLimited], nothing written. The re-enqueue UPDATE
     * is wrapped in its OWN `23505` catch — a DISTINCT statement from the producer's INSERT
     * `insertPendingOrReturnActive` handler — mapping a `data_export_requests_one_active_idx`
     * violation (the user already holds another active request) to [TriggerOutcome.AlreadyActive]
     * (benign no-op, no audit). AFTER the transaction commits, the producer
     * [DataExportSingleProcessor] seam drives the single request (its claim guard serializes
     * against a racing scheduler — a `SKIPPED` drive after a successful re-enqueue still
     * counts as the one audited trigger; the export is delivered by whichever claimant wins).
     */
    suspend fun trigger(
        requestId: UUID,
        actingAdminId: UUID,
        reason: String,
        ip: String,
        userAgent: String?,
    ): TriggerOutcome {
        val prepared =
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val current = lockStatus(conn, requestId)
                    if (current == null) {
                        conn.rollback()
                        return TriggerOutcome.Unknown
                    }
                    if (current !in TRIGGERABLE_STATUSES) {
                        conn.rollback()
                        return TriggerOutcome.NotTriggerable(current)
                    }
                    // Distinct trigger cap (design D4): checked only for a triggerable
                    // target, so an ineligible attempt never consumes the cap.
                    if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                        conn.rollback()
                        return TriggerOutcome.RateLimited
                    }

                    // The state transition that the audit row is keyed on.
                    if (current == STATUS_FAILED) {
                        try {
                            reEnqueueFailed(conn, requestId)
                        } catch (e: SQLException) {
                            // 23505 = unique_violation on data_export_requests_one_active_idx:
                            // the user already holds another active (pending|processing) row.
                            // DISTINCT from the producer's INSERT handler — map to a benign no-op.
                            if (e.sqlState == SQLSTATE_UNIQUE_VIOLATION) {
                                conn.rollback()
                                return TriggerOutcome.AlreadyActive
                            }
                            throw e
                        }
                    }

                    auditLogger.logDataExportTriggered(
                        conn = conn,
                        adminId = actingAdminId,
                        requestId = requestId,
                        reason = reason,
                        beforeState = buildJsonObject { put("status", JsonPrimitive(current)) },
                        afterState =
                            buildJsonObject {
                                // failed → pending (re-enqueued); pending stays pending (the
                                // drive claims it). Either way the trigger is recorded.
                                put("status", JsonPrimitive(STATUS_PENDING))
                                put("triggered", JsonPrimitive(true))
                            },
                        ip = ip,
                        userAgent = userAgent,
                    )

                    conn.commit()
                    current
                } catch (e: Throwable) {
                    runCatching { conn.rollback() }
                    throw e
                } finally {
                    runCatching { conn.autoCommit = true }
                }
            }

        // The transition + audit are committed. Drive the producer pipeline for this one
        // id OUTSIDE the transaction (its claim guard serializes against the scheduler).
        val processOutcome = processor.processSingle(requestId)
        return TriggerOutcome.Triggered(beforeStatus = prepared, processOutcome = processOutcome)
    }

    /**
     * `SELECT … FOR UPDATE` the request's current status, or null when the id is unknown.
     * The row lock closes the render→trigger race (the scheduler or a concurrent trigger
     * cannot mutate it between this lock and the re-enqueue/audit).
     */
    private fun lockStatus(
        conn: Connection,
        requestId: UUID,
    ): String? =
        conn.prepareStatement("SELECT status FROM data_export_requests WHERE id = ? FOR UPDATE").use { ps ->
            ps.setObject(1, requestId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("status") else null }
        }

    /**
     * Re-enqueue a (locked, verified-`failed`) request to `pending`: clear `started_at`
     * + `error`, keep `attempt_count` (the retry history is preserved). Raises `23505`
     * against `data_export_requests_one_active_idx` if the user already holds another
     * active row — caught by the caller and mapped to the "already active" no-op.
     */
    private fun reEnqueueFailed(
        conn: Connection,
        requestId: UUID,
    ) {
        conn.prepareStatement(SQL_REENQUEUE).use { ps ->
            ps.setObject(1, requestId)
            ps.executeUpdate()
        }
    }

    /**
     * The filter half of the WHERE clause shared by [query] and [summary] so the summary
     * counts EXACTLY the filtered set the page query pages over. Every value is bound
     * positionally; nothing is interpolated. A blank `q` reaches here already dropped by
     * the route (so only meaningful filters appear).
     */
    private fun filterClauses(q: DataExportQuery): Pair<MutableList<String>, MutableList<Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        q.status?.let {
            // An out-of-enum value simply matches zero rows (the bound literal is never an
            // enum constant), so no validation is needed here.
            conditions += "r.status = ?"
            params += it
        }
        q.qUserId?.let {
            conditions += "r.user_id = ?"
            params += it
        }
        q.qUsername?.let {
            conditions += "LOWER(u.username) = LOWER(?)"
            params += it
        }
        return conditions to params
    }

    private fun java.sql.ResultSet.toDataExportRow(): DataExportRow =
        DataExportRow(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            username = getString("username"),
            requestedAt = getTimestamp("requested_at").toInstant(),
            status = getString("status"),
        )

    companion object {
        /** Fixed page size (implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50

        const val STATUS_PENDING = "pending"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_READY = "ready"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_FAILED = "failed"

        const val SQLSTATE_UNIQUE_VIOLATION = "23505"

        /** The statuses a manual trigger may act on (design D1). */
        private val TRIGGERABLE_STATUSES = setOf(STATUS_FAILED, STATUS_PENDING)

        // Identity-only projection: id + user + requested_at + status. NO r2_object_key,
        // NO download_expires_at, NO signed URL (design + spec — status, never contents).
        private val BASE_SELECT =
            """
            SELECT r.id, r.user_id, u.username,
                   r.requested_at,
                   r.status
              FROM data_export_requests r
              JOIN users u ON u.id = r.user_id
             WHERE TRUE
            """.trimIndent()

        private val COUNT_SELECT =
            """
            SELECT COUNT(*)
              FROM data_export_requests r
              JOIN users u ON u.id = r.user_id
             WHERE TRUE
            """.trimIndent()

        private val SQL_REENQUEUE =
            """
            UPDATE data_export_requests
               SET status = 'pending',
                   started_at = NULL,
                   error = NULL
             WHERE id = ? AND status = 'failed'
            """.trimIndent()
    }
}

/** A single rendered Data Export Queue row — identity + lifecycle status ONLY. */
data class DataExportRow(
    val id: UUID,
    val userId: UUID,
    val username: String,
    val requestedAt: Instant,
    /** One of the `data_export_requests.status` CHECK values (raw DB string). */
    val status: String,
)

/**
 * The active filter set + page size + optional keyset cursor for one query. `qUserId`
 * and `qUsername` are mutually exclusive (the route parses the single `q` param into
 * exactly one of them); `status` filters on the request lifecycle state.
 */
data class DataExportQuery(
    val status: String? = null,
    val qUserId: UUID? = null,
    val qUsername: String? = null,
    val cursor: DataExportCursor? = null,
    val pageSize: Int = DataExportQueueRepository.PAGE_SIZE,
)

/** One page of results plus the cursor for the next page (null = last page). */
data class DataExportPage(
    val rows: List<DataExportRow>,
    val nextCursor: DataExportCursor?,
)

/**
 * Opaque keyset cursor over `(requested_at, id)` for the DESC ordering. Encoded as
 * `base64url("<sortAtEpochMicros>|<id>")`. Opaque but NOT a security boundary — it only
 * encodes a sort position over data the admin is already authorized to read. A malformed
 * token decodes to `null` (→ first page), never throws.
 */
data class DataExportCursor(
    val sortAt: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${sortAt.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): DataExportCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep <= 0 || sep == raw.length - 1) return null
                val micros = raw.substring(0, sep).toLong()
                val id = UUID.fromString(raw.substring(sep + 1))
                DataExportCursor(instantFromEpochMicros(micros), id)
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

/** Typed result of [DataExportQueueRepository.trigger]. */
sealed interface TriggerOutcome {
    /**
     * The trigger caused a state transition + recorded one immutable audit row; the
     * producer pipeline was then driven for the single request. [beforeStatus] is the
     * pre-trigger status (`failed` re-enqueued, or stuck `pending`); [processOutcome] is
     * the drive result (`READY` delivered, `FAILED` soft-failed, or `SKIPPED` when a
     * racing scheduler claimed it first — the export is still delivered by the winner).
     */
    data class Triggered(
        val beforeStatus: String,
        val processOutcome: DataExportProcessOutcome,
    ) : TriggerOutcome

    /** The target is `processing`/`ready`/`expired` — benign no-op, nothing written. */
    data class NotTriggerable(val status: String) : TriggerOutcome

    /** Re-enqueue collided with another active request (one-active index) — benign no-op. */
    data object AlreadyActive : TriggerOutcome

    /** The target id is unknown (or the user was hard-deleted, cascading the row) — benign no-op. */
    data object Unknown : TriggerOutcome

    /** Acting admin is at/over the trigger cap; nothing written. */
    data object RateLimited : TriggerOutcome
}
