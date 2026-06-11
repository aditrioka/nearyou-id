package id.nearyou.app.screens.post

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.CreatePostRepository
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.post.PostCreationApiClient
import id.nearyou.app.post.PostCreationOutcome
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.timeline.StubLocationProvider
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.cta_post
import id.nearyou.resources.generated.resources.post_create_content_placeholder
import id.nearyou.resources.generated.resources.post_create_error_empty
import id.nearyou.resources.generated.resources.post_create_error_location
import id.nearyou.resources.generated.resources.post_create_error_moderated
import id.nearyou.resources.generated.resources.post_create_error_too_long
import id.nearyou.resources.generated.resources.post_create_loading
import id.nearyou.resources.generated.resources.post_create_location_unavailable
import id.nearyou.resources.generated.resources.post_create_title
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.jetbrains.compose.resources.stringResource
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml) — pins the
// composer copy alongside the render assertions.
private const val TITLE = "Buat postingan"
private const val PLACEHOLDER = "Apa yang sedang terjadi di sekitarmu?"
private const val COUNTER_ZERO = "0/280"
private const val CTA_POST = "Posting"
private const val LOADING = "Sedang memposting…"
private const val ERR_EMPTY = "Postingan tidak boleh kosong."
private const val ERR_TOO_LONG = "Postingan maksimal 280 karakter."
private const val ERR_LOCATION = "Lokasi kamu di luar jangkauan layanan."
private const val ERR_MODERATED = "Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."
private const val LOC_UNAVAILABLE = "Aktifkan lokasi untuk membuat postingan."
private const val OPEN_SETTINGS = "Buka Pengaturan"
private const val ERR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"

// A 201 create body that ECHOES the author's actual coordinate — the minimal CreatedPostDto reads
// only `id`, so neither -6.21 nor 106.85 must ever reach the rendered tree (PII discipline).
private const val ECHO_COORD_201_BODY =
    """{"id":"p1","content":"halo","latitude":-6.21,"longitude":106.85,""" +
        """"distance_m":null,"created_at":"2026-06-04T00:00:00Z"}"""

