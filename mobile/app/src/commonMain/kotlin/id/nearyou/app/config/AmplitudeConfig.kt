package id.nearyou.app.config

/**
 * The NON-SECRET Amplitude ingestion API key for the analytics tracker (`mobile-amplitude-analytics`).
 * It is an Amplitude *ingestion write key* — client-embeddable (like the Sentry DSN), NOT a secret
 * (matches the "slot names in source, secrets in GCP Secret Manager" posture). A **blank** key binds
 * `NoOpAnalyticsTracker` (no events; operator provisions per flavor). Resolved per flavor/scheme, NOT a
 * hardcoded literal at the call site (the `id.nearyou.app.config` carve-out is the only home for these).
 *  - androidMain: `BuildConfig.AMPLITUDE_API_KEY` per product flavor (empty until provisioned;
 *    overridable via `-P<flavor>AmplitudeApiKey=`; staging slot `staging-amplitude-api-key`, docs/10 § 3.8).
 *  - iosMain: `NSBundle` `AmplitudeApiKey` Info.plist key (xcconfig-driven), empty fallback.
 */
expect val amplitudeApiKey: String
