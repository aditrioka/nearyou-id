## Context

The block system shipped at V5 — any user may block another, and the product timelines / chat / profile paths enforce blocks bidirectionally via a `user_blocks` NOT-IN join (the `BlockExclusionJoinRule` invariant). The table:

```sql
CREATE TABLE user_blocks (
    blocker_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT user_blocks_no_self_block CHECK (blocker_id <> blocked_id),
    PRIMARY KEY (blocker_id, blocked_id)
);
-- The PRIMARY KEY also serves as the (blocker_id, blocked_id) directional index.
```

There is no single-column surrogate `id` and no `created_at` index — the composite PK is the only index. `users` (V2) holds `id UUID PK`, `username VARCHAR(60) NOT NULL UNIQUE`, `display_name VARCHAR(50) NOT NULL`, with a `users_username_lower_idx ON users(LOWER(username))` for case-insensitive lookup.

The admin panel already ships the proven read-only-viewer substrate: `admin-login` (Argon2id + TOTP → `__Host-admin_session` cookie + per-session CSRF gate, `authenticate(ADMIN_AUTH_NAME)`), `admin-panel-scaffold` (Pebble base layout + HTMX + `/admin/*` route subtree), and **two near-identical read-only keyset+filter+HTMX surfaces** — `admin-actions-log-viewer` (`GET /admin/actions-log`) and `admin-rejected-identifiers-viewer` (`GET /admin/rejected-identifiers`). This change clones the latter. The constraint is to add the dispute-resolution read surface for `user_blocks` WITHOUT a mutation path (the admin never blocks/unblocks on behalf of a user — enforcement and the user-driven block/unblock stay in the product path) and WITHOUT a Flyway migration (to stay disjoint from the in-flight migration-bearing admin work and because the table's growth profile is served fine by the existing PK for the common search path).

## Goals / Non-Goals

**Goals:**

- A read-only `GET /admin/blocks` dispute-resolution surface inside the existing admin session gate, accessible to any authenticated admin role.
- Newest-first keyset pagination over `(created_at, blocker_id, blocked_id)`; either-side `q` search (username or UUID); a per-row "Bidirectional?" indicator; HTMX partial-swap + plain-GET fallback; HTML-escaped output.
- Username deep-links to the shipped `/admin/users` lookup; blocker/blocked usernames resolved by orphan-safe INNER joins to `users`.

**Non-Goals:**

- Any mutation of `user_blocks` (create/remove a block on behalf of a user); notifying either party; role-restriction on the read view; a rate-limit surface (read-only); a Flyway migration.
- A count-summary aggregate (the rejected-identifiers viewer has one for spike detection; frame 12 has no aggregate and block-registry's operational value is per-pair lookup, not volume anomaly — omitted, see D8).
- Coupling to the in-flight per-user profile page `/admin/users/{id}` (PR #251) — the deep-link targets the already-shipped `/admin/users?q=` lookup instead (D5).

## Decisions

### D1: Mirror `admin-rejected-identifiers-viewer` end-to-end

Clone the shipped viewer's structure — route placement inside `authenticate(ADMIN_AUTH_NAME)`, a read-only repository in a per-feature `admin/blockregistry/` sub-package (mirroring `admin/rejectedidentifiers/`), keyset cursor with `pageSize + 1` over-fetch, opaque base64url cursor encode/decode (malformed → first page, never throws), lenient parameterized filter parse, HTMX `HX-Request` fragment branch, Pebble templates extending the base layout, all values via positional JDBC placeholders. **Why:** the pattern is proven, reviewer-familiar, and already battle-tested for the malformed-cursor / SQL-metacharacter / over-long-value / equal-timestamp-tiebreaker / exact-page-boundary edge cases; reusing it minimizes net-new surface and review load. This is the **THIRD instance** of the read-only-admin-viewer pattern, so it **reinforces** the registered pattern and introduces **NO new `docs/11` § Pattern Registry pattern** → no docs/11 amendment task. **Alternative considered:** a bespoke `LIMIT/OFFSET` viewer — rejected; it diverges from the established keyset pattern for no benefit.

**Deltas from the precedent (intentional):**
- **Two `users` joins.** `rejected_identifiers` rendered raw column values; block pairs are UUID FKs, so the row needs blocker + blocked usernames resolved via two INNER joins to `users` (D3).
- **Three-column keyset tuple.** `rejected_identifiers` keyed on `(rejected_at, id)`; `user_blocks` has no surrogate `id`, so the keyset/order tuple is `(created_at, blocker_id, blocked_id)` — the composite PK supplies the deterministic tiebreaker (D6 of the keyset requirement).
- **Either-side search instead of enum filters.** `rejected_identifiers` filtered by `reason`/`identifier_type` enums + date range; block-registry's frame-12 control is a single "username or user ID (either side)" search box (D4).
- **No count summary.** Omitted (D8).
- **Load-bearing escaping.** `username`/`display_name` are user-controlled free text, so HTML-escaping is a real XSS control here, not just defense-in-depth as it was for the hash-only rejected-identifiers surface.

### D2: NO Flyway migration (search-by-user is index-served; unfiltered browse is rare) — **KEY DECISION**

`user_blocks` has only its composite PK `(blocker_id, blocked_id)` — no `created_at` index. The **common** support path is a lookup by a specific user, which resolves through `users_username_lower_idx` (username → id) then matches `blocker_id`/`blocked_id` against the PK — bounded and index-served. Only the **rare** unfiltered newest-first browse needs `ORDER BY created_at DESC, blocker_id DESC, blocked_id DESC`, which seq-scans + sorts `user_blocks`. At pre-launch / MVP scale (synthetic staging data; modest block volume) that sort is sub-millisecond. **Therefore this change ships zero migrations.**

**Consequences / rationale:**
- Keeps the change's migration footprint **disjoint from in-flight migration-bearing admin work** — no renumber-on-rebase contention, the property that makes this a clean parallel pick.
- Stays consistent with the "no `NOW()` in partial-index `WHERE`" invariant by construction (no index added, nothing to get wrong).
- **If `user_blocks` grows beyond the low-volume assumption** (a launch-scale, block-heavy user base), the documented lever is a follow-up `(created_at DESC, blocker_id DESC, blocked_id DESC)` index — filed as the `admin-block-registry-keyset-index` follow-up issue, not pre-built here.

**Alternative considered:** ship the keyset index now "for symmetry with actions-log." Rejected — premature optimization for a surface whose hot path is the indexed by-user lookup, and it would force V-number coordination with in-flight migration-bearing changes for no measurable MVP benefit. (This mirrors `admin-rejected-identifiers-viewer` D2.)

### D3: INNER-join `users` twice; orphan-safe by `ON DELETE CASCADE`

Resolve blocker + blocked usernames with two INNER joins (`JOIN users b ON b.id = ub.blocker_id JOIN users t ON t.id = ub.blocked_id`). INNER (not LEFT) is correct because both `user_blocks` FKs are `ON DELETE CASCADE` — a `user_blocks` row cannot outlive either referenced `users` row, so there is no orphan case to LEFT-join for. This is the admin module, exempt from the `RawFromPostsRule` / `BlockExclusionJoinRule` Detekt rules; reading raw `user_blocks` + `users` is permitted (no `visible_*` view, no block-exclusion join applies — this is the moderation surface, not a product path).

### D4: Either-side search is EXACT case-insensitive username (or UUID), not substring

The `q` parameter searches either side of the pair. A UUID-form `q` matches `blocker_id = ? OR blocked_id = ?`. A non-UUID `q` matches `LOWER(b.username) = LOWER(?) OR LOWER(t.username) = LOWER(?)`. **Exact** (not substring `ILIKE '%q%'`) because: (a) it uses the unique-username `users_username_lower_idx` directly; (b) it matches the frame-12 single-user lookup intent (`q=budi_kopi`); (c) it avoids a loose substring that would let an operator enumerate large swaths of the registry. A `q` that is neither a valid UUID nor an existing username yields the empty state (lenient, never a 500); SQL-metacharacters are bound as a literal. **Alternative considered:** substring search for fuzzy support lookups — rejected for the index + enumeration reasons; a fuzzy/typeahead variant can be a later enhancement if support workflow demands it.

### D5: Deep-link usernames to the shipped `/admin/users?q=` lookup, not the in-flight profile page

Blocker/blocked usernames render as links to `/admin/users?q=<username>` — the **shipped** `admin-user-moderation` lookup (frame 5). They deliberately do **not** target `/admin/users/{id}` — that per-user profile page is in-flight on PR #251 (not merged); linking to it would couple this change to unmerged work and break if #251's path/shape shifts. If/when #251 lands, re-pointing the deep-link to the richer profile page is a trivial follow-up, but it is out of scope here.

### D6: "Bidirectional?" via an `EXISTS` reverse-pair subquery

Compute the per-row bidirectional flag as `EXISTS (SELECT 1 FROM user_blocks r WHERE r.blocker_id = ub.blocked_id AND r.blocked_id = ub.blocker_id)`. An `EXISTS` correlated subquery yields a boolean per directed row without multiplying rows — unlike a self-join on the reverse pair, which would risk duplicating or filtering rows. Each directed pair `(A → B)` and `(B → A)` remains its own listed row; the flag merely annotates whether its mirror exists. (The PK lookup on the reverse `(blocked_id, blocker_id)` is index-served by the composite PK.)

### D7: New nav entry under the "Anti-abuse" group

Add a `Block Registry` nav entry mirroring the `admin-rejected-identifiers-viewer` entry, with `activePath = /admin/blocks`. This is the one shared-file touch (the admin layout / nav), additive (append-only), and the same trivial-merge pattern the prior viewers used.

### D8: No count-summary aggregate

The rejected-identifiers viewer ships a per-reason/per-type count summary for *spike detection* (the "rejected_identifiers insert rate" anomaly signal). Block-registry's operational value is **per-pair dispute lookup**, and frame 12 shows no aggregate band — so no count summary is rendered. This keeps the surface focused and the query set minimal (one page query, no second aggregate query). If an operational "block volume" anomaly signal is wanted later, it belongs on the Operational Dashboard (frame 3), not this lookup surface.

## Risks / Trade-offs

- **No dedicated keyset index (D2)** → at unexpectedly-large `user_blocks` cardinality the unfiltered newest-first browse's scan + sort could slow. **Mitigation:** the hot path (search-by-user) is index-served; the unfiltered browse is the rare case and stays well under budget at MVP scale; the documented follow-up index is the escalation lever if monitoring shows growth.
- **Surfacing "who blocked whom" to any admin role** is a (small) exposure of a privacy-relevant relationship. **Mitigation:** it is shown only behind the admin session gate, for the documented dispute-resolution purpose, with no user-facing notification and no mutation; this matches the existing read-only posture for the other admin viewers. The *sensitive* operations (block/unblock) remain user-driven in the product path, never exposed here.
- **Shared base-layout nav-link append (D7)** is the one file this and other in-flight admin changes touch → trivial additive merge. **Mitigation:** append-only; whichever lands second resolves a one-line conflict.

## Standards conformance (docs/11)

`design.md` names the registered `docs/11-Engineering-Standards.md` Pattern-Registry patterns this builds on: **backend layering** (§3.1 Route → Repository, JDBC `PreparedStatement`, no business logic in the route); the **admin auth seam** (`authenticate(ADMIN_AUTH_NAME)` + `__Host-admin_session` + CSRF gate from `admin-login`); the **read-only-admin-viewer render pattern** (keyset cursor + lenient parameterized filter + HTMX fragment + plain-GET fallback + base-layout extension) shared by `admin-actions-log-viewer` and `admin-rejected-identifiers-viewer`; and the **admin mockup board** binding (frame 12, `docs/11` § 3.6). This change is the THIRD instance of the read-only-admin-viewer pattern — it **reinforces** it and introduces **NO new pattern**, so there is **no `docs/11` § Pattern Registry amendment** in this change.

## Migration Plan

No schema migration. Deploy is code-only (new route + templates + repository + Koin wiring + one nav-link). Rollback is reverting the commits — no data or schema state to unwind. The staging-smoke section is N/A for a read-only admin view with no runtime-config / secret / rate-limit surface; mark N/A at archive.

## Open Questions

- None blocking. (Truncation/copy affordance for long usernames in the table is a template detail settled during `/opsx:apply`, not a spec-level decision.)
