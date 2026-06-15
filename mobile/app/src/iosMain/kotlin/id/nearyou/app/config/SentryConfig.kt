package id.nearyou.app.config

import platform.Foundation.NSBundle

/**
 * iOS resolves the Sentry config from the `SentryDsn` / `SentryEnvironment` `Info.plist` keys, driven by
 * xcconfig variables per scheme (mirroring `apiBaseUrl` / `supabaseConfig`). A missing `SentryDsn` falls
 * back to empty so a misconfigured build degrades to no-reporting (`CrashReporter.init` no-ops) rather
 * than crashing — crash reporting is an enhancement, not a launch dependency.
 */
actual val sentryConfig: SentryConfig
    get() =
        SentryConfig(
            dsn = (NSBundle.mainBundle.objectForInfoDictionaryKey("SentryDsn") as? String) ?: "",
            environment = (NSBundle.mainBundle.objectForInfoDictionaryKey("SentryEnvironment") as? String) ?: "production",
        )
