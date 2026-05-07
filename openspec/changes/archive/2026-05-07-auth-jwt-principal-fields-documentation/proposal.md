## Why

Three shipped capabilities (`post-likes`, `post-replies`, `chat-conversations`) and one shipped change (`chat-realtime-broadcast`) reference two `UserPrincipal` fields — `subscriptionStatus: String` and `isShadowBanned: Boolean` — that the canonical `auth-jwt` capability spec does NOT document. Both fields are correctly populated by [`AuthPlugin.configureUserJwt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt) at JWT validation time (single auth-time `users` row SELECT, no extra round-trip), but the spec gap means a future maintainer reading [`openspec/specs/auth-jwt/spec.md`](../../specs/auth-jwt/spec.md) will not know these fields exist on the principal. Each new rate-limit handler or principal-reading flow re-uses the fields without an authoritative anchor — spec debt that compounds every time a principal-reading handler ships.

Tracked in [`FOLLOW_UPS.md`](../../../FOLLOW_UPS.md) as `auth-jwt-spec-debt-userprincipal-subscription-status` (the entry name predates the broader scope expansion to cover both fields in one go).

## What Changes

- **MODIFIES** `auth-jwt` spec to add three ADDED Requirements:
  - One documenting `UserPrincipal.subscriptionStatus: String` (loaded from `users.subscription_status` at JWT validation time, consumed by `post-likes`, `post-replies`, `chat-conversations` rate-limit gates).
  - One documenting `UserPrincipal.isShadowBanned: Boolean` (loaded from `users.is_shadow_banned` at JWT validation time, consumed by `chat-realtime-broadcast` § Publish-side shadow-ban skip).
  - One documenting that both fields are populated by a SINGLE auth-time `users` row SELECT (no per-handler refresh; mid-flight admin flips between auth and request-end are accepted).
- No code changes. Both fields are already correctly populated by `AuthPlugin.configureUserJwt`. The KDoc on `UserPrincipal` already references this future docs-only OpenSpec change; this change closes the loop.
- No `auth-session` modifications. The session-lifecycle spec covers refresh-token rotation / reuse / logout — none of which produce a principal directly. Concentrating principal-field documentation in `auth-jwt` (where the validation block lives) preserves the existing spec division of concerns and avoids duplication risk.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `auth-jwt`: ADDS three Requirements (two field declarations + one shared-load-site invariant) documenting two `UserPrincipal` fields populated by the auth-time `users` row SELECT.

## Impact

- **Code**: none. Both fields are already correctly populated by [`AuthPlugin.configureUserJwt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt:121).
- **Specs**: `openspec/specs/auth-jwt/spec.md` gains three ADDED Requirements (post-archive). No existing Requirement is removed or rewritten.
- **Docs**: none. The KDoc on `UserPrincipal` already documents both fields with FOLLOW_UPS-aware framing; nothing more to update in `docs/`.
- **FOLLOW_UPS**: archive phase deletes the `auth-jwt-spec-debt-userprincipal-subscription-status` entry. The entry's third action item (RevenueCat webhook bumps `users.token_version` on EXPIRATION/cancellation events) is forward-looking to a Phase 4 handler that does not yet exist; deleting this entry does NOT close that requirement — the upcoming `revenuecat-webhook` change owns that verification on its own.
- **Tests**: no new tests. Behavior of both fields is observable through existing rate-limit / publish-skip tests in `like-rate-limit`, `reply-rate-limit`, `chat-rate-limit`, and `chat-realtime-broadcast`. This change documents what those tests already enforce.
- **Out of scope**: Any new `UserPrincipal` fields. This change documents only the two fields already shipped. Any future field addition will be an additive MODIFIES on `auth-jwt` in its own change.
