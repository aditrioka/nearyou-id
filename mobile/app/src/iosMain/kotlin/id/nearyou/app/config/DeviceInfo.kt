package id.nearyou.app.config

import platform.Foundation.NSBundle

actual val devicePlatform: String get() = "ios"

/** iOS resolves the app version from the `CFBundleShortVersionString` Info.plist key. */
actual val appVersionName: String?
    get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
