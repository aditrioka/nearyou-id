# mobile-post-detail Specification

## Purpose
The `:mobile:app` post-detail surface — the screen that closes the core engagement loop (like + reply) on a single post, consuming the shipped V7 `post-likes` + V8 `post-replies` endpoints with no backend change. `PostDetailScreen` is reached by tapping a Nearby/Global feed card — a payload-carrying `PostDetailRoute` pushed onto the root back stack (overlaying the tab bar, mirroring the composer-FAB pattern) — and renders the post header (`content` + posted-from, empty-city tolerated), an optimistic status-driven like toggle (count + Free-tier cap upsell, graceful count-degradation), a read-only replies list, and a reply composer with a live `N/280` Unicode-code-point counter, mapping every like/reply/list result to exactly one sealed outcome (no generic fallthrough; `401` delegated to the shared `Auth` plugin). PII discipline is enforced: `PostDetailRoute` carries only non-PII display fields and declares no `latitude`/`longitude`, reply cards render content + timestamp only (never `author_id`), and the screen never logs PII (`HttpClientFactory` stays at `LogLevel.HEADERS`). The reply DTOs mirror the SHIPPED snake_case wire (`post_id`/`author_id`/`is_auto_hidden`/`created_at`/`next_cursor`), which differs from the timelines' camelCase `nextCursor` — a negative-guard test asserts the camelCase form does not bind (the PR #128 casing-drift precedent). This is the dependency-upstream of the future in-app notifications list, which deep-links into post/reply detail.
## Requirements
### Requirement: PostDetailScreen renders the post-detail surface

The mobile app SHALL ship a composable `PostDetailScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostDetailScreen.kt`), mapped from the `PostDetailRoute` `NavKey` by the `appEntryProvider`, that renders the detail surface for a single post. The screen SHALL display: (a) the post header — the post `content` plus a "Diposting dari {city_name}, {relative_time}" line via `stringResource(Res.string.post_detail_posted_from)` (formatted with `cityName` + the same `created_at` treatment the feed cards use — the existing `postDateLabel` ISO-date-portion helper, i.e. `createdAt.substringBefore('T')`; true relative formatting stays deferred to the `mobile-timeline-relative-timestamp` follow-up), per `docs/03-UX-Design.md:14` / `docs/02-Product.md:140`, reusing the existing feed card visual where practical; (b) a like control (per the § "Like toggle is optimistic and status-driven" requirement); (c) a replies list (per the § "Replies list mirrors the shipped snake_case wire" requirement); (d) a reply composer (per the § "Reply composer posts with a 280-code-point guard" requirement); (e) the loading / empty / error / rate-limit states, all copy via `stringResource`. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark). When `cityName` is the backend's empty-string convention (`""`), the header SHALL render without the city fragment (no crash, no literal `""`).

#### Scenario: Initial render shows the post content and posted-from header

- **WHEN** a test composes `PostDetailScreen` under `NearYouTheme` with a `FakePostDetailFlow` and a route payload carrying `content = "halo"`, `cityName = "Jakarta Selatan"`
- **THEN** the rendered tree contains a node whose text is `"halo"` AND a node whose text matches `stringResource(Res.string.post_detail_posted_from)` formatted with `"Jakarta Selatan"`

#### Scenario: Empty city_name is tolerated in the header

- **WHEN** the route payload carries `cityName = ""`
- **THEN** the header renders without the city fragment (no crash, no literal `""`)

#### Scenario: No hardcoded UI strings in PostDetailScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostDetailScreen.kt`
- **THEN** every `Text(...)` / placeholder / `contentDescription = ...` call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: PostDetailRoute is a payload-carrying, serializable, polymorphic-registered route that excludes PII

