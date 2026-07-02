## Context

The Premium post-editing capability (`mobile-post-editing` / `post-editing`, V22) lets an author edit a post within a 30-minute window. Each edit appends a **before-edit snapshot** to `post_edits` inside the same transaction that updates `posts`:

```
post_edits(id, post_id, edited_at DEFAULT clock_timestamp(),
           content_snapshot VARCHAR(280), location_snapshot GEOGRAPHY, edited_by)
-- indexes: post_edits_temporal_idx UNIQUE(post_id, edited_at)
--          post_edits_post_id_idx (post_id, edited_at DESC)
```

The current live content lives in `posts.content` / `posts.updated_at`; `post_edits` holds only the **superseded** versions. So a post that has been edited N times has N snapshot rows, and the full version list is `[live posts row] + [N snapshots, newest-first]` — N+1 versions total.

Moderators have no in-panel way to read this. The Report Queue (shipped) deep-links a report to the offending post but cannot show how the post's text evolved — a real evasion vector (report, then soften the text within the edit window). This viewer is the read pair of the deferred Report-Queue "post has edit history" filter ([#191](https://github.com/aditrioka/nearyou-id/issues/191)).

The admin panel is a Ktor + Pebble + HTMX subtree with a shipped, repeated read-viewer idiom (`admin-block-registry`, `admin-hard-delete-queue`, `admin-rejected-identifiers-viewer`, the grace/privacy monitors): authenticated GET, keyset pagination, HTML-escaped HTMX-fragment-or-full-page render, username deep-links, role-open reads, strictly read-only.

## Goals / Non-Goals

**Goals:**
- A read-only `GET /admin/posts/{post_id}/edits` rendering one post's complete, version-numbered edit history (live version + every snapshot), newest-first.
- Reuse the shipped admin read-viewer pattern verbatim — no new architectural pattern (per `docs/11` § Pattern Registry, this is the established backend route → service → repository layering + the HTMX-fragment render contract).
- Close the report→edit-history triage loop with a back-link to the Report Queue and an author deep-link to `/admin/users?q=`.
- Zero new schema: served by the existing `post_edits_post_id_idx`.

