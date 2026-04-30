# Auth — Realtime

## Purpose

Defines the Supabase-compatible HS256 token issuance contract used by the chat WSS path. The endpoint sits behind the standard auth middleware and exchanges a Ktor RS256 access token for a short-lived HS256 token signed with the Supabase project's JWT secret.
## Requirements
### Requirement: HS256 realtime-token endpoint

`GET /api/v1/realtime/token` SHALL be authenticated (requires a valid Ktor RS256 access token). It MUST return a Supabase-compatible HS256 JWT with claims `{ sub, role: "authenticated", iat, exp }`, TTL 1 hour, signed with the secret resolved by `secretKey(env, "supabase-jwt-secret")`.

#### Scenario: Authenticated caller receives token
- **WHEN** a request with a valid Ktor access token calls the endpoint
- **THEN** the response is HTTP 200 with body `{ "token": "<jws>", "expires_in": 3600 }` and the JWS verifies under HS256 with the configured Supabase secret

#### Scenario: Claims correct
- **WHEN** the returned token is decoded
- **THEN** its claims contain `sub` matching the caller's user id, `role == "authenticated"`, and `exp - iat == 3600`

### Requirement: Unauthenticated calls rejected

The endpoint MUST require authentication; an unauthenticated request SHALL receive HTTP 401.

#### Scenario: No bearer token
- **WHEN** a request without an Authorization header calls the endpoint
- **THEN** the response is HTTP 401

### Requirement: HS256 secret resolved through secretKey helper

The signing key SHALL be obtained via `secretKey(env, "supabase-jwt-secret")` so the staging vs production prefix is honored. Hardcoded secret strings MUST NOT appear in the codebase.

#### Scenario: Source code search
- **WHEN** searching the backend source for the literal string `"supabase-jwt-secret"`
- **THEN** the only occurrence is the call to `secretKey(env, "supabase-jwt-secret")`

### Requirement: Realtime RLS policy installed with shadow-ban-aware subscriber semantics

The V15 migration shipped by `chat-foundation` SHALL install the `participants_can_subscribe` policy on `realtime.messages` directly (V2's gated `DO` block was a one-time no-op at V2-time because `conversation_participants` did not exist; Flyway is forward-only so V2 cannot re-run). The installed policy SHALL match V2's structural intent ([V2__auth_foundation.sql:64-89](../../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql)) EXCEPT the subscriber-side `AND NOT EXISTS (SELECT 1 FROM public.users WHERE id = cp.user_id AND is_shadow_banned = TRUE)` clause from V2 lines 81-84 SHALL be REMOVED — shadow-banned users SHALL be allowed to subscribe to their own conversation realtime channels per the invisible-actor model in `chat-foundation/design.md` § D9. Hiding shadow-banned senders from OTHER readers is a publish-side concern owned by the future `chat-realtime-broadcast` change.

The realtime token endpoint contract itself (defined by the existing `HS256 realtime-token endpoint`, `Unauthenticated calls rejected`, and `HS256 secret resolved through secretKey helper` requirements) does NOT change. What changes is that a token issued for user U, used against a `realtime:conversation:<uuid>` channel, now produces a meaningful authorization decision (allow if U is an active participant; deny otherwise — irrespective of U's shadow-ban state) instead of being a "policy not yet installed" no-op.

#### Scenario: Token-gated subscription denied for non-participant after V15
- **GIVEN** V15 has been applied and the `participants_can_subscribe` policy is installed
- **WHEN** an HS256 token issued by `GET /api/v1/realtime/token` for user U is presented to Supabase Realtime to subscribe to `realtime:conversation:<conv_id>`
- **AND** U is NOT an active participant of `<conv_id>`
- **THEN** Supabase Realtime denies the subscription

#### Scenario: Token-gated subscription allowed for active participant after V15
- **GIVEN** V15 has been applied AND user U has a row in `conversation_participants` for `<conv_id>` with `left_at IS NULL`
- **WHEN** an HS256 token issued by `GET /api/v1/realtime/token` for user U is presented to Supabase Realtime to subscribe to `realtime:conversation:<conv_id>`
- **THEN** Supabase Realtime allows the subscription

#### Scenario: Shadow-banned active participant subscription allowed after V15
- **GIVEN** V15 has been applied AND user U is an active participant of `<conv_id>` AND U has `is_shadow_banned = TRUE`
- **WHEN** an HS256 token issued by `GET /api/v1/realtime/token` for user U is presented to Supabase Realtime to subscribe to `realtime:conversation:<conv_id>`
- **THEN** Supabase Realtime ALLOWS the subscription — the V15-installed policy does NOT carry V2's `is_shadow_banned` subscriber-side clause; the invisible-actor model preserves the shadow-banned user's own realtime view

