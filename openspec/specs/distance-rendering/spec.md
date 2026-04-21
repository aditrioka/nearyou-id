# distance-rendering Specification

## Purpose
TBD - created by archiving change post-creation-geo. Update Purpose after archive.
## Requirements
### Requirement: DistanceRenderer.render lives in :shared:distance

The `:shared:distance` module SHALL expose `DistanceRenderer.render(distanceMeters: Double): String` in `commonMain`. The input represents an already-fuzzed distance (measured against `display_location`, not `actual_location`); the function is the sole user-facing distance renderer for the product.

#### Scenario: Function exists
- **WHEN** importing `DistanceRenderer.render` from `:shared:distance` in backend code
- **THEN** the import resolves AND the function signature is `(Double) -> String`

### Requirement: Floor at 5km, round-to-1km above

`render(d)` SHALL return the literal string `"5km"` when `d < 5000.0`. For `d >= 5000.0` it SHALL return `"${(d / 1000.0).roundToInt()}km"` using Kotlin's `Double.roundToInt()` (banker's rounding is not required; standard half-up via `kotlin.math.roundToInt` is sufficient).

#### Scenario: Boundary
- **WHEN** `render(5000.0)` is called
- **THEN** the return value is exactly `"5km"`

#### Scenario: Documented matrix — 4.5km
- **WHEN** `render(4500.0)` is called
- **THEN** the return value is exactly `"5km"`

#### Scenario: Documented matrix — 7.4km
- **WHEN** `render(7400.0)` is called
- **THEN** the return value is exactly `"7km"`

#### Scenario: Documented matrix — 7.6km
- **WHEN** `render(7600.0)` is called
- **THEN** the return value is exactly `"8km"`

### Requirement: Input is fuzzed distance by contract

Documentation adjacent to `DistanceRenderer` SHALL make clear that the input `distanceMeters` is expected to be a distance measured against `display_location` (or otherwise fuzzed). The function MUST NOT itself fuzz — fuzzing belongs to `JitterEngine`.

#### Scenario: Module docs state the contract
- **WHEN** reading the KDoc comment on `DistanceRenderer.render`
- **THEN** it references the fuzz-first contract (input is fuzzed; function does not fuzz)

### Requirement: :shared:distance jvmMain source set populated with haversine

The `:shared:distance` module SHALL add a `jvmMain` source set containing `Distance.metersBetween(a: LatLng, b: LatLng): Double`. The implementation MUST use the haversine formula on a sphere of radius 6371008.8 m (the WGS84 mean radius used by PostGIS for `ST_Distance(::geography, ::geography)` defaults). The function MUST live in `shared/distance/src/jvmMain/kotlin/id/nearyou/shared/distance/HaversineDistance.kt` (or analogous package path consistent with the module's `commonMain` packaging).

The `jvmMain` source set MUST NOT depend on JTS, GeoTools, vendor SDKs, or any non-stdlib dependency.

#### Scenario: jvmMain source set exists
- **WHEN** reading `shared/distance/build.gradle.kts`
- **THEN** the file declares a `jvmMain` source set in addition to `commonMain`

#### Scenario: Function exists with correct signature
- **WHEN** importing `id.nearyou.shared.distance.Distance.metersBetween` from `:backend:ktor`
- **THEN** the import resolves AND the signature is `(LatLng, LatLng) -> Double`

#### Scenario: PostGIS parity within 1%
- **WHEN** `Distance.metersBetween(a, b)` is called for any random pairs `(a, b)` drawn from the Indonesian envelope `[-11.0, 6.5] × [94.0, 142.0]` within the Nearby radius band (5–35 km separation) AND PostGIS `ST_Distance(a::geography, b::geography)` is computed for the same pairs
- **THEN** the relative difference between the Kotlin and PostGIS results is below 1% for every pair (PostGIS uses the WGS84 spheroid by default; haversine on a sphere diverges 0.5–0.7% in this distance band — the 1% bound is the realistic, not aspirational, upper limit and is well below the 1km rounding floor in DistanceRenderer)

### Requirement: :shared:distance is the canonical distance source

`:shared:distance` SHALL be the single source of distance computation for both backend and mobile. Backend code MUST NOT reimplement haversine, Vincenty, or `ST_Distance`-equivalent geography math outside this module (the SQL `ST_Distance` call inside the Nearby query is allowed as it is PostGIS-native, not a Kotlin reimplementation).

#### Scenario: No competing haversine in backend
- **WHEN** searching `backend/ktor/src/main/kotlin/` for `haversine`, `6371`, or `acos.*sin.*cos` patterns
- **THEN** zero matches are found outside test fixtures and `:shared:distance` itself

#### Scenario: Backend depends on jvmMain distance
- **WHEN** reading `backend/ktor/build.gradle.kts`
- **THEN** the dependency on `:shared:distance` resolves to the JVM target (transitively pulling in `jvmMain`)
