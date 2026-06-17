package id.nearyou.app.admin.subscriptiongrace

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.admin.ratelimit.GraceExpediteActionRateLimiter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** The `subscription_status` value the monitor lists (the billing-retry grace). */
const val STATUS_BILLING_RETRY: String = "premium_billing_retry"

/**
 * Read + write repository for the Subscription Grace Monitor
 * (`admin-subscription-grace-monitor` capability, admin board frame 18). Lists
 * the `users` rows in `subscription_status = 'premium_billing_retry'` — the
 * RevenueCat billing-retry grace population per `docs/07-Operations.md`
 * § Subscription Grace Monitor — and records the manual "expedite" bookkeeping
 * write.
 *
 * The 5th instance of the read-only-admin-viewer pattern (clones
 * `AdminPrivacyFlipsRepository`'s keyset pagination + lenient parameterized
 * filters + opaque cursor + `pageSize + 1` has-more probe), extended with ONE
 * non-punitive write (design D1). Deltas vs the privacy-flip viewer:
 *  - **Reads `users` filtered to billing-retry + not soft-deleted** (the
 *    `users_subscription_idx` V2 partial index serves the predicate; design D4 —
 *    NO migration). Admin-module raw read of `users` / `subscription_events`:
 *    the block-exclusion / raw-`FROM posts` lint rules do not fire on these
 *    tables, so no annotation is required (per the privacy-flip precedent).
 *  - **LEFT JOIN LATERAL `subscription_events`** for the latest event
 *    (store/platform + last webhook) AND the current-streak retry-since (design
 *    D6), and **LEFT JOIN LATERAL `admin_actions_log`** for the latest expedite
 *    indicator — all LEFT so a billing-retry user with no events / no prior
 *    expedite still renders (empty-state tolerant).
 *  - **DESC newest-retry-first order** over a non-null `COALESCE(retry_since,
 *    last_event_at, epoch)` sort key, so the keyset cursor needs no NULLS-LAST
 *    handling.
 *
 * Expedite is bookkeeping-only (design D1): it mutates NO entitlement and writes
 * NO `subscription_events` row — RevenueCat stays the source of truth. It writes
 * exactly one immutable `admin_actions_log` row inside one transaction, gated by
 * the distinct [GraceExpediteActionRateLimiter] cap and validated against the
 * target's billing-retry state (design D7).
 */
