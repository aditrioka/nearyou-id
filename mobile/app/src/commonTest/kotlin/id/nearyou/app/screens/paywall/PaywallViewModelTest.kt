package id.nearyou.app.screens.paywall

import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import id.nearyou.app.infra.revenuecat.PurchaseResult
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
