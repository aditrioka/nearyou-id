package id.nearyou.app.admin.privacyflips

import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Allowed `status` filter values for the privacy-flip monitor — the two
 * classification buckets. `in_window` = the flip is still in its 72h
 * downgrade grace (`eval_instant < privacy_flip_scheduled_at`); `overdue` =
 * the deadline has passed but the worker has not cleared the row
 * (`privacy_flip_scheduled_at <= eval_instant`), a stuck-row / webhook-handler
 * bug signal. Ordered for stable rendering.
 */
val ALLOWED_PRIVACY_FLIP_STATUSES: List<String> = listOf("in_window", "overdue")

/** Classification bucket value for the IN_WINDOW (mid-grace) rows. */
const val STATUS_IN_WINDOW: String = "in_window"

/** Classification bucket value for the OVERDUE (stuck) rows. */
const val STATUS_OVERDUE: String = "overdue"

/**
 * Read-only repository for the Privacy Flip Monitor
 * (`admin-privacy-flip-monitor` capability, admin board frame 17). Lists the
 * `users` rows with a pending privacy flip (`privacy_flip_scheduled_at IS NOT
 * NULL`) — the canonical 72h-downgrade-window population per
 * [`docs/07-Operations.md` § Privacy Flip Monitor] PLUS the OVERDUE / stuck
 * superset that frame 17 surfaces for webhook-handler-bug detection.
 *
 * The FOURTH instance of the read-only-admin-viewer pattern: it clones
 * `AdminRejectedIdentifiersRepository` (keyset pagination, lenient
 * parameterized filters, opaque cursor, `pageSize + 1` has-more probe), with
 * three intentional deltas (design.md D1):
 *  - **ASC order** `privacy_flip_scheduled_at ASC, id ASC` (most-overdue /
 *    soonest-deadline first — surfaces stuck rows at the top), keyset predicate
 *    `(privacy_flip_scheduled_at, id) > (?, ?)`.
 *  - **Per-row IN_WINDOW / OVERDUE classification** via a `CASE` over a single
 *    request-scoped `evalInstant` bound parameter (design D3) — NOT SQL
 *    `now()`, so the boundary is deterministically testable; the SAME instant
 *    drives the `status` filter, so class and filter cannot disagree.
 *  - **Reads `users`** (admin-module raw-read exemption — no `visible_*` view
 *    or block-exclusion join applies; design D6). NO `deleted_at` filter: a
 *    soft-deleted user with a lingering flip is itself a stuck-row signal.
 *
 * The query is INDEX-SERVED by the existing V2 partial index
 * `users_privacy_flip_idx ON users (privacy_flip_scheduled_at) WHERE
 * privacy_flip_scheduled_at IS NOT NULL` (predicate + lead sort column), so the
 * change ships NO Flyway migration (design D5). The exact-username `q` lookup
 * is served by `users_username_lower_idx`.
 *
 * Exposes NO write path — it neither clears nor expedites a scheduled flip
 * (anomalies are escalated to an out-of-band worker fix, frame 17).
 */
