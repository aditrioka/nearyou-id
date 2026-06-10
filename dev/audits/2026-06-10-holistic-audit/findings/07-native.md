# 07 — Native Layers (androidMain, iosMain, iosApp host, expect/actual seams, lifecycle, permissions)

Audited 2026-06-10 against `docs/11-Engineering-Standards.md` §2.5 (expect/actual) + §2.4 (perf) + §2.2 (state).
Scope: `mobile/app/src/androidMain/**`, `mobile/app/src/iosMain/**`, `mobile/app/src/iosTest/**`, flavor manifests (`src/dev`, `src/stagingDebug`), `iosApp/**`, `mobile/app/build.gradle.kts`.
Hunting focus: the class of bugs that pass JVM CI but break on device.

**Verified clean (hunted, not found):** `enableEdgeToEdge()` called in `MainActivity.onCreate` before `super.onCreate` (official sample order); no deprecated `statusBarColor`/`navigationBarColor` setters; `CurrentActivityHolder` set in `onResume`/cleared in `onPause` (no destroy-leak); deep links are handled by separate trampoline activities (`DevTestLoginActivity` / `StagingTestLoginActivity`, dev/stagingDebug-only with documented threat model) so no `onNewIntent` gap in `MainActivity`; Robolectric Release-variant exclude list covers all 14 UI test classes currently in `androidUnitTest` (cross-checked file-by-file); iOS `timeIntervalSinceNow` category-member import is present (`import platform.Foundation.timeIntervalSinceNow`); the keychain `cfDictionaryOf` CF retain/release accounting is correct (CFBridgingRetain +1 → AddValue retain → CFRelease our +1; constants add-only); `ProactiveRefreshEffect` ON_RESUME wiring has a real iosTest (`ProactiveRefreshEffectIosTest` drives a `LifecycleRegistry` to RESUMED); Podfile compose-resources bootstrap solves the pod-install ordering hazard self-bootstrappingly; permission DENIED-collapse + no-re-prompt-after-denial is the spec'd decision (`openspec/specs/mobile-location/spec.md` § terminal-denial collapse) — not flagged.

## CRITICAL

(none)

## HIGH

1. `mobile/app/src/androidMain/AndroidManifest.xml:12` — `allowBackup="true"` with no backup exclusion rules bricks sign-in after device-to-device transfer
   Auto Backup / D2D transfer restores the Tink keyset SharedPreferences (`nearyou_auth_tokens_tink_keyset_pref`), the token DataStore, and the `nearyou_location_permission` asked-flag to a new device — but the Android-Keystore master key (`android-keystore://nearyou_auth_tokens_master_key`) does not transfer. `AndroidKeysetManager` then can't unwrap the restored keyset: `read()` swallows it (`GeneralSecurityException` → null, user looks signed out — acceptable), but `write()` (`SecureTokenStore.kt:70-86`) dereferences the lazy `aead` OUTSIDE any catch, so the next sign-in crashes — and reinstalling can re-restore the same poisoned prefs. This is the classic Tink/EncryptedSharedPreferences restore failure mode. Fix: add `android:dataExtractionRules` (+ `fullBackupContent` for API ≤30) excluding the two Tink pref files + the `nearyou_auth_tokens` datastore, and catch `GeneralSecurityException` in the write path with a delete-keyset-and-regenerate recovery. Confidence: high on mechanism, medium on exact Tink 1.x throw-vs-recover behavior per version.

2. `mobile/app/src/iosMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt:126` — `SecItemAdd` status ignored → silent token-persistence failure
   `writeKeychainItem` is delete-then-add, but the `SecItemAdd` OSStatus is discarded (and `dataUsingEncoding ?: return` is another silent-exit). If the add fails (e.g. `errSecInteractionNotAllowed` pre-first-unlock, keychain pressure, or a duplicate from a racing write since delete+add is not atomic), the app continues with in-memory tokens and the user is silently signed out on next cold start — undiagnosable. The hunting-list errSec contract (ItemNotFound vs duplicate → update path) is unimplemented. Fix: check the status; on `errSecDuplicateItem` fall back to `SecItemUpdate`; on other failures emit a `DiagnosticSink` event (never the token value). Confidence: high (code-certain; the failure trigger is the rare part).

## MEDIUM

