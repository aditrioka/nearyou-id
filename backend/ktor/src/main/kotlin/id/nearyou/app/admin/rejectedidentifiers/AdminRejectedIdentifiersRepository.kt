package id.nearyou.app.admin.rejectedidentifiers

import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Allowed `reason` values for `rejected_identifiers` — MUST stay in lockstep
 * with the V3 `rejected_identifiers` CHECK constraint
 * (`reason IN ('age_under_18','attestation_persistent_fail')`,
 * `V3__signup_flow.sql`). If that CHECK ever gains a value (e.g. a future
 * rejection reason), this list MUST gain it too, or the new rows become
 * unfilterable + invisible in the per-reason summary. Ordered for stable
 * summary rendering.
 */
val ALLOWED_REJECTION_REASONS: List<String> = listOf("age_under_18", "attestation_persistent_fail")

/**
 * Allowed `identifier_type` values — MUST stay in lockstep with the V3
 * `rejected_identifiers` CHECK (`identifier_type IN ('google','apple')`). If a
 * future provider is added to the CHECK, add it here in lockstep. Ordered for
 * stable summary rendering.
 */
val ALLOWED_IDENTIFIER_TYPES: List<String> = listOf("google", "apple")

/**
 * Read-only repository for `rejected_identifiers` — backs the rejected-
 * identifiers viewer (`admin-rejected-identifiers-viewer` capability,
 * Admin #3.5). Clones the `admin-actions-log-viewer` read path (keyset
 * pagination over `(rejected_at, id)` DESC, lenient parameterized filters).
 *
 * Design references: `openspec/changes/admin-rejected-identifiers-viewer/
 * design.md` D1 (mirror the actions-log viewer end-to-end), D2 (NO Flyway
 * migration — low-cardinality table served by seq-scan + in-memory sort over
 * the existing PK + `rejected_identifiers_hash_idx`).
 *
 *  - All queries via JDBC `PreparedStatement`; every filter value is bound
 *    positionally, NEVER string-interpolated — so a filter value carrying SQL
 *    metacharacters is treated as a literal, never executed.
 *  - Raw read of `rejected_identifiers` is permitted: this is the admin module,
 *    exempt from the `RawFromPostsRule` / `BlockExclusionJoinRule` Detekt rules
 *    (per `openspec/project.md` § Coding Conventions — admin-module raw reads
 *    allowed). `rejected_identifiers` is an anti-abuse blocklist, not a
 *    user-content table, so no `visible_*` view or block-exclusion join applies.
 *  - This repository exposes NO write path. The manual support-clear `DELETE`
 *    is deferred to the fast-follow `admin-rejected-identifiers-clear-action`
 *    (design.md D3) — it would be role-gated + CSRF-gated + audit-logged +
 *    rate-limited, none of which this read-only surface carries.
 */
