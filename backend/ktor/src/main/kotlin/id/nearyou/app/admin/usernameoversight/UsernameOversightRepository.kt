package id.nearyou.app.admin.usernameoversight

import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Repository for the Premium Username Change Oversight surface
 * (`admin-premium-username-oversight` capability, admin mockup frame 22) — the
 * admin read + write layer over the pre-existing `moderation_queue` (V9) +
 * `username_history` (V3) tables.
 *
 * Read paths mirror the read-only-admin-viewer pattern (keyset pagination, lenient
 * parameterized filters, opaque cursor — `admin-block-registry` /
 * `admin-reserved-usernames-editor`): each list fetches `PAGE_SIZE + 1` rows so the
 * extra (if present) signals "there is an older page" and its predecessor becomes
 * the next cursor — no `OFFSET`, no total-count query.
 *
 * Write paths ([resolveFlag] / [releaseHold]) mirror the conditional-UPDATE
 * idempotent-resolution pattern from `admin-report-queue-resolution-actions`
 * ([id.nearyou.app.admin.reportqueue.ReportResolutionRepository]): a single
 * `UPDATE … WHERE <precondition>` returning the affected count — zero rows is a
 * benign no-op (already resolved / already released) that also serializes the
 * two-admins-on-the-same-row race (the loser is a no-op). The transaction
 * orchestration (rate-limit gate, the accept-side override upsert, the audit row)
 * lives in [UsernameOversightService]; this layer exposes the connection-scoped
 * SQL primitives so the service composes them in ONE transaction.
 *
 *  - All queries are parameterized JDBC `PreparedStatement`s — never string-
 *    interpolated.
 *  - Raw `FROM`/`UPDATE` on `moderation_queue` / `username_history` + the
 *    `LEFT JOIN users` (to resolve `target_id` / `user_id` → `username`) are
 *    permitted: this is the admin module (package `id.nearyou.app.admin.*`),
 *    exempt from the `RawFromPostsRule` / `BlockExclusionJoinRule` /
 *    `CoordinateJitterRule` Detekt rules (admins moderate everything; per
 *    `CLAUDE.md` § Critical invariants + `openspec/project.md` § Coding
 *    Conventions). No `visible_*` view or block-exclusion join applies.
 *  - ZERO migration: `moderation_queue` + `username_history` pre-exist (V9 / V3);
 *    `username_flag_overrides` (V23) is written through the SHARED
 *    [id.nearyou.data.repository.UsernameFlagOverrideRepository], not here.
 */