**Non-Goals:**
- No mutation of any kind — no redaction, no hide, no edit, no `admin_actions_log` write. Content-moderation actions stay in the Report Queue (`admin-report-queue` + resolution actions).
- No mobile/client surface — users already see their own edit history via the product path; this is the operator view only (single-layer by design, declared per `docs/12`).
- No `post_edits` schema change, no Report-Queue filter change (#191 stays deferred — this is its read pair, not its implementation).
- No map / precise-geo rendering of `location_snapshot` (see Decision 4).

## Decisions

**Decision 1 — Compose the live `posts` row as "Versi terbaru" (version 1), snapshots below.**
`post_edits` holds only superseded content, so showing snapshots alone would omit the version a reader most needs (the current text). The repository returns the live `posts` row as the newest version, then the `post_edits` snapshots in `edited_at DESC` order. Version numbering is presentation-side `ROW_NUMBER()`-style ("Versi terbaru" / "Versi ke-N") over the composed, ordered list.
*Alternative considered:* show only `post_edits` rows — rejected: a moderator triaging a reported post needs the current content first, and "no edits" must still render the post, not an empty page.
*Note — intentional divergence from the public endpoint:* the shipped public `GET /api/v1/posts/{post_id}/edits` returns snapshots only and numbers them oldest-first ("Versi ke-1" = oldest). This admin viewer deliberately differs — it composes the live row as "Versi terbaru" and numbers newest-first — because a moderator reads top-down from the current text. The label semantics intentionally do not match the public surface; this is a moderation-ergonomics choice, not a drift.

**Decision 2 — Keyset pagination over `post_edits (post_id, edited_at DESC)`, fixed page size, mirroring the shipped viewers.**
Edit counts are bounded (a 30-min window) but unbounded in principle across a post's life; pagination keeps the contract identical to the other admin viewers and is index-served (no migration). The cursor is `(edited_at)` within a fixed `post_id`; `post_edits_temporal_idx` guarantees `edited_at` is unique per post, so the keyset has no tiebreaker ambiguity. The live `posts` version sits above the paginated snapshot list on the first page only.
*Alternative considered:* offset pagination — rejected: the repo standardizes on keyset (consistency + no deep-offset cost).

**Decision 3 — Read raw `posts` / `post_edits` directly (admin module lint-exemption), no `visible_*` view, no block/shadow-ban join.**
Admin moderation must see content regardless of shadow-ban / block / auto-hide state — that is the entire point of a moderation viewer, and the admin module is explicitly exempt from `RawFromPostsRule` / `BlockExclusionJoinRule` / `display_location`. The repository SQL lives in the `admin` package.

**Decision 4 — Do NOT render raw coordinates from `location_snapshot`.**
`location_snapshot` is the **actual** (un-fuzzed) `GEOGRAPHY`. The admin module is lint-exempt from `display_location`, so rendering it would not trip CI — but surfacing a user's precise historical coordinates in a list view is a privacy choice, not a lint question. This viewer is about **content** evolution for moderation triage; location is not needed to make that call. We render only a neutral **"lokasi berubah"** indicator when a snapshot's `location_snapshot` differs from the next version's, and never the coordinates themselves. The comparison is reduced to a **boolean in the repository/service layer** — the per-version view model handed to the template carries no geography/coordinate field at all, so coordinates cannot leak via the template, an HTMX `hx-vals`, a debug attribute, or a serialized JSON island (the guard is at the type boundary, not just the rendered string). The newest snapshot's baseline for this comparison is the **live `posts` row's location** (its adjacent newer version is the live post, not another snapshot); that location is read only to compute the boolean, never to render.
*Alternative considered:* show a coarse city/region label — rejected for MVP: adds a reverse-geocode dependency for marginal moderation value; can be a follow-up if a real need appears.

**Decision 5 — Edge handling: post-not-found and no-edits both render gracefully.**
A malformed / unknown / hard-deleted `post_id` renders an empty-state page (admin-styled "post tidak ditemukan / tanpa riwayat"), never a 500. A valid post with zero snapshots renders just its live version. `post_id` path segment is parsed as a UUID; a non-UUID value is treated as not-found (literal, not an error, no injection).

## Risks / Trade-offs

- **[A reported post is hard-deleted before review → `posts` row gone, `post_edits` cascade-deleted]** → By V22 design `post_edits` is `ON DELETE CASCADE` from `posts`, so once the post is hard-deleted there is no history to show. The viewer renders the not-found empty state. This is acceptable: post-hard-delete the moderation decision is moot. Documented, not mitigated.
- **[Reader assumes `post_edits` already includes the current text]** → It does not (it holds only superseded snapshots); composing the live `posts` row as the newest version (Decision 1) is the correctness crux. The spec pins this with an explicit scenario.
- **[Location omission hides a relevant signal]** → Accepted trade-off (Decision 4); the "lokasi berubah" indicator preserves the *fact* of a location change without leaking coordinates. Revisit only on a demonstrated moderation need.
- **[Pattern drift]** → None expected: this reuses the shipped admin read-viewer pattern. No `docs/11` § Pattern Registry amendment is required (no new pattern, no deviation).

## Migration Plan

No database migration. Deploy is route-additive and read-only:
1. Ship the route + service + repository + template behind the existing admin auth gate.
2. No data backfill, no flag. The route appears once merged + deployed to staging.
3. **Rollback**: revert the PR — the route disappears; nothing else is touched (no schema, no data, no other surface).

## Open Questions

- None blocking. The entry point (a "Lihat riwayat edit" link from the Report-Queue offending-post row vs. a standalone lookup) is an apply-time wiring detail; the canonical entry is the report row per frame 8, with the route also directly addressable by `post_id`.
