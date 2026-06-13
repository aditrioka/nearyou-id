package id.nearyou.app.admin.reservedusernames

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.ratelimit.ReservedUsernameActionRateLimiter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource

/**
 * Repository for the Reserved Usernames Editor (`admin-reserved-usernames-editor`
 * capability, admin mockup frame 21) — the admin CRUD surface over the existing
 * `reserved_usernames` table (V3).
 *
 * Read path mirrors the read-only-admin-viewer pattern (keyset pagination,
 * lenient parameterized filters, opaque cursor — `admin-block-registry` /
 * `admin-rejected-identifiers-viewer`). Write paths mirror the
 * write-in-one-transaction + audit-row-in-the-same-transaction + sealed-outcome
 * pattern from `admin-report-queue-resolution-actions`
 * ([id.nearyou.app.admin.reportqueue.ReportResolutionRepository]): each write
 * opens a transaction, checks the per-admin rate limit on that connection
 * ([ReservedUsernameActionRateLimiter], design D1), performs the mutation, writes
 * exactly one immutable `admin_actions_log` row on the same connection, and
 * commits — or rolls back leaving nothing.
 *
 *  - All queries are parameterized JDBC `PreparedStatement`s — never string-
 *    interpolated; a search term carrying SQL/LIKE metacharacters is bound as a
 *    literal (LIKE wildcards are escaped, see [escapeLike]).
 *  - Raw read/write of `reserved_usernames` + `admin_actions_log` is permitted:
 *    this is the admin module, exempt from the `RawFromPostsRule` Detekt rule
 *    (per `openspec/project.md` § Coding Conventions). `reserved_usernames` is a
 *    reference table — no `visible_*` view, no block-exclusion join applies.
 *  - ZERO migration: the table, the `source` CHECK, the `reserved_usernames_
 *    set_updated_at` trigger (refreshes `updated_at` on UPDATE), and the
 *    `reserved_usernames_protect_seed` trigger (blocks seed DELETE + source-
 *    change) all pre-exist in V3; `admin_app` already holds S/I/U/D.
 */
class ReservedUsernamesRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
    private val rateLimiter: ReservedUsernameActionRateLimiter,
) {
    // ---- Read --------------------------------------------------------------

    /**
     * One filtered + keyset-paginated page, newest-first over
     * `(created_at DESC, username DESC)`. Fetches `pageSize + 1` rows: the extra
     * (if present) signals "there is an older page" and its predecessor becomes
     * the next cursor — no `OFFSET`, no total-count query (design D5).
     */
    fun query(q: ReservedUsernamesQuery): ReservedUsernamesPage {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        q.source?.let {
            conditions += "source = ?"
            params += it
        }
        q.search?.let {
            // Case-insensitive substring search; LIKE metacharacters in the term
            // are escaped so a `_`/`%` is matched literally, not as a wildcard.
            conditions += "LOWER(username) LIKE ? ESCAPE '\\'"
            params += "%" + escapeLike(it.lowercase()) + "%"
        }
        q.cursor?.let {
            conditions += "(created_at, username) < (?, ?)"
            params += java.sql.Timestamp.from(it.createdAt)
            params += it.username
        }

        val sql =
            buildString {
                append("SELECT username, reason, source, created_at, updated_at FROM reserved_usernames")
                if (conditions.isNotEmpty()) {
                    append("\n WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append("\n ORDER BY created_at DESC, username DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<ReservedUsernameRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        fetched +=
                            ReservedUsernameRow(
                                username = rs.getString("username"),
                                reason = rs.getString("reason"),
                                source = rs.getString("source"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                            )
                    }
                }
            }
        }

        val hasOlder = fetched.size > q.pageSize
        val display = if (hasOlder) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) {
                display.last().let { ReservedUsernamesCursor(it.createdAt, it.username) }
            } else {
                null
            }
        return ReservedUsernamesPage(rows = display, nextCursor = nextCursor)
    }

    // ---- Writes ------------------------------------------------------------

    /**
     * Add one `admin_added` entry. [username] is already normalized + charset-
     * validated and [reason] already trimmed + length-validated by the route
     * (the server-side allowlist, design D9). A username that already exists is
     * a benign no-op ([AddOutcome.AlreadyReserved]) with no audit row, detected
     * BEFORE the rate-limit check so a duplicate never consumes quota.
     */
    fun addSingle(
        username: String,
        reason: String,
        actingAdminId: java.util.UUID,
        ip: String,
        userAgent: String?,
    ): AddOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val inserted = insertRow(conn, username, reason)
                if (!inserted) {
                    conn.rollback()
                    return AddOutcome.AlreadyReserved
                }
                if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                    conn.rollback()
                    return AddOutcome.RateLimited
                }
                auditLogger.logReservedUsernameAdded(conn, actingAdminId, username, addedState(username, reason), ip, userAgent)
                conn.commit()
                AddOutcome.Added
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /**
     * Bulk-add from already-parsed CSV text (the route enforces the ≤1000-row /
     * ≤256 KB guards + 400 BEFORE this call). One transaction: classify rows
     * into added / skipped-duplicate / skipped-invalid, then if the trailing-hour
     * count + the added count would exceed the cap, roll back the WHOLE upload
     * ([BulkOutcome.RateLimited]); otherwise insert + audit each added row and
     * commit (design D2). A username repeated within the upload is added once;
     * the later occurrence is a skipped duplicate (no phantom audit row).
     */
    fun bulkAdd(
        rawCsv: String,
        actingAdminId: java.util.UUID,
        ip: String,
        userAgent: String?,
    ): BulkOutcome {
        val parsed = parseCsv(rawCsv)
        return dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val added = mutableListOf<String>()
                val skippedDuplicate = parsed.duplicateInBatch.toMutableList()
                parsed.candidates.forEach { (username, reason) ->
                    if (insertRow(conn, username, reason)) added += username else skippedDuplicate += username
                }
                val currentCount = rateLimiter.countInTrailingHour(conn, actingAdminId)
                if (currentCount + added.size > ReservedUsernameActionRateLimiter.RESERVED_USERNAME_ACTION_CAP) {
                    conn.rollback()
                    return BulkOutcome.RateLimited(wouldAdd = added.size, currentCount = currentCount)
                }
                added.forEach { u ->
                    auditLogger.logReservedUsernameAdded(
                        conn,
                        actingAdminId,
                        u,
                        addedState(u, parsed.candidates.getValue(u)),
                        ip,
                        userAgent,
                    )
                }
                conn.commit()
                BulkOutcome.Applied(BulkAddReport(added = added, skippedDuplicate = skippedDuplicate, skippedInvalid = parsed.invalid))
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }
    }

    /**
     * Edit the `reason` of an `admin_added` row only. [username] is the
     * normalized path username, [newReason] already validated. A `seed_system`
     * row is refused at the APP layer ([EditOutcome.SeedProtected]) — the V3
     * trigger does NOT block seed reason edits, so this is the only guard for
     * that case (design D4). A missing row is [EditOutcome.NotFound]. No-op
     * outcomes are checked before the rate limit, so they consume no quota.
     */
    fun editReason(
        username: String,
        newReason: String,
        actingAdminId: java.util.UUID,
        ip: String,
        userAgent: String?,
    ): EditOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val current =
                    lockRow(conn, username) ?: run {
                        conn.rollback()
                        return EditOutcome.NotFound
                    }
                if (current.source == SOURCE_SEED) {
                    conn.rollback()
                    return EditOutcome.SeedProtected
                }
                if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                    conn.rollback()
                    return EditOutcome.RateLimited
                }
                conn.prepareStatement("UPDATE reserved_usernames SET reason = ? WHERE username = ?").use { ps ->
                    ps.setString(1, newReason)
                    ps.setString(2, username)
                    ps.executeUpdate()
                }
                auditLogger.logReservedUsernameEdited(
                    conn,
                    actingAdminId,
                    username,
                    reasonState(current.reason),
                    reasonState(newReason),
                    ip,
                    userAgent,
                )
                conn.commit()
                EditOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    /**
     * Remove an `admin_added` row only. [username] is the normalized path
     * username. A `seed_system` row is refused at the APP layer
     * ([RemoveOutcome.SeedProtected]); the V3 `reserved_usernames_protect_seed`
     * trigger is the defense-in-depth second line for a direct-SQL bypass (tested
     * separately) — under the `FOR UPDATE` lock + the app source check, the
     * DELETE below provably targets only an `admin_added` row, so the trigger
     * never fires on this path (no 5xx). A missing row is [RemoveOutcome.NotFound].
     */
    fun remove(
        username: String,
        actingAdminId: java.util.UUID,
        ip: String,
        userAgent: String?,
    ): RemoveOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val current =
                    lockRow(conn, username) ?: run {
                        conn.rollback()
                        return RemoveOutcome.NotFound
                    }
                if (current.source == SOURCE_SEED) {
                    conn.rollback()
                    return RemoveOutcome.SeedProtected
                }
                if (rateLimiter.isAtOrOverCap(conn, actingAdminId)) {
                    conn.rollback()
                    return RemoveOutcome.RateLimited
                }
                conn.prepareStatement("DELETE FROM reserved_usernames WHERE username = ?").use { ps ->
                    ps.setString(1, username)
                    ps.executeUpdate()
                }
                auditLogger.logReservedUsernameRemoved(
                    conn,
                    actingAdminId,
                    username,
                    fullState(username, current.reason, current.source),
                    ip,
                    userAgent,
                )
                conn.commit()
                RemoveOutcome.Applied
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = true }
            }
        }

    // ---- internals ---------------------------------------------------------

    /** INSERT one `admin_added` row; true if inserted, false on PK conflict
     *  (already reserved). Shared by the single + bulk add paths. */
    private fun insertRow(
        conn: java.sql.Connection,
        username: String,
        reason: String,
    ): Boolean =
        conn.prepareStatement(
            "INSERT INTO reserved_usernames (username, reason, source) VALUES (?, ?, 'admin_added') ON CONFLICT (username) DO NOTHING",
        ).use { ps ->
            ps.setString(1, username)
            ps.setString(2, reason)
            ps.executeUpdate() > 0
        }

    /** `SELECT … FOR UPDATE` the row's reason + source, locking it for the tx. */
    private fun lockRow(
        conn: java.sql.Connection,
        username: String,
    ): LockedRow? =
        conn.prepareStatement("SELECT reason, source FROM reserved_usernames WHERE username = ? FOR UPDATE").use { ps ->
            ps.setString(1, username)
            ps.executeQuery().use { rs ->
                if (rs.next()) LockedRow(reason = rs.getString("reason"), source = rs.getString("source")) else null
            }
        }

    /**
     * Parse the CSV text. Splits each non-blank line on its FIRST comma — the
     * username is comma-free by charset, so the remainder is the reason and may
     * itself contain commas (design D8). An optional `username,reason` header on
     * the first content line is detected + skipped. Each row is classified:
     * valid + new → candidate (insertion order preserved); valid but repeated
     * within the upload → duplicate-in-batch; otherwise → invalid (with its
     * 1-based line number).
     */
    private fun parseCsv(raw: String): Parsed {
        val candidates = LinkedHashMap<String, String>()
        val duplicateInBatch = mutableListOf<String>()
        val invalid = mutableListOf<BulkInvalidRow>()
        var firstContentLine = true
        var lineNo = 0
        raw.lineSequence().forEach { line ->
            lineNo++
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            if (firstContentLine) {
                firstContentLine = false
                if (trimmed.replace(" ", "").equals("username,reason", ignoreCase = true)) return@forEach
            }
            val comma = trimmed.indexOf(',')
            if (comma < 0) {
                invalid += BulkInvalidRow(lineNo, "missing comma separator")
                return@forEach
            }
            val username = ReservedUsernameValidation.normalizeUsername(trimmed.substring(0, comma))
            val reason = ReservedUsernameValidation.validateReason(trimmed.substring(comma + 1))
            when {
                username == null -> invalid += BulkInvalidRow(lineNo, "invalid username")
                reason == null -> invalid += BulkInvalidRow(lineNo, "invalid reason (blank or over 64 chars)")
                candidates.containsKey(username) -> duplicateInBatch += username
                else -> candidates[username] = reason
            }
        }
        return Parsed(candidates, duplicateInBatch, invalid)
    }

    private data class LockedRow(val reason: String, val source: String)

    private data class Parsed(
        val candidates: LinkedHashMap<String, String>,
        val duplicateInBatch: List<String>,
        val invalid: List<BulkInvalidRow>,
    )

    private companion object {
        const val SOURCE_SEED = "seed_system"

        /** Escape LIKE metacharacters so a search term's `_`/`%`/`\` match literally. */
        fun escapeLike(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        fun addedState(
            username: String,
            reason: String,
        ): JsonObject =
            buildJsonObject {
                put("username", JsonPrimitive(username))
                put("reason", JsonPrimitive(reason))
                put("source", JsonPrimitive("admin_added"))
            }

        fun reasonState(reason: String): JsonObject = buildJsonObject { put("reason", JsonPrimitive(reason)) }

        fun fullState(
            username: String,
            reason: String,
            source: String,
        ): JsonObject =
            buildJsonObject {
                put("username", JsonPrimitive(username))
                put("reason", JsonPrimitive(reason))
                put("source", JsonPrimitive(source))
            }
    }
}

