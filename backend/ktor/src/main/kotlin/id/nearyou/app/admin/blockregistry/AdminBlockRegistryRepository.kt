package id.nearyou.app.admin.blockregistry

import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Read-only repository for `user_blocks` — backs the Block User Registry
 * viewer (`admin-block-registry` capability, admin mockup frame 12). The THIRD
 * instance of the read-only-admin-viewer pattern: clones the
 * `admin-rejected-identifiers-viewer` read path (keyset pagination, lenient
 * parameterized filters, opaque cursor) over `user_blocks`.
 *
 * Design references: `openspec/changes/admin-block-registry/design.md` D1
 * (mirror the rejected-identifiers viewer end-to-end), D2 (NO Flyway migration
 * — the hot path searches by a specific user, served by the existing
 * `users_username_lower_idx` → PK; only the rare unfiltered browse seq-scans),
 * D3 (INNER-join `users` twice — orphan-safe via `ON DELETE CASCADE`), D6
 * (bidirectional flag via an `EXISTS` reverse-pair subquery, not a self-join).
 *
 *  - All queries via JDBC `PreparedStatement`; every filter value is bound
 *    positionally, NEVER string-interpolated — so a search value carrying SQL
 *    metacharacters is treated as a literal, never executed.
 *  - Raw read of `user_blocks` + `users` is permitted: this is the admin module,
 *    exempt from the `RawFromPostsRule` / `BlockExclusionJoinRule` Detekt rules
 *    (per `openspec/project.md` § Coding Conventions — admin-module raw reads
 *    allowed). This is the moderation inspection surface, not a product path, so
 *    no `visible_*` view and no bidirectional NOT-IN block-exclusion join apply.
 *  - This repository exposes NO write path. The admin never blocks/unblocks on
 *    behalf of a user — enforcement and the user-driven block/unblock stay in
 *    the product path.
 */
class AdminBlockRegistryRepository(
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
    fun query(q: BlockRegistryQuery): BlockRegistryPage {
        val (conditions, params) = filterClauses(q)
        q.cursor?.let {
            // Keyset "older" predicate: row-value comparison aligned with the
            // `created_at DESC, blocker_id DESC, blocked_id DESC` ordering. The
            // composite primary key `(blocker_id, blocked_id)` is the
            // deterministic tiebreaker for rows sharing an identical
            // `created_at` (the `DEFAULT NOW()` column collides under a block
            // burst).
            conditions += "(ub.created_at, ub.blocker_id, ub.blocked_id) < (?, ?, ?)"
            params += java.sql.Timestamp.from(it.createdAt)
            params += it.blockerId
            params += it.blockedId
        }

        val sql =
            buildString {
                append(
                    """
                    SELECT ub.blocker_id,
                           ub.blocked_id,
                           b.username AS blocker_username,
                           t.username AS blocked_username,
                           ub.created_at,
                           EXISTS (
                               SELECT 1 FROM user_blocks r
                                WHERE r.blocker_id = ub.blocked_id
                                  AND r.blocked_id = ub.blocker_id
                           ) AS bidirectional
                      FROM user_blocks ub
                      JOIN users b ON b.id = ub.blocker_id
                      JOIN users t ON t.id = ub.blocked_id
                    """.trimIndent(),
                )
                if (conditions.isNotEmpty()) {
                    append("\n WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append("\n ORDER BY ub.created_at DESC, ub.blocker_id DESC, ub.blocked_id DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<BlockRegistryRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            BlockRegistryRow(
                                blockerId = rs.getObject("blocker_id", UUID::class.java),
                                blockedId = rs.getObject("blocked_id", UUID::class.java),
                                blockerUsername = rs.getString("blocker_username"),
                                blockedUsername = rs.getString("blocked_username"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                bidirectional = rs.getBoolean("bidirectional"),
                            )
                    }
                }
            }
        }

        val hasOlder = fetched.size > q.pageSize
        val display = if (hasOlder) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) {
                display.last().let { BlockRegistryCursor(it.createdAt, it.blockerId, it.blockedId) }
            } else {
                null
            }
        return BlockRegistryPage(rows = display, nextCursor = nextCursor)
    }

    /**
     * The filter half of the WHERE clause. Every value is bound positionally;
     * nothing is interpolated. At most one of [BlockRegistryQuery.searchUserId]
     * / [BlockRegistryQuery.searchUsername] is set by the route (the lenient
     * UUID-or-username parse, design.md D4); both null ⇒ the unfiltered list.
     */
    private fun filterClauses(q: BlockRegistryQuery): Pair<MutableList<String>, MutableList<Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        // UUID search matches EITHER side (blocker or blocked); the UUID is
        // bound to both placeholders.
        q.searchUserId?.let {
            conditions += "(ub.blocker_id = ? OR ub.blocked_id = ?)"
            params += it
            params += it
        }
        // Username search is EXACT + case-insensitive (design.md D4), served by
        // `users_username_lower_idx`, matching EITHER side.
        q.searchUsername?.let {
            conditions += "(LOWER(b.username) = LOWER(?) OR LOWER(t.username) = LOWER(?))"
            params += it
            params += it
        }
        return conditions to params
    }

    companion object {
        /** Fixed page size (design.md D1 — implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50
    }
}

/** A single rendered `user_blocks` pair with both usernames + the bidirectional flag. */
data class BlockRegistryRow(
    val blockerId: UUID,
    val blockedId: UUID,
    val blockerUsername: String,
    val blockedUsername: String,
    val createdAt: Instant,
    val bidirectional: Boolean,
)

/**
 * The active search + page size + optional keyset cursor for one query. At most
 * one of [searchUserId] / [searchUsername] is set (the route's lenient parse
 * decides which); both null ⇒ unfiltered.
 */
data class BlockRegistryQuery(
    val searchUserId: UUID? = null,
    val searchUsername: String? = null,
    val cursor: BlockRegistryCursor? = null,
    val pageSize: Int = AdminBlockRegistryRepository.PAGE_SIZE,
)

/** One page of results plus the cursor for the next-older page (null = last page). */
data class BlockRegistryPage(
    val rows: List<BlockRegistryRow>,
    val nextCursor: BlockRegistryCursor?,
)

/**
 * Opaque keyset cursor over `(created_at, blocker_id, blocked_id)`. Encoded as
 * `base64url("<createdAtEpochMicros>|<blockerId>|<blockedId>")`. It is opaque to
 * the user but NOT a security boundary — it only encodes a sort position over
 * data the admin is already authorized to read. A malformed token decodes to
 * `null` (→ first page), never throws (design.md D1).
 */
data class BlockRegistryCursor(
    val createdAt: Instant,
    val blockerId: UUID,
    val blockedId: UUID,
) {
    fun encode(): String {
        val raw = "${createdAt.toEpochMicros()}|$blockerId|$blockedId"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): BlockRegistryCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val parts = raw.split('|')
                if (parts.size != 3) return null
                val micros = parts[0].toLong()
                val blockerId = UUID.fromString(parts[1])
                val blockedId = UUID.fromString(parts[2])
                BlockRegistryCursor(instantFromEpochMicros(micros), blockerId, blockedId)
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
