package id.nearyou.app.moderation

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.perspective.RecordingPerspectiveClient
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcPerspectiveModerationWriter
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.post.ContentModeratedProfanityException
import id.nearyou.app.post.CreatePostService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.sql.Date
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Service-level integration tests covering the full Layer 3 pipeline against a real
 * Postgres + Redis stack — `text-moderation-perspective-api-layer/spec.md` capability
 * Section 10 + Section 11. Substitutes direct service invocation for `testApplication`
 * to keep the test surface focused on the orchestrator-dispatcher-writer integration;
 * the route-layer wiring is verified by the structural call-order test.
 *
 * Each scenario:
 *  1. Builds a real `DefaultPerspectiveModerator` + `PerspectiveDispatcherScope` over
 *     a `RecordingPerspectiveClient` (test fixture from `:infra:perspective`) and
 *     `JdbcPerspectiveModerationWriter` against a real Postgres dataSource.
 *  2. Invokes `CreatePostService.create(...)` via `runBlocking`.
 *  3. Calls `dispatcher.shutdown(drainMillis = 5000)` to drain in-flight dispatches —
 *     equivalent to waiting on the dispatch Job; deterministic.
 *  4. Asserts DB state (`posts.is_auto_hidden`, `moderation_queue` rows).
 *
 * Privacy / no-content-on-Sentry coverage (Section 11): a logback `ListAppender`
 * captures `event=perspective_*` log lines; assertions verify no raw user content,
 * no raw per-attribute floats appear in the captured events.
 */
