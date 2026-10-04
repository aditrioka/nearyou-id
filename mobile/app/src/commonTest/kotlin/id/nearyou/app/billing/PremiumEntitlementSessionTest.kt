package id.nearyou.app.billing

import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.screens.paywall.FakePurchaseController
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A signed-in id the test can switch (sign-out = null, account switch = another id). */
class MutableSelfUserId(var id: String?) : SelfUserIdProvider {
    override suspend fun selfUserId(): String? = id
}

/**
 * A session whose purchase is already confirmed while the server tier still reads Free — the webhook-lag
 * window every upsell surface swaps to the activating notice for (#517). Screen tests bind it in Koin.
 */
fun confirmedPremiumSession(): PremiumEntitlementSession =
    PremiumEntitlementSession(MutableSelfUserId("self"), FakePurchaseController()).apply { onPurchaseConfirmed() }

/** `mobile-premium-entitlement` — identity binding + the account-scoped confirmed-purchase signal. */
class PremiumEntitlementSessionTest {
    @Test
    fun aSignedInSessionLogsRevenueCatInAsTheUsersId() =
        runTest {
            val controller = FakePurchaseController()
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), controller)

            assertTrue(session.syncIdentity())
            assertEquals(listOf("u-1"), controller.loggedInIds)
            assertEquals(0, controller.logOutCount)
        }

    @Test
    fun noSessionLogsRevenueCatOutToAnonymous() =
        runTest {
            val controller = FakePurchaseController()
            val session = PremiumEntitlementSession(MutableSelfUserId(null), controller)

            assertFalse(session.syncIdentity())
            assertEquals(1, controller.logOutCount)
            assertTrue(controller.loggedInIds.isEmpty())
        }

    @Test
    fun aFailedLogInReportsNotIdentifiedWithoutThrowing() =
        runTest {
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), FakePurchaseController(logInResult = false))

            assertFalse(session.syncIdentity())
        }

    @Test
    fun aThrowingVendorCallNeverEscapes() =
        runTest {
            // The token-refresh failure path + the logout wipe call this — it must hold "never throws" itself.
            val controller = FakePurchaseController().apply { identityFailure = IllegalStateException("sdk boom") }

            assertFalse(PremiumEntitlementSession(MutableSelfUserId("u-1"), controller).syncIdentity())
            assertFalse(PremiumEntitlementSession(MutableSelfUserId(null), controller).syncIdentity())
        }

    @Test
    fun aConfirmedPurchaseSetsTheSignal() =
        runTest {
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), FakePurchaseController())
            session.syncIdentity()

            assertFalse(session.purchaseConfirmed.value)
            session.onPurchaseConfirmed()
            assertTrue(session.purchaseConfirmed.value)
        }

    @Test
    fun theSignalSurvivesASameAccountResync() =
        runTest {
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), FakePurchaseController())
            session.syncIdentity()
            session.onPurchaseConfirmed()

            session.syncIdentity() // e.g. a foreground resume

            assertTrue(session.purchaseConfirmed.value)
        }

    @Test
    fun signOutResetsTheSignalSoTheNextAccountDoesNotInheritIt() =
        runTest {
            val self = MutableSelfUserId("u-1")
            val session = PremiumEntitlementSession(self, FakePurchaseController())
            session.syncIdentity()
            session.onPurchaseConfirmed()

            self.id = null
            session.syncIdentity()
            assertFalse(session.purchaseConfirmed.value, "sign-out must reset the confirmed signal")

            self.id = "u-2"
            session.syncIdentity()
            assertFalse(session.purchaseConfirmed.value, "the next account must not inherit it")
        }

    @Test
    fun aDirectAccountSwitchAlsoResetsTheSignal() =
        runTest {
            val self = MutableSelfUserId("u-1")
            val session = PremiumEntitlementSession(self, FakePurchaseController())
            session.syncIdentity()
            session.onPurchaseConfirmed()

            self.id = "u-2" // tokens replaced without an intervening sync (e.g. the staging test-login)
            session.syncIdentity()

            assertFalse(session.purchaseConfirmed.value)
        }
}
