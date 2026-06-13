package id.nearyou.app.config

import id.nearyou.app.BuildConfig

/**
 * Android resolves the Sentry config from the per-flavor `BuildConfig.SENTRY_DSN` /
 * `BuildConfig.SENTRY_ENVIRONMENT` fields declared in `mobile/app/build.gradle.kts` (empty DSN until the
 * operator provisions — a blank DSN makes `CrashReporter.init` a no-op).
 */
actual val sentryConfig: SentryConfig
    get() = SentryConfig(dsn = BuildConfig.SENTRY_DSN, environment = BuildConfig.SENTRY_ENVIRONMENT)
