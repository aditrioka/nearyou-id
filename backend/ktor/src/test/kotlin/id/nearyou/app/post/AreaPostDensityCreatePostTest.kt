package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.TextModerator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * DB-tagged behavior tests for the Layer 4 per-area density gate
 * (`post-area-density-cap`, tasks.md Section 5) at the [CreatePostService] level.
 * The limiter mechanics (boundary, cell derivation, sliding window) are pinned
 * fast + DB-free in [AreaPostDensityLimiterTest]; this suite pins the
 * gate→`moderation_queue` wiring end-to-end against real Postgres.
 *
 * **Test isolation (5.0; project memory: post-creating DB tests pollute the
 * Nearby/Global/Following suites).** Every post is created at a dedicated
 * out-of-band deep-Indian-Ocean coordinate ([OOB_LAT]/[OOB_LNG], a 0.01°
 * cell-center so the ≤500 m jitter stays in-cell) outside any timeline suite's
 * seed radius, and all seeded `posts` / `moderation_queue` / `notifications` /
 * `users` rows are swept per-test in `afterEach`.
 *
 * **Threshold strategy.** 5.1/5.2 use the PRODUCTION threshold (50) to pin the
 * exact `count > 50` boundary. The wiring tests (5.4/5.5/5.7/5.8/5.9/5.10/5.11)
 * inject a small-threshold limiter ([SMALL_THRESHOLD] = 2) so OverThreshold trips
 * after 3 posts — the threshold *value* is independent of the gate→queue wiring
 * those scenarios exercise, and keeping the volume tiny keeps the suite fast.
 *
 * Posters use `premium_active` so the 10/day Free daily cap never short-circuits
 * the volume — which also doubles as evidence the area gate is NOT Premium-exempt.
 */
