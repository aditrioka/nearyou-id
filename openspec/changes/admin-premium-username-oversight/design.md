## Context

The premium username customization backend (`premium-username-customization`, [#301](https://github.com/aditrioka/nearyou-id/pull/301), 2026-06-14) produces two admin-relevant artifacts and provides no surface to act on either, and its "borderline-candidate override" (`docs/06` / `docs/07`) was deferred:

1. A standing `moderation_queue` row (`trigger = 'username_flagged'`, `target_type = 'user'`, `target_id = <user id>`, `status = 'pending'`) inserted via `ON CONFLICT … DO NOTHING` whenever a candidate hits the profanity / UU-ITE pipeline. Today it records *that* a user was flagged but **not** the candidate string.
2. A `username_history` row per change (`old_username`, `new_username`, `changed_at`, `released_at = changed_at + 30 days`) that blocks the relinquished handle from re-claim while `released_at > NOW()`.

`docs/05` § Premium Customization Endpoint and the shipped spec both list "the admin username-change oversight (`username_history` viewer, borderline-candidate override, manual handle release)" as a deferred follow-on. `docs/07` § Premium Username Change Oversight, `docs/06` § Premium Username Customization Moderation, `docs/08` Phase 3.5 #24, and admin mockup frame 22 (`GET /admin/username-oversight`) are the canonical spec + visual sources.

This change delivers that oversight **end-to-end** — including the live-path coupling that makes "accept" mean "candidate passes on re-submit" (operator decision, 2026-06-16, expanding the originally-deferred scope). All schema it needs pre-exists EXCEPT the new override store: `username_history` (V3, `username_history_old_lower_idx`), `moderation_queue.resolution`/`trigger` CHECK values (V9), `admin_actions_log.action_type` unconstrained `VARCHAR(64)` (V16); the new `username_flag_overrides` table ships as **V23**.

## Goals / Non-Goals

**Goals:**

- An in-panel surface to (a) triage standing `username_flagged` flags (now showing the candidate), (b) browse `username_history` read-only, (c) force-release a handle from its 30-day hold.
- Make the admin's **accept** actually let the approved candidate through on the user's next `PATCH /api/v1/user/username` — via a **per-candidate, one-shot** override, never a per-user moderation bypass.
- Match the shipped admin idioms exactly (session-gated read; CSRF→write-role-gated, idempotent, atomic, per-admin-rate-limited, audit-logged writes).

**Non-Goals:**

- No per-user moderation exemption (Decision 2 — the override is candidate-scoped + one-shot).
- No direct username editing in the panel (usernames change only via the user-facing PATCH endpoint).
- No `anomaly_score` column / anomaly-scoring path (remains deferred to the anomaly-detection capability, Phase 4 #17).
- No new endpoint on `premium-username-customization` (it stays backend-only; only its moderation-gate behavior changes).
- The mockup-frame-22 "Hit" column (which moderation list matched) — not persisted by the flag insert, so omitted.

## Decisions

### Decision 1 — Dedicated `/admin/username-oversight/*` write endpoints, not an extension of the shared moderation-queue endpoint

The resolution + release actions get their own routes (`POST /admin/username-oversight/flags/{queue_id}/resolve`, `POST /admin/username-oversight/holds/{history_id}/release`) rather than extending `admin-report-queue`'s `POST /admin/moderation-queue/{id}/resolve`.

- **Why:** `admin-report-queue`'s endpoint already *explicitly rejects* `accept_flagged_username` / `reject_flagged_username` as "owned by the future Premium Username Change Oversight feature" — this change is that feature, on its own route, so **no MODIFIED capability is needed for `admin-report-queue`**. The domains differ in every operational dimension: distinct rate caps (10/hr & 5/hr vs the 20/hr destructive cap), distinct `action_type`s, no author/content enforcement, a `trigger`-scoped guard, and — uniquely here — the accept side-effect of writing an override.
- **Alternative considered:** extend the shared endpoint — rejected: mixes two unrelated resolution domains and re-opens a clean requirement.

### Decision 2 — Per-candidate, one-shot override via a dedicated `username_flag_overrides` table (the live coupling)

`accept_flagged_username` writes a row to `username_flag_overrides (id, user_id → users ON DELETE CASCADE, candidate, approved_by → admin_users ON DELETE SET NULL, approved_at, consumed_at, UNIQUE(user_id, candidate))`, candidate stored **normalized lowercase**. The live moderation gate consults it (non-consumed `(user_id, candidate)` match → skip the moderation rejection) and **consumes** it via a conditional, rows-affected-gated `UPDATE … SET consumed_at = NOW() WHERE user_id = ? AND candidate = ? AND consumed_at IS NULL` executed **inside** the existing `SELECT … FOR UPDATE` change transaction (not the earlier pre-lock read connection). The skip decision is re-validated under the lock — if the conditional consume affects zero rows (a concurrent same-user change already spent it), the candidate is re-moderated — so the one-shot guarantee holds atomically, not merely by virtue of "it runs in a transaction."

This change also retires the now-fulfilled deferral in `premium-username-customization`: its shipped `Backend-only scope (mobile UI, admin oversight, and anomaly-score effect deferred)` requirement is **RENAMED + MODIFIED** (delta) to drop the admin-oversight/override deferral (delivered here) while keeping the still-valid mobile-UI + anomaly-score deferrals — so the archived canonical spec is not self-contradicting.

- **Why a dedicated table + per-candidate + one-shot:**
  - *Per-candidate* (not per-user): an accept whitelists exactly the handle the admin judged (e.g. a legitimate Indonesian word matching UU-ITE in an unrelated sense — `docs/06`). A per-user flag-clear would be a **moderation bypass / abuse hole** (the user could then set *any* flagged handle) — rejected.
  - *One-shot* (`consumed_at`): the pass is spent on first successful use, so a stale approval can't be reused after the user changes away and back.
  - *Dedicated table* (not overloading `moderation_queue.notes` + `resolution` as the override store): the standing flag row is per-user and gets **overwritten/re-opened** by the next flagged attempt (Decision 6) — so it cannot also serve as a durable per-candidate approval. A separate table keeps approvals independent of subsequent attempts and gives clean one-shot semantics. `approved_by → admin_users ON DELETE SET NULL` satisfies critical invariant (admin-user FKs `ON DELETE SET NULL`).
- **Alternative considered:** store the approval on the `moderation_queue` row (`resolution = accept` + `notes = candidate`) and have the gate read that — rejected: fragile (a later flagged attempt's `ON CONFLICT DO UPDATE` overwrites a still-unused approval) and conflates display state with enforcement state.

### Decision 3 — Rate limits via the audit-log-COUNT pattern (reserved-usernames precedent), not the global destructive cap

Flag resolution 10/hour/admin, manual release 5/hour/admin (per `docs/07`), each sourced by `COUNT(*)` over `admin_actions_log` for the acting admin where `action_type = <the action>` and `created_at > NOW() - INTERVAL '1 hour'`, evaluated inside the write transaction (soft cap, ±1 concurrency tolerance).

- **Why:** feature-specific caps, exactly like `admin-reserved-usernames-editor`'s 100/hour cap — not the cross-cutting 20/hour `admin-destructive-action-rate-limit`. Index-served by `admin_actions_type_idx`. Reuses the `admin/ratelimit` package.
- **Alternative considered:** fold under the 20/hour destructive cap — rejected: `docs/07` specifies distinct 10/hr & 5/hr caps, and these are not user-state-destructive.

### Decision 4 — Frame-22 reconciliation: show the candidate, omit the "Hit" column

With Decision 6 persisting the flagged candidate in `moderation_queue.notes`, the flagged-candidate table **shows the candidate** (the admin needs it to judge a false positive and to know what `accept` approves). The frame-22 "Hit" column (which wordlist matched) is **omitted** — the flag row doesn't persist it. The candidate is rendered **in full** (HTML-escaped), not masked: an operator judging a UU-ITE false positive needs the whole string; the frame-22 masking (`b****t69`) is a deferred cosmetic.

### Decision 5 — Layering, read scope, and search index

- **Backend layering:** Routes → Service → Repository in a new `id.nearyou.app.admin.usernameoversight` package, matching every shipped admin feature package. The live-gate change lives in the existing `id.nearyou.app.user.UsernameChangeService`.
- **Read scope:** the `GET` is available to any valid admin session; only the two POSTs are write-role-gated. Admin-module raw reads of `moderation_queue` / `username_history` / `users` are lint-exempt.
- **Search:** `q` matches `old_username` via the V3 `username_history_old_lower_idx` `LOWER` index and `new_username` via a `LOWER(new_username)` comparison (no dedicated index — seq scan acceptable at admin cardinality, the `admin-block-registry` precedent).

### Decision 6 — The `username_flagged` insert re-opens the standing row and persists the candidate

The `premium-username-customization` flag insert changes from `ON CONFLICT … DO NOTHING` to `ON CONFLICT (target_type, target_id, trigger) DO UPDATE SET status='pending', resolution=NULL, resolved_by=NULL, resolved_at=NULL, notes=EXCLUDED.notes, created_at=NOW()`.

- **Why:** the prior `DO NOTHING` left a *resolved* flag in place forever, silently swallowing every later flagged attempt (the admin never saw new attempts). Re-opening with the latest candidate makes the queue accurate and the candidate visible to approve. One standing row per user is preserved (the `UNIQUE` still holds); only its freshness changes.
- **Trade-off:** if a user has an unused approval for `X` and then submits a different flagged `Y`, the standing row now shows `Y` (re-opened) — but the `X` approval lives in `username_flag_overrides`, independent of the queue row, so it is NOT lost (this is exactly why Decision 2 uses a separate table).

## Standards conformance

Per `docs/11-Engineering-Standards.md` § Pattern Registry, this change builds **only on already-registered patterns** and introduces no new one (no Pattern Registry amendment required):

- **Backend layering** (Routes → Service → Repository) — the registered admin-feature shape (`admin-report-queue`, `admin-reserved-usernames-editor`).
- **Admin auth + CSRF + role gate** — `authenticate(ADMIN_AUTH_NAME)`, the CSRF gate (`admin_csrf_violation`), `AdminRoleGate.requireWriteRole`.
- **Audit write** — one immutable `admin_actions_log` row per write via the shared writer.
- **Per-admin audit-log-COUNT rate limit** — `admin/ratelimit` + the `admin-reserved-usernames-editor` precedent.
- **Reference/state table** — `username_flag_overrides` follows the established small-reference-table pattern (`reserved_usernames`, `username_history`); operational `admin_app` grant out-of-band (no Flyway `GRANT`).
- **Admin viewer rendering** — keyset pagination (no OFFSET), HTML-escaping, HTMX partial-swap + plain-`GET` fallback; Pebble + HTMX + vendored CSS per `docs/11` § 3.6 (frame 22).

No deviation from any registered pattern; no second pattern introduced for an already-covered concern.

## Risks / Trade-offs

- **An accepted override is a deliberate moderation bypass for one handle** → if mis-approved, a borderline-profane handle goes live. *Mitigation:* per-candidate + one-shot + write-role-gated + rate-limited (10/hr) + audited (`username_flag_resolved` with the candidate in `after_state`); the operator sees the full candidate before approving.
- **Storing the flagged candidate in `moderation_queue.notes`** surfaces a possibly-profane string in the admin DB/UI → *Mitigation:* admin-only data (already the case for `moderation_queue`); rendered HTML-escaped; this is the intended review material (`docs/06`).
- **Re-opening the standing flag on every attempt** (Decision 6) could re-surface a flag the admin just rejected if the user immediately retries → *acceptable:* that IS a fresh attempt warranting fresh review; the `reject` decision is preserved in the audit log even though the row re-opens.
- **Override consume vs. concurrent change race** → the consume is a conditional, rows-affected-gated `UPDATE … WHERE consumed_at IS NULL` inside the `SELECT … FOR UPDATE` change transaction, with the skip decision re-validated under the lock; two concurrent same-user changes serialize on the per-user row lock and the override is consumed at most once (the loser is re-moderated). The `WHERE consumed_at IS NULL` is what enforces the one-shot — not merely "it runs in a transaction."

## Migration Plan

- **`V23__username_flag_overrides.sql`** — create the table (FKs to `users` ON DELETE CASCADE + `admin_users` ON DELETE SET NULL; `UNIQUE(user_id, candidate)`; `candidate VARCHAR(60)` matching the `username_history` column width (V3) — the app-layer candidate is ≤30 (`MAX_LENGTH`), comfortably under). No partial-index `NOW()` predicates (critical invariant). Migration applies cleanly on the integration-test Postgres (no `admin_app` dependency — grants are operational).
- **Operational grant:** add `username_flag_overrides` to the `admin_app` per-table grants in `dev/scripts/provision-admin-app-staging.sh` (SELECT/INSERT/UPDATE) and run it on staging (then prod with `PROJECT_OVERRIDE`). The main API role (migration owner) already has DML on public tables for the consume path.
- **Deploy:** standard `:backend:ktor` deploy (merge → staging auto-deploy applies V23 via `RUN_FLYWAY_ON_STARTUP`).
- **Rollback:** revert the PR; the code change reverts with it. The V23 table is additive and inert without the code (an orphaned empty table); no destructive down-migration needed (Flyway is forward-only here — a follow-up drop migration only if truly required).

## Open Questions

- **Override expiry.** v1 overrides are valid until consumed (no time expiry). If an approved-but-unused override should auto-expire (e.g. after 30 days), that is a small fast-follow; not required for the operator flow.
- **Resolved-flag visibility.** v1 shows pending flags only (frame 22). A `status` filter to review resolved flags / past decisions could be a small fast-follow.