/**
 * Render + interaction coverage of `PostCreationScreen` via the Robolectric-backed CMP UI runner
 * (task 7.3), migrated off Voyager. The composer is composed directly with a recording `onPostCreated`
 * callback (the Nav3 pop is `backStack.removeLastOrNull()`, wired by `appEntryProvider`; the screen's
 * contract is "Success → invoke onPostCreated", which the recording callback asserts — the spec § "Success
 * pops back to the home surface" explicitly allows a recording pop callback). The outcome→state
 * projection is covered purely by `PostCreationUiStateTest`; this suite verifies the composable renders
 * the canonical strings for the initial / loading / success / per-error states, that typing drives the
 * CTA-enable gate, that the LocationUnavailable settings CTA invokes `openAppSettings()`, that success
 * invokes the pop, and that NO coordinate / id leaks into the rendered tree (PII discipline).
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for why this is retained for the
 * multi-test JVM startKoin/stopKoin cycle.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class PostCreationScreenTest {
    private lateinit var fakeController: FakeLocationPermissionController

    private fun installKoin(flow: CreatePostFlow) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fakeController = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
        startKoin {
            modules(
                module {
                    single { flow }
                    single<LocationPermissionController> { fakeController }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun initialRender_showsTitlePlaceholderZeroCounterDisabledCta() {
        installKoin(FakeCreatePostFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(PLACEHOLDER).assertExists()
            onNodeWithText(COUNTER_ZERO).assertExists()
            onNodeWithText(CTA_POST).assertIsNotEnabled()
        }
    }

    @Test
    fun typingValidContent_enablesCta_andUpdatesCounter() {
        installKoin(FakeCreatePostFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText("4/280").assertExists()
            onNodeWithText(CTA_POST).assertIsEnabled()
        }
    }

    @Test
    fun typing281CodePoints_disablesCta() {
        installKoin(FakeCreatePostFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("a".repeat(281))
            onNodeWithText("281/280").assertExists()
            onNodeWithText(CTA_POST).assertIsNotEnabled()
        }
    }

    @Test
    fun loadingState_showsLoadingCopyAndDisabledCta() {
        installKoin(FakeCreatePostFlow(suspendForever = true))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            onNodeWithText(LOADING).assertExists()
            onNodeWithText(LOADING).assertIsNotEnabled()
        }
    }

    @Test
    fun contentEmptyOutcome_showsEmptyBanner() = assertBanner(PostCreationOutcome.ContentEmpty, ERR_EMPTY)

    @Test
    fun contentTooLongOutcome_showsTooLongBanner() = assertBanner(PostCreationOutcome.ContentTooLong, ERR_TOO_LONG)

    @Test
    fun locationOutOfBoundsOutcome_showsLocationBanner() = assertBanner(PostCreationOutcome.LocationOutOfBounds, ERR_LOCATION)

    @Test
    fun contentRejectedOutcome_showsKeywordFreeModerationBanner() = assertBanner(PostCreationOutcome.ContentRejected, ERR_MODERATED)

    private fun assertBanner(
        outcome: PostCreationOutcome,
        expectedCopy: String,
    ) {
        installKoin(FakeCreatePostFlow(outcome = outcome))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            onNodeWithText(expectedCopy).assertExists()
        }
    }

    @Test
    fun locationUnavailableOutcome_showsBannerAndSettingsCta_thatInvokesOpenAppSettings() {
        installKoin(FakeCreatePostFlow(outcome = PostCreationOutcome.LocationUnavailable))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            onNodeWithText(LOC_UNAVAILABLE).assertExists()
            assertEquals(0, fakeController.openAppSettingsCount)
            onNodeWithText(OPEN_SETTINGS).performClick()
            waitForIdle()
            assertEquals(1, fakeController.openAppSettingsCount, "Buka Pengaturan deep-links to settings")
        }
    }

    @Test
    fun networkErrorOutcome_showsNetworkBannerAndRetry_thatReInvokesSubmit() {
        val fake = FakeCreatePostFlow(outcome = PostCreationOutcome.NetworkError)
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            onNodeWithText(ERR_NETWORK).assertExists()
            assertEquals(1, fake.submitInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitForIdle()
            assertEquals(2, fake.submitInvocationCount, "retry re-invokes submit")
        }
    }

    @Test
    fun success_invokesPopCallback_andRendersNoSuccessId() {
        var popped = 0
        installKoin(FakeCreatePostFlow(outcome = PostCreationOutcome.Success("SECRET-POST-ID-9999")))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = { popped++ }) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            // Success → the composer invokes its pop callback (appEntryProvider wires it to
            // backStack.removeLastOrNull()).
            assertEquals(1, popped, "Success invokes the pop callback exactly once")
            // PII: the success post id is never rendered.
            onNodeWithText("SECRET-POST-ID-9999", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun success_doesNotRenderTheEchoedActualCoordinate() {
        // Drives the REAL repository over a MockEngine whose 201 echoes the author's actual
        // coordinate; the minimal CreatedPostDto reads only `id`, so the echo must never render.
        val mockHttp =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine =
                    MockEngine {
                        respond(ECHO_COORD_201_BODY, HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
                    },
                installLogging = false,
                nowMillis = { 0L },
            )
        val granted = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
        val repository = CreatePostRepository(PostCreationApiClient(mockHttp), StubLocationProvider(), granted)
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single<CreatePostFlow> { repository }
                    single<LocationPermissionController> { granted }
                },
            )
        }
        var popped = false
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = { popped = true }) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            // The REAL repository's submit is an async network call (not synchronous like the fake),
            // so waitForIdle may return before the success pop callback fires — wait for the callback.
            waitUntil(timeoutMillis = 5_000) { popped }
            onNodeWithText("-6.21", substring = true).assertDoesNotExist()
            onNodeWithText("106.85", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun postStrings_haveExactCanonicalText() {
        var title = ""
        var placeholder = ""
        var ctaPost = ""
        var loading = ""
        var errEmpty = ""
        var errTooLong = ""
        var errLocation = ""
        var errModerated = ""
        var locUnavailable = ""
        runComposeUiTest {
            setContent {
                title = stringResource(Res.string.post_create_title)
                placeholder = stringResource(Res.string.post_create_content_placeholder)
                ctaPost = stringResource(Res.string.cta_post)
                loading = stringResource(Res.string.post_create_loading)
                errEmpty = stringResource(Res.string.post_create_error_empty)
                errTooLong = stringResource(Res.string.post_create_error_too_long)
                errLocation = stringResource(Res.string.post_create_error_location)
                errModerated = stringResource(Res.string.post_create_error_moderated)
                locUnavailable = stringResource(Res.string.post_create_location_unavailable)
            }
            waitForIdle()
        }
        assertEquals(TITLE, title)
        assertEquals(PLACEHOLDER, placeholder)
        assertEquals(CTA_POST, ctaPost)
        assertEquals(LOADING, loading)
        assertEquals(ERR_EMPTY, errEmpty)
        assertEquals(ERR_TOO_LONG, errTooLong)
        assertEquals(ERR_LOCATION, errLocation)
        // Docs-canonical (post-creation spec § Verdict.Reject) — keyword-free moderation copy.
        assertEquals(ERR_MODERATED, errModerated)
        assertEquals(LOC_UNAVAILABLE, locUnavailable)
    }
}
