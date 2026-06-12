package id.nearyou.app.follow

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcNotificationRepository
import id.nearyou.app.infra.repo.JdbcUserFollowsRepository
import id.nearyou.app.infra.repo.JdbcUserRepository
import id.nearyou.app.notifications.DbNotificationEmitter
import id.nearyou.app.notifications.NoopNotificationDispatcher
import id.nearyou.app.user.JdbcUserProfileReader
import id.nearyou.app.user.UserProfileService
import id.nearyou.app.user.userProfileRoutes
import id.nearyou.data.repository.FollowBlockedException
import id.nearyou.data.repository.FollowListRow
import id.nearyou.data.repository.UserFollowsRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientCN

/**
 * Constant, direction-less 404 body — must be byte-identical across the follow/list
 * endpoints AND the profile route (`user-profile-read` D4; cross-route equality is
 * asserted against the live profile route below, not just this literal).
 */
private const val USER_NOT_FOUND_BODY = """{"error":{"code":"user_not_found"}}"""

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
class FollowEndpointsTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-follow")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val follows = JdbcUserFollowsRepository(dataSource)
    val notificationsRepo = JdbcNotificationRepository(dataSource)
    val dispatcher = NoopNotificationDispatcher()
    val notificationEmitter = DbNotificationEmitter(notificationsRepo)
    val service = FollowService(dataSource, follows, notificationEmitter, dispatcher)

    // Destructures like the old Pair (component1 = id, component2 = token); `username`
    // backs the enriched-row value assertions. Display name stays the shared constant.
    data class Seeded(val id: UUID, val token: String, val username: String)

    fun seedUser(
        subscriptionStatus: String = "free",
        shadowBanned: Boolean = false,
        deleted: Boolean = false,
    ): Seeded {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        val username = "fe_$short"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    subscription_status, is_shadow_banned, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, "Follow Endpoint Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "h${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.setBoolean(7, shadowBanned)
                ps.setTimestamp(8, if (deleted) Timestamp.from(Instant.now()) else null)
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return Seeded(id, token, username)
    }

    // Raw edge insert — bypasses the service visibility gate so fixtures can attach
    // followers/followees to HIDDEN profiles (service.follow would 404 at the gate).
    fun insertFollow(
        follower: UUID,
        followee: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
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

    fun countFollow(
        follower: UUID,
        followee: UUID,
    ): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
            ).use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach {
                    st.executeUpdate("DELETE FROM users WHERE id = '$it'")
                }
            }
        }
    }

    suspend fun withFollows(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
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
                followRoutes(service)
                userSocialRoutes(service)
                // Same dataSource (no new pool — CI connection budget): the live profile
                // route backs the cross-route byte-identical-404 assertions.
                userProfileRoutes(UserProfileService(JdbcUserProfileReader(dataSource)))
            }
            block()
        }
    }

    "POST /follows/{B} first follow returns 204 and creates a row" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 1
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows/{B} re-follow is idempotent (still 204, still one row)" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                client.post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                    .status shouldBe HttpStatusCode.NoContent
                client.post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                    .status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 1
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows/{self} returns 400 cannot_follow_self and does not insert" {
        val (a, ta) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$a") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "cannot_follow_self"
            }
            countFollow(a, a) shouldBe 0
        } finally {
            cleanup(a)
        }
    }

    "POST /follows/{nonexistent} returns 404 user_not_found" {
        val (a, ta) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/${UUID.randomUUID()}") {
                            header(HttpHeaders.Authorization, "Bearer $ta")
                        }
                resp.status shouldBe HttpStatusCode.NotFound
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "user_not_found"
            }
        } finally {
            cleanup(a)
        }
    }

    "POST /follows/{B} returns constant 404 when caller has blocked target (no edge)" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            insertBlock(a, b)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NotFound
                resp.bodyAsText() shouldBe USER_NOT_FOUND_BODY
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows/{B} returns constant 404 when target has blocked caller (no edge)" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            insertBlock(b, a)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NotFound
                resp.bodyAsText() shouldBe USER_NOT_FOUND_BODY
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows/{B} returns constant 404 for a shadow-banned target (no edge)" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser(shadowBanned = true)
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NotFound
                resp.bodyAsText() shouldBe USER_NOT_FOUND_BODY
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows/{B} returns constant 404 for a soft-deleted target (no edge)" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser(deleted = true)
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NotFound
                resp.bodyAsText() shouldBe USER_NOT_FOUND_BODY
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "POST /follows 404 is byte-identical across unknown/hidden/blocked causes AND equals the profile-route 404" {
        val (a, ta) = seedUser()
        val (sb, _) = seedUser(shadowBanned = true)
        val (dl, _) = seedUser(deleted = true)
        val (cb, _) = seedUser() // caller-blocked target
        val (tb, _) = seedUser() // target-blocked-caller
        try {
            insertBlock(a, cb)
            insertBlock(tb, a)
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                val targets = listOf(UUID.randomUUID(), sb, dl, cb, tb)
                val bodies =
                    targets.map { t ->
                        val resp = client.post("/api/v1/follows/$t") { header(HttpHeaders.Authorization, "Bearer $ta") }
                        resp.status shouldBe HttpStatusCode.NotFound
                        resp.bodyAsText()
                    }
                val profile404 =
                    client.get("/api/v1/users/${UUID.randomUUID()}") {
                        header(HttpHeaders.Authorization, "Bearer $ta")
                    }
                profile404.status shouldBe HttpStatusCode.NotFound
                (bodies + profile404.bodyAsText()).distinct() shouldHaveSize 1
                bodies[0] shouldBe USER_NOT_FOUND_BODY
                // Header parity too: a refactor back to map-serialized respond() would keep
                // the bytes but drift the Content-Type parameters.
                val follow404 = client.post("/api/v1/follows/${UUID.randomUUID()}") { header(HttpHeaders.Authorization, "Bearer $ta") }
                follow404.headers[HttpHeaders.ContentType] shouldBe profile404.headers[HttpHeaders.ContentType]
            }
        } finally {
            cleanup(a, sb, dl, cb, tb)
        }
    }

    "followInTx throws FollowBlockedException when a block row exists (TOCTOU backstop, repo level)" {
        // The service-level visibility gate makes a pre-existing block unreachable at the
        // endpoint, so the in-tx guard is pinned here: gate bypassed, guard still rejects.
        val (a, _) = seedUser()
        val (b, _) = seedUser()
        try {
            insertBlock(a, b)
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    shouldThrow<FollowBlockedException> { follows.followInTx(conn, a, b) }
                } finally {
                    conn.rollback()
                    conn.autoCommit = true
                }
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "FollowBlockedException from the transaction maps to the constant 404 (mid-flight block)" {
        // Fake repo: the gate passes (block lands "after" it), then the in-tx guard
        // throws — the route must answer the same constant 404 as every other cause.
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        val midFlightRepo =
            object : UserFollowsRepository {
                override fun unfollow(
                    follower: UUID,
                    followee: UUID,
                ) = Unit

                override fun listFollowers(
                    profileId: UUID,
                    viewerId: UUID,
                    cursorCreatedAt: Instant?,
                    cursorUserId: UUID?,
                    limit: Int,
                ): List<FollowListRow> = emptyList()

                override fun listFollowing(
                    profileId: UUID,
                    viewerId: UUID,
                    cursorCreatedAt: Instant?,
                    cursorUserId: UUID?,
                    limit: Int,
                ): List<FollowListRow> = emptyList()

                override fun ensureProfileVisible(
                    profileId: UUID,
                    viewerId: UUID,
                ) = Unit // gate passed — the block lands after it

                override fun followInTx(
                    conn: Connection,
                    follower: UUID,
                    followee: UUID,
                ): Boolean = throw FollowBlockedException()
            }
        val midFlightService = FollowService(dataSource, midFlightRepo, notificationEmitter, dispatcher)
        try {
            testApplication {
                application {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                    install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                    followRoutes(midFlightService)
                }
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .post("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NotFound
                resp.bodyAsText() shouldBe USER_NOT_FOUND_BODY
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "DELETE /follows/{B} removes existing row and returns 204" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            service.follow(a, b)
            countFollow(a, b) shouldBe 1
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
            countFollow(a, b) shouldBe 0
        } finally {
            cleanup(a, b)
        }
    }

    "DELETE /follows/{B} no-op still returns 204" {
        val (a, ta) = seedUser()
        val (b, _) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .delete("/api/v1/follows/$b") { header(HttpHeaders.Authorization, "Bearer $ta") }
                resp.status shouldBe HttpStatusCode.NoContent
            }
        } finally {
            cleanup(a, b)
        }
    }

    "GET /users/{P}/followers ordered DESC by created_at, excludes unrelated users" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        val (z, _) = seedUser()
        try {
            service.follow(x, p)
            Thread.sleep(10)
            service.follow(y, p)
            Thread.sleep(10)
            service.follow(z, p) // most recent
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids shouldBe listOf(z.toString(), y.toString(), x.toString())
            }
        } finally {
            cleanup(p, viewer, x, y, z)
        }
    }

    "GET /users/{P}/followers excludes viewer-blocked users in both directions" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        val (z, _) = seedUser()
        try {
            service.follow(x, p)
            service.follow(y, p)
            service.follow(z, p)
            insertBlock(viewer, x) // viewer blocked x
            insertBlock(y, viewer) // y blocked viewer
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids.contains(x.toString()) shouldBe false
                ids.contains(y.toString()) shouldBe false
                ids.contains(z.toString()) shouldBe true
            }
        } finally {
            cleanup(p, viewer, x, y, z)
        }
    }

    "GET /users/{P}/followers rows embed the profile summary — exact camelCase keys, no snake_case, D2 isPremium" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val premium = seedUser(subscriptionStatus = "premium_active")
        val billingRetry = seedUser(subscriptionStatus = "premium_billing_retry")
        try {
            service.follow(premium.id, p)
            service.follow(billingRetry.id, p)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val rows = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["users"]!!.jsonArray
                rows shouldHaveSize 2
                rows.forEach { row ->
                    val keys = (row as JsonObject).keys
                    // Exact camelCase wire — raw JSON keys, not DTO round-trips.
                    keys.containsAll(listOf("userId", "username", "displayName", "isPremium", "createdAt")) shouldBe true
                    listOf("user_id", "display_name", "is_premium", "created_at").any { it in keys } shouldBe false
                }
                val byId = rows.associateBy { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                val premiumRow = byId[premium.id.toString()]!!.jsonObject
                premiumRow["username"]!!.jsonPrimitive.content shouldBe premium.username
                premiumRow["displayName"]!!.jsonPrimitive.content shouldBe "Follow Endpoint Tester"
                premiumRow["isPremium"]!!.jsonPrimitive.boolean shouldBe true
                // premium_billing_retry is NOT premium on this surface (profile-read formula).
                byId[billingRetry.id.toString()]!!.jsonObject["isPremium"]!!.jsonPrimitive.boolean shouldBe false
            }
        } finally {
            cleanup(p, viewer, premium.id, billingRetry.id)
        }
    }

    "GET /users/{P}/followers excludes shadow-banned and soft-deleted members" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (visible, _) = seedUser()
        val (sb, _) = seedUser(shadowBanned = true)
        val (dl, _) = seedUser(deleted = true)
        try {
            insertFollow(visible, p)
            insertFollow(sb, p)
            insertFollow(dl, p)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids shouldBe listOf(visible.toString())
            }
        } finally {
            cleanup(p, viewer, visible, sb, dl)
        }
    }

    "GET /users/{P}/following excludes hidden followees and embeds the summary" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val visible = seedUser()
        val (sb, _) = seedUser(shadowBanned = true)
        val (dl, _) = seedUser(deleted = true)
        try {
            insertFollow(p, visible.id)
            insertFollow(p, sb)
            insertFollow(p, dl)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/following") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                val rows = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["users"]!!.jsonArray
                rows shouldHaveSize 1
                val row = rows[0] as JsonObject
                row["userId"]!!.jsonPrimitive.content shouldBe visible.id.toString()
                row["username"]!!.jsonPrimitive.content shouldBe visible.username
                row["displayName"]!!.jsonPrimitive.content shouldBe "Follow Endpoint Tester"
                ("user_id" in row.keys) shouldBe false
            }
        } finally {
            cleanup(p, viewer, visible.id, sb, dl)
        }
    }

    "GET followers+following answer the constant 404 for every unresolvable target, byte-identical to the profile 404" {
        val (viewer, tv) = seedUser()
        val (sb, _) = seedUser(shadowBanned = true)
        val (dl, _) = seedUser(deleted = true)
        val (cb, _) = seedUser() // viewer blocked this profile
        val (tb, _) = seedUser() // this profile blocked viewer
        try {
            insertBlock(viewer, cb)
            insertBlock(tb, viewer)
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                val targets = listOf(UUID.randomUUID(), sb, dl, cb, tb)
                val bodies = mutableListOf<String>()
                for (endpoint in listOf("followers", "following")) {
                    for (t in targets) {
                        val resp =
                            client.get("/api/v1/users/$t/$endpoint") {
                                header(HttpHeaders.Authorization, "Bearer $tv")
                            }
                        resp.status shouldBe HttpStatusCode.NotFound
                        bodies += resp.bodyAsText()
                    }
                }
                val profile404Resp =
                    client.get("/api/v1/users/${UUID.randomUUID()}") {
                        header(HttpHeaders.Authorization, "Bearer $tv")
                    }
                (bodies + profile404Resp.bodyAsText()).distinct() shouldHaveSize 1
                bodies[0] shouldBe USER_NOT_FOUND_BODY
                // Header parity with the profile route (see the POST differential test).
                val list404 =
                    client.get("/api/v1/users/${UUID.randomUUID()}/followers") {
                        header(HttpHeaders.Authorization, "Bearer $tv")
                    }
                list404.headers[HttpHeaders.ContentType] shouldBe profile404Resp.headers[HttpHeaders.ContentType]
            }
        } finally {
            cleanup(viewer, sb, dl, cb, tb)
        }
    }

    "shadow-banned caller still reads OWN followers and following (200)" {
        val s = seedUser(shadowBanned = true)
        val other = seedUser()
        try {
            insertFollow(other.id, s.id) // other follows the hidden S
            insertFollow(s.id, other.id) // S follows other
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                val followers =
                    client.get("/api/v1/users/${s.id}/followers") {
                        header(HttpHeaders.Authorization, "Bearer ${s.token}")
                    }
                followers.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(followers.bodyAsText())
                    .jsonObject["users"]!!.jsonArray
                    .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                    .contains(other.id.toString()) shouldBe true
                val following =
                    client.get("/api/v1/users/${s.id}/following") {
                        header(HttpHeaders.Authorization, "Bearer ${s.token}")
                    }
                following.status shouldBe HttpStatusCode.OK
            }
        } finally {
            cleanup(s.id, other.id)
        }
    }

    "GET /users/{P}/followers — profile owner sees own followers minus their own blocks" {
        val (p, tp) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        try {
            service.follow(x, p)
            service.follow(y, p)
            insertBlock(p, x) // owner blocked X (block after follow keeps the edge: direct insert)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tp")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids shouldBe listOf(y.toString())
            }
        } finally {
            cleanup(p, x, y)
        }
    }

    "GET /users/{P}/followers — malformed cursor returns 400 invalid_cursor" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/followers?cursor=not-a-base64-json") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.BadRequest
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "invalid_cursor"
            }
        } finally {
            cleanup(p, viewer)
        }
    }

    "GET /users/{P}/followers paginates with cursor (35 followers → 30 + 5)" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val followers = (1..35).map { seedUser().id }
        try {
            for (f in followers) {
                service.follow(f, p)
                Thread.sleep(2)
            }
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                val r1 =
                    client.get("/api/v1/users/$p/followers") {
                        header(HttpHeaders.Authorization, "Bearer $tv")
                    }
                r1.status shouldBe HttpStatusCode.OK
                val b1 = Json.parseToJsonElement(r1.bodyAsText()).jsonObject
                b1["users"]!!.jsonArray shouldHaveSize 30
                val cursor = b1["nextCursor"]!!.jsonPrimitive.content
                val r2 =
                    client.get("/api/v1/users/$p/followers?cursor=$cursor") {
                        header(HttpHeaders.Authorization, "Bearer $tv")
                    }
                r2.status shouldBe HttpStatusCode.OK
                val b2 = Json.parseToJsonElement(r2.bodyAsText()).jsonObject
                b2["users"]!!.jsonArray shouldHaveSize 5
                // Last page (<30 rows) → nextCursor null/absent.
                val nc2 = b2["nextCursor"]
                (nc2 == null || nc2 == kotlinx.serialization.json.JsonNull) shouldBe true
                val ids1 = b1["users"]!!.jsonArray.map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }.toSet()
                val ids2 = b2["users"]!!.jsonArray.map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }.toSet()
                (ids1 intersect ids2).isEmpty() shouldBe true
            }
        } finally {
            cleanup(p, viewer, *followers.toTypedArray())
        }
    }

    "GET /users/{nonexistent}/followers returns 404 user_not_found" {
        val (viewer, tv) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/${UUID.randomUUID()}/followers") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.NotFound
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "user_not_found"
            }
        } finally {
            cleanup(viewer)
        }
    }

    "GET /users/{nonexistent}/following returns 404 user_not_found (previously only /followers was pinned)" {
        val (viewer, tv) = seedUser()
        try {
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/${UUID.randomUUID()}/following") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.NotFound
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["error"]!!.jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe "user_not_found"
            }
        } finally {
            cleanup(viewer)
        }
    }

    "GET followers/following — malformed cursor returns 400 invalid_cursor (spec scenario, previously untested)" {
        val (viewer, tv) = seedUser()
        try {
            withFollows {
                val client = createClient { install(ClientCN) { json() } }
                listOf("followers", "following").forEach { leg ->
                    val resp =
                        client.get("/api/v1/users/$viewer/$leg?cursor=not-a-valid-base64-cursor") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                    resp.status shouldBe HttpStatusCode.BadRequest
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["error"]!!.jsonObject["code"]!!
                        .jsonPrimitive.content shouldBe "invalid_cursor"
                }
            }
        } finally {
            cleanup(viewer)
        }
    }

    "GET /users/{P}/following ordered DESC by created_at" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        val (z, _) = seedUser()
        try {
            service.follow(p, x)
            Thread.sleep(10)
            service.follow(p, y)
            Thread.sleep(10)
            service.follow(p, z)
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/following") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids shouldBe listOf(z.toString(), y.toString(), x.toString())
            }
        } finally {
            cleanup(p, viewer, x, y, z)
        }
    }

    "GET /users/{P}/following excludes viewer-blocked users in both directions" {
        val (p, _) = seedUser()
        val (viewer, tv) = seedUser()
        val (x, _) = seedUser()
        val (y, _) = seedUser()
        val (z, _) = seedUser()
        try {
            service.follow(p, x)
            service.follow(p, y)
            service.follow(p, z)
            insertBlock(viewer, x) // viewer blocked x
            insertBlock(y, viewer) // y blocked viewer
            withFollows {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/users/$p/following") {
                            header(HttpHeaders.Authorization, "Bearer $tv")
                        }
                val ids =
                    Json.parseToJsonElement(resp.bodyAsText())
                        .jsonObject["users"]!!.jsonArray
                        .map { (it as JsonObject)["userId"]!!.jsonPrimitive.content }
                ids.contains(x.toString()) shouldBe false
                ids.contains(y.toString()) shouldBe false
                ids.contains(z.toString()) shouldBe true
            }
        } finally {
            cleanup(p, viewer, x, y, z)
        }
    }

    "auth — all four follow endpoints return 401 without JWT" {
        val ghost = UUID.randomUUID()
        withFollows {
            val client = createClient { install(ClientCN) { json() } }
            client.post("/api/v1/follows/$ghost").status shouldBe HttpStatusCode.Unauthorized
            client.delete("/api/v1/follows/$ghost").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/users/$ghost/followers").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/users/$ghost/following").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
