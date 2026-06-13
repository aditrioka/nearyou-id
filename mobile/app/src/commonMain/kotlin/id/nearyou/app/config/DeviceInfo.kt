package id.nearyou.app.config

/**
 * The FCM registration `platform` value — `"android"` or `"ios"`, matching the backend
 * `CHECK (platform IN ('android','ios'))` (`mobile-fcm-token-registration`). A compile-time platform
 * constant, NEVER runtime-detected in commonMain.
 */
expect val devicePlatform: String

/**
 * The app version string for the FCM registration `app_version` field — Android `BuildConfig.VERSION_NAME`,
 * iOS the `CFBundleShortVersionString` Info.plist key. Nullable: when unavailable it is omitted from the
 * request body (the backend column is nullable).
 */
expect val appVersionName: String?
