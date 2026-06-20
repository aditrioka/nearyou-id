## Why

Banned and suspended users currently have **no in-product recourse**. A 7-day-suspended user is silently 403'd on every write (auth-jwt `account_suspended`); a permanently-banned user is bounced at the login screen with "Hubungi support jika ini keliru" ([docs/03-UX-Design.md:270](../../../docs/03-UX-Design.md)) — but no support/appeal path is actually built. This is a due-process and user-trust gap: moderation actions (suspend / ban) are issued and audited, yet the actioned party can neither contest a mistake nor learn the outcome. [docs/08-Roadmap-Risk.md:510](../../../docs/08-Roadmap-Risk.md) Open Decision #2 commits an appeal workflow for Phase 3.5, and [docs/11-Engineering-Standards.md:149](../../../docs/11-Engineering-Standards.md) names the appeal-review workflow as the **sole unbuilt frame** on the otherwise-complete 23-frame admin board. With the moderation actions and the admin moderation tooling all shipped, the appeal loop is the missing counterpart that makes moderation legitimate, contestable, and auditable.

## What Changes

- **New `appeals` table** (Flyway **V31**) — ledger of user-submitted appeals against *account-level* moderation actions (7-day suspension / permanent ban), with a `pending → approved | rejected` status lifecycle and an audit linkage to the reviewing admin.
- **New `POST /api/v1/appeals` submission endpoint**, reachable by `is_banned` users (suspended *or* permanent) via a **ban-exempt authenticated realm** — it validates the JWT's `token_version` but does **not** apply the auth-jwt `account_banned` / `account_suspended` 403 short-circuit (without this, a banned user literally cannot reach the endpoint). Guards: eligibility = `is_banned = TRUE`; **one pending appeal per user** (partial-unique, no `NOW()` in the predicate); per-user submission rate-limit; `appeal_text` length-capped (1000 chars, content-length-guard invariant).
- **Shadow-ban exclusion (by design):** `is_shadow_banned`-only users are *not* `is_banned`, so no appeal is ever surfaced or accepted for them — preserving shadow-ban invisibility (the form's existence must never confirm the state). Enforced by the eligibility predicate, with an explicit negative-guard scenario.
- **New own-appeal-status read** — the user reads their latest appeal's status (pending / approved / rejected + decision reason) from Settings, so the outcome is surfaced without a proactive push.
- **New admin appeals-review queue** (`GET /admin/appeals` + detail) — paginated pending list; **approve** (clears `is_banned`, nulls `suspended_until`, reusing the shipped unban path) and **reject**, each writing one immutable `admin_actions_log` row (new `appeal_approved` / `appeal_rejected` action types); owner/admin gate + CSRF + destructive-action rate-limit. Shipped **unstyled** (admin UI is intentionally unstyled so far; the styled mockup frame is the documented gap, [docs/11:149](../../../docs/11-Engineering-Standards.md)).
- **New mobile "Ajukan Banding" Settings entry** + appeal form + status display, reachable by suspended users in their limited session (Bahasa Indonesia strings via `:shared:resources` CMP Resources). Makes the loop usable end-to-end.
- **Modified `auth-jwt`:** introduce the ban-exempt authenticated realm (validates `token_version`; does **not** short-circuit on `is_banned` / `suspended_until`), consumed only by the appeal-submission route.

No breaking changes.

## Capabilities

### New Capabilities
- `content-moderation-appeal`: the `appeals` data model + the ban-exempt submission endpoint (eligibility, shadow-ban exclusion, one-pending guard, rate-limit, length guard) + the own-appeal-status read.
- `admin-appeal-review`: the admin appeals-review queue + approve/reject actions wired to the unban path + immutable audit (`appeal_approved` / `appeal_rejected`).
- `mobile-appeal`: the mobile Settings appeal entry + submission form + status display (suspended-user-reachable).

### Modified Capabilities
- `auth-jwt`: add a **ban-exempt authenticated realm** — a route configuration that validates `token_version` (instant-revocation check preserved) but does NOT apply the `is_banned` / `suspended_until` 403 short-circuit. Used exclusively by `POST /api/v1/appeals`; all other authenticated routes keep the existing short-circuit unchanged.

## Impact

- **Code:** new backend `appeal` package (`AppealRoutes` → `AppealService` → `JdbcAppealRepository`); `V31__appeals.sql`; ban-exempt realm wiring in the auth configuration; admin route subtree + Pebble templates + two audit action types; mobile `screens/appeal` + a Settings entry + `:shared:resources` strings.
- **APIs:** `+ POST /api/v1/appeals`, `+ GET` own-appeal-status, `+ GET /admin/appeals` (+ approve / reject form actions).
- **DB:** `+ appeals` table (V31 — re-verify the next-free version at pre-merge; in-flight siblings hold V29×2 + V30, and parallel Flyway collisions are a known risk).
- **Reused (anti-patchwork):** the unban path (admin-user-moderation), `admin_actions_log` audit, rate-limit infra (Redis hash-tag key shape + `computeTTLToNextReset`), the auth-jwt validate block (extended, not duplicated).
- **Deferred — captured as explicit requirements in the spec, not dropped:** proactive in-app/FCM notification on an appeal decision (the own-status read is the MVP outcome surface); a permanent-ban in-app entry point (default per [docs/03:270](../../../docs/03-UX-Design.md): support-email path — design.md resolves the docs/08-vs-docs/03 tension).
- **Out of scope:** appeals against shadow-ban (never surfaced, by design); appeals against individual post auto-hide / report outcomes (this change is account-level actions only).
