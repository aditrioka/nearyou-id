## Why

The block system (shipped V5) lets any user block another, and the product path enforces it bidirectionally via the `user_blocks` NOT-IN join (`BlockExclusionJoinRule` invariant). But there is **no admin surface to inspect block relationships**, which [`docs/07-Operations.md` § Core Features → "Block User Registry (read-only, for dispute resolution)"](../../../docs/07-Operations.md) calls for: when a support ticket asks "why can't I see this user / why did my DM never arrive," a moderator needs to look up the block pairs involving a given account — read-only, for dispute resolution. Today that requires a raw-SQL query a human runs against the Supabase dashboard. This change ships the admin **Block User Registry** read surface (admin mockup board **frame 12**, `dev/mockups/nearyou-admin-mockup.html`).

This is the **third instance of the established read-only-admin-viewer pattern** (after `admin-actions-log-viewer` and `admin-rejected-identifiers-viewer`); it clones `admin-rejected-identifiers-viewer` end-to-end (keyset pagination, lenient parameterized filters, HTMX fragment + plain-GET fallback, HTML-escaped output, any-admin-role read access, no migration), so it adds near-zero net-new pattern surface.

**Operator override (priority).** `openspec/project.md` § Mobile-First to Full-Demo Priority currently biases `/next-change` toward mobile picks and defers admin (Phase 3.5). The full mobile critical-path menu (#1–#5 + FCM) is already claimed by concurrent sessions (PRs #245–#250), and the operator explicitly requested **admin-related work** for this session, so this admin surface is advanced in parallel. The pick is deliberately the lowest-collision admin candidate — disjoint from the in-flight `admin-user-management-profile` (PR #251): different capability, route, repository, and template; **no shared spec; no Flyway migration; no new library** — so it squash-merges in parallel without rebase pain.

## What Changes

- **New read-only route `GET /admin/blocks`**, wired INSIDE the existing `admin-login` session gate (`authenticate(ADMIN_AUTH_NAME)`; any valid admin session, not role-restricted — matching `admin-actions-log-viewer` / `admin-rejected-identifiers-viewer`), rendering a moderator dispute-resolution table over `user_blocks`. The implementation mirrors the shipped `admin-rejected-identifiers-viewer` end-to-end.
- **Newest-first paginated table** (`created_at DESC, blocker_id DESC, blocked_id DESC`) via a **keyset cursor over `(created_at, blocker_id, blocked_id)`** (no SQL `OFFSET`). Each row displays the **blocker username**, the **blocked username** (both deep-linked to the shipped `/admin/users?q=<username>` lookup), `created_at` (UTC), and a **"Bidirectional?"** indicator. Usernames are resolved by INNER-joining `user_blocks` to `users` twice (orphan-safe: both FKs are `ON DELETE CASCADE`).
- **Either-side search** via a single `q` parameter: a UUID-form `q` matches `blocker_id = ? OR blocked_id = ?`; a non-UUID `q` matches an EXACT case-insensitive username (`LOWER(username) = LOWER(?)`, served by `users_username_lower_idx`) on either the blocker or blocked side. Parameterized placeholders only; lenient (a term matching nothing → empty state, not error; SQL-metacharacters bound as a literal).
- **"Bidirectional?" indicator** computed via an `EXISTS` reverse-pair subquery (`(blocked_id, blocker_id)` also present) — a per-row flag, not a row-multiplying self-join.
- **HTML-escapes every rendered value** (usernames are user-controlled free text → escaping is load-bearing here, not just defense-in-depth) and supports **HTMX partial-swap** (`HX-Request` → `#block-registry-table` fragment) with a plain-`GET` progressive-enhancement fallback.
- **Strictly read-only** (explicit negative-guard requirement): the route writes **zero** `admin_actions_log` rows, mutates no table, wires no `POST`/`PUT`/`PATCH`/`DELETE` handler, notifies neither user, and creates/removes no block. A page banner states enforcement stays in the product path via the bidirectional NOT-IN join.
- **No Flyway migration, no new dependencies.** `user_blocks` shipped at V5; a dedicated `(created_at DESC, …)` index is deliberately NOT added (see `design.md` D2) — keeping the migration footprint disjoint from in-flight migration-bearing admin work.
- **New nav entry** under the admin "Anti-abuse" group (`activePath = /admin/blocks`), mirroring the `admin-rejected-identifiers-viewer` nav entry.

## Capabilities

### New Capabilities

- `admin-block-registry`: the read-only admin Block User Registry surface (`GET /admin/blocks`) — authenticated, any-admin-role dispute-resolution table over `user_blocks` with keyset pagination, either-side username/UUID search, a per-row bidirectional-block indicator, HTML-escaped HTMX rendering with plain-GET fallback, username deep-links to the shipped `/admin/users` lookup, and the explicit read-only / no-mutation / dispute-resolution-only requirements.

### Modified Capabilities

<!-- None. The viewer is purely additive: it reads `user_blocks` + `users` (admin-module raw-read exemption) without changing their requirements, and consumes the `admin-login` / `admin-panel-scaffold` gate + layout without modifying them. The username deep-link targets the already-shipped `admin-user-moderation` `/admin/users` lookup route without altering it. -->

## Impact

- **Code**: `:backend:ktor` `admin` package — new `routes/AdminBlockRegistryRoute.kt` (`get("/blocks")`) mounted inside the existing `authenticate(ADMIN_AUTH_NAME)` block in `AdminModule.kt`; a new read-only repository in an `admin/blockregistry/` sub-package (mirroring `admin/rejectedidentifiers/`) with the keyset query + opaque cursor + lenient `q` parse + bidirectional `EXISTS`; new Pebble template(s) (full page + `#block-registry-table` HTMX fragment + empty state + read-only banner) extending the `admin-panel-scaffold` base layout; a one-line nav-link append; Koin wiring.
- **Schema / migrations**: **none**. `user_blocks` shipped at V5; a `(created_at DESC, blocker_id DESC, blocked_id DESC)` index is deliberately NOT added (see `design.md` D2). No V-number contention with in-flight migration-bearing admin work.
- **Lint / invariants**: the `admin` module is exempt from the `visible_*`-view + block-exclusion rules (raw read of `user_blocks` + `users` permitted, mirroring `admin-rejected-identifiers-viewer` / `admin-user-moderation`). All filter values applied via parameterized JDBC placeholders. No new secret reads, no rate-limit surface (read-only), no new `gradle/libs.versions.toml` pin.
- **Docs**: aligns with [`docs/07-Operations.md` § Core Features](../../../docs/07-Operations.md) — flips "Block User Registry" from DESIGN to shipped at archive time.
- **Follow-ups** (filed as `follow-up` GitHub issues at archive, not silent): `admin-block-registry-keyset-index` — add a `(created_at DESC, blocker_id DESC, blocked_id DESC)` index if `user_blocks` cardinality grows beyond the low-volume MVP assumption (`design.md` D2).