class PerspectiveLayer3IntegrationTest : StringSpec({

    val dataSource = hikari()
    val perspectiveLogAppender = ListAppender<ILoggingEvent>().apply { start() }
    val perspectiveLogger =
        LoggerFactory.getLogger(DefaultPerspectiveModerator::class.java) as Logger
    perspectiveLogger.addAppender(perspectiveLogAppender)
    val configLoaderLogger =
        LoggerFactory.getLogger(CachingPerspectiveConfigLoader::class.java) as Logger
    configLoaderLogger.addAppender(perspectiveLogAppender)

    afterSpec {
        dataSource.close()
        perspectiveLogger.detachAppender(perspectiveLogAppender)
        configLoaderLogger.detachAppender(perspectiveLogAppender)
    }

    "10.2 + AutoHide path: high score 0.92 → posts.is_auto_hidden = TRUE + queue row" {
        val sentinelContent = "sentinel-content-DO-NOT-LEAK-high"
        perspectiveLogAppender.list.clear()
        val client =
            RecordingPerspectiveClient().apply {
                stageScore(toxicity = 0.92, severeToxicity = 0.40, identityAttack = 0.10, threat = 0.20)
            }
        val (service, scope, author) = newService(dataSource, client, isEnabled = true)
        try {
            val created = runBlocking { service.create(author, sentinelContent, -6.2, 106.8) }
            scope.shutdown(drainMillis = 5_000L)

            isAutoHidden(dataSource, created.id) shouldBe true
            countQueueRows(dataSource, created.id) shouldBe 1

            // Privacy contract (Section 11): captured events do NOT include the user content.
            val capturedJson = capturedEventsAsJsonLike(perspectiveLogAppender)
            capturedJson shouldNotContain sentinelContent
            // No raw per-attribute scores either — only score_bucket category.
            capturedJson shouldNotContain "0.92"
            capturedJson shouldNotContain "0.40"
            capturedJson shouldContain "score_bucket=high"
        } finally {
            cleanupPost(dataSource, created = author)
        }
    }

    "10.3 FlagOnly path: mid score 0.70 → no flip + queue row" {
        perspectiveLogAppender.list.clear()
        val client = RecordingPerspectiveClient().apply { stageScore(toxicity = 0.70) }
        val (service, scope, author) = newService(dataSource, client, isEnabled = true)
        val created =
            try {
                val out = runBlocking { service.create(author, "midcontent", -6.2, 106.8) }
                scope.shutdown(drainMillis = 5_000L)
                out
            } finally {
                // Cleanup deferred to end-of-block to keep `created.id` in scope.
            }
        try {
            isAutoHidden(dataSource, created.id) shouldBe false
            countQueueRows(dataSource, created.id) shouldBe 1

            val capturedJson = capturedEventsAsJsonLike(perspectiveLogAppender)
            capturedJson shouldContain "score_bucket=mid"
            capturedJson shouldNotContain "0.70"
        } finally {
            cleanupPost(dataSource, created.id, author)
        }
    }

    "10.4 NoAction path: low score 0.50 → no DB writes" {
        perspectiveLogAppender.list.clear()
        val client = RecordingPerspectiveClient().apply { stageScore(toxicity = 0.50) }
        val (service, scope, author) = newService(dataSource, client, isEnabled = true)
        val created = runBlocking { service.create(author, "lowcontent", -6.2, 106.8) }
        try {
            scope.shutdown(drainMillis = 5_000L)

            isAutoHidden(dataSource, created.id) shouldBe false
            countQueueRows(dataSource, created.id) shouldBe 0
        } finally {
            cleanupPost(dataSource, created.id, author)
        }
    }

    "10.5 + 10.6 Failure path: HTTP 5xx → no DB writes, sentinel content NOT in logs" {
        val sentinelContent = "sentinel-content-DO-NOT-LEAK-503"
        perspectiveLogAppender.list.clear()
        val client =
            RecordingPerspectiveClient().apply {
                stageException(
                    id.nearyou.app.infra.perspective.PerspectiveHttpException(
                        status = io.ktor.http.HttpStatusCode.ServiceUnavailable,
                        body = "",
                    ),
                )
            }
        val (service, scope, author) = newService(dataSource, client, isEnabled = true)
        val created = runBlocking { service.create(author, sentinelContent, -6.2, 106.8) }
        try {
            scope.shutdown(drainMillis = 5_000L)

            isAutoHidden(dataSource, created.id) shouldBe false
            countQueueRows(dataSource, created.id) shouldBe 0

            // Failure event present, sentinel content absent.
            val capturedJson = capturedEventsAsJsonLike(perspectiveLogAppender)
            capturedJson shouldContain "perspective_dispatch_failed"
            capturedJson shouldContain "failure_kind=http_5xx"
            capturedJson shouldContain "status_code=503"
            capturedJson shouldNotContain sentinelContent
        } finally {
            cleanupPost(dataSource, created.id, author)
        }
    }

    "10.7 Kill-switch OFF: PerspectiveClient never invoked, no DB writes" {
        perspectiveLogAppender.list.clear()
        val client =
            RecordingPerspectiveClient().apply {
                stageScore(toxicity = 0.92) // would auto-hide if invoked
            }
        val (service, scope, author) = newService(dataSource, client, isEnabled = false)
        val created = runBlocking { service.create(author, "anycontent", -6.2, 106.8) }
        try {
            scope.shutdown(drainMillis = 5_000L)

            client.callCount shouldBe 0
            isAutoHidden(dataSource, created.id) shouldBe false
            countQueueRows(dataSource, created.id) shouldBe 0
        } finally {
            cleanupPost(dataSource, created.id, author)
        }
    }

    "10.9 Layer 1 reject short-circuits BEFORE Layer 3 dispatch (analyzeCalls == 0)" {
        perspectiveLogAppender.list.clear()
        val client = RecordingPerspectiveClient()
        val (service, scope, author) =
            newServiceWithModerator(
                dataSource,
                client,
                isEnabled = true,
                profanityKeywords = listOf("kontolblock"),
                uuIteKeywords = emptyList(),
                uuIteThreshold = 3,
            )
        try {
            shouldThrow<ContentModeratedProfanityException> {
                runBlocking { service.create(author, "halo kontolblock semua", -6.2, 106.8) }
            }
            scope.shutdown(drainMillis = 5_000L)

            // Layer 1 reject prevented the INSERT; therefore no Layer 3 dispatch.
            client.callCount shouldBe 0
            // No DB writes — no post row, no queue row.
            postRowCount(dataSource, author) shouldBe 0
        } finally {
            cleanupPost(dataSource, created = author)
        }
    }

    "10.10 Layer 2 flag does NOT block Layer 3 — both triggers produce queue rows" {
        perspectiveLogAppender.list.clear()
        val client = RecordingPerspectiveClient().apply { stageScore(toxicity = 0.92) }
        val (service, scope, author) =
            newServiceWithModerator(
                dataSource,
                client,
                isEnabled = true,
                profanityKeywords = emptyList(),
                uuIteKeywords = listOf("sara1", "sara2", "sara3"),
                uuIteThreshold = 3,
            )
        // Content with all three UU ITE keywords meets the threshold → Verdict.Flag.
        val content = "ini sara1 dan sara2 plus sara3 dalam satu pesan"
        val created = runBlocking { service.create(author, content, -6.2, 106.8) }
        try {
            scope.shutdown(drainMillis = 5_000L)

            // Layer 2 flagged AND Layer 3 high-scored — two queue rows with different triggers.
            client.callCount shouldBe 1
            countQueueRowsByTrigger(dataSource, created.id, "uu_ite_keyword_match") shouldBe 1
            countQueueRowsByTrigger(dataSource, created.id, "perspective_api_high_score") shouldBe 1
            isAutoHidden(dataSource, created.id) shouldBe true
        } finally {
            cleanupPost(dataSource, created.id, author)
        }
    }

    "11.3 At most one Sentry-equivalent event per moderate(...) invocation" {
        perspectiveLogAppender.list.clear()
        val client = RecordingPerspectiveClient().apply { stageScore(toxicity = 0.92) }
        val (service, scope, author) = newService(dataSource, client, isEnabled = true)
        val created = runBlocking { service.create(author, "anycontent", -6.2, 106.8) }
        try {
            scope.shutdown(drainMillis = 5_000L)

            // Filter to events emitted by the orchestrator surface — exclude
            // the `perspective_dispatcher_scope_shutdown` lifecycle log.
            val perspectiveOutcomeEvents =
                perspectiveLogAppender.list.filter { event ->
                    val msg = event.formattedMessage ?: ""
                    msg.contains("event=perspective_high_score_applied") ||
                        msg.contains("event=perspective_flag_applied") ||
                        msg.contains("event=perspective_dispatch_failed") ||
                        msg.contains("event=perspective_kill_switch_unavailable") ||
                        msg.contains("event=perspective_threshold_misconfigured")
                }
            perspectiveOutcomeEvents.size shouldBe 1
        } finally {
            cleanupPost(dataSource, created.id, author)
        }
    }
})

