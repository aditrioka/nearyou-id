import SwiftUI
import ComposeApp
import GoogleSignIn

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
                .onOpenURL { url in
                    // Route the Google Sign-In OAuth callback back into the SDK. The Bool
                    // return value MUST be acted on (never silently swallowed) — `false`
                    // means this URL was NOT a GoogleSignIn callback, so it should fall
                    // through to any subsequent handler rather than being treated as handled.
                    // (Per openspec/specs/mobile-auth-signin/spec.md § "iOS URL handler
                    // verifies GIDSignIn.handle(url) Bool return value".)
                    let handled = GIDSignIn.sharedInstance.handle(url)
                    if !handled {
                        // No other URL handlers are registered today; log so a future
                        // URL-scheme misconfiguration is diagnosable rather than a silent no-op.
                        print("onOpenURL: GIDSignIn did not handle \(url); no downstream handler")
                    }
                }
        }
    }
}
