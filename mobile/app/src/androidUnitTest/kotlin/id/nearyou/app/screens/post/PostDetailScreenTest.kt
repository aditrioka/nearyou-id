package id.nearyou.app.screens.post

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.post.EditHistoryOutcome
import id.nearyou.app.post.EditVersionDto
import id.nearyou.app.post.FakePostDetailFlow
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.LikeApiClient
import id.nearyou.app.post.LikeCountOutcome
import id.nearyou.app.post.LikeOutcome
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostDetailRepository
import id.nearyou.app.post.PostEditApiClient
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostEditRepository
import id.nearyou.app.post.PostRefreshOutcome
import id.nearyou.app.post.RepliesOutcome
import id.nearyou.app.post.ReplyApiClient
import id.nearyou.app.post.ReplyPostOutcome
import id.nearyou.app.post.SinglePostApiClient
import id.nearyou.app.post.fakeReply
import id.nearyou.app.screens.routing.PostDetailRoute
import id.nearyou.app.theme.NearYouTheme
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlin.test.assertTrue
import kotlin.time.Clock

// Canonical Bahasa Indonesia copy (byte-identical to shared/resources strings.xml).
private const val CONTENT = "halo"
private const val CITY = "Jakarta Selatan"
private const val CREATED_AT = "2026-06-06T10:00:00Z"
private const val POSTED_FROM = "Diposting dari Jakarta Selatan, 2026-06-06" // post_detail_posted_from
private const val POSTED_FROM_NO_CITY = "Diposting 2026-06-06" // post_detail_posted_from_no_city
private const val REPLIES_EMPTY = "Belum ada balasan. Jadilah yang pertama."
private const val LOADING = "Sedang memuat postingan…" // timeline_loading
private const val ERR_NETWORK = "Tidak bisa terhubung. Periksa koneksi internet kamu." // signin_error_network
private const val RETRY = "Coba lagi" // cta_retry
private const val PLACEHOLDER = "Tulis balasan…" // post_detail_reply_placeholder
private const val CTA_REPLY = "Balas" // cta_reply
private const val CTA_CLOSE = "Tutup" // cta_close (the back/close affordance)

// reset countdown of 3600s → resetHours = 1 → "1 jam" fills the cap-upsell %1$s.
private const val LIKE_CAP_1H =
    "Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam 1 jam."
private const val REPLY_CAP_1H =
    "Kamu sudah menggunakan 20 balasan hari ini. Upgrade ke Premium untuk balas tanpa batas, atau tunggu reset dalam 1 jam."
private const val POST_GONE = "Postingan ini sudah tidak tersedia." // post_detail_post_gone

private const val AUTHOR_UUID = "11111111-1111-1111-1111-111111111111"
private val JSON = headersOf("Content-Type", "application/json")

