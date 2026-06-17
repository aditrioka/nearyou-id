package id.nearyou.app.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import org.jetbrains.compose.resources.stringResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Render + behavior coverage of the shared daily-cap upsell dialog (`mobile-cap-upsell-dialog`,
 * mockup frame 18). Covers: the verbatim docs/03:187 like-body render with the frame-true
 * "14 j 19 mnt" countdown + both CTAs, the CTA/dismiss callback routing (the component fires the
 * hoisted callbacks; the Premium-CTA navigation (host pushes `PaywallRoute(LIKE_CAP)`, mobile-paywall
 * #235) is the HOST's concern, covered by the feed screen tests), the per-minute tick-down, the zero auto-dismiss, the `RateLimited(0)` floor
 * (no flash-dismiss on entry), and light+dark rendering. Scrim/back dismissal shares the SAME
 * `onDismiss` lambda by construction (`AlertDialog(onDismissRequest = onDismiss)`), exercised here
 * through the dismiss path.
 *
 * The tick uses the compose test `mainClock` (the dialog decrements via monotonic `delay`, no
 * wall-clock API — `mobile-cap-upsell-dialog` § countdown), so these tests advance virtual time
 * deterministically (no `waitForIdle` flake — the issue #228 pattern is avoided).
 *
 * `@Suppress("DEPRECATION")`: the suite keeps the v1 `runComposeUiTest` API every sibling screen
 * test uses — the suite-wide v2 migration is the tracked follow-up per docs/11 § 2.7.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class DailyCapUpsellDialogTest {
    /** The exact frame-18 body: the verbatim docs/03:187 copy with the "14 j 19 mnt" countdown. */
    private val frame18Body =
        "Kamu sudah menggunakan 10 like hari ini. " +
            "Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam 14 j 19 mnt."

    @Test
    fun rendersTitleVerbatimLikeBodyAndBothCtas() =
        runComposeUiTest {
            setContent {
                NearYouTheme(darkTheme = false) {
                    DailyCapUpsellDialog(
                        retryAfterSeconds = 51_540,
                        body = { countdown -> stringResource(Res.string.post_detail_likes_cap_upsell, countdown) },
                        onDismiss = {},
                        onActivatePremium = {},
                    )
                }
            }
            onNodeWithText("Batas harian tercapai").assertExists()
            onNodeWithText(frame18Body).assertExists()
            onNodeWithText("Aktifkan Premium").assertExists()
            onNodeWithText("Tutup").assertExists()
        }

    @Test
    fun ctasRouteToTheirCallbacks() =
        runComposeUiTest {
            var premium = 0
            var dismissed = 0
            setContent {
                NearYouTheme(darkTheme = false) {
                    DailyCapUpsellDialog(
                        retryAfterSeconds = 51_540,
                        body = { it },
                        onDismiss = { dismissed++ },
                        onActivatePremium = { premium++ },
                    )
                }
            }
            onNodeWithTag(DAILY_CAP_DIALOG_PREMIUM_TAG).performClick()
            assertEquals(1, premium, "Aktifkan Premium fires onActivatePremium exactly once")
            assertEquals(0, dismissed, "the Premium CTA itself does not fire onDismiss (host wiring decides)")
            onNodeWithTag(DAILY_CAP_DIALOG_CLOSE_TAG).performClick()
            assertEquals(1, dismissed, "Tutup fires onDismiss exactly once")
        }

    @Test
    fun countdownTicksDownByTheMinute() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                NearYouTheme(darkTheme = false) {
                    DailyCapUpsellDialog(
                        // 2 minutes (120 s) — renders "2 mnt", then "1 mnt" after one virtual minute.
                        retryAfterSeconds = 120,
                        body = { it },
                        onDismiss = {},
                        onActivatePremium = {},
                    )
                }
            }
            mainClock.advanceTimeByFrame()
            onNodeWithText("2 mnt").assertExists()
            mainClock.advanceTimeBy(60_000)
            onNodeWithText("1 mnt").assertExists()
        }

    @Test
    fun reachingZeroAutoDismisses() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            var dismissed = 0
            setContent {
                NearYouTheme(darkTheme = false) {
                    DailyCapUpsellDialog(
                        retryAfterSeconds = 60,
                        body = { it },
                        onDismiss = { dismissed++ },
                        onActivatePremium = {},
                    )
                }
            }
            mainClock.advanceTimeByFrame()
            assertEquals(0, dismissed, "the dialog is up during its final minute")
            mainClock.advanceTimeBy(60_000)
            assertEquals(1, dismissed, "the floored final minute elapsed — the cap reset closes the dialog")
        }

    @Test
    fun zeroRetryAfterIsFloored_notFlashDismissed() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            var dismissed = 0
            setContent {
                NearYouTheme(darkTheme = false) {
                    DailyCapUpsellDialog(
                        // The shipped client maps an absent/stripped Retry-After to RateLimited(0).
                        retryAfterSeconds = 0,
                        body = { it },
                        onDismiss = { dismissed++ },
                        onActivatePremium = {},
                    )
                }
            }
            mainClock.advanceTimeByFrame()
            onNodeWithText("1 mnt").assertExists()
            assertEquals(0, dismissed, "a zero Retry-After must NOT flash-dismiss on entry (the ≥1-minute floor)")
            mainClock.advanceTimeBy(60_000)
            assertEquals(1, dismissed, "the floored minute then elapses normally")
        }

    @Test
    fun rendersUnderTheDarkScheme() =
        runComposeUiTest {
            setContent {
                NearYouTheme(darkTheme = true) {
                    DailyCapUpsellDialog(
                        retryAfterSeconds = 1_140,
                        body = { it },
                        onDismiss = {},
                        onActivatePremium = {},
                    )
                }
            }
            onNodeWithText("Batas harian tercapai").assertExists()
            onNodeWithText("19 mnt").assertExists()
        }
}
