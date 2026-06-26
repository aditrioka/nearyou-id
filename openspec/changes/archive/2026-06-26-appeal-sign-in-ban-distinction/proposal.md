## Why

The sign-in 403 (`account_banned`) issues a limited appeal token to both 7-day-suspended and permanently-banned users but carries **no field distinguishing the two**, so the mobile app shows every banned user the same permanent-ban "Hubungi support" copy (`signin_error_banned`) **and** the in-app "Ajukan banding" appeal entry — a contradictory pairing. `content-moderation-appeal` design D7 wants a suspended user routed to the in-app appeal form (with suspension-appropriate copy) and a permanently-banned user routed to the "Hubungi support" path (no form). Three shipped specs already describe this split (`mobile-appeal` § permanent-ban support routing; `auth-signin` § banned-user blocked at sign-in; `mobile-auth-signin` § error-code mapping, which explicitly tracks the gap as #187), but it is unimplementable without a wire discriminator — even though the server already derives the distinction (`content-moderation-appeal`: `suspended_until IS NULL → permanent_ban`). This change ships the missing slice, closing **#391** (appeal-entry vs support routing) and **#187** (suspension-vs-permanent copy split) — the same backend differentiation.

## What Changes

- **Backend:** add a nullable `suspended_until` (ISO-8601 timestamp) field to the 403 `account_banned` sign-in response body. `null` ⇒ permanent ban; a future timestamp ⇒ 7-day suspension. The appeal-token issuance and the "no `refresh_tokens` row" behavior are **unchanged**.
- **Mobile:** parse `suspended_until` from the 403 body, split the single `SignInOutcome.Banned` into a suspension-vs-permanent distinction, and branch `SignInScreen` — suspension → a new suspension-specific banner string + the existing "Ajukan banding" appeal entry; permanent → the existing `signin_error_banned` "Hubungi support" copy with **no** appeal form.
- **New copy:** one net-new suspension sign-in banner string in `:shared:resources` (only the permanent-ban `signin_error_banned` exists today). The appeal-entry copy already exists.
- **Field choice:** `suspended_until` (the raw expiry) is chosen over a `ban_type` enum because the same field also feeds follow-up #208 (suspension countdown); ban-type is derivable client-side (`suspended_until == null` ⇒ permanent).
- **Mechanism choice:** the 403 keeps code `account_banned` for both states + adds the `suspended_until` field, rather than the distinct `account_suspended` code the `mobile-auth-signin` spec previously *hinted* (#187 note). Rationale in `design.md`; this preserves the existing `account_banned`-carries-appeal-token contract and the appeal-entry trigger, and the MODIFIED `mobile-auth-signin` requirement updates that note to the chosen mechanism.
- No new database column and **no Flyway migration** — `users.suspended_until` already exists.
- Not in scope: follow-up #208's separate surface (the mid-session auth-challenge in `AuthPlugin`). This change defines the `suspended_until` field shape on the sign-in 403 only; #208 will mirror the same field name on the auth-challenge body later.

## Capabilities

### New Capabilities

_(none — this is a behavioral refinement of three shipped capabilities)_

### Modified Capabilities

- `auth-signin`: the 403 `account_banned` sign-in response additionally carries a nullable `suspended_until` timestamp distinguishing a suspension from a permanent ban.
- `mobile-auth-signin`: the 403 error-code mapping is updated — the single `Banned` outcome is split into suspension vs permanent (parsed from the 403 `suspended_until`), each mapped to its own copy + entry, and the stale #187 note (which hinted `account_suspended`) is replaced with the shipped `account_banned` + `suspended_until` mechanism.
- `mobile-appeal`: the permanent-ban support-routing requirement becomes wire-backed and implemented — a permanently-banned user is shown the support copy (no appeal entry), a suspended user is shown the appeal entry, branched on the new field.

## Impact

- **Backend:** `auth-signin` route + the banned 403 response DTO in `backend/ktor/src/main/kotlin/id/nearyou/app/auth/routes/AuthRoutes.kt`; backend sign-in flow tests.
- **Mobile:** `SignInOutcome` + the 403 parse in `mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthRepository.kt`; `SignInViewModel` / `SignInScreen` branch; one net-new suspension banner string in `:shared:resources`; mobile sign-in + appeal tests.
- **Specs:** delta files for `auth-signin`, `mobile-auth-signin`, `mobile-appeal`.
- **Follow-ups:** closes #391 and #187; aligns the `suspended_until` field shape that #208 will reuse on the auth-challenge surface. Notes `docs/03-UX-Design.md` § Suspension UX (line ~269) as stale vs the shipped session-terminating sign-in-403 reality — filed as follow-up #420 (adjacent to #377/#208), not rewritten here.
- No schema/migration, no new dependency, no new architectural pattern.
