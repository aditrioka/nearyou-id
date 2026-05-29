package id.nearyou.app.config

import id.nearyou.app.BuildConfig

/**
 * Android resolves the base URL from the per-flavor `BuildConfig.API_BASE_URL` field
 * declared in `mobile/app/build.gradle.kts` (`buildConfigField` per product flavor).
 */
actual val apiBaseUrl: String
    get() = BuildConfig.API_BASE_URL
