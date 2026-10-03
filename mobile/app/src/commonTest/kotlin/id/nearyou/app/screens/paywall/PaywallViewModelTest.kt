package id.nearyou.app.screens.paywall

import id.nearyou.app.billing.MutableSelfUserId
import id.nearyou.app.billing.PremiumEntitlementSession
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import id.nearyou.app.infra.revenuecat.PurchaseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives [PaywallViewModel] over a [FakePurchaseController]. `viewModelScope` dispatches on
 * `Dispatchers.Main`; an [UnconfinedTestDispatcher] over an explicit scheduler runs the init load +
 * purchase coroutines eagerly so the resulting [PaywallUiState] is observable synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsOfferingsIntoContentWithMonthlyPreselected() =
        runTest {
            val vm = PaywallViewModel(FakePurchaseController(OfferingsResult.Loaded(packages())))
            val state = vm.state.value
            assertIs<PaywallUiState.Content>(state)
            assertEquals(3, state.cards.size)
            assertEquals(PaywallPeriod.MONTHLY, state.selectedPeriod)
        }

    @Test
    fun unavailableOfferingsMapToUnconfigured() =
        runTest {
            val vm = PaywallViewModel(FakePurchaseController(OfferingsResult.Unavailable))
            assertIs<PaywallUiState.Unconfigured>(vm.state.value)
        }

    @Test
    fun successfulPurchaseSignalsReturnExactlyOnce() =
        runTest {
            val fake = FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Success(true))
            val vm = PaywallViewModel(fake)
            vm.onSubscribe()
            val state = assertIs<PaywallUiState.Content>(vm.state.value)
            assertEquals(1, fake.purchaseInvocations)
            assertTrue(state.purchaseSucceeded)
            assertFalse(state.purchaseInProgress)
            vm.onReturnConsumed()
            assertFalse(assertIs<PaywallUiState.Content>(vm.state.value).purchaseSucceeded)
        }

    @Test
    fun cancelledPurchaseReturnsToContentWithoutErrorOrSuccess() =
        runTest {
            val vm =
                PaywallViewModel(
                    FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Cancelled),
                )
            vm.onSubscribe()
            val state = assertIs<PaywallUiState.Content>(vm.state.value)
            assertFalse(state.purchaseSucceeded)
            assertFalse(state.purchaseError)
            assertFalse(state.purchaseInProgress)
        }

    @Test
    fun purchaseErrorSurfacesRetryableErrorNotSuccess() =
        runTest {
            val vm =
                PaywallViewModel(
                    FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Error("boom")),
                )
            vm.onSubscribe()
            val state = assertIs<PaywallUiState.Content>(vm.state.value)
            assertTrue(state.purchaseError)
            assertFalse(state.purchaseSucceeded)
        }

    // ----- premium-entitlement-lifecycle: identity + entitlement confirmation -----

    @Test
    fun confirmedPurchasePublishesTheSignalAfterBindingTheIdentity() =
        runTest {
            val fake = FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Success(true))
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), fake)
            val vm = PaywallViewModel(fake, session)

            vm.onSubscribe()

            assertTrue(assertIs<PaywallUiState.Content>(vm.state.value).purchaseSucceeded)
            assertTrue(session.purchaseConfirmed.value)
            assertEquals(listOf("u-1"), fake.loggedInIds, "the identity is bound before the purchase")
        }

    @Test
    fun inactiveEntitlementThatConfirmsOnRecheckStillSucceeds() =
        runTest {
            val fake =
                FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Success(false), premiumActive = true)
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), fake)
            val vm = PaywallViewModel(fake, session)

            vm.onSubscribe()

            val state = assertIs<PaywallUiState.Content>(vm.state.value)
            assertTrue(state.purchaseSucceeded)
            assertFalse(state.purchasePending)
            assertTrue(session.purchaseConfirmed.value)
            assertEquals(1, fake.entitlementChecks, "exactly one recheck")
        }

    @Test
    fun activeEntitlementSkipsTheRecheck() =
        runTest {
            val fake = FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Success(true))
            PaywallViewModel(fake).onSubscribe()
            assertEquals(0, fake.entitlementChecks)
        }

    @Test
    fun purchasePassesThroughInProgressAndIgnoresADoubleTap() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val fake =
                FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Success(true)).apply { purchaseGate = gate }
            val vm = PaywallViewModel(fake)

            vm.onSubscribe()
            assertTrue(assertIs<PaywallUiState.Content>(vm.state.value).purchaseInProgress, "in-progress while the store sheet is up")
            vm.onSubscribe() // double tap
            gate.complete(Unit)

            assertEquals(1, fake.purchaseInvocations, "no double submit")
            assertTrue(assertIs<PaywallUiState.Content>(vm.state.value).purchaseSucceeded)
        }

    @Test
    fun inactiveEntitlementThatStaysInactiveIsPendingNotSuccess() =
        runTest {
            val fake =
                FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Success(false), premiumActive = false)
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), fake)
            val vm = PaywallViewModel(fake, session)

            vm.onSubscribe()

            val state = assertIs<PaywallUiState.Content>(vm.state.value)
            assertTrue(state.purchasePending)
            assertFalse(state.purchaseSucceeded)
            assertFalse(state.purchaseError)
            assertFalse(session.purchaseConfirmed.value)
        }

    @Test
    fun paymentPendingPurchaseIsPendingWithoutError() =
        runTest {
            val fake = FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Pending)
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), fake)
            val vm = PaywallViewModel(fake, session)

            vm.onSubscribe()

            val state = assertIs<PaywallUiState.Content>(vm.state.value)
            assertTrue(state.purchasePending)
            assertFalse(state.purchaseError)
            assertFalse(state.purchaseSucceeded)
            assertFalse(state.purchaseInProgress)
            assertFalse(session.purchaseConfirmed.value, "pending never publishes the confirmed signal")
        }

    @Test
    fun ctaRechecksWhilePendingInsteadOfRepurchasing() =
        runTest {
            val fake = FakePurchaseController(OfferingsResult.Loaded(packages()), PurchaseResult.Pending)
            val session = PremiumEntitlementSession(MutableSelfUserId("u-1"), fake)
            val vm = PaywallViewModel(fake, session)
            vm.onSubscribe()
            assertTrue(assertIs<PaywallUiState.Content>(vm.state.value).purchasePending)

            // Still unsettled: the recheck keeps it pending (no second purchase).
            vm.onSubscribe()
            assertTrue(assertIs<PaywallUiState.Content>(vm.state.value).purchasePending)

            // The payment settles → the next recheck confirms.
            fake.premiumActive = true
            vm.onSubscribe()

            val state = assertIs<PaywallUiState.Content>(vm.state.value)
            assertEquals(1, fake.purchaseInvocations, "pending rechecks the entitlement — never re-purchases")
            assertTrue(state.purchaseSucceeded)
            assertFalse(state.purchasePending)
            assertTrue(session.purchaseConfirmed.value)
        }

    @Test
    fun failedIdentitySyncBlocksThePurchaseWithARetryableError() =
        runTest {
            val fake = FakePurchaseController(OfferingsResult.Loaded(packages()), logInResult = false)
            val vm = PaywallViewModel(fake, PremiumEntitlementSession(MutableSelfUserId("u-1"), fake))

            vm.onSubscribe()

            val state = assertIs<PaywallUiState.Content>(vm.state.value)
            assertEquals(0, fake.purchaseInvocations, "an unattributable (anonymous) purchase is never attempted")
            assertTrue(state.purchaseError)
            assertFalse(state.purchaseSucceeded)
        }

    @Test
    fun selectPeriodUpdatesSelection() =
        runTest {
            val vm = PaywallViewModel(FakePurchaseController(OfferingsResult.Loaded(packages())))
            vm.onSelectPeriod(PaywallPeriod.YEARLY)
            assertEquals(PaywallPeriod.YEARLY, assertIs<PaywallUiState.Content>(vm.state.value).selectedPeriod)
        }

    private fun packages(): List<PaywallPackage> =
        listOf(
            pkg(PaywallPeriod.WEEKLY, 9_900_000_000L),
            pkg(PaywallPeriod.MONTHLY, 29_000_000_000L),
            pkg(PaywallPeriod.YEARLY, 249_000_000_000L),
        )

    private fun pkg(
        period: PaywallPeriod,
        micros: Long,
    ): PaywallPackage =
        PaywallPackage(
            period = period,
            localizedPriceString = "Rp",
            priceAmountMicros = micros,
            currencyCode = "IDR",
            productId = "p_${period.name}",
        )
}