/**
 * Username + reason validation shared by the single-add route (server-side
 * allowlist) and the bulk CSV parser. Charset-only on the username (NOT the full
 * signup shape rules) so short/edge handles matching the V3 seed short-handles
 * remain reservable (design D9).
 */
object ReservedUsernameValidation {
    const val USERNAME_MAX = 30

    /** `reserved_usernames.reason` column width (V3 `VARCHAR(64)`); the cap
     *  matches the column so an over-length reason is a clean app-layer reject,
     *  never a Postgres 22001 overflow 5xx (design D9 / review B1). */
    const val REASON_MAX = 64

    private val USERNAME_REGEX = Regex("^[a-z0-9._]{1,$USERNAME_MAX}$")

    /** Trim → lowercase → charset/length validate. Null when invalid. */
    fun normalizeUsername(raw: String?): String? {
        val v = raw?.trim()?.lowercase() ?: return null
        return if (USERNAME_REGEX.matches(v)) v else null
    }

    /** Trim → non-blank → length-cap. Null when invalid. */
    fun validateReason(raw: String?): String? {
        val v = raw?.trim() ?: return null
        return if (v.isNotEmpty() && v.length <= REASON_MAX) v else null
    }
}

/** One rendered `reserved_usernames` row. */
data class ReservedUsernameRow(
    val username: String,
    val reason: String,
    val source: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The active filters + page size + optional keyset cursor for one list query.
 * [source] is `seed_system` / `admin_added` / null (all); [search] is a trimmed
 * case-insensitive substring on `username` / null (unfiltered).
 */
data class ReservedUsernamesQuery(
    val source: String? = null,
    val search: String? = null,
    val cursor: ReservedUsernamesCursor? = null,
    val pageSize: Int = PAGE_SIZE,
) {
    companion object {
        /** Fixed page size (implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50
    }
}

/** One page of results + the cursor for the next-older page (null = last page). */
data class ReservedUsernamesPage(
    val rows: List<ReservedUsernameRow>,
    val nextCursor: ReservedUsernamesCursor?,
)

/**
 * Opaque keyset cursor over `(created_at, username)`, encoded as
 * `base64url("<createdAtEpochMicros>|<username>")`. Opaque but NOT a security
 * boundary — it only encodes a sort position over data the admin may already
 * read. A malformed token decodes to `null` (→ first page), never throws.
 * (`username` is charset `[a-z0-9._]`, so the `|` delimiter is unambiguous.)
 */
data class ReservedUsernamesCursor(
    val createdAt: Instant,
    val username: String,
) {
    fun encode(): String {
        val raw = "${createdAt.toEpochMicros()}|$username"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): ReservedUsernamesCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep < 0) return null
                val micros = raw.substring(0, sep).toLong()
                val username = raw.substring(sep + 1)
                if (username.isEmpty()) return null
                ReservedUsernamesCursor(instantFromEpochMicros(micros), username)
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

/** One rejected CSV row: its 1-based line number + a short reason. */
data class BulkInvalidRow(
    val line: Int,
    val problem: String,
)

/** The three-bucket report of a bulk add. */
data class BulkAddReport(
    val added: List<String>,
    val skippedDuplicate: List<String>,
    val skippedInvalid: List<BulkInvalidRow>,
)

/** Typed result of [ReservedUsernamesRepository.addSingle]. */
sealed interface AddOutcome {
    data object Added : AddOutcome

    data object AlreadyReserved : AddOutcome

    data object RateLimited : AddOutcome
}

/** Typed result of [ReservedUsernamesRepository.editReason]. */
sealed interface EditOutcome {
    data object Applied : EditOutcome

    data object NotFound : EditOutcome

    data object SeedProtected : EditOutcome

    data object RateLimited : EditOutcome
}

/** Typed result of [ReservedUsernamesRepository.remove]. */
sealed interface RemoveOutcome {
    data object Applied : RemoveOutcome

    data object NotFound : RemoveOutcome

    data object SeedProtected : RemoveOutcome

    data object RateLimited : RemoveOutcome
}

/** Typed result of [ReservedUsernamesRepository.bulkAdd]. */
sealed interface BulkOutcome {
    /** The upload was applied; [report] lists the three buckets. */
    data class Applied(val report: BulkAddReport) : BulkOutcome

    /** The upload's added count would exceed the trailing-hour cap; nothing was
     *  written (whole-bulk atomic reject, design D2). */
    data class RateLimited(val wouldAdd: Int, val currentCount: Int) : BulkOutcome
}
