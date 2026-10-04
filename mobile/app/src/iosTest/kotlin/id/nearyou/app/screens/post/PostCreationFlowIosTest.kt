package id.nearyou.app.screens.post

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.image.FakeImagePicker
import id.nearyou.app.image.FakeImageUploadRepository
import id.nearyou.app.image.ImagePicker
import id.nearyou.app.image.ImageUploader
import id.nearyou.app.location.FakeLocationPermissionController
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionStatus
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.FakeCreatePostFlow
import id.nearyou.app.post.PostCreationOutcome
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.screens.routing.PaywallEntry
import id.nearyou.app.screens.startFlowTestKoin
import id.nearyou.app.screens.stopFlowTestKoin
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_PREMIUM_TAG
import id.nearyou.app.ui.components.DAILY_CAP_DIALOG_TAG
import org.koin.compose.KoinContext
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TITLE = "Buat postingan"
private const val PLACEHOLDER = "Apa yang sedang terjadi di sekitarmu?"
private const val COUNTER_ZERO = "0/280"
private const val CTA_POST = "Posting"
private const val LOADING = "Sedang memposting…"
private const val ERR_MODERATED = "Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."
private const val LOC_UNAVAILABLE = "Aktifkan lokasi untuk membuat postingan."
private const val OPEN_SETTINGS = "Buka Pengaturan"
private const val ERR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
private const val RETRY = "Coba lagi"
private const val LOCATION_CHIP = "Lokasi saat ini"
private const val PRIVACY_NOTE = "Lokasi kamu disamarkan hingga ±5 km sebelum tampil ke pengguna lain"
private const val POST_CAP_19M =
    "Kamu sudah membuat 10 postingan hari ini. Upgrade ke Premium untuk posting tanpa batas, atau tunggu reset dalam 19 mnt."

/**
 * iOS counterpart to the Robolectric `PostCreationScreenTest` (#174) — the composer flow run natively on
 * the iOS simulator, mirroring the [id.nearyou.app.screens.timeline.NearbyTimelineFlowIosTest] parity
 * shape: initial render, the CTA-enable gate + counter, loading, an outcome banner, the location settings
 * CTA, network error + retry, success → pop (no id rendered), the Free post cap dialog, and the Premium /
 * Free image-attach gate. Reuses the commonTest fakes. The layout-bounds and real-repository PII-echo
 * cases stay in the Android suite (JVM-only MockEngine idiom + bounds already covered there).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class PostCreationFlowIosTest {
    private lateinit var controller: FakeLocationPermissionController
    private lateinit var picker: FakeImagePicker

    private fun installKoin(
        flow: CreatePostFlow,
        isPremium: Boolean = true,
    ) {
        controller = FakeLocationPermissionController(current = LocationPermissionStatus.GRANTED)
        picker = FakeImagePicker()
        val profile = FakeProfileFlow.sampleProfile(userId = "self-id", isSelf = true).copy(isPremium = isPremium)
        startFlowTestKoin(
            module {
                single { flow }
                single<LocationPermissionController> { controller }
                single<ImagePicker> { picker }
                single<ImageUploader> { FakeImageUploadRepository() }
                single<ProfileFlow> { FakeProfileFlow(profileOutcome = ProfileOutcome.Loaded(profile)) }
                single<SelfUserIdProvider> { FakeSelfUserIdProvider("self-id") }
            },
        )
    }

    @AfterTest
    fun tearDown() = stopFlowTestKoin()

    @Test
    fun initialRender_showsTitlePlaceholderChipNoteZeroCounterDisabledCta() {
        installKoin(FakeCreatePostFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithText(TITLE).assertExists()
            onNodeWithText(PLACEHOLDER).assertExists()
            onNodeWithText(LOCATION_CHIP).assertExists()
            onNodeWithText(PRIVACY_NOTE).assertExists()
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
    fun contentRejectedOutcome_showsKeywordFreeModerationBanner() {
        installKoin(FakeCreatePostFlow(outcome = PostCreationOutcome.ContentRejected))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            onNodeWithText(ERR_MODERATED).assertExists()
        }
    }

    @Test
    fun locationUnavailableOutcome_settingsCtaInvokesOpenAppSettings() {
        installKoin(FakeCreatePostFlow(outcome = PostCreationOutcome.LocationUnavailable))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            onNodeWithText(LOC_UNAVAILABLE).assertExists()
            onNodeWithText(OPEN_SETTINGS).performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, controller.openAppSettingsCount, "Buka Pengaturan deep-links to settings")
        }
    }

    @Test
    fun networkErrorOutcome_showsBannerAndRetry_thatReInvokesSubmit() {
        val fake = FakeCreatePostFlow(outcome = PostCreationOutcome.NetworkError)
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            onNodeWithText(ERR_NETWORK).assertExists()
            assertEquals(1, fake.submitInvocationCount)
            onNodeWithText(RETRY).performScrollTo().performClick()
            waitForIdle()
            assertEquals(2, fake.submitInvocationCount, "retry re-invokes submit")
        }
    }

    @Test
    fun success_invokesPopCallback_andRendersNoPostId() {
        var popped = 0
        installKoin(FakeCreatePostFlow(outcome = PostCreationOutcome.Success("SECRET-POST-ID-9999")))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = { popped++ }) } } }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            assertEquals(1, popped, "Success invokes the pop callback exactly once")
            onNodeWithText("SECRET-POST-ID-9999", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun rateLimitedOutcome_showsPostCapDialog_andPremiumCtaNamesPostCap() {
        installKoin(FakeCreatePostFlow(outcome = PostCreationOutcome.RateLimited(retryAfterSeconds = 1_140)))
        val activated = mutableListOf<PaywallEntry>()
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}, onActivatePremium = { activated += it }) } }
            }
            onNodeWithTag(POST_CONTENT_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_POST).performClick()
            waitForIdle()
            onNodeWithTag(DAILY_CAP_DIALOG_TAG).assertExists()
            onAllNodesWithText(POST_CAP_19M).assertCountEquals(1)
            onNodeWithTag(DAILY_CAP_DIALOG_PREMIUM_TAG).performClick()
            waitForIdle()
            assertEquals(listOf(PaywallEntry.POST_CAP), activated)
        }
    }

    @Test
    fun premiumViewer_tappingAttach_invokesThePicker() {
        installKoin(FakeCreatePostFlow(), isPremium = true)
        var activated = 0
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}, onActivatePremium = { activated++ }) } }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_ATTACH_IMAGE_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_ATTACH_IMAGE_TAG).performScrollTo().performClick()
            waitForIdle()
            assertEquals(1, picker.pickInvocationCount, "a Premium viewer's attach invokes the picker")
            assertEquals(0, activated, "a Premium viewer is NOT routed to the paywall")
        }
    }

    @Test
    fun freeViewer_tappingAttach_isUpsold_andPickerNotInvoked() {
        installKoin(FakeCreatePostFlow(), isPremium = false)
        val activated = mutableListOf<PaywallEntry>()
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { PostCreationScreen(onPostCreated = {}, onActivatePremium = { activated += it }) } }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_ATTACH_IMAGE_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_ATTACH_IMAGE_TAG).performScrollTo().performClick()
            waitForIdle()
            assertEquals(0, picker.pickInvocationCount, "a Free viewer's attach must NOT invoke the picker")
            assertEquals(listOf(PaywallEntry.IMAGE_ATTACH), activated)
        }
    }
}
