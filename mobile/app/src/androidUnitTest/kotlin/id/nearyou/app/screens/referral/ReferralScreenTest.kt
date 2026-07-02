package id.nearyou.app.screens.referral

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.referral.ReferralRepository
import id.nearyou.app.referral.ReferralState
import id.nearyou.app.theme.NearYouTheme
import kotlinx.coroutines.CompletableDeferred
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

private const val COPY_ACTION = "Salin kode"
private const val PROGRESS_3_OF_5 = "3 dari 5"
private const val REWARD_UNLOCKED = "Hadiah inviter sudah kamu klaim. Terima kasih sudah mengajak teman!"
private const val PAYWALL_CTA = "Aktifkan Premium" // cta_activate_premium — must NOT appear (open to all)
private const val LOADING = "Sedang memuat…"
private const val ERROR_TEXT = "Ada yang salah. Coba lagi sebentar."
private const val RETRY = "Coba lagi"

/** A loaded-state fixture for the loading/error render tests (matches the "a3f7k2mq" assertions). */
private fun sampleLoaded() = ReferralState(inviteCode = "a3f7k2mq", grantedReferrals = 3, milestone = 5, inviterRewardClaimed = false)

/** A fixed-state [ReferralRepository] for the screen render tests. */
private class FixedReferralRepository(private val state: ReferralState?) : ReferralRepository {
    override suspend fun loadState(): ReferralState? = state
}

/** A [ReferralRepository] returning [results] in order (last repeats); optional [gate] suspends each call. */
private class GatedReferralRepository(
    private val results: List<ReferralState?>,
    private val gate: CompletableDeferred<Unit>? = null,
) : ReferralRepository {
    private var calls = 0

    override suspend fun loadState(): ReferralState? {
        gate?.await()
        val index = calls.coerceAtMost(results.lastIndex)
        calls++
        return results[index]
    }
}

/**
 * Render coverage of `ReferralScreen` (mobile-referral) via the Robolectric-backed CMP UI runner. The
 * referral state is supplied by a fake `ReferralRepository` seeded into the test Koin module (the
 * `SettingsScreenTest` pattern). Verifies the loaded screen renders the invite code + copy action +
 * "X dari 5" progress, that copy places the code on the clipboard, the reward-unlocked state appears
 * only when claimed, and that NO paywall/upsell is shown (the surface is open to all tiers — design D3).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ReferralScreenTest {
    private fun installRepo(repo: ReferralRepository) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(module { single<ReferralRepository> { repo } })
        }
    }

    private fun installKoin(state: ReferralState?) = installRepo(FixedReferralRepository(state))

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun loaded_rendersCodeCopyActionAndProgress_withNoPaywall() {
        installKoin(ReferralState(inviteCode = "a3f7k2mq", grantedReferrals = 3, milestone = 5, inviterRewardClaimed = false))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ReferralScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithText("a3f7k2mq").assertExists()
            onNodeWithText(COPY_ACTION).assertExists()
            onNodeWithText(PROGRESS_3_OF_5).assertExists()
            // open to all tiers (design D3): no paywall/upsell CTA, and the reward-unlocked state is absent.
            onNodeWithText(PAYWALL_CTA).assertDoesNotExist()
            onNodeWithText(REWARD_UNLOCKED).assertDoesNotExist()
        }
    }

    @Test
    fun tappingCopy_placesTheCodeOnTheClipboard() {
        installKoin(ReferralState(inviteCode = "a3f7k2mq", grantedReferrals = 0, milestone = 5, inviterRewardClaimed = false))
        var clipboard: ClipboardManager? = null
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        clipboard = LocalClipboardManager.current
                        ReferralScreen(onBack = {})
                    }
                }
            }
            waitForIdle()
            onNodeWithText(COPY_ACTION).performClick()
            waitForIdle()
            assertEquals("a3f7k2mq", clipboard?.getText()?.text, "the copy action places the invite code on the clipboard")
        }
    }

    @Test
    fun loading_showsTheLoadingMessage() {
        // A gated repository that never completes keeps the screen in the loading state.
        installRepo(GatedReferralRepository(listOf(sampleLoaded()), gate = CompletableDeferred()))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ReferralScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithText(LOADING).assertExists()
            onNodeWithText(COPY_ACTION).assertDoesNotExist() // no loaded content yet
        }
    }

    @Test
    fun error_showsRetry_andRetryReFetchesAndLoads() {
        // First load fails (null → Error); tapping "Coba lagi" re-fetches and the second load succeeds.
        installRepo(GatedReferralRepository(listOf(null, sampleLoaded())))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ReferralScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithText(ERROR_TEXT).assertExists()
            onNodeWithText(RETRY).assertExists().performClick()
            waitForIdle()
            onNodeWithText("a3f7k2mq").assertExists() // the retry re-fetch loaded the code
            onNodeWithText(ERROR_TEXT).assertDoesNotExist()
        }
    }

    @Test
    fun claimedReward_showsTheRewardUnlockedState() {
        installKoin(ReferralState(inviteCode = "a3f7k2mq", grantedReferrals = 5, milestone = 5, inviterRewardClaimed = true))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { ReferralScreen(onBack = {}) } } }
            waitForIdle()
            onNodeWithText(REWARD_UNLOCKED).assertExists()
            onNodeWithText(PAYWALL_CTA).assertDoesNotExist() // still no upsell in the claimed state
        }
    }
}
