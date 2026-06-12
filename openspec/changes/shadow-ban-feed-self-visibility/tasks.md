# Tasks: shadow-ban-feed-self-visibility

## 1. Feed queries (infra/supabase)

- [ ] 1.1 `JdbcPostsTimelineRepository.nearby`: adopt the two-arm UNION ALL shape (design Decision 1) — arm 1 = shipped query + `p.author_id <> :viewer`, arm 2 = own-content self-arm (`FROM posts` + `JOIN users`, `author_id = :viewer AND deleted_at IS NULL`, same `ST_DWithin` on `display_location`), per-arm keyset + `ORDER BY` + `LIMIT`, V7/V8 joins moved outside the union; `@AllowRawPostsRead` on the SQL-holding declaration with a self-arm justification.
- [ ] 1.2 `JdbcPostsGlobalRepository.global`: same two-arm shape minus the spatial filter; same annotation discipline.
- [ ] 1.3 `JdbcPostsFollowingRepository`: NO query change; add the Decision-3 rationale comment (self-follow impossible → no self-arm) at the SQL literal so the next editor doesn't "fix" it.

## 2. Engagement paths (infra/supabase)

- [ ] 2.1 `JdbcPostLikeRepository.resolveVisiblePost`: append the `UNION ALL` self-arm (design Decision 4.1); `@AllowRawPostsRead` with justification.
- [ ] 2.2 `JdbcPostReplyRepository.resolveVisiblePost`: identical self-arm (keep the spec-pinned shape-identity with 2.1); same annotation.
- [ ] 2.3 `JdbcPostReplyRepository.listByPost`: `JOIN visible_users` → `LEFT JOIN` + `(vu.id IS NOT NULL OR pr.author_id = :viewer)` author bypass (design Decision 4.2); update the literal's contract comment.

## 3. Tests (DB-tagged kotest, existing style)

- [ ] 3.1 `NearbyTimelineServiceTest`: shadow-banned author sees own in-radius post; second user does not see it; un-shadow-ban restores; own soft-deleted post hidden from author; own auto-hidden post visible to author + hidden from second user; pagination across the page-30 boundary with interleaved own-shadow-banned posts (no dup/gap); self rows carry `liked_by_viewer` + `reply_count`.
- [ ] 3.2 `GlobalTimelineServiceTest`: same scenario set minus radius mechanics.
- [ ] 3.3 `FollowingTimelineServiceTest`: shadow-banned viewer's own posts absent from Following (parity with normal users — pins Decision 3).
- [ ] 3.4 Like path: shadow-banned author `POST /like` on own post → 204 + row inserted; own `GET /likes/count` → 200; second user → 404 on both; author's own soft-deleted post → 404 for the author.
- [ ] 3.5 Reply path: shadow-banned author `POST /replies` on own post → 201; `GET /replies` on own post → 200 with the author's own replies visible; second user → 404; own reply visible to its shadow-banned author in ANOTHER author's visible thread; second user does not see that reply.

## 4. Canonical docs (same-PR anti-drift)

- [ ] 4.1 docs/05 § Timeline Implementation: refresh the Nearby + Global canonical SQL blocks to the shipped two-arm shape; add the Following no-self-arm note.
- [ ] 4.2 docs/05 § Shadow Ban Implementation: amend the own-content-exception paragraph to name the feed/engagement self-arms (alongside the Repository own-content paths); amend the §476 reply read-path note for the shadow-ban author bypass.

## 5. Verification & PR hygiene

- [ ] 5.1 `openspec validate shadow-ban-feed-self-visibility --strict` green.
- [ ] 5.2 Local gates: `./gradlew ktlintCheck detekt :lint:detekt-rules:test` + `:backend:ktor:test -Dkotest.tags='!database'` (no local Postgres in this environment — DB-tagged lanes run in PR CI; poll `gh pr checks` after each push).
- [ ] 5.3 PR CI fully green including the `database`-tagged suite + `migrate-supabase-parity`.
- [ ] 5.4 qodo review requested via `/review` PR comment after apply commits; findings triaged.
- [ ] 5.5 PR body current at every phase boundary; manual-verification waiver stated explicitly (backend-only, no UI surface — docs/11 §5 item 3 N/A).
- [ ] 5.6 File the search self-visibility `follow-up` issue (design § Deliberately unchanged).

## 6. Staging smoke (pre-archive convention)

- [ ] 6.1 Manual staging branch deploy + live shadow-ban flip smoke (project.md § Staging deploy timing) — requires GCP access; if not executable from this remote session, surface explicitly in the PR body for operator follow-up instead of silently skipping.

## 7. Archive

- [ ] 7.1 Archive commit on the same branch (`openspec archive shadow-ban-feed-self-visibility`); `openspec validate --specs` green; no "TBD - created by archiving" strings under `openspec/specs/`.
- [ ] 7.2 Tick item #210 in `dev/audits/2026-06-10-holistic-audit/PROGRESS.md` § Remaining after wave 7 ("✔ shipped via PR #N").
