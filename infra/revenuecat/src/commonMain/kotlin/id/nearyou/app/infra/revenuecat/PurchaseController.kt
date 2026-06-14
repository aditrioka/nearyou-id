package id.nearyou.app.infra.revenuecat

/**
 * The billing period of a purchasable subscription package — the three store SKUs the paywall offers.
 * The Daily tier is deliberately absent (dropped for cross-platform parity; Apple lacks a daily
 * auto-renewable duration — `docs/01-Business.md` § Multi-Period Pricing).
 */
enum class PaywallPeriod { WEEKLY, MONTHLY, YEARLY }

/**
 * A purchasable subscription package projected from a RevenueCat Offering package — a plain-Kotlin,
 * vendor-type-FREE model so the RevenueCat SDK never crosses the `:mobile:app` boundary (invariant #16).
 *
 * [localizedPriceString] is the store-localized display price the paywall renders verbatim (e.g.
 * "Rp29.000") — the card price MUST come from here, never a hardcoded rupiah literal. [priceAmountMicros]
 * (price × 1,000,000 in [currencyCode] units) is the raw value the pure price-derivation helper uses to
 * compute the strike-anchor (Monthly vs 4× Weekly, Yearly vs 12× Monthly), the savings %, and the
 * Weekly per-day baseline — all derived, never hardcoded ([PaywallPeriod]-keyed).
 */
data class PaywallPackage(
    val period: PaywallPeriod,
    val localizedPriceString: String,
    val priceAmountMicros: Long,
    val currencyCode: String,
    val productId: String,
)

/**
 * The result of fetching the current offering. [Loaded] carries the available packages (Weekly / Monthly
 * / Yearly, as published). [Unavailable] is the fail-soft state — the RevenueCat SDK is not configured,
 * no offering is published, or the fetch failed (the expected state until the operator provisions the
 * dashboard / store products / publishable key) — and drives the paywall's "Premium belum tersedia"
 * surface. The seam NEVER throws across this boundary; every failure maps to [Unavailable].
 */
sealed interface OfferingsResult {
    data class Loaded(val packages: List<PaywallPackage>) : OfferingsResult

    data object Unavailable : OfferingsResult
}

/**
 * The result of a purchase attempt. [Success] carries the post-purchase entitlement state read from the
 * RevenueCat `CustomerInfo` (the authoritative CLIENT premium signal — the server's `subscription_status`
 * updates independently via the `subscription-billing-webhook`, design D5). [Cancelled] is the user
 * dismissing the store purchase sheet (return to the paywall, no error chrome). [Error] is any
 * non-cancellation failure (store / network / SDK) — surface a retryable error, never a false success.
 */
sealed interface PurchaseResult {
    data class Success(val entitlementActive: Boolean) : PurchaseResult

    data object Cancelled : PurchaseResult

    data class Error(val message: String?) : PurchaseResult
}

/**
 * The vendor-SDK-FREE purchase + entitlement seam for the `mobile-paywall` capability. The production
 * binding (`RevenueCatPurchaseController`, in this module, alongside the `purchases-kmp` SDK) fences the
 * vendor import; `:mobile:app` (the `PaywallViewModel`) consumes ONLY this interface, and commonTest
 * substitutes a `FakePurchaseController`. This mirrors the docs/11 §2.6 realtime-consumer seam
 * (`ChatRealtimeSubscriber` + `SupabaseChatRealtimeSubscriber`): a vendor-free interface in a KMP
 * `:infra:*` module alongside the single vendor implementation.
 *
 * No method throws a vendor exception across the boundary — failures are modelled in the return types.
 */
interface PurchaseController {
    /**
     * Fetch the current offering's Weekly / Monthly / Yearly packages, or [OfferingsResult.Unavailable]
     * when the SDK is unconfigured / no offering is published / the fetch fails.
     */
    suspend fun fetchOfferings(): OfferingsResult

    /** Drive the platform store purchase flow for [pkg]; maps the outcome to a [PurchaseResult]. */
    suspend fun purchase(pkg: PaywallPackage): PurchaseResult

    /**
     * True iff the RevenueCat `CustomerInfo` currently carries an active Premium entitlement — the
     * client-side premium signal used to confirm a purchase and re-evaluate gated state on return.
     */
    suspend fun isPremiumEntitlementActive(): Boolean
}
