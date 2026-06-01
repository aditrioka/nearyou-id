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

### Requirement: Cross-platform HMAC actuals are byte-identical, verified by a Native-executable known-answer test

The Android and iOS `hmacSha256` actuals SHALL produce byte-identical output to the JVM actual for the same `(key, msg)` inputs. A `commonTest` known-answer test SHALL assert `hmacSha256(knownKey, knownMsg)` equals a fixed expected 32-byte digest pinned to a **published RFC 4231 HMAC-SHA-256 test vector** (an external oracle — e.g., RFC 4231 Test Case 2: key = ASCII `"Jefe"`, data = ASCII `"what do ya want for nothing?"`, expected digest `5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843`), so all three actuals are checked against a known-correct value rather than merely agreeing with each other.

The known-answer test MUST be written with **`kotlin.test`** (`@Test` + `assertContentEquals`/`assertEquals`), NOT Kotest — the existing `:shared:distance` tests are Kotest `StringSpec`s run via the JUnit5 platform (`useJUnitPlatform()`), which executes ONLY on JVM/Android; a Kotest `commonTest` compiles for the Kotlin/Native targets but never runs there. `kotlin.test` `@Test` (already a `commonTest` dependency) IS executed by the Kotlin/Native test runner, so the iOS actual is genuinely exercised. The CI/local verification command MUST include `:shared:distance:iosSimulatorArm64Test` (and `:shared:distance:testDebugUnitTest` for Android), NOT only `:shared:distance:build` (which compiles but does not run Native tests).

#### Scenario: HMAC known-answer test is kotlin.test and runs on Native
- **WHEN** inspecting `:shared:distance` `commonTest`
- **THEN** a `kotlin.test` `@Test` (NOT a Kotest `StringSpec`) asserts `hmacSha256(<RFC 4231 key bytes>, <RFC 4231 message bytes>)` equals the published RFC 4231 expected 32-byte digest

#### Scenario: iOS test target actually executes the known-answer test
- **WHEN** running `./gradlew :shared:distance:iosSimulatorArm64Test`
- **THEN** the HMAC known-answer test is discovered and executed on the iOS simulator target (verifying the CommonCrypto `CCHmac` actual against the RFC 4231 vector), AND the verification command set is `:shared:distance:iosSimulatorArm64Test` + `:shared:distance:testDebugUnitTest` (NOT merely `:shared:distance:build`)

#### Scenario: JITTER_SECRET still absent from client-facing paths
- **WHEN** searching `mobile/**` and `shared/**` (excluding test fixtures) for `JITTER_SECRET` or hardcoded secret bytes
- **THEN** no hit is found — only the jitter *algorithm* (`JitterEngine` + the HMAC primitive) is compiled for the mobile targets; the secret is never embedded in client code (consistent with the existing § "Non-reversibility without the secret" requirement, which fences the secret, not the math)
