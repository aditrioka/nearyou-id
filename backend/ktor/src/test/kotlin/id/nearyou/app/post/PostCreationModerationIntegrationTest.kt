package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.TextModerator
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        },
    )
}

/**
 * Integration tests for `content-moderation-keyword-lists` POST /api/v1/posts wiring.
 * Verifies all 7 scenarios from `specs/post-creation/spec.md`:
 *  1. Profanity → 400 + no INSERT.
 *  2. UU ITE flag → 201 + queue row + `is_auto_hidden=FALSE`.
 *  3. UU ITE below threshold → 201 + no queue row.
 *  4. Allowed → 201 + no queue row.
 *  5. Reject body keyword leak.
 *  6. Idempotent retry → single queue row.
 *  7. Oversized payload short-circuits before moderator runs (mock-spy).
 *
 * Uses real Postgres (Hikari + V9 schema) but a fixture [FixedListLoader] for
 * the moderation lists so test sentinels are deterministic.
 */
@Tags("database")
class PostCreationModerationIntegrationTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-mod-post")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val posts = JdbcPostRepository()
    val moderationQueue = JdbcModerationQueueRepository()
    val jitterSecret =
        Base64.getDecoder()
            .decode("00000000000000000000000000000000000000000000")
            .copyOf(32)
    val contentGuard = ContentLengthGuard(mapOf("post.content" to 280))

    afterSpec {
        // Clean up all rows seeded by this spec (users prefixed `mt_*`, plus their
        // dependent posts + moderation_queue rows). Avoids polluting timeline tests
        // which query the same Jakarta coords. The `LIKE 'mt\_%'` pattern uses
        // backslash-escape (note the embedded literal backslash in the Kotlin
        // raw-string-of-triple-quote'd region) so the underscore is treated as a
        // literal char and not a wildcard. We avoid the SQL ESCAPE clause because
        // PostgreSQL interprets the JDBC layer's backslash-handling differently
        // across drivers; selecting only by prefix is sufficient since `mt_*` is
        // the only username pattern this spec creates.
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    DELETE FROM moderation_queue WHERE target_id IN
                      (SELECT id FROM posts WHERE author_id IN
                        (SELECT id FROM users WHERE username LIKE 'mt!_%' ESCAPE '!'))
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    DELETE FROM posts WHERE author_id IN
                      (SELECT id FROM users WHERE username LIKE 'mt!_%' ESCAPE '!')
                    """.trimIndent(),
                )
                st.executeUpdate("DELETE FROM users WHERE username LIKE 'mt!_%' ESCAPE '!'")
            }
        }
    }

    fun newService(loader: ModerationListLoader): CreatePostService =
        CreatePostService(
            dataSource = dataSource,
            posts = posts,
            contentGuard = contentGuard,
            textModerator = TextModerator(loader),
            moderationQueue = moderationQueue,
            jitterSecret = jitterSecret,
        )

    fun seedUserAndIssue(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                val short = id.toString().replace("-", "").take(8)
                ps.setString(2, "mt_$short")
                ps.setString(3, "Mod Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "m${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    suspend fun withApp(
        loader: ModerationListLoader,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) {
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
                            mapOf(
                                "error" to
                                    mapOf(
                                        "code" to "content_moderated_profanity",
                                        "message" to "Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi.",
                                    ),
                            ),
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                postRoutes(newService(loader))
            }
            block()
        }
    }

    fun queueRowCount(
        targetId: UUID,
        trigger: String,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM moderation_queue WHERE target_type = 'post' AND target_id = ? AND trigger = ?",
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

    fun postRowCount(authorId: UUID): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM posts WHERE author_id = ?").use { ps ->
                ps.setObject(1, authorId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    "Profanity hit → 400, no posts row, no queue row" {
        val (userId, token) = seedUserAndIssue()
        val loader =
            FixedListLoader(profanity = listOf("sentinelbadword"), uuIte = listOf("sentinelsara"), threshold = 3)

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"this is a sentinelbadword test","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "content_moderated_profanity"
            body["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldBe
                "Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."
            postRowCount(userId) shouldBe 0
        }
    }

    "UU ITE flag at threshold → 201 + queue row + is_auto_hidden=FALSE" {
        val (userId, token) = seedUserAndIssue()
        val loader =
            FixedListLoader(
                profanity = emptyList(),
                uuIte = listOf("sentsara1", "sentsara2", "sentsara3"),
                threshold = 3,
            )

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"content":"perdebatan tentang sentsara1 dan sentsara2 dan sentsara3","latitude":-6.2,"longitude":106.8}""",
                    )
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val postId = UUID.fromString(body["id"]!!.jsonPrimitive.content)

            queueRowCount(postId, "uu_ite_keyword_match") shouldBe 1

            // is_auto_hidden defaults FALSE (not set by the Layer 2 path).
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT is_auto_hidden FROM posts WHERE id = ?").use { ps ->
                    ps.setObject(1, postId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean(1) shouldBe false
                    }
                }
            }
            postRowCount(userId) shouldBe 1
        }
    }

    "UU ITE below threshold → 201, no queue row" {
        val (userId, token) = seedUserAndIssue()
        val loader =
            FixedListLoader(
                profanity = emptyList(),
                uuIte = listOf("sentsara1", "sentsara2", "sentsara3"),
                threshold = 3,
            )

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"saya membahas sentsara1 saja","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val postId = UUID.fromString(body["id"]!!.jsonPrimitive.content)
            queueRowCount(postId, "uu_ite_keyword_match") shouldBe 0
            postRowCount(userId) shouldBe 1
        }
    }

    "Allowed content → 201, no queue row" {
        val (userId, token) = seedUserAndIssue()
        val loader =
            FixedListLoader(
                profanity = listOf("sentinelbadword"),
                uuIte = listOf("sentsara1"),
                threshold = 3,
            )

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"halo dunia ini post yang baik","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.Created
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val postId = UUID.fromString(body["id"]!!.jsonPrimitive.content)
            queueRowCount(postId, "uu_ite_keyword_match") shouldBe 0
            queueRowCount(postId, "auto_hide_3_reports") shouldBe 0
            postRowCount(userId) shouldBe 1
        }
    }

    "Reject response body does not leak matched keywords" {
        val (_, token) = seedUserAndIssue()
        val loader =
            FixedListLoader(
                profanity = listOf("sentbadword1", "sentbadword2"),
                uuIte = emptyList(),
                threshold = 3,
            )

        withApp(loader) {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"content":"text with sentbadword1 and sentbadword2","latitude":-6.2,"longitude":106.8}""",
                    )
                }
            resp.status shouldBe HttpStatusCode.BadRequest
            val bodyText = resp.bodyAsText()
            bodyText.shouldNotContain("sentbadword1")
            bodyText.shouldNotContain("sentbadword2")
            // Confirm the canonical envelope IS present.
            bodyText.shouldContain("content_moderated_profanity")
        }
    }

    "Idempotent retry on Flag preserves single moderation_queue row" {
        // The retry case with the SAME target post can't happen via the public API
        // (each POST generates a new UUIDv7), so the idempotency we verify is the
        // INSERT path's `ON CONFLICT (target_type, target_id, trigger) DO NOTHING`
        // contract. We exercise it by directly calling the repo twice with the same
        // tuple — the second call returns false (no-op).
        val (userId, _) = seedUserAndIssue()
        val postId = UUID.randomUUID()
        // Seed a posts row so the FK is satisfiable (target_id references posts.id).
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location)
                VALUES (?, ?, 'idempotency-test',
                    ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, postId)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
            val first =
                moderationQueue.upsertUuIteKeywordMatchRow(
                    conn,
                    id.nearyou.data.repository.ReportTargetType.POST,
                    postId,
                )
            val second =
                moderationQueue.upsertUuIteKeywordMatchRow(
                    conn,
                    id.nearyou.data.repository.ReportTargetType.POST,
                    postId,
                )
            first shouldBe true
            second shouldBe false
        }
        queueRowCount(postId, "uu_ite_keyword_match") shouldBe 1
    }

    "Oversized payload short-circuits before moderator runs (no Reject mapping)" {
        val (_, token) = seedUserAndIssue()
        // Even with a profanity sentinel in the loader, an oversized payload should
        // be rejected by the length guard with `content_too_long`, NOT by the moderator
        // with `content_moderated_profanity`. Asserts ordering: length BEFORE moderator.
        val recordingLoader =
            object : ModerationListLoader {
                var moderateInvocationsViaProfanity = 0
                var moderateInvocationsViaUuIte = 0

                override fun load(list: ModerationList): List<String> {
                    when (list) {
                        ModerationList.ProfanityList -> moderateInvocationsViaProfanity += 1
                        ModerationList.UuIteList -> moderateInvocationsViaUuIte += 1
                    }
                    return when (list) {
                        ModerationList.ProfanityList -> listOf("sentinelbadword")
                        ModerationList.UuIteList -> emptyList()
                    }
                }

                override fun loadThreshold(): Int = 3
            }
        withApp(recordingLoader) {
            val client = createClient { install(ClientCN) { json() } }
            val oversized = "x".repeat(281)
            val resp =
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"sentinelbadword $oversized","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.BadRequest
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "content_too_long"
            // Moderator was NOT consulted.
            recordingLoader.moderateInvocationsViaProfanity shouldBe 0
            recordingLoader.moderateInvocationsViaUuIte shouldBe 0
        }
    }
})

/**
 * Static-content-aware loader for moderation tests. No I/O, no Redis — returns the
 * provided lists + threshold verbatim.
 */
private class FixedListLoader(
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

/**
 * Static call-order assertion test — verifies the post handler source contains the
 * canonical sequence `contentLengthGuard` → `TextModerator.moderate` → `posts.create`
 * in that order. Mirrors the static-source-scan pattern from
 * `FcmDispatchStructuralTest`.
 */
class PostCreationCallOrderTest : StringSpec({
    "POST /api/v1/posts handler source: length guard → moderator → INSERT call order" {
        val source =
            java.io.File("src/main/kotlin/id/nearyou/app/post/CreatePostService.kt").readText()

        val lengthMarker = source.indexOf("contentGuard.enforce")
        val moderatorMarker = source.indexOf("textModerator.moderate")
        val insertMarker = source.indexOf("posts.create(")

        require(lengthMarker > 0) { "contentGuard.enforce not found in CreatePostService.kt" }
        require(moderatorMarker > 0) { "textModerator.moderate not found in CreatePostService.kt" }
        require(insertMarker > 0) { "posts.create() call not found in CreatePostService.kt" }

        // Ordering check: length BEFORE moderator BEFORE INSERT.
        (lengthMarker < moderatorMarker) shouldBe true
        (moderatorMarker < insertMarker) shouldBe true
    }
})
