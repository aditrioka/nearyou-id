package id.nearyou.app.screens.notifications

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsOutcome
import id.nearyou.app.notifications.PartnerResolution
import id.nearyou.app.notifications.PostTargetResolution
import id.nearyou.app.notifications.fakeNotification
import id.nearyou.app.screens.home.PostDetailTarget
import id.nearyou.app.theme.NearYouTheme
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Canonical Bahasa Indonesia row copy (byte-identical to shared/resources strings.xml).
private const val COPY_POST_LIKED = "Seseorang menyukai postingan kamu" // notif_post_liked
private const val COPY_FOLLOWED = "Seseorang mulai mengikuti kamu" // notif_followed
private const val COPY_CHAT = "Pesan baru" // notif_chat_message
private const val COPY_SUB_EXPIRED = "Langganan Premium kamu telah berakhir" // notif_subscription_expired (#343)
private const val POST_UNAVAILABLE = "Postingan tidak tersedia" // notifications_post_unavailable

/**
 * Deep-link tap-through coverage of `NotificationsScreen` (`mobile-notifications-deep-link-targets` task
 * 6.3): tapping a row invokes the correct hoisted nav callback — `post` → `onOpenPost` (with `distanceM`
 * null), `followed` → `onOpenProfile(actor)`, `chat_message` → `onOpenChatThread(conversation, partner)` —
 * an unavailable post-target shows the non-blocking affordance and invokes NO callback, and a no-target
 * informational row invokes no callback. In the Release-variant `*ScreenTest` exclude (the
 * `ui-test-manifest` host activity is debug-only).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class NotificationsScreenNavTest {
    private lateinit var fake: FakeNotificationsFlow

    private fun installKoin(
        outcome: NotificationsOutcome,
        postTargetResolution: PostTargetResolution =
            PostTargetResolution.Resolved(
                postId = "p-resolved",
                authorUsername = "budi",
                authorDisplayName = "Budi",
                content = "halo dunia",
                cityName = "Jakarta",
                createdAtIso = "2026-05-31T10:00:00Z",
                likedByViewer = true,
                replyCount = 5,
                imageUrl = null,
            ),
        partnerResolution: PartnerResolution = PartnerResolution.Resolved("budi", "Budi"),
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        fake =
            FakeNotificationsFlow(
                outcome = outcome,
                postTargetResolution = postTargetResolution,
                partnerResolution = partnerResolution,
            )
        startKoin { modules(module { single<NotificationsFlow> { fake } }) }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun postTargetTap_invokesOnOpenPost_withNullDistance() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(id = "n1", type = "post_liked", targetType = "post", targetId = "p1")),
                null,
            ),
        )
        runComposeUiTest {
            var capturedPost: PostDetailTarget? = null
            var profileCalls = 0
            setContent {
                KoinContext {
                    NearYouTheme {
                        NotificationsScreen(
                            onOpenPost = { capturedPost = it },
                            onOpenProfile = { profileCalls++ },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText(COPY_POST_LIKED).performClick()
            waitForIdle()

            val post = requireNotNull(capturedPost) { "onOpenPost should be invoked for a post-target row" }
            assertNull(post.distanceM, "the by-id projection omits coordinates → distanceM is null")
            assertEquals("Jakarta", post.cityName)
            assertEquals(0, profileCalls, "no other nav callback fires")
        }
    }

    @Test
    fun followedTap_invokesOnOpenProfile_withActorId() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(id = "n1", type = "followed", targetType = null, actorUserId = "actor-1")),
                null,
            ),
        )
        runComposeUiTest {
            var capturedUserId: String? = null
            var postCalls = 0
            setContent {
                KoinContext {
                    NearYouTheme {
                        NotificationsScreen(
                            onOpenPost = { postCalls++ },
                            onOpenProfile = { capturedUserId = it },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText(COPY_FOLLOWED).performClick()
            waitForIdle()

            assertEquals("actor-1", capturedUserId, "onOpenProfile should be invoked with the follower's id")
            assertEquals(0, postCalls)
        }
    }

    @Test
    fun chatMessageTap_invokesOnOpenChatThread_withResolvedPartner() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(
                    fakeNotification(
                        id = "n1",
                        type = "chat_message",
                        targetType = "message",
                        actorUserId = "actor-1",
                        bodyData = buildJsonObject { put("conversation_id", "conv-1") },
                    ),
                ),
                null,
            ),
        )
        runComposeUiTest {
            var captured: Triple<String, String, String>? = null
            setContent {
                KoinContext {
                    NearYouTheme {
                        NotificationsScreen(
                            onOpenChatThread = { c, u, d -> captured = Triple(c, u, d) },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText(COPY_CHAT).performClick()
            waitForIdle()

            assertEquals(Triple("conv-1", "budi", "Budi"), captured, "onOpenChatThread should carry the conversation + resolved partner")
        }
    }

    @Test
    fun unavailablePostTap_showsAffordance_andInvokesNoCallback() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(id = "n1", type = "post_liked", targetType = "post", targetId = "p1")),
                null,
            ),
            postTargetResolution = PostTargetResolution.Unavailable,
        )
        runComposeUiTest {
            var postCalls = 0
            // Freeze the clock so the auto-dismissing affordance stays asserted (the LaunchedEffect delay
            // would otherwise advance under autoAdvance and clear it).
            mainClock.autoAdvance = false
            setContent {
                KoinContext {
                    NearYouTheme {
                        NotificationsScreen(onOpenPost = { postCalls++ })
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            onNodeWithText(COPY_POST_LIKED).performClick()
            mainClock.advanceTimeByFrame()

            onNodeWithTag(NOTIFICATION_POST_UNAVAILABLE_TAG).assertExists()
            assertEquals(0, postCalls, "an unavailable post-target invokes no navigation")
        }
    }

    @Test
    fun informationalTap_invokesNoNavCallback() {
        installKoin(
            NotificationsOutcome.Loaded(
                listOf(fakeNotification(id = "n1", type = "subscription_expired", targetType = null, actorUserId = null)),
                null,
            ),
        )
        runComposeUiTest {
            var anyNav = false
            setContent {
                KoinContext {
                    NearYouTheme {
                        NotificationsScreen(
                            onOpenPost = { anyNav = true },
                            onOpenProfile = { anyNav = true },
                            onOpenChatThread = { _, _, _ -> anyNav = true },
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText(COPY_SUB_EXPIRED).performClick()
            waitForIdle()

            assertTrue(!anyNav, "an informational no-target row navigates nowhere")
        }
    }
}
