# Tasks: shadow-ban-feed-self-visibility

## 1. Feed queries (infra/supabase)

- [x] 1.1 `JdbcPostsTimelineRepository.nearby`: adopt the two-arm UNION ALL shape (design Decision 1) — arm 1 = shipped query + `p.author_id <> :viewer`, arm 2 = own-content self-arm (`FROM posts` + `JOIN users`, `author_id = :viewer AND deleted_at IS NULL`, same `ST_DWithin` on `display_location`), per-arm keyset + `ORDER BY` + `LIMIT`, V7/V8 joins + spatial scalar projections (`ST_Y`/`ST_X`/`ST_Distance`) in the outer SELECT; **single string template with the conditional keyset fragment interpolated per arm** (`${cursorPredicate}` — Decision 6 literal-structure pin; NOT separate `buildString` appends, which `BlockExclusionJoinRule` checks in isolation); `@AllowRawPostsRead` on the SQL-holding declaration with a self-arm justification; re-derive the positional parameter-binding order (≈20 binds) against the final literal.
- [x] 1.2 `JdbcPostsGlobalRepository.global`: same two-arm shape minus the spatial filter; same literal-structure + annotation discipline (≈12 binds).
- [x] 1.3 `JdbcPostsFollowingRepository`: NO query change; add the Decision-3 rationale comment (self-follow impossible → no self-arm) at the SQL literal so the next editor doesn't "fix" it.

## 2. Engagement paths (infra/supabase)

- [x] 2.1 `JdbcPostLikeRepository.resolveVisiblePost`: append the `UNION ALL` self-arm (design Decision 4.1); `@AllowRawPostsRead` with justification.
- [x] 2.2 `JdbcPostReplyRepository.resolveVisiblePost`: identical self-arm (keep the spec-pinned shape-identity with 2.1); same annotation.
- [x] 2.3 `JdbcPostReplyRepository.listByPost`: `JOIN visible_users` → `LEFT JOIN` + `(vu.id IS NOT NULL OR pr.author_id = :viewer)` author bypass (design Decision 4.2); update the literal's contract comment.

## 3. Tests (DB-tagged kotest, existing style)

- [ ] 3.1 `NearbyTimelineServiceTest`: shadow-banned author sees own in-radius post; second user does not see it; un-shadow-ban restores; own soft-deleted post hidden from author; own auto-hidden post visible to author + hidden from second user; pagination across the page-30 boundary with interleaved own-shadow-banned posts (no dup, no gap, own posts on BOTH pages); self rows carry `liked_by_viewer` + `reply_count`; `reply_count` stays viewer-independent on self rows (author's own reply not counted).
- [ ] 3.2 `GlobalTimelineServiceTest`: same scenario set minus radius mechanics (including the viewer-independent self-row counter pin).
- [ ] 3.3 `FollowingTimelineServiceTest`: shadow-banned viewer's own posts absent from Following (parity with normal users — pins Decision 3); source-scan test pinning the Following literal carries no `UNION ALL` / raw `FROM posts` arm / `author_id = :viewer` self-predicate.
- [ ] 3.4 Like path: shadow-banned author `POST /like` on own post → 204 + row inserted AND feed `liked_by_viewer = true` while `GET /likes/count` stays `visible_users`-filtered (own like not counted); own `GET /likes/count` → 200; second user → 404 on both; author's own soft-deleted post → 404 for the author; 404 body byte-identical across all invisibility causes INCLUDING the shadow-banned-author cause.
- [ ] 3.5 Reply path: shadow-banned author `POST /replies` on own post → 201; reply to own AUTO-HIDDEN post → 201; `GET /replies` on own post → 200 with the author's own replies visible; own soft-deleted post → 404 for the author on the reply path too; second user → 404; own reply visible to its shadow-banned author in ANOTHER author's visible thread; second user does not see that reply.
- [ ] 3.6 Literal-inspection tests (source-scan style per `ReplyEndpointsTest` precedent): Nearby + Global literals — visible arm reads `FROM visible_posts`; the ONLY raw `posts`/`users` references are the self arm's; `@AllowRawPostsRead` present on the SQL-holding declarations.
- [ ] 3.7 visible-posts-view "view stays viewer-agnostic" scenario: verify the existing `MigrationV20SmokeTest` full-shape pin satisfies byte-equivalence; extend it only if narrower.

## 4. Canonical docs (same-PR anti-drift)

- [x] 4.1 docs/05 § Timeline Implementation: refresh the Nearby + Global canonical SQL blocks to the shipped two-arm shape; add the Following no-self-arm note.
- [x] 4.2 docs/05 § Shadow Ban Implementation: amend the own-content-exception paragraph to name the feed/engagement self-arms (alongside the Repository own-content paths); amend the §476 reply read-path note for the shadow-ban author bypass.

## 5. Verification & PR hygiene

- [x] 5.1 `openspec validate shadow-ban-feed-self-visibility --strict` green.
- [ ] 5.2 Gates: `./gradlew ktlintCheck detekt :lint:detekt-rules:test` + `:backend:ktor:test`. NOT runnable locally in this remote session — the environment's network policy blocks Google Maven (`dl.google.com`/`maven.google.com` → 403 host_not_allowed), so Gradle cannot even configure the root build (AGP unresolvable), and there is no local Postgres. ALL Gradle lanes (lint + detekt + full test incl. `database`-tagged) run in PR CI only; poll checks after each push and fix failures there.
- [ ] 5.3 PR CI fully green including the `database`-tagged suite + `migrate-supabase-parity`.
- [ ] 5.4 qodo review requested via `/review` PR comment after apply commits; findings triaged.
- [ ] 5.5 PR body current at every phase boundary; manual-verification waiver stated explicitly (backend-only, no UI surface — docs/11 §5 item 3 N/A).

(A previously-drafted "file the search self-visibility follow-up issue" task was dropped at proposal review: `premium-search` already pins that exclusion as deliberate — see design § Deliberately unchanged.)

## 6. Staging smoke (pre-archive convention)

- [ ] 6.1 Manual staging branch deploy + live shadow-ban flip smoke (project.md § Staging deploy timing) — requires GCP access; if not executable from this remote session, surface explicitly in the PR body for operator follow-up instead of silently skipping.

## 7. Archive

- [ ] 7.1 Archive commit on the same branch (`openspec archive shadow-ban-feed-self-visibility`); `openspec validate --specs` green; no "TBD - created by archiving" strings under `openspec/specs/`.
- [ ] 7.2 Tick item #210 in `dev/audits/2026-06-10-holistic-audit/PROGRESS.md` § Remaining after wave 7 ("✔ shipped via PR #N").