The change SHALL introduce a `PostDetailRoute` `NavKey` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`) — the first payload-carrying route (existing routes are parameterless `data object`s). It SHALL be `@Serializable` AND registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (so the back stack is saveable on Kotlin/Native per `mobile-app-scaffold` § "Back stack uses serializable NavKey routes"). `PostDetailRoute` SHALL carry exactly the non-PII display fields needed to render the post header: `postId: String`, `content: String`, `cityName: String`, `distanceM: Double?` (Nearby-origin only; `null` from Global), `createdAtIso: String`, `likedByViewer: Boolean`, `replyCount: Int`, and — as of `mobile-timeline-card-redesign` — `authorUsername: String = ""` and `authorDisplayName: String = ""` (the author **display** identity; defaulted so a back stack serialized before this change still decodes — an empty value degrades gracefully per § "The post header renders from nav args without a single-post re-fetch") — plus, as of `mobile-inline-post-actions`, `focusReplyComposer: Boolean = false` (the feed reply-shortcut intent; defaulted so payloads serialized before this change still decode, the same compatibility precedent as the identity fields; behavior per § "Reply composer autofocuses on reply-shortcut entry"). `PostDetailRoute` MUST NOT declare a `latitude` or `longitude` property (raw coordinates MUST NOT enter the serialized back stack — the same PII discipline `AgeGateRoute` applies to the `id_token`) and MUST NOT declare the author UUID.

#### Scenario: PostDetailRoute carries display fields but no coordinates

- **WHEN** inspecting the `PostDetailRoute` declaration in `NavKeys.kt`
- **THEN** it declares `postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`, `focusReplyComposer` AND declares NO `latitude` / `longitude` (or any raw-coordinate) property AND no author-UUID property

#### Scenario: PostDetailRoute survives a serialized back-stack round-trip

- **GIVEN** a `PostDetailRoute` instance encoded + decoded via the `navSavedStateConfiguration` polymorphic serializer (the iOS-safe saved-state path)
- **THEN** the decoded route equals the original (no `SerializationException`), proving it is registered in the polymorphic module

#### Scenario: A payload predating the identity fields still decodes

- **GIVEN** a serialized `PostDetailRoute` payload that lacks the `authorUsername` / `authorDisplayName` properties (produced before `mobile-timeline-card-redesign`)
- **WHEN** it is decoded via the polymorphic serializer
- **THEN** decoding succeeds with `authorUsername = ""` and `authorDisplayName = ""` (the defaults), no `SerializationException`

#### Scenario: A payload predating focusReplyComposer still decodes

- **GIVEN** a serialized `PostDetailRoute` payload that lacks the `focusReplyComposer` property (produced before `mobile-inline-post-actions`)
- **WHEN** it is decoded via the polymorphic serializer
- **THEN** decoding succeeds with `focusReplyComposer = false` (the default), no `SerializationException`

### Requirement: PostDetailScreen is reached via the root back stack and is navigation-free

`PostDetailScreen` SHALL be reached by appending `PostDetailRoute` to the **root** navigation back stack (above `HomeRoute`, overlaying the tab bar) — mirroring the post-composer FAB's root-stack push and deliberately NOT using a per-tab `NavDisplay` back stack (deferred by `mobile-home-tab-host`). `PostDetailScreen` SHALL be navigation-free: it holds no back-stack reference; its back affordance invokes a hoisted `onBack` lambda (the Nav3 `backStack.removeLastOrNull()` equivalent, wired by the host) to return to the feed.

#### Scenario: Back affordance pops the detail off the root stack

- **GIVEN** `PostDetailScreen` composed over a test root back stack (or with a recording `onBack` callback) with `PostDetailRoute` as the current entry
- **WHEN** the back affordance is activated
- **THEN** the `PostDetailRoute` entry is removed from the root back stack (`removeLastOrNull`) / the recording `onBack` fires, and the feed surface becomes current again

#### Scenario: PostDetailScreen holds no back-stack reference

- **WHEN** inspecting `PostDetailScreen.kt`
- **THEN** the screen takes navigation only via hoisted lambdas (`onBack`); it holds no `NavBackStack` field and performs no direct back-stack mutation of its own

### Requirement: The post header renders from nav args without a single-post re-fetch

`PostDetailScreen` SHALL render the post header SOLELY from the `PostDetailRoute` payload and SHALL NOT issue any single-post by-id GET (a `GET /api/v1/posts/{post_id}` by-id endpoint now exists as of the `single-post-read` capability, but the card-tap path deliberately does NOT use it — the nav-arg payload is already in hand; that endpoint serves the notification-deep-link path, where there is no feed card to source the header, a separate future change). The only outbound requests the screen issues are to the like (`/like`, `/likes/count`) and reply (`/replies`) sub-resources. As of `mobile-timeline-card-redesign` the header SHALL render the author **display identity** from the payload — the letter avatar + `authorDisplayName` + the `authorUsername` handle, using the same avatar derivation and handle treatment as `mobile-post-card` (the header is not the shared card composable, but reuses its avatar/identity sub-components so the treatments cannot drift). When `authorUsername`/`authorDisplayName` are empty (a legacy restored payload), the identity row SHALL be omitted gracefully (no empty "@" handle, no crash). The identity is NOT a tap target (no profile screen exists — issue #196).

#### Scenario: No single-post GET is issued

- **GIVEN** a Ktor `MockEngine` capturing all outbound requests, wired into the composed `PostDetailScreen`
- **WHEN** the screen loads and renders its header
- **THEN** no captured request path matches `/api/v1/posts/{id}` as a single-post resource (the header came from the route payload); the only captured post-scoped requests target `/like`, `/likes/count`, or `/replies` sub-resources

#### Scenario: Header renders the author display identity from the payload

- **GIVEN** a `PostDetailRoute` with `authorUsername = "raka.jkt"`, `authorDisplayName = "Raka Pratama"`
- **WHEN** the detail surface renders
- **THEN** the header contains the "Raka Pratama" display-name node, the "@raka.jkt" handle node, and the letter avatar — with no additional network request for them

#### Scenario: Empty identity payload renders without an identity row

- **GIVEN** a `PostDetailRoute` with `authorUsername = ""` and `authorDisplayName = ""`
- **WHEN** the detail surface renders
- **THEN** the header renders the post content/meta normally AND contains no empty handle node (no literal "@") and no empty avatar

### Requirement: Like toggle is optimistic and status-driven, with count and cap upsell

The like control's initial state SHALL come from the `likedByViewer` route payload. Activating it SHALL flip the state **optimistically** and call `POST /api/v1/posts/{post_id}/like` (when liking) or `DELETE /api/v1/posts/{post_id}/like` (when unliking) via the shipped `HttpClient` (Bearer + 401 refresh owned by the `Auth` plugin; MUST NOT be reimplemented; NO `X-Session-Id` header — the like endpoints are not session-soft-capped). Both verbs return `204 No Content` on the happy path (`DELETE` is a pure no-op that NEVER returns 404). The repository SHALL map results to a sealed `LikeOutcome`: `204` → `Liked` / `Unliked`; `429` → `RateLimited(retryAfterSeconds)`; `404` → `PostGone`; `5xx`/network-IO → `NetworkError`. On any non-`204`/network failure the optimistic flip SHALL be reverted. A `RateLimited` like SHALL surface the Free like-cap upsell via `stringResource(Res.string.post_detail_likes_cap_upsell)` (`docs/03-UX-Design.md:205`). The numeric like count SHALL be fetched via `GET /api/v1/posts/{post_id}/likes/count` (`{ "count": <Long> }`) and displayed via `stringResource(Res.string.post_detail_like_count)` when available; a count-fetch failure SHALL degrade gracefully (hide the count, keep the toggle functional).

#### Scenario: Optimistic like issues POST and reflects the liked state

- **GIVEN** a `FakePostDetailFlow` whose `toggleLike(currentlyLiked = false)` returns `LikeOutcome.Liked` AND the route payload has `likedByViewer = false`
- **WHEN** the like control is activated
- **THEN** the control immediately reflects the liked state AND `toggleLike` was invoked

#### Scenario: A 429 reverts the optimistic flip and shows the cap upsell

- **GIVEN** the like control is in the not-liked state AND `toggleLike` returns `LikeOutcome.RateLimited(retryAfterSeconds = 3600)`
- **WHEN** the like control is activated
- **THEN** the optimistic flip is reverted to not-liked AND the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_likes_cap_upsell)`

