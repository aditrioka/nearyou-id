package id.nearyou.app.screens.routing

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun postDetail() =
    PostDetailRoute(
        postId = "p1",
        content = "hi",
        cityName = "Jakarta",
        distanceM = null,
        createdAtIso = "2026-06-08T00:00:00Z",
        likedByViewer = false,
        replyCount = 0,
    )

/**
 * 9.8 — destination preservation (mobile-session-expiry-and-proactive-refresh, design D5). Pure
 * coverage of the [PendingReturnDestination] holder, the [isAuthRoute] capture filter, and the
 * [restoreAfterReauth] back-stack rebuild — no Compose runner needed (a [NavBackStack] is constructed
 * directly and mutated via the snapshot system).
 */
class PendingReturnDestinationTest {
    @Test
    fun captureThenPeek_returnsTheDestinationAndMarksInvoluntary() {
        val holder = PendingReturnDestination()
        // A fresh holder is a voluntary (fresh-launch) entry with no captured destination.
        assertFalse(holder.wasInvoluntary(), "a fresh holder is not involuntary")
        assertNull(holder.peek())

        holder.capture(postDetail())
        assertTrue(holder.wasInvoluntary(), "capture marks the next entry involuntary")
        assertEquals(postDetail(), holder.peek())
    }

    @Test
    fun clear_resetsBothTheFlagAndTheDestination() {
        val holder = PendingReturnDestination().apply { capture(postDetail()) }
        holder.clear()
        assertFalse(holder.wasInvoluntary(), "clear resets the involuntary flag")
        assertNull(holder.peek(), "clear drops the captured destination")
    }

    @Test
    fun captureNull_marksInvoluntaryWithNoDestination() {
        // The auth-route / empty-stack fallback path: involuntary, but restore to Home.
        val holder = PendingReturnDestination().apply { capture(null) }
        assertTrue(holder.wasInvoluntary())
        assertNull(holder.peek())
    }

    @Test
    fun isAuthRoute_classifiesAuthFlowRoutes() {
        assertTrue(RootRoute.isAuthRoute())
        assertTrue(SignInRoute.isAuthRoute())
        assertTrue(AgeGateRoute.isAuthRoute())
        assertTrue(ConsentRoute.isAuthRoute())
        assertFalse(HomeRoute.isAuthRoute())
        assertFalse(PostCreationRoute.isAuthRoute())
        assertFalse(postDetail().isAuthRoute())
    }

    @Test
    fun restoreAfterReauth_nonAuthOverlay_rebuildsHomePlusDestination() {
        // On a non-auth destination (a PostDetail the user was viewing), restore [Home, destination]
        // so the section shell sits beneath and back-press returns to it.
        val backStack = NavBackStack<NavKey>(SignInRoute)
        backStack.restoreAfterReauth(postDetail())
        assertEquals(listOf<NavKey>(HomeRoute, postDetail()), backStack.toList())
    }

    @Test
    fun restoreAfterReauth_homeOrNull_restoresHomeOnly() {
        val fromHome = NavBackStack<NavKey>(SignInRoute).apply { restoreAfterReauth(HomeRoute) }
        assertEquals(listOf<NavKey>(HomeRoute), fromHome.toList())

        val fromNull = NavBackStack<NavKey>(SignInRoute).apply { restoreAfterReauth(null) }
        assertEquals(listOf<NavKey>(HomeRoute), fromNull.toList(), "null destination falls back to Home")
    }

    @Test
    fun capturedAuthRoute_fallsBackToHome() {
        // Mirrors SessionExpiryEffect's capture rule: an auth top is recorded as null → restore Home.
        val top: NavKey = SignInRoute
        val captured = if (top.isAuthRoute()) null else top
        assertNull(captured, "an auth top is not captured as a return destination")

        val backStack = NavBackStack<NavKey>(SignInRoute)
        backStack.restoreAfterReauth(captured)
        assertEquals(listOf<NavKey>(HomeRoute), backStack.toList(), "captured auth route restores to Home")
    }
}
