package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.infra.repo.JdbcSinglePostRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.sql.Date
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            // Frugal pool + autoClose — CI connection-budget rule (task 4.1).
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class JdbcSinglePostRepositoryTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repo = JdbcSinglePostRepository(dataSource)

    fun seedUser(
        displayName: String = "Repo Tester",
        shadowBanned: Boolean = false,
    ): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        val username = "rs_$short"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, is_shadow_banned)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "p${short.take(7)}")
                ps.setBoolean(6, shadowBanned)
                ps.executeUpdate()
            }
        }
        return id to username
    }

    fun seedPost(
        authorId: UUID,
        content: String = "row content",
        autoHidden: Boolean = false,
        softDeleted: Boolean = false,
        lng: Double = 106.8,
        lat: Double = -6.2,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden, deleted_at)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.setString(3, content)
                ps.setDouble(4, lng)
                ps.setDouble(5, lat)
                ps.setDouble(6, lng)
                ps.setDouble(7, lat)
                ps.setBoolean(8, autoHidden)
                if (softDeleted) ps.setTimestamp(9, Timestamp.from(Instant.now())) else ps.setNull(9, Types.TIMESTAMP)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedLike(
        postId: UUID,
        userId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)").use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun seedReply(
        postId: UUID,
        authorId: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO post_replies (id, post_id, author_id, content) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, postId)
                ps.setObject(3, authorId)
                ps.setString(4, "r-${UUID.randomUUID().toString().take(6)}")
                ps.executeUpdate()
            }
        }
    }

    fun seedEdit(
        postId: UUID,
        editedBy: UUID,
        editedAt: Instant,
        snapshot: String = "old content",
        lng: Double = 106.8,
        lat: Double = -6.2,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO post_edits (id, post_id, edited_at, content_snapshot, location_snapshot, edited_by)
                VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, postId)
                ps.setTimestamp(3, Timestamp.from(editedAt))
                ps.setString(4, snapshot)
                ps.setDouble(5, lng)
                ps.setDouble(6, lat)
                ps.setObject(7, editedBy)
                ps.executeUpdate()
            }
        }
    }

    fun insertBlock(
        blocker: UUID,
        blocked: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, blocker)
                ps.setObject(2, blocked)
                ps.executeUpdate()
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM post_edits WHERE edited_by = '$it'")
                    st.executeUpdate("DELETE FROM post_likes WHERE user_id = '$it'")
                    st.executeUpdate("DELETE FROM post_replies WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    "4.1 findById — visible post returns the no-PII projection" {
        val (author, authorUsername) = seedUser(displayName = "Visible Author")
        val (viewer, _) = seedUser()
        try {
            val postId = seedPost(author, content = "hello")
            val row = repo.findById(viewerId = viewer, postId = postId)
            row.shouldNotBeNull()
            row.id shouldBe postId
            row.authorUsername shouldBe authorUsername
            row.authorDisplayName shouldBe "Visible Author"
            row.content shouldBe "hello"
            row.cityName!!.isNotEmpty() shouldBe true // Jakarta coords → non-empty
            row.likedByViewer shouldBe false
            row.replyCount shouldBe 0
        } finally {
            cleanup(author, viewer)
        }
    }

    "4.1 findById — unknown / soft-deleted / other-author-shadow-banned / auto-hidden all return null" {
        val (viewer, _) = seedUser()
        val (author, _) = seedUser()
        val (shadowAuthor, _) = seedUser(shadowBanned = true)
        try {
            val softDeleted = seedPost(author, softDeleted = true)
            val autoHidden = seedPost(author, autoHidden = true)
            val shadowed = seedPost(shadowAuthor)
            repo.findById(viewer, UUID.randomUUID()).shouldBeNull()
            repo.findById(viewer, softDeleted).shouldBeNull()
            repo.findById(viewer, autoHidden).shouldBeNull()
            repo.findById(viewer, shadowed).shouldBeNull()
        } finally {
            cleanup(viewer, author, shadowAuthor)
        }
    }

    "4.1 findById — bidirectional block returns null in both directions" {
        val (viewer, _) = seedUser()
        val (blockedByViewer, _) = seedUser()
        val (blockerOfViewer, _) = seedUser()
        insertBlock(viewer, blockedByViewer) // viewer blocked author
        insertBlock(blockerOfViewer, viewer) // author blocked viewer
        try {
            val pA = seedPost(blockedByViewer)
            val pB = seedPost(blockerOfViewer)
            repo.findById(viewer, pA).shouldBeNull()
            repo.findById(viewer, pB).shouldBeNull()
        } finally {
            cleanup(viewer, blockedByViewer, blockerOfViewer)
        }
    }

    "4.1 findById — shadow-banned author reads own live post (own-content arm); own soft-deleted is null" {
        val (shadowAuthor, _) = seedUser(shadowBanned = true)
        try {
            val ownLive = seedPost(shadowAuthor, content = "mine")
            val ownDeleted = seedPost(shadowAuthor, softDeleted = true)
            repo.findById(shadowAuthor, ownLive).shouldNotBeNull().content shouldBe "mine"
            repo.findById(shadowAuthor, ownDeleted).shouldBeNull()
        } finally {
            cleanup(shadowAuthor)
        }
    }

    "4.2 findById — likedByViewer reflects the viewer's own like" {
        val (author, _) = seedUser()
        val (viewer, _) = seedUser()
        try {
            val liked = seedPost(author)
            val notLiked = seedPost(author)
            seedLike(liked, viewer)
            repo.findById(viewer, liked).shouldNotBeNull().likedByViewer shouldBe true
            repo.findById(viewer, notLiked).shouldNotBeNull().likedByViewer shouldBe false
        } finally {
            cleanup(author, viewer)
        }
    }

    "4.2 findById — replyCount is NOT viewer-block-filtered (a blocked replier still counts)" {
        val (author, _) = seedUser()
        val (viewer, _) = seedUser()
        val (replier, _) = seedUser()
        // Viewer has blocked the replier — but the reply still contributes to the public counter
        // (the documented post-replies-v8 Decision 5 tradeoff; replier is NOT shadow-banned so it
        // survives the counter's visible_users shadow-ban filter).
        insertBlock(viewer, replier)
        try {
            val postId = seedPost(author)
            seedReply(postId, replier)
            repo.findById(viewer, postId).shouldNotBeNull().replyCount shouldBe 1
        } finally {
            cleanup(author, viewer, replier)
        }
    }

    "1.3 findById — isAuthor is true for the viewer's own post, false for another viewer" {
        val (author, _) = seedUser()
        val (viewer, _) = seedUser()
        try {
            val post = seedPost(author)
            repo.findById(author, post).shouldNotBeNull().isAuthor shouldBe true
            repo.findById(viewer, post).shouldNotBeNull().isAuthor shouldBe false
        } finally {
            cleanup(author, viewer)
        }
    }

    "2.1 findById — editedAt is MAX(post_edits.edited_at) for an edited post" {
        val (author, _) = seedUser()
        val (viewer, _) = seedUser()
        try {
            val postId = seedPost(author)
            val older = Instant.parse("2026-01-01T00:00:00Z")
            val newer = Instant.parse("2026-01-01T00:05:00Z")
            seedEdit(postId, author, older)
            seedEdit(postId, author, newer)
            val row = repo.findById(viewer, postId).shouldNotBeNull()
            row.editedAt.shouldNotBeNull()
            row.editedAt!!.toEpochMilli() shouldBe newer.toEpochMilli()
        } finally {
            cleanup(author, viewer)
        }
    }

    "2.2 findById — editedAt is null for a never-edited post" {
        val (author, _) = seedUser()
        val (viewer, _) = seedUser()
        try {
            val postId = seedPost(author)
            repo.findById(viewer, postId).shouldNotBeNull().editedAt.shouldBeNull()
        } finally {
            cleanup(author, viewer)
        }
    }
})
