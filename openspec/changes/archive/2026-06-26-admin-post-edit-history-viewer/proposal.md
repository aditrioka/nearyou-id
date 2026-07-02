## Why

When a post is reported, moderators currently have no in-panel way to see how that post's content changed over time — a post can be reported, then edited within the 30-minute Premium window to soften or alter the offending text. The `post_edits` ledger (V22) already captures every before-edit snapshot, but no admin surface reads it. This viewer closes that gap and is the read-side pair of the deferred Report-Queue "post has edit history" prioritization filter ([#191](https://github.com/aditrioka/nearyou-id/issues/191)): both serve prioritizing review of content edited after being reported.

## What Changes

- **New read-only admin route `GET /admin/posts/{post_id}/edits`** — renders one post's complete temporal version history: the current live version (from `posts`) plus every prior before-edit snapshot in `post_edits`, composed into a version-numbered list (`ROW_NUMBER()` → "Versi ke-N"), most-recent first.
- Each version row shows the content snapshot, the `edited_at` timestamp, and the editing user (the author — edits are self-only) deep-linking to the shipped `/admin/users?q=` lookup, plus a back-link to the Report Queue for the report→edit-history triage loop.
- Mirrors the shipped admin read-viewer idiom exactly (template: `admin-block-registry`, `admin-hard-delete-queue`): keyset pagination over `post_edits (post_id, edited_at DESC)` at a fixed page size; HTML-escaped HTMX partial swap + plain-`GET` progressive enhancement; accessible to **every** authenticated admin role (including `read_only`); unauthenticated → redirect to login.
- Robust edge handling: a post with no edits renders just its current version (not an error); a non-existent / hard-deleted post is handled safely (no 500); SQL-metacharacter and over-long inputs are treated as literals.
- **Strictly read-only** — adds only the `GET` route; mutation methods stay unmapped; serving the viewer writes **no** `admin_actions_log` row, mutates nothing, and notifies no one. Content-moderation actions remain in the Report Queue.
- **No Flyway migration** — reads the existing `post_edits` (V22) and `posts` tables via the existing `post_edits_post_id_idx (post_id, edited_at DESC)` index. Reads raw tables directly (the admin module is lint-exempt from the `visible_*` / `display_location` rules).

## Capabilities

### New Capabilities
- `admin-post-edit-history`: Read-only admin viewer for a single post's full edit-version history (`GET /admin/posts/{post_id}/edits`), composing the live post with its `post_edits` snapshots, with keyset pagination, HTMX progressive enhancement, role-open read access, and a strictly read-only / no-audit / no-mutation contract.

### Modified Capabilities
<!-- None. This adds a new admin read surface; it changes no existing capability's requirements. The post-editing write path (post_edits ledger) and the admin-panel scaffold are unchanged. -->

## Impact

- **Code**: new `admin` route + service + repository under `backend/ktor/.../admin` (backend layering per `docs/11` § Pattern Registry), plus an HTMX/Pebble template and a sidebar/entry reachable from a report row's offending post. No changes to the post-editing write path.
- **Schema / migrations**: none. Existing `post_edits` (V22) + `posts`, served by `post_edits_post_id_idx`.
- **APIs**: one new admin-only HTML route `GET /admin/posts/{post_id}/edits` (+ HTMX fragment variant). No public/mobile API change.
- **Layers (per `docs/12` cross-layer cohesion)**: single-layer (admin-only) **by design** — this is an operator surface, not a user-facing capability, so no mobile/client counterpart is required. Users already see their own edit history through the existing product path; this adds only the moderator view.
- **Visual reference**: admin mockup board frame 8 (`dev/mockups/nearyou-admin-mockup.html`); binding rule `docs/11` § 3.6.
