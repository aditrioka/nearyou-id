## Context

`UserPrincipal` is the authenticated request principal produced by the `auth-jwt` plugin's validate block at [`AuthPlugin.kt:46`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt:46). It is loaded once per authenticated request from a single `users` row read by the validate block at [`AuthPlugin.kt:84`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt:84) (`users.findById(userId)`), and the resulting `UserPrincipal` carries — as of today — four fields: `userId: UUID`, `tokenVersion: Int`, `subscriptionStatus: String`, and `isShadowBanned: Boolean`.

The first two (`userId`, `tokenVersion`) are documented in [`auth-jwt/spec.md`](../../specs/auth-jwt/spec.md) (token-version revocation requirement; principal returned with the user's id). The latter two (`subscriptionStatus`, `isShadowBanned`) were added by prior changes (`like-rate-limit` task 6.1.1 added subscriptionStatus; `chat-realtime-broadcast` Phase 2.6 added isShadowBanned) but never made it into the canonical spec — pure spec debt that compounds as more handlers consume the principal.

The KDoc on `UserPrincipal` already references the future docs-only OpenSpec change tracking this debt; this change closes that loop.

## Goals / Non-Goals

**Goals:**

- Document the two un-anchored `UserPrincipal` fields in the canonical `auth-jwt` spec so future maintainers reading the spec see the full principal shape.
- Capture the single-load-site invariant: both fields are populated by the SAME auth-time `users` row SELECT, and handlers MUST NOT issue a fresh `SELECT` for either field on the request path. This invariant is load-bearing for the post-likes "Read-site constraint" (rate limiter runs before any DB read) AND the chat-realtime-broadcast publish-skip (no extra round-trip in the publish path).
- Establish a precedent for documenting future principal fields additively rather than as silent code changes.

**Non-Goals:**

- Adding new fields to `UserPrincipal`. This change documents only the two fields already shipped.
- Modifying `auth-session/spec.md`. The session-lifecycle spec covers refresh-token rotation / reuse / logout; none of those produce a principal directly. Concentrating principal-field documentation in `auth-jwt` (where the validation block lives) preserves the existing spec division of concerns.
- Adding tests. Existing tests in `like-rate-limit`, `reply-rate-limit`, `chat-rate-limit`, and `chat-realtime-broadcast` already enforce the behavior these new Requirements describe. Adding test coverage for the spec text itself would be redundant.
- Closing the RevenueCat webhook follow-up item (verifying the future webhook bumps `users.token_version` on EXPIRATION/cancellation). That handler does not yet exist; verification belongs in the future `revenuecat-webhook` change. The third action item in the FOLLOW_UPS entry stays open under that future change's scope.
- Documenting the `userId` and `tokenVersion` fields as new Requirements. Both are already implicitly covered by the existing token-version revocation Requirement and the JWT round-trip scenario (line 45 mentions principal-with-id). A new Requirement for those would be redundant.

## Decisions

### Decision: MODIFY `auth-jwt` only, not `auth-session`

The follow-up entry suggested optionally cross-referencing in `auth-session` for capability-spanning clarity. We reject that addition.

**Rationale**: `auth-session` covers session lifecycle (refresh tokens, rotation, logout, version bump). It does NOT produce a principal. The principal is a property of `auth-jwt`'s validate block. Cross-referencing the principal field catalog from `auth-session` creates a duplication risk — if a future change adds a fifth field, both specs would need to update or drift. Single-source-of-truth in `auth-jwt` is cleaner.

**Alternative considered**: add a one-line "see auth-jwt for principal field catalog" Requirement to `auth-session`. Rejected as low-value: a maintainer reading auth-session already knows the principal is defined elsewhere (auth-session never references `UserPrincipal` by name); adding the reference is documentation theater.

### Decision: Three ADDED Requirements, not two

A natural shape would be two Requirements (one per field). We add a third covering the shared-load-site invariant.

**Rationale**: The "single auth-time SELECT, no per-handler refresh" property is load-bearing across multiple consumers. Capturing it as an explicit Requirement (with scenario text) makes it grep-able and lint-able by reviewers of future principal-reading handlers. Without the third Requirement, a reviewer evaluating a new handler that issues `SELECT subscription_status FROM users WHERE id = :caller` would have no spec text to point at — they'd have to grep KDocs or the post-likes "Read-site constraint" spec line. The third Requirement makes auth-jwt itself the canonical source for that constraint.

**Alternative considered**: fold the invariant into the field-declaration Requirements (one sentence each saying "loaded from the auth-time SELECT, do not refresh"). Rejected: weakens grep-ability and creates duplicate language across two Requirements.

### Decision: Field type literal in spec text (`String`, `Boolean`)

The Requirements name Kotlin types verbatim (e.g., `subscriptionStatus: String`).

**Rationale**: The repo's other capability specs already use Kotlin-typed declarations when describing the public API surface (e.g., `chat-realtime-broadcast` spec § Publish-side shadow-ban skip references `viewer.isShadowBanned: Boolean`). Aligning is cheap and improves grep-by-type for cross-spec consistency review.

**Alternative considered**: language-neutral phrasing ("a string-valued tier field"). Rejected: the field IS a Kotlin `String` in the only language the backend runs; faux abstraction.

### Decision: Out-of-scope for the RevenueCat webhook

The original FOLLOW_UPS entry has a third action item: "Confirm the RevenueCat webhook bumps `users.token_version` on EXPIRATION/cancellation events so JWT re-issuance picks up the new tier." We carve this out.

**Rationale**: The webhook handler does not exist in the backend today. Verifying behavior of a non-existent handler is impossible. The verification is the right thing to do — but it belongs in the Phase 4 `revenuecat-webhook` change's spec checklist (already noted on the FOLLOW_UPS entry). Trying to bundle it here would either (a) ship a vacuous "TBD when handler exists" Requirement, (b) preempt the Phase 4 design, or (c) require us to ALSO build the handler (massive scope creep). All three are wrong.

**How the carve-out is signaled**: the proposal § Impact explicitly notes the third action item stays open under the future Phase 4 change. The archive phase will delete the FOLLOW_UPS entry but the upcoming `revenuecat-webhook` change owns the residual verification.

## Risks / Trade-offs

[Risk: future principal-reading handler bypasses the auth-time SELECT and issues a fresh DB query for one of these fields.] → Mitigated by the new Requirement § "Both fields populated by the auth-time `users` row SELECT" with explicit scenario text; reviewer can cite the Requirement in PR review. A Detekt rule enforcing this is out of scope (would over-fit current writers); code review remains the primary defense. If the bypass recurs, file a separate `userprincipal-no-fresh-select-detekt-rule` follow-up.

[Risk: a future change adds a fifth `UserPrincipal` field without updating the spec.] → Mitigated by precedent: this change establishes the pattern that any field addition goes through an additive MODIFIES of `auth-jwt`. Future-maintainer-grep-ability around `UserPrincipal` in `auth-jwt/spec.md` makes the pattern visible.

[Risk: the spec text drifts from the actual `AuthPlugin.kt` definition over time.] → Low. The KDoc on the data class explicitly references the `auth-jwt-spec-debt-userprincipal-subscription-status` follow-up (which this change closes); after archive, a future maintainer adding a field will see no FOLLOW_UPS entry but will still read the KDoc and the spec — the spec is the canonical reference.

## Migration Plan

No migration. Pure spec-text addition. Archive phase syncs the canonical spec.

## Open Questions

None. All decisions resolved at proposal time.
