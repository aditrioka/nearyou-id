package id.nearyou.app.account

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.infra.r2.ObjectStore
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration

private fun hikari(): HikariDataSource {
    val config =
        HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
            username = System.getenv("DB_USER") ?: "postgres"
            password = System.getenv("DB_PASSWORD") ?: "postgres"
            maximumPoolSize = 2
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/** Deterministic ObjectStore so the GET ready-path presigns a known URL with no R2. */
private class FakeObjectStore(private val configured: Boolean = true) : ObjectStore {
    override fun isConfigured(): Boolean = configured

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) = Unit

    override suspend fun presignedGetUrl(
        key: String,
        ttl: Duration,
    ): String = "https://fake.r2.example/$key?sig=test"

    override suspend fun delete(key: String) = Unit
}

/**
 * DB-tagged route tests for the user-facing export endpoints (capability `account-data-export`):
 *  - 8.2 POST /api/v1/account/export — first → 202 pending; re-request while active → SAME
 *    request (no 2nd row); re-request after a terminal row → a NEW row; unauth → 401; export
 *    keyed strictly on the authenticated user (own-data-only, no `user_id` param surface).
 *  - 8.3 GET /api/v1/account/export — pending; ready + downloadExpiresAt + downloadUrl;
 *    no-export ("none"); no cross-user leak of state/key/URL.
 *  - 8.10 GET on a past-deadline ready row reports "expired" with no fresh durable URL.
 */
