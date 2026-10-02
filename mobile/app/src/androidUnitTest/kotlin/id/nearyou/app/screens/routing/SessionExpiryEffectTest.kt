package id.nearyou.app.screens.routing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SUB_USER_123_JWT
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStoreSelfUserIdProvider
import id.nearyou.app.billing.PremiumEntitlementSession
import id.nearyou.app.screens.paywall.FakePurchaseController
import kotlinx.coroutines.runBlocking
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

/**
 * premium-entitlement-lifecycle — involuntary invalidation unbinds the RevenueCat identity from the app-root
 * [SessionExpiryEffect] (after the re-route, OFF the token-refresh critical section that runs
 * `SessionInvalidator.invalidate`): consuming the session-expired signal re-routes to [SignInRoute] AND logs
 * RevenueCat out, so the next account never inherits this one's entitlement.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class SessionExpiryEffectTest {
    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun invalidation_reRoutesToSignIn_andLogsRevenueCatOut() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val store = InMemoryTokenStore(TokenPair(SUB_USER_123_JWT, "rt", Long.MAX_VALUE))
        val controller = FakePurchaseController()
        val session = PremiumEntitlementSession(TokenStoreSelfUserIdProvider(store), controller)
        val invalidator = SessionInvalidator(store)
        runBlocking { session.syncIdentity() } // bound as user-123
        startKoin {
            modules(
                module {
                    single { invalidator }
                    single { PendingReturnDestination() }
                    single { session }
                },
            )
        }

        runComposeUiTest {
            lateinit var backStack: NavBackStack<NavKey>
            setContent {
                KoinContext {
                    backStack = rememberNavBackStack(navSavedStateConfiguration, HomeRoute)
                    SessionExpiryEffect(backStack)
                }
            }
            waitForIdle()

            runBlocking { invalidator.invalidate() }

            waitUntil(timeoutMillis = 5_000) { controller.logOutCount == 1 }
            assertEquals(SignInRoute, backStack.last(), "the expiry still re-routes to sign-in")
            assertEquals(1, controller.logOutCount, "RevenueCat is logged out after the re-route")
        }
    }
}
