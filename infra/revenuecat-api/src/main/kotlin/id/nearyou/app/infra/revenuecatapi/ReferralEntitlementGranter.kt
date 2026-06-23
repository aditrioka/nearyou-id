package id.nearyou.app.infra.revenuecatapi

/**
 * A request to grant (or extend) a promotional Premium entitlement for one user.
 *
 * [endTimeMs] is the ABSOLUTE expiry (epoch millis) the caller has already computed
 * for stacking — `GREATEST(current_entitlement_end, NOW()) + 7 days` (docs/01 §139-140):
 * extend-if-active, fresh-if-free. The RevenueCat v1 promotional endpoint takes this
 * absolute value, so the extend-vs-fresh decision lives in our code, not RevenueCat's
 * near-duplicate heuristic (design D3; verified 2026-06-20: v2 cannot stack reliably).
 *
 * [dedupKey] is our-side idempotency only (the `granted_entitlements.dedup_key` UNIQUE
 * column) — the v1 endpoint has no dedup parameter; it is carried here for logging and
 * the future reconciliation follow-up, not sent to RevenueCat.
 */
data class GrantRequest(
    val appUserId: String,
    val entitlementId: String,
    val endTimeMs: Long,
    val dedupKey: String,
)

/** Outcome of a grant dispatch. The worker never fails a ticket on a grant outcome. */
sealed interface GrantResult {
    /** RevenueCat accepted the promotional grant. */
    data object Dispatched : GrantResult

    /** No API key configured — fail-soft; the ledger row is still written. */
    data object NotConfigured : GrantResult

    /** RevenueCat rejected or the call errored; logged, reconciled by a follow-up. */
    data class Failed(val reason: String) : GrantResult
}

/**
 * Outbound RevenueCat promotional-entitlement port. The single vendor implementation
 * ([RevenueCatPromotionalGranter]) lives in this module; `:backend:ktor` depends only on
 * this interface and never imports an HTTP-client or RevenueCat symbol (the
 * [ImageModerator]/`:infra:cloud-vision` precedent + the `VendorSdkLeakageScan` rule).
 *
 * [isConfigured] reports whether the RevenueCat secret API key is present — an
 * unconfigured environment fails soft (the worker still writes the ledger + flips the
 * ticket, logs the un-dispatched grant, and never throws).
 */
interface ReferralEntitlementGranter {
    fun isConfigured(): Boolean

    /** Grants (or extends to [GrantRequest.endTimeMs]) the promotional entitlement. */
    suspend fun grant(request: GrantRequest): GrantResult
}

/** Fail-soft binding when no RevenueCat secret API key is configured. */
object NoOpReferralEntitlementGranter : ReferralEntitlementGranter {
    override fun isConfigured(): Boolean = false

    override suspend fun grant(request: GrantRequest): GrantResult = GrantResult.NotConfigured
}

/**
 * Factory. Returns the real RevenueCat-backed granter when [secretApiKey] is a non-blank
 * credential, else the fail-soft [NoOpReferralEntitlementGranter]. Returns the public
 * [ReferralEntitlementGranter] interface so `:backend:ktor` imports no vendor / Ktor-client
 * symbol.
 */
fun referralEntitlementGranter(secretApiKey: String?): ReferralEntitlementGranter =
    if (secretApiKey.isNullOrBlank()) {
        NoOpReferralEntitlementGranter
    } else {
        RevenueCatPromotionalGranter(secretApiKey)
    }
