## 1. Repository (data layer)

- [ ] 1.1 Add a `PostEditHistoryRepository` in the `admin` package that, given a `post_id`, returns the live `posts` row (content + author + updated_at/created_at) or null if absent/hard-deleted. Read raw `posts` directly (admin lint-exemption); no `visible_*` view, no block-exclusion join.
- [ ] 1.2 Add a keyset-paginated read of `post_edits` for a `post_id` over `(post_id, edited_at DESC)`, fixed page size, served by the existing `post_edits_post_id_idx` (cursor = `edited_at`; `post_edits_temporal_idx` makes it tiebreaker-free). Return `content_snapshot`, `edited_at`, `edited_by`, and a `location_snapshot` presence/equality signal — NOT raw coordinates (compute the "location changed vs adjacent newer version" boolean server-side; never surface the geography to the template). The newest snapshot's location-change baseline is the **live `posts` row's location** (read it in 1.1), since its adjacent newer version is the live post, not another snapshot — read that location only to compute the boolean, never to render.
- [ ] 1.3 Parse the `post_id` path segment as a UUID; a non-UUID value resolves to not-found (no exception, no string interpolation into SQL — parameterized only).

## 2. Service (composition + versioning)

- [ ] 2.1 Add a service that composes the version list: live `posts` row as the newest version, then snapshots newest-first; assign presentation version labels ("Versi terbaru" / "Versi ke-N") over the composed list. N snapshots → N+1 versions; the oldest version row is the original pre-first-edit content.
- [ ] 2.2 Empty/edge handling: post exists but has zero snapshots → single live version; post absent/hard-deleted/non-UUID → not-found empty-state signal. Never throw to a 500.
- [ ] 2.3 Derive the per-row "lokasi berubah" indicator by comparing each snapshot's location to the adjacent newer version's location (the newest snapshot compares against the live `posts` location from 1.1); expose ONLY the boolean. The per-version view model / DTO MUST carry no `GEOGRAPHY` / coordinate field — the location signal leaves the service as a boolean and nothing else.

## 3. Route + templates (presentation)

- [ ] 3.1 Map `GET /admin/posts/{post_id}/edits` under the authenticated admin route subtree (auth gate inherited; unauthenticated → login redirect). Do NOT map any mutation method on the path.
- [ ] 3.2 Render full-page (plain GET) vs HTMX fragment (HTMX request) using the shipped admin viewer template idiom; HTML-escape every value (content, username, ids).
- [ ] 3.3 Render each version row: version label, content, `edited_at` (live version uses post created/updated), author as a `/admin/users?q=` deep-link, and the "lokasi berubah" indicator only when set. Add a back-link to the Report Queue.
- [ ] 3.4 Render the empty state (not-found / no-history) in the admin style; render the "older" keyset control only when a next-older page exists.
- [ ] 3.5 Wire the report→edit-history triage loop both ways: add a "Lihat riwayat edit" link from the Report-Queue offending-post row to `/admin/posts/{post_id}/edits`, and a back-link from the viewer to the originating report/Report Queue, per mockup frame 8 (this loop is the capability's stated justification — not optional polish).
- [ ] 3.6 Consult mockup frame 8 (`dev/mockups/nearyou-admin-mockup.html`) before building the template — render it + generate the measurement annex (`dev/scripts/mockup-measure.sh`), translate to Pebble + HTMX + vendored CSS per `docs/11` § 3.6.

## 4. Tests

- [ ] 4.1 Authenticated GET renders the version table newest-first; unauthenticated GET responds `302` → `Location: /admin/login`.
- [ ] 4.2 Live `posts` content is the newest version above the snapshots; N snapshots → N+1 versions, correctly ordered; the oldest version row equals the original pre-first-edit content.
- [ ] 4.3 Post with zero edits renders exactly one (live) version with the no-history indicator.
- [ ] 4.4 Empty-state branches each render with HTTP `200` (no 500): (a) unknown `post_id`, (b) non-UUID `post_id` (treated as literal not-found, no injection for SQL-metacharacter input), (c) hard-deleted post.
- [ ] 4.5a Keyset pagination — first page is capped at the fixed page size and exposes the "older" control when more snapshots exist.
- [ ] 4.5b Following the cursor returns the next-older, non-overlapping, gap-free page.
- [ ] 4.5c Last page omits the older control; a post with **exactly `pageSize` snapshots** also omits it (live version doesn't count toward the snapshot page budget — the off-by-one boundary).
- [ ] 4.5d Malformed cursor falls back to the first page; snapshots seeded at distinct `edited_at` paginate in a stable total order across the boundary with no duplicate/skip; the live version appears only on the first page, never on a later page.
- [ ] 4.6 HTMX request returns only the fragment; plain GET returns the full page.
- [ ] 4.7 Content with markup is HTML-escaped, not executed; author cell deep-links to `/admin/users?q=`; a version whose `edited_by` user was hard-deleted renders the author cell safely (no NPE, link degrades gracefully).
- [ ] 4.8 Location: a differing `location_snapshot` shows "lokasi berubah" (incl. the newest snapshot compared against the live post's location); an unchanged one shows no indicator; raw coordinates never appear in the rendered output AND the per-version view model / DTO exposes no geography/coordinate field (assert at the type boundary, not only the rendered HTML).
- [ ] 4.9 Read-only contract: a mutating method on the path is unmapped; serving the page writes no `admin_actions_log` row and mutates nothing.
- [ ] 4.10 `read_only` admin can view the history; a shadow-banned / auto-hidden post still renders for admins.
- [ ] 4.11 Ensure any new DB-tagged `*RoutesTest` pool autoCloses (CI connection-budget discipline, `docs/11` § 3.2).

## 5. Verification + DoD

- [ ] 5.1 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [ ] 5.2 Manually verify on the running admin panel (verify-loop): authenticated GET renders an edited post's history, a no-edits post, and a not-found post; capture evidence for the PR per `docs/11` § 5 Definition of Done.
- [ ] 5.3 Confirm no Flyway migration was added (read-only over V22 `post_edits` + `posts`).