/**
 * Render + interaction coverage of `PostDetailScreen` via the Robolectric-backed CMP UI runner (task 9.3),
 * driven by `FakePostDetailFlow` (plus one real-repository-over-MockEngine test for the mobile-post-editing
 * single-post-read refresh on resume). The outcome→state projection is covered purely by `PostDetailUiStateTest`; this suite
 * verifies the composable renders the header (empty-city tolerated, no `author_id`/coordinate), the
 * replies states (incl. a viewer's-own auto-hidden reply rendering normally), the like toggle (optimistic
 * + revert + 429 upsell + count + graceful degradation), and the reply composer (counter, 280-disable,
 * 201 local-append-without-refetch, 429 upsell, error banner), plus the deferral negatives (no block/
 * report affordance). In the Release-variant `*ScreenTest` exclude (the ui-test-manifest host is debug-only).
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: see `SignInScreenTest` for the multi-test startKoin cycle.
 *
 * The display is pinned to a realistic phone viewport (`w360dp-h800dp`): Robolectric's legacy default
 * (320x470) leaves only ~180dp of scrollable height between the back bar and the reply composer, so the
 * detail `LazyColumn` composes ONLY the header item once the header carries the identity row
 * (mobile-timeline-card-redesign) — every below-the-header assertion (like row, replies) would fail not
 * on behavior but on the tiny legacy window. Real device classes are ~780-900dp tall.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp")
@OptIn(ExperimentalTestApi::class)
class PostDetailScreenTest {
    private fun installKoin(
        flow: PostDetailFlow,
        editFlow: PostEditFlow = FakePostEditFlow(),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single { flow }
                    single { editFlow }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun route(
        cityName: String = CITY,
        likedByViewer: Boolean = false,
        replyCount: Int = 2,
        authorUsername: String = "raka.jkt",
        authorDisplayName: String = "Raka Pratama",
        focusReplyComposer: Boolean = false,
        createdAtIso: String = CREATED_AT,
    ): PostDetailRoute =
        PostDetailRoute(
            postId = "p1",
            content = CONTENT,
            cityName = cityName,
            distanceM = 1234.5,
            createdAtIso = createdAtIso,
            likedByViewer = likedByViewer,
            replyCount = replyCount,
            authorUsername = authorUsername,
            authorDisplayName = authorDisplayName,
            focusReplyComposer = focusReplyComposer,
        )

    // ---- mobile-post-editing integration (the refresh-on-resume → affordance / Diedit label / history) ----

    @Test
    fun editAffordance_shown_forOwnPostWithinWindow() {
        val fresh = Clock.System.now().toString()
        installKoin(
            FakePostDetailFlow(),
            FakePostEditFlow(refreshOutcome = PostRefreshOutcome.Loaded(content = CONTENT, editedAt = null, isAuthor = true)),
        )
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        PostDetailScreen(route = route(createdAtIso = fresh), onBack = {}, onEditPost = {
                                _,
                                _,
                            ->
                        })
                    }
                }
            }
            // The affordance appears only AFTER the resume refresh reports isAuthor = true (within the window).
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(POST_DETAIL_EDIT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_EDIT_TAG).assertExists()
        }
    }

    @Test
    fun editAffordance_hidden_forAnotherUsersPost() {
        val fresh = Clock.System.now().toString()
        installKoin(
            FakePostDetailFlow(),
            // editedAt non-null is the refresh-completed SIGNAL (the Diedit label appears); isAuthor = false.
            FakePostEditFlow(
                refreshOutcome = PostRefreshOutcome.Loaded(content = CONTENT, editedAt = "2026-06-07T09:00:00Z", isAuthor = false),
            ),
        )
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        PostDetailScreen(route = route(createdAtIso = fresh), onBack = {}, onEditPost = {
                                _,
                                _,
                            ->
                        })
                    }
                }
            }
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(POST_DETAIL_EDITED_LABEL_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_EDIT_TAG).assertDoesNotExist() // not the author → no affordance
        }
    }

    @Test
    fun editAffordance_hidden_forOwnButStalePost() {
        installKoin(
            FakePostDetailFlow(),
            // own post (isAuthor = true) but the route's createdAt is the far-past CREATED_AT (outside 30 min).
            FakePostEditFlow(
                refreshOutcome = PostRefreshOutcome.Loaded(content = CONTENT, editedAt = "2026-06-07T09:00:00Z", isAuthor = true),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}, onEditPost = { _, _ -> }) } } }
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(POST_DETAIL_EDITED_LABEL_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_EDIT_TAG).assertDoesNotExist() // own but stale → no affordance
        }
    }

    @Test
    fun dieditLabel_shown_whenEdited_andOpensHistoryOverlay() {
        installKoin(
            FakePostDetailFlow(),
            FakePostEditFlow(
                refreshOutcome = PostRefreshOutcome.Loaded(content = CONTENT, editedAt = "2026-06-07T09:00:00Z", isAuthor = false),
                historyOutcome = EditHistoryOutcome.Loaded(listOf(EditVersionDto("Versi ke-1", "isi lama", "2026-06-07T09:00:00Z"))),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}, onEditPost = { _, _ -> }) } } }
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(POST_DETAIL_EDITED_LABEL_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_EDITED_LABEL_TAG).performClick()
            // The "Riwayat edit" overlay opens and lists the version (label + content; no location field).
            onNodeWithTag(EDIT_HISTORY_SHEET_TAG).assertExists()
            onNodeWithText("Versi ke-1").assertExists()
            onNodeWithText("isi lama").assertExists()
            // Spatial-fuzzing privacy invariant (spec § "History modal renders no location"): the version
            // surface renders content + version + time ONLY — never a coordinate/location field.
            onNodeWithText("106.8", substring = true).assertDoesNotExist()
            onNodeWithText("-6.2", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun historyModal_emptyHistory_showsEmptyState() {
        installKoin(
            FakePostDetailFlow(),
            FakePostEditFlow(
                refreshOutcome = PostRefreshOutcome.Loaded(content = CONTENT, editedAt = "2026-06-07T09:00:00Z", isAuthor = false),
                historyOutcome = EditHistoryOutcome.Loaded(emptyList()),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}, onEditPost = { _, _ -> }) } } }
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(POST_DETAIL_EDITED_LABEL_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_EDITED_LABEL_TAG).performClick()
            onNodeWithTag(EDIT_HISTORY_SHEET_TAG).assertExists()
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(EDIT_HISTORY_EMPTY_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_HISTORY_EMPTY_TAG).assertExists()
        }
    }

    @Test
    fun historyModal_networkError_showsRetry() {
        installKoin(
            FakePostDetailFlow(),
            FakePostEditFlow(
                refreshOutcome = PostRefreshOutcome.Loaded(content = CONTENT, editedAt = "2026-06-07T09:00:00Z", isAuthor = false),
                historyOutcome = EditHistoryOutcome.Network,
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}, onEditPost = { _, _ -> }) } } }
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(POST_DETAIL_EDITED_LABEL_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_EDITED_LABEL_TAG).performClick()
            onNodeWithTag(EDIT_HISTORY_SHEET_TAG).assertExists()
            // The history load fails → the modal shows the error state + a retry affordance (not blank/crash).
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(EDIT_HISTORY_RETRY_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(EDIT_HISTORY_RETRY_TAG).assertExists()
        }
    }

    @Test
    fun dieditLabel_absent_whenNotEdited() {
        val fresh = Clock.System.now().toString()
        installKoin(
            FakePostDetailFlow(),
            // editedAt = null (never edited); isAuthor = true is the refresh-completed signal (affordance appears).
            FakePostEditFlow(refreshOutcome = PostRefreshOutcome.Loaded(content = CONTENT, editedAt = null, isAuthor = true)),
        )
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        PostDetailScreen(route = route(createdAtIso = fresh), onBack = {}, onEditPost = {
                                _,
                                _,
                            ->
                        })
                    }
                }
            }
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(POST_DETAIL_EDIT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_EDITED_LABEL_TAG).assertDoesNotExist() // never edited → no Diedit label
        }
    }

    // ---- header ----

    @Test
    fun header_showsContentAndPostedFrom_andNoBlockReportAffordance() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(CONTENT).assertExists()
            onNodeWithText(POSTED_FROM).assertExists()
            // Deferral negatives: no block/report affordance on the detail surface.
            onNodeWithText("Blokir", substring = true).assertDoesNotExist()
            onNodeWithText("Laporkan", substring = true).assertDoesNotExist()
        }
    }

    // mobile-post-detail § "Header renders the author display identity from the payload" — the identity
    // row comes SOLELY from the route (no network request for it; the no-single-post-GET test below
    // keeps pinning the outbound surface).
    @Test
    fun header_rendersAuthorDisplayIdentity_fromTheRoutePayload() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("Raka Pratama").assertExists()
            onNodeWithText("@raka.jkt").assertExists()
            // The shared LetterAvatar renders the derived initials ("Raka Pratama" → "RP").
            onNodeWithText("RP").assertExists()
        }
    }

    // mobile-post-detail § "Empty identity payload renders without an identity row" — a back stack
    // serialized before the fields existed decodes to "" defaults; no "@" orphan, no empty avatar.
    @Test
    fun emptyIdentityPayload_rendersNoIdentityRow_noOrphanHandle() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        PostDetailScreen(route = route(authorUsername = "", authorDisplayName = ""), onBack = {})
                    }
                }
            }
            onNodeWithText(CONTENT).assertExists()
            onNodeWithText("@", substring = true).assertDoesNotExist()
            onNodeWithText("Raka Pratama").assertDoesNotExist()
            // No avatar either — empty identity yields no initials node ("RP" absent).
            onNodeWithText("RP").assertDoesNotExist()
        }
    }

    @Test
    fun emptyCityName_rendersNoCityFragment_noLiteralQuotes() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(cityName = ""), onBack = {}) } } }
            onNodeWithText(CONTENT).assertExists()
            onNodeWithText(POSTED_FROM_NO_CITY).assertExists() // date only, no dangling "dari ,"
            onNodeWithText("\"\"").assertDoesNotExist() // no literal empty-quotes leaked
        }
    }

    @Test
    fun noAuthorIdNorCoordinate_inRenderedTree() {
        installKoin(
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.Loaded(listOf(fakeReply(authorId = AUTHOR_UUID, content = "PII_REPLY")), nextCursor = null),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("PII_REPLY").assertExists()
            onNodeWithText(AUTHOR_UUID, substring = true).assertDoesNotExist()
            onNodeWithText("1234.5", substring = true).assertDoesNotExist() // no raw distance/coordinate
        }
    }

    // ---- replies states ----

    @Test
    fun repliesLoading_showsLoadingCopy() {
        installKoin(FakePostDetailFlow(suspendRepliesForever = true))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(LOADING).assertExists()
        }
    }

    @Test
    fun repliesEmpty_showsEmptyCopy() {
        installKoin(FakePostDetailFlow(repliesOutcome = RepliesOutcome.Loaded(emptyList(), nextCursor = null)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(REPLIES_EMPTY).assertExists()
        }
    }

    @Test
    fun autoHiddenReply_rendersIdenticallyToALiveReply() {
        // The viewer's own auto-hidden reply (is_auto_hidden = true) renders normally — no badge, no dimming.
        installKoin(
            FakePostDetailFlow(
                repliesOutcome =
                    RepliesOutcome.Loaded(listOf(fakeReply(content = "MY_HIDDEN_REPLY", isAutoHidden = true)), nextCursor = null),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("MY_HIDDEN_REPLY").assertExists()
        }
    }

    @Test
    fun repliesError_showsNetworkCopyAndRetry_reInvokesLoad() {
        // First load errors; the retry re-load succeeds with a reply → the screen recovers to Content.
        val fake =
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.NetworkError,
                secondRepliesOutcome = RepliesOutcome.Loaded(listOf(fakeReply(content = "RETRIED_REPLY")), nextCursor = null),
            )
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(ERR_NETWORK).assertExists()
            assertEquals(1, fake.loadRepliesCount)
            // The retry control is a scrollable LazyColumn item below the fold on the small test surface
            // — scroll it into view before clicking.
            onNodeWithTag(POST_DETAIL_REPLIES_RETRY_TAG).performScrollTo().performClick()
            // Wait on the recovered Content (a Compose-tree condition) — the retry re-ran the load.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("RETRIED_REPLY").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("RETRIED_REPLY").assertExists() // recovered to Content
            assertEquals(2, fake.loadRepliesCount, "retry re-invokes the replies load")
        }
    }

    // ---- like control ----

    @Test
    fun likeCountAvailable_showsCount() {
        installKoin(FakePostDetailFlow(likeCountOutcome = LikeCountOutcome.Available(42)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("42 suka").assertExists()
        }
    }

    @Test
    fun likeCountUnavailable_hidesCount_andToggleStillWorks() {
        val fake = FakePostDetailFlow(likeCountOutcome = LikeCountOutcome.Unavailable, toggleOutcome = LikeOutcome.Liked)
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText("suka", substring = true).assertDoesNotExist() // count degraded → no count node
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            assertEquals(1, fake.toggleLikeCount, "the toggle stays functional when the count is unavailable")
        }
    }

    @Test
    fun optimisticLike_reflectsLikedState_andInvokesToggle() {
        val fake = FakePostDetailFlow(toggleOutcome = LikeOutcome.Liked)
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            // The like-state dot is nested inside the clickable (merged) toggle → assert via the unmerged tree.
            onNodeWithTag(POST_DETAIL_LIKE_NOT_LIKED_TAG, useUnmergedTree = true).assertExists()
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(POST_DETAIL_LIKE_LIKED_TAG, useUnmergedTree = true).assertExists() // reflects the liked state
            assertEquals(1, fake.toggleLikeCount)
            assertEquals(false, fake.lastToggleCurrentlyLiked, "toggled from the not-liked nav-arg state")
        }
    }

    @Test
    fun like429_revertsOptimisticFlip_andShowsCapUpsell() {
        installKoin(FakePostDetailFlow(toggleOutcome = LikeOutcome.RateLimited(retryAfterSeconds = 3600)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(POST_DETAIL_LIKE_NOT_LIKED_TAG, useUnmergedTree = true).assertExists() // reverted
            onNodeWithText(LIKE_CAP_1H).assertExists()
        }
    }

    @Test
    fun likeFailure_restoresTheExactPriorCount() {
        // Invariant guard: a known count (42 suka), the optimistic flip bumps to 43, then a failure must
        // fully undo it back to 42 (never left stuck at 43). NOTE: with the synchronous fake the count is
        // already resolved at tap time, so this does NOT distinguish the direct prior-value restore from an
        // inverse delta — both land on 42. The off-by-one those two diverge on only arises if the initial
        // count fetch resolves BETWEEN the optimistic flip and the failure (a tap during count-load), which
        // this fake can't sequence; that case is prevented by construction (priorCount captured before the
        // flip — see PostDetailScreen.onToggleLike).
        installKoin(FakePostDetailFlow(likeCountOutcome = LikeCountOutcome.Available(42), toggleOutcome = LikeOutcome.NetworkError))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            onNodeWithText("42 suka").assertExists()
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithText("42 suka").assertExists() // restored exactly — not 41, not 43
            onNodeWithText("43 suka").assertDoesNotExist()
            onNodeWithText("41 suka").assertDoesNotExist()
        }
    }

    @Test
    fun like404_showsTerminalPostGoneBanner() {
        installKoin(FakePostDetailFlow(toggleOutcome = LikeOutcome.PostGone))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(likedByViewer = false), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_LIKE_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithTag(POST_DETAIL_LIKE_NOT_LIKED_TAG, useUnmergedTree = true).assertExists() // reverted
            onNodeWithText(POST_GONE).assertExists() // terminal copy, distinct from the network-retry banner
            onNodeWithText(ERR_NETWORK).assertDoesNotExist()
        }
    }

    // ---- reply composer ----

    @Test
    fun replyComposer_counterUpdates_and281Disables() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(PLACEHOLDER).assertExists()
            onNodeWithText(CTA_REPLY).assertIsNotEnabled() // empty → disabled
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("a".repeat(281))
            onNodeWithText("281/280").assertExists()
            onNodeWithText(CTA_REPLY).assertIsNotEnabled() // over-limit → disabled
        }
    }

    @Test
    fun reply201_appendsLocally_bumpsCount_withoutRefetch() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.Loaded(emptyList(), nextCursor = null),
                replyOutcome = ReplyPostOutcome.Success(fakeReply(content = "NEW_REPLY")),
            )
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(replyCount = 2), onBack = {}) } } }
            onNodeWithText(REPLIES_EMPTY).assertExists()
            onNodeWithTag(POST_DETAIL_REPLY_COUNT_TAG).assertTextEquals("2")
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_REPLY).performClick()
            waitForIdle()
            onNodeWithText("NEW_REPLY").assertExists() // appended locally
            onNodeWithTag(POST_DETAIL_REPLY_COUNT_TAG).assertTextEquals("3") // count bumped
            assertEquals(1, fake.postReplyCount)
            assertEquals(1, fake.loadRepliesCount, "the 201 append must NOT trigger a replies re-fetch")
        }
    }

    @Test
    fun reply429_showsReplyCapUpsell() {
        installKoin(FakePostDetailFlow(replyOutcome = ReplyPostOutcome.RateLimited(retryAfterSeconds = 3600)))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_REPLY).performClick()
            waitForIdle()
            onNodeWithText(REPLY_CAP_1H).assertExists()
        }
    }

    @Test
    fun replyInvalidContent_showsRetryableNetworkBanner() {
        installKoin(FakePostDetailFlow(replyOutcome = ReplyPostOutcome.InvalidContent))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).performTextInput("halo")
            onNodeWithText(CTA_REPLY).performClick()
            waitForIdle()
            // The defensive InvalidContent maps to the generic retryable banner (no dedicated copy in v1).
            onNodeWithText(ERR_NETWORK).assertExists()
        }
    }

    // ---- back affordance ----

    @Test
    fun backAffordance_invokesOnBack() {
        installKoin(FakePostDetailFlow())
        var backCount = 0
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = { backCount++ }) } } }
            onNodeWithText(CTA_CLOSE).assertExists()
            onNodeWithTag(POST_DETAIL_BACK_TAG).performClick()
            waitForIdle()
            assertEquals(1, backCount, "the back affordance invokes the hoisted onBack")
        }
    }

    // ---- mobile-post-editing: the single-post-read refresh on resume (real repos over a capturing MockEngine) ----

    @Test
    fun postDetail_issuesSinglePostRefresh_plusSubResourceRequests() {
        val captured = mutableListOf<String>()
        val mockClient =
            HttpClientFactory.create(
                installTimeouts = false,
                apiBaseUrl = "http://test.local",
                tokenStore = InMemoryTokenStore(),
                sessionInvalidator = SessionInvalidator(InMemoryTokenStore()),
                engine =
                    MockEngine { request ->
                        captured += request.url.encodedPath
                        when {
                            request.url.encodedPath.endsWith("/likes/count") ->
                                respond("""{"count":0}""", HttpStatusCode.OK, JSON)
                            request.url.encodedPath.endsWith("/replies") ->
                                respond("""{"replies":[],"next_cursor":null}""", HttpStatusCode.OK, JSON)
                            // mobile-post-editing: the single-post-read refresh (GET /posts/{id}).
                            else ->
                                respond(
                                    """{"id":"p1","authorUsername":"u","authorDisplayName":"D","content":"halo",""" +
                                        """"city_name":"","createdAt":"t","liked_by_viewer":false,""" +
                                        """"reply_count":0,"isAuthor":false}""",
                                    HttpStatusCode.OK,
                                    JSON,
                                )
                        }
                    },
                installLogging = false,
                nowMillis = { 0L },
            )
        val detailRepo = PostDetailRepository(LikeApiClient(mockClient), ReplyApiClient(mockClient))
        val editRepo = PostEditRepository(PostEditApiClient(mockClient), SinglePostApiClient(mockClient))
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single<PostDetailFlow> { detailRepo }
                    single<PostEditFlow> { editRepo }
                },
            )
        }
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            // Entry loads fire from real coroutines + MockEngine (async w.r.t. compose idle), so poll the
            // captured list directly. The "fetch on open" change adds the single-post refresh GET alongside
            // the like + reply sub-resource loads. Generous ceiling for batch Robolectric CPU contention (#228).
            waitUntil(timeoutMillis = 30_000) { captured.contains("/api/v1/posts/p1") }
            // mobile-post-editing reverses the prior no-single-post-GET guarantee: the refresh IS now issued.
            assertTrue(captured.contains("/api/v1/posts/p1"), "the single-post-read refresh is issued; captured=$captured")
            assertTrue(
                captured.all {
                    it == "/api/v1/posts/p1" ||
                        it == "/api/v1/posts/p1/replies" ||
                        it == "/api/v1/posts/p1/likes/count"
                },
                "only the post + its sub-resources; captured=$captured",
            )
        }
    }

    // ---- mobile-inline-post-actions: the reply-shortcut composer autofocus ----

    // mobile-post-detail § "Reply composer autofocuses on reply-shortcut entry": a route carrying
    // focusReplyComposer = true focuses the composer (IME up) once on first composition.
    @Test
    fun replyShortcutEntry_focusesTheComposer() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme { PostDetailScreen(route = route(focusReplyComposer = true), onBack = {}) }
                }
            }
            waitForIdle()
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).assertIsFocused()
        }
    }

    // The whole-card open (focusReplyComposer = false, the default) keeps today's behavior: no autofocus.
    @Test
    fun wholeCardEntry_doesNotFocusTheComposer() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent {
                KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } }
            }
            waitForIdle()
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).assertIsNotFocused()
        }
    }

    // mobile-post-detail § autofocus scenario "A restored entry does not re-fire a consumed autofocus":
    // the consume-once marker is SAVEABLE, so a config-change/process-death restore must not re-focus.
    // The restore is simulated the way StateRestorationTester does internally (that junit4 harness is
    // not on this project's classpath): generation 0 composes with a fresh SaveableStateRegistry and
    // autofocuses; its state is performSave()d and generation 1 recomposes from the captured values —
    // the restored consumed=true marker suppresses the re-fire, so the field comes up NOT focused.
    @Test
    fun restoredEntry_doesNotReFireAConsumedAutofocus() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            var savedState: Map<String, List<Any?>>? = null
            var registry: SaveableStateRegistry? = null
            var generation by mutableStateOf(0)
            // A 1dp focus "parking" node OUTSIDE the key(generation) scope: focus is moved here before
            // the recreate so the restored generation starts from an unfocused field — the assertion
            // then discriminates exactly "did the autofocus re-fire?" (in-window recreation otherwise
            // lets the old generation's focus bleed into the new field).
            val parkRequester = FocusRequester()
            setContent {
                Column {
                    Box(Modifier.size(1.dp).focusRequester(parkRequester).focusable())
                    // The screen is recreated by leaving and re-entering the SAME if-branch slot (NOT
                    // key(generation): a changed key() value alters the composite key hash, so the
                    // rememberSaveable auto-keys would differ between generations and the restore map
                    // would never match — the exact false-negative this harness must avoid).
                    if (generation != -1) {
                        val current =
                            remember(generation) {
                                SaveableStateRegistry(restoredValues = savedState, canBeSaved = { true })
                            }
                        registry = current
                        CompositionLocalProvider(LocalSaveableStateRegistry provides current) {
                            KoinContext {
                                NearYouTheme {
                                    PostDetailScreen(route = route(focusReplyComposer = true), onBack = {})
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            // Generation 0: the reply-shortcut entry consumed its autofocus.
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).assertIsFocused()
            // Park focus away from the field, then save + dispose + restore (the config-change/
            // process-death path): save while alive, drop the branch, re-enter it with the captured map.
            runOnIdle { parkRequester.requestFocus() }
            waitForIdle()
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).assertIsNotFocused()
            runOnIdle { savedState = registry!!.performSave() }
            runOnIdle { generation = -1 }
            waitForIdle()
            runOnIdle { generation = 1 }
            waitForIdle()
            // The restored entry decodes consumed=true → the autofocus does NOT re-fire. (If the
            // consume-once marker were NOT saveable, generation 1 would re-run the autofocus and
            // steal focus back from the park node — failing this assertion.)
            onNodeWithTag(POST_DETAIL_REPLY_FIELD_TAG).assertIsNotFocused()
        }
    }
}
