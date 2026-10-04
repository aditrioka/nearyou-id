package id.nearyou.app.ui.billing

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import id.nearyou.app.billing.MutableSelfUserId
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

/**
 * `rememberPremiumActivating()` (`mobile-premium-entitlement`, #517) follows the bound session's
 * `purchaseConfirmed`: it flips `true` on a confirmed purchase and recomposes, returns to `false` when a
 * sign-out sync resets the signal, and reads `false` when no session is bound.
 *
 * `@Suppress("DEPRECATION")` + `KoinContext`: the multi-test startKoin cycle the screen tests use.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalTestApi::class)
class RememberPremiumActivatingTest {
    @AfterTest
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    private fun startWith(session: PremiumEntitlementSession?) {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        startKoin { modules(module { if (session != null) single { session } }) }
    }

    @Test
    fun followsTheConfirmedSignal_andTheSignOutReset() {
        val selfId = MutableSelfUserId("u-1")
        val session = PremiumEntitlementSession(selfId, FakePurchaseController())
        // Bind the account first: syncIdentity only resets the signal when the bound id changes.
        runBlocking { session.syncIdentity() }
        startWith(session)
        runComposeUiTest {
            setContent { KoinContext { Text(if (rememberPremiumActivating()) "ON" else "OFF") } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("OFF").fetchSemanticsNodes().isNotEmpty() }

            session.onPurchaseConfirmed()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("ON").fetchSemanticsNodes().isNotEmpty() }

            selfId.id = null
            runBlocking { session.syncIdentity() }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("OFF").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun anUnboundSessionReadsFalse() {
        startWith(null)
        runComposeUiTest {
            setContent { KoinContext { Text(if (rememberPremiumActivating()) "ON" else "OFF") } }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("OFF").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
