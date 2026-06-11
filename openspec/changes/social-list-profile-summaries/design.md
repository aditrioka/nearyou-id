# Design: social-list-profile-summaries

## Context

Three list endpoints return bare `{userId, createdAt}` rows: `GET /users/{id}/followers` and `GET /users/{id}/following` (`JdbcUserFollowsRepository` — `FROM follows` with bidirectional viewer-block NOT-IN subqueries, keyset `(created_at, user_id) DESC`, page cap 30) and `GET /blocks` (`JdbcUserBlockRepository.listOutbound` — `FROM user_blocks`, same keyset shape). Neither joins any users relation, so:

- Rendering a list forces a client N+1 against `GET /users/{id}` (audit 03-#6).
- Shadow-banned / soft-deleted users appear in follow lists as bare IDs whose profile fetch then 404s.
- The follow-list profile-target gate (`ensureProfileExists`) reads raw `users`, so `GET /users/{id}` → 404 while `GET /users/{id}/followers` → 200 for a hidden target, and `POST /follows/{id}` → 204/409 — a cross-endpoint differential that defeats the profile read's constant-404 (audit 03-#5).

Reference contracts already shipped:

- `user-profile-read` (design D4/D5): self read via raw `users` (annotated `SQL_SELF`); other read via `visible_users` + `NOT EXISTS` bidirectional `user_blocks`; all unresolvable causes → constant `404` body `{"error":{"code":"user_not_found"}}` via `respondText`.
- `chat-conversations` partner pattern: `LEFT JOIN visible_users` + `COALESCE` placeholders (`'akun_dihapus'` / `'Akun Dihapus'` / `FALSE`) — row survives, identity masked.
- `mobile-timeline-card-redesign` (in flight, PR #221): timeline rows gain `authorUsername`/`authorDisplayName` via `JOIN visible_users`, bare camelCase wire, declared explicitly against the stale snake_case spec examples.
- `docs/05-Implementation.md` §1272 (canonical visibility rules): "follower/following list JOINs `visible_users`" — the canonical doc already mandates the join this change introduces.

Constraints: docs/11 §3.1 layering (thin routes → service tx boundary → repo SQL); §3.3 cursor pagination; 16 code invariants (notably shadow-ban safety, `BlockExclusionJoinRule` bidirectional NOT-IN on protected tables); CI Postgres connection budget (reuse existing test pools).

## Goals / Non-Goals

**Goals:**

- One "profile summary" vocabulary — `userId`, `username`, `displayName`, `isPremium` — across every user-list row, identical in naming to `UserProfileResponse` and consistent with the timeline's `author*`-prefixed fields, so the mobile profile/follow screens (next change) reuse one list-item component.
- Kill the client N+1: a list page renders with zero follow-up profile fetches.
- Close the 03-#5 oracle: `/followers`, `/following`, and `POST /follows` answer the byte-identical constant 404 under exactly the conditions the profile read does.

**Non-Goals:**

- `POST/DELETE /blocks` semantics (block-create must stay usable against hidden targets — safety beats the residual oracle; see Decisions).
- Other target-taking endpoints that deliberately expose block/visibility state: `POST /api/v1/conversations` answers 403 for blocked pairs (user-visible symmetric friction per `docs/06-Security-Privacy.md` — by design, not debt) and `POST /api/v1/reports` resolves targets by existence. This change REDUCES the hidden-vs-gone differential surface (profile + lists + follow now agree); it does not claim to eliminate it system-wide.
- Profile `followerCount`/`followingCount` semantics (design D1 raw totals stays; only the spec's contrast note is updated).
- Follower-count/list parity, batch profile endpoint, notifications actor enrichment (#194), per-row `followedByViewer` (no consumer yet; add when a screen needs it).
- The `BlockExclusionJoinRule` lint gap on `visible_users` (documented stance in `user-profile-read`: scenarios are the guardrail).

## Decisions

### D1 — Summary shape: `{userId, username, displayName, isPremium, createdAt}`, bare camelCase

`username`/`displayName`/`userId` match `UserProfileResponse` field-for-field; the timeline uses the same vocabulary with an `author` prefix because its row is a post — a list row IS the user, so unprefixed. `isPremium` is included because the mockups render premium styling (gold name + `workspace_premium` badge) wherever a user identity appears, and the chat partner summary already carries it; formula is the `user-profile-read` one (`subscription_status = 'premium_active'` only — the same formula chat was aligned to when the 2026-06-10 audit fixed the 03-#9 divergence, so all three surfaces now agree). `createdAt` (edge timestamp) is retained from the shipped wire. `bio` is excluded: no mockup list row renders it, and it widens rows for nothing. Alternative considered: a batch profile endpoint (`GET /users?ids=…`) — rejected: still 2 round-trips per page, a second cache/authz surface, and the audit sketch + operator decision both name embedding.

### D2 — Follow lists: INNER `JOIN visible_users`; hidden users' edges disappear

Canonical docs/05 §1272 mandates this join (unqualified = INNER); the bare-`follows` implementation is the drift being fixed. Semantics: a shadow-banned/soft-deleted user must not be observable ANYWHERE a viewer can read — a "masked" placeholder row in a followers list would still leak existence-and-count, re-opening the oracle #211 closes. Contrast with chat (D3): there is no product contract that a follow edge "remains visible in history". LIMIT applies after the join, so the SQL keeps scanning until it fills the page with visible rows; the keyset cursor stays `(f.created_at, f.<side>_id)` from the `follows` table, unaffected by join columns (same discipline as the timeline's identity join: not in ORDER BY, not in the keyset predicate).

### D3 — Blocks list: `LEFT JOIN visible_users` + COALESCE placeholders (chat partner pattern verbatim)

A block-list row must survive a hidden target — it is the owner's only handle to find and unblock (bidirectional exclusion means the blocked user's profile 404s for the blocker). Dropping rows (INNER) would strand unblockable edges; raw-`users` identity would put a shadow-banned user's live identity in a non-admin read path (invariant #1) and make this list the one surface that diverges from the masking pattern. Placeholders match `chat-conversations` byte-for-byte (`'akun_dihapus'` / `'Akun Dihapus'` / `FALSE`). The transition signal this admits (a blocked user's row flipping to `akun_dihapus` reveals they were hidden) sits inside `docs/06-Security-Privacy.md`'s documented "Known Leak Surfaces (Accepted Risk)" stance, identical to the chat-conversations acceptance. No block-direction masking: rows are the owner's own outbound blocks; masking on counter-block would itself leak "X blocked you back". Pattern-Registry note: D2-vs-D3 is not a silent pattern fork — each follows the established precedent for its semantics (visibility-filtered public lists per docs/05 §1272 vs row-must-survive owner lists per the chat partner contract); this section is the declaration.

### D4 — List-target resolution adopts the profile-read gate; constant 404 via `respondText`

`ensureProfileExists` (raw `users`) is replaced by a two-path gate, mirroring `JdbcUserProfileReader` D5: self (`profileId == viewerId`) → raw-`users` existence with the own-content justification KDoc'd at the SQL-holding declaration (a shadow-banned viewer keeps their own lists). Implementation reality discovered at apply time: the `@AllowMissingBlockJoin` annotation class lives in `:backend:ktor` and the detekt ruleset only scans that module — `:infra:supabase`, where `JdbcUserFollowsRepository` lives, is not detekt-scanned at all, so the annotation is structurally inapplicable there and the KDoc + integration scenarios carry the justification (the same scenarios-are-the-guardrail stance `user-profile-read` documents for `visible_users`). Other-viewer path → `visible_users` + `NOT EXISTS` bidirectional `user_blocks`. Unresolvable → `ProfileUserNotFoundException` → the routes emit the constant `{"error":{"code":"user_not_found"}}` via `respondText` (byte-identical; replaces the current `respondError` map body with its `message` field). The gate stays a separate query from the list query: folding it in cannot distinguish "404 target" from "200 empty list". Cost: one PK/visible-view probe per request, same as today's raw probe.

### D5 — `POST /follows` joins the constant-404 contract; 409 `follow_blocked` removed

Today: hidden target → FK-satisfied INSERT → 204 (existence oracle); blocked pair → 409 (the body hides direction, but receiving 409-not-404 still tells a non-blocker "X blocked you"). The PRIMARY rationale is consistency, not secret-closing: the mobile client reaches follow actions through profiles, and profile-read D4 (the newest deliberate posture on this surface) answers 404 for the same causes — a 404-profile/409-follow pair is a contradictory contract for the screens landing next change. System-wide, block visibility remains deliberately non-uniform: chat-create keeps its 403 symmetric-friction posture per `docs/06-Security-Privacy.md` (see Non-Goals) — this change reduces the differential surface on the profile/follow path rather than eliminating it everywhere. New mapping: target unresolvable under the D4 gate (unknown / soft-deleted / shadow-banned / blocked-either-direction) → the same constant 404. **Gate placement**: inside `FollowService.follow`, AFTER `checkRateLimit` (routes stay SQL-free per docs/11 §3.1, and 404-probes keep burning the 50/h bucket — moving the gate before the limiter would hand enumeration probes a free pass; 429 therefore takes precedence over 404, which `SocialGraphRateLimitTest`'s "rejected before any transaction" pin already encodes). The repo-level bidirectional `user_blocks` SELECT + pair advisory lock inside `followInTx` is unchanged — it remains the transactional guard that prevents the edge (and now also backstops TOCTOU between gate and INSERT for the BLOCK half, mapped to the same 404); only the HTTP translation of `FollowBlockedException` changes. The VISIBILITY half deliberately has no in-tx re-check: a target shadow-banned between gate and commit still gets the edge — accepted (review-ratified) because it is not prober-controllable (requires an interleaved admin action), the edge is invisible at read time (lists INNER-JOIN `visible_users`), and counts are raw per D1. `DELETE /follows` stays 204-always (responds identically whether or not an edge existed — no oracle). UX cost is nil: a viewer can only reach a follow action from a profile they can see, and the mobile follow UI lands next change — this is the last cheap moment to change the contract. Alternative (keep 409, list-endpoints-only per the literal #211 text): rejected as leaving the POST half of the audit-named differential open, but isolated in its own spec requirement (the MODIFIED target-resolution + the REMOVED 409) so review can drop it without unwinding the rest.

### D6 — No migration, no new indexes

`follows_followee_idx`/`follows_follower_idx` drive the list scans; the identity join is a PK nested-loop per returned row (≤31/page); the gate is a PK/view probe. `visible_users` (V7) already exists. Revisit only if EXPLAIN on staging says otherwise — not at MVP scale.

### D7 — Wire/DTO mechanics

`FollowListItem`/`BlockListItem` gain `username`, `displayName`, `isPremium` (bare camelCase, no `@SerialName` — matching the shipped mixed-case convention and #221's explicit-declaration precedent). `FollowListRow` (`:core:data`) and `UserBlockRow` (which lives in the `infra/supabase` repo module, not `:core:data`) carry the new columns; `FollowService`/`BlockService` pagination (`PAGE_SIZE + 1` probe) is untouched. The unused non-tx `UserFollowsRepository.follow()` (zero production call sites) is deleted rather than left documenting the removed 409 contract. Spec response examples are corrected from never-shipped snake_case to the actual camelCase wire in the same deltas.

## Risks / Trade-offs

- **[Page-fill scans on heavily-hidden lists — perf AND timing side-channel]** A profile whose followers are mostly hidden makes the SQL walk more `follows` rows to fill a page; response time weakly correlates with hidden-follower density → bounded by the index order and page cap 30; at MVP scale negligible on both axes. Mitigation: none needed now; EXPLAIN check rides the staging smoke.
- **[Behavior change: hidden users vanish from follow lists]** Could surprise a viewer who saw N rows yesterday and N−1 today → this is the intended privacy posture (and counts were never list-consistent — D1 raw totals). Documented in the spec scenarios.
- **[409 removal is a breaking error-contract change]** Any out-of-tree consumer coded against `follow_blocked` breaks → grep confirms no client ships against it; the spec delta + PR body flag **BREAKING** loudly; doing it pre-client is the point.
- **[Residual oracle via `POST /blocks`]** A prober can still distinguish hidden-vs-gone by blocking (204 vs 404) → accepted deliberately: block-create must work against hidden harassers (safety > anti-oracle); cost is a real block + the 30/h limiter; recorded as an explicit negative requirement so the deferral is spec-visible.
- **[TOCTOU between the D4 gate and the INSERT]** A block landing between gate and `followInTx` is caught by the unchanged in-tx guard + pair lock → maps to the same constant 404; no window where the edge commits.
- **[Placeholder masking on the blocks list is itself a weak signal]** ("my blocked user became `akun_dihapus`") → identical to the accepted chat-conversations trade-off; the alternative (raw identity) is worse (invariant #1).

## Migration Plan

Code-only; single deploy, no schema steps. Rollback = revert the commit. Staging branch-deploy smoke pre-archive (docs/11 §5 item 4): seed a followers fixture incl. one shadow-banned member, verify enriched rows + the byte-identical 404 triple (unknown / hidden / blocked) + `POST /follows` 404 on a hidden target.

## Open Questions

(none — the one judgment call, D5's scope extension beyond the literal issue text, is flagged in the proposal and isolated for review.)
