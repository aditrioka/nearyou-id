## ADDED Requirements

### Requirement: Onward audit dependency from Phase 2

No behavior of `JitterEngine` or `JITTER_SECRET` resolution changes in this capability. The `nearby-timeline` endpoint introduced by this change is the first read path that surfaces `display_location` to clients, and is therefore the first end-to-end path against which the Phase 2 audit (item 15 in `docs/08-Roadmap-Risk.md` — verifying that `actual_location` never leaks via any read response) MUST be run.

#### Scenario: actual_location absent from nearby response
- **WHEN** an end-to-end test issues `GET /api/v1/timeline/nearby` for a known viewer + known posts AND inspects the JSON response
- **THEN** no field name is `actual_location` AND no value matches the post's known `actual_location` lat/lng (only `display_location`-derived `latitude`, `longitude`, and `distance_m` appear)

#### Scenario: Phase 2 audit precondition documented
- **WHEN** reading `docs/08-Roadmap-Risk.md` Phase 2 item 15 after this change archives
- **THEN** the entry references `nearby-timeline` as the read path the audit is to be run against
