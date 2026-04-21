## MODIFIED Requirements

### Requirement: POST /api/v1/blocks/{user_id} creates a block (idempotent)

A Ktor route SHALL be registered at `POST /api/v1/blocks/{user_id}` requiring Bearer JWT auth. On success, the endpoint MUST execute the following statements inside a SINGLE JDBC transaction:
1. `INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (:caller, :path_user_id) ON CONFLICT (blocker_id, blocked_id) DO NOTHING`
2. `DELETE FROM follows WHERE (follower_id = :caller AND followee_id = :path_user_id) OR (follower_id = :path_user_id AND followee_id = :caller)`

Both statements MUST be committed together or rolled back together; a crash between them MUST leave the database in a consistent state (either the block is absent and follows remain, or the block is present and both directions of the follow relationship are gone). The endpoint MUST return HTTP 204 with no body on success, whether or not the INSERT actually inserted (already-blocked case) and whether or not the DELETE removed any rows (no-follow case). Re-blocking an already-blocked user MUST also return 204.

The canonical SQL flow is `docs/05-Implementation.md` §1286–1300 and MUST be matched verbatim (module the `followed_id`→`followee_id` rename enacted by V6).

#### Scenario: First block returns 204
- **WHEN** caller A blocks user B for the first time
- **THEN** the response is HTTP 204 AND a `user_blocks` row `(A, B, ...)` exists

#### Scenario: Re-block idempotent
- **WHEN** caller A blocks user B AND then immediately blocks user B again
- **THEN** both responses are HTTP 204 AND there is exactly one `user_blocks` row `(A, B, ...)`

#### Scenario: Unauthenticated rejected
- **WHEN** the request lacks a valid JWT
- **THEN** the response is HTTP 401 with `error.code = "unauthenticated"`

#### Scenario: Block cascades outbound follow
- **WHEN** caller A follows B AND then calls `POST /api/v1/blocks/B`
- **THEN** the response is HTTP 204 AND the `follows` row `(A, B, ...)` no longer exists

#### Scenario: Block cascades inbound follow
- **WHEN** user B follows caller A AND A calls `POST /api/v1/blocks/B`
- **THEN** the response is HTTP 204 AND the `follows` row `(B, A, ...)` no longer exists

#### Scenario: Block cascades both directions in one transaction
- **WHEN** caller A follows B AND B follows A AND A calls `POST /api/v1/blocks/B`
- **THEN** the response is HTTP 204 AND neither `(A, B)` nor `(B, A)` exists in `follows` AND `(A, B)` exists in `user_blocks`

#### Scenario: No follows rows to delete — still 204
- **WHEN** caller A calls `POST /api/v1/blocks/B` AND no `follows` row exists between A and B in either direction
- **THEN** the response is HTTP 204 AND `user_blocks` contains `(A, B)` AND `follows` is unchanged

## ADDED Requirements

### Requirement: Follow-cascade enforced at block time

The block flow SHALL enforce the invariant "a `user_blocks` row implies no `follows` row in either direction between the same two user UUIDs." The invariant is enforced IN THE APPLICATION (not via a DB trigger) by the transactional `INSERT` + bidirectional `DELETE` described in the modified `POST /api/v1/blocks/{user_id}` requirement above. No DB-level trigger, CHECK constraint, or foreign-key coupling between `user_blocks` and `follows` is required or permitted.

#### Scenario: Invariant holds after block
- **WHEN** the caller completes `POST /api/v1/blocks/B`
- **THEN** for the pair `(caller, B)`, at most one of the following is true in the committed DB state: (a) `user_blocks` row exists OR (b) `follows` row exists in either direction — never both

#### Scenario: Enforcement is in application code, not DB
- **WHEN** inspecting V5 and V6 migration files plus any later migrations
- **THEN** no trigger, rule, or cascade-between-tables coupling exists that would enforce this invariant at the DB level

## REMOVED Requirements

### Requirement: Future follow-cascade hook documented

**Reason:** Superseded by the new `Follow-cascade enforced at block time` requirement above. V5 added a documentation-only comment because `follows` did not yet exist; V6 creates `follows` and V6's accompanying code change extends `BlockService.block()` to execute the cascade. The TODO is now a discharged obligation, not a deferred one.

**Migration:** The scenario "Comment present in V5" remains accurate (the V5 file on disk still contains the original comment string — V5 is immutable once applied). New readers who need to understand the current contract should read the `Follow-cascade enforced at block time` requirement and the modified `POST /api/v1/blocks/{user_id}` requirement in this spec. No code-level migration is required.
