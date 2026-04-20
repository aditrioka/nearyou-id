package id.nearyou.app.post

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.guard.ContentLengthGuard
import id.nearyou.app.infra.repo.JdbcPostRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.sql.SQLException
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    val config =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class CreatePostServiceTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-post")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val posts = JdbcPostRepository()
    // Deterministic 32-byte secret for test runs.
    val jitterSecret =
        Base64.getDecoder()
            .decode("00000000000000000000000000000000000000000000")
            .copyOf(32)
    val contentGuard = ContentLengthGuard(mapOf("post.content" to 280))
    val service =
        CreatePostService(
            dataSource = dataSource,
            posts = posts,
            contentGuard = contentGuard,
            jitterSecret = jitterSecret,
        )

    // Seed a disposable user row and return its id + access token.
    fun seedUserAndIssue(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                val short = id.toString().replace("-", "").take(8)
                ps.setString(2, "pt_$short")
                ps.setString(3, "Post Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1995, 3, 14)))
                ps.setString(5, "p${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun cleanup(userId: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM posts WHERE author_id = '$userId'")
                st.executeUpdate("DELETE FROM refresh_tokens WHERE user_id = '$userId'")
                st.executeUpdate("DELETE FROM users WHERE id = '$userId'")
            }
        }
    }

    suspend fun withPosts(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                    exception<id.nearyou.app.guard.ContentEmptyException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to
                                    mapOf("code" to "content_empty", "message" to "empty"),
                            ),
                        )
                    }
                    exception<id.nearyou.app.guard.ContentTooLongException> { call, cause ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to
                                    mapOf("code" to "content_too_long", "message" to "limit=${cause.limit}"),
                            ),
                        )
                    }
                    exception<LocationOutOfBoundsException> { call, _ ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to mapOf("code" to "location_out_of_bounds", "message" to "envelope"),
                            ),
                        )
                    }
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                postRoutes(service)
            }
            block()
        }
    }

    "happy path — 201 + both geographies populated, distance in [50, 500] m" {
        val (userId, token) = seedUserAndIssue()
        try {
            withPosts {
                val client = createClient { install(ClientCN) { json() } }
                val resp =
                    client.post("/api/v1/posts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"Halo Jakarta","latitude":-6.2,"longitude":106.8}""")
                    }
                resp.status shouldBe HttpStatusCode.Created
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val postId = body["id"]!!.jsonPrimitive.content
                body["content"]!!.jsonPrimitive.content shouldBe "Halo Jakarta"
                body["latitude"]!!.jsonPrimitive.double shouldBe -6.2
                body["longitude"]!!.jsonPrimitive.double shouldBe 106.8
                body["distance_m"] shouldBe JsonNull
                body["created_at"]!!.jsonPrimitive.contentOrNull.shouldNotBeNull()
                // Verify display vs actual differ + fall inside the band.
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        """
                        SELECT ST_Distance(actual_location::geometry, display_location::geometry) AS d,
                               ST_AsText(actual_location) AS a,
                               ST_AsText(display_location) AS dp
                        FROM posts WHERE id = ?::uuid
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, postId)
                        ps.executeQuery().use { rs ->
                            rs.next() shouldBe true
                            val d = rs.getDouble("d")
                            // Approximation: PostGIS ST_Distance on geometry returns degrees, not meters.
                            // Use ::geography for meters. Re-query via geography instead:
                        }
                    }
                    // Geography distance = meters.
                    conn.prepareStatement(
                        "SELECT ST_Distance(actual_location, display_location) FROM posts WHERE id = ?::uuid",
                    ).use { ps ->
                        ps.setString(1, postId)
                        ps.executeQuery().use { rs ->
                            rs.next() shouldBe true
                            val meters = rs.getDouble(1)
                            meters shouldBeGreaterThanOrEqual 50.0
                            meters shouldBeLessThanOrEqual 500.0
                        }
                    }
                    conn.prepareStatement(
                        "SELECT ST_AsText(actual_location) = ST_AsText(display_location) FROM posts WHERE id = ?::uuid",
                    ).use { ps ->
                        ps.setString(1, postId)
                        ps.executeQuery().use { rs ->
                            rs.next() shouldBe true
                            rs.getBoolean(1) shouldBe false
                        }
                    }
                }
            }
        } finally {
            cleanup(userId)
        }
    }

    "content length — 280 ok, 281 rejected, empty rejected, whitespace rejected" {
        val (userId, token) = seedUserAndIssue()
        try {
            withPosts {
                val client = createClient { install(ClientCN) { json() } }
                // 280 ok.
                val ok280 =
                    client.post("/api/v1/posts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"content":"${"a".repeat(280)}","latitude":-6.2,"longitude":106.8}""",
                        )
                    }
                ok280.status shouldBe HttpStatusCode.Created
                // 281 rejected.
                val tooLong =
                    client.post("/api/v1/posts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"content":"${"a".repeat(281)}","latitude":-6.2,"longitude":106.8}""",
                        )
                    }
                tooLong.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(tooLong.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "content_too_long"
                // Empty rejected.
                val empty =
                    client.post("/api/v1/posts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"","latitude":-6.2,"longitude":106.8}""")
                    }
                empty.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(empty.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "content_empty"
                // Whitespace-only rejected.
                val ws =
                    client.post("/api/v1/posts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"   ","latitude":-6.2,"longitude":106.8}""")
                    }
                ws.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(ws.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "content_empty"
            }
        } finally {
            cleanup(userId)
        }
    }

    "coord bounds — Jakarta ok, New York rejected" {
        val (userId, token) = seedUserAndIssue()
        try {
            withPosts {
                val client = createClient { install(ClientCN) { json() } }
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"OK","latitude":-6.2,"longitude":106.8}""")
                }.status shouldBe HttpStatusCode.Created

                val ny =
                    client.post("/api/v1/posts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"NY","latitude":40.0,"longitude":-74.0}""")
                    }
                ny.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(ny.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "location_out_of_bounds"
            }
        } finally {
            cleanup(userId)
        }
    }

    "auth — missing JWT returns 401" {
        withPosts {
            val client = createClient { install(ClientCN) { json() } }
            val resp =
                client.post("/api/v1/posts") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"x","latitude":-6.2,"longitude":106.8}""")
                }
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "auth — stale token_version returns 401" {
        val (userId, _) = seedUserAndIssue()
        try {
            // Bump the DB token_version out from under the already-issued token.
            users.incrementTokenVersion(userId)
            val staleToken = jwtIssuer.issueAccessToken(userId, tokenVersion = 0)
            withPosts {
                val client = createClient { install(ClientCN) { json() } }
                val resp =
                    client.post("/api/v1/posts") {
                        header(HttpHeaders.Authorization, "Bearer $staleToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"stale","latitude":-6.2,"longitude":106.8}""")
                    }
                resp.status shouldBe HttpStatusCode.Unauthorized
            }
        } finally {
            cleanup(userId)
        }
    }

    "FK RESTRICT — user hard-delete blocked while posts exist, succeeds after posts cleared" {
        val (userId, token) = seedUserAndIssue()
        try {
            withPosts {
                val client = createClient { install(ClientCN) { json() } }
                client.post("/api/v1/posts") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"restrict","latitude":-6.2,"longitude":106.8}""")
                }.status shouldBe HttpStatusCode.Created
            }
            // Direct DELETE of the user row should fail with 23503.
            var failed = false
            try {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { st ->
                        st.executeUpdate("DELETE FROM users WHERE id = '$userId'")
                    }
                }
            } catch (ex: SQLException) {
                failed = true
                ex.sqlState shouldBe "23503"
            }
            failed shouldBe true
            // Clearing posts first should let the user delete succeed.
            dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$userId'")
                    val rows = st.executeUpdate("DELETE FROM users WHERE id = '$userId'")
                    rows shouldBe 1
                }
            }
        } finally {
            cleanup(userId) // idempotent — no-op if the test already cleared
        }
    }

    "response body — exact six keys + distance_m is null" {
        val (userId, token) = seedUserAndIssue()
        try {
            withPosts {
                val client = createClient { install(ClientCN) { json() } }
                val resp =
                    client.post("/api/v1/posts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"content":"keys","latitude":-6.2,"longitude":106.8}""")
                    }
                resp.status shouldBe HttpStatusCode.Created
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                body.keys shouldBe setOf("id", "content", "latitude", "longitude", "distance_m", "created_at")
                body["distance_m"] shouldBe JsonNull
            }
        } finally {
            cleanup(userId)
        }
    }
})
