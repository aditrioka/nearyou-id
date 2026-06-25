## ADDED Requirements

### Requirement: moderation_queue.trigger enum extended with `area_spam` (V36)

Migration `V36__moderation_queue_area_spam_trigger.sql` SHALL extend the `moderation_queue.trigger` CHECK enum with the value `area_spam`, raising the allowed set to 8 values: `auto_hide_3_reports`, `perspective_api_high_score`, `uu_ite_keyword_match`, `admin_flag`, `csam_detected`, `anomaly_detection`, `username_flagged`, `area_spam`. Because the V9 enum is an inline column CHECK (auto-named `moderation_queue_trigger_check`), the migration SHALL perform `ALTER TABLE moderation_queue DROP CONSTRAINT moderation_queue_trigger_check, ADD CONSTRAINT moderation_queue_trigger_check CHECK (trigger IN ( … 8 values …))`. The extension is additive — existing rows are untouched and all seven previously-valid values continue to pass — so the V9 forward-compatibility scenarios remain valid. The `area_spam` trigger denotes the Layer 4 per-area anti-local-spam control and is DISTINCT from the reserved `anomaly_detection` value (which is earmarked for the separate per-user behavioral-baseline mechanism); the two SHALL NOT be conflated.

#### Scenario: area_spam insert succeeds after V36
- **WHEN** an INSERT supplies `trigger = 'area_spam'` against a DB migrated to V36
- **THEN** the INSERT succeeds (the value passes the extended CHECK)

#### Scenario: All eight trigger values are accepted after V36
- **WHEN** an INSERT supplies `trigger` from any of the eight values (`auto_hide_3_reports`, `perspective_api_high_score`, `uu_ite_keyword_match`, `admin_flag`, `csam_detected`, `anomaly_detection`, `username_flagged`, `area_spam`)
- **THEN** the INSERT succeeds

#### Scenario: Out-of-enum trigger still rejected after V36
- **WHEN** an INSERT supplies `trigger = 'spam_pattern_detected'` against a DB migrated to V36
- **THEN** the INSERT fails with a check-constraint violation (the extension added only `area_spam`, not arbitrary values)

#### Scenario: Pre-V36 rows remain valid
- **WHEN** the V36 migration runs against a DB that already holds `moderation_queue` rows with any of the seven original trigger values
- **THEN** the migration succeeds AND all existing rows remain present and valid (additive constraint change, no data rewrite)