// ---- Helper builders --------------------------------------------------------

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 6
            initializationFailTimeout = -1
        },
    )
}

private data class TestRig(
    val service: CreatePostService,
    val dispatcherScope: PerspectiveDispatcherScope,
    val authorId: UUID,
)

private fun newService(
    dataSource: DataSource,
    client: RecordingPerspectiveClient,
    isEnabled: Boolean,
): TestRig {
    val author = seedUser(dataSource, "p3")
    val scope =
        PerspectiveDispatcherScope.forTestWithDefensiveHandler(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    val configLoader =
        StaticConfigLoader(
            isEnabled = isEnabled,
            highScoreThreshold = 0.8,
            flagThreshold = 0.6,
        )
    val writer = JdbcPerspectiveModerationWriter(dataSource)
    val moderator =
        DefaultPerspectiveModerator(
            client = client,
            configLoader = configLoader,
            writer = writer,
        )
    val service =
        CreatePostService(
            dataSource = dataSource,
            posts = JdbcPostRepository(),
            contentGuard = ContentLengthGuard(mapOf("post.content" to 280)),
            textModerator = passThroughTextModerator(),
            moderationQueue = JdbcModerationQueueRepository(),
            jitterSecret = ByteArray(32) { 0x01 },
            perspectiveDispatcherScope = scope,
            perspectiveModerator = moderator,
        )
    return TestRig(service = service, dispatcherScope = scope, authorId = author)
}

/**
 * TextModerator that always returns `Verdict.Allow` so the integration test focuses
 * on Layer 3 behavior. Layer 1+2 short-circuit semantics are covered by
 * `PostCreationModerationIntegrationTest`.
 */
private fun passThroughTextModerator(): TextModerator =
    TextModerator(
        loader =
            object : ModerationListLoader {
                override fun load(list: ModerationList): List<String> = emptyList()

                override fun loadThreshold(): Int = 3
            },
    )

/**
 * Variant of [newService] that builds a [TextModerator] over a real keyword list, so
 * tests can exercise the interaction between Layer 1 (Reject) / Layer 2 (Flag) and
 * Layer 3 dispatch.
 */
private fun newServiceWithModerator(
    dataSource: DataSource,
    client: RecordingPerspectiveClient,
    isEnabled: Boolean,
    profanityKeywords: List<String>,
    uuIteKeywords: List<String>,
    uuIteThreshold: Int,
): TestRig {
    val author = seedUser(dataSource, "p3m")
    val scope =
        PerspectiveDispatcherScope.forTestWithDefensiveHandler(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    val configLoader =
        StaticConfigLoader(
            isEnabled = isEnabled,
            highScoreThreshold = 0.8,
            flagThreshold = 0.6,
        )
    val writer = JdbcPerspectiveModerationWriter(dataSource)
    val moderator =
        DefaultPerspectiveModerator(
            client = client,
            configLoader = configLoader,
            writer = writer,
        )
    val textModerator =
        TextModerator(
            loader =
                object : ModerationListLoader {
                    override fun load(list: ModerationList): List<String> =
                        when (list) {
                            ModerationList.ProfanityList -> profanityKeywords
                            ModerationList.UuIteList -> uuIteKeywords
                        }

                    override fun loadThreshold(): Int = uuIteThreshold
                },
        )
    val service =
        CreatePostService(
            dataSource = dataSource,
            posts = JdbcPostRepository(),
            contentGuard = ContentLengthGuard(mapOf("post.content" to 280)),
            textModerator = textModerator,
            moderationQueue = JdbcModerationQueueRepository(),
            jitterSecret = ByteArray(32) { 0x01 },
            perspectiveDispatcherScope = scope,
            perspectiveModerator = moderator,
        )
    return TestRig(service = service, dispatcherScope = scope, authorId = author)
}

/** Static config loader — bypasses the Redis + Remote Config tier ladder for E2E tests. */
internal class StaticConfigLoader(
    private val isEnabled: Boolean,
    private val highScoreThreshold: Double,
    private val flagThreshold: Double,
) : PerspectiveConfigLoader {
    override suspend fun isEnabled(): Boolean = isEnabled

    override suspend fun highScoreThreshold(): Double = highScoreThreshold

    override suspend fun flagThreshold(): Double = flagThreshold
}

private fun capturedEventsAsJsonLike(appender: ListAppender<ILoggingEvent>): String =
    appender.list.joinToString("\n") { it.formattedMessage ?: "" }

// ---- DB seed / inspect / cleanup helpers (mirror JdbcPerspectiveModerationWriterIntegrationTest) ----

private fun seedUser(
    dataSource: DataSource,
    prefix: String,
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
            ps.setString(2, "${prefix}_$short")
            ps.setString(3, "L3 Integration")
            ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
            ps.setString(5, "i${short.take(7)}")
            ps.executeUpdate()
        }
    }
    return id
}

