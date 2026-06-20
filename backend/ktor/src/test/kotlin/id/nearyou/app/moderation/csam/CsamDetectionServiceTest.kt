package id.nearyou.app.moderation.csam

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.SuspensionUnbanWorker
import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end coverage for [CsamDetectionService.handleDetection] — the fixed-policy
 * CSAM takedown executed in ONE transaction. This is the safety-critical heart of the
 * subsystem; every effect is asserted exactly (post tombstones, ban + token bump,
 * cascade, exactly-one archive row, moderation_queue, audit attribution, encryption).
 *
 * Scenarios:
 *  - 7.4  admin-manual E2E — affected post deleted, uploader banned + token+1, benign
 *         post also deleted, ONE archive row (admin_manual, expires=created+90d,
 *         kominfo NULL), moderation_queue (post, csam_detected), audit admin_id=actor.
 *  - 7.5  cf_worker E2E — same, audit admin_id = system sentinel.
 *  - 7.6  idempotent re-trigger — no 2nd archive/queue row, is_banned stays true,
 *         newlyBanned=false on the 2nd call.
 *  - 7.7  ledger-miss — cfImageId not in image_uploads → archive written, no throw,
 *         uploaderResolved=false.
 *  - 7.10 encryption — key set → encrypted_metadata decrypts to JSON with the uploader
 *         id; key unset → takedown still happens, blob NULL, plaintext essentials present.
 *  - 7.13 moderation_queue accepts csam_detected + idempotent on (target_type,target_id,trigger).
 */
