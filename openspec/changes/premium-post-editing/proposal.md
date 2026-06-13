## Why

Post editing is a committed Premium entitlement — the freemium table promises Premium users an "Edit post (30 min window)" ([`docs/01-Business.md`](../../../docs/01-Business.md) line 19) and Phase 4 item 12 scopes the backend build. Today there is no edit path at all: a post is immutable after creation, so a Premium subscriber cannot fix a typo, and the `post_edits` audit trail that the product ("Riwayat edit" / "Versi ke-N") and the admin report queue ([#191](https://github.com/aditrioka/nearyou-id/issues/191)) depend on does not exist. This change ships the backend half — the edit endpoint + the temporal edit-history store — so the paired mobile UI and admin filter have a contract to build against.

## What Changes

- **New `PATCH /api/v1/posts/{post_id}` edit endpoint** — Premium-gated content edit, allowed only within **30 minutes of post creation**, by the **author only**. Content-only (location is immutable). Enforces the existing 280-char post guard.
- **New `post_edits` temporal-versioning table** (Flyway **V22** — V21 is taken by the in-flight `revenuecat-subscription-webhook` branch) — append-only; each edit inserts the **before-edit** snapshot (content + location + editor + `clock_timestamp()`), so the table reconstructs full history. Schema is verbatim from [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) §367.
- **Race-safe edit transaction** — the mandatory `SELECT … FOR UPDATE` (window + author + not-deleted guards) → `INSERT` snapshot → `UPDATE posts` shape from `docs/05` §385, single JDBC connection, with app-level retry on the sub-microsecond `unique_violation` → **409 CONFLICT** ("Coba lagi sebentar.").
- **New `GET /api/v1/posts/{post_id}/edits` history read** — chronological versions labelled "Versi ke-N" via `ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at)`; honours shadow-ban (`visible_posts`) + bidirectional-block visibility on the read path.
- **Re-moderation on edit** — the edited content re-runs the content-moderation pipeline (synchronous keyword profanity/UU-ITE + fire-and-forget Layer-3 Perspective dispatch), mirroring `CreatePostService`, so an edit cannot launder clean→toxic content past create-time moderation. `docs/05` §367–407 is silent on this; see `design.md` D1 + the docs-reconciliation note.
- **`premium_billing_retry` retains edit access** — the gate accepts both `subscription_status = 'premium_active'` AND `'premium_billing_retry'` (the active 7-day billing grace per Phase 4 item 4).
- **Explicitly NO mobile/admin UI in this change** — the mobile "Riwayat edit" modal + "Diedit" label (`docs/02` §133, Phase 4 item 13) and the admin report-queue "has edit history" filter ([#191](https://github.com/aditrioka/nearyou-id/issues/191)) are deferred; captured as an explicit scope-boundary requirement so the follow-ups have a spec to MODIFY.

## Capabilities

### New Capabilities

- `post-editing`: Premium author-only post-content editing within a 30-minute creation window, with append-only temporal edit-history (`post_edits`), race-safe transactional atomicity, edit re-moderation, and a visibility-respecting history read. Groups with the existing `post-creation` / `post-likes` / `post-replies` post-domain capabilities.

### Modified Capabilities

<!-- None. The edit endpoint + post_edits store are net-new; no existing capability's REQUIREMENTS change. The history read consumes the existing visible-posts-view + user-blocking invariants without altering them, and re-moderation reuses the existing content-moderation capabilities without changing their requirements. -->

## Impact

- **Schema**: new migration `V22__post_edits.sql` (table + `post_edits_temporal_idx` UNIQUE + `post_edits_post_id_idx`). No `posts` ALTER (existing `content`/`updated_at`/`deleted_at`/`created_at`/`author_id` suffice).
- **Backend `:backend:ktor`**: new `PostEditService` (transactional, mirrors `CreatePostService`) + edit-history read query; two new routes under the existing posts route group; premium-status gate reusing the existing `users.subscription_status`; reuse of the existing moderation dispatch + `clientIp` + content-length-guard plumbing.
- **APIs**: `PATCH /api/v1/posts/{post_id}`, `GET /api/v1/posts/{post_id}/edits` (additive; no existing endpoint changes).
- **Dependencies**: none new — no `gradle/libs.versions.toml` change (Ktor / JDBC / PostGIS already present).
- **Tests**: new `*RoutesTest` (DB-tagged; autoClose pool, size 2 per the CI connection-budget rule) covering the spec scenarios incl. the Pre-Launch "Post edit concurrency tested" item.
- **Downstream unblocks**: mobile edit/history UI (Phase 4 item 13), admin report-queue edit-history filter ([#191](https://github.com/aditrioka/nearyou-id/issues/191)), chat context-card edit-history navigation (Phase 4 item 14).
- **In-flight coordination**: disjoint from all 9 open PRs; only migration-number adjacency with `revenuecat-subscription-webhook` (#291, V21) — this change takes V22.
