package id.nearyou.app.infra.revenuecat

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.ktx.awaitCustomerInfo
import com.revenuecat.purchases.kmp.ktx.awaitOfferings
import com.revenuecat.purchases.kmp.ktx.awaitPurchase
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.Offering
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.PurchasesException
import com.revenuecat.purchases.kmp.models.PurchasesTransactionException

/**
 * The production [PurchaseController] — the ONLY code that touches the RevenueCat `purchases-kmp` SDK
 * (invariant #16; the dependency is `implementation`-scoped in this module so it never reaches
 * `:mobile:app`'s compile classpath). Assumes [Purchases] has been configured at app startup
 * ([configureRevenueCat]); if it has NOT (no publishable key wired yet — the pre-provisioning state),
 * every call fail-softs to the Unavailable / inactive result so the paywall renders its "Premium belum
 * tersedia" surface rather than crashing.
 *
 * [entitlementId] is the RevenueCat dashboard entitlement the client checks (staging value `premium`,
 * provisioned in PR #319). The offering consumed is the dashboard's CURRENT offering
 * ([com.revenuecat.purchases.kmp.models.Offerings.current]); the Weekly / Monthly / Yearly packages are
 * read via the predefined-type accessors. No vendor type crosses the public surface — the controller
 * maps everything to the plain-Kotlin [OfferingsResult] / [PurchaseResult] / [PaywallPackage] models.
 */
class RevenueCatPurchaseController(
    private val entitlementId: String = DEFAULT_ENTITLEMENT_ID,
) : PurchaseController {
    override suspend fun fetchOfferings(): OfferingsResult {
        if (!Purchases.isConfigured) return OfferingsResult.Unavailable
        return try {
            val current =
                Purchases.sharedInstance.awaitOfferings().current
                    ?: return OfferingsResult.Unavailable
            val packages = current.toPaywallPackages()
            if (packages.isEmpty()) OfferingsResult.Unavailable else OfferingsResult.Loaded(packages)
        } catch (e: PurchasesException) {
            // Network / SDK / no-offering error → fail-soft (never throws across the seam).
            OfferingsResult.Unavailable
        }
    }

    override suspend fun purchase(pkg: PaywallPackage): PurchaseResult {
        if (!Purchases.isConfigured) return PurchaseResult.Error(message = "billing_unavailable")
        val rcPackage =
            findCurrentPackage(pkg.period) ?: return PurchaseResult.Error(message = "package_unavailable")
        return try {
            val success = Purchases.sharedInstance.awaitPurchase(packageToPurchase = rcPackage)
            PurchaseResult.Success(entitlementActive = success.customerInfo.hasActiveEntitlement())
        } catch (e: PurchasesTransactionException) {
            // PurchasesTransactionException carries the userCancelled flag; a true cancellation is NOT
            // an error. CancellationException (coroutine cancellation) is not a PurchasesException, so it
            // propagates uncaught — correct.
            if (e.userCancelled) PurchaseResult.Cancelled else PurchaseResult.Error(message = e.message)
        } catch (e: PurchasesException) {
            PurchaseResult.Error(message = e.message)
        }
    }

    override suspend fun isPremiumEntitlementActive(): Boolean {
        if (!Purchases.isConfigured) return false
        return try {
            Purchases.sharedInstance.awaitCustomerInfo().hasActiveEntitlement()
        } catch (e: PurchasesException) {
            false
        }
    }

    private suspend fun findCurrentPackage(period: PaywallPeriod): Package? {
        val current =
            try {
                Purchases.sharedInstance.awaitOfferings().current
            } catch (e: PurchasesException) {
                null
            } ?: return null
        return current.packageFor(period)
    }

    private fun Offering.toPaywallPackages(): List<PaywallPackage> =
        listOfNotNull(
            weekly?.toPaywallPackage(PaywallPeriod.WEEKLY),
            monthly?.toPaywallPackage(PaywallPeriod.MONTHLY),
            annual?.toPaywallPackage(PaywallPeriod.YEARLY),
        )

    private fun Offering.packageFor(period: PaywallPeriod): Package? =
        when (period) {
            PaywallPeriod.WEEKLY -> weekly
            PaywallPeriod.MONTHLY -> monthly
            PaywallPeriod.YEARLY -> annual
        }

    private fun Package.toPaywallPackage(period: PaywallPeriod): PaywallPackage =
        PaywallPackage(
            period = period,
            localizedPriceString = storeProduct.price.formatted,
            priceAmountMicros = storeProduct.price.amountMicros,
            currencyCode = storeProduct.price.currencyCode,
            productId = storeProduct.id,
        )

    private fun CustomerInfo.hasActiveEntitlement(): Boolean = entitlements.active.containsKey(entitlementId)

    companion object {
        /** The RevenueCat dashboard entitlement identifier the client checks (staging `premium`, PR #319). */
        const val DEFAULT_ENTITLEMENT_ID: String = "premium"
    }
}
