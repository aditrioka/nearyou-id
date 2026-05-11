package id.nearyou.app.moderation

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.config.StubRemoteConfig
import id.nearyou.app.engagement.REPLY_CONTENT_KEY
import id.nearyou.app.engagement.ReplyService
import id.nearyou.app.engagement.replyRoutes
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.infra.openaimoderation.ModerationHttpException
import id.nearyou.app.infra.openaimoderation.RecordingModerationClient
import id.nearyou.app.infra.redis.NoOpRateLimiter
import id.nearyou.app.infra.repo.JdbcLayer3ModerationWriter
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcPostReplyRepository
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.post.ContentModeratedProfanityException
import id.nearyou.app.post.CreatePostService
import id.nearyou.app.post.LocationOutOfBoundsException
import id.nearyou.app.post.postRoutes
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * HTTP-boundary integration tests for `text-moderation-perspective-api-layer` via
 * `testApplication { ... }` (per task 10.1's spec). Exercises the full
 * `POST /api/v1/posts` and `POST /api/v1/posts/{post_id}/replies` request lifecycle
 * with real Postgres + `RecordingModerationClient` + a fresh
 * `Layer3DispatcherScope` per scenario.
 *
 * Each scenario:
 *  1. Issues a JWT for a seeded user.
 *  2. Calls the HTTP endpoint with a request body.
 *  3. Asserts the HTTP response code/body.
 *  4. Drains the per-scenario dispatcher via `shutdown(5000)` so the async Layer 3
 *     dispatch completes before DB assertions run.
 *  5. Asserts DB state on `posts` / `post_replies` / `moderation_queue`.
 *
 * Scope vs. `Layer3IntegrationTest`: this file covers the HTTP-boundary
 * path (route handler, auth middleware, `StatusPages` mapping, ContentNegotiation,
 * response envelope shape). The sibling test covers the service-level orchestrator-
 * dispatcher-writer integration. Both are valuable: the route test catches
 * regressions in HTTP-layer wiring (e.g., `suspend` modifier propagation, status-code
 * mapping); the service-level test isolates orchestrator concerns from HTTP plumbing.
 *
 * Covers spec tasks 10.1 (testApplication boot), 10.2 (HTTP-level high score),
 * 10.3 (HTTP-level mid score), 10.4 (HTTP-level low score), 10.5 (HTTP-level 5xx
 * failure), 10.6 (HTTP-level 600ms timeout), 10.7 (HTTP-level kill-switch off), and
 * 10.8 (reply path — same shape of tests).
 */
@Tags("database")
class Layer3RouteTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-perspective-route")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val postRepo = JdbcPostRepository()
    val replyRepo = JdbcPostReplyRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val notificationEmitter = DbNotificationEmitter(notificationsRepo)
    val moderationQueue = JdbcModerationQueueRepository()
    val perspectiveWriter = JdbcLayer3ModerationWriter(dataSource)
    val jitterSecret =
        Base64
            .getDecoder()
            .decode("00000000000000000000000000000000000000000000")
            .copyOf(32)
    val contentGuardPost = ContentLengthGuard(mapOf("post.content" to 280))
    val contentGuardReply = ContentLengthGuard(mapOf(REPLY_CONTENT_KEY to 280))
    val passThroughTextModerator =
        TextModerator(
            loader =
                object : ModerationListLoader {
                    override fun load(list: ModerationList): List<String> = emptyList()

                    override fun loadThreshold(): Int = 3
                },
        )

    afterSpec {
        // Cleanup all rows seeded by this spec (users prefixed `pr_*`).
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    DELETE FROM notifications WHERE user_id IN
                      (SELECT id FROM users WHERE username LIKE 'pr!_%' ESCAPE '!')
                       OR actor_user_id IN
                      (SELECT id FROM users WHERE username LIKE 'pr!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM moderation_queue WHERE target_id IN
                      (SELECT id FROM post_replies WHERE author_id IN
                        (SELECT id FROM users WHERE username LIKE 'pr!_%' ESCAPE '!'))
                       OR target_id IN
                      (SELECT id FROM posts WHERE author_id IN
                        (SELECT id FROM users WHERE username LIKE 'pr!_%' ESCAPE '!'))
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM post_replies WHERE author_id IN
                      (SELECT id FROM users WHERE username LIKE 'pr!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM posts WHERE author_id IN
                      (SELECT id FROM users WHERE username LIKE 'pr!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate("DELETE FROM users WHERE username LIKE 'pr!_%' ESCAPE '!'")
            }
        }
        dataSource.close()
    }

    fun seedUserAndIssue(): Pair<UUID, String> {
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
                ps.setString(2, "pr_$short")
                ps.setString(3, "Perspective Route Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "p${short.take(7)}")
                ps.executeUpdate()
            }
        }
        return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
    }

    fun seedParentPost(authorId: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location)
                VALUES (?, ?, 'parent post for reply',
                  ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, authorId)
                ps.executeUpdate()
            }
        }
        return id
    }

    // ---- testApplication scaffolds ------------------------------------------

    /**
     * Wires the POST route with full Layer 3 collaborators using a fresh
     * [Layer3DispatcherScope] per scenario (so `shutdown(5000)` drains
     * deterministically without leaking into peer scenarios).
     */
    suspend fun withPostApp(
        recordingClient: RecordingModerationClient,
        isKillSwitchEnabled: Boolean = true,
        highScoreThreshold: Double = 0.8,
        flagThreshold: Double = 0.6,
        block: suspend HttpScenarioContext.() -> Unit,
    ) {
        val dispatcher =
            Layer3DispatcherScope.forTestWithDefensiveHandler(
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        val configLoader =
            StaticConfigLoader(
                isEnabled = isKillSwitchEnabled,
                highScoreThreshold = highScoreThreshold,
                flagThreshold = flagThreshold,
            )
        val moderator =
            DefaultLayer3Moderator(
                client = recordingClient,
                configLoader = configLoader,
                writer = perspectiveWriter,
            )
        val createPostService =
            CreatePostService(
                dataSource = dataSource,
                posts = postRepo,
                contentGuard = contentGuardPost,
                textModerator = passThroughTextModerator,
                moderationQueue = moderationQueue,
                jitterSecret = jitterSecret,
                layer3DispatcherScope = dispatcher,
                layer3Moderator = moderator,
            )
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
                install(StatusPages) {
                    exception<ContentEmptyException> { call, _ ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf("code" to "content_empty")))
                    }
                    exception<ContentTooLongException> { call, _ ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf("code" to "content_too_long")))
                    }
                    exception<LocationOutOfBoundsException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to mapOf("code" to "location_out_of_bounds")),
                        )
                    }
                    exception<ContentModeratedProfanityException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to mapOf("code" to "content_moderated_profanity")),
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                postRoutes(createPostService)
            }
            block(HttpScenarioContext(this, dispatcher))
        }
    }

    /** Reply-path scaffold — mirrors [withPostApp] for the `POST /api/v1/posts/{post_id}/replies` route. */
    suspend fun withReplyApp(
        recordingClient: RecordingModerationClient,
        isKillSwitchEnabled: Boolean = true,
        block: suspend HttpScenarioContext.() -> Unit,
    ) {
        val dispatcher =
            Layer3DispatcherScope.forTestWithDefensiveHandler(
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        val configLoader =
            StaticConfigLoader(
                isEnabled = isKillSwitchEnabled,
                highScoreThreshold = 0.8,
                flagThreshold = 0.6,
            )
        val moderator =
            DefaultLayer3Moderator(
                client = recordingClient,
                configLoader = configLoader,
                writer = perspectiveWriter,
            )
        val replyService =
            ReplyService(
                dataSource = dataSource,
                replies = replyRepo,
                notifications = notificationEmitter,
                dispatcher = NoopNotificationDispatcher(),
                rateLimiter = NoOpRateLimiter(),
                remoteConfig = StubRemoteConfig(),
                textModerator = passThroughTextModerator,
                moderationQueue = moderationQueue,
                layer3DispatcherScope = dispatcher,
                layer3Moderator = moderator,
            )
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
                install(StatusPages) {
                    exception<ContentEmptyException> { call, _ ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf("code" to "content_empty")))
                    }
                    exception<ContentTooLongException> { call, _ ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf("code" to "content_too_long")))
                    }
                    exception<ContentModeratedProfanityException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to mapOf("code" to "content_moderated_profanity")),
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                replyRoutes(replyService, contentGuardReply)
            }
            block(HttpScenarioContext(this, dispatcher))
        }
    }

    // ---- POST scenarios -----------------------------------------------------

    "10.2 POST /api/v1/posts at score 0.92 → 201 + posts.is_auto_hidden = TRUE + queue row" {
        val (_, token) = seedUserAndIssue()
        val client = RecordingModerationClient().apply { stageMaxScore(0.92) }

        withPostApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"high score content","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val postId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isAutoHidden(dataSource, postId) shouldBe true
                countPostQueueRows(dataSource, postId, "perspective_api_high_score") shouldBe 1
            }
        }
    }

    "10.3 POST /api/v1/posts at score 0.70 → 201 + no flip + queue row" {
        val (_, token) = seedUserAndIssue()
        val client = RecordingModerationClient().apply { stageMaxScore(0.70) }

        withPostApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"mid score content","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val postId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isAutoHidden(dataSource, postId) shouldBe false
                countPostQueueRows(dataSource, postId, "perspective_api_high_score") shouldBe 1
            }
        }
    }

    "10.4 POST /api/v1/posts at score 0.50 → 201 + no Layer 3 DB writes" {
        val (_, token) = seedUserAndIssue()
        val client = RecordingModerationClient().apply { stageMaxScore(0.50) }

        withPostApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"low score content","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val postId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isAutoHidden(dataSource, postId) shouldBe false
                countPostQueueRows(dataSource, postId, "perspective_api_high_score") shouldBe 0
            }
        }
    }

    "10.5 POST /api/v1/posts with HTTP 503 → 201 + no Layer 3 DB writes (fail-open)" {
        val (_, token) = seedUserAndIssue()
        val client =
            RecordingModerationClient().apply {
                stageException(ModerationHttpException(HttpStatusCode.ServiceUnavailable, body = ""))
            }

        withPostApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"503 content","latitude":-6.2,"longitude":106.8}""")
                }
            // Layer 3 is fire-and-forget — the 503 from Perspective does NOT bubble up to
            // the user; the post still creates successfully at 201.
            resp.status shouldBe HttpStatusCode.Created
            val postId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isAutoHidden(dataSource, postId) shouldBe false
                countPostQueueRows(dataSource, postId, "perspective_api_high_score") shouldBe 0
            }
        }
    }

    "10.6 POST /api/v1/posts with 600ms Perspective delay → 201 + timeout fail-open" {
        val (_, token) = seedUserAndIssue()
        // Stage a 600ms delay — exceeds the orchestrator's 500ms withTimeoutOrNull budget.
        val client = RecordingModerationClient().apply { stageDelay(delayMillis = 600L) }

        withPostApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"slow Perspective","latitude":-6.2,"longitude":106.8}""")
                }
            // Even though Perspective is slow, the HTTP request returns 201 immediately
            // — Layer 3 is fire-and-forget. The orchestrator's withTimeoutOrNull(500.ms)
            // ensures the dispatch returns NoAction within the budget.
            resp.status shouldBe HttpStatusCode.Created
            val postId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isAutoHidden(dataSource, postId) shouldBe false
                countPostQueueRows(dataSource, postId, "perspective_api_high_score") shouldBe 0
            }
        }
    }

    "10.7 POST /api/v1/posts with kill-switch OFF → Perspective NOT invoked" {
        val (_, token) = seedUserAndIssue()
        // Stage a HIGH score that would AutoHide IF invoked. With kill-switch off,
        // the orchestrator must short-circuit before calling the client.
        val client = RecordingModerationClient().apply { stageMaxScore(0.92) }

        withPostApp(client, isKillSwitchEnabled = false) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"would auto-hide if enabled","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val postId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                client.callCount shouldBe 0
                isAutoHidden(dataSource, postId) shouldBe false
                countPostQueueRows(dataSource, postId, "perspective_api_high_score") shouldBe 0
            }
        }
    }

    // ---- REPLY scenarios (task 10.8 — same shape against POST /replies) -----

    "10.8 POST /api/v1/posts/{post_id}/replies at score 0.92 → 201 + post_replies.is_auto_hidden + queue row" {
        val (replierId, replierToken) = seedUserAndIssue()
        val (parentAuthorId, _) = seedUserAndIssue()
        val parentPostId = seedParentPost(parentAuthorId)
        val client = RecordingModerationClient().apply { stageMaxScore(0.92) }

        withReplyApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"high score reply"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val replyId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isReplyAutoHidden(dataSource, replyId) shouldBe true
                countReplyQueueRows(dataSource, replyId, "perspective_api_high_score") shouldBe 1
                // Reply by replierId, not the parent post author.
                replyAuthor(dataSource, replyId) shouldBe replierId
            }
        }
    }

    "10.8 POST /api/v1/posts/{post_id}/replies at score 0.70 → 201 + no flip + queue row" {
        val (_, replierToken) = seedUserAndIssue()
        val (parentAuthorId, _) = seedUserAndIssue()
        val parentPostId = seedParentPost(parentAuthorId)
        val client = RecordingModerationClient().apply { stageMaxScore(0.70) }

        withReplyApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"mid score reply"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val replyId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isReplyAutoHidden(dataSource, replyId) shouldBe false
                countReplyQueueRows(dataSource, replyId, "perspective_api_high_score") shouldBe 1
            }
        }
    }

    "10.8 POST /api/v1/posts/{post_id}/replies at score 0.50 → 201 + no Layer 3 DB writes" {
        val (_, replierToken) = seedUserAndIssue()
        val (parentAuthorId, _) = seedUserAndIssue()
        val parentPostId = seedParentPost(parentAuthorId)
        val client = RecordingModerationClient().apply { stageMaxScore(0.50) }

        withReplyApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"low score reply"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val replyId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isReplyAutoHidden(dataSource, replyId) shouldBe false
                countReplyQueueRows(dataSource, replyId, "perspective_api_high_score") shouldBe 0
            }
        }
    }

    "10.8 POST /api/v1/posts/{post_id}/replies with 600ms Perspective delay → 201 + timeout fail-open" {
        val (_, replierToken) = seedUserAndIssue()
        val (parentAuthorId, _) = seedUserAndIssue()
        val parentPostId = seedParentPost(parentAuthorId)
        val client = RecordingModerationClient().apply { stageDelay(delayMillis = 600L) }

        withReplyApp(client) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"slow Perspective reply"}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val replyId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                isReplyAutoHidden(dataSource, replyId) shouldBe false
                countReplyQueueRows(dataSource, replyId, "perspective_api_high_score") shouldBe 0
            }
        }
    }

    "10.8 POST /api/v1/posts/{post_id}/replies with kill-switch OFF → Perspective NOT invoked" {
        val (_, replierToken) = seedUserAndIssue()
        val (parentAuthorId, _) = seedUserAndIssue()
        val parentPostId = seedParentPost(parentAuthorId)
        val client = RecordingModerationClient().apply { stageMaxScore(0.92) }

        withReplyApp(client, isKillSwitchEnabled = false) {
            val httpClient = testBuilder.createClient { install(ClientCN) { json() } }
            val resp =
                httpClient.post("/api/v1/posts/$parentPostId/replies") {
                    header(HttpHeaders.Authorization, "Bearer $replierToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"would auto-hide if enabled","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val replyId = postIdFromBody(resp.bodyAsText())
            drainAndAssert {
                client.callCount shouldBe 0
                isReplyAutoHidden(dataSource, replyId) shouldBe false
                countReplyQueueRows(dataSource, replyId, "perspective_api_high_score") shouldBe 0
            }
        }
    }
})

