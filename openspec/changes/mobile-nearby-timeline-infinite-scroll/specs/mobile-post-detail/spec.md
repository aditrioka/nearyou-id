## RENAMED Requirements

- FROM: `### Requirement: By-id post fetch and replies infinite-scroll are deferred`
- TO: `### Requirement: By-id post fetch is deferred`

## MODIFIED Requirements

### Requirement: By-id post fetch is deferred

This change SHALL NOT implement a `GET /api/v1/posts/{post_id}` by-id fetch for the post-detail header — the header is built from the `PostDetailRoute` nav args. (The backend by-id endpoint shipped as the `single-post-read` capability, tracked by GitHub issue [#202](https://github.com/aditrioka/nearyou-id/issues/202); it is consumed by the notifications deep-link change, not this screen.) Replies cursor load-more is **no longer deferred** — it is implemented by this change per the § "Replies list wires cursor load-more via PostDetailViewModel" requirement, which closes the replies half of GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188).

#### Scenario: No by-id fetch is issued for the header

- **WHEN** inspecting the post-detail screen for a `GET /api/v1/posts/{post_id}` by-id call
- **THEN** the post header is built from the `PostDetailRoute` nav args AND no by-id `GET /api/v1/posts/{post_id}` request is issued by this screen

#### Scenario: By-id deferral is tracked; replies load-more is no longer deferred

- **WHEN** inspecting the project's open GitHub issues (label `follow-up`) and this screen's replies paging
- **THEN** GitHub issue [#202](https://github.com/aditrioka/nearyou-id/issues/202) tracks the by-id endpoint (shipped as `single-post-read`, unconsumed by this screen) AND the replies list now issues `cursor=`-bearing follow-up `GET /replies` requests (the replies half of issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) is implemented, not deferred)

### Requirement: Replies list mirrors the shipped snake_case wire with loading, empty, and error states

`ReplyApiClient` SHALL issue `GET /api/v1/posts/{post_id}/replies` and parse `@Serializable` DTOs whose wire names match the SHIPPED backend serialization in `backend/ktor/.../engagement/ReplyRoutes.kt` (`ReplyDto` / `ReplyListResponse`) — **snake_case**, NOT the timelines' camelCase. Specifically: `ReplyDto` = `id` (bare String), `@SerialName("post_id") postId`, `@SerialName("author_id") authorId`, `content` (bare), `@SerialName("is_auto_hidden") isAutoHidden` (Boolean), `@SerialName("created_at") createdAt`, `@SerialName("updated_at") updatedAt: String?`, `@SerialName("deleted_at") deletedAt: String?`; `ReplyListResponse` = `replies: List<ReplyDto>` (bare), `@SerialName("next_cursor") nextCursor: String? = null`. The `next_cursor` key is snake_case and MUST differ from the timelines' camelCase `nextCursor`. The screen SHALL render reply cards showing `content` + the `created_at` treatment only — NO author identity (the wire carries only `author_id`, never rendered) — within one of: a loading state (`stringResource(Res.string.timeline_loading)`), an empty state (`stringResource(Res.string.post_detail_replies_empty)`), the reply-card list, or an error state (`stringResource(Res.string.signin_error_network)` + a `stringResource(Res.string.cta_retry)` control). `next_cursor` SHALL be parsed + retained AND SHALL drive replies cursor load-more per the § "Replies list wires cursor load-more via PostDetailViewModel" requirement (this supersedes the prior deferral). A returned reply MAY carry `is_auto_hidden = true` ONLY when it is the viewer's OWN reply (the backend's author-bypass `is_auto_hidden = FALSE OR author_id = :viewer` lives in the `PostReplyRepository.listByPost` query — impl in `core/data/.../repository/PostReplyRepository.kt`, surfaced via `engagement/ReplyService.list` — so no other reply with the flag set is ever returned); in v1 the `is_auto_hidden` flag SHALL be **parsed but NOT surfaced** (the viewer's own auto-hidden reply renders identically to a live reply, matching the backend's author-bypass intent) — no "under review" badge or dimming is added in this change. Similarly `deleted_at` is faithfully parsed (DTO mirrors the wire) but is effectively dead on this list path (the backend excludes `deleted_at IS NOT NULL` rows).

#### Scenario: Replies parse against the shipped snake_case wire

- **GIVEN** a `MockEngine` returning `200` with `{ "replies": [ { "id": "...", "post_id": "...", "author_id": "...", "content": "hi", "is_auto_hidden": false, "created_at": "2026-06-06T00:00:00Z", "updated_at": null, "deleted_at": null } ], "next_cursor": "tok" }`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the reply exposes `content = "hi"` AND `nextCursor = "tok"`

#### Scenario: camelCase next_cursor does NOT populate — negative guard against the timeline assumption

- **GIVEN** a `MockEngine` returning `{ "replies": [], "nextCursor": "tok" }` (the timelines' camelCase key, NOT the shipped reply wire)
- **THEN** `ReplyListResponse.nextCursor` is `null` (the camelCase key does not bind under the `@SerialName("next_cursor")` mapping) — a fixture MUST assert this so the casing regression cannot slip in

#### Scenario: Empty replies show the empty-state copy

- **WHEN** the replies outcome is `Loaded` with an empty list
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_replies_empty)`

#### Scenario: Reply card renders no author identity

- **GIVEN** a reply with `author_id = "11111111-1111-1111-1111-111111111111"`
- **WHEN** the reply card renders
- **THEN** the rendered tree contains NO node whose text contains `"11111111-1111-1111-1111-111111111111"` (only `content` + the timestamp treatment appear)

## ADDED Requirements

### Requirement: Replies list wires cursor load-more via PostDetailViewModel

The post-detail replies list SHALL wire cursor-based load-more following `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern". To hold the replies list + paging state robustly (rather than in composition-local `var`s), this change SHALL introduce a `PostDetailViewModel` resolved via `viewModel { … }` scoped to the post-detail NavEntry (mirroring the timeline-VM migration in [#167](https://github.com/aditrioka/nearyou-id/pull/167)), owning: the replies list, the current replies `next_cursor`, and the load-more `isLoadingMore` / `endReached` / `loadMoreError` state (via the shared load-more controller). `PostDetailFlow` SHALL gain a cursor-bearing replies load-more path (e.g. `loadMoreReplies(postId, cursor)`) issuing `GET /api/v1/posts/{post_id}/replies?cursor=…`; the reply DTO `next_cursor` is snake_case (`@SerialName("next_cursor")`, distinct from the timelines' bare camelCase `nextCursor`). Migrating the existing like + reply-composer composition-local state into the ViewModel is OUT of scope (a noted follow-up); this requirement moves only the replies-list + paging state.

Because replies render as `items()` in the SAME `LazyColumn` as the post header + like-row items (the composer is a separate `bottomBar`), scroll-end detection SHALL key off the replies-items region (so it does not mis-fire while only the header/like-row is on screen). The existing optimistic new-reply behavior SHALL be preserved: a successfully posted reply is **prepended** to the VM-held replies list (above page 1) and the reply count increments, exactly as today — load-more appends pages at the END and never interferes with the prepend.

#### Scenario: Replies state is held in PostDetailViewModel, not composition-local

- **WHEN** inspecting the post-detail screen and its state holder
- **THEN** the replies list + replies cursor + load-more flags are exposed by a `PostDetailViewModel` (collected via `collectAsStateWithLifecycle`), NOT by composition-local `remember`/`var` reply state

#### Scenario: Scrolling near the end of the replies issues a cursor-bearing follow-up

- **GIVEN** the post-detail screen with a loaded first page of replies whose `Loaded.nextCursor = "c1"` AND a MockEngine/fake capturing requests
- **WHEN** the user scrolls near the end of the replies list
- **THEN** exactly one follow-up `GET /api/v1/posts/{post_id}/replies` is issued carrying `cursor=c1` (the threshold keys off the replies items, not the header/like-row)

#### Scenario: The second reply page appends below existing replies and advances the cursor

- **GIVEN** a fake returning a second page of replies with `nextCursor = "c2"` for `cursor = "c1"`
- **WHEN** replies load-more completes
- **THEN** the second page's replies are appended below the first page (page-1 replies retained, post header + like row still first) AND the replies cursor is `"c2"`

#### Scenario: Posting a new reply still prepends and does not disturb paging

- **GIVEN** the replies list with a first page plus an appended second page
- **WHEN** the user posts a new reply successfully
- **THEN** the new reply is prepended to the top of the replies list (above page 1) AND the reply count increments AND the appended later pages remain below, undisturbed

#### Scenario: A null reply cursor stops further load-more

- **GIVEN** the replies list whose latest page returned `nextCursor = null`
- **WHEN** the user scrolls to the end again
- **THEN** no further `GET /api/v1/posts/{post_id}/replies` request is issued AND no load-more footer spinner is shown (end-reached)

#### Scenario: A replies load-more failure keeps the loaded replies and offers retry

- **GIVEN** the replies list with a loaded first page AND a load-more fetch that fails (network/5xx)
- **THEN** the first-page replies remain rendered (the post header + like row unaffected) AND a non-destructive load-more error footer with a retry control is shown AND retry re-issues the `cursor`-bearing follow-up for the same cursor
