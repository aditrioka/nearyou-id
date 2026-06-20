package id.nearyou.app.screens.profile

import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.profile.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun profile(
    isSelf: Boolean = false,
    followedByViewer: Boolean = false,
) = UserProfile(
    userId = "u1",
    username = "raka.jkt",
    displayName = "Raka Pratama",
    bio = "halo",
    followerCount = 3,
    followingCount = 5,
    isSelf = isSelf,
    followedByViewer = followedByViewer,
    isPremium = false,
    isPrivate = if (isSelf) false else null,
)

private fun project(
    outcome: ProfileOutcome?,
    isInitialLoad: Boolean = false,
    optimisticFollowed: Boolean? = null,
    isFollowInFlight: Boolean = false,
    message: ProfileMessage? = null,
    navigateBack: Boolean = false,
) = profileUiState(outcome, isInitialLoad, optimisticFollowed, isFollowInFlight, message, navigateBack)

class ProfileUiStateTest {
    @Test
    fun `initial load maps to Loading regardless of outcome`() {
        assertIs<ProfilePhase.Loading>(project(outcome = null, isInitialLoad = true).phase)
        assertIs<ProfilePhase.Loading>(
            project(outcome = ProfileOutcome.Loaded(profile()), isInitialLoad = true).phase,
        )
    }

    @Test
    fun `null outcome - not yet loaded - maps to Loading`() {
        assertIs<ProfilePhase.Loading>(project(outcome = null).phase)
    }

    @Test
    fun `loaded outcome maps to Content carrying the profile`() {
        val state = project(outcome = ProfileOutcome.Loaded(profile(isSelf = true)))
        val phase = assertIs<ProfilePhase.Content>(state.phase)
        assertEquals("raka.jkt", phase.profile.username)
        assertTrue(phase.profile.isSelf)
    }

    @Test
    fun `not-found maps to a single NotFound phase`() {
        assertIs<ProfilePhase.NotFound>(project(outcome = ProfileOutcome.NotFound).phase)
    }

    @Test
    fun `network error maps to Error`() {
        assertIs<ProfilePhase.Error>(project(outcome = ProfileOutcome.NetworkError).phase)
    }

    @Test
    fun `followedByViewer reflects the loaded value when no optimistic override`() {
        assertTrue(project(outcome = ProfileOutcome.Loaded(profile(followedByViewer = true))).followedByViewer)
        assertEquals(
            false,
            project(outcome = ProfileOutcome.Loaded(profile(followedByViewer = false))).followedByViewer,
        )
    }

    @Test
    fun `optimistic override wins over the loaded value`() {
        // Loaded as not-followed, but an in-flight optimistic follow flips it true.
        val state =
            project(
                outcome = ProfileOutcome.Loaded(profile(followedByViewer = false)),
                optimisticFollowed = true,
                isFollowInFlight = true,
            )
        assertTrue(state.followedByViewer)
        assertTrue(state.isFollowInFlight)
    }

    @Test
    fun `one-shot message and navigateBack are carried through`() {
        val state =
            project(
                outcome = ProfileOutcome.Loaded(profile()),
                message = ProfileMessage.BLOCK_SUCCESS,
                navigateBack = true,
            )
        assertEquals(ProfileMessage.BLOCK_SUCCESS, state.message)
        assertTrue(state.navigateBack)
    }
}
