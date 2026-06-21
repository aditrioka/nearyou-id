package id.nearyou.app.moderation.csam

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Data layer for the `csam-detection` capability: the takedown writes (resolve →
 * tombstone → permanent-ban → cascade), the `csam_detection_archive` upsert, and the
 * retention purge.
 *
 * Every takedown method takes the caller's [Connection] so it enlists in the single
 * service-level transaction (docs/11 §3.2). The takedown is implemented entirely as
 * `UPDATE … RETURNING` writes plus one `FROM image_uploads` read: there is NO
 * `FROM posts`/`FROM users` read, so neither `RawFromPostsRule` (matches `FROM posts`
 * reads) nor `BlockExclusionJoinRule` (protects `FROM`/`JOIN` on posts/users) is
 * triggered — no lint allowlist annotation is required. (`image_uploads` is not a
 * block-protected table.)
 */
class CsamRepository(
    private val dataSource: DataSource,
) {
    /**
     * Resolve the offending uploader from the matched Cloudflare image via the
     * `image_uploads` ledger (V26). Returns `null` when the image has no ledger row
     * (already cleaned / stale) — the ledger-miss path archives the match anyway.
     */
    fun resolveUploaderId(
        conn: Connection,
        cfImageId: String,
    ): UUID? =
        conn.prepareStatement(
            "SELECT uploader_user_id FROM image_uploads WHERE cf_image_id = ?",
        ).use { ps ->
            ps.setString(1, cfImageId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
        }

    /**
     * Tombstone the post carrying the matched image (`posts.image_id = cfImageId`),
     * returning its id. `UPDATE … RETURNING` — a write, no `FROM posts` read. Returns
     * `null` when the image was uploaded but never attached to a post (still bans the
     * uploader). Idempotent: a re-trigger finds `deleted_at` already set → no row.
     */
    fun tombstoneAffectedPost(
        conn: Connection,
        cfImageId: String,
    ): UUID? =
        conn.prepareStatement(
            "UPDATE posts SET deleted_at = now() WHERE image_id = ? AND deleted_at IS NULL RETURNING id",
        ).use { ps ->
            ps.setString(1, cfImageId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
        }

    /**
     * Permanently ban the uploader and bump `token_version` (kick live sessions). The
     * `AND is_banned = FALSE` guard makes a re-trigger idempotent — an already-banned
     * uploader yields no row and no second `token_version` bump. Returns `true` iff
     * this call newly banned the user.
     */
    fun banUploaderPermanently(
        conn: Connection,
        uploaderId: UUID,
    ): Boolean =
        conn.prepareStatement(
            """
            UPDATE users
               SET is_banned = TRUE, suspended_until = NULL, token_version = token_version + 1
             WHERE id = ? AND is_banned = FALSE
            RETURNING id
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, uploaderId)
            ps.executeQuery().use { rs -> rs.next() }
        }

    /**
     * Cascade-tombstone every still-visible post by the uploader (abundance of caution
     * — the affected post is already tombstoned by [tombstoneAffectedPost]). A write;
     * returns the number of posts newly tombstoned.
     */
    fun cascadeTombstoneUploaderPosts(
        conn: Connection,
        uploaderId: UUID,
    ): Int =
        conn.prepareStatement(
            "UPDATE posts SET deleted_at = now() WHERE author_id = ? AND deleted_at IS NULL",
        ).use { ps ->
            ps.setObject(1, uploaderId)
            ps.executeUpdate()
        }

    /**
     * Upsert the legal-preservation archive row. `ON CONFLICT (image_hash) DO UPDATE`
     * ENRICHES previously-NULL columns (`cf_match_id`, `ncmec_reference`,
     * `encrypted_metadata`) via COALESCE WITHOUT resetting `source`/`created_at`/
     * `expires_at` — so a later CF-Worker re-detection of an admin-manual row fills the
     * `cf_match_id` while preserving the original `source`. `expires_at` is the column
     * DEFAULT (`created_at + 90 days`), never set here.
     */
    fun archive(
        conn: Connection,
        imageHash: String,
        cfMatchId: String?,
        ncmecReference: String?,
        source: String,
        encryptedMetadata: ByteArray?,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO csam_detection_archive (image_hash, cf_match_id, ncmec_reference, source, encrypted_metadata)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (image_hash) DO UPDATE SET
                cf_match_id        = COALESCE(csam_detection_archive.cf_match_id, EXCLUDED.cf_match_id),
                ncmec_reference    = COALESCE(csam_detection_archive.ncmec_reference, EXCLUDED.ncmec_reference),
                encrypted_metadata = COALESCE(csam_detection_archive.encrypted_metadata, EXCLUDED.encrypted_metadata)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, imageHash)
            ps.setString(2, cfMatchId)
            ps.setString(3, ncmecReference)
            ps.setString(4, source)
            ps.setBytes(5, encryptedMetadata)
            ps.executeUpdate()
        }
    }

    /**
     * Daily retention purge (`/internal/csam-archive-purge`): delete rows past their
     * 90-day deadline ONLY once the Kominfo report is filed. A row past `expires_at`
     * with `kominfo_reported_at IS NULL` is preserved (the unfulfilled legal obligation
     * extends preservation). `now()` in a DELETE WHERE is allowed (only a partial-INDEX
     * WHERE forbids it). Opens its own connection (standalone worker call). Returns the
     * number of rows purged.
     */
    fun purgeExpiredReported(): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM csam_detection_archive WHERE expires_at < now() AND kominfo_reported_at IS NOT NULL",
            ).use { ps -> ps.executeUpdate() }
        }
}
