## ADDED Requirements

### Requirement: `UserPrincipal.subscriptionStatus` field

The `UserPrincipal` returned by the auth-jwt plugin's validate block SHALL carry a `subscriptionStatus: String` field populated from the authenticated user's `users.subscription_status` column at JWT validation time. The string value MUST be one of the three CHECK-enforced tiers per [`V2__auth_foundation.sql:23-24`](../../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql): `'free'`, `'premium_active'`, or `'premium_billing_retry'` (the 7-day Premium billing-retry grace state). The auth plugin SHALL pass through whatever value `users.subscription_status` carries without transformation; tier-mapping logic (e.g., "treat `premium_billing_retry` as Premium for daily-cap purposes") lives in each consumer handler, not in the auth plugin.

Consumers of this field include the rate-limit handlers in `post-likes` (Free 10/day cap), `post-replies` (Free 20/day cap), and `chat-conversations` (Free 50/day chat-send cap). Each consumer treats both `premium_active` and `premium_billing_retry` as Premium (skip the daily limiter entirely) per the canonical [`PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")`](../../../../backend/ktor/src/main/kotlin/id/nearyou/app/engagement/LikeService.kt) pattern.

#### Scenario: `premium_active` subscription reflected on principal

- **GIVEN** caller A has `users.subscription_status = 'premium_active'`
- **WHEN** A presents an otherwise-valid JWT and the auth-jwt validate block runs
- **THEN** `call.principal()` returns a `UserPrincipal` whose `subscriptionStatus = "premium_active"`

#### Scenario: `free` subscription reflected on principal

- **GIVEN** caller A has `users.subscription_status = 'free'`
- **WHEN** A presents an otherwise-valid JWT and the auth-jwt validate block runs
- **THEN** `call.principal()` returns a `UserPrincipal` whose `subscriptionStatus = "free"`

#### Scenario: `premium_billing_retry` subscription reflected on principal

- **GIVEN** caller A has `users.subscription_status = 'premium_billing_retry'` (within the 7-day Premium billing-retry grace window)
- **WHEN** A presents an otherwise-valid JWT and the auth-jwt validate block runs
- **THEN** `call.principal()` returns a `UserPrincipal` whose `subscriptionStatus = "premium_billing_retry"` (the auth plugin passes the value through verbatim; downstream rate-limit handlers SHALL apply their `PREMIUM_STATES` membership check to skip the limiter for this caller — that mapping is a handler concern, not an auth-plugin concern)

### Requirement: `UserPrincipal.isShadowBanned` field

The `UserPrincipal` returned by the auth-jwt plugin's validate block SHALL carry an `isShadowBanned: Boolean` field populated from the authenticated user's `users.is_shadow_banned` column at JWT validation time. `TRUE` indicates the user is shadow-banned per the project's invisible-actor moderation model; `FALSE` indicates a normal user.

The primary consumer of this field is the chat send handler's publish-side skip: per the canonical [`openspec/specs/chat-realtime-broadcast/spec.md`](../../../../openspec/specs/chat-realtime-broadcast/spec.md) § Publish-side shadow-ban skip, the chat send handler SHALL NOT invoke `ChatRealtimeClient.publish(...)` when `viewer.isShadowBanned = TRUE` (the `chat_messages` row still persists, preserving the invisible-actor model on the read path).

#### Scenario: Non-shadow-banned principal populated as FALSE

- **GIVEN** caller A has `users.is_shadow_banned = FALSE`
- **WHEN** A presents an otherwise-valid JWT and the auth-jwt validate block runs
- **THEN** `call.principal()` returns a `UserPrincipal` whose `isShadowBanned = false`

#### Scenario: Shadow-banned principal populated as TRUE

- **GIVEN** caller A has `users.is_shadow_banned = TRUE` (and is otherwise eligible — not banned, not suspended, token version match)
- **WHEN** A presents an otherwise-valid JWT and the auth-jwt validate block runs
- **THEN** `call.principal()` returns a `UserPrincipal` whose `isShadowBanned = true` (auth-jwt does NOT 401/403 a shadow-banned user — shadow ban is invisible-to-the-user; the actor still authenticates, but downstream handlers gate on the principal field per their own capability spec)

### Requirement: Single auth-time `users` row SELECT populates the principal

The auth-jwt validate block SHALL issue exactly ONE `users` row SELECT per authenticated request and populate the entire `UserPrincipal` (`userId`, `tokenVersion`, `subscriptionStatus`, `isShadowBanned`) from that single row. The same SELECT also evaluates the auth-rejection gates (`is_banned`, `suspended_until`); those gate values are NOT carried on the principal but are consumed inline by the validate block to short-circuit with HTTP 403 when applicable. Handlers downstream of the validate block MUST NOT issue a fresh `SELECT subscription_status FROM users` or `SELECT is_shadow_banned FROM users` for principal-driven decisions; the principal-loaded value is authoritative for the duration of the request.

This invariant is load-bearing for two consumer specs:

- `post-likes` § "Read-site constraint" — the rate limiter runs BEFORE any DB read; a fresh tier SELECT would violate the rate-limit-before-body-parse ordering.
- `chat-realtime-broadcast` § Publish-side shadow-ban skip § Scenario "No additional shadow-ban SELECT on publish path" — the chat send handler explicitly verifies that the only user-row read on the request path is the auth-time one already performed by the validate block (verifiable via JDBC statement spy).

Mid-flight admin flips between the auth-time SELECT and the request-end handler step (e.g., a moderator flips `is_shadow_banned = TRUE` AFTER auth completed but BEFORE the handler reaches the publish step) are accepted: the in-flight request continues with the stale value, all subsequent requests pick up the new value at their own auth-time SELECT. This stale-state-acceptable property is documented per `chat-realtime-broadcast` design § D2; defense-in-depth at the data-read layer comes from the [`visible_users` view](../../../../docs/05-Implementation.md) (`docs/05-Implementation.md` § Server-Side Consistency via Database Views), which filters shadow-banned authors from `visible_posts` JOIN paths regardless of any single principal's stale state.

#### Scenario: Single SELECT at auth time

- **GIVEN** caller A is authenticating with a valid JWT
- **WHEN** the auth-jwt validate block runs
- **THEN** exactly ONE `users` row SELECT is issued by the validate block; the resulting `UserPrincipal` carries `userId`, `tokenVersion`, `subscriptionStatus`, and `isShadowBanned` populated from that single row

#### Scenario: Handler does not refresh principal-loaded fields

- **GIVEN** caller A has authenticated and a request is being handled (e.g., `POST /api/v1/posts/{post_id}/like` or `POST /api/v1/chat/{conversation_id}/messages`)
- **WHEN** the handler reads `subscriptionStatus` or `isShadowBanned` to gate behavior
- **THEN** the handler reads from `call.principal<UserPrincipal>()` directly; no additional `SELECT subscription_status` or `SELECT is_shadow_banned` SQL is executed against `users` for this request (verifiable via JDBC statement spy capturing all queries on the request path)

#### Scenario: Stale principal across an admin mid-flight flip

- **GIVEN** caller A's principal has `subscriptionStatus = "free"` and `isShadowBanned = false` at auth time AND a moderator flips `users.is_shadow_banned = TRUE` for A in a separate connection AFTER auth completed but BEFORE the request handler reaches its principal-reading step
- **WHEN** the handler reads `principal.isShadowBanned`
- **THEN** the handler reads `false` (stale, per the auth-time snapshot) AND the in-flight request proceeds with the stale value (e.g., the chat send handler invokes publish; the next request from A sees `isShadowBanned = true` at its own auth time and correctly skips publish)