#### Scenario: DELETE unlike maps to Unliked

- **GIVEN** a `MockEngine` returning `204` for `DELETE /api/v1/posts/{post_id}/like`
- **WHEN** the repository processes an unlike
- **THEN** the outcome is `LikeOutcome.Unliked` AND no crash occurs (DELETE never yields 404)

#### Scenario: Like count is fetched and shown, degrading on failure

- **GIVEN** a `MockEngine` returning `200 { "count": 42 }` for `GET /api/v1/posts/{post_id}/likes/count`
- **WHEN** the screen loads
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_like_count)` formatted with `42`; AND given a count fetch that fails, the screen renders no count node and the like toggle remains functional

### Requirement: Replies list mirrors the shipped snake_case wire with loading, empty, and error states

`ReplyApiClient` SHALL issue `GET /api/v1/posts/{post_id}/replies` and parse `@Serializable` DTOs whose wire names match the SHIPPED backend serialization in `backend/ktor/.../engagement/ReplyRoutes.kt` (`ReplyDto` / `ReplyListResponse`) — **snake_case**, NOT the timelines' camelCase. Specifically: `ReplyDto` = `id` (bare String), `@SerialName("post_id") postId`, `@SerialName("author_id") authorId`, `content` (bare), `@SerialName("is_auto_hidden") isAutoHidden` (Boolean), `@SerialName("created_at") createdAt`, `@SerialName("updated_at") updatedAt: String?`, `@SerialName("deleted_at") deletedAt: String?`; `ReplyListResponse` = `replies: List<ReplyDto>` (bare), `@SerialName("next_cursor") nextCursor: String? = null`. The `next_cursor` key is snake_case and MUST differ from the timelines' camelCase `nextCursor`. The screen SHALL render reply cards showing `content` + the `created_at` treatment only — NO author identity (the wire carries only `author_id`, never rendered) — within one of: a loading state (`stringResource(Res.string.timeline_loading)`), an empty state (`stringResource(Res.string.post_detail_replies_empty)`), the reply-card list, or an error state (`stringResource(Res.string.signin_error_network)` + a `stringResource(Res.string.cta_retry)` control). `next_cursor` SHALL be parsed + retained but cursor load-more is NOT wired in this change (see § "By-id post fetch and replies infinite-scroll are deferred"). A returned reply MAY carry `is_auto_hidden = true` ONLY when it is the viewer's OWN reply (the backend's author-bypass `is_auto_hidden = FALSE OR author_id = :viewer` lives in the `PostReplyRepository.listByPost` query — impl in `core/data/.../repository/PostReplyRepository.kt`, surfaced via `engagement/ReplyService.list` — so no other reply with the flag set is ever returned); in v1 the `is_auto_hidden` flag SHALL be **parsed but NOT surfaced** (the viewer's own auto-hidden reply renders identically to a live reply, matching the backend's author-bypass intent) — no "under review" badge or dimming is added in this change. Similarly `deleted_at` is faithfully parsed (DTO mirrors the wire) but is effectively dead on this list path (the backend excludes `deleted_at IS NOT NULL` rows).

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

#### Scenario: Viewer's own auto-hidden reply is parsed but rendered normally (v1)

- **GIVEN** a reply returned with `is_auto_hidden = true` (the only reachable case: it is the viewer's own reply, per the backend author-bypass)
- **WHEN** the reply card renders
- **THEN** parsing succeeds AND the card renders identically to a live reply (no "under review" badge, no dimming) — the flag is parsed but not surfaced in v1

### Requirement: Reply composer posts with a 280-code-point guard, local append, and cap upsell

The reply composer SHALL render a multiline field (placeholder `stringResource(Res.string.post_detail_reply_placeholder)`), a live `N/280` counter via `stringResource(Res.string.post_detail_reply_counter)` computed in **Unicode code points** (NOT UTF-16 units), and a "Balas" CTA via `stringResource(Res.string.cta_reply)` that is disabled while content is empty / over-limit / in-flight. **Empty-vs-over-limit is a CLIENT-side concern** — the pre-submit code-point projection disables the CTA so the client never submits empty or >280 content; there is no server round-trip to distinguish "empty" from "too long" (and the backend would not provide one — see below). Submitting SHALL call `POST /api/v1/posts/{post_id}/replies` with the body `{ "content": "<text>" }`. The request DTO declares `content: String` (non-null), **deliberately tightening** the shipped `ReplyCreateRequest(content: String? = null)` — the client always sends a non-null `content` (the wire's nullable+default is backend input-leniency, irrelevant to an outbound-only request DTO). The repository SHALL map results to a sealed `ReplyPostOutcome`: `201` → `Success(reply)` (the returned `ReplyDto` is appended to the in-memory list AND the displayed reply count is incremented — the list is NOT re-fetched); `429` → `RateLimited(retryAfterSeconds)` (surfaces `stringResource(Res.string.post_detail_reply_cap_upsell)`); `400 invalid_request` → a single `InvalidContent` outcome (the SHIPPED backend emits the **same** `invalid_request` code for both empty and >280 content — `backend/ktor/.../engagement/ReplyRoutes.kt` `respondInvalidRequest`, message-only difference — so the client MUST NOT assume a server empty-vs-too-long distinction; this is a defensive edge given the client guard, mapped to a retryable banner with a logged diagnostic, NOT a crash, NOT a silent no-op); `404` → `PostGone`; `5xx`/network-IO → `NetworkError`. Bearer + 401 refresh are owned by the shipped `Auth` plugin.

#### Scenario: 280 code points enabled, 281 over-limit and disabled

- **WHEN** the composer projection is invoked with a 280-code-point string and again with a 281-code-point string (not in-flight)
- **THEN** the 280 case enables the "Balas" CTA with over-limit false AND the 281 case disables it with over-limit true

#### Scenario: Multi-byte emoji counts as one code point

- **GIVEN** a string of 280 non-BMP emoji (one code point, two UTF-16 units each)
- **WHEN** the counter computes the count
- **THEN** the count is 280 (NOT 560) AND the CTA is enabled

#### Scenario: 201 appends the new reply locally and bumps the count without re-fetch

- **GIVEN** a `FakePostDetailFlow` whose `postReply(...)` returns `ReplyPostOutcome.Success` with a new `ReplyDto` AND a displayed reply count of `2`
- **WHEN** a reply is submitted successfully
- **THEN** the new reply appears in the list AND the displayed reply count becomes `3` AND no `GET /api/v1/posts/{post_id}/replies` re-fetch is issued

#### Scenario: 429 on reply shows the reply-cap upsell

- **WHEN** `postReply(...)` returns `ReplyPostOutcome.RateLimited(retryAfterSeconds = 3600)`
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_reply_cap_upsell)`

