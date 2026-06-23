package id.nearyou.app.admin.csam

import id.nearyou.app.moderation.csam.CsamMetadataEncryptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding + assertion helpers for the `admin-csam-detection-log` route integration tests
 * (tasks.md Section 7). Seeds `csam_detection_archive` rows with a controllable
 * `source` / `kominfo_reported_at` / `encrypted_metadata`, and (for the takedown
 * scenarios) the full `users` + `image_uploads` + `posts` fixture the in-process
 * [id.nearyou.app.moderation.csam.CsamDetectionService] resolves.
 *
 * Cleanup is per-seeded-id / per-run-prefix (NOT a full-table wipe), mirroring
 * [id.nearyou.app.moderation.csam.CsamDetectionServiceTest]'s afterSpec: archive rows by
 * `image_hash`, posts by `content` prefix, `csam_*` audit rows by `target_id` (the
 * archive id or uploader id), `moderation_queue` rows by author, `image_uploads` +
 * `users` by id. The CSAM audit `action_type`s
 * (`csam_takedown`/`csam_kominfo_reported`/`csam_metadata_decrypted`) are keyed by
 * `target_id`, so cleanup keys on the seeded archive/uploader ids — NOT on `admin_id`
 * (the acting admin is cleaned by `AdminAuthTestSupport.cleanupAdmin`).
 */
object CsamLogTestSupport {
    /** A 32-byte test AES key (NOT a real secret) for the decrypt-path scenarios. */
    val FIXED_CSAM_AES_KEY: ByteArray = ByteArray(32) { (it + 41).toByte() }

    /** An encryptor with the fixed key provisioned (the decrypt-succeeds path). */
    fun encryptorWithKey(): CsamMetadataEncryptor = CsamMetadataEncryptor { FIXED_CSAM_AES_KEY }

    /** An encryptor with NO key provisioned (the fail-soft "metadata unavailable" path). */
    fun encryptorNoKey(): CsamMetadataEncryptor = CsamMetadataEncryptor { null }

