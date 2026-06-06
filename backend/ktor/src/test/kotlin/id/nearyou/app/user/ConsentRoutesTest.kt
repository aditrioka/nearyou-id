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

/** The V2 column default: analytics off, crash on, ads off. */
private val V2_DEFAULT = Triple(false, true, false)

@Tags("database")
class ConsentRoutesTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-consent")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val repository = ConsentRepository(dataSource)

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
                ps.setString(2, "cr_$short")
                ps.setString(3, "Consent Routes Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "g${short.take(7)}")
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

    fun selectConsent(userId: UUID): Triple<Boolean, Boolean, Boolean>? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT analytics_consent::text FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        val obj = Json.parseToJsonElement(rs.getString(1)).jsonObject
                        Triple(
                            obj["analytics"]!!.jsonPrimitive.boolean,
                            obj["crash"]!!.jsonPrimitive.boolean,
                            obj["ads_personalization"]!!.jsonPrimitive.boolean,
                        )
                    }
                }
            }
        }

    fun setConsent(
        userId: UUID,
        analytics: Boolean,
        crash: Boolean,
        ads: Boolean,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET analytics_consent = ?::jsonb WHERE id = ?").use { ps ->
                ps.setString(1, """{"analytics":$analytics,"crash":$crash,"ads_personalization":$ads}""")
                ps.setObject(2, userId)
                ps.executeUpdate()
            }
        }
    }

    suspend fun withConsent(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                consentRoutes(repository)
            }
            block()
        }
    }

    "3.1 happy path → 200, body echoes the triple, row updated" {
        val (uid, jwt) = seedUser()
        try {
            withConsent {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .patch("/api/v1/user/consent") {
                            header(HttpHeaders.Authorization, "Bearer $jwt")
                            contentType(ContentType.Application.Json)
                            setBody("""{"analytics":true,"crash":false,"ads_personalization":true}""")
                        }
                resp.status shouldBe HttpStatusCode.OK
                val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                obj["analytics"]!!.jsonPrimitive.boolean shouldBe true
                obj["crash"]!!.jsonPrimitive.boolean shouldBe false
                obj["ads_personalization"]!!.jsonPrimitive.boolean shouldBe true
            }
            selectConsent(uid) shouldBe Triple(true, false, true)
        } finally {
            cleanup(uid)
        }
    }

    "3.2 full-object replace — prior all-true fully replaced by all-false" {
        val (uid, jwt) = seedUser()
        try {
            setConsent(uid, analytics = true, crash = true, ads = true)
            withConsent {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/consent") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"analytics":false,"crash":false,"ads_personalization":false}""")
                    }.status shouldBe HttpStatusCode.OK
            }
            selectConsent(uid) shouldBe Triple(false, false, false)
        } finally {
            cleanup(uid)
        }
    }

    "3.3 own-row only — A's PATCH leaves B at the V2 default" {
        val (uA, jA) = seedUser()
        val (uB, _) = seedUser()
        try {
            withConsent {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/consent") {
                        header(HttpHeaders.Authorization, "Bearer $jA")
                        contentType(ContentType.Application.Json)
                        setBody("""{"analytics":true,"crash":true,"ads_personalization":true}""")
                    }.status shouldBe HttpStatusCode.OK
            }
            selectConsent(uA) shouldBe Triple(true, true, true)
            selectConsent(uB) shouldBe V2_DEFAULT
        } finally {
            cleanup(uA, uB)
        }
    }

    "3.4a missing Authorization → 401, row unchanged" {
        val (uid, _) = seedUser()
        try {
            withConsent {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/consent") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"analytics":true,"crash":true,"ads_personalization":true}""")
                    }.status shouldBe HttpStatusCode.Unauthorized
            }
            selectConsent(uid) shouldBe V2_DEFAULT
        } finally {
            cleanup(uid)
        }
    }

    "3.4b invalid JWT → 401, row unchanged" {
        val (uid, _) = seedUser()
        try {
            withConsent {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/consent") {
                        header(HttpHeaders.Authorization, "Bearer not-a-real-jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"analytics":true,"crash":true,"ads_personalization":true}""")
                    }.status shouldBe HttpStatusCode.Unauthorized
            }
            selectConsent(uid) shouldBe V2_DEFAULT
        } finally {
            cleanup(uid)
        }
    }

    "3.5a missing a key → 400, row unchanged" {
        val (uid, jwt) = seedUser()
        try {
            withConsent {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/consent") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"analytics":true,"crash":false}""")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
            selectConsent(uid) shouldBe V2_DEFAULT
        } finally {
            cleanup(uid)
        }
    }

    "3.5b non-boolean value → 400, row unchanged" {
        val (uid, jwt) = seedUser()
        try {
            withConsent {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/consent") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody("""{"analytics":"yes","crash":false,"ads_personalization":false}""")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
            selectConsent(uid) shouldBe V2_DEFAULT
        } finally {
            cleanup(uid)
        }
    }

    "2.2a oversized body (> 4096 bytes) → 413, row unchanged" {
        val (uid, jwt) = seedUser()
        try {
            // Pad with ignored extra keys so the body exceeds the 4 KB cap while staying valid JSON.
            val pad = "x".repeat(4096)
            val body = """{"analytics":true,"crash":true,"ads_personalization":true,"_pad":"$pad"}"""
            (body.toByteArray().size > 4096) shouldBe true
            withConsent {
                createClient { install(ClientCN) { json() } }
                    .patch("/api/v1/user/consent") {
                        header(HttpHeaders.Authorization, "Bearer $jwt")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.status shouldBe HttpStatusCode.PayloadTooLarge
            }
            selectConsent(uid) shouldBe V2_DEFAULT
        } finally {
            cleanup(uid)
        }
    }

    "3.6 ConsentRoutes source logs no token / Authorization / sub / userId" {
        val src = File("src/main/kotlin/id/nearyou/app/user/ConsentRoutes.kt").readText()
        // Strip block + line comments FIRST so the KDoc (which legitimately names
        // "token"/"sub"/"body") does not trip the forbidden-token scan.
        val stripped =
            src
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\n]*"), "")
        val logCalls =
            Regex("""log\.(?:info|warn|error|debug)\s*\([^)]*\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(stripped)
                .map { it.value }
                .toList()
        // The route logs only content-free event names → at least one log call, none with PII.
        (logCalls.isNotEmpty()) shouldBe true
        logCalls.forEach { callSite ->
            listOf("Authorization", "Bearer", "userId", "principal", ".sub").forEach { forbidden ->
                callSite.contains(forbidden) shouldBe false
            }
        }
    }
})
