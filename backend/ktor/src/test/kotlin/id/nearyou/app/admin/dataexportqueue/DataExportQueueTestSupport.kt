package id.nearyou.app.admin.dataexportqueue

import id.nearyou.app.account.DataExportArchiveService
import id.nearyou.app.account.DataExportGatherRepository
import id.nearyou.app.account.DataExportRequestRepository
import id.nearyou.app.account.DataExportSingleProcessor
import id.nearyou.app.account.DataExportWorker
import id.nearyou.app.account.PeerIdHasher
import id.nearyou.app.infra.r2.ObjectStore
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.resend.EmailSender
import id.nearyou.app.infra.resend.EmailTemplate
import id.nearyou.app.infra.resend.SendResult
import id.nearyou.app.notifications.DbNotificationEmitter
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration as KtDuration

/**
 * Seeding + assertion helpers + a REAL producer-pipeline processor for the Data Export
 * Queue integration tests (`admin-data-export-queue` tasks.md Section 5).
 *
 * Seeds `users` rows + `data_export_requests` rows in any lifecycle status, and builds a
 * real [DataExportWorker] (the producer single-request seam) wired against a configured
 * FAKE [ObjectStore] (captured bytes + deterministic URL) + no-op [EmailSender] so a
 * `failed`/`pending` trigger reaches `ready` WITHOUT hitting real R2/Resend — exactly the
 * one export path the batch worker uses. Cleanup is per-seeded-id; deleting a seeded
 * `users` row cascades its `data_export_requests` rows (FK `ON DELETE CASCADE`).
 */
object DataExportQueueTestSupport {
    /** Seed a `users` row; returns its id. */
    fun seedUser(
        dataSource: DataSource,
        username: String,
        displayName: String = "Export Tester",
    ): UUID {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "x${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /**
     * Seed a `data_export_requests` row for [userId] in [status]; returns the request id.
     * [requestedAt] is truncated to micros (CI-Linux timestamptz truncation parity, docs/11
     * §3.2). A `ready` row carries [r2ObjectKey] + [downloadExpiresAt]; a `failed` row
     * carries an [attemptCount] (+ a non-PII error).
     */
    fun seedRequest(
        dataSource: DataSource,
        userId: UUID,
        status: String = "pending",
        requestedAt: Instant = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS),
        r2ObjectKey: String? = if (status == "ready") "exports/$userId/seed.zip" else null,
        downloadExpiresAt: Instant? = if (status == "ready") Instant.now().plusSeconds(24 * 3600L) else null,
        attemptCount: Int = if (status == "failed") 1 else 0,
        error: String? = if (status == "failed") "gather_or_upload_failed" else null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO data_export_requests (
                    id, user_id, status, requested_at, r2_object_key, download_expires_at, attempt_count, error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, status)
                ps.setObject(4, Timestamp.from(requestedAt))
                ps.setString(5, r2ObjectKey)
                ps.setObject(6, downloadExpiresAt?.let { Timestamp.from(it) })
                ps.setInt(7, attemptCount)
                ps.setString(8, error)
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed an `admin_actions_log` row of [actionType] for [adminId] — backs the cap + budget tests. */
    fun seedAuditRow(
        dataSource: DataSource,
        adminId: UUID,
        actionType: String,
        targetId: UUID? = null,
        createdAt: Instant = Instant.now(),
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO admin_actions_log (admin_id, action_type, target_type, target_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, adminId)
                ps.setString(2, actionType)
                ps.setString(3, if (targetId != null) "data_export_request" else null)
                ps.setString(4, targetId?.toString())
                ps.setObject(5, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
    }

    /** Current `status` of [requestId]. */
    fun statusOf(
        dataSource: DataSource,
        requestId: UUID,
    ): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status FROM data_export_requests WHERE id = ?").use { ps ->
                ps.setObject(1, requestId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** `attempt_count` of [requestId]. */
    fun attemptCountOf(
        dataSource: DataSource,
        requestId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT attempt_count FROM data_export_requests WHERE id = ?").use { ps ->
                ps.setObject(1, requestId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Count `data_export_triggered` audit rows targeting [requestId]. */
    fun countTriggersFor(
        dataSource: DataSource,
        requestId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM admin_actions_log " +
                    "WHERE action_type = 'data_export_triggered' AND target_id = ?",
            ).use { ps ->
                ps.setString(1, requestId.toString())
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Count `data_export_ready` notification rows for [userId]. */
    fun notificationCount(
        dataSource: DataSource,
        userId: UUID,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'data_export_ready'",
            ).use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Remove a seeded user; the `ON DELETE CASCADE` FK removes its `data_export_requests` rows. */
    fun deleteUser(
        dataSource: DataSource,
        id: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM notifications WHERE user_id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
    }

    /**
     * A REAL producer single-request seam over [dataSource] with a configured FAKE
     * [ObjectStore] (captures bytes, presigns a fake URL) + a no-op [EmailSender], so a
     * triggered `failed`/`pending` row runs the full gather → ZIP → upload → setReady →
     * notify pipeline and reaches `ready` without real R2/Resend.
     */
    fun realProcessor(dataSource: DataSource): DataExportSingleProcessor =
        DataExportWorker(
            dataSource = dataSource,
            requests = DataExportRequestRepository(dataSource),
            gather = DataExportGatherRepository(dataSource, PeerIdHasher.fromSecret(null)),
            archiveService = DataExportArchiveService(),
            objectStore = FakeObjectStore,
            emailSender = NoopEmailSender,
            notificationEmitter = DbNotificationEmitter(JdbcNotificationRepository(dataSource)),
        )

    private object FakeObjectStore : ObjectStore {
        override fun isConfigured(): Boolean = true

        override suspend fun put(
            key: String,
            bytes: ByteArray,
            contentType: String,
        ) = Unit

        override suspend fun presignedGetUrl(
            key: String,
            ttl: KtDuration,
        ): String = "https://fake.r2.example/$key?sig=test"

        override suspend fun delete(key: String) = Unit
    }

    private object NoopEmailSender : EmailSender {
        override fun isConfigured(): Boolean = false

        override suspend fun send(
            to: String,
            template: EmailTemplate,
            idempotencyKey: String,
        ): SendResult = SendResult.Skipped
    }
}
