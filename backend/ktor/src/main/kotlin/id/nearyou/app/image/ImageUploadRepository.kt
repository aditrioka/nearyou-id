package id.nearyou.app.image

import id.nearyou.app.infra.cloudvision.SafeSearchVerdict
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** A row in the `image_uploads` ownership ledger (the fields the attach path needs). */
data class ImageUploadRow(
    val cfImageId: String,
    val uploaderUserId: UUID,
    val status: String,
)

/**
 * JDBC access to the `image_uploads` ownership ledger. The standalone [insertUploaded] owns
 * its own connection (a single INSERT on the upload path); [findForAttach] / [markAttached]
 * take a caller [Connection] so the attach runs inside the post-creation transaction.
 * `image_uploads` is a ledger, not shadow-ban / block-protected user content, so raw reads
 * need no `visible_*` view or block-join.
 */
interface ImageUploadRepository {
    /** Insert a freshly-stored image as `status = 'uploaded'` (own connection). */
    fun insertUploaded(
        cfImageId: String,
        uploaderUserId: UUID,
        verdict: SafeSearchVerdict,
    )

    /** Fetch the ledger row for attach authorization (ownership + status), or null if absent. */
    fun findForAttach(
        conn: Connection,
        cfImageId: String,
    ): ImageUploadRow?

    /**
     * Conditional flip `uploaded → attached`: `UPDATE … WHERE cf_image_id = ? AND status = 'uploaded'`.
     * Returns true iff exactly one row was flipped (the winner of a concurrent attach race; a
     * loser's UPDATE affects zero rows — the post-likes "zero rows affected" precedent).
     */
    fun markAttached(
        conn: Connection,
        cfImageId: String,
    ): Boolean
}

/** Production JDBC binding for [ImageUploadRepository]. */
class JdbcImageUploadRepository(
    private val dataSource: DataSource,
) : ImageUploadRepository {
    override fun insertUploaded(
        cfImageId: String,
        uploaderUserId: UUID,
        verdict: SafeSearchVerdict,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(INSERT_SQL).use { ps ->
                ps.setString(1, cfImageId)
                ps.setObject(2, uploaderUserId)
                ps.setString(3, verdict.adult.name)
                ps.setString(4, verdict.violence.name)
                ps.setString(5, verdict.racy.name)
                ps.executeUpdate()
            }
        }
    }

    override fun findForAttach(
        conn: Connection,
        cfImageId: String,
    ): ImageUploadRow? {
        conn.prepareStatement(SELECT_SQL).use { ps ->
            ps.setString(1, cfImageId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return ImageUploadRow(
                    cfImageId = rs.getString("cf_image_id"),
                    uploaderUserId = rs.getObject("uploader_user_id", UUID::class.java),
                    status = rs.getString("status"),
                )
            }
        }
    }

    override fun markAttached(
        conn: Connection,
        cfImageId: String,
    ): Boolean {
        conn.prepareStatement(UPDATE_ATTACH_SQL).use { ps ->
            ps.setString(1, cfImageId)
            return ps.executeUpdate() == 1
        }
    }

    companion object {
        const val INSERT_SQL: String =
            "INSERT INTO image_uploads " +
                "(cf_image_id, uploader_user_id, safe_search_adult, safe_search_violence, safe_search_racy, status) " +
                "VALUES (?, ?, ?, ?, ?, 'uploaded')"

        const val SELECT_SQL: String =
            "SELECT cf_image_id, uploader_user_id, status FROM image_uploads WHERE cf_image_id = ?"

        const val UPDATE_ATTACH_SQL: String =
            "UPDATE image_uploads SET status = 'attached' WHERE cf_image_id = ? AND status = 'uploaded'"
    }
}
