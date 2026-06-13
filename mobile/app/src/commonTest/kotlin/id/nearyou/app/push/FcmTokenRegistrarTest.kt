package id.nearyou.app.push

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compose-free coverage of [FcmTokenRegistrar] (`mobile-fcm-token-registration`, tasks.md 5.3): the
 * acquire→register happy path, `NoTokenAvailable` on a null token (incl. the iOS-denied chain), token-refresh
 * re-registration, `TransportError` swallowed + re-attempt on the next trigger, concurrent-registration
 * tolerance, and the token-free diagnostic guarantee. Uses a [FakeFcmTokenProvider] + a real
 * [FcmTokenApiClient] over a MockEngine (so the registrar consumes genuine outcomes).
 */
class FcmTokenRegistrarTest {
    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClientFactory.create(
            installTimeouts = false,
            apiBaseUrl = "http://test.local",
            tokenStore = InMemoryTokenStore(),
            sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
            engine = MockEngine(handler),
            installLogging = false,
            nowMillis = { 0L },
        )

    private fun registrar(
        provider: FakeFcmTokenProvider,
        diagnostics: MutableList<String> = mutableListOf(),
        handler: MockRequestHandler,
    ): FcmTokenRegistrar =
        FcmTokenRegistrar(
            provider = provider,
            apiClient = FcmTokenApiClient(client(handler), platform = "android", appVersion = "1.0"),
            platform = "android",
            diagnosticLog = { diagnostics.add(it) },
        )

    @Test
    fun `acquire then register happy path`() =
        runTest {
            val provider = FakeFcmTokenProvider(token = "tok-1")
            var registeredToken: String? = null
            val reg =
                registrar(provider) { request ->
                    registeredToken = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                }

            val outcome = reg.registerCurrentToken()

            assertEquals(FcmRegistrationOutcome.Registered, outcome)
            assertEquals(1, provider.currentTokenCalls)
            assertTrue(registeredToken!!.contains("tok-1"), "the acquired token must be registered")
        }

    @Test
    fun `null token short-circuits to NoTokenAvailable without a request`() =
        runTest {
            val provider = FakeFcmTokenProvider(token = null)
            var requestIssued = false
            val reg =
                registrar(provider) {
                    requestIssued = true
                    respond("", HttpStatusCode.NoContent)
                }

            val outcome = reg.registerCurrentToken()

            assertEquals(FcmRegistrationOutcome.NoTokenAvailable, outcome)
            assertFalse(requestIssued, "no request must be issued when the provider yields no token")
        }

    @Test
    fun `iOS denied authorization yields NoTokenAvailable end-to-end`() =
        runTest {
            // The iOS denied-auth path is modelled by the provider returning null (IosFcmTokenProvider
            // returns null on denied authorization) — the registrar maps it to NoTokenAvailable with no POST.
            val provider = FakeFcmTokenProvider(token = null)
            var requestIssued = false
            val reg =
                registrar(provider) {
                    requestIssued = true
                    respond("", HttpStatusCode.NoContent)
                }

            assertEquals(FcmRegistrationOutcome.NoTokenAvailable, reg.registerCurrentToken())
            assertFalse(requestIssued)
        }

    @Test
    fun `transport error is swallowed and re-attempted on the next trigger`() =
        runTest {
            val provider = FakeFcmTokenProvider(token = "tok-1")
            var calls = 0
            val reg =
                registrar(provider) {
                    calls++
                    if (calls == 1) respond("", HttpStatusCode.InternalServerError) else respond("", HttpStatusCode.NoContent)
                }

            // First trigger: transport error, swallowed (no throw — it is a returned value).
            assertEquals(FcmRegistrationOutcome.TransportError, reg.registerCurrentToken())
            // Second trigger (a later session-active transition): succeeds.
            assertEquals(FcmRegistrationOutcome.Registered, reg.registerCurrentToken())
            assertEquals(2, calls)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `token refresh re-registers the new token`() =
        runTest {
            val provider = FakeFcmTokenProvider(token = "tok-1")
            val registered = mutableListOf<String>()
            val reg =
                registrar(provider) { request ->
                    registered.add((request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString())
                    respond("", HttpStatusCode.NoContent)
                }

            // UnconfinedTestDispatcher runs the collector eagerly up to its first suspension (the
            // SharedFlow subscription), so it is subscribed BEFORE the emit — no subscription race.
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reg.observeTokenRefreshes() }
            provider.emitRefresh("tok-2")
            advanceUntilIdle()
            job.cancel()

            assertTrue(registered.any { it.contains("tok-2") }, "a rotated token must be re-registered; got $registered")
        }

    @Test
    fun `concurrent registrations both proceed without client-side dedup`() =
        runTest {
            val provider = FakeFcmTokenProvider(token = "tok-1")
            var calls = 0
            val reg =
                registrar(provider) {
                    calls++
                    respond("", HttpStatusCode.NoContent)
                }

            val a = launch { reg.registerCurrentToken() }
            val b = launch { reg.registerCurrentToken() }
            a.join()
            b.join()

            assertEquals(2, calls, "both overlapping registrations proceed — no client-side mutex")
        }

    @Test
    fun `diagnostic logging never receives the token value`() =
        runTest {
            val provider = FakeFcmTokenProvider(token = "super-secret-token-xyz")
            val diagnostics = mutableListOf<String>()
            val reg =
                registrar(provider, diagnostics) { respond("", HttpStatusCode.NoContent) }

            reg.registerCurrentToken()

            assertTrue(diagnostics.isNotEmpty(), "a token-free diagnostic line is emitted")
            assertTrue(
                diagnostics.none { it.contains("super-secret-token-xyz") },
                "no diagnostic line may contain the raw token; got $diagnostics",
            )
        }
}
