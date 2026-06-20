package id.nearyou.app.account

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** A `data_export_requests` row's lifecycle state (the `status` CHECK domain + the row id). */
enum class DataExportStatus(val wire: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    READY("ready"),
    EXPIRED("expired"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String): DataExportStatus =
            entries.firstOrNull { it.wire == value }
                ?: error("unknown data_export_requests.status '$value'")
    }
}

/** The caller's latest export request, as surfaced to the status read. */
data class DataExportRequestRow(
    val id: UUID,
    val userId: UUID,
    /**
     * The effective status: `READY` rows whose [downloadExpiresAt] is in the past are
     * reported as [DataExportStatus.EXPIRED] (derived on read, not persisted), so the
     * status endpoint never hands out a fresh long-lived URL for a dead window.
     */
    val status: DataExportStatus,
    val downloadExpiresAt: Instant?,
    val r2ObjectKey: String?,
)

/** A claimed pending request the worker will process. */
data class ClaimedExportRequest(
    val id: UUID,
    val userId: UUID,
)

/**
 * JDBC for the `data_export_requests` lifecycle (V30) — the portability twin of
 * [AccountDeletionRepository]. Own-account writes: the `userId` is always the verified
 * JWT `sub` of the caller (the routes accept no `user_id` param → no IDOR surface). The
 * table is not a `visible_*` view nor a block/shadow-ban-protected table, so no
 * `@allow-*` annotation and no block/shadow-ban join applies (the lint rules match
 * `FROM posts|users|...`).
 *
 * Runs on the pool-bounded JDBC dispatcher (docs/11 §3.2); production passes
 * `DbDispatchers.db`.
 */
