package id.nearyou.app.screens.routing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.auth.InMemoryTokenStore
import id.nearyou.app.auth.SUB_USER_123_JWT
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStoreSelfUserIdProvider
import id.nearyou.app.billing.PremiumEntitlementSession
import id.nearyou.app.screens.paywall.FakePurchaseController
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
 * premium-entitlement-lifecycle — the app-root [BillingIdentityEffect] binds a RESTORED session (a token already
 * in the store at cold start, no sign-in call) to its `users.id` on the first ON_RESUME, and composes as a no-op
 * when no [PremiumEntitlementSession] is bound (the fail-safe resolution).
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class BillingIdentityEffectTest {
    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun coldStartResume_bindsTheRestoredSession() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        val store = InMemoryTokenStore(TokenPair(SUB_USER_123_JWT, "rt", Long.MAX_VALUE))
        val controller = FakePurchaseController()
        startKoin { modules(module { single { PremiumEntitlementSession(TokenStoreSelfUserIdProvider(store), controller) } }) }

        runComposeUiTest {
            setContent { KoinContext { BillingIdentityEffect() } }
            waitUntil(timeoutMillis = 5_000) { controller.loggedInIds.isNotEmpty() }
            assertEquals(listOf("user-123"), controller.loggedInIds, "the restored session is bound on resume")
        }
    }

    @Test
    fun unboundSession_composesAsANoOp() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { }) }

        runComposeUiTest {
            setContent { KoinContext { BillingIdentityEffect() } }
            waitForIdle() // no NoDefinitionFound — the effect resolves the session via getOrNull
        }
    }
}
