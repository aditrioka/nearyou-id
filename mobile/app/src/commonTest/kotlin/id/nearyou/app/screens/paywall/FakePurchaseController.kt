package id.nearyou.app.screens.paywall

import id.nearyou.app.infra.revenuecat.OfferingsResult
import id.nearyou.app.infra.revenuecat.PaywallPackage
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.infra.revenuecat.PurchaseResult

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
    val loggedInIds = mutableListOf<String>()
    var logOutCount: Int = 0
        private set

    override suspend fun fetchOfferings(): OfferingsResult = offerings

    override suspend fun purchase(pkg: PaywallPackage): PurchaseResult {
        purchaseInvocations++
        return purchaseResult
    }

    override suspend fun isPremiumEntitlementActive(): Boolean = premiumActive

    override suspend fun logIn(appUserId: String): Boolean {
        loggedInIds += appUserId
        return logInResult
    }

    override suspend fun logOut() {
        logOutCount++
    }

    fun setOfferings(value: OfferingsResult) {
        offerings = value
    }

    fun setPurchaseResult(value: PurchaseResult) {
        purchaseResult = value
    }
}