#### Scenario: 400 invalid_request maps to a single InvalidContent outcome (no server empty/too-long split)

- **GIVEN** a `MockEngine` returning `400 {"error":{"code":"invalid_request"}}` (the single code the backend emits for both empty and over-limit content)
- **WHEN** the repository processes the response
- **THEN** the outcome is the single `InvalidContent` (retryable, with a logged diagnostic) — NOT two distinct `ContentEmpty`/`ContentTooLong` server outcomes, and NOT a generic wildcard failure

#### Scenario: Empty/over-limit is gated client-side before any POST

- **GIVEN** the composer projection with empty content, and again with a 281-code-point string
- **THEN** in both cases the "Balas" CTA is disabled (the over-limit flag set for the 281 case) so no `POST /api/v1/posts/{post_id}/replies` is issued — the empty-vs-too-long UX is driven by the client projection, not a server response

### Requirement: Every fetch outcome maps to exactly one sealed member with no generic fallthrough

Each post-detail operation (like toggle, reply post, replies list, like count) SHALL map every HTTP status + transport-failure type to exactly one member of its sealed outcome type, keyed on the HTTP **status code** (and parsed `error.code` only where the backend distinguishes by code), with no generic "load failed" / "submit failed" wildcard branch. HTTP `401` SHALL be delegated to the shipped `Auth` `refreshTokens` (terminal 401 → `SessionInvalidator` → `SignInScreen`) and MUST NOT be mapped here. `CancellationException` MUST be rethrown, never mapped to `NetworkError`.

