## Context

The admin moderation hub is being built read→write in fast-follows: `admin-actions-log-viewer` (read) → `admin-user-moderation` (suspend/unban write, board frame 5) → `admin-report-queue` (read) → `admin-report-queue-resolution-actions` (in-row enforcement write). The next gap is board **frame 6**: a per-user **profile + history page** (`/admin/users/{id}`) that gives a moderator identity context and a record of prior actions, plus the `warning` action and the `±20/hr` destructive-action cap (Pre-Launch #9) that the report-queue-resolution change explicitly teed up.

The shipped `admin-user-moderation` Purpose pre-names the capability split this change follows: the browse page is `admin-user-management`; account-state actions (incl. `warning`) extend `admin-user-moderation`. The rate limit is cross-cutting (it must cap destructive actions on BOTH the user page and the report-queue resolution surface), so it is its own capability `admin-destructive-action-rate-limit` that the two action-bearing capabilities enforce.

**Mockup consult (docs/11 § 3.6, mandatory).** Board frame 6 (`dev/mockups/nearyou-admin-mockup.html`, `/admin/users/{id}`) was consulted for this proposal: profile block (identity + moderation state), the action bar (warning + the existing suspend/unban, with a visible destructive-quota chip "N/20 this hour"), and the history list. The history table styling follows **frame 7** (the audit-log viewer: Time/Admin/Action/Target/Reason/State-disclosure columns). Translation target is Pebble templates + HTMX fragment swaps + vendored vanilla CSS (design tokens lifted from the board `.frame` block), per docs/11 § 3.6; the frame-4b responsive contract applies (fluid layout, no fixed widths). The per-frame measurement annex (`dev/scripts/mockup-measure.sh`) is generated at implementation time, not committed.

## Goals

- Ship the per-user profile + merged action-history page as a new `admin-user-management` capability.
- Add the `warning` action to `admin-user-moderation`, mirroring the shipped suspend transaction shape (one audit row + one sanitized notification, atomic).
- Add a shared `±20/hr` per-admin destructive-action cap enforced across both the user-page actions and the report-queue destructive resolutions.
- Zero Flyway migration; zero new library.

## Non-Goals

- Standalone permanent-ban / shadow-ban actions on the user page (deferred; reachable via report-queue resolution).
- Premium Username Change Oversight writes (distinct Phase-4 capability; this change only reads `username_history`).
- Changing the existing suspend/unban behavior or the report-queue resolution behavior (only their pre-mutation rate-limit guard is added).

## Decisions

### D1 — Capability split: new `admin-user-management` (page) + new `admin-destructive-action-rate-limit` (cap) + MODIFY `admin-user-moderation` & `admin-report-queue`

The shipped `admin-user-moderation` Purpose names `admin-user-management` for the browse page and says account-state actions (warning) extend `admin-user-moderation` — followed verbatim. The rate limit is cross-cutting and shipped per the report-queue change's recommendation as its own capability rather than buried in one consumer; both `admin-user-moderation` and `admin-report-queue` ADD an enforcement requirement that references it. This keeps the cap's behavior specified once and applied consistently (the "Bundle full enforcement" scope chosen by the operator — a partial cap covering only the user page would leave the report-queue destructive resolutions uncapped, an inconsistency worse than deferring).

### D2 — Rate-limit substrate: COUNT over `admin_actions_log`, not Redis

Frame 6 poses "Redis vs COUNT `admin_actions_log`" as a design decision. **Chosen: COUNT over `admin_actions_log`.** Rationale: (a) the immutable audit trail already records every destructive action with `admin_id` + `created_at` + the action identity — it IS the natural rate-limit ledger, so no second source of truth can drift from it; (b) no new infra coupling — the admin panel does not currently depend on Redis, and adding it for a solo-to-few-admin ±20/hr soft cap is unjustified; (c) no migration — `admin_actions_type_idx (action_type, created_at DESC)` (V16/V17) already narrows the scan, and admin-action volumes are tiny. The guard query:

```sql
SELECT COUNT(*) FROM admin_actions_log
WHERE admin_id = ?
  AND created_at > NOW() - INTERVAL '1 hour'
  AND (
    action_type IN ('user_warned', 'user_suspended')
    OR (action_type = 'moderation_queue_resolved'
        AND after_state ->> 'resolution' IN ('suspend_author_7d', 'ban_author', 'shadow_ban_author'))
  );
```

Destructive report-queue resolutions all log as `moderation_queue_resolved` (shared with `keep`/`hide`), so they are isolated by the `after_state ->> 'resolution'` predicate — a small JSONB read, acceptable at admin volume. `≥ 20` → reject. **Rejected alternative (Redis sliding window):** cleaner counting but adds a runtime dependency the admin panel otherwise avoids, and risks ledger/Redis divergence.

### D3 — Destructive set = user-punitive actions only

Counted: `warning`, `suspend`, `permanent ban` (`ban_author`), `shadow ban` (`shadow_ban_author`). **Not** counted: `unban` (restorative), content `keep`/`hide` (content visibility, not user-punitive), report `decision` bookkeeping (`actioned`/`dismissed`), `dismiss`, and login. Feature-flag toggles have a separate `5/hr` limit (docs/07 § Security) — out of scope here. This matches "Rate limit destructive actions: 20/hour per admin" (docs/07 § Security, docs/08 Pre-Launch #9) interpreted as the punitive account-state actions.

### D4 — Concurrency posture: soft cap, ±1 tolerance

The COUNT-then-act guard has a benign race: two concurrent requests at count 19 can both pass and both write, yielding 21. For an abuse-prevention soft cap on a solo-to-few-admin panel this is acceptable and explicitly NOT treated as a hard security boundary; no `SELECT … FOR UPDATE` serialization is added for the cap (the destructive action's own transaction integrity is unaffected). Documented so reviewers don't flag it as a bug.

### D5 — Warning = audit row + sanitized notification, reusing `account_action_applied`; no state mutation

The warning mirrors the shipped suspend transaction (`UserModerationRepository`): ONE transaction, one `admin_actions_log` row (`action_type = 'user_warned'`, attributed to the acting `AdminPrincipal`, never the `system` V18 sentinel; `before_state`/`after_state` record the warning issuance), one notification `INSERT INTO notifications (..., type='account_action_applied', body_data)` with `body_data.action_type = 'warning'`. Reusing `account_action_applied` (already in the `notifications.type` CHECK) avoids a migration. The admin's free-text reason is stored in the audit row only and is NEVER placed in `body_data` / echoed to the user — the same discipline as suspend's fixed reason code. The warning sets no `users` moderation column.

### D6 — History merge: two ordered reads, not a SQL UNION

The page reads `admin_actions_log` (target rows) and `username_history` separately (two parameterized queries) and renders them as two sections (or an interleaved time-ordered list at the template level), rather than a SQL `UNION` over heterogeneous shapes. Simpler, each retains its native columns, and the audit-log read reuses the shape `AdminActionsLogRepository` already produces. Newest-first.

### D7 — Profile read repository is its own seam

A new `admin/usermanagement/UserProfileRepository` owns the identity + state + history reads (single responsibility), distinct from `UserModerationRepository` (which owns the state-mutating transactions). The profile GET route is read-only — it writes no `admin_actions_log` row and mutates nothing (mirrors the `admin-user-moderation` GET contract).

## Standards conformance (docs/11 § Pattern Registry)

Builds on the registered patterns — **no new pattern, no docs/11 amendment task**:

- **Backend layering (§3.1):** route → repository → JDBC; the warn + rate-limit guard run inside repository transactions; routes do CSRF→role→parse→repo→outcome mapping exactly as `AdminUserModerationRoute` / `AdminReportResolutionRoute` do.
- **Admin auth seam:** `AdminAuthPlugin` (session) + `AdminCsrfGate` (CSRF-before-role, `formParametersAfterValidation`) + `AdminRoleGate.requireWriteRole` on `/warn`, reused unchanged.
- **Audit + notify transaction shape:** the warn transaction reuses `UserModerationRepository`'s suspend pattern (one audit row + one sanitized `account_action_applied` notification, atomic, human-admin attribution).
- **Render pattern:** `admin-panel-scaffold` base layout + HTMX fragment swap + plain-`GET` no-JS fallback, mirroring the audit-log viewer; Pebble autoescape on all values.
- **UI substrate:** the admin mockup board (docs/11 § 3.6) governs look/layout; behavior governed by this spec + docs/07.

## Risks / Trade-offs

- **Scope size.** This bundles a new page, a new action, and a cross-cutting cap (4 capabilities). Mitigation: each capability is independently specified and tested; the cap reuses the existing audit ledger (no new infra). Flagged for review.
- **Cross-surface edit.** Enforcing the cap on `admin-report-queue` touches the just-shipped resolution repository. Mitigation: the change is purely additive (a pre-mutation guard + a new `RateLimited` sealed-outcome case mapped to an inline message); existing resolution behavior is unchanged, with a regression test asserting non-destructive resolutions stay uncapped.
- **JSONB predicate cost.** The `after_state ->> 'resolution'` filter is unindexed. Mitigation: admin-action volume is tiny and the `(action_type, created_at)` index already bounds the scan to one admin-hour; acceptable.
- **±1 cap overshoot** under concurrent requests (D4) — accepted soft-cap posture, documented.

## Open Questions

- None blocking. The substrate (D2) and destructive set (D3) are decided; the page's exact history-interleave vs two-section layout (D6) is a template detail resolved against the frame-6/frame-7 measurement annex at implementation time.
