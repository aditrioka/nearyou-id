package id.nearyou.app.appeal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A persisted `appeals` row, projected for the own-appeal-status read. */
data class AppealRow(
    val id: UUID,
    val actionType: String,
    val status: String,
    val appealText: String,
    val decisionReason: String?,
    val reviewedAt: Instant?,
    val createdAt: Instant,
)

/**
 * JDBC for the `appeals` table (V34, `content-moderation-appeal`). Own-account
 * writes/reads — `userId` is always the verified appeal-realm principal, never a
 * request param (no IDOR surface). `appeals` is neither a `visible_*` view nor a
 * block/shadow-ban-protected table, so no `@allow-*` annotation and no block join
 * applies (the lint rules match `FROM posts|users|post_replies`).
 *
 * Runs on the pool-bounded JDBC dispatcher (docs/11 §3.2); production passes
 * `DbDispatchers.db`.
 */
class JdbcAppealRepository(
    private val dataSource: DataSource,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface InsertResult {
        data class Inserted(val id: UUID) : InsertResult

        /** The `appeals_one_pending_per_user` partial-unique fired — a pending appeal already exists. */
        data object PendingConflict : InsertResult
    }

    /**
     * Inserts a `pending` appeal with the server-derived [actionType]. The
     * `appeals_one_pending_per_user` partial-unique index is the race-safe one-pending
     * guard: a concurrent second insert raises SQLSTATE 23505, caught here and mapped
     * to [InsertResult.PendingConflict] (never surfaced as a 5xx).
     */
    suspend fun insertPending(
        userId: UUID,
        actionType: String,
        appealText: String,
    ): InsertResult =
        withContext(dbDispatcher) {
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(SQL_INSERT).use { ps ->
                        ps.setObject(1, userId)
                        ps.setString(2, actionType)
                        ps.setString(3, appealText)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "appeal insert returned no id" }
                            InsertResult.Inserted(rs.getObject("id", UUID::class.java))
                        }
                    }
                }
            } catch (e: SQLException) {
                if (e.sqlState == SQLSTATE_UNIQUE_VIOLATION) {
                    InsertResult.PendingConflict
                } else {
                    throw e
                }
            }
        }

    /** Returns the caller's most-recent appeal, or null when they have never submitted one. */
    suspend fun findLatest(userId: UUID): AppealRow? =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_LATEST).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            AppealRow(
                                id = rs.getObject("id", UUID::class.java),
                                actionType = rs.getString("action_type"),
                                status = rs.getString("status"),
                                appealText = rs.getString("appeal_text"),
                                decisionReason = rs.getString("decision_reason"),
                                reviewedAt = rs.getTimestamp("reviewed_at")?.toInstant(),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                            )
                        }
                    }
                }
            }
        }

    private companion object {
        const val SQLSTATE_UNIQUE_VIOLATION = "23505"

        const val SQL_INSERT =
            """
            INSERT INTO appeals (user_id, action_type, appeal_text)
            VALUES (?, ?, ?)
            RETURNING id
            """

        const val SQL_LATEST =
            """
            SELECT id, action_type, status, appeal_text, decision_reason, reviewed_at, created_at
              FROM appeals
             WHERE user_id = ?
             ORDER BY created_at DESC
             LIMIT 1
            """
    }
}