#### Scenario: Each operation enumerates its statuses with no wildcard

- **WHEN** inspecting the repository's result mapping for the like, reply-post, replies-list, and like-count operations
- **THEN** each maps its happy status (`204`/`201`/`200`), `429`, `404`, and `5xx`/IO to exactly one sealed member with NO `else`/wildcard emitting a generic copy; `401` is delegated to the `Auth` plugin (not mapped)

#### Scenario: CancellationException is rethrown

- **GIVEN** an in-flight operation whose coroutine is cancelled (the HTTP call throws `CancellationException`)
- **WHEN** the client's catch handling runs
- **THEN** the `CancellationException` is rethrown (structured concurrency unwinds) and is NOT mapped to `NetworkError`

### Requirement: Pure PostDetailUiState projection (Compose-free, unit-testable, PII-free)

The mobile app SHALL model the screen state as Compose-free `PostDetailUiState` data class(es) plus pure projection function(s) (mirroring `NearbyTimelineUiState` / `PostCreationUiState`) so the outcome→state mapping and the reply code-point gate are deterministically unit-testable in commonTest without composing the UI. The projection MUST carry no PII (no `author_id`, no coordinates) and no wall-clock / platform dependency.

#### Scenario: Projection maps each outcome to its state deterministically

- **WHEN** the projection is invoked for the like states (liked / not-liked / rate-limited), the replies states (loading / loaded-non-empty / empty / error), and the reply-post states (success / content-empty / content-too-long / rate-limited / network-error)
- **THEN** each call returns the corresponding state deterministically (no wall-clock or platform dependency) AND no projected state carries a coordinate or `author_id`

