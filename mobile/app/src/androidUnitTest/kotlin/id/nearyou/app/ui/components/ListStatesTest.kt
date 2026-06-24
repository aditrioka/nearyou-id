package id.nearyou.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.theme.NearYouTheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Render coverage of the shared `ui/components` **list-state kit** (audit 05-#11 list-state half) — the
 * canonical non-`Content` list states every timeline feed + the notifications inbox consume after the
 * extraction. Each state wraps its content in a single-item scrollable carrying the host's `testTag` so a
 * `PullToRefreshBox` recognizes the pull gesture from it; this suite pins that the tag is present in every
 * state, the hoisted copy renders, and the error-state retry fires its callback. The per-screen behaviours
 * (which copy maps to which outcome, the skeleton-on-timelines-only split) stay covered by the screen
 * suites; this asserts the extracted primitives directly.
 *
 * `@Suppress("DEPRECATION")`: keeps the v1 `runComposeUiTest` API the sibling component/screen tests use
 * — migrating to v2 is the tracked follow-up per docs/11 § 2.7, not drive-by churn.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class ListStatesTest {
    private companion object {
        const val TAG = "kitListTag"

        // Canonical copy (byte-identical to shared/resources strings.xml) — ListErrorState bakes these in.
        const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu."
        const val RETRY = "Coba lagi"
    }

    @Test
    fun scrollableState_rendersContent_andCarriesTheTag() {
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    ListScrollableState(testTag = TAG) { Text("WRAPPED_CONTENT") }
                }
            }
            onNodeWithTag(TAG).assertExists()
            onNodeWithText("WRAPPED_CONTENT").assertExists()
        }
    }

    @Test
    fun loadingState_showsMessage_underTheTag_withSkeleton() {
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    ListLoadingState(message = "MEMUAT_KIT", testTag = TAG, showSkeleton = true)
                }
            }
            onNodeWithTag(TAG).assertExists()
            onNodeWithText("MEMUAT_KIT").assertExists()
        }
    }

    @Test
    fun loadingState_withoutSkeleton_stillShowsMessage_underTheTag() {
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    ListLoadingState(message = "MEMUAT_NO_SKELETON", testTag = TAG)
                }
            }
            onNodeWithTag(TAG).assertExists()
            onNodeWithText("MEMUAT_NO_SKELETON").assertExists()
        }
    }

    @Test
    fun centeredMessageState_showsMessage_underTheTag() {
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    ListCenteredMessageState(message = "PESAN_TENGAH", testTag = TAG)
                }
            }
            onNodeWithTag(TAG).assertExists()
            onNodeWithText("PESAN_TENGAH").assertExists()
        }
    }

    @Test
    fun errorState_showsNetworkCopyAndRetry_underTheTag_andRetryFires() {
        var retries = 0
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    ListErrorState(onRetry = { retries++ }, testTag = TAG)
                }
            }
            onNodeWithTag(TAG).assertExists()
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).performClick()
            waitForIdle()
            assertEquals(1, retries, "the retry control fires the hoisted onRetry exactly once")
        }
    }

    @Test
    fun softLimitBanner_showsItsText() {
        runComposeUiTest {
            setContent {
                NearYouTheme {
                    SoftLimitBanner(text = "BANNER_SOFT_LIMIT")
                }
            }
            onNodeWithText("BANNER_SOFT_LIMIT").assertExists()
        }
    }
}
