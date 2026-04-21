## ADDED Requirements

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
