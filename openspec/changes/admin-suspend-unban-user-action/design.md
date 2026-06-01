## Context

The admin panel today is a read-only surface: `admin-panel-scaffold` mounts the `/admin/` subtree, `admin-login-argon2-totp` gates it behind an `__Host-admin_session` cookie + per-session CSRF token + `AdminPrincipal(admin_id, role)`, and `admin-actions-log-viewer` renders the `admin_actions_log` audit trail. There is **no write action** — an admin cannot suspend, unban, or otherwise change a user's account state. This change adds the first one.

The ban/suspension data model already exists (`users.is_banned BOOLEAN NOT NULL DEFAULT FALSE`, `users.suspended_until TIMESTAMPTZ` nullable, `users_suspended_idx` partial index — `docs/05-Implementation.md` § users schema). The automated `suspension-unban-worker` already performs the elapse-driven `(is_banned, suspended_until) → (FALSE, NULL)` flip in a batch CTE, attributing audit rows to the `system` sentinel (`system-actor` capability). The `admin_actions_log` table (V16) takes a free-string `action_type VARCHAR(64)` (no CHECK enum), so new action types need no migration. The `AdminAuditLogger` already centralizes parameterized audit-row writes for the auth path.

So this change is almost entirely composition over shipped primitives: a route, a repository that does one guarded `UPDATE` + one audit `INSERT` atomically, two Pebble templates, and two new `AdminAuditLogger` methods. **No Flyway migration, no new dependency.**

## Goals / Non-Goals

**Goals:**
- Ship the admin panel's first state-changing action: suspend (7-day) + manual unban, role-gated and CSRF-gated.
- Make every action atomically auditable to the acting human admin in `admin_actions_log`.
- Establish reusable patterns for the next admin writes: the role gate, the atomic UPDATE-plus-audit transaction, and the `{is_banned, suspended_until}` before/after-state shape.
- Compose cleanly with `suspension-unban-worker` (manual unban is its human-triggered sibling) without duplicating or modifying its spec.

**Non-Goals:**
- Permanent ban, shadow ban (`is_shadow_banned`), and "warning" actions (separate future actions on the same capability).
- Report-Queue-driven moderation (suspend-from-queue) and the full User Management search / profile / history page.
- FCM push on `account_action_applied` (in-app notification only; push deferred — D2).
- Per-admin destructive-action rate-limiting ("20/hour per admin", `docs/07` § Security) — deferred (D11).
- WebAuthn step-up (multi-admin period per `admin-login`).
- Any change to the automated worker, the system sentinel, or the audit-log viewer.

## Decisions

### D1 — New capability `admin-user-moderation` (domain home), not a change-named capability
The repo's convention is **capability = durable domain noun, change = action phrase** (`admin-login` ← `admin-login-argon2-totp`; `system-actor` ← `system-actor-and-worker-audit-rows`). `admin-user-moderation` is the durable home for admin-initiated account-state moderation. This change adds only the **suspend** + **manual unban** requirements; future ban / shadow-ban / warning actions extend the same capability with ADDED requirements rather than spawning sibling capabilities. The shipped spec scope equals the change scope (it does not pre-declare ban/shadow-ban requirements) — the broader *name* is intentional domain framing, not scope creep.
- *Alternative considered:* a tightly-named `admin-suspend-unban` capability. Rejected — would force ban/shadow-ban into awkwardly-named siblings or a later rename; the domain-noun convention is established.

### D2 — Route shape: `GET /admin/users` lookup + `POST /admin/users/{id}/suspend` + `POST /admin/users/{id}/unban`
A single `GET /admin/users?q=<uuid|username>` renders the lookup form and (when `q` resolves) the target user's current moderation state + suspend/unban controls. Two `POST` routes carry the actions. This mirrors `admin-actions-log-viewer`'s HTMX-partial-swap + plain-`GET` progressive-enhancement posture (branch on the `HX-Request` header), so the surface works without JavaScript and the filtered URL stays shareable. Both `POST` handlers redirect (303) / `HX-Redirect` back to `GET /admin/users?q={id}` on success so the admin sees the updated state.
- *Alternative considered:* `GET /admin/users/{id}` detail page. Rejected for the MVP lookup — keeps a single entry route and avoids a "user not found" hard-404 page; the `?q=` form degrades to an inline "no user" message.

