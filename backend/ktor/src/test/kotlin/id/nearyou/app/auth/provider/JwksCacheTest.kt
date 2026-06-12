package id.nearyou.app.auth.provider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit coverage for [JwksCache] — the Cache-Control TTL contract (`auth-signin` spec
 * § JWKS caching) and the unknown-`kid` forced-refresh path.
 *
 * The forced-refresh cases pin the 2026-06-11 review fix: the lazy `refresh()` short-
 * circuits on a TTL-valid cache, so the unknown-kid path MUST bypass that short-circuit
 * or a mid-TTL provider key rotation 401s every new-kid sign-in until `max-age` expiry.
 */
class JwksCacheTest : StringSpec({

    fun freshRsaKey(): RSAPublicKey {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        return gen.generateKeyPair().public as RSAPublicKey
    }

    fun jwksJson(keys: Map<String, RSAPublicKey>): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val entries =
            keys.entries.joinToString(",") { (kid, key) ->
                val n = encoder.encodeToString(key.modulus.toByteArray())
                val e = encoder.encodeToString(key.publicExponent.toByteArray())
                """{"kty":"RSA","kid":"$kid","n":"$n","e":"$e"}"""
            }
        return """{"keys":[$entries]}"""
    }

    class TickClock(start: Instant) : Clock() {
        private var now: Instant = start

        fun advance(d: Duration) {
            now = now.plus(d)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    val keyA = freshRsaKey()
    val keyB = freshRsaKey()

    fun harness(
        maxAge: Long = 3600,
        gateSecondRequest: CompletableDeferred<Unit>? = null,
        secondRequestStarted: CompletableDeferred<Unit>? = null,
    ): Triple<JwksCache, TickClock, AtomicInteger> {
        val requestCount = AtomicInteger(0)
        // Request 1 serves only kid-a; later requests serve the rotated doc (kid-a + kid-b).
        val docBefore = jwksJson(mapOf("kid-a" to keyA))
        val docAfter = jwksJson(mapOf("kid-a" to keyA, "kid-b" to keyB))
        val engine =
            MockEngine { _ ->
                val n = requestCount.incrementAndGet()
                if (n >= 2) {
                    secondRequestStarted?.complete(Unit)
                    gateSecondRequest?.await()
                }
                respond(
                    content = if (n == 1) docBefore else docAfter,
                    status = HttpStatusCode.OK,
                    headers =
                        headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            HttpHeaders.CacheControl to listOf("public, max-age=$maxAge"),
                        ),
                )
            }
        val clock = TickClock(Instant.parse("2026-06-11T00:00:00Z"))
        val cache = JwksCache(HttpClient(engine), "https://example.test/jwks", clock)
        return Triple(cache, clock, requestCount)
    }

    "TTL-valid cache serves a known kid without re-fetching" {
        val (cache, _, count) = harness()
        cache.keyFor("kid-a").shouldNotBeNull()
        cache.keyFor("kid-a").shouldNotBeNull()
        count.get() shouldBe 1
    }

    "unknown kid within the cooldown returns null without an upstream fetch" {
        val (cache, clock, count) = harness()
        cache.keyFor("kid-a").shouldNotBeNull()
        clock.advance(Duration.ofSeconds(30))
        cache.keyFor("kid-b").shouldBeNull()
        count.get() shouldBe 1
    }

    "unknown kid after the cooldown forces a fetch despite a TTL-valid cache (mid-TTL rotation)" {
        val (cache, clock, count) = harness()
        cache.keyFor("kid-a").shouldNotBeNull()
        clock.advance(Duration.ofSeconds(61))
        // Cache is still TTL-valid (max-age=3600); the rotated kid must resolve NOW.
        cache.keyFor("kid-b").shouldNotBeNull().modulus shouldBe keyB.modulus
        count.get() shouldBe 2
    }

    "a forced refresh re-arms the cooldown — bogus-kid flood stays bounded" {
        val (cache, clock, count) = harness()
        cache.keyFor("kid-a").shouldNotBeNull()
        clock.advance(Duration.ofSeconds(61))
        cache.keyFor("kid-b").shouldNotBeNull().modulus shouldBe keyB.modulus
        clock.advance(Duration.ofSeconds(10))
        cache.keyFor("kid-nonexistent").shouldBeNull()
        count.get() shouldBe 2
    }

    "concurrent forced refreshes collapse to a single upstream fetch" {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val (cache, clock, count) = harness(gateSecondRequest = gate, secondRequestStarted = started)
        cache.keyFor("kid-a").shouldNotBeNull()
        clock.advance(Duration.ofSeconds(61))
        coroutineScope {
            val first = async(Dispatchers.Default) { cache.keyFor("kid-b") }
            // Wait until the forced fetch is in flight, THEN start the second caller:
            // it snapshots lastRefreshAt while the peer holds the lock mid-fetch, blocks
            // on the mutex, and must reuse the peer's fresh document instead of re-fetching.
            started.await()
            val second = async(Dispatchers.Default) { cache.keyFor("kid-b") }
            gate.complete(Unit)
            first.await().shouldNotBeNull().modulus shouldBe keyB.modulus
            second.await().shouldNotBeNull().modulus shouldBe keyB.modulus
        }
        count.get() shouldBe 2
    }

    "expired cache re-fetches lazily on the next lookup" {
        val (cache, clock, count) = harness(maxAge = 100)
        cache.keyFor("kid-a").shouldNotBeNull()
        clock.advance(Duration.ofSeconds(101))
        cache.keyFor("kid-a").shouldNotBeNull()
        count.get() shouldBe 2
    }

    "parseCacheControlMaxAge honors the header and falls back on garbage" {
        JwksCache.parseCacheControlMaxAge("public, max-age=7200") shouldBe 7200
        JwksCache.parseCacheControlMaxAge(null) shouldBe JWKS_DEFAULT_CACHE_SECONDS
        JwksCache.parseCacheControlMaxAge("no-store") shouldBe JWKS_DEFAULT_CACHE_SECONDS
        JwksCache.parseCacheControlMaxAge("max-age=abc") shouldBe JWKS_DEFAULT_CACHE_SECONDS
    }
})