3. `mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt:42,56` — No DataStore corruption handler; `read()` can crash-loop the cold-start router
   `preferencesDataStore(name = DATASTORE_NAME)` has no `corruptionHandler`; `dataStore.data.first()` throws `CorruptionException`/`IOException` uncaught (only `GeneralSecurityException` around decrypt is caught). `AuthRepository.isAuthenticated()` calls this from RootRouter on every launch → a corrupted prefs file = crash on every cold start until the user clears app data. Fix: `ReplaceFileCorruptionHandler { emptyPreferences() }` + catch `IOException` in `read()` → null (treat as signed out). Confidence: high (absence certain; corruption likelihood low because DataStore writes are atomic).

4. `mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt:44-53` — Tink keyset init (Keystore IPC + SharedPreferences I/O) runs on the main thread
   The lazy `aead` is first dereferenced inside `read()`/`write()`, which run on the caller's dispatcher — and no commonMain caller ever leaves `viewModelScope` (Main.immediate; verified: zero `Dispatchers.` switches in commonMain production code). First launch does master-key generation + keyset persist (tens–hundreds of ms, StrictMode DiskRead/DiskWrite violations) on the UI thread during RootRouter routing; `AndroidLocationPermissionController:28` similarly does its first SharedPreferences load on Main. The iOS actual already wraps in `withContext(Dispatchers.Default)` — the Android one should match: wrap `read`/`write`/`clear` bodies (incl. the lazy init + Base64/AEAD work) in `withContext(Dispatchers.IO)`. Confidence: high.

5. `mobile/app/src/androidMain/kotlin/id/nearyou/app/config/PlatformHttp.kt:7` + `iosMain/.../PlatformHttp.kt:8` — No HttpTimeout anywhere; engine defaults diverge 10s (OkHttp) vs 60s (Darwin)
   `OkHttp.create()` / `Darwin.create()` are both default-configured and `HttpClientFactory` installs no `HttpTimeout` plugin — so the same slow backend call fails at ~10s on Android (OkHttp connect/read/write defaults) but hangs ~60s on iOS (`timeoutIntervalForRequest`). Timeline/auth UX diverges per platform and neither value was chosen. Fix: install `HttpTimeout` in `HttpClientFactory.create` (single commonMain source of truth, e.g. connect 10s / request 30s) — also the natural home for the docs/11 §2.6 `HttpRequestRetry` guidance, currently unimplemented. Confidence: high.

6. `mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt:44` — `LocationPermissionRequestBridge.launcher` never cleared → leaked Activity after destroy
   The Koin-singleton bridge holds the `ActivityResultLauncher`, whose callback closure references the `MainActivity` instance. It is set in `onCreate` and never nulled in `onDestroy`: after the user backs out (activity destroyed, process alive) the dead Activity + view tree stay reachable until the next launch replaces the reference. Same-shape seam `CurrentActivityHolder` clears itself in `onPause`; the bridge should mirror it: in `onDestroy`, `if (bridge.launcher === locationPermissionLauncher) bridge.launcher = null`. Confidence: high (textbook singleton-holds-launcher leak; bounded to one Activity).

7. `mobile/app/src/iosMain/kotlin/id/nearyou/app/location/IosLocationPermissionController.kt:32-51` + `IosLocationProvider.kt:39` + `auth/GoogleSignInClient.kt:36-43` — iOS actuals are main-thread-only by accident, with no guard and (for `request()`) no timeout
   `CLLocationManager` delivers delegate callbacks on the run loop of the creating thread; created off-main (any future `withContext(Dispatchers.Default)` around a repository call) the callbacks never arrive → `request()` suspends FOREVER (it has no `withTimeoutOrNull`, unlike the provider) and `current()` always times out. `GIDSignIn.signInWithPresentingViewController` + `UIApplication.windows` are likewise UIKit-main-only. It works today solely because every caller is `viewModelScope` (Main) — exactly the latent class that passes CI and breaks on device after an innocent dispatcher refactor. Fix: wrap each entry point body in `withContext(Dispatchers.Main)` (self-documenting, cheap) and give the authorization wait a defensive ceiling like the provider's. Confidence: high on the CoreLocation run-loop requirement; the bug is latent, not live.