class SubscriptionGraceRepository(
    private val dataSource: DataSource,
    private val auditLogger: AdminAuditLogger,
    private val rateLimiter: GraceExpediteActionRateLimiter,
) {
    /**
     * Run the filtered + keyset-paginated page query. Fetches `pageSize + 1`
     * rows: the extra (if present) signals a further page and its predecessor
     * becomes the next cursor — no OFFSET, no total-count query. Every applied
     * filter value is a positionally-bound `PreparedStatement` parameter.
     */
    fun query(q: GraceQuery): GracePage {
        val (conditions, params) = filterClauses(q)
        q.cursor?.let {
            // Keyset "next" predicate for the DESC ordering: row-value comparison
            // over (sort_at, id). The sort expression is repeated verbatim (a SQL
            // alias is not visible in WHERE). `id` is the deterministic tiebreaker.
            conditions += "($SORT_EXPR, u.id) < (?, ?)"
            params += java.sql.Timestamp.from(it.sortAt)
            params += it.id
        }

        val sql =
            buildString {
                append(BASE_SELECT)
                conditions.forEach { append("\n   AND ").append(it) }
                append("\n ORDER BY sort_at DESC, u.id DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<GraceRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) fetched += rs.toGraceRow()
                }
            }
        }

        val hasNext = fetched.size > q.pageSize
        val display = if (hasNext) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasNext && display.isNotEmpty()) {
                display.last().let { GraceCursor(it.sortAt, it.id) }
            } else {
                null
            }
        return GracePage(rows = display, nextCursor = nextCursor)
    }

    /**
     * Total count of the billing-retry population scoped to the SAME active
     * filters as [query] but IGNORING the keyset cursor and page size — the
     * operational signal (a spike indicates a billing / webhook problem). One
     * `COUNT(*)` over the filtered set.
     */
    fun summary(q: GraceQuery): Long {
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
     * Record a manual expedite for [userId] in ONE transaction (design D1/D7):
     * lock the user row, reject ([ExpediteOutcome.Ineligible]) when it is not a
     * non-deleted billing-retry user (unknown / other status / soft-deleted),
     * gate on the distinct expedite cap ([ExpediteOutcome.RateLimited]), then
     * write exactly one immutable `subscription_grace_expedite` audit row —
     * mutating NO `users` column and NO `subscription_events` row. The
     * before/after snapshots carry an identical `subscription_status` to make the
     * no-entitlement-change explicit. Commit-or-rollback together.
     */
    fun expedite(
        userId: UUID,
        actingAdminId: UUID,
        ticketRef: String,
        note: String?,
        ip: String,
        userAgent: String?,
    ): ExpediteOutcome =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val eligible = lockEligibleBillingRetryUser(conn, userId)
                if (!eligible) {
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

                val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
                val reason = if (cleanNote != null) "$ticketRef — $cleanNote" else ticketRef
                auditLogger.logSubscriptionGraceExpedite(
                    conn = conn,
                    adminId = actingAdminId,
                    targetUserId = userId,
                    reason = reason,
                    // Entitlement is UNCHANGED — both snapshots carry the same
                    // status; after_state additionally records the bookkeeping.
                    beforeState =
                        buildJsonObject {
                            put("subscription_status", JsonPrimitive(STATUS_BILLING_RETRY))
                        },
                    afterState =
                        buildJsonObject {
                            put("subscription_status", JsonPrimitive(STATUS_BILLING_RETRY))
                            put("expedited", JsonPrimitive(true))
                            put("ticket_ref", JsonPrimitive(ticketRef))
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

    /** `SELECT … FOR UPDATE` the target user; true only when it is a non-deleted
     *  `premium_billing_retry` user (the population the monitor lists). */
    private fun lockEligibleBillingRetryUser(
        conn: Connection,
        userId: UUID,
    ): Boolean =
        conn.prepareStatement(
            "SELECT subscription_status, deleted_at FROM users WHERE id = ? FOR UPDATE",
        ).use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return false
                val status = rs.getString("subscription_status")
                val deleted = rs.getTimestamp("deleted_at") != null
                status == STATUS_BILLING_RETRY && !deleted
            }
        }

    /**
     * The filter half of the WHERE clause shared by [query] and [summary] so the
     * summary counts EXACTLY the filtered set the page query pages over. Every
     * value is bound positionally; nothing is interpolated. A blank `q` /
     * `store` reaches here already dropped by the route (so only meaningful
     * filters appear).
     */
    private fun filterClauses(q: GraceQuery): Pair<MutableList<String>, MutableList<Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        q.qUserId?.let {
            conditions += "u.id = ?"
            params += it
        }
        q.qUsername?.let {
            conditions += "LOWER(u.username) = LOWER(?)"
            params += it
        }
        q.store?.let {
            // Filter on the latest event's platform (the current store). A user
            // with no event row has NULL le.platform → excluded when a store
            // filter is active (correct: it has no known store to match).
            conditions += "LOWER(le.platform) = LOWER(?)"
            params += it
        }
        return conditions to params
    }

    private fun java.sql.ResultSet.toGraceRow(): GraceRow =
        GraceRow(
            id = getObject("id", UUID::class.java),
            username = getString("username"),
            platform = getString("last_platform"),
            retrySince = getTimestamp("retry_since")?.toInstant(),
            lastEventType = getString("last_event_type"),
            lastEventAt = getTimestamp("last_event_at")?.toInstant(),
            expeditedBy = getString("expedited_by"),
            expeditedAt = getTimestamp("expedited_at")?.toInstant(),
            sortAt = getTimestamp("sort_at").toInstant(),
        )

    companion object {
        /** Fixed page size (design D3 — implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50

        // The DESC sort key: the current-streak retry-since, falling back to the
        // latest event time, then to a fixed epoch sentinel so the value is NEVER
        // NULL (a clean keyset with no NULLS-LAST handling; users with no events
        // sort last).
        private const val SORT_EXPR =
            "COALESCE(rs.retry_since, le.created_at, TIMESTAMPTZ 'epoch')"

        // LATERAL #1: the user's most-recent subscription event (store/platform +
        // last webhook). LATERAL #2: the current-streak retry-since — the earliest
        // billing_issue AFTER the last renewal/purchase (design D6); NULL when no
        // billing_issue in the current streak. LATERAL #3: the most-recent expedite
        // bookkeeping row + the acting admin's display name (the handled indicator).
        private val BASE_SELECT =
            """
            SELECT u.id, u.username,
                   le.event_type   AS last_event_type,
                   le.platform     AS last_platform,
                   le.created_at   AS last_event_at,
                   rs.retry_since  AS retry_since,
                   ex.admin_name   AS expedited_by,
                   ex.expedited_at AS expedited_at,
                   $SORT_EXPR      AS sort_at
              FROM users u
              LEFT JOIN LATERAL (
                  SELECT se.event_type, se.platform, se.created_at
                    FROM subscription_events se
                   WHERE se.user_id = u.id
                   ORDER BY se.created_at DESC
                   LIMIT 1
              ) le ON TRUE
              LEFT JOIN LATERAL (
                  SELECT MIN(se.created_at) AS retry_since
                    FROM subscription_events se
                   WHERE se.user_id = u.id
                     AND se.event_type = 'billing_issue'
                     AND se.created_at > COALESCE(
                           (SELECT MAX(se2.created_at) FROM subscription_events se2
                             WHERE se2.user_id = u.id
                               AND se2.event_type IN ('renewal', 'initial_purchase')),
                           TIMESTAMPTZ 'epoch')
              ) rs ON TRUE
              LEFT JOIN LATERAL (
                  SELECT au.display_name AS admin_name, aal.created_at AS expedited_at
                    FROM admin_actions_log aal
                    JOIN admin_users au ON au.id = aal.admin_id
                   WHERE aal.action_type = 'subscription_grace_expedite'
                     AND aal.target_id = u.id::text
                   ORDER BY aal.created_at DESC
                   LIMIT 1
              ) ex ON TRUE
             WHERE u.subscription_status = '$STATUS_BILLING_RETRY'
               AND u.deleted_at IS NULL
            """.trimIndent()

        private val COUNT_SELECT =
            """
            SELECT COUNT(*)
              FROM users u
              LEFT JOIN LATERAL (
                  SELECT se.platform
                    FROM subscription_events se
                   WHERE se.user_id = u.id
                   ORDER BY se.created_at DESC
                   LIMIT 1
              ) le ON TRUE
             WHERE u.subscription_status = '$STATUS_BILLING_RETRY'
               AND u.deleted_at IS NULL
            """.trimIndent()
    }
}

/** A single rendered billing-retry row (a `users` row in `premium_billing_retry`). */
data class GraceRow(
    val id: UUID,
    val username: String,
    /** The latest event's store/platform (RevenueCat `store`, lowercased); null when no events. */
    val platform: String?,
    /** When the current billing-retry streak began (earliest billing_issue after the last success); null when unknown. */
    val retrySince: Instant?,
    /** The latest webhook event's type; null when no events. */
    val lastEventType: String?,
    /** The latest webhook event's timestamp; null when no events. */
    val lastEventAt: Instant?,
    /** Display name of the admin who last expedited this row; null when never expedited. */
    val expeditedBy: String?,
    /** When this row was last expedited; null when never expedited. */
    val expeditedAt: Instant?,
    /** The non-null DESC keyset sort value (retry-since / last-event / epoch). */
    val sortAt: Instant,
)

/**
 * The active filter set + page size + optional keyset cursor for one query.
 * `qUserId` and `qUsername` are mutually exclusive (the route parses the single
 * `q` param into exactly one of them); `store` filters on the current platform.
 */
data class GraceQuery(
    val qUserId: UUID? = null,
    val qUsername: String? = null,
    val store: String? = null,
    val cursor: GraceCursor? = null,
    val pageSize: Int = SubscriptionGraceRepository.PAGE_SIZE,
)

/** One page of results plus the cursor for the next page (null = last page). */
data class GracePage(
    val rows: List<GraceRow>,
    val nextCursor: GraceCursor?,
)

/**
 * Opaque keyset cursor over `(sort_at, id)` for the DESC ordering. Encoded as
 * `base64url("<sortAtEpochMicros>|<id>")`. Opaque but NOT a security boundary —
 * it only encodes a sort position over data the admin is already authorized to
 * read. A malformed token decodes to `null` (→ first page), never throws.
 */
data class GraceCursor(
    val sortAt: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${sortAt.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): GraceCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep <= 0 || sep == raw.length - 1) return null
                val micros = raw.substring(0, sep).toLong()
                val id = UUID.fromString(raw.substring(sep + 1))
                GraceCursor(instantFromEpochMicros(micros), id)
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

/** Typed result of [SubscriptionGraceRepository.expedite]. */
sealed interface ExpediteOutcome {
    /** The expedite recorded one immutable audit row; no entitlement changed. */
    data object Applied : ExpediteOutcome

    /** The target is not a non-deleted billing-retry user (unknown / other status / soft-deleted); nothing written. */
    data object Ineligible : ExpediteOutcome

    /** Acting admin is at/over the expedite cap; nothing written. */
    data object RateLimited : ExpediteOutcome
}
