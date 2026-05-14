import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        // Initialize Koin BEFORE the SwiftUI scene body builds, so that any koinInject()
        // / koinScreenModel<T>() call inside the KMP-side composables (reached via
        // ContentView -> ComposeView -> MainViewController()) finds Koin already running.
        // The Kotlin shim is idempotent (guarded by getKoinOrNull()), so re-launches of
        // the app process safely re-call this without throwing "Koin already started".
        KoinInitKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