### Requirement: Repository and ApiClient wired as Koin singletons behind the PostDetailFlow seam

`PostDetailRepository` and its ApiClient(s) (`LikeApiClient`, `ReplyApiClient`, or a combined client) SHALL be registered as singletons in the commonMain Koin `mobileModule`. `PostDetailRepository` SHALL be bound behind a `PostDetailFlow` interface (`single<PostDetailFlow> { get<PostDetailRepository>() }`) so a `FakePostDetailFlow` can drive the screen tests (mirroring `mobile-nearby-timeline`'s `NearbyTimelineFlow` seam). As of `mobile-inline-post-actions`, the like half of the seam is extracted for cross-surface reuse: a `LikeFlow` interface (new target-shape file `mobile/app/src/commonMain/kotlin/id/nearyou/app/data/like/LikeFlow.kt`, docs/11 § 2.1) declares `suspend fun toggleLike(postId: String, currentlyLiked: Boolean): LikeOutcome`; `PostDetailFlow` EXTENDS `LikeFlow` (its `toggleLike` member moves up to the super-interface — same signature, same `LikeOutcome` semantics; `LikeOutcome` itself stays in its existing `id.nearyou.app.post` location, no mechanical file moves); and `mobileModule` ADDITIONALLY binds `single<LikeFlow> { get<PostDetailRepository>() }` — the SAME singleton serving the detail surface and the feeds' inline like. The repository SHALL reuse the existing shared `HttpClient` — it MUST NOT construct a new client and MUST NOT register or send an `X-Session-Id` header (the like/reply endpoints are not session-soft-capped). The post-detail screen's behavior is unchanged by the extraction.

#### Scenario: mobileModule registers the post-detail graph behind the flow interfaces

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for the post-detail ApiClient(s) and `PostDetailRepository` AND binds `single<PostDetailFlow> { get<PostDetailRepository>() }` AND binds `single<LikeFlow> { get<PostDetailRepository>() }` (the same singleton behind both seams) AND the repository resolves the existing shared `HttpClient` (no new client, no `X-Session-Id` registration)

#### Scenario: PostDetailFlow extends the extracted LikeFlow seam

- **WHEN** inspecting `data/like/LikeFlow.kt` and `PostDetailFlow.kt`
- **THEN** `LikeFlow` declares `suspend fun toggleLike(postId: String, currentlyLiked: Boolean): LikeOutcome` AND `PostDetailFlow` extends `LikeFlow` without re-declaring an incompatible `toggleLike` AND no second like repository/ApiClient registration exists in the module

### Requirement: No author identifier or coordinate is rendered or logged

`PostDetailScreen`, its post header, and its reply cards SHALL render only non-PII display fields (the author **display identity** from the route payload — `authorDisplayName` + the `authorUsername` handle, as of `mobile-timeline-card-redesign` — plus `content`, `cityName`, the `created_at` treatment, the like state/count, `reply_count`). The `author_id` (a UUID) and any raw `latitude`/`longitude` MUST NOT be rendered in any UI node. Tokens, raw coordinates, and response bodies MUST NOT be logged — `HttpClientFactory` SHALL remain at `LogLevel.HEADERS` (this change MUST NOT widen it to `BODY`/`ALL`), and the post-detail client/repository MUST NOT `println`/log coordinates or bodies.

#### Scenario: No author UUID or coordinate appears in the rendered tree while display identity does

- **GIVEN** a route payload + replies whose underlying data includes an `author_id` UUID, with payload `authorDisplayName = "Raka Pratama"`, `authorUsername = "raka.jkt"`
- **WHEN** the detail surface renders
- **THEN** the rendered tree contains NO node whose text is a UUID author identifier AND NO node whose text contains a raw coordinate AND contains the "Raka Pratama" and "@raka.jkt" nodes

#### Scenario: Logging level is unchanged

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/network/HttpClientFactory.kt` after this change
- **THEN** the `Logging` plugin level remains `LogLevel.HEADERS` (NOT `BODY`/`ALL`)

### Requirement: Block and report kebab actions are deferred

This change SHALL NOT add any block or report affordance (post or reply kebab menu) to `PostDetailScreen` or the reply cards. The block + report UI (`docs/02-Product.md:233`/`:254`) is a separate feature (the `user_blocks` + `reports` backends, a confirmation modal, a reason picker) and is deferred. GitHub issue [#200](https://github.com/aditrioka/nearyou-id/issues/200) `mobile-post-detail-block-report-kebab` (label `follow-up`) tracks it.

#### Scenario: No block/report affordance is present

- **WHEN** inspecting `PostDetailScreen.kt` and the reply-card composables
- **THEN** there is NO "Blokir" / "Laporkan" control, kebab menu, or block/report API call

#### Scenario: Follow-up issue tracks the block/report deferral

- **WHEN** inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** GitHub issue [#200](https://github.com/aditrioka/nearyou-id/issues/200) (label `follow-up`) tracks `mobile-post-detail-block-report-kebab`

### Requirement: By-id post fetch and replies infinite-scroll are deferred

This change SHALL NOT implement a `GET /api/v1/posts/{post_id}` by-id fetch (the header is built from nav args; a backend by-id endpoint was later added by the `single-post-read` capability but is consumed only by the future notifications deep-link change, not this screen) NOR cursor-based load-more for the replies list (`next_cursor` is parsed + retained but not consumed). Both are deferred. To avoid a redundant new tracking item, the replies load-more deferral SHALL **extend GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) `mobile-nearby-timeline-infinite-scroll` (label `follow-up`)** (which the Global feed already extends) rather than open a new one; GitHub issue [#202](https://github.com/aditrioka/nearyou-id/issues/202) `backend-single-post-get-endpoint` (label `follow-up`) tracked the by-id endpoint and shipped it as the `single-post-read` capability, and GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) is amended to note that replies load-more is also pending.

#### Scenario: next_cursor is parsed but no load-more request is issued

- **WHEN** inspecting the replies repository/screen for cursor usage
- **THEN** `next_cursor` is parsed + retained on the `Loaded` outcome but is NOT consumed to issue a follow-up `cursor=`-bearing `GET /replies` request in this change

#### Scenario: Follow-up issues track both deferrals without a redundant new entry

- **WHEN** inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** GitHub issue [#202](https://github.com/aditrioka/nearyou-id/issues/202) (label `follow-up`) tracks `backend-single-post-get-endpoint` AND GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) `mobile-nearby-timeline-infinite-scroll` is amended to cover the replies load-more (no separate `mobile-post-detail-replies-infinite-scroll` entry is opened)

### Requirement: Test coverage for the screen, projection, wire, and iOS flow

The change SHALL ship: (1) a Robolectric `PostDetailScreenTest` (`mobile/app/src/androidUnitTest/...`) covering the header render (no PII / no coordinates), the replies list states, the like toggle (optimistic + 429 upsell + count), and the reply composer (counter, 280-disable, 201 local-append, 429 upsell, error banners) via a `FakePostDetailFlow` — ADDED to the `mobile/app/build.gradle.kts` Release-variant `*ScreenTest` test-exclude list (the `ui-test-manifest` host activity is debug-only); (2) commonTest tests for the pure `PostDetailUiState` projection, the reply code-point counter, and the `PostDetailRoute` serialized round-trip; (3) MockEngine-backed ApiClient/Repository tests verifying the like POST/DELETE→204 mapping, the `GET /likes/count` parse, the reply POST `201`→`ReplyDto` parse against the shipped snake_case wire, the camelCase `nextCursor` negative-guard, the replies-list parse + retained-not-consumed cursor, and each status→outcome; (4) an iosTest flow test mirroring `NearbyTimelineFlowIosTest`.

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `PostDetailScreenTest`, the `PostDetailUiState` projection test, the `PostDetailRoute` round-trip test, and the ApiClient/Repository MockEngine tests are discovered AND each documented state / mapping corresponds to at least one `@Test`

#### Scenario: Screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists `**/PostDetailScreenTest*` alongside the existing `*ScreenTest` exclusions, and `:mobile:app:testDevReleaseUnitTest` passes

### Requirement: Reply composer autofocuses on reply-shortcut entry

When `PostDetailScreen` is entered with a `PostDetailRoute` carrying `focusReplyComposer = true` (the feed cards' reply shortcut), the reply composer field SHALL receive focus — with the IME requested — exactly ONCE on the detail entry's first composition. Whole-card opens (`focusReplyComposer = false`, the default) SHALL keep today's behavior: no autofocus, no IME. The consumed autofocus SHALL NOT re-trigger on recomposition or when the user manually clears focus and the surface recomposes, AND the consumed marker SHALL survive saved-state restoration — a configuration-change or process-death restore of an entry whose autofocus was already consumed does NOT re-fire the focus/IME (the consume-once flag is saveable state; the exact mechanism is an implementation detail). The autofocus MUST NOT change any other detail behavior (header render, replies load, like control).

#### Scenario: Reply-shortcut entry focuses the composer

- **GIVEN** `PostDetailScreen` composed with a route payload carrying `focusReplyComposer = true` and a `FakePostDetailFlow`
- **WHEN** the surface completes its first composition
- **THEN** the reply composer field reports focused semantics (exactly one focused node — the composer)

#### Scenario: Whole-card entry does not focus the composer

- **GIVEN** `PostDetailScreen` composed with a route payload carrying `focusReplyComposer = false`
- **WHEN** the surface completes its first composition
- **THEN** the reply composer field is NOT focused (today's behavior, unchanged)

#### Scenario: The autofocus is consumed once, not re-triggered

- **GIVEN** a reply-shortcut entry whose composer received the initial focus
- **WHEN** focus is cleared (e.g. the user taps elsewhere / dismisses the IME) and the surface recomposes
- **THEN** the composer is not re-focused by the entry flag (the autofocus was consumed on first composition)

#### Scenario: A restored entry does not re-fire a consumed autofocus

- **GIVEN** a reply-shortcut entry whose autofocus was consumed
- **WHEN** the entry's state is saved and restored (configuration-change / process-death path, e.g. via a state-restoration test harness)
- **THEN** the composer is NOT re-focused on the restored composition (the consumed marker survived restoration)