class AdminRejectedIdentifiersRepository(
    private val dataSource: DataSource,
) {
    /**
     * Run the filtered + keyset-paginated page query and return one page of
     * rows plus the cursor for the next-older page (null when there is none).
     *
     * Fetches `pageSize + 1` rows: the extra row (if present) signals "there is
     * an older page" and its predecessor becomes the next cursor — no `OFFSET`,
     * no total-count query (design.md D1).
     */
    fun query(q: RejectedIdentifiersQuery): RejectedIdentifiersPage {
        val (conditions, params) = filterClauses(q)
        q.cursor?.let {
            // Keyset "older" predicate: row-value comparison aligned with the
            // `rejected_at DESC, id DESC` ordering. `id` is the deterministic
            // tiebreaker for rows sharing an identical `rejected_at` (the
            // `DEFAULT NOW()` column collides under a signup burst).
            conditions += "(rejected_at, id) < (?, ?)"
            params += java.sql.Timestamp.from(it.rejectedAt)
            params += it.id
        }

        val sql =
            buildString {
                append(
                    """
                    SELECT id, identifier_hash, identifier_type, reason, rejected_at
                      FROM rejected_identifiers
                    """.trimIndent(),
                )
                if (conditions.isNotEmpty()) {
                    append("\n WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append("\n ORDER BY rejected_at DESC, id DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<RejectedIdentifierRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            RejectedIdentifierRow(
                                id = rs.getObject("id", UUID::class.java),
                                identifierHash = rs.getString("identifier_hash"),
                                identifierType = rs.getString("identifier_type"),
                                reason = rs.getString("reason"),
                                rejectedAt = rs.getTimestamp("rejected_at").toInstant(),
                            )
                    }
                }
            }
        }

        val hasOlder = fetched.size > q.pageSize
        val display = if (hasOlder) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) {
                display.last().let { RejectedIdentifiersCursor(it.rejectedAt, it.id) }
            } else {
                null
            }
        return RejectedIdentifiersPage(rows = display, nextCursor = nextCursor)
    }

    /**
     * Count summary scoped to the SAME active filters as [query] but IGNORING
     * the keyset cursor and page size — it counts the ENTIRE filtered result
     * set, broken down by `reason` and by `identifier_type`, so a moderator can
     * spot a rejection-volume spike at a glance (the "`rejected_identifiers`
     * insert rate" anomaly signal). One `GROUP BY reason, identifier_type` query
     * supplies both breakdowns + the total.
     */
    fun summary(q: RejectedIdentifiersQuery): RejectedIdentifiersSummary {
        val (conditions, params) = filterClauses(q)

        val sql =
            buildString {
                append(
                    """
                    SELECT reason, identifier_type, COUNT(*) AS cnt
                      FROM rejected_identifiers
                    """.trimIndent(),
                )
                if (conditions.isNotEmpty()) {
                    append("\n WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append("\n GROUP BY reason, identifier_type")
            }

        var total = 0L
        val byReason = mutableMapOf<String, Long>()
        val byType = mutableMapOf<String, Long>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val reason = rs.getString("reason")
                        val type = rs.getString("identifier_type")
                        val cnt = rs.getLong("cnt")
                        total += cnt
                        byReason.merge(reason, cnt, Long::plus)
                        byType.merge(type, cnt, Long::plus)
                    }
                }
            }
        }
        return RejectedIdentifiersSummary(total = total, byReason = byReason, byType = byType)
    }

    /**
     * The filter half of the WHERE clause, shared verbatim by [query] and
     * [summary] so the summary counts EXACTLY the filtered set the page query
     * pages over (the cursor predicate is the only delta — applied by [query]
     * alone). Every value is bound positionally; nothing is interpolated.
     */
    private fun filterClauses(q: RejectedIdentifiersQuery): Pair<MutableList<String>, MutableList<Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        q.reason?.let {
            conditions += "reason = ?"
            params += it
        }
        q.identifierType?.let {
            conditions += "identifier_type = ?"
            params += it
        }
        q.fromInclusive?.let {
            conditions += "rejected_at >= ?"
            params += java.sql.Timestamp.from(it)
        }
        q.toExclusive?.let {
            conditions += "rejected_at < ?"
            params += java.sql.Timestamp.from(it)
        }
        return conditions to params
    }

    companion object {
        /** Fixed page size (design.md D1 — implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50
    }
}

/** A single rendered `rejected_identifiers` row. */
data class RejectedIdentifierRow(
    val id: UUID,
    val identifierHash: String,
    val identifierType: String,
    val reason: String,
    val rejectedAt: Instant,
)

/** The active filter set + page size + optional keyset cursor for one query. */
data class RejectedIdentifiersQuery(
    val reason: String? = null,
    val identifierType: String? = null,
    val fromInclusive: Instant? = null,
    val toExclusive: Instant? = null,
    val cursor: RejectedIdentifiersCursor? = null,
    val pageSize: Int = AdminRejectedIdentifiersRepository.PAGE_SIZE,
)

/** One page of results plus the cursor for the next-older page (null = last page). */
data class RejectedIdentifiersPage(
    val rows: List<RejectedIdentifierRow>,
    val nextCursor: RejectedIdentifiersCursor?,
)

/**
 * Per-reason / per-type count summary over the filtered result set (the whole
 * set, not one page). `byReason` / `byType` carry only the buckets present in
 * the filtered data; the route renders every allowed bucket, defaulting absent
 * ones to zero (so a filter-narrowed scope shows the excluded bucket as 0).
 */
data class RejectedIdentifiersSummary(
    val total: Long,
    val byReason: Map<String, Long>,
    val byType: Map<String, Long>,
)

/**
 * Opaque keyset cursor over `(rejected_at, id)`. Encoded as
 * `base64url("<rejectedAtEpochMicros>|<id>")`. It is opaque to the user but NOT
 * a security boundary — it only encodes a sort position over data the admin is
 * already authorized to read. A malformed token decodes to `null` (→ first
 * page), never throws (design.md D1).
 */
data class RejectedIdentifiersCursor(
    val rejectedAt: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${rejectedAt.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): RejectedIdentifiersCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep <= 0 || sep == raw.length - 1) return null
                val micros = raw.substring(0, sep).toLong()
                val id = UUID.fromString(raw.substring(sep + 1))
                RejectedIdentifiersCursor(instantFromEpochMicros(micros), id)
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
