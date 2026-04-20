## ADDED Requirements

### Requirement: Strict 18+ DOB check at signup

The signup handler SHALL reject any request where `date_of_birth > (CURRENT_DATE_UTC - INTERVAL '18 years')`. The comparison MUST be inclusive at exactly 18 years (today is the user's 18th birthday → accepted) and strict below (today is the day before the 18th birthday → rejected). The DB-level `CHECK (date_of_birth <= (CURRENT_DATE - INTERVAL '18 years'))` on the `users` table MUST remain in place as a backstop; the app-layer check MUST run first so the side-effect of inserting into `rejected_identifiers` can be performed on rejection.

#### Scenario: Exactly 18 today accepted
- **WHEN** `date_of_birth` equals `CURRENT_DATE_UTC - 18 years`
- **THEN** the signup proceeds past the age gate (no rejection)

#### Scenario: One day under 18 rejected
- **WHEN** `date_of_birth` equals `CURRENT_DATE_UTC - 18 years + 1 day`
- **THEN** the response is HTTP 403 with code `user_blocked` AND a row is inserted into `rejected_identifiers`

#### Scenario: DB CHECK backstop fires on bypass
- **WHEN** the app-layer guard is bypassed (e.g. hand-crafted INSERT) AND the DB CHECK is violated
- **THEN** the DB raises a check-constraint violation AND the server surfaces HTTP 500 AND a structured log line `signup.dob_check_db_fallback_fired` is emitted

### Requirement: Insert into rejected_identifiers on under-18 rejection

On app-layer under-18 rejection, the handler SHALL insert a row into `rejected_identifiers` with `identifier_hash = sha256(provider_sub)`, `identifier_type` ∈ `{'google','apple'}` matching the provider, `reason = 'age_under_18'`, and `rejected_at = NOW()`. If the `(identifier_hash, identifier_type)` pair already exists (duplicate under-18 retry), the INSERT MUST be no-op (ON CONFLICT DO NOTHING) — the pre-check should have short-circuited before reaching this path, but the INSERT MUST still be idempotent.

#### Scenario: New under-18 rejection inserts row
- **WHEN** a first-time identifier declares DOB under 18
- **THEN** `rejected_identifiers` contains a row with `(identifier_hash, identifier_type, reason) = (sha256(sub), provider, 'age_under_18')`

#### Scenario: Duplicate under-18 rejection is idempotent
- **WHEN** the same identifier (bypassing the pre-check in some hypothetical race) declares DOB under 18 twice
- **THEN** `rejected_identifiers` contains exactly one row for that identifier (ON CONFLICT DO NOTHING)

### Requirement: Pre-check before DOB parsing

The handler SHALL consult `rejected_identifiers` (by `(identifier_hash, identifier_type)`) BEFORE parsing, validating, or reading the `date_of_birth` field. If the pre-check matches, the handler returns HTTP 403 `user_blocked` without evaluating the DOB.

#### Scenario: Pre-check short-circuits DOB logic
- **WHEN** the identifier is in `rejected_identifiers` AND the request omits `date_of_birth`
- **THEN** the response is HTTP 403 `user_blocked` (not HTTP 400 for missing DOB)

### Requirement: Rejection body indistinguishability

The HTTP 403 response for the rejected-identifier pre-check AND the HTTP 403 response for a fresh under-18 rejection MUST share the same `error` code (`user_blocked`), the same message string, and the same total byte length. The purpose is to prevent a client from distinguishing "new under-18 rejection" from "this identifier was already blocked."

#### Scenario: Byte identity
- **WHEN** signup is called once with an already-blocked identifier AND once with a fresh identifier declaring under-18
- **THEN** the two response bodies are byte-identical

### Requirement: Rejection reason codes reserved for future use

The `rejected_identifiers.reason` column MUST accept the CHECK-enumerated values `{'age_under_18', 'attestation_persistent_fail'}`. This capability SHALL only ever write `'age_under_18'`. The `'attestation_persistent_fail'` value is reserved for the later attestation-integration change; this capability MUST NOT be modified to write it.

#### Scenario: Only age_under_18 written here
- **WHEN** any signup request in this capability results in a `rejected_identifiers` INSERT
- **THEN** the row's `reason` equals `'age_under_18'`
