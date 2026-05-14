package id.nearyou.app

import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS bridge to the shared Compose UI.
 *
 * iOS two-layer call chain:
 *   1. Swift `iOSApp.init()` (`iosApp/iosApp/iOSApp.swift`) MUST call `doInitKoin()`
 *      (from `id.nearyou.app.di`) BEFORE the SwiftUI `WindowGroup` body builds.
 *      Initializes Koin so that subsequent screens can resolve dependencies via
 *      `koinInject()` / `koinScreenModel<T>()` without a "Koin not started" crash.
 *   2. Swift `ContentView` (`iosApp/iosApp/ContentView.swift`) MUST instantiate this
 *      [MainViewController] via `UIViewControllerRepresentable`. `ContentView` SHALL NOT
 *      render its own SwiftUI product UI; it is a pure bridge wrapper.
 *
 * If a UIKit `AppDelegate` is introduced later (e.g., for push-notification handling),
 * the `doInitKoin()` call moves into `application(_:didFinishLaunchingWithOptions:)`.
 */
@Suppress("ktlint:standard:function-naming")
fun MainViewController() = ComposeUIViewController { App() }
