package id.nearyou.app.config

import platform.Foundation.NSBundle

/**
 * iOS resolves the Amplitude ingestion key from the `AmplitudeApiKey` `Info.plist` key, driven by
 * xcconfig variables per scheme (mirroring `SentryDsn`). A missing key falls back to empty so a
 * misconfigured build binds `NoOpAnalyticsTracker` (no events) rather than crashing — analytics is an
 * enhancement, not a launch dependency.
 */
actual val amplitudeApiKey: String
    get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("AmplitudeApiKey") as? String) ?: ""
