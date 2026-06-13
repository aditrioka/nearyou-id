package id.nearyou.app.config

/**
 * The NON-SECRET Sentry connection config the `:infra:sentry` `CrashReporter` needs
 * (`mobile-crash-reporting`). [dsn] is a write-only client ingest key — safe in a client by Sentry's
 * design; NOT a secret (matches the "slot names in source, secrets in GCP Secret Manager" posture). A
 * **blank** [dsn] makes init a safe no-op (operator provisions per flavor — task 1.3). [environment] is
 * the build flavor (`dev` / `staging` / `production`). Resolved per flavor/scheme, NOT a hardcoded
 * literal at the call site (the `id.nearyou.app.config` carve-out is the only home for these). The
 * release version is supplied separately at the binding site from [appVersionName].
 */
data class SentryConfig(
    val dsn: String,
    val environment: String,
)

/**
 * Environment-aware Sentry config.
 *  - androidMain: `BuildConfig.SENTRY_DSN` / `BuildConfig.SENTRY_ENVIRONMENT` per gradle product flavor
 *    (empty DSN until the operator provisions; overridable via `-P<flavor>SentryDsn=`).
 *  - iosMain: `NSBundle` `SentryDsn` / `SentryEnvironment` Info.plist keys (xcconfig-driven), empty fallback.
 */
expect val sentryConfig: SentryConfig
