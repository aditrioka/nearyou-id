## Context

The sign-in 403 (`account_banned`) issued by `backend/ktor/src/main/kotlin/id/nearyou/app/auth/routes/AuthRoutes.kt` carries a limited `appeal_token` for both suspended (`is_banned = TRUE, suspended_until > NOW()`) and permanently-banned (`suspended_until IS NULL`) users, but no field distinguishing them. The mobile client (`SignInOutcome.Banned`, defined in `AuthRepository.kt`; `SignInViewModel.kt:80` sets `showAppealEntry = outcome == SignInOutcome.Banned`) therefore shows every banned user the permanent-ban "Hubungi support" copy (`signin_error_banned`) *and* the appeal entry — a contradiction. Three shipped specs already describe the intended split (`auth-signin` § Banned-user-blocked, `mobile-appeal` § permanent-ban support routing, `mobile-auth-signin` § error-code mapping which tracks the gap as #187). The server already derives the distinction server-side for appeals (`content-moderation-appeal`: `suspended_until IS NULL → permanent_ban`); it just never surfaces it at sign-in.

## Goals / Non-Goals

**Goals:**
- Surface the suspension-vs-permanent distinction on the sign-in 403 so the client can route correctly.
- Mobile: suspension → suspension copy + "Ajukan banding" appeal entry; permanent → "Hubungi support" copy, no entry.
- Close #391 (appeal-entry routing) and #187 (suspension-vs-permanent copy split) as one coherent vertical slice.

**Non-Goals:**
- The mid-session auth-challenge surface (`AuthPlugin.respondAuthChallenge`) — that is follow-up #208's surface, not the sign-in 403. This change only fixes the sign-in path; see Decision 3.
- An in-app appeal *form* for permanent bans — still deferred (support path only), per the existing `mobile-appeal` requirement.
- A suspension countdown timer UI — #208 territory; this change only carries the `suspended_until` value, it does not render a countdown.
- Any change to the appeal-token issuance, `refresh_tokens` behavior, or `token_version` semantics.

## Decisions

### Decision 1 — Carry `suspended_until` on the existing `account_banned` 403, not a new `account_suspended` code

The `mobile-auth-signin` error-code-mapping requirement previously *hinted* (in its #187 note) that the eventual mechanism would be a distinct `account_suspended` code with a `suspended_until` field. **This change supersedes that hint**: the 403 keeps code `account_banned` for both states and adds a nullable `suspended_until` field; the client branches on the field.

Rationale:
- The `auth-signin` "Banned user blocked at sign-in" contract is keyed on `is_banned = TRUE → 403 account_banned + appeal_token`, applied identically to both states. A separate `account_suspended` code would fork that contract (two codes, two appeal-token issuance paths) for no behavioral gain.
- The appeal-entry trigger in `mobile-appeal` is keyed on the `account_banned` 403. Keeping one code keeps that trigger intact; only the sub-state (read from `suspended_until`) gates whether the entry shows.
- The raw expiry is strictly more informative than a boolean/enum and is reused as-is by #208 (countdown) — one field, two consumers. Ban-type is derivable (`suspended_until == null ⇒ permanent`).
- Smaller wire churn: one additive nullable field vs a new error code mobile must map (and which would need its own no-fallthrough scenario).

The MODIFIED `mobile-auth-signin` requirement rewrites the stale #187 note to this mechanism so the spec and code agree (B.3 reconciliation — explicit supersede, not a silent divergence).

### Decision 2 — Mobile outcome shape

`SignInOutcome.Banned` is split to carry the distinction. The implementation MAY use either a single `Banned(suspendedUntil: Instant?)` variant or two variants (`Banned.Suspended(until)` / `Banned.Permanent`); the spec is written field-first (it asserts the resulting copy + entry from the 403 `suspended_until`, not the exact Kotlin shape) so the implementer picks the cleaner shape under the existing `SignInOutcome` sealed hierarchy. `AuthRepository.kt`'s 403 branch parses `suspended_until` alongside the existing `appealToken` stash; `SignInViewModel`'s `showAppealEntry` becomes "suspension sub-state only". No new state-holder/navigation/data pattern — this stays within the `SignInOutcome` / `AuthRepository` / `SignInViewModel` pattern already registered in `docs/11` § Pattern Registry (no deviation to declare).

### Decision 3 — Reconcile, do not duplicate, with #208

#208 wants `suspended_until` surfaced on the **auth-rejection / token-refresh** response (the mid-session `AuthPlugin.respondAuthChallenge` body), a different code path from the sign-in 403 (`AuthRoutes`). This change defines the `suspended_until` field name + ISO-8601 shape on the sign-in 403 only. When #208 is implemented it SHOULD mirror the identical field name/shape on the auth-challenge body so the two surfaces are consistent. This change does not touch `AuthPlugin`.

### Decision 4 — Net-new suspension sign-in copy

Only `signin_error_banned` (permanent/support copy) exists today. A net-new `signin_error_suspended` string is added to `:shared:resources` (suspension-appropriate, e.g. "Akun kamu sedang ditangguhkan sementara. Kamu bisa mengajukan banding."). All copy via Compose Multiplatform Resources (critical invariant: no hardcoded UI strings). `docs/03-UX-Design.md` § Suspension UX (≈line 269) describes an older "login succeeds + write-endpoint 403 + countdown modal" model that conflicts with the shipped session-terminating sign-in-403 reality; it is NOT used as the copy source and is flagged as stale for a separate docs reconciliation (filed as follow-up #420, adjacent to #377/#208) rather than rewritten here.

## Risks / Trade-offs

- **`suspended_until` exposure.** The field is a moderation-action expiry already shown to the actioned user via the appeal flow; it is the user's own action metadata, carries no other user's data and no spatial/PII payload. Low risk.
- **Suspension copy wording.** The exact `signin_error_suspended` string is a copy decision; the spec fixes the behavior (which string maps where), the wording is finalized at implementation and is trivially revisable.
- **Outcome-shape choice deferred to apply.** Leaving single-variant-vs-two-variants to the implementer is intentional flexibility; the field-first scenarios keep tests stable regardless of the chosen shape.
- **Stale docs/03 left in place.** Not rewriting `docs/03` § Suspension UX here keeps scope tight but leaves a known stale paragraph; mitigated by the explicit follow-up flag (B.3 case (b)).
