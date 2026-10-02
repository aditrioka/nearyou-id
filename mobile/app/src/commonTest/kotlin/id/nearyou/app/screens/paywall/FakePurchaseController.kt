package id.nearyou.app.screens.paywall

import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.infra.revenuecat.PurchaseResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Test double for the vendor-free [PurchaseController] seam — drives the `PaywallViewModel` / screen tests
 * without the RevenueCat SDK (mirrors `FakeSearchFlow`). Defaults to a loaded offering that purchases
 * successfully; mutators flip the offering / purchase outcome per scenario, and [purchaseInvocations]
 * records how many times [purchase] was driven. The identity pair records [loggedInIds] / [logOutCount]
 * and answers `logIn` with [logInResult] (premium-entitlement-lifecycle).
 */
class FakePurchaseController(
    private var offerings: OfferingsResult = OfferingsResult.Unavailable,
    private var purchaseResult: PurchaseResult = PurchaseResult.Success(entitlementActive = true),
    var premiumActive: Boolean = false,
    var logInResult: Boolean = true,
) : PurchaseController {
    var purchaseInvocations: Int = 0
        private set
    var entitlementChecks: Int = 0
        private set
    val loggedInIds = mutableListOf<String>()
    var logOutCount: Int = 0
        private set

    /** When set, [purchase] suspends until it completes — lets a test observe the in-flight state. */
    var purchaseGate: CompletableDeferred<Unit>? = null

    /** When set, the identity calls throw it — proves callers never let a vendor hiccup escape. */
    var identityFailure: Throwable? = null

    /** When set, [logIn] suspends until it completes — models a hanging vendor call. */
    var logInGate: CompletableDeferred<Unit>? = null

    override suspend fun fetchOfferings(): OfferingsResult = offerings

    override suspend fun purchase(pkg: PaywallPackage): PurchaseResult {
        purchaseInvocations++
        purchaseGate?.await()
        return purchaseResult
    }

    override suspend fun isPremiumEntitlementActive(): Boolean {
        entitlementChecks++
        return premiumActive
    }

    override suspend fun logIn(appUserId: String): Boolean {
        identityFailure?.let { throw it }
        loggedInIds += appUserId
        logInGate?.await()
        return logInResult
    }

    override suspend fun logOut() {
        identityFailure?.let { throw it }
        logOutCount++
    }

    fun setOfferings(value: OfferingsResult) {
        offerings = value
    }

    fun setPurchaseResult(value: PurchaseResult) {
        purchaseResult = value
    }
}
