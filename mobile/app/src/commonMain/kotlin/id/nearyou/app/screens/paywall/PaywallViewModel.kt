package id.nearyou.app.screens.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PaywallPeriod
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.infra.revenuecat.PurchaseResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The `PaywallRoute`-scoped ViewModel (androidx `ViewModel` in commonMain, resolved via `koinViewModel()`
 * under the root `NavDisplay`'s entry decorator — the pushed-route precedent). Exposes ONE
 * [StateFlow] of the mutually-exclusive [PaywallUiState]; the data layer is the vendor-free
 * [PurchaseController] seam (production `RevenueCatPurchaseController`; a `FakePurchaseController` in tests).
 *
 * On construction it loads the current offering's packages → [PaywallUiState.Content] (price cards from
 * [derivePaywallPricing], Monthly pre-selected) or [PaywallUiState.Unconfigured] (fail-soft, the
 * pre-provisioning / fetch-failure state). [onSubscribe] drives the purchase; the client-side entitlement
 * (RevenueCat `CustomerInfo`) is authoritative for the one-shot [PaywallUiState.Content.purchaseSucceeded]
 * return signal, while the server's `subscription_status` syncs independently via the webhook (#291,
 * design D5). The one-shot success signal is consumed via [onReturnConsumed]; the
 * [PaywallUiState.Content.purchaseError] flag is sticky (cleared on the next [onSubscribe] retry). Both are
 * state on the [PaywallUiState.Content], not event streams (docs/11 §2.2).
 */
class PaywallViewModel(
    private val purchaseController: PurchaseController,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PaywallUiState>(PaywallUiState.Loading)
    val state: StateFlow<PaywallUiState> = mutableState.asStateFlow()

    private var loadedPackages: List<PaywallPackage> = emptyList()

    init {
        loadOfferings()
    }

    /** (Re)load the current offering. Retryable from the Unconfigured surface. */
    fun loadOfferings() {
        mutableState.value = PaywallUiState.Loading
        viewModelScope.launch {
            when (val result = purchaseController.fetchOfferings()) {
                is OfferingsResult.Loaded -> {
                    // A Loaded result with no packages is treated as Unconfigured (defensive: the production
                    // controller maps empty → Unavailable, but this keeps the VM total — no empty-list crash).
                    if (result.packages.isEmpty()) {
                        loadedPackages = emptyList()
                        mutableState.value = PaywallUiState.Unconfigured
                    } else {
                        loadedPackages = result.packages
                        mutableState.value =
                            PaywallUiState.Content(
                                cards = derivePaywallPricing(result.packages),
                                selectedPeriod = defaultSelectedPeriod(result.packages),
                            )
                    }
                }

                OfferingsResult.Unavailable -> {
                    loadedPackages = emptyList()
                    mutableState.value = PaywallUiState.Unconfigured
                }
            }
        }
    }

    fun onSelectPeriod(period: PaywallPeriod) {
        val content = mutableState.value as? PaywallUiState.Content ?: return
        if (content.cards.none { it.period == period }) return
        mutableState.value = content.copy(selectedPeriod = period)
    }

    fun onSubscribe() {
        val content = mutableState.value as? PaywallUiState.Content ?: return
        if (content.purchaseInProgress) return
        val pkg = loadedPackages.firstOrNull { it.period == content.selectedPeriod } ?: return
        mutableState.value = content.copy(purchaseInProgress = true, purchaseError = false)
        viewModelScope.launch {
            val result = purchaseController.purchase(pkg)
            val current = mutableState.value as? PaywallUiState.Content ?: return@launch
            mutableState.value =
                when (result) {
                    is PurchaseResult.Success -> current.copy(purchaseInProgress = false, purchaseSucceeded = true)
                    PurchaseResult.Cancelled -> current.copy(purchaseInProgress = false)
                    is PurchaseResult.Error -> current.copy(purchaseInProgress = false, purchaseError = true)
                }
        }
    }

    /** Consume the one-shot success signal once the host has popped the paywall (no re-trigger on recompose). */
    fun onReturnConsumed() {
        val content = mutableState.value as? PaywallUiState.Content ?: return
        if (content.purchaseSucceeded) mutableState.value = content.copy(purchaseSucceeded = false)
    }

    private fun defaultSelectedPeriod(packages: List<PaywallPackage>): PaywallPeriod =
        if (packages.any { it.period == DEFAULT_SELECTED_PERIOD }) {
            DEFAULT_SELECTED_PERIOD
        } else {
            packages.first().period
        }
}
