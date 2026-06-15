package id.nearyou.app.config

/**
 * The RevenueCat **publishable client key** for the current flavor — Android
 * `BuildConfig.REVENUECAT_PUBLIC_KEY` (per-flavor `buildConfigField`, sourced from the
 * `staging-revenuecat-test-api-key` slot via `-PstagingRevenueCatPublicKey` in CI; mirrors
 * `SUPABASE_ANON_KEY`), iOS the `RevenueCatPublicKey` `Info.plist` key (xcconfig per scheme, mirrors
 * `sentryConfig`). A publishable client key ships in the app binary by design — it is NOT a
 * `secretKey()`/GCP-Secret-Manager secret (that is the backend's `revenuecat-webhook-secret`, #291).
 *
 * Blank until provisioned for the flavor → [id.nearyou.app.di.initKoin] skips `configureRevenueCat`,
 * leaving `Purchases` unconfigured and the paywall in its fail-soft `Unconfigured` state.
 */
expect val revenueCatPublicKey: String
