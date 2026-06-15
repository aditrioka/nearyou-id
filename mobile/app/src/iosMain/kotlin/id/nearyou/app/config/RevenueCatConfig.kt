package id.nearyou.app.config

import platform.Foundation.NSBundle

/**
 * iOS resolves the RevenueCat publishable client key from the `RevenueCatPublicKey` `Info.plist` key,
 * driven by an xcconfig variable per scheme (mirroring `sentryConfig` / `apiBaseUrl`). A missing key
 * falls back to empty so a not-yet-provisioned build degrades to the fail-soft Unconfigured paywall
 * rather than crashing — the live purchase path is an enhancement, not a launch dependency.
 */
actual val revenueCatPublicKey: String
    get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("RevenueCatPublicKey") as? String) ?: ""
