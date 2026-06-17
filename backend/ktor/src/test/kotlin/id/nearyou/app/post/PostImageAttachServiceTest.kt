package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.image.ImageUploadRepository
import id.nearyou.app.image.JdbcImageUploadRepository
import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood
import id.nearyou.app.infra.cloudvision.SafeSearchVerdict
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.NewPostRow
import id.nearyou.app.infra.repo.PostRepository
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.TextModerator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.Date
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

private fun attachHikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class PostImageAttachServiceTest : StringSpec({

    val dataSource = attachHikari()
    val posts = JdbcPostRepository()
    val imageRepo: ImageUploadRepository = JdbcImageUploadRepository(dataSource)
    val jitterSecret = Base64.getDecoder().decode("0".repeat(44)).copyOf(32)
    val contentGuard = id.nearyou.app.guard.ContentLengthGuard(mapOf("post.content" to 280))
    val passThroughModerator =
        TextModerator(
            object : ModerationListLoader {
                override fun load(list: ModerationList): List<String> = emptyList()

                override fun loadThreshold(): Int = 3
            },
        )
    val moderationQueue = id.nearyou.app.infra.repo.JdbcModerationQueueRepository()

    fun serviceWith(repo: PostRepository = posts) =
        CreatePostService(
            dataSource = dataSource,
            posts = repo,
            contentGuard = contentGuard,
            textModerator = passThroughModerator,
            moderationQueue = moderationQueue,
            jitterSecret = jitterSecret,
            imageUploads = imageRepo,
        )

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                val short = id.toString().replace("-", "").take(8)
                ps.setObject(1, id)
                ps.setString(2, "ia_$short")
                ps.setString(3, "Image Attach Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "i${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedUpload(uploader: UUID): String {
        val cfImageId = "img-" + UUID.randomUUID().toString().take(12)
        val clean = SafeSearchVerdict(SafeSearchLikelihood.UNLIKELY, SafeSearchLikelihood.UNLIKELY, SafeSearchLikelihood.UNLIKELY)
        imageRepo.insertUploaded(cfImageId, uploader, clean)
        return cfImageId
    }

    fun <T> query(
        sql: String,
        id: Any,
        read: (java.sql.ResultSet) -> T,
    ): T? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                // setObject binds a UUID as `uuid` and a String as `text`, so this serves
                // both the posts (uuid id) and image_uploads (text cf_image_id) lookups.
                ps.setObject(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) read(rs) else null }
            }
        }

    fun postImageId(postId: UUID): String? = query("SELECT image_id FROM posts WHERE id = ?", postId) { it.getString("image_id") }

    fun ledgerStatus(cfImageId: String): String? =
        query("SELECT status FROM image_uploads WHERE cf_image_id = ?", cfImageId) { it.getString("status") }

    val lat = -6.2
    val lng = 106.8

    "attach a caller-owned uploaded image — post gets image_id, ledger flips to attached" {
        val user = seedUser()
        val image = seedUpload(user)
        val created = serviceWith().create(user, "hi", lat, lng, "premium_active", image)
        postImageId(created.id) shouldBe image
        ledgerStatus(image) shouldBe "attached"
    }

    "reject an image owned by another user (403), no post, ledger unchanged" {
        val owner = seedUser()
        val attacker = seedUser()
        val image = seedUpload(owner)
        shouldThrow<ImageAttachForbiddenException> {
            serviceWith().create(attacker, "hi", lat, lng, "premium_active", image)
        }
        ledgerStatus(image) shouldBe "uploaded"
    }

    "reject a non-existent image_id (422)" {
        val user = seedUser()
        shouldThrow<ImageAttachInvalidException> {
            serviceWith().create(user, "hi", lat, lng, "premium_active", "img-does-not-exist")
        }
    }

    "reject an already-attached image (422)" {
        val user = seedUser()
        val image = seedUpload(user)
        serviceWith().create(user, "first", lat, lng, "premium_active", image)
        shouldThrow<ImageAttachInvalidException> {
            serviceWith().create(user, "second", lat, lng, "premium_active", image)
        }
    }

    "conditional attach is single-winner — markAttached succeeds once then returns false" {
        val user = seedUser()
        val image = seedUpload(user)
        dataSource.connection.use { conn ->
            imageRepo.markAttached(conn, image) shouldBe true
            imageRepo.markAttached(conn, image) shouldBe false
        }
    }

    "atomic — a post INSERT failure rolls back the ledger flip (stays uploaded)" {
        val user = seedUser()
        val image = seedUpload(user)
        val throwingPosts =
            object : PostRepository by posts {
                override fun create(
                    conn: Connection,
                    row: NewPostRow,
                ): UUID = throw java.sql.SQLException("forced INSERT failure")
            }
        shouldThrow<java.sql.SQLException> {
            serviceWith(throwingPosts).create(user, "hi", lat, lng, "premium_active", image)
        }
        ledgerStatus(image) shouldBe "uploaded"
    }

    "attach is independent of tier — a Free caller can attach an already-uploaded image" {
        val user = seedUser()
        val image = seedUpload(user)
        val created = serviceWith().create(user, "hi", lat, lng, "free", image)
        postImageId(created.id) shouldBe image
    }

    "text-only post is unchanged — no image_id" {
        val user = seedUser()
        val created = serviceWith().create(user, "no image", lat, lng, "premium_active", null)
        postImageId(created.id).shouldBeNull()
    }
})
