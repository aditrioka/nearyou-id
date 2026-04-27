package id.nearyou.app.health

import id.nearyou.app.core.domain.health.ProbeError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Duration
import javax.net.ssl.SSLHandshakeException

/**
 * Unit tests for [KtorSupabaseRealtimeProbe]. Verifies the contract per the
 * `health-check` capability:
 *
 *  1. Any HTTP response (200, 401, 404, 500, 502, 503) MUST map to ok=true.
 *     The probe asserts EDGE reachability, not data-plane authorization.
 *  2. TLS / DNS / connection-refused / timeout failures MUST map to ok=false
 *     with the canonical 5-value error vocabulary.
 *  3. Stack traces and full exception messages MUST NOT leak into ProbeResult.
 *
 * Backfilled per `health-check-test-coverage-gaps` follow-up (task 5.8 of the
 * archived `health-check-endpoints` change).
 */
class KtorSupabaseRealtimeProbeTest : StringSpec(
    {
        val supabaseUrl = "https://hvlbfbuuorhackrlbouo.supabase.co"

        fun probeWithStatus(status: HttpStatusCode): KtorSupabaseRealtimeProbe {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = ByteReadChannel(""),
                        status = status,
                        headers = headersOf(),
                    )
                }
            return KtorSupabaseRealtimeProbe(HttpClient(engine), supabaseUrl)
        }

        fun probeThrowing(throwable: Throwable): KtorSupabaseRealtimeProbe {
            val engine = MockEngine { _ -> throw throwable }
            return KtorSupabaseRealtimeProbe(HttpClient(engine), supabaseUrl)
        }

        fun probeDelaying(delayMs: Long): KtorSupabaseRealtimeProbe {
            val engine =
                MockEngine { _ ->
                    delay(delayMs)
                    respond(
                        content = ByteReadChannel(""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(),
                    )
                }
            return KtorSupabaseRealtimeProbe(HttpClient(engine), supabaseUrl)
        }

        // --- ok=true paths: any HTTP response shape ------------------------

        "200 OK → ok=true" {
            val result = runBlocking { probeWithStatus(HttpStatusCode.OK).ping(Duration.ofMillis(500)) }
            result.ok shouldBe true
            result.error shouldBe null
        }

        "401 Unauthorized → ok=true (data-plane auth not asserted)" {
            val result =
                runBlocking { probeWithStatus(HttpStatusCode.Unauthorized).ping(Duration.ofMillis(500)) }
            result.ok shouldBe true
            result.error shouldBe null
        }

        "404 Not Found → ok=true (edge reachable; bare /rest/v1/ may 404)" {
            val result = runBlocking { probeWithStatus(HttpStatusCode.NotFound).ping(Duration.ofMillis(500)) }
            result.ok shouldBe true
        }

        "500 Internal Server Error → ok=true (edge reachable, upstream degraded)" {
            val result =
                runBlocking { probeWithStatus(HttpStatusCode.InternalServerError).ping(Duration.ofMillis(500)) }
            result.ok shouldBe true
        }

        "502 Bad Gateway → ok=true" {
            val result =
                runBlocking { probeWithStatus(HttpStatusCode.BadGateway).ping(Duration.ofMillis(500)) }
            result.ok shouldBe true
        }

        "503 Service Unavailable → ok=true" {
            val result =
                runBlocking { probeWithStatus(HttpStatusCode.ServiceUnavailable).ping(Duration.ofMillis(500)) }
            result.ok shouldBe true
        }

        // --- ok=false paths: error vocabulary mapping ----------------------

        "UnknownHostException → ok=false, error=dns_failure" {
            val result =
                runBlocking {
                    probeThrowing(UnknownHostException("nearyou-test.invalid"))
                        .ping(Duration.ofMillis(500))
                }
            result.ok shouldBe false
            result.error shouldBe ProbeError.DNS_FAILURE
        }

        "SSLHandshakeException → ok=false, error=tls_failure" {
            val result =
                runBlocking {
                    probeThrowing(SSLHandshakeException("cert chain not trusted"))
                        .ping(Duration.ofMillis(500))
                }
            result.ok shouldBe false
            result.error shouldBe ProbeError.TLS_FAILURE
        }

        "ConnectException → ok=false, error=connection_refused" {
            val result =
                runBlocking {
                    probeThrowing(ConnectException("Connection refused"))
                        .ping(Duration.ofMillis(500))
                }
            result.ok shouldBe false
            result.error shouldBe ProbeError.CONNECTION_REFUSED
        }

        "Generic Throwable → ok=false, error=unknown" {
            val result =
                runBlocking {
                    probeThrowing(IllegalStateException("something unexpected"))
                        .ping(Duration.ofMillis(500))
                }
            result.ok shouldBe false
            result.error shouldBe ProbeError.UNKNOWN
        }

        "Exceeds timeout via cooperative delay → ok=false, error=timeout" {
            val result = runBlocking { probeDelaying(1000L).ping(Duration.ofMillis(200)) }
            result.ok shouldBe false
            result.error shouldBe ProbeError.TIMEOUT
            // Latency reflects the timeout budget, not the underlying delay.
            result.latencyMs shouldBeGreaterThanOrEqual 200L
        }

        // --- anti-info-leak invariant --------------------------------------

        "Error field uses fixed-vocabulary only — no exception details leak" {
            val result =
                runBlocking {
                    probeThrowing(
                        IllegalStateException(
                            "leaked detail at sun.nio.ch.SocketChannelImpl.read(...)",
                        ),
                    ).ping(Duration.ofMillis(500))
                }
            result.ok shouldBe false
            val allowed =
                setOf(
                    ProbeError.TIMEOUT,
                    ProbeError.CONNECTION_REFUSED,
                    ProbeError.DNS_FAILURE,
                    ProbeError.TLS_FAILURE,
                    ProbeError.UNKNOWN,
                )
            (result.error in allowed) shouldBe true
            val err = result.error ?: ""
            err.contains("sun.nio") shouldBe false
            err.contains("SocketChannelImpl") shouldBe false
            err.contains("at ") shouldBe false
        }

        // --- URL composition -----------------------------------------------

        "Probe URL composes /rest/v1/ regardless of trailing slash on supabaseUrl" {
            // Both forms should produce the same probe URL.
            val capturedUrls = mutableListOf<String>()
            val engineNoSlash =
                MockEngine { req ->
                    capturedUrls += req.url.toString()
                    respond("", HttpStatusCode.OK, headersOf())
                }
            val engineWithSlash =
                MockEngine { req ->
                    capturedUrls += req.url.toString()
                    respond("", HttpStatusCode.OK, headersOf())
                }
            val probeNoSlash = KtorSupabaseRealtimeProbe(HttpClient(engineNoSlash), "https://example.test")
            val probeWithSlash = KtorSupabaseRealtimeProbe(HttpClient(engineWithSlash), "https://example.test/")
            runBlocking {
                probeNoSlash.ping(Duration.ofMillis(500))
                probeWithSlash.ping(Duration.ofMillis(500))
            }
            capturedUrls.size shouldBe 2
            capturedUrls[0] shouldBe "https://example.test/rest/v1/"
            capturedUrls[1] shouldBe "https://example.test/rest/v1/"
        }
    },
)
