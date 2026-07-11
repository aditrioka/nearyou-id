package id.nearyou.app.image

import id.nearyou.app.infra.cloudflareimages.CloudflareImageStoreException
import id.nearyou.app.infra.cloudflareimages.ImageStore
import id.nearyou.app.infra.cloudflareimages.StoredImage
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeding helpers for the `orphan-image-cleanup` DB-tagged tests. Rows are
 * keyed by unique random `cf_image_id`s (`oic_<uuid8>`); parent `users` rows
 * come from [id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedUser]
 * and its `cleanup` cascades `image_uploads` via the V26 `ON DELETE CASCADE`.
 * Seed timestamps sit comfortably inside/outside the 24h window (1h vs 25h) —
 * never an exact fencepost (MEMORY: DB timestamp micros truncation).
 */
object OrphanImageCleanupTestSupport {
    fun seedImageUpload(
        dataSource: DataSource,
        uploaderUserId: UUID,
        createdAt: Instant,
        status: String = "uploaded",
    ): String {
        val cfImageId = "oic_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO image_uploads (cf_image_id, uploader_user_id, created_at, status) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, cfImageId)
                ps.setObject(2, uploaderUserId)
                ps.setTimestamp(3, Timestamp.from(createdAt))
                ps.setString(4, status)
                ps.executeUpdate()
            }
        }
        return cfImageId
    }

    fun imageUploadExists(
        dataSource: DataSource,
        cfImageId: String,
    ): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM image_uploads WHERE cf_image_id = ?").use { ps ->
                ps.setString(1, cfImageId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }

    fun statusOf(
        dataSource: DataSource,
        cfImageId: String,
    ): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status FROM image_uploads WHERE cf_image_id = ?").use { ps ->
                ps.setString(1, cfImageId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
}

/**
 * Configured [ImageStore] fake: records deleted ids; throws
 * [CloudflareImageStoreException] for ids in [failFor] (the CF-outage path).
 */
class RecordingImageStore(
    private val failFor: Set<String> = emptySet(),
) : ImageStore {
    val deleted = mutableListOf<String>()

    override fun isConfigured(): Boolean = true

    override suspend fun upload(
        bytes: ByteArray,
        contentType: String,
        fileName: String,
    ): StoredImage = error("not used")

    override suspend fun delete(imageId: String) {
        if (imageId in failFor) throw CloudflareImageStoreException("simulated Cloudflare outage")
        deleted += imageId
    }
}
