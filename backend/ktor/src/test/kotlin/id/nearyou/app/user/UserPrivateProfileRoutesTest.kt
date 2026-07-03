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
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
            // Frugal pool + autoClose (ConsentRoutesTest / HideDistanceRoutesTest precedent; PR #157
            // CI connection budget).
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

@Tags("database")
class UserPrivateProfileRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-private-profile")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = PrivateProfileRepository(dataSource)

    fun seedUser(subscriptionStatus: String = "free"): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (
                    id, username, display_name, date_of_birth, invite_code_prefix, subscription_status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "pp_$short")
                ps.setString(3, "Private Profile Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "p${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.executeUpdate()
            }
        }
        val token = jwtIssuer.issueAccessToken(id, tokenVersion = 0)
        return id to token
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach { st.executeUpdate("DELETE FROM users WHERE id = '$it'") }
            }
        }
    }

    fun selectPrivateProfile(userId: UUID): Boolean? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT private_profile_opt_in FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
            }
        }

    /** Returns the stored `privacy_flip_scheduled_at` as an [Instant], or null when the column is NULL. */
    fun selectScheduledFlip(userId: UUID): Instant? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT privacy_flip_scheduled_at FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getTimestamp(1)?.toInstant() else null }
            }
        }

    fun setPrivateProfile(
        userId: UUID,
        value: Boolean,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET private_profile_opt_in = ? WHERE id = ?").use { ps ->
                ps.setBoolean(1, value)
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    fun setScheduledFlip(
        userId: UUID,
        at: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET privacy_flip_scheduled_at = ? WHERE id = ?").use { ps ->
                ps.setTimestamp(1, Timestamp.from(at))
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    suspend fun withPrivateProfile(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                privateProfileRoutes(repository)
            }
            block()
        }
    }

    "enabling → 200, body echoes privateProfile=true, row updated" {
        val (uid, jwt) = seedUser()
        try {
            withPrivateProfile {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .patch("/api/v1/user/private-profile") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"privateProfile":true}""")
                        }
                resp.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["privateProfile"]!!.jsonPrimitive.boolean shouldBe true
            }
            selectPrivateProfile(uid) shouldBe true
        } finally {
            cleanup(uid)
        }
    }

    "disabling → 200, row set back to false" {
        val (uid, jwt) = seedUser()
        try {
            setPrivateProfile(uid, true)
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":false}""")
                    }.status shouldBe HttpStatusCode.OK
            }
            selectPrivateProfile(uid) shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "a Free user may store the flag (200, row TRUE) — effect is read-gated elsewhere" {
        val (uid, jwt) = seedUser(subscriptionStatus = "free")
        try {
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":true}""")
                    }.status shouldBe HttpStatusCode.OK
            }
            // The write succeeds regardless of tier; the canonical effective-private formula gates the
            // *effect* on Premium status (covered by the premium-search private-gate + user-profile-read
            // is_private tests). A `free` row holding TRUE is NOT effectively private.
            selectPrivateProfile(uid) shouldBe true
        } finally {
            cleanup(uid)
        }
    }

    "opt-out during the grace window clears the scheduled flip" {
        // GIVEN a user mid-grace: downgraded, opt_in still TRUE, privacy_flip_scheduled_at in the future.
        val (uid, jwt) = seedUser(subscriptionStatus = "free")
        try {
            setPrivateProfile(uid, true)
            setScheduledFlip(uid, Instant.now().plusSeconds(72 * 3600))
            // WHEN they opt OUT.
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":false}""")
                    }.status shouldBe HttpStatusCode.OK
            }
            // THEN opt_in is FALSE AND the pending flip is cleared (the "confirm switch public" path),
            // so the effective-private formula resolves FALSE — the profile is public immediately.
            selectPrivateProfile(uid) shouldBe false
            selectScheduledFlip(uid) shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "opt-in does not touch the scheduled flip (NULL stays NULL)" {
        val (uid, jwt) = seedUser(subscriptionStatus = "premium_active")
        try {
            selectScheduledFlip(uid) shouldBe null
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":true}""")
                    }.status shouldBe HttpStatusCode.OK
            }
            selectPrivateProfile(uid) shouldBe true
            selectScheduledFlip(uid) shouldBe null
        } finally {
            cleanup(uid)
        }
    }

    "opt-in leaves a pre-existing scheduled flip untouched (the opt-in branch never clears it)" {
        // Hardening beyond the spec's NULL-stays-NULL scenario: prove the opt-IN SQL writes ONLY the
        // opt-in column — a non-null scheduled_at survives an opt-in write.
        val (uid, jwt) = seedUser(subscriptionStatus = "premium_active")
        try {
            val at = Instant.now().plusSeconds(72 * 3600)
            setScheduledFlip(uid, at)
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":true}""")
                    }.status shouldBe HttpStatusCode.OK
            }
            selectPrivateProfile(uid) shouldBe true
            (selectScheduledFlip(uid) != null) shouldBe true
        } finally {
            cleanup(uid)
        }
    }

    "own-row only — A's PATCH leaves B at the default (no IDOR; identity from principal)" {
        val (uA, jA) = seedUser()
        val (uB, _) = seedUser()
        try {
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jA")
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":true}""")
                    }.status shouldBe HttpStatusCode.OK
            }
            selectPrivateProfile(uA) shouldBe true
            selectPrivateProfile(uB) shouldBe false
        } finally {
            cleanup(uA, uB)
        }
    }

    "missing Authorization → 401, row unchanged" {
        val (uid, _) = seedUser()
        try {
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":true}""")
                    }.status shouldBe HttpStatusCode.Unauthorized
            }
            selectPrivateProfile(uid) shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "invalid JWT → 401, row unchanged" {
        val (uid, _) = seedUser()
        try {
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer not-a-real-jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":true}""")
                    }.status shouldBe HttpStatusCode.Unauthorized
            }
            selectPrivateProfile(uid) shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "missing the privateProfile key → 400, row unchanged" {
        val (uid, jwt) = seedUser()
        try {
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{}""")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
            selectPrivateProfile(uid) shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "non-boolean value → 400, row unchanged" {
        val (uid, jwt) = seedUser()
        try {
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"privateProfile":"yes"}""")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
            selectPrivateProfile(uid) shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "oversized body (> 4096 bytes) → 413, row unchanged" {
        val (uid, jwt) = seedUser()
        try {
            val pad = "x".repeat(4096)
            val body = """{"privateProfile":true,"_pad":"$pad"}"""
            (body.toByteArray().size > 4096) shouldBe true
            withPrivateProfile {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/private-profile") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.status shouldBe HttpStatusCode.PayloadTooLarge
            }
            selectPrivateProfile(uid) shouldBe false
        } finally {
            cleanup(uid)
        }
    }

    "GET returns the stored flag and premium=true for a premium_active caller" {
        val (uid, jwt) = seedUser(subscriptionStatus = "premium_active")
        try {
            setPrivateProfile(uid, true)
            withPrivateProfile {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/user/private-profile") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                obj["privateProfile"]!!.jsonPrimitive.boolean shouldBe true
                obj["premium"]!!.jsonPrimitive.boolean shouldBe true
            }
        } finally {
            cleanup(uid)
        }
    }

    "GET returns premium=true for a premium_billing_retry caller (effective-Premium retained)" {
        val (uid, jwt) = seedUser(subscriptionStatus = "premium_billing_retry")
        try {
            withPrivateProfile {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/user/private-profile") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["premium"]!!.jsonPrimitive.boolean shouldBe true
            }
        } finally {
            cleanup(uid)
        }
    }

    "GET returns premium=false for a Free caller regardless of the stored flag" {
        val (uid, jwt) = seedUser(subscriptionStatus = "free")
        try {
            setPrivateProfile(uid, true)
            withPrivateProfile {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/user/private-profile") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                obj["privateProfile"]!!.jsonPrimitive.boolean shouldBe true
                obj["premium"]!!.jsonPrimitive.boolean shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    "GET without a valid JWT → 401" {
        withPrivateProfile {
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/user/private-profile")
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "PrivateProfileRoutes source logs no token / Authorization / sub / userId" {
        val src = File("src/main/kotlin/id/nearyou/app/user/PrivateProfileRoutes.kt").readText()
        val stripped =
            src
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\n]*"), "")
        val logCalls =
            Regex("""log\.(?:info|warn|error|debug)\s*\([^)]*\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(stripped)
                .map { it.value }
                .toList()
        (logCalls.isNotEmpty()) shouldBe true
        logCalls.forEach { callSite ->
            listOf("Authorization", "Bearer", "userId", "principal", ".sub").forEach { forbidden ->
                callSite.contains(forbidden) shouldBe false
            }
        }
    }
})
