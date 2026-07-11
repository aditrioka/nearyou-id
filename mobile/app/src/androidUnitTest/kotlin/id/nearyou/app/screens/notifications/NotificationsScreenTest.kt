package id.nearyou.app.screens.notifications

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.MarkReadResult
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.fakeNotification
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.ui.components.LOAD_MORE_FOOTER_TAG
import id.nearyou.app.ui.components.LOAD_MORE_RETRY_TAG
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val TITLE = "Notifikasi" // notifications_title
private const val LOADING = "Sedang memuat notifikasi…" // notifications_loading
private const val EMPTY = "Belum ada notifikasi" // notifications_empty
private const val MARK_ALL = "Tandai semua dibaca" // notifications_mark_all_read
private const val ERROR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu." // signin_error_network
private const val RETRY = "Coba lagi" // cta_retry
private const val COPY_POST_LIKED = "Seseorang menyukai postingan kamu" // notif_post_liked
private const val COPY_GENERIC = "Notifikasi baru" // notif_generic

// Follow-up #343: the now-emitted types' specific copy (byte-identical to strings.xml).
// COPY_PRIVACY_FLIP_DATED is notif_privacy_flip_warning with %1$s already substituted with the
// date portion of the fake row's body_data.privacy_flip_scheduled_at.
private const val COPY_PRIVACY_FLIP_DATED =
    "Profilmu akan jadi publik pada 2026-07-14 — perpanjang Premium untuk tetap privat"
private const val COPY_PRIVACY_FLIP_SOON =
    "Profilmu akan segera jadi publik — perpanjang Premium untuk tetap privat" // notif_privacy_flip_warning_soon
private const val COPY_BILLING_ISSUE =
    "Ada masalah penagihan langganan Premium kamu — periksa metode pembayaranmu" // notif_subscription_billing_issue
private const val COPY_SUBSCRIPTION_EXPIRED = "Langganan Premium kamu telah berakhir" // notif_subscription_expired
private const val COPY_CHAT_REDACTED = "Sebuah pesan di percakapanmu dihapus oleh moderator" // notif_chat_message_redacted
private const val COPY_ACCOUNT_ACTION = "Ada tindakan moderasi pada akunmu" // notif_account_action_applied
private const val COPY_DATA_EXPORT = "Ekspor datamu siap diunduh" // notif_data_export_ready
private const val ACTOR_UUID = "11111111-1111-1111-1111-111111111111"
private const val TARGET_UUID = "22222222-2222-2222-2222-222222222222"