private fun isAutoHidden(
    dataSource: DataSource,
    postId: UUID,
): Boolean {
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT is_auto_hidden FROM posts WHERE id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return false
                return rs.getBoolean("is_auto_hidden")
            }
        }
    }
}

private fun countQueueRows(
    dataSource: DataSource,
    targetId: UUID,
): Int {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT count(*) FROM moderation_queue " +
                "WHERE target_type = 'post' AND target_id = ? AND trigger = 'perspective_api_high_score'",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}

private fun countQueueRowsByTrigger(
    dataSource: DataSource,
    targetId: UUID,
    trigger: String,
): Int {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT count(*) FROM moderation_queue WHERE target_type = 'post' AND target_id = ? AND trigger = ?",
        ).use { ps ->
            ps.setObject(1, targetId)
            ps.setString(2, trigger)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}

private fun postRowCount(
    dataSource: DataSource,
    authorId: UUID,
): Int {
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT count(*) FROM posts WHERE author_id = ?").use { ps ->
            ps.setObject(1, authorId)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }
}

private fun cleanupPost(
    dataSource: DataSource,
    postId: UUID,
    authorId: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DELETE FROM moderation_queue WHERE target_id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM posts WHERE id = ?").use { ps ->
            ps.setObject(1, postId)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
            ps.setObject(1, authorId)
            ps.executeUpdate()
        }
    }
}

// Variant that just deletes the user (for paths where the post wasn't created or
// `created.id` isn't in scope at cleanup time).
private fun cleanupPost(
    dataSource: DataSource,
    created: UUID,
) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DELETE FROM moderation_queue WHERE target_id IN (SELECT id FROM posts WHERE author_id = ?)").use { ps ->
            ps.setObject(1, created)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM posts WHERE author_id = ?").use { ps ->
            ps.setObject(1, created)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
            ps.setObject(1, created)
            ps.executeUpdate()
        }
    }
}