@Tags("database")
class CsamDetectionServiceTest : StringSpec({

    // afterSpec cleanup present → do NOT autoClose; close in afterSpec.
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
                username = System.getenv("DB_USER") ?: "postgres"
                password = System.getenv("DB_PASSWORD") ?: "postgres"
                maximumPoolSize = 3
                initializationFailTimeout = -1
            },
        )

    // Fixed AES-256 key for the key-set encryption scenario. Test-only.
    val fixedKey = ByteArray(32) { (it + 13).toByte() }

    val runTag = "csamsvc_${UUID.randomUUID().toString().take(8)}"
    val seededUserIds = mutableListOf<UUID>()
    val seededImageHashes = mutableListOf<String>()

    fun serviceWithKey(keyProvider: () -> ByteArray?) =
        CsamDetectionService(
            dataSource = dataSource,
            dbDispatcher = Dispatchers.IO,
            csamRepository = CsamRepository(dataSource),
            moderationQueueRepository = JdbcModerationQueueRepository(),
            encryptor = CsamMetadataEncryptor(keyProvider),
            auditLogger = AdminAuditLogger(dataSource),
        )

    val service = serviceWithKey { fixedKey } // key SET by default

    fun seedUser(): UUID {
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
                ps.setString(2, "cs_$short")
                ps.setString(3, "CSAM Svc $short")
                ps.setObject(4, LocalDate.of(1990, 1, 1))
                ps.setString(5, "CS${short.take(5).uppercase()}")
                ps.executeUpdate()
            }
        }
        seededUserIds += id
        return id
    }

    fun seedPost(
        authorId: UUID,
        imageId: String?,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, image_id, is_auto_hidden)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?, FALSE)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, "$runTag-post-${id.toString().take(6)}")
                ps.setDouble(4, 105.0)
                ps.setDouble(5, -10.5)
                ps.setDouble(6, 105.0)
                ps.setDouble(7, -10.5)
                if (imageId != null) ps.setString(8, imageId) else ps.setNull(8, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedImageUpload(
        cfImageId: String,
        uploaderId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO image_uploads (cf_image_id, uploader_user_id, status) VALUES (?, ?, 'attached')",
            ).use { ps ->
                ps.setString(1, cfImageId)
                ps.setObject(2, uploaderId)
                ps.executeUpdate()
            }
        }
    }

    fun input(
        cfImageId: String,
        imageHash: String,
        source: CsamDetectionService.Source,
        actorAdminId: UUID,
        cfMatchId: String? = null,
        ncmecReference: String? = null,
    ): CsamDetectionService.Input {
        seededImageHashes += imageHash
        return CsamDetectionService.Input(
            cfImageId = cfImageId,
            imageHash = imageHash,
            cfMatchId = cfMatchId,
            ncmecReference = ncmecReference,
            source = source,
            actorAdminId = actorAdminId,
            ip = "203.0.113.7",
            userAgent = "test-agent",
        )
    }

    // ---- query helpers ----

    fun postDeletedAt(postId: UUID): java.time.Instant? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT deleted_at FROM posts WHERE id = ?").use { ps ->
                ps.setObject(1, postId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    rs.getTimestamp("deleted_at")?.toInstant()
                }
            }
        }

    fun userBanState(userId: UUID): Pair<Boolean, Int> =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT is_banned, token_version FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean("is_banned") to rs.getInt("token_version")
                }
            }
        }

    data class ArchiveRow(
        val source: String,
        val kominfoReportedAt: java.time.Instant?,
        val encrypted: ByteArray?,
        val createdAtMicros: Long,
        val expiresAtMicros: Long,
    )

    fun archiveRow(imageHash: String): ArchiveRow? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT source, kominfo_reported_at, encrypted_metadata,
                       EXTRACT(EPOCH FROM created_at) AS c, EXTRACT(EPOCH FROM expires_at) AS e
                  FROM csam_detection_archive WHERE image_hash = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, imageHash)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    ArchiveRow(
                        source = rs.getString("source"),
                        kominfoReportedAt = rs.getTimestamp("kominfo_reported_at")?.toInstant(),
                        encrypted = rs.getBytes("encrypted_metadata"),
                        createdAtMicros = (rs.getDouble("c") * 1_000_000).toLong(),
                        expiresAtMicros = (rs.getDouble("e") * 1_000_000).toLong(),
                    )
                }
            }
        }

    fun archiveCount(imageHash: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM csam_detection_archive WHERE image_hash = ?").use { ps ->
                ps.setString(1, imageHash)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    fun moderationQueueCount(
        targetId: UUID,
        trigger: String = "csam_detected",
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM moderation_queue WHERE target_id = ? AND trigger = ?",
            ).use { ps ->
                ps.setObject(1, targetId)
                ps.setString(2, trigger)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    data class AuditRow(val adminId: UUID, val targetType: String?, val targetId: String?, val afterState: String?)

    fun csamAuditRows(targetUserId: UUID): List<AuditRow> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT admin_id, target_type, target_id, after_state::text AS afs
                  FROM admin_actions_log
                 WHERE action_type = 'csam_takedown' AND target_id = ?
                 ORDER BY created_at DESC
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, targetUserId.toString())
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                AuditRow(
                                    adminId = rs.getObject("admin_id", UUID::class.java),
                                    targetType = rs.getString("target_type"),
                                    targetId = rs.getString("target_id"),
                                    afterState = rs.getString("afs"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    afterSpec {
        dataSource.connection.use { conn ->
            // Audit rows are keyed by target_id = the uploader's UUID string.
            if (seededUserIds.isNotEmpty()) {
                conn.prepareStatement(
                    "DELETE FROM admin_actions_log WHERE action_type = 'csam_takedown' AND target_id = ANY(?)",
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("text", seededUserIds.map { it.toString() }.toTypedArray()))
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "DELETE FROM moderation_queue WHERE target_id IN (SELECT id FROM posts WHERE author_id = ANY(?))",
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", seededUserIds.toTypedArray()))
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("DELETE FROM posts WHERE content LIKE ?").use { ps ->
                ps.setString(1, "$runTag-post-%")
                ps.executeUpdate()
            }
            if (seededImageHashes.isNotEmpty()) {
                conn.prepareStatement("DELETE FROM csam_detection_archive WHERE image_hash = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("text", seededImageHashes.toTypedArray()))
                    ps.executeUpdate()
                }
            }
            if (seededUserIds.isNotEmpty()) {
                conn.prepareStatement("DELETE FROM image_uploads WHERE uploader_user_id = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", seededUserIds.toTypedArray()))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", seededUserIds.toTypedArray()))
                    ps.executeUpdate()
                }
            }
        }
        dataSource.close()
    }

    "7.4 admin-manual E2E — full fixed-policy takedown effect" {
        val uploader = seedUser()
        val actorAdmin = SuspensionUnbanWorker.SYSTEM_ACTOR_ID // any valid admin_users id; sentinel is seeded
        val cfImageId = "$runTag-img-74"
        seedImageUpload(cfImageId, uploader)
        val affectedPost = seedPost(uploader, imageId = cfImageId)
        val benignPost = seedPost(uploader, imageId = null)
        val hash = "$runTag-hash-74"
        val (_, versionBefore) = userBanState(uploader)

        val result =
            service.handleDetection(
                input(
                    cfImageId,
                    hash,
                    CsamDetectionService.Source.ADMIN_MANUAL,
                    actorAdmin,
                    cfMatchId = "$runTag-cf-74",
                    ncmecReference = "NCMEC-74",
                ),
            )

        // Result snapshot.
        result.uploaderResolved shouldBe true
        result.newlyBanned shouldBe true
        result.affectedPostId shouldBe affectedPost
        result.postsCascadeTombstoned shouldBe 1 // the benign post (affected already tombstoned)

        // Affected post + benign post both tombstoned.
        postDeletedAt(affectedPost).shouldNotBeNull()
        postDeletedAt(benignPost).shouldNotBeNull()

        // Uploader permanently banned + token_version bumped once.
        val (banned, versionAfter) = userBanState(uploader)
        banned shouldBe true
        versionAfter shouldBe versionBefore + 1

        // Exactly ONE archive row, admin_manual, expires=created+90d, kominfo NULL.
        archiveCount(hash) shouldBe 1
        val arc = archiveRow(hash)!!
        arc.source shouldBe "admin_manual"
        arc.kominfoReportedAt.shouldBeNull()
        (arc.expiresAtMicros - arc.createdAtMicros) shouldBe 90L * 24 * 3600 * 1_000_000

        // moderation_queue row for the affected POST, csam_detected.
        moderationQueueCount(affectedPost) shouldBe 1

        // ONE audit row, target user = uploader, admin_id = the acting admin.
        val audits = csamAuditRows(uploader)
        audits.size shouldBe 1
        audits[0].adminId shouldBe actorAdmin
        audits[0].targetType shouldBe "user"
        audits[0].targetId shouldBe uploader.toString()
    }

    "7.5 cf_worker E2E — audit attributed to the system sentinel" {
        val uploader = seedUser()
        val cfImageId = "$runTag-img-75"
        seedImageUpload(cfImageId, uploader)
        val affectedPost = seedPost(uploader, imageId = cfImageId)
        val hash = "$runTag-hash-75"

        val result =
            service.handleDetection(
                input(
                    cfImageId,
                    hash,
                    CsamDetectionService.Source.CF_WORKER,
                    SuspensionUnbanWorker.SYSTEM_ACTOR_ID,
                    cfMatchId = "$runTag-cf-75",
                ),
            )

        result.uploaderResolved shouldBe true
        result.newlyBanned shouldBe true
        postDeletedAt(affectedPost).shouldNotBeNull()

        val arc = archiveRow(hash)!!
        arc.source shouldBe "cf_worker"

        val audits = csamAuditRows(uploader)
        audits.size shouldBe 1
        audits[0].adminId shouldBe SuspensionUnbanWorker.SYSTEM_ACTOR_ID
    }

    "7.6 idempotent re-trigger — no 2nd archive/queue row, ban stays, newlyBanned=false" {
        val uploader = seedUser()
        val cfImageId = "$runTag-img-76"
        seedImageUpload(cfImageId, uploader)
        val affectedPost = seedPost(uploader, imageId = cfImageId)
        val hash = "$runTag-hash-76"

        val first =
            service.handleDetection(
                input(
                    cfImageId,
                    hash,
                    CsamDetectionService.Source.CF_WORKER,
                    SuspensionUnbanWorker.SYSTEM_ACTOR_ID,
                    cfMatchId = "$runTag-cf-76",
                ),
            )
        first.newlyBanned shouldBe true

        val second =
            service.handleDetection(
                input(
                    cfImageId,
                    hash,
                    CsamDetectionService.Source.CF_WORKER,
                    SuspensionUnbanWorker.SYSTEM_ACTOR_ID,
                    cfMatchId = "$runTag-cf-76",
                ),
            )

        // Converges: success, but no new effects.
        second.uploaderResolved shouldBe true
        second.newlyBanned shouldBe false // already banned
        second.affectedPostId.shouldBeNull() // already tombstoned → no row returned
        second.postsCascadeTombstoned shouldBe 0

        archiveCount(hash) shouldBe 1 // ON CONFLICT enrich, still one row
        moderationQueueCount(affectedPost) shouldBe 1 // ON CONFLICT DO NOTHING, still one row

        val (banned, _) = userBanState(uploader)
        banned shouldBe true
    }

    "7.7 ledger-miss — cfImageId absent from image_uploads → archive only, no throw, uploaderResolved=false" {
        val cfImageId = "$runTag-img-77-absent" // no image_uploads row seeded
        val hash = "$runTag-hash-77"

        val result =
            service.handleDetection(
                input(
                    cfImageId,
                    hash,
                    CsamDetectionService.Source.CF_WORKER,
                    SuspensionUnbanWorker.SYSTEM_ACTOR_ID,
                    cfMatchId = "$runTag-cf-77",
                ),
            )

        result.uploaderResolved shouldBe false
        result.newlyBanned shouldBe false
        result.affectedPostId.shouldBeNull()
        result.postsCascadeTombstoned shouldBe 0

        // The match is still archived so the Kominfo record exists.
        archiveCount(hash) shouldBe 1
        archiveRow(hash)!!.source shouldBe "cf_worker"
    }

    "7.10 encryption — key set → encrypted_metadata decrypts to JSON containing the uploader id" {
        val uploader = seedUser()
        val cfImageId = "$runTag-img-710a"
        seedImageUpload(cfImageId, uploader)
        seedPost(uploader, imageId = cfImageId)
        val hash = "$runTag-hash-710a"

        service.handleDetection(
            input(cfImageId, hash, CsamDetectionService.Source.CF_WORKER, SuspensionUnbanWorker.SYSTEM_ACTOR_ID),
        )

        val arc = archiveRow(hash)!!
        arc.encrypted.shouldNotBeNull()
        // Decrypt with the same key + image_hash AAD; the plaintext JSON names the uploader.
        val decrypted = CsamMetadataEncryptor { fixedKey }.decrypt(arc.encrypted!!, hash)
        val json = String(decrypted, Charsets.UTF_8)
        json shouldContain uploader.toString()
        json shouldContain "uploader_user_id"
    }

    "7.10 encryption — key UNSET → takedown still happens, encrypted_metadata NULL, plaintext essentials present" {
        val uploader = seedUser()
        val cfImageId = "$runTag-img-710b"
        seedImageUpload(cfImageId, uploader)
        val affectedPost = seedPost(uploader, imageId = cfImageId)
        val hash = "$runTag-hash-710b"

        // Service with an UNPROVISIONED key (fail-soft).
        val degraded = serviceWithKey { null }
        val result =
            degraded.handleDetection(
                input(
                    cfImageId,
                    hash,
                    CsamDetectionService.Source.ADMIN_MANUAL,
                    SuspensionUnbanWorker.SYSTEM_ACTOR_ID,
                    ncmecReference = "NCMEC-710b",
                ),
            )

        // Safety-critical takedown is NOT blocked on key availability.
        result.uploaderResolved shouldBe true
        result.newlyBanned shouldBe true
        postDeletedAt(affectedPost).shouldNotBeNull()
        userBanState(uploader).first shouldBe true

        // Archive row written with NULL blob but plaintext essentials intact.
        val arc = archiveRow(hash)!!
        arc.encrypted.shouldBeNull()
        arc.source shouldBe "admin_manual"
    }

    "7.13 moderation_queue accepts csam_detected and is idempotent on (target_type,target_id,trigger)" {
        // Direct repo-level idempotency check against the live CHECK constraint, in the
        // same shape the service uses (one tombstoned post target).
        val uploader = seedUser()
        val cfImageId = "$runTag-img-713"
        seedImageUpload(cfImageId, uploader)
        val affectedPost = seedPost(uploader, imageId = cfImageId)
        val hash = "$runTag-hash-713"

        service.handleDetection(
            input(cfImageId, hash, CsamDetectionService.Source.CF_WORKER, SuspensionUnbanWorker.SYSTEM_ACTOR_ID),
        )
        moderationQueueCount(affectedPost) shouldBe 1

        // A direct second insert of the same (post, csam_detected) is a no-op (ON CONFLICT DO NOTHING).
        val inserted =
            dataSource.connection.use { conn ->
                JdbcModerationQueueRepository().upsertCsamDetectedRow(
                    conn,
                    id.nearyou.data.repository.ReportTargetType.POST,
                    affectedPost,
                )
            }
        inserted shouldBe false // ON CONFLICT branch fired
        moderationQueueCount(affectedPost) shouldBe 1
    }
})