/**
 * Render + interaction coverage of `NotificationsScreen` (`mobile-notifications-list` § "Test coverage").
 * Drives the screen via a `FakeNotificationsFlow` through Koin: the four states, the type-keyed
 * generic-actor copy + excerpt, the no-UUID PII guard, the unknown-`type` + missing-`body_data` tolerance,
 * the read/unread visual distinction, mark-read on tap (204 keep / 404 no-op / transport revert), and
 * mark-all-read. In the Release-variant `*ScreenTest` exclude (the `ui-test-manifest` host activity is
 * debug-only).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class NotificationsScreenTest {
    private lateinit var fake: FakeNotificationsFlow

    private fun installKoin(
        outcome: NotificationsOutcome = NotificationsOutcome.Loaded(emptyList(), null),
        suspendForever: Boolean = false,
        markReadResult: MarkReadResult = MarkReadResult.Acknowledged,
        markReadThrows: Throwable? = null,
        loadMorePages: List<NotificationsOutcome> = emptyList(),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake =
            FakeNotificationsFlow(
                outcome = outcome,
                suspendForever = suspendForever,
                markReadResult = markReadResult,
                markReadThrows = markReadThrows,
                loadMorePages = loadMorePages,
            )
        startKoin { modules(module { single<NotificationsFlow> { fake } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun initialRender_showsTitle() {
        installKoin(NotificationsOutcome.Loaded(listOf(fakeNotification()), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitForIdle()
            onNodeWithText(TITLE).assertExists()
        }
    }

    @Test
    fun loading_showsLoadingCopy() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            onNodeWithText(LOADING).assertExists()
        }
    }

    @Test
    fun empty_showsEmptyCopy() {
        installKoin(NotificationsOutcome.Loaded(emptyList(), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(EMPTY).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(EMPTY).assertExists()
        }
    }

    @Test
    fun error_showsNetworkCopyAndRetry_andRetryReInvokes() {
        installKoin(NotificationsOutcome.NetworkError)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(ERROR_NETWORK).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(ERROR_NETWORK).assertExists()
            onNodeWithText(RETRY).assertExists()
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithText(RETRY).performClick()
            waitForIdle()
            assertEquals(2, fake.loadInvocationCount, "retry re-invokes the fetch")
        }
    }

    @Test
    fun content_rendersTypeCopyAndExcerpt() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(type = "post_liked", bodyData = buildJsonObject { put("post_excerpt", "halo dunia") })),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(COPY_POST_LIKED, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(COPY_POST_LIKED, substring = true).assertExists()
            onNodeWithText("halo dunia", substring = true).assertExists()
        }
    }

    @Test
    fun row_neverRendersActorOrTargetUuid() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(
                    fakeNotification(
                        type = "post_liked",
                        actorUserId = ACTOR_UUID,
                        targetId = TARGET_UUID,
                        bodyData = buildJsonObject { put("post_excerpt", "halo dunia") },
                    ),
                ),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(COPY_POST_LIKED, substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, onAllNodesWithText(ACTOR_UUID, substring = true).fetchSemanticsNodes().size, "no actor UUID in the tree")
            assertEquals(0, onAllNodesWithText(TARGET_UUID, substring = true).fetchSemanticsNodes().size, "no target UUID in the tree")
            onNodeWithText("halo dunia", substring = true).assertExists()
        }
    }

    @Test
    fun unknownType_rendersGenericFallback() {
        installKoin(
            NotificationsOutcome.Loaded(listOf(fakeNotification(type = "some_future_type", bodyData = buildJsonObject {})), null),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(COPY_GENERIC, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(COPY_GENERIC, substring = true).assertExists()
        }
    }

    @Test
    fun privacyFlipWarning_rendersDeadlineDateCopy() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(
                    fakeNotification(
                        type = "privacy_flip_warning",
                        bodyData = buildJsonObject { put("privacy_flip_scheduled_at", "2026-07-14T10:31:00Z") },
                    ),
                ),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(COPY_PRIVACY_FLIP_DATED, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(COPY_PRIVACY_FLIP_DATED, substring = true).assertExists()
        }
    }

    @Test
    fun privacyFlipWarning_missingDeadline_rendersDatelessFallback() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(type = "privacy_flip_warning", bodyData = buildJsonObject {})),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(COPY_PRIVACY_FLIP_SOON, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(COPY_PRIVACY_FLIP_SOON, substring = true).assertExists()
        }
    }

    @Test
    fun siblingEmittedTypes_renderSpecificCopy() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(
                    fakeNotification(id = "n1", type = "subscription_billing_issue", bodyData = buildJsonObject {}),
                    fakeNotification(id = "n2", type = "subscription_expired", bodyData = buildJsonObject {}),
                    fakeNotification(id = "n3", type = "chat_message_redacted", bodyData = buildJsonObject {}),
                    fakeNotification(id = "n4", type = "account_action_applied", bodyData = buildJsonObject {}),
                    fakeNotification(id = "n5", type = "data_export_ready", bodyData = buildJsonObject {}),
                ),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(COPY_BILLING_ISSUE, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(COPY_BILLING_ISSUE, substring = true).assertExists()
            onNodeWithText(COPY_SUBSCRIPTION_EXPIRED, substring = true).assertExists()
            onNodeWithText(COPY_CHAT_REDACTED, substring = true).assertExists()
            onNodeWithText(COPY_ACCOUNT_ACTION, substring = true).assertExists()
            onNodeWithText(COPY_DATA_EXPORT, substring = true).assertExists()
            // None of the five fell through to the generic fallback.
            assertEquals(0, onAllNodesWithText(COPY_GENERIC, substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun missingBodyData_rendersBaseCopyWithoutCrash() {
        installKoin(
            NotificationsOutcome.Loaded(listOf(fakeNotification(type = "post_liked", bodyData = buildJsonObject {})), null),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(COPY_POST_LIKED, substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(COPY_POST_LIKED, substring = true).assertExists()
        }
    }

    @Test
    fun readVsUnread_areVisuallyDistinct() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(
                    fakeNotification(id = "unread", readAt = null),
                    fakeNotification(id = "read", readAt = "2026-05-31T11:00:00Z"),
                ),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(COPY_POST_LIKED, substring = true).fetchSemanticsNodes().size == 2 }
            // Exactly one unread indicator dot (the unread row); the read row has none.
            assertEquals(
                1,
                onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().size,
                "exactly one unread dot for the single unread row",
            )
        }
    }

    @Test
    fun tappingUnreadRow_marksItRead_on204() {
        installKoin(
            outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), null),
            markReadResult = MarkReadResult.Acknowledged,
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            onNodeWithText(COPY_POST_LIKED, substring = true).performClick()
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf("n1"), fake.markReadIds, "a mark-read PATCH is issued for the tapped row")
        }
    }

    @Test
    fun tappingRow_404IsSilentNoOp_rowStaysRead() {
        installKoin(
            outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), null),
            markReadResult = MarkReadResult.NotFound,
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            onNodeWithText(COPY_POST_LIKED, substring = true).performClick()
            // 404 keeps the optimistic flip (idempotent-looking) — the dot stays gone, no error surfaced.
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
        }
    }

    @Test
    fun tappingRow_transportFailure_revertsToUnread() {
        installKoin(
            outcome = NotificationsOutcome.Loaded(listOf(fakeNotification(id = "n1", readAt = null)), null),
            markReadThrows = IllegalStateException("io"),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            onNodeWithText(COPY_POST_LIKED, substring = true).performClick()
            // Optimistically flips to read, then the transport failure reverts it → the dot returns.
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            onNodeWithText(ERROR_NETWORK).assertDoesNotExist()
        }
    }

    @Test
    fun markAllRead_flipsEveryRowToRead() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(id = "n1", readAt = null), fakeNotification(id = "n2", readAt = null)),
                null,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
            onNodeWithText(MARK_ALL).performClick()
            waitUntil(
                timeoutMillis = 5_000,
            ) { onAllNodesWithTag(NOTIFICATION_UNREAD_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            assertEquals(1, fake.markAllReadInvocationCount, "mark-all-read issued the read-all request")
        }
    }

    // Spec § "Pull-to-refresh re-invokes the fetch" — a pull-down on the list re-fires the first-page load
    // (mirroring the timeline screen tests' swipe via the list test tag).
    @Test
    fun pullToRefresh_reInvokesFetch() {
        installKoin(NotificationsOutcome.Loaded(listOf(fakeNotification(type = "post_liked")), null))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText(COPY_POST_LIKED, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, fake.loadInvocationCount)
            onNodeWithTag(NOTIFICATIONS_LIST_TAG).performTouchInput { swipeDown() }
            waitForIdle()
            assertEquals(2, fake.loadInvocationCount, "pull-to-refresh re-invokes the fetch")
        }
    }

    // ---- mobile-notifications-list infinite-scroll: cursor load-more (screen integration) ----

    // A short page-1 list keeps the footer within the load-more threshold, so the scroll-end detector fires
    // load-more without an explicit gesture; the appended page-2 row appears AND the follow-up reused the
    // page-1 cursor. Distinguishable row text is the `post_excerpt` body_data (rows have no free content
    // field — the type-keyed copy is identical across rows, so the excerpt is what differs per page).
    @Test
    fun loadMore_appendsTheNextPage_reusingTheCursor() {
        installKoin(
            outcome =
                NotificationsOutcome.Loaded(
                    listOf(fakeNotification(id = "n1", bodyData = buildJsonObject { put("post_excerpt", "PAGE1_EXCERPT") })),
                    "c1",
                ),
            loadMorePages =
                listOf(
                    NotificationsOutcome.Loaded(
                        listOf(fakeNotification(id = "n2", bodyData = buildJsonObject { put("post_excerpt", "PAGE2_EXCERPT") })),
                        null,
                    ),
                ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PAGE2_EXCERPT", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PAGE1_EXCERPT", substring = true).assertExists()
            onNodeWithText("PAGE2_EXCERPT", substring = true).assertExists()
            assertEquals(listOf("c1"), fake.loadMoreCalls, "load-more fetched the retained cursor c1")
        }
    }

    // A failed load-more shows the non-destructive retry footer (page-1 retained); tapping retry recovers.
    @Test
    fun loadMoreError_showsRetryFooter_andRetryRecovers() {
        installKoin(
            outcome =
                NotificationsOutcome.Loaded(
                    listOf(fakeNotification(id = "n1", bodyData = buildJsonObject { put("post_excerpt", "PAGE1_EXCERPT") })),
                    "c1",
                ),
            loadMorePages =
                listOf(
                    NotificationsOutcome.NetworkError,
                    NotificationsOutcome.Loaded(
                        listOf(fakeNotification(id = "n2", bodyData = buildJsonObject { put("post_excerpt", "PAGE2_EXCERPT") })),
                        null,
                    ),
                ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(LOAD_MORE_RETRY_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("PAGE1_EXCERPT", substring = true).assertExists() // the loaded list is retained on load-more failure
            onNodeWithTag(LOAD_MORE_RETRY_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("PAGE2_EXCERPT", substring = true).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0) // footer clears on success
        }
    }

    // The load-more footer never co-occurs with the initial-load skeleton (it lives inside the Content
    // list, which the Loading state does not render) — mobile-design-system load-more pattern.
    @Test
    fun loadMoreFooter_absentDuringInitialLoadSkeleton() {
        installKoin(suspendForever = true)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { NotificationsScreen() } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_FOOTER_TAG).assertCountEquals(0)
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0)
        }
    }
}
