package id.nearyou.app.subscription

/**
 * The single premium-tier predicate: the subscription states that grant Premium entitlements.
 * `premium_billing_retry` retains access through the billing-retry grace window
 * (`docs/02-Product.md` § Premium; V13 subscription CHECK).
 *
 * Previously copied per-service (13 identical `setOf` copies + one `isPremiumStatus` fn —
 * follow-up #386); a new premium state (e.g. a trial tier) is added HERE only. The tier is
 * always read from the auth principal, never a `users` SELECT (timeline-read-rate-limit
 * invariant).
 */
val PREMIUM_STATES: Set<String> = setOf("premium_active", "premium_billing_retry")

/** True when [subscriptionStatus] is a Premium-entitled state; null (no principal field) is Free. */
fun isPremiumStatus(subscriptionStatus: String?): Boolean = subscriptionStatus in PREMIUM_STATES
