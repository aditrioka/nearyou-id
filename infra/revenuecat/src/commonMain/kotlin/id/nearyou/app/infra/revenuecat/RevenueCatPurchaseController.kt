package id.nearyou.app.infra.revenuecat

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.ktx.awaitCustomerInfo
import com.revenuecat.purchases.kmp.ktx.awaitLogIn
import com.revenuecat.purchases.kmp.ktx.awaitLogOut
import com.revenuecat.purchases.kmp.ktx.awaitOfferings
import com.revenuecat.purchases.kmp.ktx.awaitPurchase
import com.revenuecat.purchases.kmp.models.CacheFetchPolicy
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.Offering
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.PurchasesErrorCode
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
        // Fail closed: an anonymous purchase reaches the webhook under `$RCAnonymousID`, which maps to no
        // users.id — money the server can never attribute. The paywall logs in first; this is the backstop.
        if (Purchases.sharedInstance.isAnonymous) return PurchaseResult.Error(message = "identity_unavailable")
        val rcPackage =
            findCurrentPackage(pkg.period) ?: return PurchaseResult.Error(message = "package_unavailable")
        return try {
            val success = Purchases.sharedInstance.awaitPurchase(packageToPurchase = rcPackage)
            PurchaseResult.Success(entitlementActive = success.customerInfo.hasActiveEntitlement())
        } catch (e: PurchasesTransactionException) {
            // CancellationException (coroutine cancellation) is not a PurchasesException, so it propagates
            // uncaught — correct.
            transactionFailureResult(e.userCancelled, e.code, e.message)
        } catch (e: PurchasesException) {
            PurchaseResult.Error(message = e.message)
        }
    }

    override suspend fun logIn(appUserId: String): Boolean {
        if (!Purchases.isConfigured) return false
        val purchases = Purchases.sharedInstance
        if (!purchases.isAnonymous && purchases.appUserID == appUserId) return true
        return try {
            purchases.awaitLogIn(appUserId)
            true
        } catch (e: PurchasesException) {
            false
        }
    }

    override suspend fun logOut() {
        // logOut while anonymous is a RevenueCat error (LogOutWithAnonymousUserError) — skip it.
        if (!Purchases.isConfigured || Purchases.sharedInstance.isAnonymous) return
        try {
            Purchases.sharedInstance.awaitLogOut()
        } catch (e: PurchasesException) {
            // Best-effort: the next syncIdentity (resume / sign-in) retries.
        }
    }

    override suspend fun isPremiumEntitlementActive(): Boolean {
        if (!Purchases.isConfigured) return false
        return try {
            // FETCH_CURRENT, not the default CACHED_OR_FETCHED: callers are the post-purchase / pending
            // rechecks, which exist precisely because the cached CustomerInfo can trail the entitlement grant.
            Purchases.sharedInstance.awaitCustomerInfo(CacheFetchPolicy.FETCH_CURRENT).hasActiveEntitlement()
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

/**
 * Maps a store purchase failure to the vendor-free [PurchaseResult]: a user cancellation is NOT an error
 * (back to the paywall), a payment-pending purchase (Play cash / convenience-store / carrier billing awaiting
 * settlement) is [PurchaseResult.Pending], anything else is a retryable [PurchaseResult.Error]. Pure, so the
 * mapping is unit-testable without the provisioned SDK.
 */
internal fun transactionFailureResult(
    userCancelled: Boolean,
    code: PurchasesErrorCode,
    message: String?,
): PurchaseResult =
    when {
        userCancelled -> PurchaseResult.Cancelled
        code == PurchasesErrorCode.PaymentPendingError -> PurchaseResult.Pending
        else -> PurchaseResult.Error(message = message)
    }