@Tags("database")
class AreaPostDensityCreatePostTest : StringSpec({

    val dataSource = hikari()
    afterSpec { dataSource.close() }

    val posts = JdbcPostRepository()
    val moderationQueue = JdbcModerationQueueRepository()
    val jitterSecret =
        Base64.getDecoder()
            .decode("00000000000000000000000000000000000000000000")
            .copyOf(32)
    val contentGuard = ContentLengthGuard(mapOf("post.content" to 280))

    val seeded = mutableListOf<UUID>()
    afterEach {
        cleanup(dataSource, seeded)
        seeded.clear()
    }

    fun seedUser(): UUID = seedPremiumUser(dataSource).also { seeded.add(it) }

    "5.1 — exactly 50 posts in one cell: the 50th is clean (no moderation_queue row)" {
        val author = seedUser()
        val service = serviceWith(dataSource, posts, contentGuard, moderationQueue, jitterSecret, areaLimiter())
        val created =
            runBlocking {
                (1..50).map { service.create(author, "area $it", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM) }
            }
        // No area_spam row was written for ANY of the first 50 (count never exceeds 50).
        queueRowCount(dataSource, created.last().id, "area_spam") shouldBe 0
        areaSpamRowCountForAuthor(dataSource, author) shouldBe 0
    }

    "5.2 — the 51st post in the cell is flagged: one area_spam row, post stays visible, canonical payload" {
        val author = seedUser()
        val service = serviceWith(dataSource, posts, contentGuard, moderationQueue, jitterSecret, areaLimiter())
        runBlocking {
            repeat(50) { service.create(author, "fill $it", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM) }
            val flagged = service.create(author, "the 51st", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)

            // Canonical CreatedPost is returned unchanged (no error, same fields).
            flagged.content shouldBe "the 51st"
            flagged.latitude shouldBe OOB_LAT
            flagged.longitude shouldBe OOB_LNG

            queueRowCount(dataSource, flagged.id, "area_spam") shouldBe 1
            queueRowStatus(dataSource, flagged.id, "area_spam") shouldBe "pending"
            isAutoHidden(dataSource, flagged.id) shouldBe false
            // Only the 51st is flagged — exactly one area_spam row across the cell.
            areaSpamRowCountForAuthor(dataSource, author) shouldBe 1
        }
    }

    "5.4 — a Premium poster over threshold IS flagged (area gate is not Premium-exempt)" {
        val author = seedUser()
        val service =
            serviceWith(dataSource, posts, contentGuard, moderationQueue, jitterSecret, areaLimiter(SMALL_THRESHOLD))
        runBlocking {
            repeat(SMALL_THRESHOLD) { service.create(author, "p $it", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM) }
            val flagged = service.create(author, "over", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)
            queueRowCount(dataSource, flagged.id, "area_spam") shouldBe 1
        }
    }

    "5.5 — cell isolation: saturating cell A does not flag a below-threshold post in cell B" {
        val author = seedUser()
        val service =
            serviceWith(dataSource, posts, contentGuard, moderationQueue, jitterSecret, areaLimiter(SMALL_THRESHOLD))
        runBlocking {
            // Saturate cell A past threshold.
            repeat(SMALL_THRESHOLD + 1) { service.create(author, "A $it", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM) }
            // A single post in a clearly-distinct cell B is clean.
            val inB = service.create(author, "lonely B", OOB_LAT_B, OOB_LNG_B, subscriptionStatus = PREMIUM)
            queueRowCount(dataSource, inB.id, "area_spam") shouldBe 0
        }
    }

    "5.7a — fixed expiry: after the full 1h window elapses, a later post in the same cell is not flagged" {
        val author = seedUser()
        var now = Instant.parse("2026-06-26T00:00:00Z")
        val service =
            serviceWith(
                dataSource,
                posts,
                contentGuard,
                moderationQueue,
                jitterSecret,
                areaLimiter(SMALL_THRESHOLD, clock = { now }),
            )
        runBlocking {
            repeat(SMALL_THRESHOLD + 1) { service.create(author, "t0 $it", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM) }
            now = now.plus(Duration.ofMinutes(61))
            val afterWindow = service.create(author, "post-window", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)
            queueRowCount(dataSource, afterWindow.id, "area_spam") shouldBe 0
        }
    }

    "5.7b — sliding window ages out individual posts (t0 posts gone by t0+61m → not flagged)" {
        val author = seedUser()
        var now = Instant.parse("2026-06-26T00:00:00Z")
        val service =
            serviceWith(
                dataSource,
                posts,
                contentGuard,
                moderationQueue,
                jitterSecret,
                areaLimiter(SMALL_THRESHOLD, clock = { now }),
            )
        runBlocking {
            // Fill to threshold at t0 (count = SMALL_THRESHOLD, none flagged).
            repeat(SMALL_THRESHOLD) { service.create(author, "t0 $it", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM) }
            // One more at t0+59m → in-window count exceeds threshold → flagged.
            now = now.plus(Duration.ofMinutes(59))
            val flagged = service.create(author, "t0+59m", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)
            queueRowCount(dataSource, flagged.id, "area_spam") shouldBe 1
            // At t0+61m the t0 posts have aged out → a further post is NOT flagged.
            now = now.plus(Duration.ofMinutes(2))
            val aged = service.create(author, "t0+61m", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)
            queueRowCount(dataSource, aged.id, "area_spam") shouldBe 0
        }
    }

    "5.8 — idempotent: a duplicate (post, area_spam) insert leaves exactly one row" {
        val author = seedUser()
        val postId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location)
                VALUES (?, ?, 'idem',
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, author)
                ps.setDouble(3, OOB_LNG)
                ps.setDouble(4, OOB_LAT)
                ps.setDouble(5, OOB_LNG)
                ps.setDouble(6, OOB_LAT)
                ps.executeUpdate()
            }
            val first = moderationQueue.upsertAreaSpamRow(conn, id.nearyou.data.repository.ReportTargetType.POST, postId)
            val second = moderationQueue.upsertAreaSpamRow(conn, id.nearyou.data.repository.ReportTargetType.POST, postId)
            first shouldBe true
            second shouldBe false
        }
        queueRowCount(dataSource, postId, "area_spam") shouldBe 1
    }

    "5.9 — silent to poster: no notifications row of any type for the flagged poster" {
        val author = seedUser()
        val service =
            serviceWith(dataSource, posts, contentGuard, moderationQueue, jitterSecret, areaLimiter(SMALL_THRESHOLD))
        runBlocking {
            repeat(SMALL_THRESHOLD + 1) { service.create(author, "n $it", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM) }
        }
        notificationCountForUser(dataSource, author) shouldBe 0
    }

    "5.10 — rejected content: 400, no posts row, area counter NOT incremented, no area_spam row" {
        val author = seedUser()
        // Profanity loader → the keyword content yields Verdict.Reject (400 before the area gate).
        val rejectService =
            serviceWith(
                dataSource,
                posts,
                contentGuard,
                moderationQueue,
                jitterSecret,
                areaLimiter(SMALL_THRESHOLD),
                loader = AreaFixedListLoader(profanity = listOf("sentinelbadword"), uuIte = emptyList(), threshold = 3),
            )
        runBlocking {
            // One clean post consumes area slot #1.
            val clean1 = rejectService.create(author, "clean one", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)
            // A Reject short-circuits BEFORE the area gate → no posts row, no slot consumed.
            shouldThrow<ContentModeratedProfanityException> {
                rejectService.create(author, "this has sentinelbadword", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)
            }
            // The next clean post is area slot #2 (NOT #3) → still within threshold=2 → not flagged.
            // Had the Reject consumed a slot, this would be slot #3 → OverThreshold → flagged.
            val clean2 = rejectService.create(author, "clean two", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)
            queueRowCount(dataSource, clean1.id, "area_spam") shouldBe 0
            queueRowCount(dataSource, clean2.id, "area_spam") shouldBe 0
            // Exactly two posts persisted (the reject never inserted), zero area_spam rows.
            postRowCount(dataSource, author) shouldBe 2
            areaSpamRowCountForAuthor(dataSource, author) shouldBe 0
        }
    }

    "5.11 — area-flag + UU-ITE-flag coexist: 201 with exactly two moderation_queue rows (one per trigger)" {
        val author = seedUser()
        // UU-ITE loader → content with all three sentinels yields Verdict.Flag.
        val flagService =
            serviceWith(
                dataSource,
                posts,
                contentGuard,
                moderationQueue,
                jitterSecret,
                areaLimiter(SMALL_THRESHOLD),
                loader =
                    AreaFixedListLoader(
                        profanity = emptyList(),
                        uuIte = listOf("sentsara1", "sentsara2", "sentsara3"),
                        threshold = 3,
                    ),
            )
        runBlocking {
            // Plain posts saturate the cell (no UU-ITE match → no uu_ite rows).
            repeat(SMALL_THRESHOLD) { idx ->
                flagService.create(author, "plain $idx", OOB_LAT, OOB_LNG, subscriptionStatus = PREMIUM)
            }
            // The over-threshold post ALSO trips UU-ITE → both triggers fire.
            val both =
                flagService.create(
                    author,
                    "debat sentsara1 sentsara2 sentsara3",
                    OOB_LAT,
                    OOB_LNG,
                    subscriptionStatus = PREMIUM,
                )
            queueRowCount(dataSource, both.id, "area_spam") shouldBe 1
            queueRowCount(dataSource, both.id, "uu_ite_keyword_match") shouldBe 1
            totalQueueRowCount(dataSource, both.id) shouldBe 2
        }
    }
})

