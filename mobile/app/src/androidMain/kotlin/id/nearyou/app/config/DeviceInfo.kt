package id.nearyou.app.config

import id.nearyou.app.BuildConfig

actual val devicePlatform: String get() = "android"

/** Android resolves the app version from the AGP-generated `BuildConfig.VERSION_NAME` (the `versionName`). */
actual val appVersionName: String? get() = BuildConfig.VERSION_NAME