    /**
     * Seed a `csam_detection_archive` row directly with controlled columns; returns the
     * archive row id. [encryptedMetadata] is the raw blob (use [encryptMetadataBlob] to
     * build a real AES-GCM blob bound to [imageHash]); null = no metadata (a fail-soft
     * decrypt trigger).
     */
    fun seedArchive(
        dataSource: DataSource,
        imageHash: String,
        source: String,
        createdAt: Instant = Instant.now(),
        kominfoReportedAt: Instant? = null,
        kominfoReportId: String? = null,
        cfMatchId: String? = null,
        ncmecReference: String? = null,
        encryptedMetadata: ByteArray? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO csam_detection_archive (
                    id, image_hash, cf_match_id, ncmec_reference, source,
                    encrypted_metadata, kominfo_report_id, kominfo_reported_at,
                    created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, imageHash)
                ps.setString(3, cfMatchId)
                ps.setString(4, ncmecReference)
                ps.setString(5, source)
                ps.setBytes(6, encryptedMetadata)
                ps.setString(7, kominfoReportId)
                ps.setObject(8, kominfoReportedAt?.let { Timestamp.from(it) })
                ps.setObject(9, Timestamp.from(createdAt))
                ps.setObject(10, Timestamp.from(createdAt.plusSeconds(90L * 24 * 3600)))
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Build a real AES-256-GCM metadata blob (uploader id + post id + source + cascade
     *  count) bound to [imageHash], matching the shape #358's service archives. */
    fun encryptMetadataBlob(
        imageHash: String,
        uploaderUserId: UUID,
        affectedPostId: UUID?,
        source: String,
        cascadeCount: Int,
    ): ByteArray {
        val plaintext: JsonObject =
            buildJsonObject {
                put("uploader_user_id", uploaderUserId.toString())
                put("affected_post_id", affectedPostId?.toString())
                put("cf_image_id", "test-image-$uploaderUserId")
                put("source", source)
                put("posts_cascade_tombstoned", cascadeCount)
            }
        return encryptorWithKey().encrypt(
            plaintext = id.nearyou.app.common.AppJson.encodeToString(JsonObject.serializer(), plaintext).toByteArray(Charsets.UTF_8),
            imageHash = imageHash,
        ) ?: error("encrypt returned null with a provisioned key")
    }

    /** Seed a `users` row; returns its id. */
    fun seedUser(
        dataSource: DataSource,
        usernamePrefix: String,
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
                ps.setString(2, "${usernamePrefix}_$short")
                ps.setString(3, "CSAM Log $short")
                ps.setObject(4, LocalDate.of(1990, 1, 1))
                ps.setString(5, "CL${short.take(5).uppercase()}")
                ps.executeUpdate()
            }
        }
        return id
    }

    /** Seed an `image_uploads` ledger row linking [cfImageId] to [uploaderId]. */
    fun seedImageUpload(
        dataSource: DataSource,
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

    /** Seed a `posts` row (optionally carrying [imageId]) tagged with [contentTag] for
     *  prefix cleanup; returns the post id. */
    fun seedPost(
        dataSource: DataSource,
        authorId: UUID,
        imageId: String?,
        contentTag: String,
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
                ps.setString(3, "$contentTag-${id.toString().take(6)}")
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

    /** Seed an `admin_actions_log` row of arbitrary [actionType] for [adminId] — backs the
     *  rate-limit-cap scenarios. [targetId] is the optional target (TEXT column). */
    fun seedAuditRow(
        dataSource: DataSource,
        adminId: UUID,
        actionType: String,
        targetId: String? = null,
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
                ps.setString(3, if (targetId != null) "csam_detection_archive" else null)
                ps.setString(4, targetId)
                ps.setObject(5, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
    }

    // ---------------- assertion helpers ----------------

    /** Snapshot of an archive row's Kominfo columns (for the idempotency / filing assertions). */
    data class KominfoState(
        val reportId: String?,
        val reportedAtMicros: Long?,
    )

    /** Read [archiveId]'s Kominfo state with `kominfo_reported_at` projected as epoch micros
     *  (so a byte-equal pre/post comparison dodges the CI macOS-micros/Linux-nanos round-trip). */
    fun kominfoStateOf(
        dataSource: DataSource,
        archiveId: UUID,
    ): KominfoState? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT kominfo_report_id, (EXTRACT(EPOCH FROM kominfo_reported_at) * 1000000)::bigint AS m " +
                    "FROM csam_detection_archive WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, archiveId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val micros = rs.getLong("m")
                    KominfoState(
                        reportId = rs.getString("kominfo_report_id"),
                        reportedAtMicros = if (rs.wasNull()) null else micros,
                    )
                }
            }
        }

    /** Count `csam_detection_archive` rows for [imageHash] (idempotency: must stay 1). */
    fun archiveCount(
        dataSource: DataSource,
        imageHash: String,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM csam_detection_archive WHERE image_hash = ?").use { ps ->
                ps.setString(1, imageHash)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** `is_banned` + `token_version` of [userId] (takedown effect + idempotency assertions). */
    fun userBanState(
        dataSource: DataSource,
        userId: UUID,
    ): Pair<Boolean, Int> =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT is_banned, token_version FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getBoolean("is_banned") to rs.getInt("token_version")
                }
            }
        }

    /** Count audit rows of [actionType] targeting [targetId] (the archive id or uploader id). */
    fun auditCountForTarget(
        dataSource: DataSource,
        actionType: String,
        targetId: String,
    ): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM admin_actions_log WHERE action_type = ? AND target_id = ?",
            ).use { ps ->
                ps.setString(1, actionType)
                ps.setString(2, targetId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    // ---------------- cleanup ----------------

    /** Remove all rows seeded for one test run, keyed by the collected ids + content prefix. */
    fun cleanup(
        dataSource: DataSource,
        archiveImageHashes: List<String>,
        archiveIds: List<UUID>,
        userIds: List<UUID>,
        contentTag: String,
    ) {
        dataSource.connection.use { conn ->
            // csam_* audit rows are keyed by target_id = archive id (kominfo/decrypt) or
            // uploader id (takedown). Delete both keyings.
            val auditTargets = (archiveIds.map { it.toString() } + userIds.map { it.toString() }).toTypedArray()
            if (auditTargets.isNotEmpty()) {
                conn.prepareStatement(
                    "DELETE FROM admin_actions_log WHERE action_type IN " +
                        "('csam_takedown','csam_kominfo_reported','csam_metadata_decrypted') AND target_id = ANY(?)",
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("text", auditTargets))
                    ps.executeUpdate()
                }
            }
            if (userIds.isNotEmpty()) {
                conn.prepareStatement(
                    "DELETE FROM moderation_queue WHERE target_id IN (SELECT id FROM posts WHERE author_id = ANY(?))",
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", userIds.toTypedArray()))
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("DELETE FROM posts WHERE content LIKE ?").use { ps ->
                ps.setString(1, "$contentTag-%")
                ps.executeUpdate()
            }
            if (archiveImageHashes.isNotEmpty()) {
                conn.prepareStatement("DELETE FROM csam_detection_archive WHERE image_hash = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("text", archiveImageHashes.toTypedArray()))
                    ps.executeUpdate()
                }
            }
            if (archiveIds.isNotEmpty()) {
                conn.prepareStatement("DELETE FROM csam_detection_archive WHERE id = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", archiveIds.toTypedArray()))
                    ps.executeUpdate()
                }
            }
            if (userIds.isNotEmpty()) {
                conn.prepareStatement("DELETE FROM image_uploads WHERE uploader_user_id = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", userIds.toTypedArray()))
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ANY(?)").use { ps ->
                    ps.setArray(1, conn.createArrayOf("uuid", userIds.toTypedArray()))
                    ps.executeUpdate()
                }
            }
        }
    }
}