// ---------------------------------------------------------------------------
// Shared fixtures + helpers
// ---------------------------------------------------------------------------

private const val PREMIUM = "premium_active"
private const val SMALL_THRESHOLD = 2

// Deep-Indian-Ocean cell-center coordinates, in-envelope (LAT -11..6.5, LNG 94..142),
// outside any timeline suite's Jakarta seed radius. 0.01° cell centers so the
// ≤500 m HMAC jitter never crosses a cell boundary.
private const val OOB_LAT = -10.50
private const val OOB_LNG = 105.00
private const val OOB_LAT_B = -10.20
private const val OOB_LNG_B = 104.50

private fun areaLimiter(
    threshold: Int = AreaPostDensityLimiter.AREA_POST_THRESHOLD,
    clock: () -> Instant = Instant::now,
): AreaPostDensityLimiter = AreaPostDensityLimiter(rateLimiter = InMemoryRateLimiter(clock = clock), threshold = threshold)

@Suppress("LongParameterList")
private fun serviceWith(
    dataSource: HikariDataSource,
    posts: JdbcPostRepository,
    contentGuard: ContentLengthGuard,
    moderationQueue: JdbcModerationQueueRepository,
    jitterSecret: ByteArray,
    areaLimiter: AreaPostDensityLimiter,
    loader: ModerationListLoader = AllowOnlyLoader,
): CreatePostService =
    CreatePostService(
        dataSource = dataSource,
        posts = posts,
        contentGuard = contentGuard,
        textModerator = TextModerator(loader),
        moderationQueue = moderationQueue,
        jitterSecret = jitterSecret,
        areaDensityLimiter = areaLimiter,
    )

