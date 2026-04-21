package id.nearyou.app.timeline

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcPostsTimelineRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.post.LocationOutOfBoundsException
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.time.LocalDate
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
class NearbyTimelineServiceTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-timeline")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val timeline = JdbcPostsTimelineRepository(dataSource)
    val service = NearbyTimelineService(timeline)

    fun seedUser(): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "tl_$short")
                ps.setString(3, "Timeline Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "t${short.take(7)}")
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun seedPost(
        authorId: UUID,
        lat: Double,
        lng: Double,
        autoHidden: Boolean = false,
        content: String = "post-${UUID.randomUUID().toString().take(6)}",
    ): UUID {
        val id = UUID.randomUUID()
        // Tests insert directly into posts (NOT via the Nearby read path) so display ==
        // actual is fine — the read query returns ST_Y/ST_X(display_location) which we
        // then assert against. This file is a test fixture; it bypasses the lint rule
        // by sitting under src/test/.
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO posts (id, author_id, content, display_location, actual_location, is_auto_hidden)
                VALUES (?, ?, ?,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                  ?)
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
                ps.executeUpdate()
            }
        }
        return id
    }

    fun cleanup(vararg userIds: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                userIds.forEach {
                    st.executeUpdate("DELETE FROM posts WHERE author_id = '$it'")
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withTimeline(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                timelineRoutes(service)
            }
            block()
        }
    }

    "happy path — Jakarta viewer sees nearby posts ordered DESC by created_at" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            // Three posts ~1km from Jakarta center; tiny created_at staggering.
            val p1 = seedPost(author, -6.200, 106.800)
            Thread.sleep(10)
            val p2 = seedPost(author, -6.205, 106.805)
            Thread.sleep(10)
            val p3 = seedPost(author, -6.210, 106.810)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val ids = body["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(p3.toString(), p2.toString(), p1.toString())
                // Each post item carries the documented seven keys.
                val first = body["posts"]!!.jsonArray.first().jsonObject
                first.keys shouldBe setOf("id", "authorUserId", "content", "latitude", "longitude", "distanceM", "createdAt")
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "cursor pagination — page 1 of 30, page 2 of remaining" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            // 35 posts within radius
            for (i in 0 until 35) {
                seedPost(author, -6.200 + i * 0.0001, 106.800)
                Thread.sleep(2)
            }
            withTimeline {
                val client = createClient { install(ClientCN) { json() } }
                val r1 =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                r1.status shouldBe HttpStatusCode.OK
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                b1["posts"]!!.jsonArray shouldHaveSize 30
                val cursor = b1["nextCursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000&cursor=$cursor") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                r2.status shouldBe HttpStatusCode.OK
                val b2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject
                b2["posts"]!!.jsonArray shouldHaveSize 5
                // No overlap.
                val ids1 = b1["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                val ids2 = b2["posts"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }.toSet()
                (ids1 intersect ids2).isEmpty() shouldBe true
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "radius filter — post outside radius excluded" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val near = seedPost(author, -6.201, 106.801) // ~150m from -6.2,106.8
            val far = seedPost(author, -7.000, 107.500) // ~110km
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(near.toString())
                ids.contains(far.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "auto-hidden post excluded (visible_posts view filters is_auto_hidden = TRUE)" {
        val (viewer, vt) = seedUser()
        val (author, _) = seedUser()
        try {
            val visible = seedPost(author, -6.200, 106.800)
            val hidden = seedPost(author, -6.201, 106.801, autoHidden = true)
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                val ids = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids shouldBe listOf(visible.toString())
                ids.contains(hidden.toString()) shouldBe false
            }
        } finally {
            cleanup(viewer, author)
        }
    }

    "bidirectional block exclusion — A blocked B (viewer = A): B's post hidden" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            val bp = seedPost(b, -6.200, 106.800)
            // A blocks B
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, a)
                    ps.setObject(2, b)
                    ps.executeUpdate()
                }
            }
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }
                val ids = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(bp.toString()) shouldBe false
            }
        } finally {
            cleanup(a, b)
        }
    }

    "bidirectional block exclusion — B blocked A (viewer = A): B's post hidden" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            val bp = seedPost(b, -6.200, 106.800)
            // B blocks A — viewer A should still NOT see B's posts.
            dataSource.connection.use { conn ->
                conn.prepareStatement("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)").use { ps ->
                    ps.setObject(1, b)
                    ps.setObject(2, a)
                    ps.executeUpdate()
                }
            }
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=5000") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }
                val ids = Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["posts"]!!.jsonArray
                    .map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
                ids.contains(bp.toString()) shouldBe false
            }
        } finally {
            cleanup(a, b)
        }
    }

    "out-of-envelope coordinates — 400 location_out_of_bounds (NY)" {
        val (viewer, vt) = seedUser()
        try {
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=40.0&lng=-74.0&radius_m=1000") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "location_out_of_bounds"
            }
        } finally {
            cleanup(viewer)
        }
    }

    "radius bounds — 50 (too small) and 100000 (too large) rejected; 100 and 50000 accepted" {
        val (viewer, vt) = seedUser()
        try {
            withTimeline {
                val client = createClient { install(ClientCN) { json() } }
                val tooSmall =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=50") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                tooSmall.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(tooSmall.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "radius_out_of_bounds"
                val tooLarge =
                    client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=100000") {
                        header(HttpHeaders.Authorization, "Bearer $vt")
                    }
                tooLarge.status shouldBe HttpStatusCode.BadRequest
                client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=100") {
                    header(HttpHeaders.Authorization, "Bearer $vt")
                }.status shouldBe HttpStatusCode.OK
                client.get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=50000") {
                    header(HttpHeaders.Authorization, "Bearer $vt")
                }.status shouldBe HttpStatusCode.OK
            }
        } finally {
            cleanup(viewer)
        }
    }

    "auth required — 401 without JWT" {
        withTimeline {
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000")
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "invalid cursor — 400 invalid_cursor" {
        val (viewer, vt) = seedUser()
        try {
            withTimeline {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/timeline/nearby?lat=-6.2&lng=106.8&radius_m=1000&cursor=not-a-real-cursor") {
                            header(HttpHeaders.Authorization, "Bearer $vt")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_cursor"
            }
        } finally {
            cleanup(viewer)
        }
    }
})
