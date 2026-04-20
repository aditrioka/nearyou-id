# username-generation Specification

## Purpose
TBD - created by archiving change signup-flow. Update Purpose after archive.
## Requirements
### Requirement: Canonical 5-attempt generation loop

The `UsernameGenerator` used by the signup handler SHALL attempt candidates in the following fixed order, stopping on the first success:

1. Attempt 1–2: `"{adjective}_{noun}"` (lowercase, both parts drawn uniformly from the configured adjective and noun lists).
2. Attempt 3–4: `"{adjective}_{noun}_{modifier}"` (modifier drawn uniformly from the configured modifier list).
3. Attempt 5: `"{adjective}_{noun}_{random_5_digit}"` (5-digit decimal 10000–99999).

Between attempts the generator MUST re-sample all of adjective, noun, modifier, and digits; it MUST NOT reuse the previous attempt's parts. All candidates MUST be lowercased before check and insert.

#### Scenario: Happy-path attempt 1 lands
- **WHEN** the first `{adj}_{noun}` candidate is not reserved, not on release-hold, and is a fresh UNIQUE in `users`
- **THEN** exactly one INSERT is attempted and the signup proceeds with that username

#### Scenario: Collision chain drives through all 5 attempts
- **WHEN** attempts 1–4 each hit a reserved-username, release-hold, or UNIQUE collision
- **THEN** the generator reaches attempt 5 with a `{adj}_{noun}_{5_digit}` candidate

### Requirement: Candidate rejected if present in reserved_usernames

Before attempting an `INSERT INTO users`, the generator MUST query `SELECT 1 FROM reserved_usernames WHERE username = LOWER(:candidate)`. A hit MUST cause the attempt to be skipped and the next attempt sampled; the reserved hit MUST NOT count as a failed INSERT (no write was attempted).

#### Scenario: Reserved word skipped
- **WHEN** a sampled candidate lowercases to a value present in `reserved_usernames`
- **THEN** the generator moves to the next attempt without attempting an INSERT

### Requirement: Candidate rejected if on release-hold in username_history

Before attempting an `INSERT INTO users`, the generator MUST query `SELECT 1 FROM username_history WHERE LOWER(old_username) = LOWER(:candidate) AND released_at > NOW()`. A hit MUST cause the attempt to be skipped.

#### Scenario: Candidate still on 30-day hold
- **WHEN** a sampled candidate matches an `old_username` in `username_history` with `released_at > NOW()`
- **THEN** the generator moves to the next attempt

#### Scenario: Candidate whose hold has elapsed
- **WHEN** a sampled candidate matches an `old_username` row whose `released_at <= NOW()`
- **THEN** the generator does NOT skip on that basis (the hold has expired)

### Requirement: UNIQUE-violation on INSERT continues the loop

The authoritative collision check is the `INSERT INTO users` itself. If the INSERT raises Postgres `unique_violation` on the `users_username_key` constraint, the handler MUST catch the exception, consume that attempt, and move to the next one. Other SQL exceptions MUST propagate.

#### Scenario: Concurrent signups collide
- **WHEN** two concurrent signups sample the same candidate and only one `INSERT` wins
- **THEN** the losing INSERT raises `unique_violation` AND its signup moves to the next attempt

### Requirement: Fallback `{adj}_{noun}_{uuid8hex}` always lands

After attempt 5 fails, the generator MUST produce a candidate `"{adjective}_{noun}_{uuid8hex}"` where `uuid8hex` is the first 8 hex chars of `UUID.randomUUID().toString().replace("-","")`. This MUST be attempted exactly once. If it also hits `unique_violation`, the handler returns HTTP 503 `username_generation_failed` (per auth-signup spec); it MUST NOT recurse into further attempts.

#### Scenario: All 5 attempts collide, fallback lands
- **WHEN** attempts 1–5 all fail
- **THEN** the generator attempts exactly one `{adj}_{noun}_{uuid8hex}` INSERT AND on success signup proceeds

#### Scenario: Fallback collision surfaces 503
- **WHEN** the fallback INSERT itself raises `unique_violation`
- **THEN** the signup returns HTTP 503 `username_generation_failed` (no further retry)

### Requirement: Generated username never exceeds 60 characters

All candidates MUST satisfy `LENGTH(candidate) <= 60` matching the `users.username VARCHAR(60)` ceiling. The generator MUST enforce this invariant by construction; it MUST NOT rely on DB truncation or rejection. If a word-pair resource combination could exceed 60 chars for a given template, the generator MUST skip that combination at load time or truncate deterministically.

#### Scenario: Every emitted candidate fits
- **WHEN** the generator emits a candidate
- **THEN** the candidate's length is ≤ 60 characters

### Requirement: Word-pair dataset loaded from resource

At application startup, the generator SHALL load the adjective, noun, and modifier lists from the resource `backend/ktor/src/main/resources/username/wordpairs.json`. The file MUST parse as `{ "adjectives": [String], "nouns": [String], "modifiers": [String] }` with each array non-empty and each string matching `^[a-z0-9]+$`. Load failures MUST prevent the server from starting.

#### Scenario: Malformed resource fails startup
- **WHEN** `wordpairs.json` is missing or has a non-matching schema
- **THEN** the Ktor application fails to start (fail-fast on startup, not on first signup)

### Requirement: Deterministic test seeding

The generator MUST accept an injectable RNG (`java.util.SplittableRandom`-compatible) so unit tests can drive deterministic candidate sequences. In production the default seed MUST be random.

#### Scenario: Test seeding produces expected order
- **WHEN** a test injects a seeded RNG
- **THEN** consecutive `generate()` calls return a predictable sequence of candidates

