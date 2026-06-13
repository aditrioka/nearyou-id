package id.nearyou.app.screens.profile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.theme.NearYouTheme
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import kotlin.test.AfterTest
import kotlin.test.Test

private class FakeSelfUserId(private val id: String? = "self-1") : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = id
}

/**
 * iOS counterpart to the Robolectric [ProfileScreenTest] — the profile surface (`mobile-profile`) run
 * natively on the iOS simulator (task 9.5). Covers the self read (Profil section, `targetUserId = null`),
 * the other-user overlay (follow toggle present), and the constant-404 not-found state, reusing the
 * commonTest [FakeProfileFlow]. K/N-legal fn names (no `,()#`); see `SignInFlowIosTest` for the
 * v1-API + iosTest-placement rationale.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ProfileFlowIosTest {
    private fun installKoin(
        outcome: ProfileOutcome,
        selfId: String? = "self-1",
    ) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin {
            modules(
                module {
                    single<ProfileFlow> { FakeProfileFlow(profileOutcome = outcome) }
                    single<SelfUserIdProvider> { FakeSelfUserId(selfId) }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun selfProfileRendersIdentityWithoutActions() =
        runComposeUiTest {
            installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("self-1", isSelf = true)))
            setContent { KoinContext { NearYouTheme { ProfileScreen(targetUserId = null, onBack = null) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOWERS_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Raka Pratama").assertExists()
            onNodeWithTag(PROFILE_FOLLOW_TOGGLE_TAG).assertDoesNotExist()
        }

    @Test
    fun otherUserProfileShowsFollowToggle() =
        runComposeUiTest {
            installKoin(ProfileOutcome.Loaded(FakeProfileFlow.sampleProfile("u1")))
            setContent { KoinContext { NearYouTheme { ProfileScreen(targetUserId = "u1", onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_FOLLOW_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PROFILE_FOLLOW_TOGGLE_TAG).assertExists()
            onNodeWithTag(PROFILE_ACTIONS_MENU_TAG).assertExists()
        }

    @Test
    fun notFoundRendersTheNotFoundState() =
        runComposeUiTest {
            installKoin(ProfileOutcome.NotFound)
            setContent { KoinContext { NearYouTheme { ProfileScreen(targetUserId = "u1", onBack = {}) } } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(PROFILE_NOT_FOUND_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(PROFILE_NOT_FOUND_TAG).assertExists()
        }
}