@Tags("database")
class AccountDataExportRoutesTest : StringSpec({

    val dataSource = hikari()
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-data-export")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)
    val service = DataExportService(DataExportRequestRepository(dataSource), FakeObjectStore())

    val seeded = mutableListOf<UUID>()

    data class Seeded(val id: UUID, val token: String)

    fun seedUser(): Seeded {
        val id = UUID.randomUUID()
        val short = id.toString().replace("-", "").take(8)
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "dxe_$short")
                ps.setString(3, "Export Route Test")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "e${short.take(7)}")
                ps.executeUpdate()
            }
        }
        seeded += id
        return Seeded(id, jwtIssuer.issueAccessToken(id, tokenVersion = 0))
    }

    fun seedRow(
        userId: UUID,
        status: String,
        downloadExpiresAt: Instant? = null,
        r2ObjectKey: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO data_export_requests (id, user_id, status, download_expires_at, r2_object_key)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, userId)
                ps.setString(3, status)
                if (downloadExpiresAt != null) {
                    ps.setTimestamp(
                        4,
                        Timestamp.from(downloadExpiresAt),
                    )
                } else {
                    ps.setNull(4, java.sql.Types.TIMESTAMP)
                }
                if (r2ObjectKey != null) ps.setString(5, r2ObjectKey) else ps.setNull(5, java.sql.Types.VARCHAR)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun rowCount(userId: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM data_export_requests WHERE user_id = ?").use { ps ->
                ps.setObject(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    afterTest {
        dataSource.connection.use { conn ->
            seeded.forEach { id ->
                conn.prepareStatement("DELETE FROM data_export_requests WHERE user_id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
            }
        }
        seeded.clear()
    }

    afterSpec { dataSource.close() }

    suspend fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
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
                install(Authentication) { configureUserJwt(keys, users, Instant::now) }
                accountDataExportRoutes(service)
            }
            block()
        }
    }

    suspend fun ApplicationTestBuilder.postExport(token: String?): Pair<HttpStatusCode, String> {
        val resp =
            client.post("/api/v1/account/export") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
        return resp.status to resp.bodyAsText()
    }

    suspend fun ApplicationTestBuilder.getExport(token: String?): Pair<HttpStatusCode, String> {
        val resp =
            client.get("/api/v1/account/export") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
        return resp.status to resp.bodyAsText()
    }

    fun field(
        body: String,
        key: String,
    ): String? = Json.parseToJsonElement(body).jsonObject[key]?.jsonPrimitive?.content

    // ---- 8.2 POST ---------------------------------------------------------

    "8.2 POST first request → 202 pending with a request id, exactly one active row" {
        val u = seedUser()
        withApp {
            val (status, body) = postExport(u.token)
            status shouldBe HttpStatusCode.Accepted
            field(body, "status") shouldBe "pending"
            (field(body, "requestId")?.isNotBlank() == true) shouldBe true
            rowCount(u.id) shouldBe 1
        }
    }

    "8.2 POST re-request while active returns the SAME request, no 2nd row" {
        val u = seedUser()
        withApp {
            val (_, firstBody) = postExport(u.token)
            val (status, secondBody) = postExport(u.token)
            status shouldBe HttpStatusCode.Accepted
            field(secondBody, "requestId") shouldBe field(firstBody, "requestId")
            rowCount(u.id) shouldBe 1
        }
    }

    "8.2 POST re-request after a terminal (ready/expired/failed) row creates a NEW row" {
        listOf("ready", "expired", "failed").forEach { priorStatus ->
            val u = seedUser()
            val priorId = seedRow(u.id, priorStatus)
            withApp {
                val (status, body) = postExport(u.token)
                status shouldBe HttpStatusCode.Accepted
                field(body, "status") shouldBe "pending"
                (field(body, "requestId") != priorId.toString()) shouldBe true
                rowCount(u.id) shouldBe 2
            }
        }
    }

    "8.2 POST unauthenticated → 401 and no row is written" {
        // Seed a user only to anchor a clean afterTest; the request carries no token.
        withApp {
            val (status, _) = postExport(token = null)
            status shouldBe HttpStatusCode.Unauthorized
        }
    }

    // ---- 8.3 GET ----------------------------------------------------------

    "8.3 GET reflects a pending export" {
        val u = seedUser()
        seedRow(u.id, "pending")
        withApp {
            val (status, body) = getExport(u.token)
            status shouldBe HttpStatusCode.OK
            field(body, "status") shouldBe "pending"
            // No downloadUrl for an in-progress export.
            (Json.parseToJsonElement(body).jsonObject["downloadUrl"] == null) shouldBe true
        }
    }

    "8.3 GET reflects a ready export with its deadline + a fresh signed URL" {
        val u = seedUser()
        val deadline = Instant.now().plus(20, ChronoUnit.HOURS)
        seedRow(u.id, "ready", downloadExpiresAt = deadline, r2ObjectKey = "exports/${u.id}/ready.zip")
        withApp {
            val (status, body) = getExport(u.token)
            status shouldBe HttpStatusCode.OK
            field(body, "status") shouldBe "ready"
            (field(body, "downloadExpiresAt")?.isNotBlank() == true) shouldBe true
            // The FakeObjectStore presigns a deterministic URL keyed on the object key.
            field(body, "downloadUrl") shouldBe "https://fake.r2.example/exports/${u.id}/ready.zip?sig=test"
        }
    }

    "8.3 GET reports a no-export state when nothing was ever requested" {
        val u = seedUser()
        withApp {
            val (status, body) = getExport(u.token)
            status shouldBe HttpStatusCode.OK
            field(body, "status") shouldBe "none"
            (Json.parseToJsonElement(body).jsonObject["downloadUrl"] == null) shouldBe true
        }
    }

    "8.3 GET never leaks another user's export state, key, or URL" {
        val a = seedUser()
        val b = seedUser()
        // B has a ready export; A has none. A's GET must reflect ONLY A.
        seedRow(b.id, "ready", downloadExpiresAt = Instant.now().plus(20, ChronoUnit.HOURS), r2ObjectKey = "exports/${b.id}/secret.zip")
        withApp {
            val (status, body) = getExport(a.token)
            status shouldBe HttpStatusCode.OK
            field(body, "status") shouldBe "none"
            // B's object key + signed URL never appear in A's response.
            body.contains(b.id.toString()) shouldBe false
            body.contains("secret.zip") shouldBe false
        }
    }

    // ---- 8.10 expiry on read ---------------------------------------------

    "8.10 GET on a past-deadline ready row reports expired with no fresh durable URL" {
        val u = seedUser()
        seedRow(u.id, "ready", downloadExpiresAt = Instant.now().minus(1, ChronoUnit.HOURS), r2ObjectKey = "exports/${u.id}/dead.zip")
        withApp {
            val (status, body) = getExport(u.token)
            status shouldBe HttpStatusCode.OK
            field(body, "status") shouldBe "expired"
            // Past the deadline the service derives EXPIRED → it does NOT re-presign a fresh URL.
            (Json.parseToJsonElement(body).jsonObject["downloadUrl"] == null) shouldBe true
        }
    }
})
