package id.nearyou.app.infra.revenuecat

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.PurchasesConfiguration

/**
 * Configures the RevenueCat SDK once at app startup. Called from `:mobile:app`'s platform init with the
 * per-flavor **publishable client key** (Android `buildConfigField` / iOS xcconfig — the value of the
 * `staging-revenuecat-test-api-key` slot, PR #319). The app configures anonymously (`appUserId = null`:
 * the token read is async, and configure runs synchronously at startup); `PremiumEntitlementSession`
 * then binds the signed-in `users.id` via [RevenueCatPurchaseController.logIn] so the
 * `subscription-billing-webhook` (#291) resolves each purchase to the right user.
 *
 * Idempotent — a second call is a no-op (the SDK singleton configures once). The caller MUST skip this
 * when the key is still the `REPLACE_WITH_*` placeholder (not provisioned for the flavor): leaving
 * [Purchases] unconfigured is the fail-soft path the [RevenueCatPurchaseController] maps to `Unavailable`.
 *
 * On Android the SDK initializes its context via AndroidX App Startup, so no `Application` context is
 * threaded here. Account switches (logout → login) go through `logIn` / `logOut`, never re-configure.
 */
fun configureRevenueCat(
    apiKey: String,
    appUserId: String?,
) {
    if (Purchases.isConfigured) return
    val builder = PurchasesConfiguration.Builder(apiKey = apiKey)
    if (appUserId != null) builder.appUserId(appUserId)
    Purchases.configure(builder.build())
}
