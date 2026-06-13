## Context

The privacy-downgrade grace window is backed by a single column shipped at V2 ([`V2__auth_foundation.sql`](../../../backend/ktor/src/main/resources/db/migration/V2__auth_foundation.sql)):

```sql
CREATE TABLE users (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username                    VARCHAR(60) NOT NULL UNIQUE,
    display_name                VARCHAR(50) NOT NULL,
    private_profile_opt_in      BOOLEAN NOT NULL DEFAULT FALSE,
    privacy_flip_scheduled_at   TIMESTAMPTZ,           -- the scheduled-flip deadline; NULL when no flip pending
    ...
    deleted_at                  TIMESTAMPTZ,
    ...
);
CREATE INDEX users_privacy_flip_idx ON users (privacy_flip_scheduled_at) WHERE privacy_flip_scheduled_at IS NOT NULL;
CREATE INDEX users_username_lower_idx ON users (LOWER(username));
```

The shipped read path already honors the window — `JdbcUserProfileReader` short-circuits the effective privacy state while `privacy_flip_scheduled_at > now()` (the 72h grace), and a `privacy_flip_warning` notification type exists (V10). A background worker applies the flip + clears the column when the deadline passes. The operational hole is observability: no admin surface lists who is mid-flip, and — critically — no surface exposes a **stuck** row (deadline passed, column not cleared), which is how a worker / webhook-handler bug would manifest ([`docs/07-Operations.md` § Core Features → "Privacy Flip Monitor"](../../../docs/07-Operations.md): "spotting webhook handler bugs (mass scheduling events, stuck rows past the deadline)").

