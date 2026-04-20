# coordinate-jitter Specification

## Purpose
TBD - created by archiving change post-creation-geo. Update Purpose after archive.
## Requirements
### Requirement: JitterEngine lives in :shared:distance

A Kotlin Multiplatform Gradle module `:shared:distance` SHALL exist with a `commonMain` source set containing `JitterEngine.offsetByBearing(actualLatLng: LatLng, postId: Uuid, secret: ByteArray): LatLng`. The module MUST NOT depend on vendor SDKs (Ktor, Ktor client, Koin, Supabase). Backend code importing `JitterEngine` MUST depend on `:shared:distance` via Gradle rather than reimplementing the math.

#### Scenario: Module is KMP
- **WHEN** reading `shared/distance/build.gradle.kts`
- **THEN** the file applies the `kotlin("multiplatform")` plugin AND declares a `commonMain` source set

#### Scenario: Backend depends on :shared:distance
- **WHEN** reading `backend/ktor/build.gradle.kts`
- **THEN** `dependencies { ... }` contains an entry referencing `:shared:distance`

### Requirement: HMAC derivation per docs/05

`JitterEngine.offsetByBearing` SHALL derive `bearing_radians` and `distance_meters` from `hmac = HMAC-SHA256(secret, postId.toBytes())` exactly as:
- `bearing_radians = bigEndianUint32(hmac, 0) / 2^32 * 2 * PI`
- `distance_meters = 50.0 + bigEndianUint32(hmac, 4) / 2^32 * 450.0`

The output LatLng MUST be the forward-geodesic offset of the input LatLng by `(bearing_radians, distance_meters)` on the WGS84 spheroid.

#### Scenario: Distance within 50-500m band
- **WHEN** `JitterEngine.offsetByBearing(actual, postId, secret)` is called for any 1000 random `postId` values with a fixed `actual` and `secret`
- **THEN** the great-circle distance between the result and `actual` is in `[50.0, 500.0]` meters for every call

### Requirement: Determinism

The function SHALL be a pure deterministic computation. Repeated calls with the same `(actual, postId, secret)` MUST return byte-identical `LatLng` results.

#### Scenario: Same inputs same output
- **WHEN** `offsetByBearing` is called twice with the same `(actual, postId, secret)`
- **THEN** both calls return `LatLng` values whose latitude and longitude components are bit-for-bit identical

### Requirement: Secret sensitivity

Given the same `(actual, postId)` but two different `secret` values, the function SHALL produce two different `LatLng` results (i.e., the output's bits depend on the secret).

#### Scenario: Different secret different output
- **WHEN** `offsetByBearing(actual, postId, secretA)` and `offsetByBearing(actual, postId, secretB)` are evaluated with `secretA != secretB`
- **THEN** the two returned `LatLng` values are not equal

### Requirement: Non-reversibility without the secret

Without knowledge of `secret`, there SHALL be no feasible algorithm in this codebase that recovers `actual` from `(displayLocation, postId)`. The design documents the reliance on HMAC-SHA256's preimage-resistance and keeps `secret` out of all non-backend code.

#### Scenario: Secret not in client-facing paths
- **WHEN** searching `mobile/**`, `shared/**`, and non-admin backend repositories for `JITTER_SECRET` or the secret bytes
- **THEN** no hit is found outside `backend/ktor/.../post/` (the HMAC call site), `:shared:distance` test fixtures, and secret-resolution code

### Requirement: JITTER_SECRET resolution via SecretResolver

The backend SHALL resolve the HMAC key via `secretKey(env, "jitter-secret")` through the existing `SecretResolver` chain, consistent with `invite-code-secret`. In dev, the secret comes from the `JITTER_SECRET` environment variable (hyphen-to-underscore normalized). The secret MUST be 32 bytes (base64-decoded) to match the HMAC-SHA256 key size convention.

#### Scenario: Env var resolution
- **WHEN** `SecretResolver.resolve("jitter-secret")` is invoked with `KTOR_ENV=dev` and `JITTER_SECRET=<base64 32 bytes>`
- **THEN** the returned bytes decode to exactly 32 bytes

#### Scenario: Missing secret fails fast
- **WHEN** the server starts with `JITTER_SECRET` unset
- **THEN** server startup fails with an error message identifying the missing secret key

