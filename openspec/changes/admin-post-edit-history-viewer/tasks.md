## 1. Repository (data layer)

- [ ] 1.1 Add a `PostEditHistoryRepository` in the `admin` package that, given a `post_id`, returns the live `posts` row (content + author + updated_at/created_at) or null if absent/hard-deleted. Read raw `posts` directly (admin lint-exemption); no `visible_*` view, no block-exclusion join.
- [ ] 1.2 Add a keyset-paginated read of `post_edits` for a `post_id` over `(post_id, edited_at DESC)`, fixed page size, served by the existing `post_edits_post_id_idx` (cursor = `edited_at`; `post_edits_temporal_idx` makes it tiebreaker-free). Return `content_snapshot`, `edited_at`, `edited_by`, and a `location_snapshot` presence/equality signal — NOT raw coordinates (compute the "location changed vs adjacent newer version" boolean server-side; never surface the geography to the template).
- [ ] 1.3 Parse the `post_id` path segment as a UUID; a non-UUID value resolves to not-found (no exception, no string interpolation into SQL — parameterized only).

## 2. Service (composition + versioning)

- [ ] 2.1 Add a service that composes the version list: live `posts` row as the newest version, then snapshots newest-first; assign presentation version labels ("Versi terbaru" / "Versi ke-N") over the composed list. N snapshots → N+1 versions.
- [ ] 2.2 Empty/edge handling: post exists but has zero snapshots → single live version; post absent/hard-deleted/non-UUID → not-found empty-state signal. Never throw to a 500.
- [ ] 2.3 Derive the per-row "lokasi berubah" indicator by comparing each snapshot's location to the adjacent newer version's location; expose only the boolean.

## 3. Route + templates (presentation)

- [ ] 3.1 Map `GET /admin/posts/{post_id}/edits` under the authenticated admin route subtree (auth gate inherited; unauthenticated → login redirect). Do NOT map any mutation method on the path.
- [ ] 3.2 Render full-page (plain GET) vs HTMX fragment (HTMX request) using the shipped admin viewer template idiom; HTML-escape every value (content, username, ids).
- [ ] 3.3 Render each version row: version label, content, `edited_at` (live version uses post created/updated), author as a `/admin/users?q=` deep-link, and the "lokasi berubah" indicator only when set. Add a back-link to the Report Queue.
- [ ] 3.4 Render the empty state (not-found / no-history) in the admin style; render the "older" keyset control only when a next-older page exists.
- [ ] 3.5 Make the viewer reachable from the Report-Queue offending-post row (a "Lihat riwayat edit" link to `/admin/posts/{post_id}/edits`), per mockup frame 8.
- [ ] 3.6 Consult mockup frame 8 (`dev/mockups/nearyou-admin-mockup.html`) before building the template — render it + generate the measurement annex (`dev/scripts/mockup-measure.sh`), translate to Pebble + HTMX + vendored CSS per `docs/11` § 3.6.

## 4. Tests

- [ ] 4.1 Authenticated GET renders the version table newest-first; unauthenticated GET redirects to login.
- [ ] 4.2 Live `posts` content is the newest version above the snapshots; N snapshots → N+1 versions, correctly ordered.
- [ ] 4.3 Post with zero edits renders exactly one (live) version with the no-history indicator.
- [ ] 4.4 Unknown `post_id`, non-UUID `post_id`, and hard-deleted post each render the empty state (no 500, no injection for SQL-metacharacter input).
- [ ] 4.5 Keyset pagination: page-size cap, non-overlapping next-older page, last page omits the older control, malformed cursor falls back to first page.
- [ ] 4.6 HTMX request returns only the fragment; plain GET returns the full page.
- [ ] 4.7 Content with markup is HTML-escaped, not executed; author cell deep-links to `/admin/users?q=`.
- [ ] 4.8 Location: a differing `location_snapshot` shows "lokasi berubah"; an unchanged one shows no indicator; raw coordinates never appear in the rendered output.
- [ ] 4.9 Read-only contract: a mutating method on the path is unmapped; serving the page writes no `admin_actions_log` row and mutates nothing.
- [ ] 4.10 `read_only` admin can view the history; a shadow-banned / auto-hidden post still renders for admins.
- [ ] 4.11 Ensure any new DB-tagged `*RoutesTest` pool autoCloses (CI connection-budget discipline, `docs/11` § 3.2).

## 5. Verification + DoD

- [ ] 5.1 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [ ] 5.2 Manually verify on the running admin panel (verify-loop): authenticated GET renders an edited post's history, a no-edits post, and a not-found post; capture evidence for the PR per `docs/11` § 5 Definition of Done.
- [ ] 5.3 Confirm no Flyway migration was added (read-only over V22 `post_edits` + `posts`).
