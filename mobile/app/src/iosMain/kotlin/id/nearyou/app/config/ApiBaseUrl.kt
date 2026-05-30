package id.nearyou.app.config

import platform.Foundation.NSBundle

/**
 * iOS resolves the base URL from the `ApiBaseUrl` `Info.plist` key, driven by an xcconfig
 * variable per scheme (§7 wires `Staging.xcconfig` / `Production.xcconfig` ⇒
 * `<key>ApiBaseUrl</key><string>$(APP_API_BASE_URL)</string>`).
 *
 * Fallback to the staging URL when the key is absent — this only happens transiently before
 * §7 lands the Info.plist wiring; a misconfigured production build will surface as a
 * staging-target call rather than a hard crash on launch. The fallback hostname lives here
 * (the `id.nearyou.app.config` package) which is the carve-out's allowed home for URLs.
 */
actual val apiBaseUrl: String
    get() =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("ApiBaseUrl") as? String)
            ?: "https://api-staging.nearyou.id"
