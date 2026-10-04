package id.nearyou.app.post

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments. The scanned `.kt` files have no `//`
 *  inside string literals, so the naive line strip is safe here. */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * Static-source guards for the mobile-post-creation-screen invariants that are properties of the
 * source, not of a single render (tasks 7.6 / 7.8). Mirrors the repo's `VendorSdkLeakageScanTest`
 * file-scan idiom (walk to the repo root, read `.kt` text, assert). Runs in every variant (it is NOT
 * a `*ScreenTest` and needs no Compose runner).
 *
 * Covers: the no-hardcoded-UI-strings discipline on the composer; the never-widen-logging discipline
 * (HttpClientFactory stays `LogLevel.HEADERS`; the create client + repository never `println`/log the
 * body or coordinate, and the repository's diagnostic sink is coordinate-free by construction);
 * automatic-location-only (no map / pin / manual coordinate-entry / place-search affordance); a
 * feed-agnostic composer whose success only calls `onPostCreated()`, with the Nearby/Global refresh hoisted
 * into `appEntryProvider` as saveable state, not an event bus (#173, `feed-refresh-on-post-and-radius-backstop`).
 * NO manual-location follow-up exists — that scope was dropped by #144, not deferred.
 */
class PostCreationSourceGuardTest {
    private val repoRoot: File = findRepoRoot()

    /** Raw file text (for non-Kotlin source files). */
    private fun rawSource(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.exists(), "expected source file missing: $relativePath (resolved under $repoRoot)")
        return file.readText()
    }

    /** Kotlin source with comments stripped — the guards are about CODE behavior, so an explanatory
     *  KDoc that mentions a forbidden token (e.g. "MUST NOT println …") must not trip the scan. */
    private fun code(relativePath: String): String = rawSource(relativePath).stripComments()

    private val screen by lazy { code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationScreen.kt") }

    // ---- 7.6: no hardcoded UI strings; logging never widened ----

    // cap-upsell-parity (mobile-post-creation § "The retired rate-limit banner key is gone"): the 429 daily
    // cap is the shared cap dialog — the inline rate-limit banner and its string are removed.
    @Test
    fun retiredRateLimitBanner_isGone_andTheCapDialogIsUsed() {
        val uiState = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostCreationUiState.kt")
        assertFalse(uiState.contains("RATE_LIMITED"), "PostCreationBanner must declare no rate-limit member")
        val strings = rawSource("shared/resources/src/commonMain/composeResources/values/strings.xml")
        assertFalse(strings.contains("name=\"post_create_error_rate_limited\""), "the retired banner key must be gone")
        assertTrue(strings.contains("name=\"post_create_cap_upsell\""), "the post cap dialog body must be declared")
        assertTrue(screen.contains("DailyCapUpsellDialog("), "the composer surfaces its cap via the shared cap dialog")
    }

    @Test
    fun postCreationScreen_hasNoHardcodedUiStringLiterals() {
        // Every UI-string-bearing call site sources its text via stringResource — so no `Text("…")`,
        // no `text = "…"`, no `contentDescription = "…"` literal appears (const test-tags are fine).
        assertFalse(screen.contains("Text(\""), "PostCreationScreen has a hardcoded Text(\"…\") literal")
        assertFalse(Regex("""text\s*=\s*"""").containsMatchIn(screen), "PostCreationScreen has a hardcoded text = \"…\" literal")
        assertFalse(
            Regex("""contentDescription\s*=\s*"""").containsMatchIn(screen),
            "PostCreationScreen has a hardcoded contentDescription = \"…\" literal",
        )
        // Positive: the composer copy flows through stringResource(Res.string.*).
        assertTrue(screen.contains("stringResource(Res.string.post_create_title)"), "title must use stringResource")
        assertTrue(screen.contains("stringResource(Res.string.cta_post)"), "CTA must use stringResource")
    }

    @Test
    fun httpClientFactory_logLevelIsNotWidenedPastHeaders() {
        val factory = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/network/HttpClientFactory.kt")
        assertTrue(factory.contains("LogLevel.HEADERS"), "the client log level must remain LogLevel.HEADERS")
        assertFalse(factory.contains("LogLevel.BODY"), "logging must NOT be widened to LogLevel.BODY (the coordinate is in the body)")
        assertFalse(factory.contains("LogLevel.ALL"), "logging must NOT be widened to LogLevel.ALL")
    }

    @Test
    fun createClientAndRepository_neverLogTheBodyOrCoordinate() {
        val apiClient = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/post/PostCreationApiClient.kt")
        val repository = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/post/CreatePostRepository.kt")
        for ((name, src) in listOf("PostCreationApiClient" to apiClient, "CreatePostRepository" to repository)) {
            assertFalse(src.contains("println"), "$name must not println (the body/coordinate must never be logged)")
            assertFalse(src.contains("LogLevel.BODY"), "$name must not widen logging to BODY")
            assertFalse(src.contains("LogLevel.ALL"), "$name must not widen logging to ALL")
        }
        // The diagnostic sink is coordinate-free BY CONSTRUCTION — it accepts only status/errorCode
        // primitives, never the content or the LatLng (design D7).
        assertTrue(
            repository.contains("diagnosticLog: (status: Int, errorCode: String?) -> Unit"),
            "the diagnosticLog sink signature must be coordinate-free (status + errorCode only)",
        )
    }

    // ---- 7.8: automatic-location-only; no Nearby reload on success; deferral bookkeeping ----

    @Test
    fun postCreationScreen_hasNoManualLocationAffordance() {
        // Automatic-only (#144): no map, draggable pin, manual coordinate entry, or place search.
        for (forbidden in listOf("GoogleMap", "MapView", "rememberCameraPositionState", "Marker", "Autocomplete", "placeSearch")) {
            assertFalse(screen.contains(forbidden), "PostCreationScreen must have no manual-location affordance ($forbidden)")
        }
        // The screen never even names the coordinate — it is acquired entirely inside the repository.
        assertFalse(screen.contains("latitude"), "the composer must not reference latitude (no manual coordinate entry)")
        assertFalse(screen.contains("longitude"), "the composer must not reference longitude (no manual coordinate entry)")
    }

    @Test
    fun postCreationScreen_staysFeedAgnostic_successOnlyInvokesOnPostCreated() {
        // Success invokes the hoisted callback; the composer knows nothing about the feeds it refreshes
        // (mobile-post-creation § "Successful post returns to Home and refreshes the Nearby and Global feeds").
        assertTrue(screen.contains("onPostCreated()"), "success must invoke the hoisted onPostCreated callback")
        assertFalse(screen.contains("NearbyTimeline"), "the composer must not reference NearbyTimeline")
        assertFalse(screen.contains("GlobalTimeline"), "the composer must not reference GlobalTimeline")
        assertFalse(screen.contains("reload"), "the composer must not trigger a feed reload itself")
        assertFalse(screen.contains("ResultEventBus"), "the composer must not consume a Nav3 ResultEventBus result")
    }

    @Test
    fun feedReloadSignal_isHoistedSaveableState_notAnEventBus() {
        // #173: the signal lives in appEntryProvider — a one-shot flag raised BEFORE the pop, consumed by the
        // HomeRoute entry into a rememberSaveable reload key — never a Channel/SharedFlow bus.
        val provider = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/AppEntryProvider.kt")
        assertTrue(provider.contains("rememberSaveable { mutableIntStateOf(0) }"), "the HomeRoute entry holds a saveable key")
        assertTrue(provider.contains("feedReloadKey = feedReloadKey"), "the key is passed down to the shell")
        assertTrue(
            Regex("""postCreated\.value = true\s+backStack\.removeLastOrNull\(\)""").containsMatchIn(provider),
            "onPostCreated raises the signal, then pops the composer",
        )
        val feedViewModels =
            listOf("Nearby", "Global").map {
                code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/${it}TimelineViewModel.kt")
            }
        for (source in listOf(provider) + feedViewModels) {
            for (bus in listOf("Channel", "SharedFlow", "ResultEventBus")) {
                assertFalse(source.contains(bus), "the feed reload signal must not be an event bus ($bus)")
            }
        }
    }

    private companion object {
        /** Walks up from the test working directory to the repo root (the dir holding settings.gradle.kts),
         *  so the scan resolves whether `user.dir` is the module dir or the repo root. */
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
