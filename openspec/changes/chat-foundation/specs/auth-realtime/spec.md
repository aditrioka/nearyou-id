## ADDED Requirements

### Requirement: Realtime RLS policy has a real consumer schema

Once `chat-foundation` lands the V15 migration, the V2-drafted RLS policy `participants_can_subscribe ON realtime.messages` ([V2__auth_foundation.sql:58-78](../../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql)) SHALL be active. Until V15, the policy's `IF EXISTS conversation_participants` gate evaluates false and the policy is dormant — the realtime token endpoint issues HS256 JWTs but no schema gates them.

This requirement is a cross-reference: the realtime token endpoint contract itself (defined by the existing `HS256 realtime-token endpoint`, `Unauthenticated calls rejected`, and `HS256 secret resolved through secretKey helper` requirements) does NOT change. What changes is that an HS256 token issued for user U, used against a `realtime:conversation:<uuid>` channel, now produces a meaningful authorization decision (allow if U is an active participant; deny otherwise) instead of a "policy gate not yet active" no-op.

#### Scenario: Token issued by this endpoint is gated by the realtime RLS policy after V15
- **GIVEN** V15 has been applied and `conversation_participants` exists
- **WHEN** an HS256 token issued by `GET /api/v1/realtime/token` for user U is presented to Supabase Realtime to subscribe to `realtime:conversation:<conv_id>`
- **AND** U is NOT an active participant of `<conv_id>`
- **THEN** Supabase Realtime denies the subscription via the V2-drafted RLS policy

#### Scenario: Token issued by this endpoint is allowed by the realtime RLS policy when participant
- **GIVEN** V15 has been applied AND user U has a row in `conversation_participants` for `<conv_id>` with `left_at IS NULL`
- **WHEN** an HS256 token issued by `GET /api/v1/realtime/token` for user U is presented to Supabase Realtime to subscribe to `realtime:conversation:<conv_id>`
- **THEN** Supabase Realtime allows the subscription via the V2-drafted RLS policy
