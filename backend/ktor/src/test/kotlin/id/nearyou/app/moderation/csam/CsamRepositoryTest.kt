package id.nearyou.app.moderation.csam

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * DB coverage for [CsamRepository] (`csam-detection` data layer) — the individual
 * write primitives + the archive upsert + the retention purge, in isolation from the
 * service transaction. Security-critical (child-safety/legal): every assertion is
 * exact (specific row counts, exact column values, idempotency return flags).
 *
 * Scenarios:
 *  - 7.1  archive plaintext essentials + UNIQUE(image_hash) collapse + partial
 *         UNIQUE(cf_match_id) (multiple NULL coexist; dup non-NULL rejected).
 *  - 7.3  ON CONFLICT enrich — admin_manual then cf_worker fills cf_match_id while
 *         source/created_at/expires_at stay pinned, one row.
 *  - 7.2  no FK to users — deleting the uploader leaves the archive row intact.
 *  - 7.11 purge — expired+reported purged; expired+unreported preserved;
 *         within-window preserved.
 *  - write methods — tombstoneAffectedPost (returns id + deleted_at set),
 *         banUploaderPermanently (true→false idempotent, token_version bumped once),
 *         cascadeTombstoneUploaderPosts (count).
 */
@Tags("database")
class CsamRepositoryTest : StringSpec({

    // This pool has afterSpec cleanup, so it MUST NOT be autoClose'd — close it in
    // afterSpec AFTER the cleanup runs (project memory: autoClose closes the pool
    // before the cleanup, silently no-opping it → row pollution).
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
                username = System.getenv("DB_USER") ?: "postgres"
                password = System.getenv("DB_PASSWORD") ?: "postgres"
                maximumPoolSize = 2
                initializationFailTimeout = -1
            },
        )
    val repo = CsamRepository(dataSource)

    // Distinct image_hash / cf_match_id prefixes per spec run so parallel/leftover rows
    // don't collide on the UNIQUE constraints; cleaned up by prefix in afterSpec.
    val runTag = "csamrepo_${UUID.randomUUID().toString().take(8)}"
    val seededUserIds = mutableListOf<UUID>()

    fun <T> tx(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val r = block(conn)
                conn.commit()
                r
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

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
                ps.setString(2, "cr_$short")
                ps.setString(3, "CSAM Repo $short")
                ps.setObject(4, LocalDate.of(1990, 1, 1))
                ps.setString(5, "CR${short.take(5).uppercase()}")
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
                // Deep-Indian-Ocean coords — outside every admin_regions polygon (city trigger inert).
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

    // ---- archive-row query helpers ----

    data class ArchiveRow(
        val source: String,
        val cfMatchId: String?,
        val ncmecReference: String?,
        val hasEncrypted: Boolean,
        val createdAtMicros: Long,
        val expiresAtMicros: Long,
        val kominfoReportedAt: java.time.Instant?,
    )

    fun archiveRow(imageHash: String): ArchiveRow? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT source, cf_match_id, ncmec_reference,
                       (encrypted_metadata IS NOT NULL) AS has_enc,
                       EXTRACT(EPOCH FROM created_at) AS created_epoch,
                       EXTRACT(EPOCH FROM expires_at) AS expires_epoch,
                       kominfo_reported_at
                  FROM csam_detection_archive WHERE image_hash = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, imageHash)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    ArchiveRow(
                        source = rs.getString("source"),
                        cfMatchId = rs.getString("cf_match_id"),
                        ncmecReference = rs.getString("ncmec_reference"),
                        hasEncrypted = rs.getBoolean("has_enc"),
                        createdAtMicros = (rs.getDouble("created_epoch") * 1_000_000).toLong(),
                        expiresAtMicros = (rs.getDouble("expires_epoch") * 1_000_000).toLong(),
                        kominfoReportedAt = rs.getTimestamp("kominfo_reported_at")?.toInstant(),
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

    /** Force a row's expires_at into the past (not GENERATED → an UPDATE works). */
    fun expireRow(
        imageHash: String,
        reportedAt: java.time.Instant?,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE csam_detection_archive SET expires_at = now() - INTERVAL '1 day', kominfo_reported_at = ? WHERE image_hash = ?",
            ).use { ps ->
                if (reportedAt != null) {
                    ps.setTimestamp(1, java.sql.Timestamp.from(reportedAt))
                } else {
                    ps.setNull(1, java.sql.Types.TIMESTAMP)
                }
                ps.setString(2, imageHash)
                ps.executeUpdate()
            }
        }
    }

    afterSpec {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM csam_detection_archive WHERE image_hash LIKE ?").use { ps ->
                ps.setString(1, "$runTag%")
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM posts WHERE content LIKE ?").use { ps ->
                ps.setString(1, "$runTag-post-%")
                ps.executeUpdate()
            }
            // image_uploads cascade-delete with users; delete explicitly first to be safe.
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

    "7.1 archive writes the plaintext Kominfo essentials" {
        val hash = "$runTag-hash-essentials"
        tx { conn ->
            repo.archive(
                conn = conn,
                imageHash = hash,
                cfMatchId = "$runTag-cf-1",
                ncmecReference = "NCMEC-REF-1",
                source = "admin_manual",
                encryptedMetadata = null,
            )
        }
        val row = archiveRow(hash)
        row.shouldNotBeNull()
        row.source shouldBe "admin_manual"
        row.cfMatchId shouldBe "$runTag-cf-1"
        row.ncmecReference shouldBe "NCMEC-REF-1"
        row.hasEncrypted shouldBe false
        row.kominfoReportedAt.shouldBeNull()
        // expires_at == created_at + 90 days (both DEFAULTs evaluate the same tx now()).
        (row.expiresAtMicros - row.createdAtMicros) shouldBe 90L * 24 * 3600 * 1_000_000
    }

    "7.1 UNIQUE(image_hash) collapses a duplicate archive into one enriched row" {
        val hash = "$runTag-hash-dup"
        tx { conn -> repo.archive(conn, hash, null, "NCMEC-A", "admin_manual", null) }
        tx { conn -> repo.archive(conn, hash, "$runTag-cf-dup", "NCMEC-A", "cf_worker", null) }
        archiveCount(hash) shouldBe 1
    }

    "7.1 partial UNIQUE(cf_match_id) — multiple NULLs coexist, duplicate non-NULL rejected" {
        // Two rows with NULL cf_match_id (different image_hash) must both insert.
        val hashA = "$runTag-hash-nullcfA"
        val hashB = "$runTag-hash-nullcfB"
        tx { conn -> repo.archive(conn, hashA, null, null, "admin_manual", null) }
        tx { conn -> repo.archive(conn, hashB, null, null, "admin_manual", null) }
        archiveCount(hashA) shouldBe 1
        archiveCount(hashB) shouldBe 1

        // A duplicate NON-NULL cf_match_id on a different image_hash must violate the
        // partial UNIQUE index (a Cloudflare match id is globally unique).
        val sharedCf = "$runTag-cf-shared"
        tx { conn -> repo.archive(conn, "$runTag-hash-cf1", sharedCf, null, "cf_worker", null) }
        val ex =
            runCatching {
                tx { conn -> repo.archive(conn, "$runTag-hash-cf2", sharedCf, null, "cf_worker", null) }
            }.exceptionOrNull()
        (ex is PSQLException) shouldBe true
    }

    "7.3 ON CONFLICT enrich — cf_worker re-detection of an admin_manual row fills cf_match_id, preserves source/created_at/expires_at" {
        val hash = "$runTag-hash-enrich"
        // 1) admin_manual row, no cf_match_id yet.
        tx { conn -> repo.archive(conn, hash, null, "NCMEC-ENRICH", "admin_manual", null) }
        val before = archiveRow(hash)
        before.shouldNotBeNull()
        before.cfMatchId.shouldBeNull()

        // 2) later cf_worker detection of the SAME image_hash with a cf_match_id.
        tx { conn -> repo.archive(conn, hash, "$runTag-cf-enrich", "NCMEC-ENRICH", "cf_worker", null) }

        val after = archiveRow(hash)
        after.shouldNotBeNull()
        archiveCount(hash) shouldBe 1
        after.cfMatchId shouldBe "$runTag-cf-enrich" // previously-NULL column enriched
        after.source shouldBe "admin_manual" // source NOT reset by the enrich
        after.createdAtMicros shouldBe before.createdAtMicros // created_at untouched
        after.expiresAtMicros shouldBe before.expiresAtMicros // expires_at untouched
    }

    "7.3 ON CONFLICT enrich — COALESCE does NOT overwrite an already-set cf_match_id" {
        val hash = "$runTag-hash-noreset"
        tx { conn -> repo.archive(conn, hash, "$runTag-cf-first", null, "cf_worker", null) }
        // A second detection with a different cf_match_id must NOT clobber the first
        // (COALESCE keeps the existing non-NULL value).
        tx { conn -> repo.archive(conn, hash, "$runTag-cf-second", null, "cf_worker", null) }
        val row = archiveRow(hash)
        row.shouldNotBeNull()
        row.cfMatchId shouldBe "$runTag-cf-first"
    }

    "7.2 archive row has NO FK to users — deleting the uploader leaves the archive intact" {
        val uploader = seedUser()
        val hash = "$runTag-hash-nofk"
        tx { conn -> repo.archive(conn, hash, "$runTag-cf-nofk", "NCMEC-NOFK", "cf_worker", null) }
        archiveCount(hash) shouldBe 1

        // Hard-delete the uploader (the image_uploads ledger row is ON DELETE CASCADE,
        // but the archive row carries no FK, so it survives).
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, uploader)
                ps.executeUpdate()
            }
        }
        seededUserIds.remove(uploader)
        archiveCount(hash) shouldBe 1 // preservation survives account deletion
    }

    "7.11 purge — expired+reported deleted, expired+unreported preserved, within-window preserved" {
        val expiredReported = "$runTag-hash-purge-er"
        val expiredUnreported = "$runTag-hash-purge-eu"
        val withinWindow = "$runTag-hash-purge-win"

        tx { conn -> repo.archive(conn, expiredReported, null, null, "admin_manual", null) }
        tx { conn -> repo.archive(conn, expiredUnreported, null, null, "admin_manual", null) }
        tx { conn -> repo.archive(conn, withinWindow, null, null, "admin_manual", null) }

        // Push two rows past their deadline; only the reported one is purge-eligible.
        expireRow(expiredReported, java.time.Instant.now().minusSeconds(3600))
        expireRow(expiredUnreported, null) // kominfo_reported_at NULL → unfulfilled obligation, preserved
        // withinWindow keeps its DEFAULT now()+90d.

        val purged = repo.purgeExpiredReported()
        purged shouldBe 1

        archiveCount(expiredReported) shouldBe 0 // purged
        archiveCount(expiredUnreported) shouldBe 1 // preserved (report unfiled)
        archiveCount(withinWindow) shouldBe 1 // preserved (still within window)
    }

    "tombstoneAffectedPost returns the post id and sets deleted_at; a second call finds none (idempotent)" {
        val uploader = seedUser()
        val cfImageId = "$runTag-img-tomb"
        val postId = seedPost(uploader, imageId = cfImageId)

        val first = tx { conn -> repo.tombstoneAffectedPost(conn, cfImageId) }
        first shouldBe postId
        postDeletedAt(postId).shouldNotBeNull()

        // Re-trigger: deleted_at already set → no row returned.
        val second = tx { conn -> repo.tombstoneAffectedPost(conn, cfImageId) }
        second.shouldBeNull()
    }

    "tombstoneAffectedPost returns null when the image was uploaded but never attached to a post" {
        val cfImageId = "$runTag-img-noattach"
        val result = tx { conn -> repo.tombstoneAffectedPost(conn, cfImageId) }
        result.shouldBeNull()
    }

    "banUploaderPermanently bans + bumps token_version once; a second call is a no-op (idempotent)" {
        val uploader = seedUser()
        val (bannedBefore, versionBefore) = userBanState(uploader)
        bannedBefore shouldBe false

        val first = tx { conn -> repo.banUploaderPermanently(conn, uploader) }
        first shouldBe true
        val (bannedAfter, versionAfter) = userBanState(uploader)
        bannedAfter shouldBe true
        versionAfter shouldBe versionBefore + 1 // exactly one bump

        // Already banned → the `AND is_banned = FALSE` guard yields no row, no 2nd bump.
        val second = tx { conn -> repo.banUploaderPermanently(conn, uploader) }
        second shouldBe false
        val (_, versionFinal) = userBanState(uploader)
        versionFinal shouldBe versionBefore + 1
    }

    "cascadeTombstoneUploaderPosts tombstones all still-visible posts and returns the count" {
        val uploader = seedUser()
        val p1 = seedPost(uploader, imageId = null)
        val p2 = seedPost(uploader, imageId = null)
        val p3 = seedPost(uploader, imageId = null)
        // Pre-tombstone one so the cascade only counts the still-visible two.
        tx { conn ->
            conn.prepareStatement("UPDATE posts SET deleted_at = now() WHERE id = ?").use { ps ->
                ps.setObject(1, p3)
                ps.executeUpdate()
            }
        }

        val count = tx { conn -> repo.cascadeTombstoneUploaderPosts(conn, uploader) }
        count shouldBe 2
        postDeletedAt(p1).shouldNotBeNull()
        postDeletedAt(p2).shouldNotBeNull()

        // A second cascade finds nothing left to tombstone.
        val again = tx { conn -> repo.cascadeTombstoneUploaderPosts(conn, uploader) }
        again shouldBe 0
    }

    "resolveUploaderId returns the uploader from the image_uploads ledger, null on a ledger miss" {
        val uploader = seedUser()
        val cfImageId = "$runTag-img-resolve"
        seedImageUpload(cfImageId, uploader)

        tx { conn -> repo.resolveUploaderId(conn, cfImageId) } shouldBe uploader
        tx { conn -> repo.resolveUploaderId(conn, "$runTag-img-absent") }.shouldBeNull()
    }
})
