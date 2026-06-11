# Proposal: social-list-profile-summaries

## Why

The social-list endpoints (`GET /users/{id}/followers`, `GET /users/{id}/following`, `GET /blocks`) return bare `{userId, createdAt}` rows, forcing the client into an N+1 against the single-profile endpoint (a 30-row followers page = 30 sequential profile GETs, ≈60 follows-COUNT subqueries — 2026-06-10 audit finding 03-#6, operator-approved on [#196](https://github.com/aditrioka/nearyou-id/issues/196)). Separately, those list endpoints resolve the profile target through raw `users`, so a prober can distinguish "hidden-from-me" (shadow-banned / soft-deleted / blocked) from "gone" by comparing them against the profile read's constant-404 — a side-channel that defeats the `user-profile-read` leak-safety design (audit finding 03-#5, operator-approved as [#211](https://github.com/aditrioka/nearyou-id/issues/211)). Both must land NOW because the mobile profile + follow screens are the next critical-path change: shipping the enriched contract first means the client never codes against the bare-ID shape or the leaky 404/409 semantics.

## What Changes

- **Followers/following rows embed a profile summary** `{userId, username, displayName, isPremium, createdAt}` (bare camelCase wire), sourced via `JOIN visible_users` — which `docs/05-Implementation.md` §1272 already mandates for these lists; the bare-`follows` implementation is the drift. Shadow-banned / soft-deleted users' edges consequently disappear from follower/following lists (**BREAKING** list-row shape + row-set change; no shipped client consumes these endpoints yet).
- **Blocks-list rows embed the same summary vocabulary** via `LEFT JOIN visible_users` + COALESCE placeholders (`akun_dihapus` / `Akun Dihapus` / `isPremium = false`) — the `chat-conversations` partner pattern: block rows MUST survive hidden targets so the owner can always find and unblock them (**BREAKING** row shape).
- **`/followers` + `/following` adopt the profile constant-404 contract**: target resolution moves from raw-`users` existence to `visible_users` + bidirectional `user_blocks` (self resolves via raw `users` so a shadow-banned viewer keeps their own lists); all unresolvable causes answer the byte-identical constant body `{"error":{"code":"user_not_found"}}` (**BREAKING**: previously 200 for hidden/blocked targets and a `message`-bearing 404 body for unknown targets).
- **`POST /follows/{user_id}` target resolution aligns to the same constant-404**: hidden targets currently yield 204 (a free existence oracle) and blocked pairs yield 409 `follow_blocked` (whose very existence reveals "some block exists" → "X blocked you" to a non-blocker); both become the constant 404 (**BREAKING**: the 409 contract is removed). The in-transaction pair-lock + `user_blocks` guard stays — blocks still prevent the edge; only the HTTP mapping changes. `DELETE /follows` stays 204-always (returns nothing, no oracle). This is the one item beyond the literal #211 text, included first-class because the finding (03-#5) explicitly names the POST differential and the follow-button client lands next change; it is isolated in its own spec requirement + task section so review can cleanly drop it.
- **`POST/DELETE /blocks` deliberately keep their current semantics** (no 404-alignment): a user must be able to block a shadow-banned harasser, so block-create stays fail-open for hidden-but-existing targets. The residual existence-oracle via `POST /blocks` is accepted and documented (costs a real block + the 30/h rate-limit bucket).
- **Spec response examples corrected to the shipped camelCase wire** (`userId`/`createdAt`/`nextCursor`); the current spec text shows snake_case that was never what `FollowRoutes.kt`/`BlockRoutes.kt` shipped — same correction `mobile-timeline-card-redesign` made for the timeline specs.
- **`user-profile-read` counts-contrast note updated**: `followerCount`/`followingCount` stay RAW totals (design D1); the note now also records that the lists are visibility-filtered, not just viewer-block-filtered.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `follow-system`: follower/following list rows embed the profile summary and are sourced via `visible_users`; list-target resolution + `POST /follows` adopt the constant-404 contract (404 body byte-identical across unknown / soft-deleted / shadow-banned / blocked-either-direction, replacing the 409 `follow_blocked` and the `message`-bearing 404); response examples corrected to shipped camelCase.
- `user-blocking`: `GET /blocks` rows embed the profile summary via the chat-partner LEFT-JOIN + COALESCE masking pattern; block-action endpoints explicitly keep existing semantics; response example corrected to shipped camelCase.
- `user-profile-read`: the raw-totals (D1) requirement's contrast note extended to cover visibility filtering of the lists (counts behavior itself unchanged).

## Impact

- **Code**: `FollowRoutes.kt` (list DTO + constant-404 + follow 409→404 mapping), `BlockRoutes.kt` (list DTO), `FollowService.kt` / `BlockService.kt` (row types through pagination), `JdbcUserFollowsRepository.kt` (list SQL + resolution gate replacing `ensureProfileExists`), `JdbcUserBlockRepository.kt` (`listOutbound` SQL), `:core:data` row types (`FollowListRow`, `UserBlockRow`) and exception mapping (`FollowBlockedException` → constant 404). No Flyway migration; no new indexes (`follows_follower_idx`/`follows_followee_idx` + `users` PK cover the joins at MVP scale).
- **API**: wire changes to three list responses + two error contracts. Grep of `mobile/` and `shared/` confirms no shipped client consumes these endpoints — this is exactly the window to break them.
- **Specs/tests**: `FollowEndpointsTest` block-409 tests replaced by constant-404 tests; new differential-probe tests assert byte-identical status+body across all hidden causes and equality with the profile route's 404 body. Existing pagination/order/cursor tests preserved.
- **Issues**: closes [#211](https://github.com/aditrioka/nearyou-id/issues/211); closes the social-list action item on [#196](https://github.com/aditrioka/nearyou-id/issues/196) via comment (the issue itself stays open for the mobile profile screen).
