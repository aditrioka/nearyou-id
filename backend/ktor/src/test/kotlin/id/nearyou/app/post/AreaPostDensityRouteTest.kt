package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.moderation.ModerationList
import id.nearyou.app.moderation.ModerationListLoader
import id.nearyou.app.moderation.TextModerator
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Route-level guard for `post-area-density-cap` task 5.3 — an area flag does NOT
 * change the `POST /api/v1/posts` wire shape: the flagged response is the same
 * canonical `201` body field set as an unflagged create, with no error code and
 * no `Retry-After` header (the area gate is a silent soft-flag, NOT the 429 the
 * daily cap returns). A small injected threshold (2) trips the gate on the 3rd
 * post; the poster is Premium so the 10/day daily cap never interferes.
 */
@Tags("database")
class AreaPostDensityRouteTest : StringSpec({

    val dataSource = routeHikari()
    afterSpec { dataSource.close() }

    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-area-post")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val posts = JdbcPostRepository()
    val moderationQueue = JdbcModerationQueueRepository()
    val jitterSecret =
        Base64.getDecoder().decode("00000000000000000000000000000000000000000000").copyOf(32)
    val contentGuard = ContentLengthGuard(mapOf("post.content" to 280))

    val seeded = mutableListOf<UUID>()
    afterEach {
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            seeded.forEach { id ->
                conn.prepareStatement(
                    "DELETE FROM moderation_queue WHERE target_id IN (SELECT id FROM posts WHERE author_id = ?)",
                ).use { ps ->
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
        seeded.clear()
    }

    fun seedPremiumUserAndIssue(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix, subscription_status)
                VALUES (?, ?, 'Area Route Tester', ?, ?, 'premium_active')
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "art_$short")
                ps.setDate(3, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(4, "p${short.take(7)}")
                ps.executeUpdate()
            }
        }
        seeded.add(id)
        return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
    }

    fun service(): CreatePostService =
        CreatePostService(
            dataSource = dataSource,
            posts = posts,
            contentGuard = contentGuard,
            textModerator = TextModerator(AllowAllLoader),
            moderationQueue = moderationQueue,
            jitterSecret = jitterSecret,
            areaDensityLimiter = AreaPostDensityLimiter(rateLimiter = InMemoryRateLimiter(), threshold = 2),
        )

    "area flag keeps the 201 wire shape identical — no error code, no Retry-After" {
        val (_, token) = seedPremiumUserAndIssue()
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
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                postRoutes(service())
            }
            val client = createClient { install(ClientCN) { json() } }

            suspend fun create(n: Int) =
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    // Deep-Indian-Ocean cell-center coordinate (in-envelope, out-of-band).
                    setBody("""{"content":"area route $n","latitude":-10.5,"longitude":105.0}""")
                }

            // Posts 1 and 2 are clean (within threshold=2); the 3rd trips the gate.
            val clean = create(1)
            create(2)
            val flagged = create(3)

            clean.status shouldBe HttpStatusCode.Created
            flagged.status shouldBe HttpStatusCode.Created

            // Identical canonical field set on both clean and flagged responses.
            val expectedKeys = setOf("id", "content", "latitude", "longitude", "distance_m", "created_at")
            val cleanObj = Json.parseToJsonElement(clean.bodyAsText()).jsonObject
            val flaggedObj = Json.parseToJsonElement(flagged.bodyAsText()).jsonObject
            cleanObj.keys shouldBe expectedKeys
            flaggedObj.keys shouldBe expectedKeys

            // No error envelope, no Retry-After on the flagged response.
            flaggedObj.containsKey("error") shouldBe false
            flagged.headers[HttpHeaders.RetryAfter] shouldBe null

            // The 3rd post DID get the silent area_spam queue row.
            val flaggedId = UUID.fromString(flaggedObj["id"]!!.jsonPrimitive.content)
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM moderation_queue WHERE target_id = ? AND trigger = 'area_spam'",
                ).use { ps ->
                    ps.setObject(1, flaggedId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        }
    }
})

private object AllowAllLoader : ModerationListLoader {
    override fun load(list: ModerationList): List<String> = emptyList()

    override fun loadThreshold(): Int = 3
}

private fun routeHikari(): HikariDataSource {
    val config =
        HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2
        }
    return HikariDataSource(config)
}
