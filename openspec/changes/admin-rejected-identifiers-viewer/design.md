## Context

The age gate (shipped) and the persistent-attestation-failure path both write to `rejected_identifiers` — an anti-abuse blocklist keyed on a one-way SHA-256 of the Google/Apple identifier (no DOB, email, or name retained; see [`docs/05-Implementation.md` § Rejected Identifiers Schema](../../../docs/05-Implementation.md) + [`docs/06-Security-Privacy.md` § Under-18 Bypass Prevention](../../../docs/06-Security-Privacy.md)). The table shipped at V3:

```sql
CREATE TABLE rejected_identifiers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identifier_hash TEXT NOT NULL,
    identifier_type VARCHAR(8)  NOT NULL CHECK (identifier_type IN ('google','apple')),
    reason          VARCHAR(32) NOT NULL CHECK (reason IN ('age_under_18','attestation_persistent_fail')),
    rejected_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (identifier_hash, identifier_type)
);
CREATE INDEX rejected_identifiers_hash_idx ON rejected_identifiers (identifier_hash);
```

The admin panel already ships the proven read-only-viewer substrate: `admin-login` (Argon2id + TOTP → `__Host-admin_session` cookie + per-session CSRF gate, `authenticate(ADMIN_AUTH_NAME)`), `admin-panel-scaffold` (Pebble base layout + HTMX + `/admin/*` route subtree), and `admin-actions-log-viewer` (`GET /admin/actions-log`) — a near-identical read-only keyset+filters+HTMX surface this change clones. The constraint is to add the anti-abuse audit surface for `rejected_identifiers` WITHOUT introducing a mutation path (the support-clear write is a separate, more-sensitive change) and WITHOUT a Flyway migration (to stay disjoint from the in-flight `admin-report-queue-viewer` and because the table's growth profile differs from the audit log's).

## Goals / Non-Goals

**Goals:**

- A read-only `GET /admin/rejected-identifiers` triage surface inside the existing admin session gate, accessible to any authenticated admin role.
- Newest-first keyset pagination over `(rejected_at, id)`, composable `reason` / `identifier_type` / date-range filters, a per-reason/per-type count summary for spike detection, HTMX partial-swap + plain-GET fallback, HTML-escaped output.
- Hash-only PII discipline: surface the one-way `identifier_hash`, never a resolved raw identifier.
- Capture the deferred manual-clear write action as explicit spec requirements (positive deferral + negative guard) so the fast-follow has a requirement to MODIFY.

**Non-Goals:**

- The manual support-clear / `DELETE` write action (deferred fast-follow `admin-rejected-identifiers-clear-action`).
- Any mutation of `rejected_identifiers`; role-restriction on the read view; a rate-limit surface (read-only); a Flyway migration.
- Resolving `identifier_hash` to a raw identifier (impossible — one-way hash, none stored) or cross-linking to `/admin/users` (a rejected identifier has NO `users` row by design).

## Decisions

### D1: Mirror `admin-actions-log-viewer` end-to-end

Clone the shipped viewer's structure — route placement inside `authenticate(ADMIN_AUTH_NAME)`, keyset cursor, opaque-cursor encoding, lenient filter parsing, HTMX `HX-Request` fragment branch, Pebble template extending the base layout, parameterized JDBC placeholders. **Why:** the pattern is proven, reviewer-familiar, and already battle-tested for the malformed-cursor / SQL-metacharacter / over-long-value edge cases; reusing it minimizes net-new surface and review load. **Alternative considered:** a bespoke simpler viewer (no keyset, `LIMIT/OFFSET`) — rejected because it diverges from the established pattern for no benefit and reintroduces OFFSET-pagination drift that the project deliberately avoids.

**Deltas from the precedent (intentional):**
- **No `admin_users` join.** `rejected_identifiers` has no admin-actor FK (rejections are system-written at signup), so there is no `display_name`/`email` resolution requirement.
- **New per-reason/per-type count summary.** The audit-log viewer has no aggregate; this viewer adds a small count-by-`reason` (and by-`identifier_type`) summary scoped to the active filters, because the operational value here is *spike detection* (the "rejected_identifiers insert rate" anomaly signal), not per-row forensics.
- **Lower XSS surface.** `admin_actions_log` carries a client-controlled `user_agent` + free-text `reason` + JSONB state; `rejected_identifiers` has none — `identifier_hash` is hex, the two enums are CHECK-constrained, `rejected_at` is a timestamp. Escaping is still applied (default-on Pebble autoescape, no `raw` filter) as defense-in-depth, but the realistic injection surface is near-zero.

### D2: NO Flyway migration (low-cardinality table) — **KEY DECISION**

