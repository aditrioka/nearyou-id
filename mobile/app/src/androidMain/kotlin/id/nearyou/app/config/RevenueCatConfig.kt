package id.nearyou.app.config

import id.nearyou.app.BuildConfig

/**
 * Android resolves the RevenueCat publishable client key from the per-flavor
 * `BuildConfig.REVENUECAT_PUBLIC_KEY` field declared in `mobile/app/build.gradle.kts` (blank until the
 * operator provisions it — a blank key makes `initKoin` skip configuration, fail-soft to Unconfigured).
 */
actual val revenueCatPublicKey: String
    get() = BuildConfig.REVENUECAT_PUBLIC_KEY