private fun seedPremiumUser(dataSource: HikariDataSource): UUID {
    val id = UUID.randomUUID()
    val short = id.toString().replace("-", "").take(8)
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, subscription_status)
            VALUES (?, ?, 'Area Tester', DATE '1995-03-14', ?, 'premium_active')
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "area_$short")
            ps.setString(3, "a${short.take(7)}")
            ps.executeUpdate()
        }
    }
    return id
}

private fun cleanup(
    dataSource: HikariDataSource,
    ids: Collection<UUID>,
) {
    if (ids.isEmpty()) return
    dataSource.connection.use { conn ->
        conn.autoCommit = true
        ids.forEach { id ->
            conn.prepareStatement(
                "DELETE FROM moderation_queue WHERE target_id IN (SELECT id FROM posts WHERE author_id = ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM notifications WHERE user_id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM posts WHERE author_id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, id)
                ps.executeUpdate()
            }
        }
    }
}

private fun queueRowCount(
    dataSource: HikariDataSource,
    targetId: UUID,
    trigger: String,
): Int =
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM moderation_queue WHERE target_type = 'post' AND target_id = ? AND trigger = ?",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.setString(2, trigger)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun totalQueueRowCount(
    dataSource: HikariDataSource,
    targetId: UUID,
): Int =
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM moderation_queue WHERE target_type = 'post' AND target_id = ?",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun queueRowStatus(
    dataSource: HikariDataSource,
    targetId: UUID,
    trigger: String,
): String =
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT status FROM moderation_queue WHERE target_type = 'post' AND target_id = ? AND trigger = ?",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.setString(2, trigger)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

private fun areaSpamRowCountForAuthor(
    dataSource: HikariDataSource,
    authorId: UUID,
): Int =
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT COUNT(*) FROM moderation_queue
             WHERE trigger = 'area_spam'
               AND target_id IN (SELECT id FROM posts WHERE author_id = ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, authorId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun isAutoHidden(
    dataSource: HikariDataSource,
    postId: UUID,
): Boolean =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT is_auto_hidden FROM posts WHERE id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

private fun postRowCount(
    dataSource: HikariDataSource,
    authorId: UUID,
): Int =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM posts WHERE author_id = ?").use { ps ->
            ps.setObject(1, authorId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun notificationCountForUser(
    dataSource: HikariDataSource,
    userId: UUID,
): Int =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ?").use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

private fun hikari(): HikariDataSource {
    val config =
        HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2
        }
    return HikariDataSource(config)
}

private object AllowOnlyLoader : ModerationListLoader {
    override fun load(list: ModerationList): List<String> = emptyList()

    override fun loadThreshold(): Int = 3
}

private class AreaFixedListLoader(
    private val profanity: List<String>,
    private val uuIte: List<String>,
    private val threshold: Int,
) : ModerationListLoader {
    override fun load(list: ModerationList): List<String> =
        when (list) {
            ModerationList.ProfanityList -> profanity
            ModerationList.UuIteList -> uuIte
        }

    override fun loadThreshold(): Int = threshold
}