8. `iosApp/iosApp/Info.plist:9` — Missing `NSLocationDefaultAccuracyReduced`: iOS permission prompt defaults to Precise ON; only app code self-limits accuracy
   Android's coarse-only posture is OS-enforced (`ACCESS_COARSE_LOCATION` only in the manifest); on iOS the app gets FULL-accuracy authorization by default and reduction relies entirely on `desiredAccuracy = kCLLocationAccuracyReduced` in `IosLocationProvider` — one regressed line away from precise coordinates, and the prompt UI contradicts the UU-PDP data-minimization copy. The spec (`openspec/specs/mobile-location/spec.md` § reduced accuracy) names reduced accuracy as the contract. Fix: add `<key>NSLocationDefaultAccuracyReduced</key><true/>` so the OS prompt + grant default to approximate (user can still opt up). Confidence: high.

9. `mobile/app/build.gradle.kts:193-197` — Release build has `isMinifyEnabled = false`: docs/11 §2.4 mandates R8 + `proguard-android-optimize.txt`
   The standards baseline (§2.4 "Release Android builds: R8 + proguard-android-optimize.txt") is unmet; release ships unshrunk Tink/Play-services/Credential-Manager bytecode. Pre-launch this is invisible, but flipping it late is risky precisely because Tink + kotlinx.serialization + Compose need keep-rule soak time — turning it on early surfaces R8 breakage while the surface is small. Fix: enable minify + the optimize default file + library-provided consumer rules; smoke per the verify-loop DoD. Confidence: high (explicit contract).

