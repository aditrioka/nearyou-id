package id.nearyou.app.screens.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.nearyou.app.billing.PremiumEntitlementSession
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
 * pre-provisioning / fetch-failure state). [onSubscribe] binds the RevenueCat identity, drives the purchase,
 * and confirms the entitlement: the client-side entitlement (RevenueCat `CustomerInfo`) is authoritative for
 * the one-shot [PaywallUiState.Content.purchaseSucceeded] return signal (an unconfirmed / payment-pending
 * purchase is [PaywallUiState.Content.purchasePending] instead), while the server's `subscription_status`
 * syncs independently via the webhook (#291, design D5). The one-shot success signal is consumed via
 * [onReturnConsumed]; the
 * [PaywallUiState.Content.purchaseError] flag is sticky (cleared on the next [onSubscribe] retry). Both are
 * state on the [PaywallUiState.Content], not event streams (docs/11 §2.2).
 */
class PaywallViewModel(
    private val purchaseController: PurchaseController,
    // premium-entitlement-lifecycle: syncs the RevenueCat identity before a purchase + publishes the
    // confirmed-purchase signal. Null (tests / DI gap) skips both; the controller still refuses anonymous buys.
    private val premiumEntitlement: PremiumEntitlementSession? = null,
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
        if (content.purchasePending) {
            recheckPendingEntitlement(content)
            return
        }
        val pkg = loadedPackages.firstOrNull { it.period == content.selectedPeriod } ?: return
        mutableState.value = content.copy(purchaseInProgress = true, purchaseError = false)
        viewModelScope.launch {
            val result = confirmedPurchase(pkg)
            val current = mutableState.value as? PaywallUiState.Content ?: return@launch
            mutableState.value =
                when (result) {
                    is PurchaseResult.Success -> confirm(current)
                    PurchaseResult.Pending -> current.copy(purchaseInProgress = false, purchasePending = true)
                    PurchaseResult.Cancelled -> current.copy(purchaseInProgress = false)
                    is PurchaseResult.Error -> current.copy(purchaseInProgress = false, purchaseError = true)
                }
        }
    }

    /**
     * Bind the RevenueCat identity, purchase, then confirm the entitlement: a [PurchaseResult.Success] is
     * returned only for an ACTIVE entitlement — an inactive one gets one recheck (the purchase result's
     * `CustomerInfo` can trail the grant) and otherwise becomes [PurchaseResult.Pending], never a success claim.
     */
    private suspend fun confirmedPurchase(pkg: PaywallPackage): PurchaseResult {
        // An anonymous purchase maps to no users.id server-side — never take it (mobile-premium-entitlement).
        if (premiumEntitlement?.syncIdentity() == false) return PurchaseResult.Error(message = "identity_unavailable")
        return when (val result = purchaseController.purchase(pkg)) {
            is PurchaseResult.Success ->
                if (result.entitlementActive || purchaseController.isPremiumEntitlementActive()) {
                    PurchaseResult.Success(entitlementActive = true)
                } else {
                    PurchaseResult.Pending
                }
            else -> result
        }
    }

    /** Pending: the CTA rechecks the entitlement — re-purchasing would hit the store's already-owned error. */
    private fun recheckPendingEntitlement(content: PaywallUiState.Content) {
        mutableState.value = content.copy(purchaseInProgress = true)
        viewModelScope.launch {
            val active = purchaseController.isPremiumEntitlementActive()
            val current = mutableState.value as? PaywallUiState.Content ?: return@launch
            mutableState.value = if (active) confirm(current) else current.copy(purchaseInProgress = false)
        }
    }

    /** A confirmed entitlement: publish the account-scoped signal the gated surfaces re-evaluate from, then return. */
    private fun confirm(content: PaywallUiState.Content): PaywallUiState.Content {
        premiumEntitlement?.onPurchaseConfirmed()
        return content.copy(purchaseInProgress = false, purchasePending = false, purchaseSucceeded = true)
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
