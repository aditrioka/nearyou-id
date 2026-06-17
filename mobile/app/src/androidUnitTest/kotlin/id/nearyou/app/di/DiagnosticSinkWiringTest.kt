package id.nearyou.app.di

import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.diagnostics.DiagnosticSink
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.timeline.GlobalTimelineRepository
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.app.timeline.NearbyTimelineRepository
import id.nearyou.app.timeline.StubLocationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

private val JSON_HEADERS = headersOf("Content-Type", "application/json")

/** Strips block comments (incl. KDoc) then line comments (per `feedback_source_scan_guard_strip_comments`). */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * 9.10 — `mobile-nearby-timeline` / `mobile-global-timeline` § "MobileModule wires a non-no-op
 * diagnostic sink" (mobile-session-expiry-and-proactive-refresh, design D6). Two guards:
 *
 *  - **DI-graph sink-capture spy:** the real [mobileModule] is loaded with the [DiagnosticSink] binding
 *    overridden by a capturing spy (and the shared `HttpClient` overridden by a MockEngine that returns
 *    400). Resolving each timeline repository from the GRAPH and driving a 400 fetch proves the repo
 *    single passes the graph's real sink (not the hardcoded no-op default) — the spy captures the
 *    coordinate-free diagnostic. (NOT a repository-level fake — the wiring under test is `MobileModule`.)
 *  - **Source scan:** the comments-stripped `diagnosticLog(...)` call sites in both repositories pass no
 *    coordinate and no token (only status / cause-message strings). Mirrors the `*NoFetchScanTest`
 *    precedent; runs in every variant.
 */
class DiagnosticSinkWiringTest {
    @BeforeTest
    fun ensureNoLeakedKoinState() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun startGraphWithSpy(captured: MutableList<String>) =
        startKoin {
            modules(
                mobileModule,
                module {
                    // Platform leaf deps (production binds these in each platformModule) …
                    single<TokenStore> { InMemoryTokenStore() }
                    single<LocationProvider>(named("deviceLocation")) { StubLocationProvider() }
                    single<LocationPermissionController> { FakeLocationPermissionController() }
                    // … plus overrides (allowOverride defaults on in Koin 4.x): a MockEngine client that
                    // returns 400 for the timeline fetches, and the DiagnosticSink replaced by a spy.
                    single<HttpClient> {
                        HttpClientFactory.create(
                            installTimeouts = false,
                            apiBaseUrl = "http://test.local",
                            tokenStore = get(),
                            sessionInvalidator = get(),
                            engine =
                                MockEngine {
                                    respond("""{"error":{"code":"invalid_request"}}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                                },
                            installLogging = false,
                        )
                    }
                    single<DiagnosticSink> { DiagnosticSink { message -> captured.add(message) } }
                },
            )
        }.koin

    @Test
    fun mobileModule_wiresNearbyRepositoryWithTheRealSink() =
        runTest {
            val captured = mutableListOf<String>()
            val koin = startGraphWithSpy(captured)

            koin.get<NearbyTimelineRepository>().loadFirstPage()

            assertTrue(
                captured.any { it.contains("nearby_invalid_request") },
                "MobileModule must wire NearbyTimelineRepository with the real (graph) diagnostic sink, not the no-op default: $captured",
            )
        }

    @Test
    fun mobileModule_wiresGlobalRepositoryWithTheRealSink() =
        runTest {
            val captured = mutableListOf<String>()
            val koin = startGraphWithSpy(captured)

            koin.get<GlobalTimelineRepository>().loadFirstPage()

            assertTrue(
                captured.any { it.contains("global_invalid_request") },
                "MobileModule must wire GlobalTimelineRepository with the real (graph) diagnostic sink, not the no-op default: $captured",
            )
        }

    @Test
    fun diagnosticLogCallSites_passNoCoordinateNorToken() {
        val repoRoot = findRepoRoot()
        val files =
            listOf(
                "mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/NearbyTimelineRepository.kt",
                "mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/GlobalTimelineRepository.kt",
                // Following also carries coordinates in the response BODY (not the URL); its loadFirstPage +
                // load-more diagnostics must stay coordinate-free too (mobile-nearby-timeline-infinite-scroll
                // security review). The whole-file scan covers every diagnosticLog(...) site, incl. loadMore.
                "mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/FollowingTimelineRepository.kt",
            )
        // Coordinate / token identifiers that must never reach the diagnostic sink.
        val forbidden = listOf("latitude", "longitude", "lat", "lng", "coord", "token", "Token", "location")
        val argRegex = Regex("""diagnosticLog\(([^)]*)\)""")

        for (path in files) {
            val src = File(repoRoot, path).readText().stripComments()
            for (match in argRegex.findAll(src)) {
                val arg = match.groupValues[1]
                for (needle in forbidden) {
                    assertTrue(
                        !arg.contains(needle),
                        "$path: a diagnosticLog(...) argument must not carry a coordinate/token ('$needle'): $arg",
                    )
                }
            }
        }
    }

    private companion object {
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root from ${System.getProperty("user.dir")}")
        }
    }
}