### D3 — Role gate: `owner` / `admin` / `moderator` may act; `read_only` is rejected (403)
Suspend/unban is core moderator work, so the three operational roles may act; `read_only` is view-only and is rejected with **HTTP 403** (not a redirect — the admin is authenticated, just unauthorized; a redirect would falsely imply a session problem). This is the **first role-gated admin write**, so it introduces a small reusable guard (`AdminPrincipal.role ∈ allowed`, checked at the top of each state-changing handler, after `validateCsrf`). The contrast with `admin-actions-log-viewer` (readable by ALL roles incl. `read_only`) is deliberate: reads are universal, writes are gated.
- *Alternative considered:* `owner`/`admin` only (mirroring the chat-redaction precedent's `role IN ('owner','admin')`). Rejected — chat redaction is a rarer, higher-severity PII action; routine suspend/unban is exactly what a `moderator` role exists to do. (Open Question OQ1 surfaces this for review.)

### D4 — Atomic UPDATE + audit INSERT in one transaction, owned by `UserModerationRepository`
The user `UPDATE` and its `admin_actions_log` `INSERT` execute in a **single JDBC transaction** (one connection, `autoCommit=false`, commit at end, rollback on any exception). If the audit `INSERT` fails, the user UPDATE rolls back — there is never a state change without its audit row, nor an audit row without the state change. This matches the `suspension-unban-worker` atomicity contract. It means the repository owns the transaction rather than calling `AdminAuditLogger.insert(...)` (which opens its own connection) — a deliberate divergence from the login/logout/CSRF audit path, where each event is standalone with nothing to be atomic against. The two new `AdminAuditLogger` methods (`logUserSuspended` / `logUserUnbanned`) therefore accept an optional `Connection` (or the repo inlines the same parameterized INSERT) so the audit write joins the repo's transaction.
- *Alternative considered:* reuse `AdminAuditLogger.insert`'s own-connection write (non-atomic). Rejected — a suspend that isn't logged (or a log of a suspend that didn't commit) is exactly the integrity failure the audit trail exists to prevent.

### D5 — `action_type` vocabulary: `user_suspended` / `user_unbanned`
Target-descriptive snake_case, consistent with the existing log vocabulary (`admin_login_success`, `system_unban_applied`, `reserved_username_added`, `admin_chat_redaction`). Distinct from the worker's `system_unban_applied` so the audit log cleanly separates human-triggered from machine-triggered transitions when filtering by `action_type`.
- *Alternative considered:* `admin_suspend_applied` / `admin_unban_applied` (actor-prefixed, parallel to `system_unban_applied`). Rejected — the `admin_id` column already records the actor; a target-descriptive `action_type` reads better in the viewer and matches the `reserved_username_*` / target-first style.

### D6 — `before_state` / `after_state` built in Kotlin (`{is_banned, suspended_until}`)
The before/after JSON is constructed in Kotlin via kotlinx.serialization (matching the existing `AdminAuditLogger` pattern) and passed as `?::jsonb`: `before_state = {"is_banned": <prev bool>, "suspended_until": <prev ISO-8601 instant | null>}`, `after_state = {"is_banned": <new>, "suspended_until": <new | null>}`. This intentionally differs from the worker's Postgres-native `jsonb_build_object(...)` form (which renders `suspended_until` in Postgres timestamptz JSON form). That's acceptable — different writers, different code paths; `admin-actions-log-viewer` HTML-escapes and renders whatever JSON is present, and this change's tests assert the Kotlin-written shape. The `suspended_until` value is serialized as the ISO-8601 `Instant.toString()` (e.g. `2026-06-09T21:00:00Z`).

### D7 — Suspend semantics + guards
Suspend sets `is_banned = TRUE`, `suspended_until = NOW() + INTERVAL '7 days'` for the target user. Guards (each a spec scenario):
- **Soft-deleted target** (`deleted_at IS NOT NULL`): rejected — no state change, no audit row, informational message. A tombstoned account is not moderated.
- **Already permanently banned** (`is_banned = TRUE` AND `suspended_until IS NULL`): rejected — suspending would silently *downgrade* a permanent ban to a 7-day window. The admin must not weaken a permanent ban via the suspend control.
- **Already suspended** (`is_banned = TRUE` AND `suspended_until > NOW()`): allowed; the clock resets to `NOW() + 7d`, and `before_state` captures the prior `suspended_until`. (A moderator re-suspending is extending, which is intended.)
- **Active user** (`is_banned = FALSE`): the normal path.

### D8 — Manual unban semantics
Unban sets `is_banned = FALSE`, `suspended_until = NULL` — lifting **both** a time-bound suspension and a permanent ban (the admin's deliberate override). Guards:
- **Not currently banned** (`is_banned = FALSE`): no-op — no state change and **no audit row written** (the log is not polluted with non-events); the handler returns an informational "user is not banned" message. This keeps `admin_actions_log` a record of actual state transitions, consistent with the worker's "zero-flip run writes zero audit rows" contract.
- **Soft-deleted target**: unban is still permitted (lifting a ban on a tombstoned-but-banned row is harmless and may be a legitimate cleanup), but documented; alternatively rejected. *Default: permit, no special-case* — the row's `deleted_at` is unchanged either way.

### D9 — `account_action_applied` in-app notification on suspend; none on unban; FCM deferred
On a successful **suspend**, insert one `notifications` row of the existing `account_action_applied` type (catalog: "Admin action on user", `body_data = {action_type, reason, suspended_until}` per `docs/05-Implementation.md`) for the suspended user — the documented user-facing signal. On **manual unban**, insert **no** notification: this mirrors `suspension-unban-worker` design D5 (no `account_action_lifted` type exists, and the `account_action_applied` copy "Akun kamu menerima tindakan moderasi" does not fit a positive restoration). The notification `INSERT` joins the same atomic transaction as the UPDATE + audit row. **FCM push is deferred** to keep this change scoped as the first write action; the in-app `notifications` row is the in-band signal (the user sees it on next app open / notifications-list fetch). (OQ2 surfaces both the FCM deferral and the "include the in-app insert at all" question for review.)

### D10 — Minimal target-user lookup (UUID primary, exact username secondary)
`GET /admin/users?q=` resolves `q` as a user UUID first; if not a UUID, it attempts an **exact** `username` match (parameterized). No fuzzy / prefix / paginated search — that is the future full User Management page. A non-resolving `q` renders an inline "no matching user" state, not a 404. All lookups are parameterized queries; the admin module is exempt from the `visible_*`-view lint, so a direct `FROM users` read is permitted here.

### D11 — Defer per-admin destructive-action rate-limiting
`docs/07` § Security specifies "Rate limit destructive actions: 20/hour per admin". This change **defers** that limiter to a focused follow-up (logged in `FOLLOW_UPS.md`) rather than inlining it, because a correct admin-action limiter needs its own substrate decision (Redis-backed per-admin counter vs a `COUNT(*) admin_actions_log WHERE admin_id=? AND created_at > NOW()-1h` DB check) that would balloon this "first write action" change. The interim mitigations (CSRF, 30-min idle session, 8-hour absolute session cap, full audit trail, IAP network gate) bound the blast radius. (OQ3.)

## Risks / Trade-offs

- **Compromised/hostile admin session mass-suspends users (no rate limit yet)** → Mitigated by CSRF + 30-min idle + 8-hour absolute session cap + IAP network gate + complete audit trail (every action attributable + reversible via unban). The 20/hour limiter is deferred but explicitly tracked (D11), and a hostile *authenticated* admin is largely outside this change's threat model (IAP + TOTP gate who gets a session).
- **Suspend silently weakening a permanent ban** → Mitigated by the D7 guard rejecting suspend on `is_banned=TRUE AND suspended_until IS NULL`, with a dedicated spec scenario.
- **Non-atomic state vs audit (a suspend not logged, or a logged suspend that didn't commit)** → Mitigated by D4's single-transaction design + a scenario asserting an injected audit-INSERT failure rolls the user UPDATE back.
- **Transition-semantics drift from the worker** (two code paths flipping the same columns could diverge over time) → Mitigated by both referencing the canonical `docs/05-Implementation.md` § suspension SQL + a design cross-reference; they are intentionally separate (batch-CTE machine path vs single-user human path) and there is no shared code to keep in lockstep, only the column contract.
- **`account_action_applied` copy mismatch** (the existing Bahasa copy is moderation-negative) → Fits suspend (a negative action); unban deliberately gets no notification (D9), avoiding the copy mismatch entirely.

## Migration Plan

- **No Flyway migration.** Pure code + templates. `users` columns, `users_suspended_idx`, `admin_actions_log` (free `action_type`), and the `account_action_applied` notification type all already exist.
- **Deploy**: ships in the same Cloud Run image as the rest of `:backend:ktor`. The `/admin/` subtree is already mounted in all envs behind the `admin-login` auth gate (the `KTOR_ENV` scaffold guard was removed by Admin #3), so no mount/env change.
- **Pre-archive smoke (feasible)**: a manual staging branch deploy (`gh workflow run deploy-staging.yml --ref admin-suspend-unban-user-action`) lets the suspend/unban flow be exercised against staging Supabase before the squash-merge, per the project's pre-archive-smoke convention. A smoke script logs in as the seeded admin, suspends a synthetic user, asserts the `admin_actions_log` row + `users.suspended_until`, then unbans.
- **Rollback**: revert the change's commits — no schema state to unwind. Any `user_suspended` / `user_unbanned` rows already written remain valid audit history (the worker / a future unban can still flip affected users back).

## Open Questions

- **OQ1 (role gate breadth, D3)**: allow `moderator` to suspend/unban (default), or restrict to `owner`/`admin` like chat redaction? — surface to review.
- **OQ2 (notification scope, D9)**: ship the in-app `account_action_applied` insert on suspend now (default), or defer the entire notification to a follow-up alongside FCM push? — surface to review.
- **OQ3 (rate-limit timing, D11)**: defer the 20/hour destructive-action limiter (default), or include a minimal DB-count guard in this change? — surface to review.
- **OQ4 (route paths, D2)**: confirm `GET /admin/users` + `POST /admin/users/{id}/suspend|unban` vs a `/admin/users/{id}` detail-page shape — low-stakes, settle at apply time.
