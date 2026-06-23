package id.nearyou.app.moderation.csam

import java.sql.Connection
import java.time.Instant
import java.util.Base64
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
 *
 * The `admin-csam-detection-log` viewer/Kominfo/decrypt surface extends this repository
 * in place (design D2 / D7 — no second repository): a keyset list/filter query over
 * `csam_detection_archive`, a pending-count, the idempotent Kominfo update, and a
 * single-row `encrypted_metadata` fetch. These reads of `csam_detection_archive` are
 * admin-module-sanctioned (the `RawFromPostsRule` Detekt rule matches `FROM posts` only,
 * so it is inert on `csam_detection_archive`; `csam_detection_archive` is not a
 * block-protected table, so `BlockExclusionJoinRule` does not fire) — no lint annotation
 * is required. Every query is a parameterized `PreparedStatement`.
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

    // ---------------------------------------------------------------------------
    // admin-csam-detection-log viewer / Kominfo / decrypt extensions (design D2/D7).
    // ---------------------------------------------------------------------------

    /**
     * Run the filtered + keyset-paginated detection-log page query, newest-first over
     * `(created_at DESC, id DESC)` (spec Req "CSAM detection-log viewer"). Composable
     * filters — `source`, Kominfo status (filed vs pending), and a UTC `created_at`
     * `[fromInclusive, toExclusive)` range — are ANDed; every applied value is a
     * positionally-bound parameter (an out-of-enum `source` simply matches zero rows).
     * Fetches `pageSize + 1` rows: the extra (if present) signals an older page and its
     * predecessor becomes the next cursor — no OFFSET, no total-count query (mirrors
     * [id.nearyou.app.admin.actionslog.AdminActionsLogRepository.query]).
     *
     * Projects ONLY scalar columns — `encrypted_metadata` is deliberately NOT selected
     * here (no bulk decrypt; the blob is fetched one row at a time by [fetchEncryptedMetadata]
     * on an explicit, audited decrypt). No image bytes / content URL exist in any column.
     */
    fun query(q: CsamArchiveQuery): CsamArchivePage {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        q.source?.let {
            conditions += "source = ?"
            params += it
        }
        when (q.kominfoStatus) {
            CsamKominfoStatus.PENDING -> conditions += "kominfo_reported_at IS NULL"
            CsamKominfoStatus.FILED -> conditions += "kominfo_reported_at IS NOT NULL"
            null -> {}
        }
        q.fromInclusive?.let {
            conditions += "created_at >= ?"
            params += java.sql.Timestamp.from(it)
        }
        q.toExclusive?.let {
            conditions += "created_at < ?"
            params += java.sql.Timestamp.from(it)
        }
        q.cursor?.let {
            // Keyset "older" predicate aligned with `created_at DESC, id DESC`. `id` is
            // the deterministic tiebreaker, so an insert between page requests never
            // shifts the cursor window (the new newest row sorts ABOVE the cursor).
            conditions += "(created_at, id) < (?, ?)"
            params += java.sql.Timestamp.from(it.createdAt)
            params += it.id
        }

        val sql =
            buildString {
                append(
                    """
                    SELECT id, image_hash, source, cf_match_id, ncmec_reference,
                           kominfo_report_id, kominfo_reported_at,
                           (encrypted_metadata IS NOT NULL) AS has_metadata,
                           created_at, expires_at
                      FROM csam_detection_archive
                    """.trimIndent(),
                )
                if (conditions.isNotEmpty()) {
                    append("\n WHERE ")
                    append(conditions.joinToString(" AND "))
                }
                append("\n ORDER BY created_at DESC, id DESC")
                append("\n LIMIT ?")
            }

        val fetched = mutableListOf<CsamArchiveRow>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                params.forEach { ps.setObject(i++, it) }
                ps.setInt(i, q.pageSize + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) fetched += rs.toArchiveRow()
                }
            }
        }

        val hasOlder = fetched.size > q.pageSize
        val display = if (hasOlder) fetched.take(q.pageSize) else fetched
        val nextCursor =
            if (hasOlder && display.isNotEmpty()) {
                display.last().let { CsamArchiveCursor(it.createdAt, it.id) }
            } else {
                null
            }
        return CsamArchivePage(rows = display, nextCursor = nextCursor)
    }

    /** Count archive rows still pending a Kominfo filing (`kominfo_reported_at IS NULL`)
     *  — the same-business-day SOP banner signal (spec Req "Pending-count summary"). */
    fun pendingKominfoCount(): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM csam_detection_archive WHERE kominfo_reported_at IS NULL",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "COUNT(*) yielded no row" }
                    rs.getLong(1)
                }
            }
        }

    /**
     * Idempotent Kominfo-filing write on a caller-owned [conn] (so it commits atomically
     * with the audit row in the route's one transaction, spec Req "Kominfo report
     * tracking"). The `AND kominfo_reported_at IS NULL` guard makes a re-file a no-op:
     * an already-filed (or unknown) row yields 0 affected rows → the caller rejects with
     * NO mutation and NO audit row, preserving the original `kominfo_reported_at` (the
     * legal timestamp). Returns the affected-row count (1 = filed, 0 = already-filed/unknown).
     */
    fun fileKominfoReport(
        conn: Connection,
        id: UUID,
        kominfoReportId: String,
    ): Int =
        conn.prepareStatement(
            """
            UPDATE csam_detection_archive
               SET kominfo_report_id = ?, kominfo_reported_at = now()
             WHERE id = ? AND kominfo_reported_at IS NULL
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, kominfoReportId)
            ps.setObject(2, id)
            ps.executeUpdate()
        }

    /**
     * Fetch one archive row's `image_hash` (the decrypt AAD) + `encrypted_metadata` blob
     * by id, for the on-demand audited decrypt path (spec Req "Audit-logged metadata
     * decrypt"). Returns `null` when the id is unknown; the row is present with a `null`
     * [CsamEncryptedMetadata.encryptedMetadata] when the blob itself is NULL (the
     * fail-soft "metadata unavailable" case). Never returns image bytes — the blob holds
     * only the JSON metadata #358 archived.
     */
    fun fetchEncryptedMetadata(id: UUID): CsamEncryptedMetadata? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT image_hash, encrypted_metadata FROM csam_detection_archive WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    CsamEncryptedMetadata(
                        imageHash = rs.getString("image_hash"),
                        encryptedMetadata = rs.getBytes("encrypted_metadata"),
                    )
                }
            }
        }

    private fun java.sql.ResultSet.toArchiveRow(): CsamArchiveRow =
        CsamArchiveRow(
            id = getObject("id", UUID::class.java),
            imageHash = getString("image_hash"),
            source = getString("source"),
            cfMatchId = getString("cf_match_id"),
            ncmecReference = getString("ncmec_reference"),
            kominfoReportId = getString("kominfo_report_id"),
            kominfoReportedAt = getTimestamp("kominfo_reported_at")?.toInstant(),
            hasMetadata = getBoolean("has_metadata"),
            createdAt = getTimestamp("created_at").toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant(),
        )

    companion object {
        /** Fixed page size for the detection-log viewer (implementation constant, not a client param). */
        const val PAGE_SIZE: Int = 50
    }
}

/** Kominfo-filing status filter for the detection-log viewer. */
enum class CsamKominfoStatus { PENDING, FILED }

/**
 * The active filter set + page size + optional keyset cursor for one detection-log
 * page query. A blank `source` / null status reaches here already dropped by the route.
 */
data class CsamArchiveQuery(
    val source: String? = null,
    val kominfoStatus: CsamKominfoStatus? = null,
    val fromInclusive: Instant? = null,
    val toExclusive: Instant? = null,
    val cursor: CsamArchiveCursor? = null,
    val pageSize: Int = CsamRepository.PAGE_SIZE,
)

/**
 * A single rendered `csam_detection_archive` row (scalar columns only — never
 * `encrypted_metadata` and never any image bytes / content URL).
 */
data class CsamArchiveRow(
    val id: UUID,
    val imageHash: String,
    /** One of the `source` CHECK values (`admin_manual` | `cf_worker`). */
    val source: String,
    val cfMatchId: String?,
    val ncmecReference: String?,
    val kominfoReportId: String?,
    /** Null = Kominfo filing still pending. */
    val kominfoReportedAt: Instant?,
    /** Whether `encrypted_metadata` is present (drives the decrypt affordance; the blob itself is not projected here). */
    val hasMetadata: Boolean,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** One page of detection-log results plus the cursor for the next-older page (null = last page). */
data class CsamArchivePage(
    val rows: List<CsamArchiveRow>,
    val nextCursor: CsamArchiveCursor?,
)

/** The `image_hash` (decrypt AAD) + raw `encrypted_metadata` blob for one row (blob null = unavailable). */
data class CsamEncryptedMetadata(
    val imageHash: String,
    val encryptedMetadata: ByteArray?,
)

/**
 * Opaque keyset cursor over `(created_at, id)` for the `DESC` ordering. Encoded as
 * `base64url("<createdAtEpochMicros>|<id>")` (mirrors
 * [id.nearyou.app.admin.actionslog.ActionLogCursor]). Opaque but NOT a security
 * boundary — it only encodes a sort position over data the admin is already authorized
 * to read. A malformed token decodes to `null` (→ first page), never throws.
 */
data class CsamArchiveCursor(
    val createdAt: Instant,
    val id: UUID,
) {
    fun encode(): String {
        val raw = "${createdAt.toEpochMicros()}|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(token: String?): CsamArchiveCursor? {
            if (token.isNullOrBlank()) return null
            return try {
                val raw = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
                val sep = raw.indexOf('|')
                if (sep <= 0 || sep == raw.length - 1) return null
                val micros = raw.substring(0, sep).toLong()
                val id = UUID.fromString(raw.substring(sep + 1))
                CsamArchiveCursor(instantFromEpochMicros(micros), id)
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