10. `iosApp/iosApp.xcodeproj/project.pbxproj:307` (×4 configs) vs `mobile/app/build.gradle.kts:32` + `iosApp/Podfile:58` — Deployment-target matrix incoherent: app 18.2, KMP framework + Pods 13.0
    The effective floor is 18.2 (almost certainly the Xcode template default, not an operator decision — it excludes every device that can't run iOS 18.2, a real market cut for an Indonesia MVP). Meanwhile the K/N framework claims 13.0 while using iOS-14+-only API (`CLLocationManager.authorizationStatus` instance property, `kCLLocationAccuracyReduced`) — and Kotlin/Native has NO `@available` checking, so if anyone later lowers the app target below 14 it compiles clean and crashes on old devices with unrecognized-selector. Fix: pick the supported floor deliberately (e.g. 16.x), then align pbxproj `IPHONEOS_DEPLOYMENT_TARGET`, `ios.deploymentTarget`, and the Podfile `platform :ios`. Confidence: high on the mismatch; the floor choice is an operator decision.

## LOW

11. `mobile/app/src/androidMain/kotlin/id/nearyou/app/location/AndroidLocationProvider.kt:56,78-83` — `CancellationTokenSource` created but never wired; caller cancellation doesn't cancel the fused request
    `awaitOrNull` has no `cont.invokeOnCancellation`; navigating away mid-acquisition leaves `getCurrentLocation` running to its full 12s duration (wasted radio/battery, no leak). Fix: hoist the CTS and `cont.invokeOnCancellation { cts.cancel() }`. Confidence: high.

12. `mobile/app/src/iosMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt:46` — Cancel detection compares `error.code == -5` without checking `error.domain`
    Any NSError with code -5 from a non-GIDSignIn domain (e.g. a network-layer error surfaced through the completion) would be misclassified as `UserCancelled` (silent, no banner) instead of `Failed`. Fix: also require `error.domain == "com.google.GIDSignIn"` (kGIDSignInErrorDomain). Confidence: medium-high.

13. `mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt:37-44` + iOS sibling — No nonce on the Google ID-token request
    `GetGoogleIdOption` supports `.setNonce(...)` (and GIDSignIn the equivalent) to bind the token to one exchange; without it a captured ID token is replayable against `/signin` for its ~1h validity. Needs backend coordination (issue + verify the nonce), so it's a hardening follow-up, not a drive-by — specs are silent on it. Confidence: high that it's absent; medium that it's worth the backend round-trip pre-launch.

14. `mobile/app/src/iosMain/kotlin/id/nearyou/app/config/ApiBaseUrl.kt:18` — Stale staging fallback now that the §7 Info.plist wiring has landed
    The KDoc calls the `"https://api-staging.nearyou.id"` fallback transitional-pre-§7; the `ApiBaseUrl` plist key is now wired, so the fallback's only remaining effect is masking a broken plist by silently pointing a (possibly production) build at staging — the opposite of `Production.xcconfig`'s deliberate fail-fast placeholder posture. Fix: fail fast (error/crash) when the key is missing in release binaries, or at least log via DiagnosticSink. Confidence: high.

15. `iosApp/Configuration/Production.xcconfig` — Production overrides `GID_CLIENT_ID` but not `GID_SERVER_CLIENT_ID` / `GID_REVERSED_CLIENT_ID`
    Both inherit the STAGING values from `Config.xcconfig`. Harmless today (production GID is a placeholder anyway), but on provisioning day setting only `GID_CLIENT_ID` leaves the ID-token `aud` pointed at the staging server client → backend 401 that will cost a debugging session. Fix: add explicit placeholder overrides for all three keys now so the gap is visible. Confidence: high.

16. `mobile/app/src/iosMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt:83` — `UIApplication.windows` is deprecated (iOS 15+); fine at the current 18.2 floor but worth migrating to `connectedScenes` → key window when touched next. Also `IosLocationProvider.kt:116` / `IosLocationPermissionController.kt:82`: the `retainedLocationDelegates`/`retainedAuthorizationDelegates` `mutableSetOf` retention sets are non-thread-safe — safe under today's all-main-thread usage, becomes a real race if finding 7's `withContext(Dispatchers.Main)` fix is NOT adopted and a background caller appears. Confidence: high/low respectively.

17. `mobile/app/src/androidMain/AndroidManifest.xml:17` — Activity theme is `Theme.Material.Light.NoActionBar` (no DayNight/dark variant): dark-mode users get a white flash + light window background between process start and first Compose frame. Cosmetic-but-visible on device; fix is a values-night theme or `Theme.Material.NoActionBar` parent switch when a splash/theming pass happens. Confidence: high.

## EXPECT-ACTUAL INVENTORY

Per docs/11 §2.5 (prefer commonMain interface + Koin platform binding; `expect class` is Beta — reserve expect/actual for top-level functions). Seam → pattern → verdict:

| Seam | Pattern | Verdict |
|---|---|---|
| `platformModule` (`di/MobileModule.kt:183`) | `expect val Module` + Koin | OK — canonical KMP-Koin idiom |
| `httpClientEngine()` (`config/PlatformHttp.kt:12`) | expect top-level fun | OK — exactly the §2.5-blessed shape (but see finding 5: both actuals unconfigured) |
| `isDebugBuild` (`config/PlatformHttp.kt:19`) | expect top-level val | OK |
| `apiBaseUrl` (`config/ApiBaseUrl.kt:15`) | expect top-level val, injected as a parameter at the DI seam for testability | OK (finding 14 on the iOS actual's fallback) |
| `SecureTokenStore` (`auth/SecureTokenStore.kt:30`) | hybrid: `interface TokenStore` (what consumers inject) + `expect class` actual | Works; the expect-class layer is redundant — Koin platform modules could bind plain platform classes to `TokenStore` directly (as location does). Don't churn; new seams should follow the interface-only shape |
| `GoogleSignInClient` (`auth/GoogleSignInClient.kt:29`) | hybrid: `interface GoogleSignInGateway` + `expect class` actual | Same verdict as SecureTokenStore — leave, don't replicate |
| `LocationProvider` / `LocationPermissionController` | pure commonMain interface + platform impls bound in Koin platform modules (no expect class) | Exemplary — the §2.5 target shape; newest seams already converged on it |
| Activity seams (`CurrentActivityHolder`, `LocationPermissionRequestBridge`) | Android-only Koin singletons populated by `MainActivity` | OK pattern (no business logic in androidMain); launcher-clear gap is finding 6 |
| Lifecycle (ProactiveTokenRefreshTrigger ON_RESUME) | no expect/actual at all — commonMain `LifecycleEventEffect` via lifecycle-runtime-compose; iOS emission via CMP's `ComposeUIViewController` LifecycleOwner | OK — effect-level wiring proven by `ProactiveRefreshEffectIosTest`; platform ON_RESUME emission rests on CMP ≥1.7 foreground-notification mapping (current pin 1.11.1: fine) |

Net: no error-prone seam demands restructuring; the two `expect class` hybrids predate the §2.5 rule and are shielded by their interfaces.
