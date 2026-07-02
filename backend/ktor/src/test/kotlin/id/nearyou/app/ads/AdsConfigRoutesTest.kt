package id.nearyou.app.ads

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.auth.configureUserJwt
import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import id.nearyou.app.auth.jwt.TestKeys
import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.infra.redis.RedisStringCache
import id.nearyou.app.infra.repo.JdbcUserRepository
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeInRange
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Date
import java.time.Duration
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
            // Frugal pool + autoClose (HideDistance/ConsentRoutesTest precedent / PR #157 CI budget).
            maximumPoolSize = 2
            minimumIdle = 0
            initializationFailTimeout = -1
        }
    return HikariDataSource(config)
}

/**
 * In-memory [RedisStringCache] honoring the short-TTL semantics the gate relies on: a value is served
 * until [expireAll] simulates the per-flag TTL elapsing (the actual Redis EX is exercised by the live
 * smoke). Lets the propagation tests assert in BOTH directions without a wall-clock dependency.
 */
private class FakeCache : RedisStringCache {
    private val store = mutableMapOf<String, String>()

    override fun get(key: String): String? = store[key]

    override fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        store[key] = value
    }

    fun expireAll() = store.clear()
}

private class MutableConfig(var bool: Boolean?) : RemoteConfig {
    override fun getLong(key: String): Long? = null

    override fun getBoolean(key: String): Boolean? = bool
}

@Tags("database")
class AdsConfigRoutesTest : StringSpec({

    val dataSource = autoClose(hikari())
    val keys = RsaKeyLoader(TestKeys.freshEncodedPemPrivateKey(), kid = "test-ads-config")
    val jwtIssuer = JwtIssuer(keys)
    val users = JdbcUserRepository(dataSource)

    fun service(
        cache: RedisStringCache,
        rc: RemoteConfig,
    ) = AdsConfigService(
        adsEnabledGate = AdsEnabledFlagGate(cache, rc, dbDispatcher = Dispatchers.Unconfined),
        remoteConfig = rc,
    )

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
                ps.setString(2, "ad_$short")
                ps.setString(3, "Ads Config Tester")
                ps.setDate(4, Date.valueOf(LocalDate.of(1990, 1, 1)))
                ps.setString(5, "a${short.take(7)}")
                ps.setString(6, subscriptionStatus)
                ps.executeUpdate()
            }
        }
        return id to jwtIssuer.issueAccessToken(id, tokenVersion = 0)
    }

    fun cleanup(vararg ids: UUID) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                ids.forEach { st.executeUpdate("DELETE FROM users WHERE id = '$it'") }
            }
        }
    }

    suspend fun withApp(
        svc: AdsConfigService,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(Authentication) { configureUserJwt(keys, users, java.time.Instant::now) }
                adsConfigRoutes(svc)
            }
            block()
        }
    }

    "Free + flag ON → ads_enabled true + frequency in the 5–7 range" {
        val (uid, jwt) = seedUser("free")
        try {
            withApp(service(FakeCache(), MutableConfig(true))) {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/config/ads") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                resp.status shouldBe HttpStatusCode.OK
                val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                obj["adsEnabled"]!!.jsonPrimitive.boolean shouldBe true
                // Assert the band, not the literal default, so a re-tune of the default doesn't break this.
                obj["timelineFrequency"]!!.jsonPrimitive.int shouldBeInRange 5..7
            }
        } finally {
            cleanup(uid)
        }
    }

    "premium_active + flag ON → ads_enabled false (suppressed)" {
        val (uid, jwt) = seedUser("premium_active")
        try {
            withApp(service(FakeCache(), MutableConfig(true))) {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/config/ads") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                resp.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["adsEnabled"]!!.jsonPrimitive.boolean shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    "premium_billing_retry (grace) + flag ON → ads_enabled false (keeps the ad-free benefit)" {
        val (uid, jwt) = seedUser("premium_billing_retry")
        try {
            withApp(service(FakeCache(), MutableConfig(true))) {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/config/ads") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                resp.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["adsEnabled"]!!.jsonPrimitive.boolean shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    "flag absent (null) → ads_enabled false (fail-closed)" {
        val (uid, jwt) = seedUser("free")
        try {
            withApp(service(FakeCache(), MutableConfig(null))) {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/config/ads") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                resp.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["adsEnabled"]!!.jsonPrimitive.boolean shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    "flag explicitly false → ads_enabled false" {
        val (uid, jwt) = seedUser("free")
        try {
            withApp(service(FakeCache(), MutableConfig(false))) {
                val resp =
                    createClient { install(ClientCN) { json() } }
                        .get("/api/v1/config/ads") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                resp.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject["adsEnabled"]!!.jsonPrimitive.boolean shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    "unauthenticated → 401, no config returned" {
        withApp(service(FakeCache(), MutableConfig(true))) {
            createClient { install(ClientCN) { json() } }
                .get("/api/v1/config/ads")
                .status shouldBe HttpStatusCode.Unauthorized
        }
    }

    "short-TTL propagation: absent/false → ON after the TTL elapses" {
        val (uid, jwt) = seedUser("free")
        try {
            val cache = FakeCache()
            val rc = MutableConfig(false)
            withApp(service(cache, rc)) {
                val client = createClient { install(ClientCN) { json() } }

                suspend fun adsEnabled(): Boolean {
                    val body =
                        client.get("/api/v1/config/ads") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                            .bodyAsText()
                    return Json.parseToJsonElement(body).jsonObject["adsEnabled"]!!.jsonPrimitive.boolean
                }

                // First read caches FALSE.
                adsEnabled() shouldBe false
                // Operator flips the flag ON; within the TTL the cached FALSE is still served.
                rc.bool = true
                adsEnabled() shouldBe false
                // After the short TTL elapses (cache entry expires), the ON propagates.
                cache.expireAll()
                adsEnabled() shouldBe true
            }
        } finally {
            cleanup(uid)
        }
    }

    "short-TTL propagation: ON → OFF flip takes effect within the TTL window" {
        val (uid, jwt) = seedUser("free")
        try {
            val cache = FakeCache()
            val rc = MutableConfig(true)
            withApp(service(cache, rc)) {
                val client = createClient { install(ClientCN) { json() } }

                suspend fun adsEnabled(): Boolean {
                    val body =
                        client.get("/api/v1/config/ads") { header(HttpHeaders.Authorization, "Bearer $jwt") }
                            .bodyAsText()
                    return Json.parseToJsonElement(body).jsonObject["adsEnabled"]!!.jsonPrimitive.boolean
                }

                // First read caches TRUE (ads live).
                adsEnabled() shouldBe true
                // Emergency flip to OFF; the cached TRUE is bounded by the short TTL.
                rc.bool = false
                cache.expireAll()
                adsEnabled() shouldBe false
            }
        } finally {
            cleanup(uid)
        }
    }

    "AdsConfigRoutes source logs no token / Authorization / sub / userId" {
        val src = File("src/main/kotlin/id/nearyou/app/ads/AdsConfigRoutes.kt").readText()
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
