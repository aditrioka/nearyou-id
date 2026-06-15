package id.nearyou.app.di

import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.diagnostics.CrashReportingController
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.mp.KoinPlatformTools

/**
 * Initialize Koin for the `:mobile:app` module.
 *
 * Idempotency. Guarded by `KoinPlatformTools.defaultContext().getOrNull() == null`, so
 * calling [initKoin] when Koin is already running is a no-op. This is intentional so
 * that Compose previews, Activity recreations, and `commonTest` re-entries do not throw
 * "Koin already started" exceptions.
 *
 * Note on the entry-point choice: `KoinPlatformTools.defaultContext()` is the canonical
 * KMP-common surface for accessing the active `KoinContext` across all platforms (JVM
 * / Android, iOS / Kotlin Native, JS, Wasm). The JVM-only `org.koin.core.context.GlobalContext`
 * singleton is NOT visible from iosMain in `koin-core` 4.1.0 — using it in commonMain
 * breaks the iOS target compile.
 *
 * Platform call-site conventions:
 *  - Android: invoke from `MainActivity.onCreate` BEFORE the first `setContent { App() }`.
 *  - iOS: invoke via the Swift-callable [doInitKoin] shim from the `iOSApp.init()` block
 *    in `iosApp/iosApp/iOSApp.swift`, BEFORE the `WindowGroup { ContentView() }` scene
 *    body runs. (If a UIKit `AppDelegate` is later introduced, the same shim moves into
 *    `AppDelegate.application(_:didFinishLaunchingWithOptions:)`.)
 *
 * Thread-safety caveat. The `getOrNull() == null` guard is a check-then-act pattern that
 * is safe ONLY when invoked from a single thread. The scaffold's current call sites
 * (`MainActivity.onCreate`, `iOSApp.init()`) are both main-thread; both are fine. Any
 * FUTURE non-main-thread call site (e.g., a background-init service, a JS engine
 * bootstrap, a coroutine launched from `Application.onCreate`) MUST guard externally
 * via `synchronized(KoinInit::class)` or equivalent. Removing the `getOrNull()` guard
 * for "performance" will reintroduce the "Koin already started" double-init bug — keep
 * the guard.
 */
fun initKoin(additionalConfig: KoinAppDeclaration? = null) {
    if (KoinPlatformTools.defaultContext().getOrNull() == null) {
        startKoin {
            modules(mobileModule, platformModule)
            additionalConfig?.invoke(this)
        }
        startCrashReporting()
    }
}

/**
 * mobile-crash-reporting — initialize crash reporting once Koin is up (called from [initKoin] right
 * after `startKoin`, so it runs at process startup before app code can crash). Opt-out default ON; a
 * last-known crash DECLINE in the [ConsentSnapshotStore] closes the session (docs/06 § Enforcement). A
 * blank DSN makes init a safe no-op. User correlation is set on the next sign-in (`AuthRepository`).
 */
private fun startCrashReporting() {
    val koin = KoinPlatformTools.defaultContext().get()
    val controller = koin.get<CrashReportingController>()
    val snapshot = koin.get<ConsentSnapshotStore>().read()
    // Opt-out default ON; close iff a last-known decline is present. Blank DSN no-ops init.
    controller.applyStartupConsent(snapshot?.crash)
}

/**
 * Swift-callable top-level shim for [initKoin]. Kotlin/Native exposes top-level functions
 * to Swift as static members of a generated `KoinInitKt` class; the Swift call site is
 * `KoinInitKt.doInitKoin()`.
 */
fun doInitKoin() = initKoin()