class DataExportRequestRepository(
    private val dataSource: DataSource,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Inserts a fresh `pending` request for [userId] and returns it. When the caller
     * already has an active (`pending`/`processing`) row, the `data_export_requests_one_active_idx`
     * partial-unique index raises a unique violation; this catches it and returns the
     * existing active row instead (the structural idempotency guard — design D6). A
     * `ready`/`expired`/`failed` prior row does not block a fresh request.
     */
    suspend fun insertPendingOrReturnActive(userId: UUID): DataExportRequestRow =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                try {
                    conn.prepareStatement(SQL_INSERT_PENDING).use { ps ->
                        ps.setObject(1, userId)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "insert-pending returned no row" }
                            rs.toRequestRow()
                        }
                    }
                } catch (e: SQLException) {
                    // 23505 = unique_violation on the one-active partial index → return existing.
                    if (e.sqlState == SQLSTATE_UNIQUE_VIOLATION) {
                        conn.prepareStatement(SQL_SELECT_ACTIVE).use { ps ->
                            ps.setObject(1, userId)
                            ps.executeQuery().use { rs ->
                                check(rs.next()) {
                                    "one-active unique violation but no active row found for user"
                                }
                                rs.toRequestRow()
                            }
                        }
                    } else {
                        throw e
                    }
                }
            }
        }

    /**
     * Optimistically claims a `pending` request for processing
     * (`UPDATE … SET status='processing', started_at=NOW() WHERE id=? AND status='pending'`).
     * Returns the claim only when a row was affected, so concurrent worker invocations never
     * double-process the same request.
     */
    suspend fun claimPending(requestId: UUID): ClaimedExportRequest? =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_CLAIM).use { ps ->
                    ps.setObject(1, requestId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            ClaimedExportRequest(
                                id = UUID.fromString(rs.getString("id")),
                                userId = UUID.fromString(rs.getString("user_id")),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    /** Snapshot of the `pending` request ids (oldest-first), no lock held — the worker scan. */
    suspend fun snapshotPending(): List<UUID> =
        withContext(dbDispatcher) {
            val ids = mutableListOf<UUID>()
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_SNAPSHOT_PENDING).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) ids += UUID.fromString(rs.getString("id"))
                    }
                }
            }
            ids
        }

    /**
     * Marks a claimed request `ready` with its object key + 24h deadline:
     * `status='ready', completed_at=NOW(), download_expires_at=NOW()+INTERVAL '24 hours'`.
     */
    suspend fun setReady(
        requestId: UUID,
        r2ObjectKey: String,
    ): Instant =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_SET_READY).use { ps ->
                    ps.setString(1, r2ObjectKey)
                    ps.setObject(2, requestId)
                    ps.executeQuery().use { rs ->
                        check(rs.next()) { "set-ready affected no row for $requestId" }
                        rs.getTimestamp("download_expires_at").toInstant()
                    }
                }
            }
        }

    /**
     * Marks a request `failed` with a non-PII [error] and increments `attempt_count`.
     * Used on a gather/upload failure (never on an email-only failure of an already-ready row).
     */
    suspend fun setFailed(
        requestId: UUID,
        error: String,
    ) = withContext(dbDispatcher) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(SQL_SET_FAILED).use { ps ->
                ps.setString(1, error)
                ps.setObject(2, requestId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * The caller's latest request (by `requested_at DESC`), or `null` when none. A `ready`
     * row past its `download_expires_at` is returned with [DataExportStatus.EXPIRED] (derived
     * on read). Filtered on `user_id` — never leaks another user's row.
     */
    suspend fun latestForUser(userId: UUID): DataExportRequestRow? =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SQL_LATEST).use { ps ->
                    ps.setObject(1, userId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.toRequestRow() else null
                    }
                }
            }
        }

    private fun java.sql.ResultSet.toRequestRow(): DataExportRequestRow {
        val rawStatus = DataExportStatus.fromWire(getString("status"))
        val expiresAt = getTimestamp("download_expires_at")?.toInstant()
        // Effective status: a READY row whose window has passed reads as EXPIRED.
        val effective =
            if (rawStatus == DataExportStatus.READY &&
                expiresAt != null &&
                expiresAt.isBefore(Instant.now())
            ) {
                DataExportStatus.EXPIRED
            } else {
                rawStatus
            }
        return DataExportRequestRow(
            id = UUID.fromString(getString("id")),
            userId = UUID.fromString(getString("user_id")),
            status = effective,
            downloadExpiresAt = expiresAt,
            r2ObjectKey = getString("r2_object_key"),
        )
    }

    private companion object {
        const val SQLSTATE_UNIQUE_VIOLATION = "23505"

        const val SQL_INSERT_PENDING =
            """
            INSERT INTO data_export_requests (user_id, status)
            VALUES (?, 'pending')
            RETURNING id, user_id, status, download_expires_at, r2_object_key
            """

        const val SQL_SELECT_ACTIVE =
            """
            SELECT id, user_id, status, download_expires_at, r2_object_key
              FROM data_export_requests
             WHERE user_id = ? AND status IN ('pending','processing')
             LIMIT 1
            """

        const val SQL_SNAPSHOT_PENDING =
            """
            SELECT id FROM data_export_requests
             WHERE status = 'pending'
             ORDER BY requested_at ASC
            """

        const val SQL_CLAIM =
            """
            UPDATE data_export_requests
               SET status = 'processing', started_at = NOW()
             WHERE id = ? AND status = 'pending'
            RETURNING id, user_id
            """

        const val SQL_SET_READY =
            """
            UPDATE data_export_requests
               SET status = 'ready',
                   r2_object_key = ?,
                   completed_at = NOW(),
                   download_expires_at = NOW() + INTERVAL '24 hours'
             WHERE id = ?
            RETURNING download_expires_at
            """

        const val SQL_SET_FAILED =
            """
            UPDATE data_export_requests
               SET status = 'failed',
                   error = ?,
                   attempt_count = attempt_count + 1
             WHERE id = ?
            """

        const val SQL_LATEST =
            """
            SELECT id, user_id, status, download_expires_at, r2_object_key
              FROM data_export_requests
             WHERE user_id = ?
             ORDER BY requested_at DESC
             LIMIT 1
            """
    }
}
