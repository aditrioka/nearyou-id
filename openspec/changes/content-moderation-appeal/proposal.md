## Why

Banned and suspended users currently have **no in-product recourse**. A 7-day-suspended user is silently 403'd on every write (auth-jwt `account_suspended`); a permanently-banned user is bounced at the login screen with "Hubungi support jika ini keliru" ([docs/03-UX-Design.md:270](../../../docs/03-UX-Design.md)) — but no support/appeal path is actually built. This is a due-process and user-trust gap: moderation actions (suspend / ban) are issued and audited, yet the actioned party can neither contest a mistake nor learn the outcome. [docs/08-Roadmap-Risk.md:510](../../../docs/08-Roadmap-Risk.md) Open Decision #2 commits an appeal workflow for Phase 3.5, and [docs/11-Engineering-Standards.md:149](../../../docs/11-Engineering-Standards.md) names the appeal-review workflow as the **sole unbuilt frame** on the otherwise-complete 23-frame admin board. With the moderation actions and the admin moderation tooling all shipped, the appeal loop is the missing counterpart that makes moderation legitimate, contestable, and auditable.

## What Changes

- **New `appeals` table** (Flyway **V31**) — ledger of user-submitted appeals against *account-level* moderation actions (7-day suspension / permanent ban), with a `pending → approved | rejected` status lifecycle and an audit linkage to the reviewing admin.
- **New `POST /api/v1/appeals` submission endpoint**, reachable by `is_banned` users (suspended *or* permanent) via a **ban-exempt authenticated realm** — it validates `token_version` but does **not** apply the auth-jwt `account_banned` / `account_suspended` 403 short-circuit (without this, a banned user literally cannot reach the endpoint). The credential is a **limited-scope appeal token** the user obtains at sign-in (see the `auth-signin` modification), since suspension revokes their normal tokens. Guards: eligibility = `is_banned = TRUE` (else `409 no_actionable_moderation`); **one pending appeal per user** (partial-unique, no `NOW()` in the predicate; `409 appeal_already_pending`); per-user submission rate-limit; `appeal_text` length-capped (1000 chars, content-length-guard invariant).
- **Shadow-ban exclusion (by design):** `is_shadow_banned`-only users are *not* `is_banned`, so no appeal is ever surfaced or accepted for them — preserving shadow-ban invisibility (the form's existence must never confirm the state). Enforced by the eligibility predicate, with an explicit negative-guard scenario.
- **New own-appeal-status read** — the user reads their latest appeal's status (pending / approved / rejected + decision reason) from Settings, so the outcome is surfaced without a proactive push.
- **New admin appeals-review queue** (`GET /admin/appeals` + detail) — paginated pending list; **approve** (clears `is_banned`, nulls `suspended_until`, reusing the shipped unban path) and **reject**, each writing one immutable `admin_actions_log` row (new `appeal_approved` / `appeal_rejected` action types); owner/admin gate + CSRF + destructive-action rate-limit. Shipped **unstyled** (admin UI is intentionally unstyled so far; the styled mockup frame is the documented gap, [docs/11:149](../../../docs/11-Engineering-Standards.md)).
- **New mobile "Ajukan Banding" Settings entry** + appeal form + status display, reachable by suspended users in their limited session (Bahasa Indonesia strings via `:shared:resources` CMP Resources). Makes the loop usable end-to-end.
- **Modified `auth-jwt`:** introduce the ban-exempt authenticated realm (validates `token_version` + soft-delete gate; does **not** short-circuit on `is_banned` / `suspended_until`), consumed only by the appeal routes; and confine limited tokens — every standard realm rejects a `scope = "appeal"` token (401), so the limited token never reaches a normal route.
- **Modified `auth-signin`:** a banned/suspended sign-in (currently 403 with no tokens) additionally returns a **limited-scope `appeal_token`** (RS256, current `token_version`, `scope = "appeal"`, ≤1h TTL) so the actioned user has a credential for the appeal realm — without issuing any normal access/refresh token.

No breaking changes.

## Capabilities

### New Capabilities
- `content-moderation-appeal`: the `appeals` data model + the ban-exempt submission endpoint (eligibility, shadow-ban exclusion, one-pending guard, rate-limit, length guard) + the own-appeal-status read.
- `admin-appeal-review`: the admin appeals-review queue + approve/reject actions wired to the unban path + immutable audit (`appeal_approved` / `appeal_rejected`).
- `mobile-appeal`: the mobile Settings appeal entry + submission form + status display (suspended-user-reachable).

### Modified Capabilities
- `auth-jwt`: add a **ban-exempt authenticated realm** (validates `token_version` + soft-delete gate, skips ONLY the `is_banned` / `suspended_until` short-circuit; appeal routes only) **and** confine limited tokens — every standard realm rejects a `scope = "appeal"` token with 401. All other authenticated routes keep the existing short-circuit unchanged.
- `auth-signin`: a banned/suspended sign-in additionally issues a **limited-scope appeal token** (`scope = "appeal"`, current `token_version`, ≤1h TTL) in the 403 response body — no normal access/refresh token is issued. This is the credential a suspended user needs to reach the appeal realm (suspension revokes their normal tokens via the `token_version` bump).

## Impact

- **Code:** new backend `appeal` package (`AppealRoutes` → `AppealService` → `JdbcAppealRepository`); `V31__appeals.sql`; ban-exempt realm wiring + `scope = "appeal"` confinement on standard realms in the auth configuration; limited-appeal-token issuance in the sign-in path; admin route subtree + Pebble templates + two audit action types; mobile `screens/appeal` + a Settings entry + `:shared:resources` strings.
- **APIs:** `+ POST /api/v1/appeals`, `+ GET` own-appeal-status, `+ GET /admin/appeals` (+ approve / reject form actions).
- **DB:** `+ appeals` table (V31 — re-verify the next-free version at pre-merge; in-flight siblings hold V29×2 + V30, and parallel Flyway collisions are a known risk).
- **Reused (anti-patchwork):** the unban path (admin-user-moderation), `admin_actions_log` audit, rate-limit infra (Redis hash-tag key shape + `computeTTLToNextReset`), the auth-jwt validate block (extended, not duplicated).
- **Deferred — captured as explicit requirements in the spec, not dropped:** proactive in-app/FCM notification on an appeal decision (the own-status read is the MVP outcome surface); a permanent-ban in-app entry point (default per [docs/03:270](../../../docs/03-UX-Design.md): support-email path — design.md resolves the docs/08-vs-docs/03 tension).
- **Out of scope:** appeals against shadow-ban (never surfaced, by design); appeals against individual post auto-hide / report outcomes (this change is account-level actions only).
