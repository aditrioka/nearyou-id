## ADDED Requirements

### Requirement: :shared:distance is consumable by the mobile Kotlin Multiplatform targets

The `:shared:distance` module SHALL declare the Kotlin Multiplatform targets `androidTarget()`, `iosArm64()`, and `iosSimulatorArm64()` in addition to the existing `jvm()` target, so that `:mobile:app` (whose targets are android + iosArm64 + iosSimulatorArm64) can consume the module's `commonMain` API (`DistanceRenderer.render`, `LatLng`). The existing `commonMain` source set — including `JitterEngine.offsetByBearing`, `UuidV7`, and the `Crypto.kt` `expect fun hmacSha256` / `expect fun unixMillis` declarations — MUST remain in `commonMain` (per the existing § "JitterEngine lives in :shared:distance" requirement); adding the mobile targets MUST NOT move that code out of `commonMain`.

Each newly-added target SHALL provide the `actual` implementations the `commonMain` `expect`s require:
- `androidMain` SHALL implement `actual fun hmacSha256` via `javax.crypto.Mac` ("HmacSHA256") and `actual fun unixMillis` via `System.currentTimeMillis()` (Android is JVM-based; the implementation is equivalent to the shipped `jvmMain` actual).
- `iosMain` SHALL implement `actual fun hmacSha256` via CommonCrypto `CCHmac(kCCHmacAlgSHA256, …)` (the `platform.CoreCrypto` Kotlin/Native binding, using `usePinned` for the key, message, and 32-byte digest buffers) and `actual fun unixMillis` via the Foundation `NSDate` epoch.

The module MUST still NOT add a third-party crypto/library dependency to `gradle/libs.versions.toml` for this — the Android actual uses the JDK (`javax.crypto`) and the iOS actual uses the platform CommonCrypto + Foundation APIs.

#### Scenario: build.gradle declares the mobile targets
- **WHEN** reading `shared/distance/build.gradle.kts`
- **THEN** the `kotlin { ... }` block declares `androidTarget()`, `iosArm64()`, AND `iosSimulatorArm64()` in addition to `jvm()`

#### Scenario: Mobile-target actuals exist for the commonMain expects
- **WHEN** inspecting the `androidMain` and `iosMain` source sets of `:shared:distance`
- **THEN** `androidMain` declares `actual fun hmacSha256` (javax.crypto) AND `actual fun unixMillis` AND `iosMain` declares `actual fun hmacSha256` (CommonCrypto `CCHmac` / `kCCHmacAlgSHA256`) AND `actual fun unixMillis` (Foundation epoch)

#### Scenario: :mobile:app depends on :shared:distance
- **WHEN** reading `mobile/app/build.gradle.kts`
- **THEN** the `commonMain` dependencies include `implementation(projects.shared.distance)` (so `DistanceRenderer.render` + `LatLng` are consumable on android + iOS)

#### Scenario: JitterEngine and crypto expects remain in commonMain
- **WHEN** inspecting `:shared:distance` `commonMain`
- **THEN** `JitterEngine.offsetByBearing`, `UuidV7`, and the `Crypto.kt` `expect fun hmacSha256` / `expect fun unixMillis` are still declared in `commonMain` (not moved to `jvmMain`); the existing § "JitterEngine lives in :shared:distance" requirement continues to hold

### Requirement: Cross-platform HMAC actuals are byte-identical to the JVM actual

The Android and iOS `hmacSha256` actuals SHALL produce byte-identical output to the JVM actual for the same `(key, msg)` inputs. A `commonTest` known-answer test SHALL assert `hmacSha256(knownKey, knownMsg)` equals a fixed expected 32-byte digest, so the test runs on — and validates — every target (JVM, Android, iOS), closing the gap that the existing crypto coverage is `jvmTest`-only. This guarantees `JitterEngine` produces the same `display_location` on every platform.

#### Scenario: HMAC known-answer test runs on all targets
- **WHEN** inspecting `:shared:distance` `commonTest`
- **THEN** a test asserts `hmacSha256(<fixed key bytes>, <fixed message bytes>)` equals a fixed expected 32-byte digest (a published HMAC-SHA256 vector or a vector captured from the JVM actual), so the iOS and Android actuals are verified equal to the JVM actual

#### Scenario: JITTER_SECRET still absent from client-facing paths
- **WHEN** searching `mobile/**` and `shared/**` (excluding test fixtures) for `JITTER_SECRET` or hardcoded secret bytes
- **THEN** no hit is found — only the jitter *algorithm* (`JitterEngine` + the HMAC primitive) is compiled for the mobile targets; the secret is never embedded in client code (consistent with the existing § "Non-reversibility without the secret" requirement, which fences the secret, not the math)
