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
import androidx.compose.ui.test.assertCountEquals
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
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportSubmitter
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
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
import id.nearyou.app.theme.NearYouTheme
import id.nearyou.app.ui.components.LOAD_MORE_FOOTER_TAG
import id.nearyou.app.ui.components.LOAD_MORE_RETRY_TAG
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

/** The session user for the reply self-block gate — matches NO fixture reply author by default. */
private const val SELF_USER_ID = "99999999-9999-9999-9999-999999999999"
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

/** The canonical block dialog body (profile_block_confirm_body — docs/03 §Block User UX, verbatim). */
private const val BLOCK_DIALOG_BODY = "Kalian berdua tidak akan saling melihat post, profil, atau bisa memulai percakapan baru."
private val JSON = headersOf("Content-Type", "application/json")

/**
 * Render + interaction coverage of `PostDetailScreen` via the Robolectric-backed CMP UI runner (task 9.3),
 * driven by `FakePostDetailFlow` (plus one real-repository-over-MockEngine test for the mobile-post-editing
 * single-post-read refresh on resume). The outcome→state projection is covered purely by `PostDetailUiStateTest`; this suite
 * verifies the composable renders the header (empty-city tolerated, no `author_id`/coordinate), the
 * replies states (incl. a viewer's-own auto-hidden reply rendering normally), the like toggle (optimistic
 * + revert + 429 upsell + count + graceful degradation), the reply composer (counter, 280-disable,
 * 201 local-append-without-refetch, 429 upsell, error banner), the mobile-content-report affordances (post
 * report shown for a non-authored post / hidden on own post, per-reply report ungated by authorship,
 * dialog submit → success message, reply target_id = reply id with no author UUID), and the block-deferral
 * negative. In the Release-variant `*ScreenTest` exclude (the ui-test-manifest host is debug-only).
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
        reportSubmitter: ReportSubmitter = FakeReportSubmitter(),
        blockSubmitter: BlockSubmitter = FakeBlockSubmitter(),
        // The session user for the reply self-block gate; SELF_USER_ID owns none of the fixture replies,
        // so the block item defaults to visible on another user's reply.
        selfUserIdProvider: SelfUserIdProvider = FakeSelfUserIdProvider(SELF_USER_ID),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single { flow }
                    single { editFlow }
                    single { reportSubmitter }
                    single { blockSubmitter }
                    single { selfUserIdProvider }
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
        imageUrl: String? = null,
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
            imageUrl = imageUrl,
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
    fun header_showsContentAndPostedFrom_andNoBlockAffordance() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithText(CONTENT).assertExists()
            onNodeWithText(POSTED_FROM).assertExists()
            // Deferral negative: NO block affordance on the detail surface (block stays deferred —
            // mobile-post-detail "Block kebab action is deferred"). The Laporkan-absent assertion was
            // REMOVED — report ships now (mobile-content-report); its presence is covered by the report
            // affordance tests below.
            onNodeWithText("Blokir", substring = true).assertDoesNotExist()
        }
    }

    // mobile-post-detail § "Header renders the author display identity from the payload" — the identity
    // row comes SOLELY from the route (no per-identity network call; the outbound-surface test below
    // keeps pinning the allowed request set — now incl. the mobile-post-editing refresh GET).
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

    // image-attached-posts § "Attached image renders when imageUrl is present, and nothing when absent" —
    // the detail image rides the route payload (no by-id re-fetch). The bytes never decode in Robolectric;
    // this asserts the AsyncImage NODE is composed/omitted by the imageUrl guard (the behavioral contract).
    @Test
    fun attachedImageRendersWhenRouteImageUrlPresent() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent {
                KoinContext {
                    NearYouTheme {
                        PostDetailScreen(route = route(imageUrl = "https://img.example/acct/p1/public"), onBack = {})
                    }
                }
            }
            onNodeWithTag(POST_DETAIL_IMAGE_TAG, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun noImageElementWhenRouteImageUrlNull() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(imageUrl = null), onBack = {}) } } }
            onNodeWithText(CONTENT).assertExists() // header still renders
            onNodeWithTag(POST_DETAIL_IMAGE_TAG, useUnmergedTree = true).assertDoesNotExist()
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

    // ---- mobile-content-report: report affordances + entry points ----
    //
    // NOTE on dialog-internals coverage: the shared report dialog hosts an OutlinedTextField inside an
    // AlertDialog. Over PostDetailScreen's LazyColumn Scaffold content, that specific combination triggers a
    // Robolectric-only NEVER-SETTLING measure pass ("Compose did not get idle after N attempts") — a known
    // Compose-test layout quirk; it renders + idles fine on a real device, and the SAME dialog opens cleanly
    // in ProfileScreenTest (whose host is a verticalScroll Column, not a LazyColumn). So these screen tests
    // assert everything observable WITHOUT opening the dialog body (affordance presence/absence, the
    // "Laporkan" menu entry point, and the PII negative-guard that no author UUID renders). The dialog's
    // SUBMIT behavior — Submitted/Duplicate→the same success message, RateLimited/NetworkError mapping, and
    // the reply submission carrying target_id = the reply id ONLY with no author_id — is covered precisely
    // (and more strongly, via a capturing FakeReportSubmitter) in PostDetailViewModelTest.

    // The post-header report affordance shows for a NON-authored post (default refresh = Unavailable, so
    // isAuthor stays false → !isAuthor); tapping the kebab surfaces the "Laporkan" entry point.
    @Test
    fun postReportAffordance_present_whenNotAuthor() {
        installKoin(FakePostDetailFlow())
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_REPORT_POST_TAG).assertExists()
            onNodeWithTag(POST_DETAIL_REPORT_POST_TAG).performClick() // open the overflow
            onNodeWithText("Laporkan").assertExists() // the single menu item (the report entry point)
        }
    }

    // The post-header report affordance is ABSENT for the viewer's OWN post (isAuthor = true) — the Edit
    // affordance shows instead (mirrors the editAffordance_shown test). Locks design D4's post gate.
    @Test
    fun postReportAffordance_absent_whenAuthor() {
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
            // The Edit affordance appears only after the resume refresh reports isAuthor = true.
            waitUntil(timeoutMillis = 2_000) { onAllNodesWithTag(POST_DETAIL_EDIT_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_EDIT_TAG).assertExists()
            onNodeWithTag(POST_DETAIL_REPORT_POST_TAG).assertDoesNotExist() // own post → no report kebab
            // mobile-block-from-content: the block item lives inside that kebab, so it is absent too —
            // you never see "Blokir" on your own post (spec § hidden on the viewer's own post).
            onNodeWithTag(POST_DETAIL_BLOCK_POST_TAG).assertDoesNotExist()
        }
    }

    // Each reply row exposes a report affordance, regardless of authorship (author_id is dropped, so the
    // client cannot gate by it — design D4). Two replies → two report kebabs; tapping one surfaces the
    // "Laporkan" entry point. The PII negative-guard (no author UUID renders) is also asserted here.
    @Test
    fun replyReportAffordance_presentOnEveryReply_noAuthorUuid() {
        installKoin(
            FakePostDetailFlow(
                repliesOutcome =
                    RepliesOutcome.Loaded(
                        listOf(
                            // A viewer-authored reply (modelled here as auto-hidden — the viewer's own) and a
                            // non-authored reply both expose the affordance; both carry the PII author UUID on
                            // the wire, which must never render.
                            fakeReply(id = "rOwn", authorId = AUTHOR_UUID, content = "OWN_REPLY", isAutoHidden = true),
                            fakeReply(id = "rOther", authorId = AUTHOR_UUID, content = "OTHER_REPLY"),
                        ),
                        nextCursor = null,
                    ),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("OWN_REPLY").fetchSemanticsNodes().isNotEmpty() }
            // Both reply rows carry the report affordance (ungated by authorship).
            onAllNodesWithTag(POST_DETAIL_REPORT_REPLY_TAG).assertCountEquals(2)
            // PII negative-guard: no author UUID renders anywhere in the reply tree (the report sends only
            // the reply id — that wire assertion is in PostDetailViewModelTest's capturing-submitter test).
            onNodeWithText(AUTHOR_UUID, substring = true).assertDoesNotExist()
            // The reply report entry point surfaces "Laporkan" (the dialog body itself can't be opened under
            // Robolectric here — see the NOTE above; the submit path is covered at the VM level).
            onAllNodesWithTag(POST_DETAIL_REPORT_REPLY_TAG)[0].performScrollTo().performClick()
            onNodeWithText("Laporkan").assertExists()
        }
    }

    // ---- mobile-block-from-content: block affordances + the shared dialog + outcomes ----
    //
    // The BlockConfirmDialog is a plain-text AlertDialog (no OutlinedTextField), so it opens cleanly
    // under Robolectric even over the LazyColumn host — unlike the report dialog (see the NOTE above).
    // The outcome→message/pop/removal mapping is additionally covered (via a capturing
    // FakeBlockSubmitter) in PostDetailViewModelTest.

    // 5.1: non-authored post + a resolved freshness authorUserId → the kebab hosts "Blokir @{username}";
    // confirming the canonical dialog submits the author UUID and pops the screen; the UUID never renders.
    @Test
    fun postBlock_item_dialog_confirm_popsBack_neverRenderingTheUuid() {
        val submitter = FakeBlockSubmitter(BlockOutcome.Blocked)
        var backCalls = 0
        installKoin(
            FakePostDetailFlow(),
            FakePostEditFlow(
                refreshOutcome =
                    PostRefreshOutcome.Loaded(content = CONTENT, editedAt = null, isAuthor = false, authorUserId = AUTHOR_UUID),
            ),
            blockSubmitter = submitter,
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = { backCalls++ }) } } }
            // The block item depends on the resume-refresh authorUserId (async) — poll the kebab open.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_DETAIL_REPORT_POST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_REPORT_POST_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_DETAIL_BLOCK_POST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Blokir @raka.jkt").assertExists() // the canonical menu label
            onNodeWithTag(POST_DETAIL_BLOCK_POST_TAG).performClick()
            // The shared dialog renders the canonical docs/03 copy for @raka.jkt.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_DETAIL_BLOCK_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Blokir @raka.jkt?").assertExists()
            onNodeWithText(BLOCK_DIALOG_BODY).assertExists()
            onNodeWithText("Blokir").performClick() // the destructive confirm (exact match ≠ the title)
            waitUntil(timeoutMillis = 5_000) { backCalls == 1 }
            assertEquals(AUTHOR_UUID, submitter.lastUserId, "the post block targets the freshness-read author UUID")
            // PII negative-guard: the author UUID never appears in the rendered tree.
            onNodeWithText(AUTHOR_UUID, substring = true).assertDoesNotExist()
        }
    }

    // 5.1: the freshness read degraded (Unavailable → no authorUserId) → the kebab still offers report,
    // but NO block item (graceful degradation, the same dependence as the Edit affordance).
    @Test
    fun postBlockItem_absent_whenNoAuthorUserIdResolved() {
        installKoin(FakePostDetailFlow()) // default FakePostEditFlow refresh = Unavailable
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            onNodeWithTag(POST_DETAIL_REPORT_POST_TAG).performClick()
            onNodeWithText("Laporkan").assertExists()
            onNodeWithTag(POST_DETAIL_BLOCK_POST_TAG).assertDoesNotExist()
        }
    }

    // 5.3: "Batal" dismisses the dialog with ZERO block submissions and an unchanged surface.
    @Test
    fun blockDialog_batal_issuesZeroSubmissions() {
        val submitter = FakeBlockSubmitter(BlockOutcome.Blocked)
        installKoin(
            FakePostDetailFlow(),
            FakePostEditFlow(
                refreshOutcome =
                    PostRefreshOutcome.Loaded(content = CONTENT, editedAt = null, isAuthor = false, authorUserId = AUTHOR_UUID),
            ),
            blockSubmitter = submitter,
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_DETAIL_REPORT_POST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_REPORT_POST_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_DETAIL_BLOCK_POST_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_BLOCK_POST_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_DETAIL_BLOCK_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Batal").performClick()
            onNodeWithTag(POST_DETAIL_BLOCK_DIALOG_TAG).assertDoesNotExist()
            assertEquals(0, submitter.submitCount, "Batal issues no POST /api/v1/blocks/{userId}")
            onNodeWithText(CONTENT).assertExists() // the surface is unchanged
        }
    }

    // 5.2 + 5.6: another user's reply renders the identity row (display name — mockup frame 7) and its
    // kebab hosts "Blokir @{username}"; confirming removes the row locally (no pop) targeting the reply
    // author_id; the reply author UUID never renders.
    @Test
    fun replyBlock_identityRow_dialog_confirm_removesTheRow() {
        val submitter = FakeBlockSubmitter(BlockOutcome.Blocked)
        var backCalls = 0
        installKoin(
            FakePostDetailFlow(
                repliesOutcome =
                    RepliesOutcome.Loaded(
                        listOf(
                            fakeReply(
                                id = "rOther",
                                authorId = AUTHOR_UUID,
                                authorUsername = "sinta.mhr",
                                authorDisplayName = "Sinta Maharani",
                                content = "OTHER_REPLY",
                            ),
                        ),
                        nextCursor = null,
                    ),
            ),
            blockSubmitter = submitter,
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = { backCalls++ }) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("OTHER_REPLY").fetchSemanticsNodes().isNotEmpty() }
            // 5.6: the identity row renders the display name; the author UUID never renders.
            onNodeWithText("Sinta Maharani").assertExists()
            onNodeWithText(AUTHOR_UUID, substring = true).assertDoesNotExist()
            onNodeWithTag(POST_DETAIL_REPORT_REPLY_TAG).performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_DETAIL_BLOCK_REPLY_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Blokir @sinta.mhr").assertExists()
            onNodeWithTag(POST_DETAIL_BLOCK_REPLY_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(POST_DETAIL_BLOCK_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Blokir @sinta.mhr?").assertExists()
            onNodeWithText("Blokir").performClick()
            // The confirmed reply block removes the row locally — and never pops the screen.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("OTHER_REPLY").fetchSemanticsNodes().isEmpty() }
            assertEquals(AUTHOR_UUID, submitter.lastUserId, "the reply block targets the reply author_id")
            assertEquals(0, backCalls, "a reply block never pops the screen")
        }
    }

    // 5.2: the viewer's OWN reply (author_id == SelfUserIdProvider id) exposes report but NO block item.
    @Test
    fun replyBlockItem_absentOnTheViewersOwnReply() {
        installKoin(
            FakePostDetailFlow(
                repliesOutcome =
                    RepliesOutcome.Loaded(
                        listOf(
                            fakeReply(
                                id = "rOwn",
                                authorId = SELF_USER_ID,
                                authorUsername = "self.user",
                                authorDisplayName = "Self User",
                                content = "OWN_REPLY",
                            ),
                        ),
                        nextCursor = null,
                    ),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("OWN_REPLY").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(POST_DETAIL_REPORT_REPLY_TAG).performScrollTo().performClick()
            onNodeWithText("Laporkan").assertExists()
            onNodeWithTag(POST_DETAIL_BLOCK_REPLY_TAG).assertDoesNotExist()
        }
    }

    // 5.2 + 5.6 (older-backend guard): an identity-less reply renders content + timestamp with NO
    // identity row AND no block item (the canonical copy is unrenderable without a username).
    @Test
    fun identityLessReply_rendersWithoutIdentityRow_andWithoutBlockItem() {
        installKoin(
            FakePostDetailFlow(
                repliesOutcome =
                    RepliesOutcome.Loaded(
                        listOf(
                            fakeReply(
                                id = "rLegacy",
                                authorId = AUTHOR_UUID,
                                authorUsername = null,
                                authorDisplayName = null,
                                content = "LEGACY_REPLY",
                            ),
                        ),
                        nextCursor = null,
                    ),
            ),
        )
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("LEGACY_REPLY").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("LEGACY_REPLY").assertExists() // no crash, content renders
            onNodeWithTag(POST_DETAIL_REPORT_REPLY_TAG).performScrollTo().performClick()
            onNodeWithText("Laporkan").assertExists()
            onNodeWithTag(POST_DETAIL_BLOCK_REPLY_TAG).assertDoesNotExist()
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

    // ---- replies cursor load-more (screen integration) ----

    // A short page-1 replies list keeps the load-more footer within the scroll-end threshold, so the
    // eager detector fires load-more without an explicit gesture; the appended page-2 reply appears AND
    // the follow-up reused the page-1 anchor's cursor c1.
    @Test
    fun repliesLoadMore_appendsTheNextPage_reusingTheCursor() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.Loaded(listOf(fakeReply(id = "r1", content = "REPLY_PAGE1")), nextCursor = "c1"),
                loadMoreRepliesPages =
                    listOf(RepliesOutcome.Loaded(listOf(fakeReply(id = "r2", content = "REPLY_PAGE2")), nextCursor = null)),
            )
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("REPLY_PAGE2").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("REPLY_PAGE1").assertExists()
            assertEquals(listOf("c1"), fake.loadMoreRepliesCalls, "load-more fetched the retained cursor c1")
        }
    }

    // A failed replies load-more shows the non-destructive retry footer (page-1 retained); tapping retry
    // recovers and the footer clears.
    @Test
    fun repliesLoadMoreError_showsRetryFooter_andRetryRecovers() {
        val fake =
            FakePostDetailFlow(
                repliesOutcome = RepliesOutcome.Loaded(listOf(fakeReply(id = "r1", content = "REPLY_PAGE1")), nextCursor = "c1"),
                loadMoreRepliesPages =
                    listOf(
                        RepliesOutcome.NetworkError,
                        RepliesOutcome.Loaded(listOf(fakeReply(id = "r2", content = "REPLY_PAGE2")), nextCursor = null),
                    ),
            )
        installKoin(fake)
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(LOAD_MORE_RETRY_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("REPLY_PAGE1").assertExists() // the loaded list is retained on load-more failure
            onNodeWithTag(LOAD_MORE_RETRY_TAG).performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("REPLY_PAGE2").fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0) // footer clears on success
        }
    }

    // The load-more footer never co-occurs with the replies initial-load skeleton (it lives inside the
    // replies Content list, which the loading state does not render) — mobile-design-system load-more pattern.
    @Test
    fun repliesLoadMoreFooter_absentDuringInitialLoad() {
        installKoin(FakePostDetailFlow(suspendRepliesForever = true))
        runComposeUiTest {
            setContent { KoinContext { NearYouTheme { PostDetailScreen(route = route(), onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(LOADING).fetchSemanticsNodes().isNotEmpty() }
            onAllNodesWithTag(LOAD_MORE_FOOTER_TAG).assertCountEquals(0)
            onAllNodesWithTag(LOAD_MORE_RETRY_TAG).assertCountEquals(0)
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
                    single<ReportSubmitter> { FakeReportSubmitter() }
                    single<BlockSubmitter> { FakeBlockSubmitter() }
                    single<SelfUserIdProvider> { FakeSelfUserIdProvider(SELF_USER_ID) }
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