// ---- Helpers ----------------------------------------------------------------

private class HttpScenarioContext(
    val testBuilder: io.ktor.server.testing.ApplicationTestBuilder,
    private val dispatcher: Layer3DispatcherScope,
) {
    /**
     * Drains the per-scenario dispatcher so the async Layer 3 dispatch completes
     * before the assertion runs. Mirrors production's JVM-shutdown-hook drain
     * semantics — `shutdown(5000)` cancel-and-joins with a 5s budget; in-flight
     * dispatches within the budget complete normally.
     */
    suspend fun drainAndAssert(block: () -> Unit) {
        dispatcher.shutdown(drainMillis = 5_000L)
        block()
    }
}

private fun postIdFromBody(body: String): UUID {
    val parsed = Json.parseToJsonElement(body).jsonObject
    return UUID.fromString(parsed["id"]!!.jsonPrimitive.content)
}

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

private fun isReplyAutoHidden(
    dataSource: DataSource,
    replyId: UUID,
): Boolean {
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT is_auto_hidden FROM post_replies WHERE id = ?").use { ps ->
            ps.setObject(1, replyId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return false
                return rs.getBoolean("is_auto_hidden")
            }
        }
    }
}

private fun replyAuthor(
    dataSource: DataSource,
    replyId: UUID,
): UUID? {
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT author_id FROM post_replies WHERE id = ?").use { ps ->
            ps.setObject(1, replyId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getObject("author_id", UUID::class.java) else null
            }
        }
    }
}

private fun countPostQueueRows(
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

private fun countReplyQueueRows(
    dataSource: DataSource,
    targetId: UUID,
    trigger: String,
): Int {
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT count(*) FROM moderation_queue WHERE target_type = 'reply' AND target_id = ? AND trigger = ?",
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