`admin-actions-log-viewer` shipped a V17 `(created_at DESC, id DESC)` keyset index because `admin_actions_log` grows with **every** admin action (operationally unbounded), so a bare newest-first scan would degrade. `rejected_identifiers` has the **opposite growth profile**: it accumulates only under-18 rejections + persistent-attestation-fail rows — a tiny fraction of signup attempts, and each identifier appears at most once per type (`UNIQUE (identifier_hash, identifier_type)`). At MVP scale a `rejected_at DESC, id DESC` keyset served by seq-scan + in-memory sort over a small table is sub-millisecond; the existing PK + `rejected_identifiers_hash_idx` suffice for the page query and the count summary. **Therefore this change ships zero migrations.**

**Consequences / rationale:**
- Keeps the change's migration footprint **disjoint from the in-flight `admin-report-queue-viewer`**, which tentatively reserves V19 — no renumber-on-rebase contention.
- Stays consistent with the partial-index invariant by construction (no index added, nothing to get wrong).
- **If the table grows beyond the low-volume assumption** (e.g., a sustained brute-force campaign inflates it), the documented lever is a follow-up `(rejected_at DESC, id DESC)` index — logged to `FOLLOW_UPS.md` as `admin-rejected-identifiers-keyset-index`, not pre-built here.

**Alternative considered:** ship the `(rejected_at DESC, id DESC)` index now "for symmetry with actions-log." Rejected — it would be premature optimization for a low-cardinality table, and it would force V19/V20 coordination with the in-flight report-queue change for no measurable MVP benefit.

### D3: Read-only viewer first, write-action fast-follow

Ship the read-only audit/triage surface now; defer the manual support-clear `DELETE` to `admin-rejected-identifiers-clear-action`. **Why:** this is the project's established admin cadence — `admin-actions-log-viewer` (read) preceded `admin-user-moderation` (write); `admin-report-queue-viewer` (read) precedes its deferred resolution actions. The clear action is materially more sensitive (it's destructive, must be role-gated + CSRF-gated + audit-logged + rate-limited via the `admin-destructive-action-rate-limit` limiter still in `FOLLOW_UPS.md`), so isolating it keeps this change small and lets the write surface land once its rate-limiter dependency is ready. The deferral is encoded as **explicit spec requirements** (a positive "this change defers X" statement + a negative "no mutation wired" guard) per the project convention, so the fast-follow has a concrete requirement to MODIFY rather than inventing scope from prose.

### D4: Display `identifier_hash` as-is; hash-only PII discipline

Render `identifier_hash` directly (monospace; may be visually truncated with the full value available via title/copy affordance) — it IS the audit key and the only correlation handle a moderator has. The view never attempts to resolve it to a raw identifier because none is stored (the hash is one-way by design). This is asserted as an explicit requirement because [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md) flags the hash as an anti-abuse signal that "may cross other users" — the discipline (show the hash, resolve nothing) is the privacy contract.

### D5: Any-admin-role read access

Match `admin-actions-log-viewer`: any valid admin session may view, regardless of `admin_users.role` (`owner` / `admin` / `moderator` / `read_only`); no role-based row/column redaction. **Why:** it is a read-only anti-abuse audit surface with no PII beyond opaque hashes; the *sensitive* operation (clearing an identifier) is the deferred write action, which WILL be role-gated. Keeping the read open mirrors the audit-log precedent and avoids divergent role logic.

## Risks / Trade-offs

- **No dedicated keyset index (D2)** → at unexpectedly-large cardinality the newest-first scan + sort could slow. **Mitigation:** the table is low-volume by construction; the count summary + page query both stay well under any latency budget at MVP scale; the documented follow-up index is the escalation lever if monitoring shows growth.
- **Hash is an anti-abuse signal "that may cross other users" (per docs/06)** → displaying it to any admin role is a (small) exposure. **Mitigation:** it is a one-way hash (not reversible to PII), shown only behind the admin session gate; this matches the existing posture for `admin_actions_log` `target_id` hashes.
- **Deferring the clear action** → the read viewer alone cannot unblock a falsely-rejected adult; that still requires the current raw-SQL path until the fast-follow lands. **Mitigation:** explicit; the viewer at least makes the row discoverable (find the hash, then run the existing manual clear), and the fast-follow is scoped + named. The anti-abuse *audit* value (spike detection) is delivered independently and immediately.
- **Shared base-layout nav-link append** is the one file both this change and `admin-report-queue-viewer` touch → trivial additive merge. **Mitigation:** append-only; whichever lands second resolves a one-line conflict.

## Migration Plan

No schema migration. Deploy is code-only (new route + templates + repository + Koin wiring + one nav-link). Rollback is reverting the commits — no data or schema state to unwind. Section 6 (staging smoke) is N/A for a read-only admin view with no runtime-config / secret / rate-limit surface; mark N/A at archive.

## Open Questions

- None blocking. (Count-summary placement — header band vs. sidebar — is a template detail settled during `/opsx:apply`, not a spec-level decision.)