class UsernameOversightRepository(
    private val dataSource: DataSource,
) {
    // ---- Reads -------------------------------------------------------------

    /**
     * One keyset-paginated page of pending `username_flagged` flags, newest-first
     * over `(created_at DESC, id DESC)`. Selects the candidate from `notes` and
     * `LEFT JOIN users` to resolve `target_id → username` (null when the user has
     * been hard-deleted → rendered as text with no deep-link).
     */
    fun listPendingFlags(cursor: FlagsCursor?): FlagsPage {
        val sql =
            buildString {
                append(
                    "SELECT mq.id, mq.target_id, mq.notes, mq.created_at, u.username AS username " +
                        "FROM moderation_queue mq " +
                        "LEFT JOIN users u ON u.id = mq.target_id " +
                        "WHERE mq.trigger = 'username_flagged' AND mq.status = 'pending'",
                )
                if (cursor != null) append(" AND (mq.created_at, mq.id) < (?, ?)")
                append(" ORDER BY mq.created_at DESC, mq.id DESC LIMIT ?")
            }
        val fetched = mutableListOf<FlagRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                if (cursor != null) {
                    ps.setObject(i++, java.sql.Timestamp.from(cursor.createdAt))
                    ps.setObject(i++, cursor.id)
                }
                ps.setInt(i, PAGE_SIZE + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            FlagRow(
                                queueId = rs.getObject("id", UUID::class.java),
                                targetId = rs.getObject("target_id", UUID::class.java),
                                candidate = rs.getString("notes"),
                                username = rs.getString("username"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            )
                    }
                }
            }
        }
        val hasOlder = fetched.size > PAGE_SIZE
        val display = if (hasOlder) fetched.take(PAGE_SIZE) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) display.last().let { FlagsCursor(it.createdAt, it.queueId) } else null
        return FlagsPage(rows = display, nextCursor = nextCursor)
    }

    /**
     * One keyset-paginated page of `username_history` rows, newest-first over
     * `(changed_at DESC, id DESC)`, optionally filtered by a case-insensitive
     * substring [q] matching `LOWER(old_username)` (V3 `username_history_old_lower_idx`)
     * OR `LOWER(new_username)`. `LEFT JOIN users` resolves the owning user.
     */
    fun listHistory(
        q: String?,
        cursor: HistoryCursor?,
    ): HistoryPage {
        val sql =
            buildString {
                append(
                    "SELECT h.id, h.user_id, h.old_username, h.new_username, h.changed_at, u.username AS username " +
                        "FROM username_history h " +
                        "LEFT JOIN users u ON u.id = h.user_id WHERE TRUE",
                )
                if (q != null) append(" AND (LOWER(h.old_username) LIKE ? ESCAPE '\\' OR LOWER(h.new_username) LIKE ? ESCAPE '\\')")
                if (cursor != null) append(" AND (h.changed_at, h.id) < (?, ?)")
                append(" ORDER BY h.changed_at DESC, h.id DESC LIMIT ?")
            }
        val fetched = mutableListOf<HistoryRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                if (q != null) {
                    val like = "%" + escapeLike(q.lowercase()) + "%"
                    ps.setString(i++, like)
                    ps.setString(i++, like)
                }
                if (cursor != null) {
                    ps.setObject(i++, java.sql.Timestamp.from(cursor.changedAt))
                    ps.setObject(i++, cursor.id)
                }
                ps.setInt(i, PAGE_SIZE + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            HistoryRow(
                                id = rs.getObject("id", UUID::class.java),
                                userId = rs.getObject("user_id", UUID::class.java),
                                oldUsername = rs.getString("old_username"),
                                newUsername = rs.getString("new_username"),
                                username = rs.getString("username"),
                                changedAt = rs.getTimestamp("changed_at").toInstant(),
                            )
                    }
                }
            }
        }
        val hasOlder = fetched.size > PAGE_SIZE
        val display = if (hasOlder) fetched.take(PAGE_SIZE) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) display.last().let { HistoryCursor(it.changedAt, it.id) } else null
        return HistoryPage(rows = display, nextCursor = nextCursor)
    }

    /**
     * All `username_history` rows still under hold (`released_at > NOW()`), soonest
     * auto-release first (`released_at ASC`), `LEFT JOIN users` for the owner. Not
     * paginated (held handles are few — a handful at a time at this cardinality;
     * the `username_history_released_idx` serves the `released_at` predicate).
     */
    fun listHolds(): List<HoldRow> {
        val rows = mutableListOf<HoldRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT h.id, h.user_id, h.old_username, h.released_at, u.username AS username " +
                    "FROM username_history h " +
                    "LEFT JOIN users u ON u.id = h.user_id " +
                    "WHERE h.released_at > NOW() ORDER BY h.released_at ASC LIMIT ?",
            ).use { ps ->
                ps.setInt(1, HOLDS_CAP)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows +=
                            HoldRow(
                                id = rs.getObject("id", UUID::class.java),
                                userId = rs.getObject("user_id", UUID::class.java),
                                oldUsername = rs.getString("old_username"),
                                username = rs.getString("username"),
                                releasedAt = rs.getTimestamp("released_at").toInstant(),
                            )
                    }
                }
            }
        }
        return rows
    }

    // ---- Writes (connection-scoped primitives; orchestrated by the service) ----

    /**
     * Conditional resolution of a `username_flagged` queue row: `UPDATE
     * moderation_queue SET status='resolved', resolution=?, resolved_by=?,
     * resolved_at=NOW() WHERE id=? AND status='pending' AND trigger='username_flagged'`.
     *
     * Runs on the caller's [conn] (the service's transaction). Returns a
     * [FlagResolveResult] carrying the rows-affected, plus the row's `target_id`
     * and `notes` (the candidate) read from the SAME locked row BEFORE the update,
     * so the service can write the override for `(target_id, notes)` on accept.
     * Returns null when no row matches the id at all (not found).
     *
     * The `WHERE … AND trigger='username_flagged'` clause means a queue_id whose
     * trigger is something else (a content trigger owned by `admin-report-queue`)
     * never matches → rows-affected 0 → the service treats it as out-of-scope.
     */
    fun resolveFlag(
        conn: Connection,
        queueId: UUID,
        resolution: String,
        adminId: UUID,
    ): FlagResolveResult? {
        // Lock + read the row first (target_id + notes + status + trigger) so the
        // service has the candidate to approve AND the scope guard inputs, all
        // under the row lock — serializing the two-admins race.
        val locked =
            conn.prepareStatement(
                "SELECT target_id, notes, status, trigger FROM moderation_queue WHERE id = ? FOR UPDATE",
            ).use { ps ->
                ps.setObject(1, queueId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        LockedFlag(
                            targetId = rs.getObject("target_id", UUID::class.java),
                            notes = rs.getString("notes"),
                            status = rs.getString("status"),
                            trigger = rs.getString("trigger"),
                        )
                    } else {
                        null
                    }
                }
            } ?: return null

        val updated =
            conn.prepareStatement(
                "UPDATE moderation_queue SET status='resolved', resolution=?, resolved_by=?, resolved_at=NOW() " +
                    "WHERE id=? AND status='pending' AND trigger='username_flagged'",
            ).use { ps ->
                ps.setString(1, resolution)
                ps.setObject(2, adminId)
                ps.setObject(3, queueId)
                ps.executeUpdate()
            }
        return FlagResolveResult(
            rowsAffected = updated,
            targetId = locked.targetId,
            candidate = locked.notes,
            trigger = locked.trigger,
        )
    }

    /**
     * Conditional manual release of a held handle: `UPDATE username_history SET
     * released_at=NOW() WHERE id=? AND released_at > NOW()`. Runs on the caller's
     * [conn]. Returns a [HoldReleaseResult] carrying the rows-affected + the prior
     * `released_at` (read from the locked row before the update, for the audit
     * `before_state`); null when no `username_history` row has the id (not found).
     * A row whose hold already elapsed (`released_at <= NOW()`) matches the SELECT
     * but not the conditional UPDATE → rows-affected 0 → benign no-op.
     */
    fun releaseHold(
        conn: Connection,
        historyId: UUID,
    ): HoldReleaseResult? {
        val priorReleasedAt =
            conn.prepareStatement(
                "SELECT released_at FROM username_history WHERE id = ? FOR UPDATE",
            ).use { ps ->
                ps.setObject(1, historyId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp("released_at").toInstant() else null }
            } ?: return null

        val updated =
            conn.prepareStatement(
                "UPDATE username_history SET released_at=NOW() WHERE id=? AND released_at > NOW()",
            ).use { ps ->
                ps.setObject(1, historyId)
                ps.executeUpdate()
            }
        return HoldReleaseResult(rowsAffected = updated, priorReleasedAt = priorReleasedAt)
    }

    private data class LockedFlag(
        val targetId: UUID,
        val notes: String?,
        val status: String,
        val trigger: String,
    )

    companion object {
        /** Fixed page size for the flagged-queue + history lists (implementation
         *  constant, not a client param). */
        const val PAGE_SIZE: Int = 50

        /** Hard cap on the (un-paginated) holds list — a defensive upper bound so a
         *  pathological backlog can't render an unbounded table. */
        const val HOLDS_CAP: Int = 200

        /** Escape LIKE metacharacters so a search term's `_`/`%`/`\` match literally. */
        fun escapeLike(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

// ---- Row DTOs --------------------------------------------------------------

/** One pending `username_flagged` flag row. [username] is null when the flagged
 *  user has been hard-deleted (rendered as text, no deep-link). [candidate] is the
 *  flagged handle from `moderation_queue.notes` (null/blank on a legacy pre-V23
 *  flag). */
data class FlagRow(
    val queueId: UUID,
    val targetId: UUID,
    val candidate: String?,
    val username: String?,
    val createdAt: Instant,
)

/** One `username_history` row for the read-only viewer. */
data class HistoryRow(
    val id: UUID,
    val userId: UUID,
    val oldUsername: String,
    val newUsername: String,
    val username: String?,
    val changedAt: Instant,
)

/** One held-handle row (`released_at > NOW()`). */
data class HoldRow(
    val id: UUID,
    val userId: UUID,
    val oldUsername: String,
    val username: String?,
    val releasedAt: Instant,
)

/** One page of pending flags + the cursor for the next-older page (null = last). */
data class FlagsPage(
    val rows: List<FlagRow>,
    val nextCursor: FlagsCursor?,
)

/** One page of history rows + the cursor for the next-older page (null = last). */
data class HistoryPage(
    val rows: List<HistoryRow>,
    val nextCursor: HistoryCursor?,
)

/** Result of [UsernameOversightRepository.resolveFlag]: the conditional-update
 *  rows-affected plus the locked row's enforcement-relevant fields. */
data class FlagResolveResult(
    val rowsAffected: Int,
    val targetId: UUID,
    val candidate: String?,
    val trigger: String,
)

/** Result of [UsernameOversightRepository.releaseHold]: the conditional-update
 *  rows-affected plus the prior `released_at` (for the audit `before_state`). */
data class HoldReleaseResult(
    val rowsAffected: Int,
    val priorReleasedAt: Instant,
)

/**
 * Opaque keyset cursor over the flagged-queue `(created_at, id)`, encoded as
 * `base64url("<createdAtEpochMicros>|<uuid>")`. Opaque but NOT a security boundary
 * — it only encodes a sort position over data the admin may already read. A
 * malformed token decodes to `null` (→ first page), never throws.
 */
data class FlagsCursor(
    val createdAt: Instant,
    val id: UUID,
) {
    fun encode(): String = CursorCodec.encode(createdAt, id)

    companion object {
        fun decode(token: String?): FlagsCursor? = CursorCodec.decode(token)?.let { FlagsCursor(it.first, it.second) }
    }
}

/**
 * Opaque keyset cursor over the history `(changed_at, id)`, same encoding +
 * malformed-→-null semantics as [FlagsCursor].
 */
data class HistoryCursor(
    val changedAt: Instant,
    val id: UUID,
) {
    fun encode(): String = CursorCodec.encode(changedAt, id)

    companion object {
        fun decode(token: String?): HistoryCursor? = CursorCodec.decode(token)?.let { HistoryCursor(it.first, it.second) }
    }
}

/** Shared `(Instant, UUID)` opaque-cursor codec for both list cursors. */
private object CursorCodec {
    fun encode(
        instant: Instant,
        id: UUID,
    ): String {
        val raw = "${instant.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decode(token: String?): Pair<Instant, UUID>? {
        if (token.isNullOrBlank()) return null
        return try {
            val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
            val sep = raw.indexOf('|')
            if (sep < 0) return null
            val micros = raw.substring(0, sep).toLong()
            val id = UUID.fromString(raw.substring(sep + 1))
            instantFromEpochMicros(micros) to id
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
