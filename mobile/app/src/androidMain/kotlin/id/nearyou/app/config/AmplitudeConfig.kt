package id.nearyou.app.config

import id.nearyou.app.BuildConfig

/**
 * Android resolves the Amplitude ingestion key from the per-flavor `BuildConfig.AMPLITUDE_API_KEY` field
 * declared in `mobile/app/build.gradle.kts` (empty until the operator provisions — a blank key binds
 * `NoOpAnalyticsTracker`).
 */
actual val amplitudeApiKey: String
    get() = BuildConfig.AMPLITUDE_API_KEY
