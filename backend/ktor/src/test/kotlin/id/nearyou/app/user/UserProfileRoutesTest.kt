package id.nearyou.app.user

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
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
            // Frugal pool (2 conns) + autoClose below: this spec's DB ops are sequential, so 2
            // connections suffice, and releasing the pool when the spec finishes keeps the change
            // from adding to the suite-wide leaked-HikariPool pressure on the CI Postgres service
            // container (per the CI connection-budget rule). Task 6.1.
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/** Constant, direction-less 404 body — must be byte-identical for every unresolvable cause (D4). */
private const val USER_NOT_FOUND_BODY = """{"error":{"code":"user_not_found"}}"""

@Tags("database")
class UserProfileRoutesTest : StringSpec({

    // autoClose releases the HikariPool when the spec finishes (task 6.1 — CI connection-budget rule).
    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-user-profile")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val service = UserProfileService(JdbcUserProfileReader(dataSource))

    data class Seeded(val id: UUID, val token: String, val username: String, val displayName: String, val bio: String?)

    fun seedUser(
        bio: String? = null,
        subscriptionStatus: String = "free",
        shadowBanned: Boolean = false,
        deleted: Boolean = false,
        privateOptIn: Boolean = false,
        privacyFlipAt: Instant? = null,
    ): Seeded {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        val username = "up_$short"
        val displayName = "Profile Tester"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix,
                    bio, subscription_status, is_shadow_banned, deleted_at,
                    private_profile_opt_in, privacy_flip_scheduled_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "p${short.take(7)}")
                ps.setString(6, bio)
                ps.setString(7, subscriptionStatus)
                ps.setBoolean(8, shadowBanned)
                ps.setTimestamp(9, if (deleted) Timestamp.from(Instant.now()) else null)
                ps.setBoolean(10, privateOptIn)
                ps.setTimestamp(11, privacyFlipAt?.let(Timestamp::from))
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return Seeded(id, token, username, displayName, bio)
    }

    fun follow(
        follower: UUID,
        followee: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)").use { ps ->
                ps.setObject(1, follower)
                ps.setObject(2, followee)
                ps.executeUpdate()
            }
        }
    }

    fun block(
        blocker: UUID,
        blocked: UUID,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)").use { ps ->
                ps.setObject(1, blocker)
                ps.setObject(2, blocked)
                ps.executeUpdate()
            }
        }
    }

    // follows / user_blocks FK to users is ON DELETE CASCADE — deleting users cleans child rows.
    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach { st.executeUpdate("DELETE FROM users WHERE id = '$it'") }
            }
        }
    }

    suspend fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Authentication) { configureUserJwt(keys, users, Instant::now) }
                userProfileRoutes(service)
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.getProfile(
        path: String,
        token: String?,
    ): Pair<HttpStatusCode, String> {
        val resp =
            createClient { install(ClientCN) { json() } }.get(path) {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
        return resp.status to resp.bodyAsText()
    }

    fun String.obj(): JsonObject = Json.parseToJsonElement(this).jsonObject

    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

    fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.long

    fun JsonObject.isNull(key: String): Boolean = this[key] is JsonNull

    "6.2 200 — viewer reads another user's public profile (isSelf=false, self-only isPrivate null)" {
        val viewer = seedUser()
        val target = seedUser(bio = "hello world")
        try {
            withApp {
                val (status, body) = getProfile("/api/v1/users/${target.id}", viewer.token)
                status shouldBe HttpStatusCode.OK
                val o = body.obj()
                o.str("userId") shouldBe target.id.toString()
                o.str("username") shouldBe target.username
                o.str("displayName") shouldBe target.displayName
                o.str("bio") shouldBe "hello world"
                o.bool("isSelf") shouldBe false
                o.bool("followedByViewer") shouldBe false
                o.long("followerCount") shouldBe 0L
                o.long("followingCount") shouldBe 0L
                o.bool("isPremium") shouldBe false
                o.isNull("isPrivate") shouldBe true
            }
        } finally {
            cleanup(viewer.id, target.id)
        }
    }

    "6.3 200 — followedByViewer = true when the viewer follows the target" {
        val viewer = seedUser()
        val target = seedUser()
        follow(viewer.id, target.id)
        try {
            withApp {
                val (status, body) = getProfile("/api/v1/users/${target.id}", viewer.token)
                status shouldBe HttpStatusCode.OK
                body.obj().bool("followedByViewer") shouldBe true
            }
        } finally {
            cleanup(viewer.id, target.id)
        }
    }

    "6.4 200 — viewer reads own profile (isSelf=true, followedByViewer=false)" {
        val self = seedUser(bio = "me")
        try {
            withApp {
                val (status, body) = getProfile("/api/v1/users/${self.id}", self.token)
                status shouldBe HttpStatusCode.OK
                val o = body.obj()
                o.bool("isSelf") shouldBe true
                o.bool("followedByViewer") shouldBe false
                o.str("bio") shouldBe "me"
            }
        } finally {
            cleanup(self.id)
        }
    }

    "6.5 200 — shadow-banned VIEWER reads own profile (own-content path, not filtered)" {
        val self = seedUser(shadowBanned = true)
        try {
            withApp {
                val (status, body) = getProfile("/api/v1/users/${self.id}", self.token)
                status shouldBe HttpStatusCode.OK
                body.obj().bool("isSelf") shouldBe true
            }
        } finally {
            cleanup(self.id)
        }
    }

    "6.6 200 — followerCount / followingCount reflect the follows graph (3 followers / 5 following)" {
        val viewer = seedUser()
        val target = seedUser()
        val followers = (1..3).map { seedUser() }
        val followees = (1..5).map { seedUser() }
        followers.forEach { follow(it.id, target.id) }
        followees.forEach { follow(target.id, it.id) }
        try {
            withApp {
                val (status, body) = getProfile("/api/v1/users/${target.id}", viewer.token)
                status shouldBe HttpStatusCode.OK
                val o = body.obj()
                o.long("followerCount") shouldBe 3L
                o.long("followingCount") shouldBe 5L
            }
        } finally {
            cleanup(viewer.id, target.id, *followers.map { it.id }.toTypedArray(), *followees.map { it.id }.toTypedArray())
        }
    }

    "6.7 200 — counts are NOT viewer-block-filtered on both axes (locks D1)" {
        val viewer = seedUser()
        val target = seedUser()
        // X follows T and has blocked the viewer → still counted in followerCount.
        val x = seedUser()
        follow(x.id, target.id)
        block(x.id, viewer.id)
        // T follows Y and Y has blocked the viewer → still counted in followingCount.
        val y = seedUser()
        follow(target.id, y.id)
        block(y.id, viewer.id)
        try {
            withApp {
                val (status, body) = getProfile("/api/v1/users/${target.id}", viewer.token)
                status shouldBe HttpStatusCode.OK
                val o = body.obj()
                o.long("followerCount") shouldBe 1L
                o.long("followingCount") shouldBe 1L
            }
        } finally {
            cleanup(viewer.id, target.id, x.id, y.id)
        }
    }

    "6.8 200 — isPremium true only for premium_active (false for free and premium_billing_retry)" {
        val viewer = seedUser()
        val premium = seedUser(subscriptionStatus = "premium_active")
        val free = seedUser(subscriptionStatus = "free")
        val retry = seedUser(subscriptionStatus = "premium_billing_retry")
        try {
            withApp {
                getProfile("/api/v1/users/${premium.id}", viewer.token).second.obj().bool("isPremium") shouldBe true
                getProfile("/api/v1/users/${free.id}", viewer.token).second.obj().bool("isPremium") shouldBe false
                getProfile("/api/v1/users/${retry.id}", viewer.token).second.obj().bool("isPremium") shouldBe false
            }
        } finally {
            cleanup(viewer.id, premium.id, free.id, retry.id)
        }
    }

    "6.9 200 — self-read isPrivate matrix (premium-conjunct + grace short-circuit)" {
        // (a) opt-in + premium_active, no grace → true
        val a = seedUser(subscriptionStatus = "premium_active", privateOptIn = true)
        // (b) opt-in + free, no grace → false (premium-conjunct guard)
        val b = seedUser(subscriptionStatus = "free", privateOptIn = true)
        // (c) base-false + privacy_flip in FUTURE → true (grace short-circuit)
        val c = seedUser(subscriptionStatus = "free", privacyFlipAt = Instant.now().plusSeconds(3600))
        // (d) base-false + privacy_flip in PAST → false (guards an inverted comparison)
        val d = seedUser(subscriptionStatus = "free", privacyFlipAt = Instant.now().minusSeconds(3600))
        try {
            withApp {
                getProfile("/api/v1/users/${a.id}", a.token).second.obj().bool("isPrivate") shouldBe true
                getProfile("/api/v1/users/${b.id}", b.token).second.obj().bool("isPrivate") shouldBe false
                getProfile("/api/v1/users/${c.id}", c.token).second.obj().bool("isPrivate") shouldBe true
                getProfile("/api/v1/users/${d.id}", d.token).second.obj().bool("isPrivate") shouldBe false
            }
        } finally {
            cleanup(a.id, b.id, c.id, d.id)
        }
    }

    "6.10 200 — other-user read does NOT leak isPrivate even when the target is effectively private" {
        val viewer = seedUser()
        val target = seedUser(subscriptionStatus = "premium_active", privateOptIn = true)
        try {
            withApp {
                val o = getProfile("/api/v1/users/${target.id}", viewer.token).second.obj()
                o.isNull("isPrivate") shouldBe true
            }
        } finally {
            cleanup(viewer.id, target.id)
        }
    }

    "6.11–6.15 404 — all five unresolvable causes return the byte-identical constant body" {
        val viewer = seedUser()
        val shadow = seedUser(shadowBanned = true) // 6.12
        val softDeleted = seedUser(deleted = true) // 6.13
        val viewerBlocked = seedUser() // 6.14 — viewer blocked target
        block(viewer.id, viewerBlocked.id)
        val blockedViewer = seedUser() // 6.15 — target blocked viewer (opposite direction)
        block(blockedViewer.id, viewer.id)
        val unknown = UUID.randomUUID() // 6.11
        try {
            withApp {
                val bodies =
                    listOf(
                        // 6.11 unknown UUID
                        getProfile("/api/v1/users/$unknown", viewer.token),
                        // 6.12 shadow-banned target
                        getProfile("/api/v1/users/${shadow.id}", viewer.token),
                        // 6.13 soft-deleted target
                        getProfile("/api/v1/users/${softDeleted.id}", viewer.token),
                        // 6.14 viewer blocked target
                        getProfile("/api/v1/users/${viewerBlocked.id}", viewer.token),
                        // 6.15 target blocked viewer (opposite direction)
                        getProfile("/api/v1/users/${blockedViewer.id}", viewer.token),
                    )
                bodies.forEach { (status, body) ->
                    status shouldBe HttpStatusCode.NotFound
                    body shouldBe USER_NOT_FOUND_BODY
                }
                // All five bodies are mutually byte-identical (the block-direction non-leak — D4).
                bodies.map { it.second }.toSet().size shouldBe 1
            }
        } finally {
            cleanup(viewer.id, shadow.id, softDeleted.id, viewerBlocked.id, blockedViewer.id)
        }
    }

    "6.16 400 — malformed user_id (invalid_request)" {
        val viewer = seedUser()
        try {
            withApp {
                val (status, body) = getProfile("/api/v1/users/not-a-uuid", viewer.token)
                status shouldBe HttpStatusCode.BadRequest
                body.obj()["error"]!!.jsonObject.str("code") shouldBe "invalid_request"
            }
        } finally {
            cleanup(viewer.id)
        }
    }

    "6.17 401 — unauthenticated request" {
        withApp {
            val (status, _) = getProfile("/api/v1/users/${UUID.randomUUID()}", token = null)
            status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
