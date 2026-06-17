## Context

`rejected_identifiers` (V3) is the anti-abuse blocklist the age gate writes on under-18 rejection (`reason = age_under_18`) and the attestation path writes on persistent failure (`reason = attestation_persistent_fail`). Each row is a one-way SHA-256 of a Google/Apple identifier — no raw PII (`docs/06-Security-Privacy.md` § Under-18 Bypass Prevention). The shipped `admin-rejected-identifiers-viewer` (PR #156) renders this table read-only; its spec **explicitly defers** the manual support-clear write action by name (`admin-rejected-identifiers-clear-action`) and tracking issue [#190](https://github.com/aditrioka/nearyou-id/issues/190) holds it open. Until this change, the only way to clear a falsely-rejected legitimate adult is an out-of-band raw-SQL `DELETE` (per the ops runbook + the staging DB-access memory).

The admin panel already ships every dependency this change needs: `admin-login` (Argon2id + TOTP → `__Host-admin_session` + per-session CSRF gate, `authenticate(ADMIN_AUTH_NAME)`), `admin-panel-scaffold` (Pebble base + HTMX + `/admin/*`), the viewer itself (`AdminRejectedIdentifiersRepository` + `AdminRejectedIdentifiersRoute`), and a proven write-action template in `admin-reserved-usernames-editor` (CSRF→role→parse ordering, reason validation, audit-log-sourced per-admin rate cap). This change clones that write-action template onto the rejected-identifiers surface.

## Goals / Non-Goals

**Goals:**

- A single-row clear (`POST /admin/rejected-identifiers/{id}/clear`) that hard-`DELETE`s the row and writes exactly one immutable `admin_actions_log` row in the same transaction, with the cleared row preserved in `before_state`.
- Owner/admin-only write gate; CSRF gate; required, length-bounded reason; idempotent not-found; a dedicated per-admin trailing-hour rate cap.
- A per-row clear control in the existing viewer table (confirm + reason), HTMX row-swap on success, plain-`POST` fallback, all output escaped.
- Zero Flyway migrations.

**Non-Goals:**

- Any change to the read view's behavior (any-admin-role access, filters, keyset pagination, count summary) — untouched.
- Bulk clear, soft-delete/tombstone, or an "undo" (the audit `before_state` is the trail; a re-rejection re-inserts naturally).
- Resolving `identifier_hash` to a raw identifier (impossible — one-way hash, none stored).
- Adding `rejected_identifier_cleared` to the shared `admin-destructive-action-rate-limit` punitive set (see D1).

## Decisions

### D1: Dedicated per-admin rate cap (10/hr), NOT the shared destructive limiter — **KEY / FLAGGED DIVERGENCE**

Rate-limit the clear at a **dedicated 10 clears per admin per trailing one-hour window**, counted from `admin_actions_log` rows with `action_type = 'rejected_identifier_cleared'` and `created_at > NOW() - INTERVAL '1 hour'` for the acting `admin_id` — the same audit-trail-as-ledger mechanism `admin-reserved-usernames-editor` uses (no second source of truth), checked inside the same JDBC transaction as the gated action (soft cap, ±1 concurrency tolerance, never `FOR UPDATE`).

**This diverges from the viewer's design D3**, which (authored before `admin-destructive-action-rate-limit` shipped, when that limiter was "still in FOLLOW_UPS.md") said to rate-limit the clear "via the `admin-destructive-action-rate-limit` limiter". **Why diverge:** the *shipped* `admin-destructive-action-rate-limit` spec defines its set as **user-punitive** actions only — warn / suspend / ban / shadow-ban / chat-redaction — and **explicitly excludes restorative actions** (unban, content keep/hide, bookkeeping). Clearing a rejected identifier is restorative-but-sensitive (it re-opens signup for a previously-blocked identity, like an unban), so it does **not** belong in the punitive set. Folding it in would require a cross-capability MODIFY of that spec and muddy its "punitive" semantics for every reader. A dedicated cap keeps this change self-contained and semantically honest.

**Cap value (10/hr):** a proposed default for a rare, sensitive support op — an order of magnitude below reserved-usernames' 100/hr (bulk-CSV surface) and on par with the `Premium Username Change Oversight` write caps (10/hr resolution, 5/hr manual release, `docs/07`). **Tunable** — flagged for review/operator input; the mechanism is independent of the exact number.

**Alternatives considered:** (a) shared 20/hr destructive limiter — rejected (punitive-only semantics, cross-capability MODIFY). (b) No rate limit — rejected (the deferral requirement + docs/07 both mandate one; an unbounded clear is an anti-abuse-control-weakening footgun). (c) A new shared "sensitive-restorative" limiter capability — rejected as over-engineering for a single consumer; revisit only if a second restorative-sensitive action appears.

### D2: Owner/admin-only write gate (read stays any-role)

Gate the clear to `admin_users.role IN ('owner','admin')` — excluding `moderator` and `read_only` — matching the most-sensitive shipped write actions (permanent ban, chat redaction). **Why:** clearing weakens an anti-abuse safety control (re-opens a blocked identity), so it warrants the higher-trust tier. The **read** view deliberately stays open to every admin role (unchanged) — only the mutation is restricted. Enforcement order is **CSRF → role → parse** (mirrors `admin-reserved-usernames-editor` "State-changing requests are CSRF- and write-role-gated in order"), so a missing CSRF token is rejected before role evaluation and before any body parsing.

**Alternative considered:** allow `moderator` (matching suspend/warn). Rejected — a moderator can punish, but re-opening a safety-blocked identity is owner/admin-tier by analogy to ban/redaction.

### D3: Zero Flyway migration — **KEY**

`admin_actions_log.action_type` is `VARCHAR(64) NOT NULL` with **no CHECK constraint** (`docs/05-Implementation.md`; precedent values are plain string literals — `'reserved_username_removed'`, `'admin_chat_redaction'`). The new `'rejected_identifier_cleared'` value therefore needs no schema change, and both `rejected_identifiers` (V3) and `admin_actions_log` (V16) already exist. **This change ships zero migrations** — keeping its footprint disjoint from the three in-flight backend migrations (no `V<N>` renumber-on-rebase contention).

**Alternative considered:** add a `CHECK` enumerating action types "for safety." Rejected — it contradicts the established free-text convention, would force a migration (collision risk), and would need editing on every future action-type addition.

### D4: Hard DELETE, audit `before_state` is the retained trail

`rejected_identifiers` has no soft-delete column; the clear is a hard `DELETE`. The cleared row's full content (`identifier_hash`, `identifier_type`, `reason`, `rejected_at`) is captured in the `admin_actions_log.before_state` JSONB **in the same transaction**, so the action is fully reconstructable from the immutable audit trail. After clearing, the same identifier can be re-rejected on a future signup attempt — the `UNIQUE (identifier_hash, identifier_type)` simply allows a fresh insert — which is the correct behavior (a still-under-18 identity is re-blocked). No soft-delete column ⇒ no migration (reinforces D3).

### D5: Mirror the reserved-usernames write-action template end-to-end

Reuse the proven shape: route handler validates CSRF → role → reason; repository performs the `DELETE` + audit insert + rate-count read in one `Connection`/transaction; HTMX `HX-Request` branch swaps the cleared row out (`hx-target` the row, `hx-swap="outerHTML"` to empty) with a plain-`POST` full-page re-render fallback; destructive-action confirm (`hx-confirm` or a confirm step) + a reason text input on the per-row control; the control is rendered **only** when the session role is owner/admin. **Why:** the template is battle-tested for exactly these edge cases (blank reason, over-length reason, nonexistent id no-op, per-admin cap, CSRF-before-role) and reviewer-familiar; cloning it minimizes net-new surface.

## Standards conformance (docs/11-Engineering-Standards.md)

- **Backend layering (Pattern Registry):** route handler (HTTP/CSRF/role/parse) → repository (JDBC, parameterized, single transaction for DELETE + audit + rate-count). No business logic in the route; no SQL outside the repository. Matches `admin-reserved-usernames-editor` / `admin-user-moderation`.
- **Admin write-action pattern:** CSRF→role→parse ordering; audit-log-as-rate-limit-ledger; one immutable `admin_actions_log` row per successful mutation; `before_state`/`after_state` capture. No deviation — this change is a faithful application of the existing pattern, not a new one. **No Pattern-Registry amendment required.**
- **Invariants touched:** `admin_sessions.csrf_token_hash` mandatory on the state-changing request; admin-user FK `ON DELETE SET NULL` already in place on `admin_actions_log`; all rendered values HTML-escaped (default-on Pebble autoescape, no `raw`); parameterized JDBC (no string interpolation); secrets unaffected.
- **Static-asset integrity:** if `admin.css` is edited for the clear control, re-pin `htmx.min.js.SHA256SUMS` (CI lint-lane check; not covered by the local gradle gate).

## Risks / Trade-offs

- **First mutation surface over `rejected_identifiers`** (a safety-control table) → an over-broad or under-gated clear could re-open blocked identities. **Mitigation:** owner/admin + CSRF + required reason + dedicated rate cap + full audit `before_state`; single-row only (no bulk); idempotent not-found prevents replay surprises.
- **Dedicated cap diverges from viewer D3** → reviewers may expect the shared limiter. **Mitigation:** the divergence is an explicit Decision (D1) with the shipped-spec-semantics justification; flagged for confirmation. If the operator prefers the shared limiter, it is a one-Decision swap (plus a cross-capability MODIFY of `admin-destructive-action-rate-limit`).
- **10/hr is a guess** → too low blocks a legitimate batch of mis-rejections; too high weakens the abuse control. **Mitigation:** called out as tunable; the cap is a soft abuse-prevention bound, not an authorization boundary, so a conservative default is safe and adjustable.
- **Hash is an anti-abuse signal "that may cross other users" (docs/06)** → the clear control surfaces per-row hashes to owner/admin. **Mitigation:** unchanged from the viewer's existing exposure (one-way hash, behind the admin gate); the write adds no new PII surface.
- **Shared template files** (the rejected-identifiers Pebble template + repository) are touched by this change only — no other in-flight change edits them — so no merge contention beyond the trivial nav/base-layout that is already shipped.

## Migration Plan

No schema migration. Deploy is code-only: new route + repository methods + Koin wiring + template edits + one `action_type` literal. **Pre-archive staging smoke (Section 6):** boot a branch deploy, bootstrap an owner admin, seed a `rejected_identifiers` row, exercise `POST .../clear`, confirm the row is gone + exactly one `admin_actions_log` row with the `before_state` captured, and confirm a `read_only`/`moderator` session is refused. Rollback = revert the commits (no data/schema state to unwind; cleared rows are intentionally gone and reconstructable from the audit trail).

## Open Questions

- **Rate-cap value (D1):** 10/hr per admin proposed — confirm or adjust. Non-blocking; default is conservative and tunable.
- **Confirm affordance:** `hx-confirm` (native browser confirm) vs a small inline reason+confirm form. Settled during `/opsx:apply` as a template detail (the reason input is required regardless, which nudges toward an inline form over a bare `hx-confirm`).
