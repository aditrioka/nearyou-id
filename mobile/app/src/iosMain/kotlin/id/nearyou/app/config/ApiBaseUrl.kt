package id.nearyou.app.config

import platform.Foundation.NSBundle

/**
 * iOS resolves the base URL from the `ApiBaseUrl` `Info.plist` key, driven by an xcconfig
 * variable per scheme (§7 wired `Staging.xcconfig` / `Production.xcconfig` ⇒
 * `<key>ApiBaseUrl</key><string>$(APP_API_BASE_URL)</string>`).
 *
 * The plist wiring has landed, so a missing key is a broken build configuration, not a
 * transitional state. Release binaries FAIL FAST (matching `Production.xcconfig`'s deliberate
 * fail-fast placeholder posture) — silently falling back would point a possibly-production
 * build at staging (2026-06-10 audit, 07-#14). Debug binaries keep the staging fallback for
 * local-dev ergonomics. The fallback hostname lives here (the `id.nearyou.app.config`
 * package), the carve-out's allowed home for URLs.
 */
actual val apiBaseUrl: String
    get() =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("ApiBaseUrl") as? String)
            ?: if (isDebugBuild) {
                "https://api-staging.nearyou.id"
            } else {
                error("ApiBaseUrl missing from Info.plist — check the active xcconfig's APP_API_BASE_URL wiring")
            }