class AdminPrivacyFlipsRepository(
    private val dataSource: DataSource,
) {
    /**
     * Run the filtered + keyset-paginated page query and return one page of
     * rows plus the cursor for the next page (null when there is none).
     *
     * Fetches `pageSize + 1` rows: the extra row (if present) signals "there is
     * a further page" and its predecessor becomes the next cursor — no
     * `OFFSET`, no total-count query.
     *
     * Parameter ordering is positional and left-to-right in the SQL text: the
     * projection's `evalInstant` (`CASE WHEN ? < ...`) is bound FIRST (it sits
     * in the SELECT clause, before WHERE), then the WHERE filter params, then
     * the cursor params, then the LIMIT.
     */
    fun query(q: PrivacyFlipsQuery): PrivacyFlipsPage {
        val (conditions, params) = filterClauses(q)
        q.cursor?.let {
            // Keyset "next" predicate: ascending row-value comparison aligned
            // with the `privacy_flip_scheduled_at ASC, id ASC` ordering. `id`
            // is the deterministic tiebreaker for rows sharing an identical
            // `privacy_flip_scheduled_at` (a mass-scheduling event can write
            // colliding timestamps).
            conditions += "(privacy_flip_scheduled_at, id) > (?, ?)"
            params += java.sql.Timestamp.from(it.scheduledAt)
            params += it.id
        }

        val sql =
            buildString {
                append(
                    """
                    SELECT id, username, display_name, private_profile_opt_in,
                           privacy_flip_scheduled_at, deleted_at,
                           (CASE WHEN ? < privacy_flip_scheduled_at THEN '$STATUS_IN_WINDOW'
                                 ELSE '$STATUS_OVERDUE' END) AS flip_status
                      FROM users
                     WHERE privacy_flip_scheduled_at IS NOT NULL
                    """.trimIndent(),
                )
                conditions.forEach { append("\n   AND ").append(it) }
                append("\n ORDER BY privacy_flip_scheduled_at ASC, id ASC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<PrivacyFlipRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                // Projection eval_instant FIRST (CASE in the SELECT clause).
                ps.setObject(i++, java.sql.Timestamp.from(q.evalInstant))
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            PrivacyFlipRow(
                                id = rs.getObject("id", UUID::class.java),
                                username = rs.getString("username"),
                                displayName = rs.getString("display_name"),
                                privateProfileOptIn = rs.getBoolean("private_profile_opt_in"),
                                scheduledAt = rs.getTimestamp("privacy_flip_scheduled_at").toInstant(),
                                deleted = rs.getTimestamp("deleted_at") != null,
                                status = rs.getString("flip_status"),
                            )
                    }
                }
            }
        }

        val hasNext = fetched.size > q.pageSize
        val display = if (hasNext) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasNext && display.isNotEmpty()) {
                display.last().let { PrivacyFlipsCursor(it.scheduledAt, it.id) }
            } else {
                null
            }
        return PrivacyFlipsPage(rows = display, nextCursor = nextCursor)
    }

    /**
     * Count summary scoped to the SAME active `q` filter as [query] but
     * IGNORING the keyset cursor, the page size, AND the `status` filter — it
     * counts the ENTIRE `q`-scoped result set broken down by the IN_WINDOW /
     * OVERDUE classification, so a moderator can spot a stuck-row spike (the
     * rising-OVERDUE-count anomaly signal) at a glance. One
     * `GROUP BY classification` query supplies both bucket counts + the total.
     *
     * Uses the SAME `evalInstant` as [query]'s classification so the summary's
     * buckets agree with the rendered row classes within the request.
     */
    fun summary(q: PrivacyFlipsQuery): PrivacyFlipsSummary {
        // Summary scope = q only (NOT status, NOT cursor): it always reports
        // both buckets within the q scope.
        val qOnly = q.copy(status = null, cursor = null)
        val (conditions, params) = filterClauses(qOnly)

        val sql =
            buildString {
                append(
                    """
                    SELECT (CASE WHEN ? < privacy_flip_scheduled_at THEN '$STATUS_IN_WINDOW'
                                 ELSE '$STATUS_OVERDUE' END) AS flip_status,
                           COUNT(*) AS cnt
                      FROM users
                     WHERE privacy_flip_scheduled_at IS NOT NULL
                    """.trimIndent(),
                )
                conditions.forEach { append("\n   AND ").append(it) }
                append("\n GROUP BY flip_status")
            }

        var inWindow = 0L
        var overdue = 0L
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setObject(i++, java.sql.Timestamp.from(q.evalInstant))
                params.forEach { ps.setObject(i++, it) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val cnt = rs.getLong("cnt")
                        when (rs.getString("flip_status")) {
                            STATUS_IN_WINDOW -> inWindow = cnt
                            STATUS_OVERDUE -> overdue = cnt
                        }
                    }
                }
            }
        }
        return PrivacyFlipsSummary(inWindow = inWindow, overdue = overdue)
    }

    /**
     * The filter half of the WHERE clause (the `AND`-appended conditions),
     * shared by [query] and [summary] so the summary counts EXACTLY the
     * `q`-scoped set the page query pages over. Every value is bound
     * positionally; nothing is interpolated. The `status` predicate reuses the
     * caller's `evalInstant` (bound here as a literal `?`), so it matches the
     * projection classification at the boundary.
     */
    private fun filterClauses(q: PrivacyFlipsQuery): Pair<MutableList<String>, MutableList<Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        when (q.status) {
            STATUS_IN_WINDOW -> {
                conditions += "? < privacy_flip_scheduled_at"
                params += java.sql.Timestamp.from(q.evalInstant)
            }
            STATUS_OVERDUE -> {
                conditions += "privacy_flip_scheduled_at <= ?"
                params += java.sql.Timestamp.from(q.evalInstant)
            }
            else -> Unit // null / unrecognized → no status predicate (both buckets)
        }
        q.qUserId?.let {
            conditions += "id = ?"
            params += it
        }
        q.qUsername?.let {
            conditions += "LOWER(username) = LOWER(?)"
            params += it
        }
        return conditions to params
    }

    companion object {
        /** Fixed page size (design.md D1 — implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50
    }
}

/** A single rendered privacy-flip row (a `users` row with a pending flip). */
data class PrivacyFlipRow(
    val id: UUID,
    val username: String,
    val displayName: String,
    val privateProfileOptIn: Boolean,
    val scheduledAt: Instant,
    val deleted: Boolean,
    /** Classification bucket: [STATUS_IN_WINDOW] or [STATUS_OVERDUE]. */
    val status: String,
)

/**
 * The active filter set + the request-scoped evaluation instant + page size +
 * optional keyset cursor for one query. `qUserId` and `qUsername` are mutually
 * exclusive (the route parses the single `q` param into exactly one of them);
 * `status` is `in_window` / `overdue` / null.
 */
data class PrivacyFlipsQuery(
    val evalInstant: Instant,
    val status: String? = null,
    val qUserId: UUID? = null,
    val qUsername: String? = null,
    val cursor: PrivacyFlipsCursor? = null,
    val pageSize: Int = AdminPrivacyFlipsRepository.PAGE_SIZE,
)

/** One page of results plus the cursor for the next page (null = last page). */
data class PrivacyFlipsPage(
    val rows: List<PrivacyFlipRow>,
    val nextCursor: PrivacyFlipsCursor?,
)

/**
 * In-window vs overdue count summary over the `q`-scoped result set (the whole
 * set within the q scope, not one page, and NOT status-filtered). The OVERDUE
 * count is the stuck-row anomaly signal.
 */
data class PrivacyFlipsSummary(
    val inWindow: Long,
    val overdue: Long,
) {
    val total: Long get() = inWindow + overdue
}

/**
 * Opaque keyset cursor over `(privacy_flip_scheduled_at, id)`. Encoded as
 * `base64url("<scheduledAtEpochMicros>|<id>")`. Opaque to the user but NOT a
 * security boundary — it only encodes a sort position over data the admin is
 * already authorized to read. A malformed token decodes to `null` (→ first
 * page), never throws (design.md D1).
 */
data class PrivacyFlipsCursor(
    val scheduledAt: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${scheduledAt.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): PrivacyFlipsCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep <= 0 || sep == raw.length - 1) return null
                val micros = raw.substring(0, sep).toLong()
                val id = UUID.fromString(raw.substring(sep + 1))
                PrivacyFlipsCursor(instantFromEpochMicros(micros), id)
            } catch (_: IllegalArgumentException) {
                // Not valid base64url, not a parseable Long, or not a UUID.
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
