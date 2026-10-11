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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

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
        @Volatile private var now: Instant = start

        /** Runs on every [instant] read — lets a test park a caller at an exact point in [JwksCache.keyFor]. */
        @Volatile var onInstant: (() -> Unit)? = null

        fun advance(d: Duration) {
            now = now.plus(d)
        }

        override fun instant(): Instant {
            onInstant?.invoke()
            return now
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    val keyA = freshRsaKey()
    val keyB = freshRsaKey()

    fun harness(
        maxAge: Long = 3600,
        gate: CompletableDeferred<Unit>? = null,
        gateReached: CompletableDeferred<Unit>? = null,
        gateFromRequest: Int = 2,
    ): Triple<JwksCache, TickClock, AtomicInteger> {
        val requestCount = AtomicInteger(0)
        // Request 1 serves only kid-a; later requests serve the rotated doc (kid-a + kid-b).
        val docBefore = jwksJson(mapOf("kid-a" to keyA))
        val docAfter = jwksJson(mapOf("kid-a" to keyA, "kid-b" to keyB))
        val engine =
            MockEngine { _ ->
                val n = requestCount.incrementAndGet()
                if (n >= gateFromRequest) {
                    gateReached?.complete(Unit)
                    gate?.await()
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

    "unknown kid on a cold cache costs exactly one upstream fetch" {
        val (cache, _, count) = harness()
        cache.keyFor("kid-nonexistent").shouldBeNull()
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

    // #567: a caller that read the stale entry before a peer's forced fetch landed, but reaches
    // the mutex after it, must reuse the peer's document. The second caller is parked at its
    // Nth clock read (1 = the TTL check in current(), 2 = the cooldown check), both before the
    // mutex, while the peer's fetch completes. With the refresh snapshot taken inside
    // forceRefresh() (after those reads), read 2 re-fetched upstream (count 3) and read 1
    // returned null (the peer's fetch re-armed the cooldown).
    listOf(1, 2).forEach { parkAtRead ->
        "concurrent forced refreshes collapse to a single upstream fetch (peer lands at clock read $parkAtRead)" {
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val (cache, clock, count) = harness(gate = gate, gateReached = started)
            cache.keyFor("kid-a").shouldNotBeNull()
            clock.advance(Duration.ofSeconds(61))
            withTimeout(30.seconds) {
                val firstDone = CountDownLatch(1)
                val first = async(Dispatchers.IO) { cache.keyFor("kid-b").also { firstDone.countDown() } }
                // The peer now holds the lock mid-fetch and reads no clock until the gate
                // opens, so every read counted below is the second caller's.
                started.await()
                val reads = AtomicInteger(0)
                clock.onInstant = {
                    if (reads.incrementAndGet() == parkAtRead) {
                        clock.onInstant = null
                        gate.complete(Unit)
                        firstDone.await(10, TimeUnit.SECONDS) shouldBe true
                    }
                }
                val second = async(Dispatchers.IO) { cache.keyFor("kid-b") }
                first.await().shouldNotBeNull().modulus shouldBe keyB.modulus
                second.await().shouldNotBeNull().modulus shouldBe keyB.modulus
            }
            count.get() shouldBe 2
        }
    }

    // Mutex contention (01-#6 flood control): the second caller reaches refreshMutex while the
    // peer still holds it mid-fetch. UNDISPATCHED runs the caller on this thread up to its first
    // suspension point, so when async() returns it is parked on the lock (or, with no lock,
    // already committed to its own fetch = request 3) before the gate opens. Deterministic.
    "concurrent forced refreshes collapse to a single upstream fetch (peer still mid-fetch)" {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val (cache, clock, count) = harness(gate = gate, gateReached = started)
        cache.keyFor("kid-a").shouldNotBeNull()
        clock.advance(Duration.ofSeconds(61))
        withTimeout(30.seconds) {
            val first = async(Dispatchers.IO) { cache.keyFor("kid-b") }
            started.await()
            val second = async(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { cache.keyFor("kid-b") }
            gate.complete(Unit)
            first.await().shouldNotBeNull().modulus shouldBe keyB.modulus
            second.await().shouldNotBeNull().modulus shouldBe keyB.modulus
        }
        count.get() shouldBe 2
    }

    // Same contention on the lazy path: refresh()'s lock + under-lock re-check collapse
    // concurrent cold-cache callers into one upstream fetch.
    "concurrent cold-cache lookups collapse to a single upstream fetch" {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val (cache, _, count) = harness(gate = gate, gateReached = started, gateFromRequest = 1)
        withTimeout(30.seconds) {
            val first = async(Dispatchers.IO) { cache.keyFor("kid-a") }
            started.await()
            val second = async(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { cache.keyFor("kid-a") }
            gate.complete(Unit)
            first.await().shouldNotBeNull().modulus shouldBe keyA.modulus
            second.await().shouldNotBeNull().modulus shouldBe keyA.modulus
        }
        count.get() shouldBe 1
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