The admin panel already ships the proven read-only-viewer substrate: `admin-login` (Argon2id + TOTP → `__Host-admin_session` cookie + per-session CSRF gate, `authenticate(ADMIN_AUTH_NAME)`), `admin-panel-scaffold` (Pebble base layout + HTMX + `/admin/*` route subtree), and three near-identical read-only keyset+filters+HTMX surfaces — `admin-actions-log-viewer`, `admin-rejected-identifiers-viewer`, and the in-flight `admin-block-registry` ([PR #264](https://github.com/aditrioka/nearyou-id/pull/264)). This change clones `admin-rejected-identifiers-viewer` and adds the monitor-specific IN_WINDOW / OVERDUE classification, WITHOUT a mutation path and WITHOUT a Flyway migration.

## Goals / Non-Goals

**Goals:**

- A read-only `GET /admin/privacy-flips` monitor surface inside the existing admin session gate, accessible to any authenticated admin role.
- Surface BOTH the in-window rows (support-ticket answers) AND the overdue/stuck rows (worker-bug detection) from the single `WHERE privacy_flip_scheduled_at IS NOT NULL` scan, classified per row.
- Ascending keyset pagination over `(privacy_flip_scheduled_at, id)` (most-overdue / soonest first), composable `status` / `q` filters, an in-window-vs-overdue count summary, HTMX partial-swap + plain-GET fallback, HTML-escaped output (load-bearing here — free-text identity columns).
- Identity-only PII discipline: surface `username` / `display_name` / `id` + privacy/flip state; no location; cross-link only to the SHIPPED `/admin/users?q=` lookup.

**Non-Goals:**

- Any write action — no clearing/expediting a stuck flip, no mutation of `users.privacy_flip_scheduled_at`, no `admin_actions_log` row (anomalies escalate to a worker fix out-of-band, per frame 17). An in-panel "expedite/clear" write is a deferred follow-up.
- A Flyway migration; a role-restriction on the read view; a rate-limit surface (read-only).
- Reproducing or modifying the worker that applies the flip; building the privacy-flip *warning notification* path (shipped separately).

## Decisions

### D1: Mirror `admin-rejected-identifiers-viewer` end-to-end

Clone the shipped viewer's structure — route placement inside `authenticate(ADMIN_AUTH_NAME)`, the keyset cursor + opaque-cursor encode/decode, lenient parameterized filter parsing, the `HX-Request` fragment branch, the Pebble full-page + table-fragment template pair extending the base layout, the read-only repository in a per-feature sub-package (`admin/privacyflips/` mirroring `admin/rejectedidentifiers/`), the fixed page size + `pageSize + 1` "has-more" probe, parameterized JDBC placeholders, the empty-state render. **Why:** the pattern is proven and reviewer-familiar and already battle-tested for the malformed-cursor / SQL-metacharacter / over-long-value / equal-key-tiebreaker / exact-page-boundary edge cases; this is the fourth instance, so reusing it minimizes net-new surface and review load and **reinforces the Pattern Registry entry rather than forking it**. **Alternative considered:** a bespoke simpler viewer (`LIMIT/OFFSET`, no keyset) — rejected for the same reason the prior three viewers rejected it (OFFSET-pagination drift the project deliberately avoids).

**Deltas from the precedent (intentional):**
- **Reads `users`, not a dedicated blocklist table.** Raw read of `users` is admin-module-exempt from `RawFromPostsRule` / `BlockExclusionJoinRule` (see D6); no `visible_*` view or block-exclusion join applies to an admin moderation surface.
- **Ascending order, not newest-first** (D2).
- **Per-row IN_WINDOW / OVERDUE classification** computed against `NOW()` (D3) — the monitor-specific behavior with no precedent in the prior viewers.
- **Free-text identity columns** make HTML-escaping load-bearing, not merely defense-in-depth (D7).

### D2: Ascending order `privacy_flip_scheduled_at ASC, id ASC` (keyset `(privacy_flip_scheduled_at, id) > (?, ?)`)

The three sibling viewers order **newest-first** (`DESC`) because their value is recency forensics. This monitor's value is **urgency / anomaly spotting**: the most-overdue rows (smallest, already-past `privacy_flip_scheduled_at`) and the soonest-to-flip rows are the ones a support agent or on-call engineer wants first. Ascending by `privacy_flip_scheduled_at` puts every OVERDUE row (deadline in the past) ahead of every IN_WINDOW row, with the most-overdue at the very top — exactly the stuck-row triage orientation. The keyset "next" predicate is therefore the ascending-form row-value comparison `(privacy_flip_scheduled_at, id) > (?, ?)`, with `id` as the deterministic tiebreaker for rows sharing an identical `privacy_flip_scheduled_at` (a mass-scheduling event can write colliding timestamps). The opaque cursor encodes the last-displayed `(privacy_flip_scheduled_at, id)`; a malformed/absent cursor decodes to "first page", never an error. **Alternative considered:** DESC for symmetry with the sibling viewers — rejected; it would bury the overdue rows at the bottom, defeating the monitor's primary purpose.

### D3: Per-row IN_WINDOW / OVERDUE classification — the OVERDUE branch is deliberately OUTSIDE the canonical predicate

`docs/07` defines the canonical population as `privacy_flip_scheduled_at IS NOT NULL AND NOW() < privacy_flip_scheduled_at` (the in-grace set). Frame 17 is explicit that the monitor query scans the **broader** `WHERE privacy_flip_scheduled_at IS NOT NULL` and **adds a stuck-row branch on top of the docs predicate**: a row whose `privacy_flip_scheduled_at <= NOW()` is past its deadline but the worker has not cleared it — an OVERDUE / stuck row that is the webhook-handler-bug signal the monitor exists to catch. So each rendered row is classified, against a single `NOW()` evaluated server-side:

- **IN_WINDOW** — `NOW() < privacy_flip_scheduled_at`. Mid-grace; render the time **remaining** until the flip.
- **OVERDUE** — `privacy_flip_scheduled_at <= NOW()`. Past deadline, not cleared; render how long it has been **overdue**. This is the anomaly.

Classification is computed in the query projection (a `CASE WHEN privacy_flip_scheduled_at > now() THEN 'in_window' ELSE 'overdue' END` column) so the same `now()` snapshot drives both the row's class and the `status` filter, avoiding a read-vs-filter skew. **This makes the docs predicate the IN_WINDOW subset, with zero divergence**: the monitor is a strict superset of the canonical population, and the OVERDUE superset is the documented stuck-row extension, not a contradiction of it (called out for B.3 reconciliation).

### D4: `status` (`in_window` / `overdue`) + optional `q` user-search — composable, lenient, parameterized

- **`status`**: `in_window` → `AND privacy_flip_scheduled_at > now()`; `overdue` → `AND privacy_flip_scheduled_at <= now()`. An unrecognized / over-long value is ignored (lenient), leaving the unfiltered both-buckets view. The filter reuses the SAME `now()`-based predicate as the classification (D3) so a `status=overdue` page contains exactly the rows the table classifies OVERDUE.
- **`q`** (the support-ticket lookup, per "why is my profile still private?"): a value that parses as a UUID → `AND id = ?`; otherwise → `AND LOWER(username) = LOWER(?)` (exact, case-insensitive — served by `users_username_lower_idx`; exact-match, not substring, to use the index and avoid registry enumeration, matching the block-registry [PR #264] D4 rationale). Over-long `q` is length-bounded then bound as a literal; a `q` matching nothing renders the empty state.

All values reach the repository as positionally-bound `PreparedStatement` parameters — never string-interpolated. Filters compose with logical AND and survive into the "next" pagination link (shareable URL).

### D5: NO Flyway migration — the hot path is index-served by the existing partial index — **KEY DECISION**

The monitor's core query is `SELECT ... FROM users WHERE privacy_flip_scheduled_at IS NOT NULL ORDER BY privacy_flip_scheduled_at ASC` — and the **existing** partial index `users_privacy_flip_idx ON users (privacy_flip_scheduled_at) WHERE privacy_flip_scheduled_at IS NOT NULL` (V2) covers BOTH the predicate (its partial `WHERE` is identical) AND the lead sort column. Postgres serves the page from a forward index scan over a small, naturally-bounded population (only users with a pending flip — a transient subset that the worker continuously drains), with the `id` tiebreaker resolved cheaply per page and the username/display_name fetched via heap lookup for the ≤ page-size rows. **This is a genuine perf advantage over the sibling `admin-block-registry` ([PR #264], which noted it ships no index and seq-scans `user_blocks`) — here the index already exists, so the monitor is index-served from day one with zero new schema.** Therefore this change ships **zero migrations**, keeping its footprint disjoint from in-flight admin PRs #251 / #264 (no V-number contention).

**Consequence / escalation lever:** if the `id` tiebreaker (not part of the single-column partial index) ever shows up as a cost under an extreme mass-scheduling event, the documented follow-up is a `(privacy_flip_scheduled_at, id)` composite partial index — filed as a `follow-up` issue, not pre-built. **Alternative considered:** ship the composite index now "for keyset symmetry" — rejected as premature optimization against an already index-served lead column on a self-draining population.

### D6: Match the canonical predicate verbatim — NO `deleted_at IS NULL` filter

The query filters on `privacy_flip_scheduled_at IS NOT NULL` and nothing else structural — it does **not** add `AND deleted_at IS NULL`. **Why:** (a) zero-divergence from the `docs/07` predicate (which carries no `deleted_at` clause); (b) a **soft-deleted** user (`deleted_at IS NOT NULL`) that still carries a non-NULL `privacy_flip_scheduled_at` is *itself* a stuck-row signal — the deletion path should have cleared or mooted the pending flip, so its lingering presence is exactly the kind of worker/handler bug the monitor exists to surface. Hiding soft-deleted rows would suppress a real anomaly class. The row's `deleted_at` state MAY be surfaced as a visual marker, but it is never a filter. **Alternative considered:** exclude soft-deleted users for "cleaner" support output — rejected; it diverges from the canonical predicate AND blinds the monitor to a stuck-row class.

### D7: HTML-escaping is load-bearing (free-text identity columns)

Unlike `rejected_identifiers` (hex `identifier_hash` + CHECK-constrained enums + timestamp — near-zero injection surface), this view renders **user-controlled free text**: `username` (`VARCHAR(60)`) and `display_name` (`VARCHAR(50)`), both set by the user. Every rendered value goes through Pebble's default-on autoescaping; **no `raw` filter** is used on any row value. This is asserted as an explicit requirement with a real markup-in-`display_name` scenario, because here escaping is a genuine XSS control, not just defense-in-depth.

### D8: Identity-only PII discipline; deep-link to the SHIPPED `/admin/users?q=` lookup

The row surfaces `username`, `display_name`, the user `id`, the current `private_profile_opt_in` state, and `privacy_flip_scheduled_at` + derived status — and **nothing else**: explicitly **no location** (`display_location`, coordinates, `city` — none is read, so the spatial-fuzzing invariant is not even engaged), no email, no DOB. The `username` deep-links to the **shipped** `/admin/users?q=<username>` moderation lookup (frame 5 / `admin-user-moderation`), the same target `admin-block-registry` ([PR #264] D5) chose — **NOT** the in-flight `/admin/users/{id}` profile page ([PR #251] `admin-user-management-profile`), to avoid coupling this change to an unmerged route. If #251 merges first, retargeting to `/admin/users/{id}` is a trivial follow-up; building against the shipped lookup keeps this change independently mergeable.

### D9: Any-admin-role read access

Match the three sibling viewers: any valid admin session may view, regardless of `admin_users.role` (`owner` / `admin` / `moderator` / `read_only`); no role-based row/column redaction. **Why:** it is a read-only operational monitor; the data (identity + a privacy-flip timestamp) is no more sensitive than the user-moderation lookup any admin can already reach. The only *sensitive* operation here would be a write (clear/expedite a flip), which this change does not ship.

## Risks / Trade-offs

- **`NOW()`-based classification + `status` filter skew.** Computing the row class and the `status` predicate against two different clock reads could misclassify a row crossing its deadline mid-request. **Mitigation:** D3 — both the projection's `CASE` and the `status` predicate use the SAME SQL `now()` within one statement (the page query selects the classification column AND applies the status predicate in one round-trip), so they cannot disagree within a page. The count summary is a separate statement; a row crossing the boundary between the page query and the summary query is a sub-second cosmetic count drift, acceptable for a monitor.
- **Surfacing user identity to any admin role (D9).** A small exposure. **Mitigation:** identity-only (no location/email/DOB), behind the admin session gate, matching the existing user-moderation lookup posture.
- **No in-panel clear/expedite for a stuck row.** The monitor makes the stuck row *discoverable* but the actual fix is still an out-of-band worker correction (frame 17). **Mitigation:** explicit non-goal; the discovery value (and the count-summary anomaly signal) is delivered immediately, and the in-panel write is a scoped follow-up.
- **Shared base-layout nav-link append** is the one file this change and the in-flight admin PRs (#251 / #264) all touch → trivial additive merge (append-only; whichever lands second resolves a one-line conflict).
- **`id` tiebreaker not in the single-column partial index.** A theoretical cost only under an extreme colliding-timestamp burst. **Mitigation:** D5 documented composite-index follow-up; not a concern at MVP scale on a self-draining population.

## Migration Plan

No schema migration. Deploy is code-only (new route + templates + repository + Koin wiring + one nav-link). Rollback is reverting the commits — no data or schema state to unwind. Section 4 of the DoD (staging smoke) is N/A for a read-only admin view with no runtime-config / secret / rate-limit surface; mark N/A at archive. The UI-affecting DoD gate (docs/11 §5.3) applies — the admin panel is brought up locally (or via the verify-loop admin bootstrap + TOTP path) and frame-17-conformance screenshots attached before archive.

## Open Questions

- None blocking. (Count-summary placement — header band vs. sidebar chip — and the exact "overdue by / flips in" relative-time rendering are template details settled during `/opsx:apply`, not spec-level decisions.)
