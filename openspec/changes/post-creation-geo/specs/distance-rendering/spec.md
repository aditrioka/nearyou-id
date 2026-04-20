## ADDED Requirements

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
