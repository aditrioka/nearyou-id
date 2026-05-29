package id.nearyou.app.config

/**
 * Environment-aware API base URL.
 *
 *  - androidMain: `BuildConfig.API_BASE_URL`, injected per gradle product flavor
 *    (`dev` ⇒ `http://10.0.2.2:8080`, `staging` ⇒ `https://api-staging.nearyou.id`,
 *    `production` ⇒ a deliberately-broken `…PLACEHOLDER` until prod infra is provisioned).
 *  - iosMain: `NSBundle.mainBundle.objectForInfoDictionaryKey("ApiBaseUrl")`, driven by an
 *    xcconfig variable per scheme (§7).
 *
 * This is the ONLY commonMain surface permitted to resolve an API hostname — the
 * `mobile-app-scaffold` carve-out allows hardcoded URLs only inside `id.nearyou.app.config`.
 */
expect val apiBaseUrl: String
