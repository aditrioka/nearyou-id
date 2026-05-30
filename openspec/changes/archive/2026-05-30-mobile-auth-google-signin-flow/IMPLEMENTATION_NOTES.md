# Implementation Notes — mobile-auth-google-signin-flow (Mobile #3)

Decisions + deviations surfaced during `/opsx:apply`. Companion to `design.md` (which holds
the propose-time decisions); this file records implementation-time findings.

## Testing strategy for platform-binding code (§3 + §4)

`commonTest` cannot exercise the real platform ceremonies:

- **`SecureTokenStore`** — the Android actual needs a real `Context` + Android Keystore +
  Tink runtime; the iOS actual needs a real Keychain. commonTest runs on the JVM (Android
  unit) + iOS-sim native, neither of which provides those. Coverage strategy:
  - `SecureTokenStoreContractTest` (commonTest) verifies the read/write/clear contract
    against an in-tree `InMemoryTokenStore` fake.
  - Architectural assertions (master-key URI is canonical, no `setUserAuthenticationRequired(true)`,
    no `EncryptedSharedPreferences`, iOS has no `kSecAttrAccessGroup`, iOS uses
    `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) are enforced by the §9.10a–§9.10d
    grep audits in CI rather than runtime tests — matching the project's existing
    grep-audit pattern (§9.6–§9.10).
  - The real DataStore+Tink raw-byte-leak runtime test (`tasks.md` 3.5) is **deferred** —
    the project has no `androidInstrumentedTest` / Robolectric infra yet. Runtime
    correctness is covered by the §10.4 + §10.4b device smoke (real-token round-trip,
    uninstall/reinstall regeneration). Tracked as a follow-up
    (`mobile-auth-signin-android-instrumented-encryption-test`, §11).

- **`GoogleSignInClient`** — the Android actual needs Credential Manager (real Play Services
  + foreground Activity); the iOS actual needs `GIDSignIn` (real `GoogleService-Info.plist`
  + a presenting view controller). Neither is reachable from commonTest. Coverage strategy:
  - The `GoogleSignInGateway` interface is the substitution seam. `AuthRepository`
    depends on the interface; production binds the platform `GoogleSignInClient`, commonTest
    binds `FakeGoogleSignInGateway` (a queue-backed fake).
  - `GoogleSignInResultContractTest` (commonTest) verifies the sealed-result contract +
    the "cancellation is not Failed" mapping.
  - The real ceremonies are exercised by the §10 device smoke (real Google account on a
    test device).

This mirrors the `design.md` Risks-table row "Mobile-side commonTest can't easily exercise
Credential Manager / GoogleSignIn iOS SDK".

## iOS CocoaPods toolchain (§2 + §3 + §4)

The `kotlin("native.cocoapods")` plugin requires a working `pod` CLI on `PATH` at iOS-build
time. The session's dev workstation had a **broken** `~/.gem/bin/pod` gem-shim shadowing a
**working** `/opt/homebrew/bin/pod` (CocoaPods 1.16.2). Until the broken shim is removed,
iOS Gradle tasks must run with `PATH=/opt/homebrew/bin:$PATH ./gradlew …`. Documented as a
dev-workstation precondition in `dev/docs/google-cloud-oauth-clients.md` (§7). CI lanes that
build the iOS framework must ensure a real `pod` is first on `PATH`.

## `GoogleSignInGateway` adapter seam (§4)

`design.md` Decision 2 specifies `expect class GoogleSignInClient { suspend fun signIn() }`.
Implementation adds a commonMain `interface GoogleSignInGateway { suspend fun signIn() }`
that the expect class implements (`expect class GoogleSignInClient : GoogleSignInGateway`).
This is additive — the expect class + its `signIn()` member match the spec verbatim; the
interface only provides the DI/test substitution seam so `AuthRepository` can be unit-tested
with `FakeGoogleSignInGateway`. No behavioral change.
