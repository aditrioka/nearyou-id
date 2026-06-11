package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.infra.repo.JdbcPostRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Pins the 10/day post cap (post-creation spec § "Daily post cap"; shipped as 2026-06-10
 * audit fix 02-M2 which until this file had only manual staging curl evidence).
 *
 * Service-level — the cap decision lives entirely in [CreatePostService.create]
 * (acquire after free validations, before moderation I/O; Premium skips). Route-level
 * 429 + Retry-After mapping is covered by `AppStatusPages`' `PostRateLimitedException`
 * handler (StatusPagesClientErrorTest family).
 */
@Tags("database")
class PostRateLimitTest : StringSpec({

    // autoClose + tiny pool per the CI connection-budget convention (PR #157):
    // a leaked per-spec pool tips the CI Postgres into "too many clients".
    val dataSource = autoClose(hikariLocal())
    val posts = JdbcPostRepository()
    val jitterSecret =
        Base64.getDecoder()
            .decode("00000000000000000000000000000000000000000000")
            .copyOf(32)
    val contentGuard = ContentLengthGuard(mapOf("post.content" to 280))
    val allowOnlyLoader =
        object : id.nearyou.app.moderation.ModerationListLoader {
            override fun load(list: id.nearyou.app.moderation.ModerationList): List<String> = emptyList()

            override fun loadThreshold(): Int = 3
        }
    val passThroughModerator = id.nearyou.app.moderation.TextModerator(allowOnlyLoader)
    val moderationQueueRepo = id.nearyou.app.infra.repo.JdbcModerationQueueRepository()

    fun serviceWith(limiter: RateLimiter): CreatePostService =
        CreatePostService(
            dataSource = dataSource,
            posts = posts,
            contentGuard = contentGuard,
            textModerator = passThroughModerator,
            moderationQueue = moderationQueueRepo,
            jitterSecret = jitterSecret,
            rateLimiter = PostRateLimiter(rateLimiter = limiter),
        )

    fun seedUser(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix
                ) VALUES (?, ?, ?, DATE '2000-01-01', ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                val short = id.toString().replace("-", "").take(8)
                ps.setString(2, "cap_$short")
                ps.setString(3, "Cap Tester")
                ps.setString(4, "c${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            ids.forEach { id ->
                conn.createStatement().use { st ->
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$id'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$id'")
                }
            }
        }
    }

    "11th Free post in the window throws PostRateLimitedException with positive Retry-After" {
        val author = seedUser()
        try {
            val service = serviceWith(InMemoryRateLimiter())
            runBlocking {
                repeat(10) { i ->
                    service.create(author, "post nomor $i", -6.2, 106.8)
                }
                val ex =
                    shouldThrow<PostRateLimitedException> {
                        service.create(author, "post nomor 11", -6.2, 106.8)
                    }
                ex.retryAfterSeconds shouldBeGreaterThan 0L
            }
            dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM posts WHERE author_id = '$author'").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 10
                    }
                }
            }
        } finally {
            cleanup(author)
        }
    }

    "Premium author skips the daily cap entirely (bucket never written)" {
        val author = seedUser()
        try {
            val recording = RecordingLimiter()
            val service = serviceWith(recording)
            runBlocking {
                repeat(11) { i ->
                    service.create(author, "premium post $i", -6.2, 106.8, subscriptionStatus = "premium_active")
                }
            }
            recording.keys.none { it.contains("rate_post_day") } shouldBe true
        } finally {
            cleanup(author)
        }
    }

    "created post id is UUIDv7 — version nibble 7 (post-creation spec, previously untested)" {
        val author = seedUser()
        try {
            val service = serviceWith(InMemoryRateLimiter())
            val created = runBlocking { service.create(author, "uuidv7 pin", -6.2, 106.8) }
            created.id.version() shouldBe 7
        } finally {
            cleanup(author)
        }
    }

    "validation failure consumes no daily slot (cap gate runs after free validations)" {
        val author = seedUser()
        try {
            val recording = RecordingLimiter()
            val service = serviceWith(recording)
            runBlocking {
                shouldThrow<ContentTooLongException> {
                    service.create(author, "x".repeat(281), -6.2, 106.8)
                }
                shouldThrow<LocationOutOfBoundsException> {
                    service.create(author, "valid content", 48.85, 2.35)
                }
            }
            recording.keys.toList() shouldBe emptyList<String>()
        } finally {
            cleanup(author)
        }
    }
})

private class RecordingLimiter : RateLimiter {
    val keys = ConcurrentLinkedQueue<String>()

    private val delegate = InMemoryRateLimiter()

    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        keys.add(key)
        return delegate.tryAcquire(userId, key, capacity, ttl)
    }

    override fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        keys.add(key)
        return delegate.tryAcquireByKey(key, capacity, ttl)
    }

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) {
        delegate.releaseMostRecent(userId, key)
    }
}

private fun hikariLocal(): HikariDataSource {
    val config =
        HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2
        }
    return HikariDataSource(config)
}
