# mobile-post-detail Specification

## Purpose
The `:mobile:app` post-detail surface — the screen that closes the core engagement loop (like + reply) on a single post, consuming the shipped V7 `post-likes` + V8 `post-replies` endpoints with no backend change. `PostDetailScreen` is reached by tapping a feed card, a post-target notification, or a search result — a payload-carrying `PostDetailRoute` pushed onto the root back stack (overlaying the tab bar, mirroring the composer-FAB pattern) — and renders, per mockup frame 7, a top app bar, the post header (`content` + posted-from, empty-city tolerated, distance-free), an action row with an optimistic status-driven like toggle (bare count + Free-tier cap upsell, graceful count-degradation) and share-to-chat, a replies list (load-more, report / block, and delete of the viewer's own reply with an optimistic revert), and a pill reply composer with a live `N/280` Unicode-code-point counter, mapping every like / reply / list / delete result to exactly one sealed outcome (no generic fallthrough; `401` delegated to the shared `Auth` plugin). An entry-scoped `PostDetailViewModel` owns all of the surface's network work and exposes one `uiState`; its writes run under `NonCancellable` so an issued like / reply / delete completes even if the entry is popped. PII discipline is enforced: `PostDetailRoute` carries only non-PII display fields and declares no `latitude`/`longitude`, reply rows render content, timestamp, and (when the wire carries it) the author's display identity, never `author_id`, and the screen never logs PII (`HttpClientFactory` stays at `LogLevel.HEADERS`). The reply DTOs mirror the SHIPPED snake_case wire (`post_id`/`author_id`/`is_auto_hidden`/`created_at`/`next_cursor`), which differs from the timelines' camelCase `nextCursor` — a negative-guard test asserts the camelCase form does not bind (the PR #128 casing-drift precedent). The in-app notifications list deep-links into this screen for post targets (reply targets stay non-navigating).
## Requirements
### Requirement: PostDetailScreen renders the post-detail surface

The mobile app SHALL ship a composable `PostDetailScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostDetailScreen.kt`), mapped from the `PostDetailRoute` `NavKey` by the `appEntryProvider`, that renders the detail surface for a single post. As of `post-detail-vm-reply-delete-restyle`, the screen's sub-composables live in three sibling files in the same package (docs/11 §4 UI soft cap):

- `PostDetailHeader.kt` — top bar, post header, action row;
- `PostDetailReplies.kt` — subhead, reply rows, list states, delete dialog;
- `PostDetailComposer.kt` — the reply composer.

The screen SHALL render, per mockup frame 7 · "Detail postingan + balasan":

- **(0) A top app bar.** An M3 `TopAppBar` whose navigation icon is a back-arrow `IconButton` (`Res.drawable.ic_arrow_back`, `contentDescription = stringResource(Res.string.cta_back)`), whose title is `stringResource(Res.string.post_detail_title)` ("Postingan"), and whose actions are the Edit affordance (when eligible) and the post overflow kebab.
- **(a) The post header.** The post `content` plus a "Diposting dari {city_name}, {relative_time}" line via `stringResource(Res.string.post_detail_posted_from)`. The line is formatted with `cityName` + the same `created_at` treatment the feed cards use: the shared `localDateLabel` helper, i.e. the `createdAt` instant's calendar date in the device time zone, not its UTC date. True relative formatting stays deferred to the `mobile-timeline-relative-timestamp` follow-up. This follows `docs/03-UX-Design.md:14` / `docs/02-Product.md:129`, reusing the existing feed-card visual where practical. The line is led by a `locationPin`-tinted pin icon (frame 7's coral meta-line treatment).
- **(b) The attached image** below the content when the route payload carries a non-null `imageUrl`. It is rendered via the async image loader (Coil 3) with an aspect-ratio placeholder and graceful failure (no error chrome), per the docs/02 § 6 delivery rules. It carries a meaningful `contentDescription` via `stringResource` (accessibility alt text, not a literal). When `imageUrl` is null no image element is rendered.
- **(c) A like control** in the post's action row (per the § "Like toggle is optimistic and status-driven" requirement).
- **(d) A replies list** (per the § "Replies list mirrors the shipped snake_case wire" requirement).
- **(e) A reply composer** (per the § "Reply composer posts with a 280-code-point guard" requirement).
- **(f) The loading / empty / error / rate-limit states**, all copy via `stringResource`.

No hardcoded UI string literals SHALL appear in any of the four post-detail UI source files. The screen SHALL render under `NearYouTheme` (light/dark), and as a root-stack overlay SHALL own exactly one `Scaffold`. When `cityName` is the backend's empty-string convention (`""`), the header SHALL render without the city fragment (no crash, no literal `""`).

#### Scenario: Initial render shows the post content and posted-from header

- **WHEN** a test composes `PostDetailScreen` under `NearYouTheme` with a `FakePostDetailFlow` and a route payload carrying `content = "halo"`, `cityName = "Jakarta Selatan"`
- **THEN** the rendered tree contains a node whose text is `"halo"` AND a node whose text matches `stringResource(Res.string.post_detail_posted_from)` formatted with `"Jakarta Selatan"`

#### Scenario: The frame-7 top bar renders a titled back arrow

- **WHEN** the detail surface renders
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_title)` AND the back affordance (test tag `postDetailBack`) carries the `contentDescription` `stringResource(Res.string.cta_back)` AND no node's text is "Tutup"

#### Scenario: Empty city_name is tolerated in the header

- **WHEN** the route payload carries `cityName = ""`
- **THEN** the header renders without the city fragment (no crash, no literal `""`)

#### Scenario: Attached image renders when imageUrl is present, and nothing when absent

- **WHEN** a test composes `PostDetailScreen` once with a route payload carrying a non-null `imageUrl` and once with `imageUrl = null`
- **THEN** the first render contains an async image node below the content AND the second render contains no image element

#### Scenario: No hardcoded UI strings in the post-detail UI sources

- **WHEN** inspecting `PostDetailScreen.kt`, `PostDetailHeader.kt`, `PostDetailReplies.kt` and `PostDetailComposer.kt` under `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/`
- **THEN** every `Text(...)` / placeholder / `contentDescription = ...` call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: PostDetailRoute is a payload-carrying, serializable, polymorphic-registered route that excludes PII

The change SHALL introduce a `PostDetailRoute` `NavKey` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`) — the first payload-carrying route (existing routes are parameterless `data object`s). It SHALL be `@Serializable` AND registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (so the back stack is saveable on Kotlin/Native per `mobile-app-scaffold` § "Back stack uses serializable NavKey routes"). `PostDetailRoute` SHALL carry exactly the non-PII display fields needed to render the post header: `postId: String`, `content: String`, `cityName: String`, `distanceM: Double?` (Nearby-origin only; `null` from Global), `createdAtIso: String`, `likedByViewer: Boolean`, `replyCount: Int`, and — as of `mobile-timeline-card-redesign` — `authorUsername: String = ""` and `authorDisplayName: String = ""` (the author **display** identity; defaulted so a back stack serialized before this change still decodes — an empty value degrades gracefully per § "The post header renders from nav args without a single-post re-fetch") — plus, as of `mobile-inline-post-actions`, `focusReplyComposer: Boolean = false` (the feed reply-shortcut intent; defaulted so payloads serialized before this change still decode, the same compatibility precedent as the identity fields; behavior per § "Reply composer autofocuses on reply-shortcut entry") — plus, as of `image-attached-posts`, `imageUrl: String? = null` (the public, coordinate-independent image delivery URL; defaulted so payloads serialized before this change still decode; not PII). `PostDetailRoute` MUST NOT declare a `latitude` or `longitude` property (raw coordinates MUST NOT enter the serialized back stack — the same PII discipline `AgeGateRoute` applies to the `id_token`) and MUST NOT declare the author UUID.

#### Scenario: PostDetailRoute carries display fields but no coordinates

- **WHEN** inspecting the `PostDetailRoute` declaration in `NavKeys.kt`
- **THEN** it declares `postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`, `focusReplyComposer`, `imageUrl` AND declares NO `latitude` / `longitude` (or any raw-coordinate) property AND no author-UUID property

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

#### Scenario: A payload predating imageUrl still decodes

- **GIVEN** a serialized `PostDetailRoute` payload that lacks the `imageUrl` property (produced before `image-attached-posts`)
- **WHEN** it is decoded via the polymorphic serializer
- **THEN** decoding succeeds with `imageUrl = null` (the default), no `SerializationException`

### Requirement: PostDetailScreen is reached via the root back stack and is navigation-free

`PostDetailScreen` SHALL be reached by appending `PostDetailRoute` to the **root** navigation back stack (above `HomeRoute`, overlaying the tab bar). This mirrors the post-composer FAB's root-stack push and deliberately does NOT use a per-tab `NavDisplay` back stack (deferred by `mobile-home-tab-host`).

`PostDetailScreen` SHALL be navigation-free: it holds no back-stack reference. Its back affordance — as of `post-detail-vm-reply-delete-restyle`, the top app bar's back arrow — invokes a hoisted `onBack` lambda (the Nav3 `backStack.removeLastOrNull()` equivalent, wired by the host) to return to the feed. The same holds for the split-out `PostDetailHeader.kt`, `PostDetailReplies.kt` and `PostDetailComposer.kt`.

#### Scenario: Back affordance pops the detail off the root stack

- **GIVEN** `PostDetailScreen` composed over a test root back stack (or with a recording `onBack` callback) with `PostDetailRoute` as the current entry
- **WHEN** the back affordance is activated
- **THEN** the `PostDetailRoute` entry is removed from the root back stack (`removeLastOrNull`) / the recording `onBack` fires, and the feed surface becomes current again

#### Scenario: PostDetailScreen holds no back-stack reference

- **WHEN** inspecting `PostDetailScreen.kt`, `PostDetailHeader.kt`, `PostDetailReplies.kt` and `PostDetailComposer.kt`
- **THEN** they take navigation only via hoisted lambdas (`onBack`); none holds a `NavBackStack` field or performs a direct back-stack mutation of its own

### Requirement: The post header renders from nav args without a single-post re-fetch

`PostDetailScreen` SHALL render the post header SOLELY from the `PostDetailRoute` payload. It SHALL NOT issue any single-post by-id GET **to source the initial header render**. A `GET /api/v1/posts/{post_id}` by-id endpoint exists as of the `single-post-read` capability, but the card-tap path deliberately does NOT block the first paint on it, because the nav-arg payload is already in hand.

The screen's outbound requests are:

- the like (`/like`, `/likes/count`) and reply (`/replies`) sub-resources, including the own-reply `DELETE /replies/{reply_id}`;
- the resume-time single-post **freshness read** (`mobile-post-editing`). It refreshes the displayed content and resolves `editedAt` / `isAuthor` / `authorUserId`. It is issued by `PostDetailViewModel`, and a failure degrades silently to the payload.

As of `mobile-timeline-card-redesign` the header SHALL render the author **display identity** from the payload:

- The letter avatar, then `authorDisplayName`, then the `authorUsername` handle.
- The header uses the same avatar derivation and handle treatment as `mobile-post-card`. It is not the shared card composable, but it reuses the card's avatar / identity sub-components so the treatments cannot drift.
- Mockup frame 7 also shows a distance in this meta line. Post-detail SHALL NOT render it: `hide-distance` § "Scope is Nearby only — every other surface stays distance-free" governs (spec over mockup), even though the payload may carry the Nearby-origin `distanceM`.
- When `authorUsername` / `authorDisplayName` are empty (a legacy restored payload), the identity row SHALL be omitted gracefully: no empty "@" handle, no crash.

As of `post-detail-tap-to-profile`, the identity row IS a tap target once the single-post freshness read has resolved an `authorUserId` (per § "Post header identity row opens the author profile"). It renders non-tappable while/if that read has not resolved one.

#### Scenario: No single-post GET is issued

- **GIVEN** a Ktor `MockEngine` capturing all outbound requests, wired into the composed `PostDetailScreen`
- **WHEN** the screen loads and renders its header
- **THEN** the header paints from the route payload without waiting on any single-post GET; the captured post-scoped requests are limited to `/like`, `/likes/count`, `/replies`, and the resume-time freshness read `GET /api/v1/posts/{id}` (`mobile-post-editing`)

#### Scenario: Header renders the author display identity from the payload

- **GIVEN** a `PostDetailRoute` with `authorUsername = "raka.jkt"`, `authorDisplayName = "Raka Pratama"`
- **WHEN** the detail surface renders
- **THEN** the header contains the "Raka Pratama" display-name node, the "@raka.jkt" handle node, and the letter avatar — with no additional network request for them

#### Scenario: Post-detail renders no distance even with a Nearby payload

- **GIVEN** a `PostDetailRoute` with `distanceM = 5000.0` (a Nearby-origin card tap)
- **WHEN** the detail surface renders
- **THEN** no node's text equals `DistanceRenderer.render(5000.0)` (post-detail stays distance-free)

#### Scenario: Empty identity payload renders without an identity row

- **GIVEN** a `PostDetailRoute` with `authorUsername = ""` and `authorDisplayName = ""`
- **WHEN** the detail surface renders
- **THEN** the header renders the post content/meta normally AND contains no empty handle node (no literal "@") and no empty avatar

### Requirement: Like toggle is optimistic and status-driven, with count and cap upsell

The like control's initial state SHALL come from the `likedByViewer` route payload. Activating it SHALL flip the state **optimistically** and call `POST /api/v1/posts/{post_id}/like` (when liking) or `DELETE /api/v1/posts/{post_id}/like` (when unliking) via the shipped `HttpClient`:

- Bearer + 401 refresh are owned by the `Auth` plugin and MUST NOT be reimplemented.
- There is NO `X-Session-Id` header; the like endpoints are not session-soft-capped.
- Both verbs return `204 No Content` on the happy path. `DELETE` is a pure no-op that NEVER returns 404.

The toggle, its in-flight guard, the optimistic flip and the revert SHALL be owned by `PostDetailViewModel` (per § "PostDetailViewModel owns all post-detail work with cancel-safe writes").

The repository SHALL map results to a sealed `LikeOutcome`:
- `204` → `Liked` / `Unliked`;
- `429` → `RateLimited(retryAfterSeconds)`;
- `404` → `PostGone`;
- `5xx`/network-IO → `NetworkError`.

On any non-`204`/network failure the optimistic flip SHALL be reverted, restoring the exact pre-tap like count.

As of `post-detail-vm-reply-delete-restyle` (mockup frame 7), the like control SHALL sit in the post's **action row**, rendered between two dividers below the header. The row contains, in order:

1. The read-only reply indicator: the reply icon + the live reply count, test tag `postDetailReplyCount`.
2. A share affordance (`Res.drawable.ic_send`, `contentDescription = stringResource(Res.string.chat_share_to_chat_action)`) that invokes the same hoisted `onShareToChat(postId)` as the kebab's "Bagikan ke chat" item.
3. The like affordance. It shows a `locationPin`-tinted filled heart when liked and a muted outlined heart otherwise. Next to the heart, the like count renders as the **bare number** when available.
   - The count node's `contentDescription` SHALL be `stringResource(Res.string.post_detail_like_count)` formatted with the count, so assistive tech announces "N suka".
   - The like target SHALL carry the action label `stringResource(Res.string.post_card_action_like)` as its `contentDescription` and announce its liked state via `stateDescription` (the `mobile-post-card` idiom).

Every action-row target SHALL be ≥48dp.

A `RateLimited` like SHALL surface the Free like-cap upsell as the shared `DailyCapUpsellDialog` (`mobile-cap-upsell-dialog`, mockup frame 18) — NOT an inline banner. The body is `stringResource(Res.string.post_detail_likes_cap_upsell)` (`docs/03-UX-Design.md` § Rate Limit Communication), formatted with the dialog's live per-minute countdown derived from `retryAfterSeconds`. The dialog's CTAs:
- "Tutup" / scrim / back / the countdown auto-dismiss SHALL clear the rate-limited state.
- "Aktifkan Premium" SHALL clear it AND invoke the screen's hoisted `onActivatePremium(PaywallEntry.LIKE_CAP)`, which `appEntryProvider` wires to push `PaywallRoute(LIKE_CAP)` onto the root back stack. The screen stays navigation-free.

The numeric like count SHALL be fetched via `GET /api/v1/posts/{post_id}/likes/count` (`{ "count": <Long> }`) by the ViewModel and displayed when available. A count-fetch failure SHALL degrade gracefully: hide the count, keep the toggle functional.

#### Scenario: Optimistic like issues POST and reflects the liked state

- **GIVEN** a `FakePostDetailFlow` whose `toggleLike(currentlyLiked = false)` returns `LikeOutcome.Liked` AND the route payload has `likedByViewer = false`
- **WHEN** the like control is activated
- **THEN** the control immediately reflects the liked state AND `toggleLike` was invoked

#### Scenario: A 429 reverts the optimistic flip and shows the cap dialog

- **GIVEN** the like control is in the not-liked state AND `toggleLike` returns `LikeOutcome.RateLimited(retryAfterSeconds = 3600)`
- **WHEN** the like control is activated
- **THEN** the optimistic flip is reverted to not-liked AND the `DailyCapUpsellDialog` is shown whose body matches `stringResource(Res.string.post_detail_likes_cap_upsell)` formatted with the countdown ("1 j 0 mnt") AND no inline cap banner is rendered

#### Scenario: The like cap CTA opens the paywall as LIKE_CAP

- **GIVEN** the like cap dialog shown on post-detail under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped
- **THEN** the dialog is dismissed AND the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.LIKE_CAP)`

#### Scenario: DELETE unlike maps to Unliked

- **GIVEN** a `MockEngine` returning `204` for `DELETE /api/v1/posts/{post_id}/like`
- **WHEN** the repository processes an unlike
- **THEN** the outcome is `LikeOutcome.Unliked` AND no crash occurs (DELETE never yields 404)

#### Scenario: Like count is fetched and shown as a bare number, degrading on failure

- **GIVEN** a `MockEngine` returning `200 { "count": 42 }` for `GET /api/v1/posts/{post_id}/likes/count`
- **WHEN** the screen loads
- **THEN** the action row contains a node whose text is `"42"` and whose `contentDescription` matches `stringResource(Res.string.post_detail_like_count)` formatted with `42`; AND given a count fetch that fails, the screen renders no count node and the like toggle remains functional

#### Scenario: The inline share affordance opens share-to-chat

- **WHEN** the action row's share affordance is activated
- **THEN** the hoisted `onShareToChat` fires with the route's `postId` (the same action as the kebab's "Bagikan ke chat")

### Requirement: Replies list mirrors the shipped snake_case wire with loading, empty, and error states

`ReplyApiClient` SHALL issue `GET /api/v1/posts/{post_id}/replies` and parse `@Serializable` DTOs whose wire names match the SHIPPED backend serialization in `backend/ktor/.../engagement/ReplyRoutes.kt` (`ReplyDto` / `ReplyListResponse`). The wire is **snake_case**, NOT the timelines' camelCase.

`ReplyDto` fields:
- `id` (bare String);
- `@SerialName("post_id") postId`;
- `@SerialName("author_id") authorId`;
- `@SerialName("author_username") authorUsername: String? = null` + `@SerialName("author_display_name") authorDisplayName: String? = null` — the author **display identity**, added by `mobile-block-from-content` design D7; nullable-with-default so a body from an older backend still decodes;
- `content` (bare);
- `@SerialName("is_auto_hidden") isAutoHidden` (Boolean);
- `@SerialName("created_at") createdAt`;
- `@SerialName("updated_at") updatedAt: String?`;
- `@SerialName("deleted_at") deletedAt: String?`.

`ReplyListResponse` fields:
- `replies: List<ReplyDto>` (bare);
- `@SerialName("next_cursor") nextCursor: String? = null`.

The `next_cursor` key is snake_case and MUST differ from the timelines' camelCase `nextCursor`.

As of `post-detail-vm-reply-delete-restyle` (mockup frame 7 · "Detail postingan + balasan"), the replies SHALL be introduced by a subhead `stringResource(Res.string.post_detail_replies_header)` formatted with the displayed reply count ("N balasan"). Each reply SHALL render as a **full-bleed list item**, not a card:

- A leading shared `LetterAvatar` (rendered only with a wire identity).
- A text column holding:
  - a first line with the `authorDisplayName` (bold; the `@handle` fallback when the display name is blank), then the separator and the `created_at` treatment;
  - the reply `content` below it.
- The trailing overflow kebab.

When the identity fields are null/blank (an older-backend body), the avatar and name SHALL be omitted gracefully (no empty row, no crash — the post header's legacy-payload precedent), and the date + content still render. The reply `author_id` (a UUID) stays NEVER rendered.

States:
- a loading state (`stringResource(Res.string.timeline_loading)`);
- an empty state (`stringResource(Res.string.post_detail_replies_empty)`);
- the reply list;
- an error state (`stringResource(Res.string.signin_error_network)` + a `stringResource(Res.string.cta_retry)` control).

`next_cursor` SHALL be parsed + retained AND SHALL drive replies cursor load-more per the § "Replies list wires cursor load-more via PostDetailViewModel" requirement.

A returned reply MAY carry `is_auto_hidden = true` ONLY when it is the viewer's OWN reply. The backend's author-bypass `is_auto_hidden = FALSE OR author_id = :viewer` lives in the `PostReplyRepository.listByPost` query (impl in `core/data/.../repository/PostReplyRepository.kt`, surfaced via `engagement/ReplyService.list`), so no other reply with the flag set is ever returned. In v1 the `is_auto_hidden` flag SHALL be **parsed but NOT surfaced**: the viewer's own auto-hidden reply renders identically to a live reply, matching the backend's author-bypass intent. No "under review" badge or dimming is added in this change.

Similarly `deleted_at` is faithfully parsed (the DTO mirrors the wire) but is effectively dead on this list path, because the backend excludes `deleted_at IS NOT NULL` rows.

#### Scenario: Replies parse against the shipped snake_case wire

- **GIVEN** a `MockEngine` returning `200` with `{ "replies": [ { "id": "...", "post_id": "...", "author_id": "...", "author_username": "sinta.mhr", "author_display_name": "Sinta Maharani", "content": "hi", "is_auto_hidden": false, "created_at": "2026-06-06T00:00:00Z", "updated_at": null, "deleted_at": null } ], "next_cursor": "tok" }`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the reply exposes `content = "hi"`, `authorUsername = "sinta.mhr"`, `authorDisplayName = "Sinta Maharani"` AND `nextCursor = "tok"`

#### Scenario: A body without the identity fields still decodes — older-backend guard

- **GIVEN** a `MockEngine` returning a reply object WITHOUT `author_username` / `author_display_name` keys (an older backend)
- **WHEN** the response is parsed
- **THEN** parsing succeeds with null identity fields AND the reply row renders content + timestamp with NO avatar or name (graceful omission, no crash)

#### Scenario: camelCase next_cursor does NOT populate — negative guard against the timeline assumption

- **GIVEN** a `MockEngine` returning `{ "replies": [], "nextCursor": "tok" }` (the timelines' camelCase key, NOT the shipped reply wire)
- **THEN** `ReplyListResponse.nextCursor` is `null` (the camelCase key does not bind under the `@SerialName("next_cursor")` mapping) — a fixture MUST assert this so the casing regression cannot slip in

#### Scenario: Empty replies show the empty-state copy

- **WHEN** the replies outcome is `Loaded` with an empty list
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_replies_empty)`

#### Scenario: The replies subhead shows the live count

- **GIVEN** a route payload with `replyCount = 4`
- **WHEN** the detail surface renders
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_replies_header)` formatted with `4`

#### Scenario: Reply row renders the display identity but never the author UUID

- **GIVEN** a reply with `author_id = "11111111-1111-1111-1111-111111111111"`, `author_username = "sinta.mhr"`, `author_display_name = "Sinta Maharani"`
- **WHEN** the reply row renders
- **THEN** the rendered tree contains a node whose text is `"Sinta Maharani"` (the identity line, mockup frame 7) AND NO node whose text contains `"11111111-1111-1111-1111-111111111111"`

#### Scenario: Viewer's own auto-hidden reply is parsed but rendered normally (v1)

- **GIVEN** a reply returned with `is_auto_hidden = true` (the only reachable case: it is the viewer's own reply, per the backend author-bypass)
- **WHEN** the reply row renders
- **THEN** parsing succeeds AND the row renders identically to a live reply (no "under review" badge, no dimming) — the flag is parsed but not surfaced in v1

### Requirement: Reply composer posts with a 280-code-point guard, local append, and cap upsell

As of `post-detail-vm-reply-delete-restyle` (mockup frame 7's bottom bar), the reply composer SHALL render a single row holding:

- **A pill-shaped multiline `TextField`.**
  - Container `surfaceContainerHigh`, no indicator line, bounded growth.
  - Placeholder `stringResource(Res.string.post_detail_reply_placeholder)`.
  - Test tag `postDetailReplyField`.
- **A filled circular send `IconButton`** (`Res.drawable.ic_send_filled`). This is the "Balas" CTA, labelled via `contentDescription = stringResource(Res.string.cta_reply)`. It SHALL be disabled while content is empty / over-limit / in-flight.

While the draft is non-empty, a live `N/280` counter (`stringResource(Res.string.post_detail_reply_counter)`) SHALL render above the row. It is computed in **Unicode code points** (NOT UTF-16 units) and uses the `error` color when over the limit.

The composer SHALL keep itself above the navigation bar and the IME, directly on top of the keyboard. On Android this requires the activity to declare `android:windowSoftInputMode="adjustResize"`, so the IME insets reach Compose's `imePadding` instead of the system panning the whole window (edge-to-edge guidance; without it the window panned AND the padding applied, leaving a keyboard-tall gap and pushing the top bar off-screen).

**Empty-vs-over-limit is a CLIENT-side concern.** The pre-submit code-point projection disables the CTA, so the client never submits empty or >280 content. There is no server round-trip to distinguish "empty" from "too long", and the backend would not provide one (see below).

Submitting SHALL call `POST /api/v1/posts/{post_id}/replies` with the body `{ "content": "<text>" }`, issued by `PostDetailViewModel` (cancel-safe per § "PostDetailViewModel owns all post-detail work with cancel-safe writes"). The request DTO declares `content: String` (non-null), **deliberately tightening** the shipped `ReplyCreateRequest(content: String? = null)`. The client always sends a non-null `content`; the wire's nullable+default is backend input-leniency, irrelevant to an outbound-only request DTO.

The repository SHALL map results to a sealed `ReplyPostOutcome`:
- `201` → `Success(reply)`. The returned `ReplyDto` is prepended to the in-memory list AND the displayed reply count is incremented; the list is NOT re-fetched. The composer draft is cleared.
- `429` → `RateLimited(retryAfterSeconds)`.
- `400 invalid_request` → a single `InvalidContent` outcome. The SHIPPED backend emits the **same** `invalid_request` code for both empty and >280 content (`backend/ktor/.../engagement/ReplyRoutes.kt` `respondInvalidRequest`, message-only difference), so the client MUST NOT assume a server empty-vs-too-long distinction. This is a defensive edge given the client guard: it maps to a retryable banner with a logged diagnostic, NOT a crash, NOT a silent no-op.
- `404` → `PostGone`.
- `5xx`/network-IO → `NetworkError`.

Bearer + 401 refresh are owned by the shipped `Auth` plugin.

A `RateLimited` reply SHALL surface the Free reply-cap upsell as the shared `DailyCapUpsellDialog` (`mobile-cap-upsell-dialog`, frame 18) — NOT an inline banner. The body is `stringResource(Res.string.post_detail_reply_cap_upsell)`, formatted with the live countdown. The composer text SHALL be preserved, so the user can send it after the reset or after upgrading. The dialog's CTAs:
- "Tutup" / scrim / back / auto-dismiss SHALL clear the rate-limited state.
- "Aktifkan Premium" SHALL clear it AND invoke `onActivatePremium(PaywallEntry.REPLY_CAP)`, which `appEntryProvider` wires to push `PaywallRoute(REPLY_CAP)`.

Replies can only be written on post-detail, so this is the Free reply cap's sole paywall path.

#### Scenario: 280 code points enabled, 281 over-limit and disabled

- **WHEN** the composer projection is invoked with a 280-code-point string and again with a 281-code-point string (not in-flight)
- **THEN** the 280 case enables the "Balas" CTA with over-limit false AND the 281 case disables it with over-limit true

#### Scenario: Multi-byte emoji counts as one code point

- **GIVEN** a string of 280 non-BMP emoji (one code point, two UTF-16 units each)
- **WHEN** the counter computes the count
- **THEN** the count is 280 (NOT 560) AND the CTA is enabled

#### Scenario: The counter shows only while the draft is non-empty

- **WHEN** the composer renders empty, and again after "halo" is typed
- **THEN** the empty composer shows no `N/280` counter node AND the typed composer shows the node whose text matches `stringResource(Res.string.post_detail_reply_counter)` formatted with `4`

#### Scenario: The Android activity resizes for the IME

- **WHEN** inspecting `mobile/app/src/androidMain/AndroidManifest.xml`
- **THEN** the `MainActivity` entry declares `android:windowSoftInputMode="adjustResize"`

#### Scenario: 201 appends the new reply locally and bumps the count without re-fetch

- **GIVEN** a `FakePostDetailFlow` whose `postReply(...)` returns `ReplyPostOutcome.Success` with a new `ReplyDto` AND a displayed reply count of `2`
- **WHEN** a reply is submitted successfully via the send action (described "Balas")
- **THEN** the new reply appears in the list AND the displayed reply count becomes `3` AND no `GET /api/v1/posts/{post_id}/replies` re-fetch is issued AND the composer field is empty

#### Scenario: 429 on reply shows the reply-cap dialog and keeps the draft

- **WHEN** a reply "halo" is submitted and `postReply(...)` returns `ReplyPostOutcome.RateLimited(retryAfterSeconds = 3600)`
- **THEN** the `DailyCapUpsellDialog` is shown whose body matches `stringResource(Res.string.post_detail_reply_cap_upsell)` formatted with the countdown AND no inline cap banner is rendered AND the composer field still contains "halo"

#### Scenario: The reply cap CTA opens the paywall as REPLY_CAP

- **GIVEN** the reply cap dialog shown on post-detail under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped
- **THEN** the dialog is dismissed AND the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.REPLY_CAP)`

#### Scenario: 400 invalid_request maps to a single InvalidContent outcome (no server empty/too-long split)

- **GIVEN** a `MockEngine` returning `400 {"error":{"code":"invalid_request"}}` (the single code the backend emits for both empty and over-limit content)
- **WHEN** the repository processes the response
- **THEN** the outcome is the single `InvalidContent` (retryable, with a logged diagnostic) — NOT two distinct `ContentEmpty`/`ContentTooLong` server outcomes, and NOT a generic wildcard failure

#### Scenario: Empty/over-limit is gated client-side before any POST

- **GIVEN** the composer projection with empty content, and again with a 281-code-point string
- **THEN** in both cases the "Balas" CTA is disabled (the over-limit flag set for the 281 case) so no `POST /api/v1/posts/{post_id}/replies` is issued — the empty-vs-too-long UX is driven by the client projection, not a server response

### Requirement: Every fetch outcome maps to exactly one sealed member with no generic fallthrough

Each post-detail operation SHALL map every HTTP status + transport-failure type to exactly one member of its sealed outcome type. The operations are: like toggle, reply post, replies list, like count, and — as of `post-detail-vm-reply-delete-restyle` — own-reply delete. The mapping is keyed on the HTTP **status code** (and the parsed `error.code` only where the backend distinguishes by code), with no generic "load failed" / "submit failed" wildcard branch.

The reply delete maps `204` → `ReplyDeleteOutcome.Deleted` and every other status + transport failure → the single retryable `ReplyDeleteOutcome.NetworkError`. The backend route never emits `403` / `404` / `429` by contract (`post-replies` § "DELETE replies — author-only soft-delete, idempotent 204"; § "DELETE … is NOT rate-limited"), so no member is invented for them.

HTTP `401` SHALL be delegated to the shipped `Auth` `refreshTokens` (terminal 401 → `SessionInvalidator` → `SignInScreen`) and MUST NOT be mapped here. `CancellationException` MUST be rethrown, never mapped to `NetworkError`.

#### Scenario: Each operation enumerates its statuses with no wildcard

- **WHEN** inspecting the repository's result mapping for the like, reply-post, replies-list, like-count, and reply-delete operations
- **THEN** each maps its happy status (`204`/`201`/`200`/`204`) and its failure statuses to exactly one sealed member with NO `else`/wildcard emitting a generic copy (the like / reply-post operations map `429`, `404`, and `5xx`/IO distinctly; the delete maps every non-`204` to its single retryable member, per the backend contract); `401` is delegated to the `Auth` plugin (not mapped)

#### Scenario: CancellationException is rethrown

- **GIVEN** an in-flight operation whose coroutine is cancelled (the HTTP call throws `CancellationException`)
- **WHEN** the client's catch handling runs
- **THEN** the `CancellationException` is rethrown (structured concurrency unwinds) and is NOT mapped to `NetworkError`

### Requirement: Pure PostDetailUiState projection (Compose-free, unit-testable, PII-free)

The mobile app SHALL model the screen state as Compose-free `PostDetailUiState` data class(es) plus pure projection function(s), mirroring `NearbyTimelineUiState` / `PostCreationUiState`. This keeps the outcome→state mapping and the reply code-point gate deterministically unit-testable in commonTest without composing the UI. The projection MUST carry no coordinates and no wall-clock / platform dependency.

As of `post-detail-vm-reply-delete-restyle`, `PostDetailViewModel` projects one screen-level `PostDetailUiState`. It holds:
- the displayed content and freshness fields (`editedAtIso`, `isAuthor`);
- like state;
- the replies sub-state and paging flags;
- composer in-flight / outcome;
- the report / block / delete targets and one-shots.

That state MAY carry the freshness read's post-author `authorUserId`, SOLELY as the post-header block target and the profile-navigation argument. It MUST NOT be rendered in any UI node or logged.

As of `mobile-block-from-content`, the **reply** UI model MAY carry the reply `author_id` (already on the reply wire), SOLELY to drive the client-side authorship gates and as the block-request path param. This `author_id` MUST NOT be rendered in any UI node and MUST NOT be logged (the "No author identifier or coordinate is rendered or logged" requirement is preserved).

The reply UI model SHALL also carry:
- the reply author's **display identity** (`authorUsername` / `authorDisplayName` from the reply wire — design D7). Display identity is renderable, not PII.
- as of `post-detail-vm-reply-delete-restyle`, a boolean `isOwn`, projected as `selfUserId != null && authorId == selfUserId` (fail-closed). It drives the delete gate (shown only when own) and hides the block item on an own reply.

The session `selfUserId` itself is NOT projected. `PostDetailUiState` carries only a boolean `selfResolved` (the self id has resolved to a non-null value). The reply block item SHALL stay absent until `selfResolved` is true, so neither gate can fail open while the session id is unknown. No other PII (coordinates, token material) enters projected state.

#### Scenario: Projection maps each outcome to its state deterministically

- **WHEN** the projection is invoked for the like states (liked / not-liked / rate-limited), the replies states (loading / loaded-non-empty / empty / error), and the reply-post states (success / content-empty / content-too-long / rate-limited / network-error)
- **THEN** each call returns the corresponding state deterministically (no wall-clock or platform dependency) AND no projected state carries a coordinate

#### Scenario: The reply model carries author_id only for the authorship gates, never rendered

- **GIVEN** a reply with `author_id = "33333333-3333-3333-3333-333333333333"` and `author_display_name = "Sinta Maharani"`
- **WHEN** the reply is projected into `PostDetailUiState`
- **THEN** the reply model carries `author_id` (available for the authorship gates and the block path param) AND the display identity (renderable) AND no rendered node or log line contains `"33333333-3333-3333-3333-333333333333"`

#### Scenario: isOwn is fail-closed on the session id

- **GIVEN** a reply with `author_id = "U"`
- **WHEN** it is projected with `selfUserId = "U"`, with `selfUserId = "V"`, and with `selfUserId = null`
- **THEN** `isOwn` is `true`, `false`, and `false` respectively

#### Scenario: Neither authorship gate opens while the session id is unresolved

- **GIVEN** `SelfUserIdProvider` returns null AND a loaded reply with a wire username
- **WHEN** the reply's kebab is opened
- **THEN** neither the "Blokir" nor the "Hapus balasan" item is present (the "Laporkan" item remains)

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

### Requirement: Replies list wires cursor load-more via PostDetailViewModel

The post-detail replies list SHALL wire cursor-based load-more following `mobile-design-system` § "Canonical list load-more (infinite-scroll) pattern". The replies list + paging state SHALL be held in a `PostDetailViewModel` resolved via `viewModel { … }`, scoped to the post-detail NavEntry (mirroring the timeline-VM migration in [#167](https://github.com/aditrioka/nearyou-id/pull/167)), not in composition-local `var`s. The VM owns:
- the replies list;
- the current replies `next_cursor`;
- the load-more `isLoadingMore` / `endReached` / `loadMoreError` state, via the shared load-more controller.

`PostDetailFlow` SHALL provide a cursor-bearing replies load-more path (`loadMoreReplies(postId, cursor)`) issuing `GET /api/v1/posts/{post_id}/replies?cursor=…`. The reply DTO `next_cursor` is snake_case (`@SerialName("next_cursor")`), distinct from the timelines' bare camelCase `nextCursor`.

As of `post-detail-vm-reply-delete-restyle` the like and reply-composer state have ALSO moved into this ViewModel, per § "PostDetailViewModel owns all post-detail work with cancel-safe writes". The former "like + composer state stay composition-local" carve-out is retired.

Replies render as `items()` in the SAME `LazyColumn` as the post header + action-row items; the composer is a separate `bottomBar`. Scroll-end detection SHALL therefore key off the replies-items region, so it does not mis-fire while only the header / action row is on screen.

The existing optimistic new-reply behavior SHALL be preserved:
- A successfully posted reply is **prepended** to the VM-held replies list (above page 1) and the reply count increments.
- Load-more appends pages at the END and never interferes with the prepend.
- A deleted own reply is removed in place without disturbing the cursor.

#### Scenario: Replies state is held in PostDetailViewModel, not composition-local

- **WHEN** inspecting the post-detail screen and its state holder
- **THEN** the replies list + replies cursor + load-more flags are exposed through the `PostDetailViewModel`'s single `uiState` (collected via `collectAsStateWithLifecycle`), NOT by composition-local `remember`/`var` reply state

#### Scenario: Scrolling near the end of the replies issues a cursor-bearing follow-up

- **GIVEN** the post-detail screen with a loaded first page of replies whose `Loaded.nextCursor = "c1"` AND a MockEngine/fake capturing requests
- **WHEN** the user scrolls near the end of the replies list
- **THEN** exactly one follow-up `GET /api/v1/posts/{post_id}/replies` is issued carrying `cursor=c1` (the threshold keys off the replies items, not the header/action row)

#### Scenario: The second reply page appends below existing replies and advances the cursor

- **GIVEN** a fake returning a second page of replies with `nextCursor = "c2"` for `cursor = "c1"`
- **WHEN** replies load-more completes
- **THEN** the second page's replies are appended below the first page (page-1 replies retained, post header + action row still first) AND the replies cursor is `"c2"`

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
- **THEN** the first-page replies remain rendered (the post header + action row unaffected) AND a non-destructive load-more error footer with a retry control is shown AND retry re-issues the `cursor`-bearing follow-up for the same cursor

### Requirement: Post header exposes a report affordance for non-authored posts

`PostDetailScreen` SHALL expose a "Laporkan" report affordance in the post header, shown ONLY when the viewer does NOT author the post (the server-authoritative `isAuthor` boolean — mirroring the existing Edit-affordance gate). Activating it SHALL open the shared report dialog (`mobile-content-report`) targeting `target_type = "post"`, `target_id = <the post id>`. The affordance SHALL NOT appear for the viewer's own post. The affordance SHALL NOT introduce any author UUID or coordinate into the rendered tree (the "No author identifier or coordinate is rendered or logged" requirement is preserved).

#### Scenario: Report affordance shown on a non-authored post
- **GIVEN** the post header renders with `isAuthor = false`
- **WHEN** the header is inspected
- **THEN** a "Laporkan" report affordance is present AND activating it opens the report dialog targeting the post

#### Scenario: Report affordance hidden on the viewer's own post
- **GIVEN** the post header renders with `isAuthor = true`
- **WHEN** the header is inspected
- **THEN** no "Laporkan" post affordance is present (the Edit affordance is shown instead, per the existing header requirement)

#### Scenario: Completing the dialog submits the post target
- **WHEN** the viewer completes the report dialog for the post
- **THEN** a `POST /api/v1/reports` with `target_type = "post"` and the post id is issued AND the outcome is handled per `mobile-content-report`

### Requirement: Each reply row exposes a report affordance

Each reply row in `PostDetailScreen` SHALL expose a "Laporkan" report affordance that opens the shared report dialog (`mobile-content-report`) targeting `target_type = "reply"`, `target_id = <the reply id>`. The affordance SHALL NOT render or rely on reply author identity — the reply wire carries `author_id` but it is never rendered and never used to gate the affordance; only the reply `id` is used as `target_id`. The "No author identifier or coordinate is rendered or logged" requirement is preserved (no author UUID appears in the rendered tree, logs, or the report request).

#### Scenario: Reply row exposes a report affordance
- **WHEN** a reply card renders
- **THEN** it exposes a "Laporkan" report affordance AND activating it opens the report dialog targeting that reply's id

#### Scenario: Reply report uses the reply id only, never author identity
- **GIVEN** a reply with `author_id = "11111111-1111-1111-1111-111111111111"` and `id = "R"`
- **WHEN** the report dialog is opened from that reply and submitted
- **THEN** the request `target_id` is `"R"` AND no rendered node, log line, or request field contains `"11111111-1111-1111-1111-111111111111"`

### Requirement: PostDetailScreen report affordances are covered by tests

The Robolectric `PostDetailScreenTest` SHALL additionally cover: the post report affordance present on a non-authored post and absent on the viewer's own post; a reply-row report affordance present; and the dialog submit → success-message path (via a fake report seam). These additions remain within the existing Release-variant `*ScreenTest` exclude and pass under `:mobile:app:testDevReleaseUnitTest`.

#### Scenario: Report affordance tests exist and are discoverable
- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `PostDetailScreenTest` includes tests asserting the non-authored-post report affordance, the own-post report-affordance absence, the reply-row report affordance, and the dialog submit → success-message path

### Requirement: Post header exposes a block affordance for non-authored posts

`PostDetailScreen` SHALL expose a "Blokir @{username}" block affordance in the post-header overflow kebab (alongside the existing "Laporkan" report item), shown ONLY when the viewer does NOT author the post (the server-authoritative `isAuthor` boolean from the single-post freshness read — the same gate as the Edit and Report affordances). Activating it SHALL open the shared block confirmation dialog (`mobile-block-from-content`) targeting the post author; on confirm it SHALL issue `POST /api/v1/blocks/{authorUserId}` via the shared block seam. The `authorUserId` SHALL be obtained from the single-post freshness read (`single-post-read` `SinglePostResponse.authorUserId`, the same server-authoritative source as `isAuthor`) — NOT from the `PostDetailRoute` payload, which continues to carry no author UUID (the serialized-back-stack PII discipline is preserved). The affordance SHALL NOT render the `authorUserId` (the "No author identifier or coordinate is rendered or logged" requirement is preserved); the UUID is used only as the block-request path param. When the freshness read has not resolved an `authorUserId` (e.g. it failed and degraded to `Unavailable`), the block affordance SHALL be absent (graceful, mirroring the Edit affordance's dependence on the same read). This un-defers the block-from-post-context affordance and resolves GitHub issue [#200](https://github.com/aditrioka/nearyou-id/issues/200).

#### Scenario: Block affordance shown on a non-authored post

- **GIVEN** the post header renders with `isAuthor = false` and an `authorUserId` resolved from the freshness read
- **WHEN** the header overflow kebab is inspected
- **THEN** a "Blokir @{username}" affordance is present AND activating it opens the block confirmation dialog targeting the post author

#### Scenario: Block affordance hidden on the viewer's own post

- **GIVEN** the post header renders with `isAuthor = true`
- **WHEN** the header overflow kebab is inspected
- **THEN** no "Blokir" affordance is present (the Edit affordance is shown instead, per the existing header requirement)

#### Scenario: Confirming the dialog issues the block against the author UUID

- **GIVEN** the post header renders with `isAuthor = false` and `authorUserId = "A"`
- **WHEN** the viewer confirms the block dialog for the post
- **THEN** a `POST /api/v1/blocks/A` is issued via the shared block seam AND the outcome is handled per `mobile-block-from-content`

#### Scenario: Block affordance absent when the freshness read has no authorUserId

- **GIVEN** the single-post freshness read degraded to `Unavailable` (no `authorUserId` resolved)
- **WHEN** the header overflow kebab is inspected
- **THEN** no "Blokir" affordance is present (graceful degradation, the same dependence as the Edit affordance)

### Requirement: Each reply row exposes a block affordance

Each reply row in `PostDetailScreen` SHALL expose a "Blokir @{username}" block affordance in its overflow kebab (alongside the existing "Laporkan" report item) — `{username}` interpolated from the reply wire's `author_username` (design D7) — shown ONLY when the reply is NOT authored by the viewer AND the reply carries a non-blank `author_username` (an older-backend body without identity cannot render the canonical copy, so the block item is absent — the same graceful degradation as the post-header affordance without an `authorUserId`). Authorship SHALL be determined by comparing the reply's `author_id` (already carried on the reply wire) to the session user id from the existing `SelfUserIdProvider`; the `author_id` SHALL NOT be rendered in any UI node (the UUID discipline is preserved) and is used only for this self-block gate and as the block-request path param. Activating it SHALL open the shared block confirmation dialog (`mobile-block-from-content`) targeting the reply author; on confirm it SHALL issue `POST /api/v1/blocks/{replyAuthorId}` via the shared block seam.

#### Scenario: Reply row exposes a block affordance for another user's reply

- **GIVEN** a reply with `author_id = "B"` and `author_username = "sinta.mhr"` where `B` is not the session user
- **WHEN** the reply card overflow kebab is inspected
- **THEN** it exposes a "Blokir @sinta.mhr" affordance AND activating it opens the block confirmation dialog targeting `B`

#### Scenario: Reply block is absent without a wire identity

- **GIVEN** a reply whose `author_username` is null/blank (an older-backend body)
- **WHEN** the reply card overflow kebab is inspected
- **THEN** no "Blokir" affordance is present on that reply (the report item remains)

#### Scenario: Reply block is hidden on the viewer's own reply

- **GIVEN** a reply whose `author_id` equals the session user id from `SelfUserIdProvider`
- **WHEN** the reply card overflow kebab is inspected
- **THEN** no "Blokir" affordance is present on that reply

#### Scenario: Reply block uses the reply author UUID as the block target, never rendering it

- **GIVEN** a reply with `author_id = "11111111-1111-1111-1111-111111111111"` authored by another user
- **WHEN** the block dialog is opened from that reply and confirmed
- **THEN** the request is `POST /api/v1/blocks/11111111-1111-1111-1111-111111111111` AND no rendered node or log line contains `"11111111-1111-1111-1111-111111111111"`

### Requirement: Post header identity row opens the author profile

The post-header author-identity row (the letter avatar + display name + @handle) SHALL be a tap target that emits navigation to the author's profile via a hoisted `onOpenProfile(userId: String)` lambda — wired by the host (`AppEntryProvider`) to push `ProfileRoute(authorUserId)` onto the ROOT back stack, the same profile-entry mechanism as the feed-card identity tap (the `mobile-profile` entry convention; the screen stays navigation-free per § "PostDetailScreen is reached via the root back stack and is navigation-free"). The `authorUserId` SHALL be sourced from the single-post freshness read (`single-post-read` `SinglePostResponse.authorUserId`) — NOT from the `PostDetailRoute` payload, which continues to carry no author UUID (the serialized-back-stack PII discipline is preserved). When the freshness read has not resolved an `authorUserId` (in-flight, failed/`Unavailable`, or an older backend), the identity row SHALL NOT be tappable (graceful absence — the same dependence as the Edit and Block affordances). The identity tap SHALL apply on own AND non-authored posts alike (the profile surface itself renders the self/other distinction). The `authorUserId` SHALL NOT be rendered or logged (the "No author identifier or coordinate is rendered or logged" requirement is preserved); it is used solely as the navigation argument. The tap target SHALL carry a stable test tag so the affordance is assertable.

#### Scenario: Identity tap opens the author profile once the freshness read resolves

- **GIVEN** the detail surface rendered with a payload identity and a freshness read that resolved `authorUserId = "A"`
- **WHEN** the header identity row is tapped
- **THEN** `onOpenProfile("A")` fires (the host pushes `ProfileRoute("A")` onto the root back stack)

#### Scenario: Identity row is not tappable when the freshness read degraded

- **GIVEN** the detail surface rendered with a payload identity but a freshness read that degraded to `Unavailable` (no `authorUserId`)
- **WHEN** the header identity row is activated
- **THEN** no navigation is emitted (`onOpenProfile` never fires) — the identity renders as today, display-only

#### Scenario: No author UUID appears in the rendered tree

- **GIVEN** the detail surface rendered with a resolved `authorUserId = "11111111-1111-1111-1111-111111111111"`
- **WHEN** the rendered tree is inspected
- **THEN** no node's text contains `"11111111-1111-1111-1111-111111111111"` (the UUID is a navigation argument only)

### Requirement: Each reply row identity opens the reply author profile

Each reply row's author-identity row (the letter avatar + display name / @handle fallback, rendered when the reply wire carries an identity per `mobile-block-from-content` D7) SHALL be a tap target that emits `onOpenProfile(reply.authorId)` — the reply wire's `author_id`, already carried for the self-block gate — routed by the host to `ProfileRoute(authorId)` on the ROOT back stack. When the reply carries no wire identity (an older-backend body → no identity row renders at all), there is no tap target (unchanged graceful absence). The affordance SHALL apply to the viewer's own replies too (the profile surface renders the self case). The reply `authorId` SHALL NOT be rendered or logged; it is used solely as the navigation argument. The tap target SHALL carry a stable test tag so the affordance is assertable.

#### Scenario: Reply identity tap opens the reply author's profile

- **GIVEN** a rendered reply with `author_id = "B"` and a wire identity (`author_username = "sinta.mhr"`)
- **WHEN** that reply's identity row is tapped
- **THEN** `onOpenProfile("B")` fires (the host pushes `ProfileRoute("B")`)

#### Scenario: No tap target without a wire identity

- **GIVEN** a rendered reply whose `author_username`/`author_display_name` are null/blank (an older-backend body)
- **WHEN** the reply card is inspected
- **THEN** no identity row renders (unchanged) AND no identity tap target exists on that reply

### Requirement: Tap-to-profile affordances are covered by tests

The change SHALL extend the Robolectric `PostDetailScreenTest` with: (1) the header identity tap firing `onOpenProfile` with the freshness-read `authorUserId`; (2) the header identity NOT firing when the freshness read degraded (`Unavailable`); (3) a reply identity tap firing `onOpenProfile` with the reply's `author_id`; (4) the no-UUID-in-tree assertion holding with the tap affordances present.

#### Scenario: Tap-to-profile tests exist and pass

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `PostDetailScreenTest` covers the header-tap fire, the degraded-read no-fire, and the reply-tap fire, and all pass

### Requirement: By-id post reads never gate the header's first paint

The post-detail surface SHALL be fed by the `single-post-read` capability (`GET /api/v1/posts/{post_id}`; the backend half of GitHub issue [#202](https://github.com/aditrioka/nearyou-id/issues/202), now closed) in exactly the three shipped ways below — the screen's own resume read, the deep-link lookup done before the push, and the search-result lookup done before the push — and none SHALL block the header's first paint — the header is always first built from the `PostDetailRoute` nav args (per § "The post header renders from nav args without a single-post re-fetch"):

- **Resume-time freshness read** (owned by `mobile-post-editing` § "Post-detail reads edit state from a single-post-read refresh"): on each resume the screen fetches the minimal projection via `SinglePostApiClient.fetchPost` to refresh the displayed content and resolve `editedAt` / `isAuthor` / `authorUserId`; a non-200 or transport failure degrades silently to the payload (no error state).
- **No-card entry** (owned by `mobile-notifications-list` § "A post-target notification resolves to a PostDetailTarget via the full-projection single-post fetch"): a notification tap into a post — from the in-app list or a push tap, both via the shared notification target resolver — resolves the FULL projection via `SinglePostApiClient.fetchFullPost` BEFORE `PostDetailRoute` is pushed (the screen itself does not call it), so the pushed route still carries every display field and the header renders from it exactly as on a card tap; a resolution failure pushes nothing.
- **Search-result entry** (owned by `mobile-search` § "A result tap opens PostDetailRoute hydrated from the by-id post read"): a search result tap resolves the FULL projection via `SinglePostApiClient.fetchFullPost` BEFORE `PostDetailRoute` is pushed (the screen itself does not call it), so the pushed route carries the real city, like state, reply count, and image, and the header renders from it exactly as on a card tap. Unlike the notification entry, a search hit already carries a renderable payload, so a resolution failure still pushes: the hit's own fields with the documented defaults (`cityName = ""`, `likedByViewer = false`, `replyCount = 0`, `imageUrl = null`). `distanceM` is `null` on both paths.

Replies cursor load-more is **no longer deferred** — it is implemented per the § "Replies list wires cursor load-more via PostDetailViewModel" requirement, which closes the replies half of GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188).

#### Scenario: A failed freshness read keeps the payload header

- **GIVEN** a `PostDetailScreen` composed from a `PostDetailRoute` whose resume-time `GET /api/v1/posts/{id}` returns `404` (or fails at transport)
- **WHEN** the screen renders
- **THEN** the header shows the route payload's content AND no error state is rendered

#### Scenario: A notification deep-link builds the route from the full projection

- **GIVEN** a `post`-target notification whose full-projection `GET /api/v1/posts/{target_id}` returns `200`
- **WHEN** the notification is tapped
- **THEN** the pushed `PostDetailRoute` carries the projection's display fields (content, city, like state, reply count, author identity) with `distanceM = null`, and the header renders from that payload

#### Scenario: Replies load-more is not deferred

- **WHEN** the replies list scrolls to its end while the last page carried a `next_cursor`
- **THEN** a `cursor=`-bearing follow-up `GET /replies` request is issued (the replies half of issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) is implemented, not deferred)

#### Scenario: A search-result tap builds the route from the full projection

- **GIVEN** a search hit whose full-projection `GET /api/v1/posts/{post_id}` returns `200`
- **WHEN** the result is tapped
- **THEN** the pushed `PostDetailRoute` carries the projection's display fields (content, city, like state, reply count, author identity, image) with `distanceM = null`, and the header renders from that payload

#### Scenario: A failed search-result lookup still opens the detail from the hit

- **GIVEN** a search hit whose full-projection read returns `404` (or fails at transport)
- **WHEN** the result is tapped
- **THEN** a `PostDetailRoute` is pushed carrying the hit's content and author identity with `cityName = ""`, `likedByViewer = false`, `replyCount = 0`, `distanceM = null`, and no image

### Requirement: PostDetailViewModel owns all post-detail work with cancel-safe writes

Every post-detail network operation SHALL be launched by `PostDetailViewModel` (`viewModelScope`). This covers the like toggle, the like-count read, the reply POST, the own-reply DELETE, the resume-time single-post freshness read, the session self-id read (`SelfUserIdProvider`), the replies first page, the replies load-more, report submission and block submission. `PostDetailScreen` and its split-out composable files SHALL NOT launch any of them from a composition scope: no `rememberCoroutineScope()`-launched repository call, and no `LaunchedEffect` whose body calls a repository / flow.

One exception remains: the `mobile-post-editing` "Riwayat edit" overlay (`EditHistorySheet.kt`, its own file and capability) still loads the edit history from its own composition. Moving it into a ViewModel is tracked by [#576](https://github.com/aditrioka/nearyou-id/issues/576).

The screen MAY forward lifecycle triggers to the VM. Its `LifecycleEventEffect(ON_RESUME)` calls `viewModel.refreshPost()` (docs/11 §2.3 "silent re-read in the screen's VM").

The in-flight guards SHALL be VM state (`likeInFlight`, `replyInFlight`), claimed synchronously before the launch so a same-frame double tap cannot double-submit.

The VM SHALL expose exactly ONE `uiState: StateFlow<PostDetailUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`, collected with `collectAsStateWithLifecycle()`. It SHALL NOT expose any other public `StateFlow`. One-shot events SHALL be nullable / boolean `uiState` fields cleared through `onXxxShown()` callbacks, never a `Channel` / `SharedFlow`. These are: the report / block / delete result messages, the post-block pop-back, and the reply-posted draft clear.

The network leg of every **write** (like toggle, reply POST, reply DELETE, report submission, block submission) SHALL run under `NonCancellable`. Once issued, a write SHALL complete even if the post-detail entry is popped and the VM cleared mid-flight. Reads SHALL remain cancellable.

The reply draft text SHALL remain composable-held saveable UI state. On a `201` the VM SHALL raise a one-shot `replyPosted` flag, and the screen clears the draft and acknowledges it.

#### Scenario: A reply POST completes after the entry is popped mid-flight

- **GIVEN** a `PostDetailViewModel` whose `PostDetailFlow.postReply` suspends on a gate
- **WHEN** a reply is submitted, the ViewModel is cleared (its `ViewModelStore` cleared, cancelling `viewModelScope`), and the gate is then released
- **THEN** the `postReply` call runs to completion (it is NOT cancelled)

#### Scenario: A like toggle completes after the entry is popped mid-flight

- **GIVEN** a `PostDetailViewModel` whose `toggleLike` suspends on a gate
- **WHEN** the like is toggled, the ViewModel is cleared, and the gate is released
- **THEN** the `toggleLike` call runs to completion (it is NOT cancelled)

#### Scenario: An own-reply DELETE completes after the entry is popped mid-flight

- **GIVEN** a `PostDetailViewModel` whose `deleteReply` suspends on a gate AND the viewer's own reply loaded
- **WHEN** the delete is confirmed, the ViewModel is cleared, and the gate is released
- **THEN** the `deleteReply` call runs to completion (it is NOT cancelled)

#### Scenario: A double submit issues one reply POST

- **GIVEN** a reply submission in flight
- **WHEN** `onSubmitReply` is invoked a second time before the first resolves
- **THEN** exactly one `postReply` call is issued

#### Scenario: The screen launches no repository work from composition

- **WHEN** inspecting the post-detail UI source files (`PostDetailScreen.kt`, `PostDetailHeader.kt`, `PostDetailReplies.kt`, `PostDetailComposer.kt`) with comments stripped
- **THEN** none contains `rememberCoroutineScope`; none references `PostDetailFlow`, `PostEditFlow`, `SelfUserIdProvider`, `ReportSubmitter` or `BlockSubmitter` outside the injections that construct the ViewModel in `PostDetailScreen`; AND none calls a member of those injected dependencies (no `flow.…(`, `editFlow.…(`, `selfUserIdProvider.…(`, `reportSubmitter.…(`, `blockSubmitter.…(`)

#### Scenario: The ViewModel exposes one state stream and no event bus

- **WHEN** inspecting `PostDetailViewModel.kt` with comments stripped
- **THEN** exactly one public `StateFlow` property (`uiState`) is declared AND no `Channel` / `SharedFlow` is used

#### Scenario: A 201 clears the draft through a one-shot

- **GIVEN** the composer holds "halo" AND `postReply` returns `Success`
- **WHEN** the reply is submitted
- **THEN** the composer field becomes empty AND `uiState.replyPosted` is cleared after the screen acknowledges it

### Requirement: Viewer can delete their own reply from post-detail

Each reply row's overflow kebab SHALL include a "Hapus balasan" item (`stringResource(Res.string.post_detail_reply_delete_action)`, test tag `postDetailDeleteReply`). The item is present ONLY when the reply is the viewer's own: the reply wire `author_id` equals the session user id from `SelfUserIdProvider`, projected as `ReplyUi.isOwn`. The gate SHALL fail closed: while the session id is unresolved, or the token is malformed (null), no reply shows the item. The item SHALL be the first menu item. The existing "Laporkan" item stays on every reply, unchanged.

Activating the item SHALL open a confirmation dialog (test tag `postDetailDeleteReplyDialog`) with:

- title `post_detail_reply_delete_title`;
- body `post_detail_reply_delete_body`;
- a confirm action `cta_delete`;
- a dismiss action `cta_cancel`.

Dismissing it SHALL issue no request. Confirming it SHALL:

1. Close the dialog.
2. Remove the reply from the displayed list optimistically.
3. Decrement the displayed reply count by one, floored at 0. The public `reply_count` excludes soft-deleted replies server-side, so a self-delete really lowers it (unlike a viewer-local block).
4. Issue `DELETE /api/v1/posts/{post_id}/replies/{reply_id}` via `ReplyApiClient` → `PostDetailFlow.deleteReply` → a sealed `ReplyDeleteOutcome`.

The outcome mapping:

- `204` → `Deleted`. The backend returns `204` idempotently, also for already-deleted or never-existing replies, so `Deleted` keeps the optimistic state.
- Any other status or a transport failure → `NetworkError`, the single retryable failure member. The route never emits `403` / `404` / `429` by contract.
- `401` is delegated to the `Auth` plugin.

On `NetworkError`:

- The reply SHALL be restored at its original position, clamped to the list size. This is skipped when a concurrent reload already re-listed it, or when the list is no longer loaded.
- The count SHALL be restored by adding back exactly the amount the optimistic step subtracted (1, or 0 when the count was already 0).
- A one-shot snackbar `post_detail_reply_delete_failed` SHALL show.

The ViewModel SHALL ignore a confirmed delete for a reply that is not the viewer's own (defence in depth — the backend `204`s a stranger's reply too, which would otherwise hide it locally).

The reply `author_id` SHALL NOT be rendered or logged. It is used only for the `isOwn` comparison. The DELETE carries only the post id and the reply id.

#### Scenario: Delete item shows only on the viewer's own reply

- **GIVEN** the session user id is `"U"` AND the loaded replies include one with `author_id = "U"` and one with `author_id = "B"`
- **WHEN** each reply's kebab is opened
- **THEN** the own reply's menu contains "Hapus balasan" AND the other reply's menu does not

#### Scenario: Delete item is absent while the session id is unresolved

- **GIVEN** `SelfUserIdProvider` returns null
- **WHEN** any reply's kebab is opened
- **THEN** no "Hapus balasan" item is present

#### Scenario: Confirming removes the reply and decrements the count, then keeps it on 204

- **GIVEN** a displayed reply count of `2` AND the viewer's own reply "MINE" in the list AND `deleteReply` returns `Deleted`
- **WHEN** "Hapus balasan" is activated and the dialog confirmed
- **THEN** "MINE" is no longer rendered AND the displayed count is `1` AND exactly one `deleteReply(postId, replyId)` call was issued with the reply's id

#### Scenario: Cancelling the dialog issues no request

- **WHEN** the delete dialog is opened and dismissed with "Batal"
- **THEN** the reply is still rendered AND zero `deleteReply` calls were issued

#### Scenario: A failed delete restores the reply and the count and shows the failure message

- **GIVEN** the replies `[A, MINE, C]`, a count of `3`, AND `deleteReply` returns `NetworkError`
- **WHEN** the delete of "MINE" is confirmed
- **THEN** the list is again `[A, MINE, C]` (same position) AND the count is `3` AND a snackbar with `stringResource(Res.string.post_detail_reply_delete_failed)` is shown

#### Scenario: A restore never duplicates a re-listed reply

- **GIVEN** the delete of "MINE" is in flight AND a replies reload re-lists "MINE"
- **WHEN** the DELETE returns `NetworkError`
- **THEN** "MINE" appears exactly once in the list

#### Scenario: The DELETE maps 204 to Deleted and anything else to NetworkError

- **GIVEN** a `MockEngine` answering `DELETE /api/v1/posts/p1/replies/r1`
- **WHEN** it returns `204`, then `500`, then a transport exception
- **THEN** the outcomes are `Deleted`, `NetworkError`, `NetworkError` respectively AND the request carried no body

### Requirement: Frame-7 elements outside the restyle are deferred

Post-detail SHALL NOT yet render four elements of mockup frame 7 (`dev/mockups/nearyou-screens-mockup.html` · "Detail postingan + balasan"). Each is tracked for a follow-up change that will MODIFY this requirement:

- **(a) A post-header "Ikuti" follow button** (tracked: [#569](https://github.com/aditrioka/nearyou-id/issues/569)). It will be an optimistic follow toggle beside the author identity, hidden on the viewer's own post, reusing the `mobile-profile` follow seam.
- **(b) Per-reply likes** (tracked: [#570](https://github.com/aditrioka/nearyou-id/issues/570)). Each reply row will show a heart + like count, backed by a future reply-likes capability.
- **(c) The reply composer's leading self-avatar** (tracked: [#569](https://github.com/aditrioka/nearyou-id/issues/569)). The viewer's own `LetterAvatar` will lead the composer bar once a cached self identity exists.
- **(d) The Premium badge + name treatment on a reply author** (tracked: [#575](https://github.com/aditrioka/nearyou-id/issues/575)). A Premium author's identity will show the `workspace_premium` badge and the tinted name, once the reply wire carries an author-premium display flag.

Until then, post-detail SHALL render none of the four.

#### Scenario: No follow button in the post header

- **GIVEN** the detail surface rendered for another user's post with a resolved `authorUserId`
- **WHEN** the rendered tree is inspected
- **THEN** no node with text "Ikuti" exists AND no follow request is issued

#### Scenario: Reply rows carry no like control

- **GIVEN** a loaded replies list
- **WHEN** a reply row is inspected
- **THEN** it renders no like / heart affordance and no per-reply like count

#### Scenario: Reply identities carry no Premium badge

- **WHEN** a reply row with a wire identity renders
- **THEN** it renders the avatar, the display name and the date only — no Premium badge node

#### Scenario: The composer has no self-avatar

- **WHEN** the reply composer renders
- **THEN** it contains the reply field and the send action only — no avatar node

### Requirement: Post-detail ViewModel, delete, and restyle are covered by tests

The change SHALL ship:

1. `PostDetailViewModelTest` (commonTest) rewritten onto the single `uiState` (a background collector). It covers:
   - every previously-asserted VM behaviour;
   - the cancel-safety scenarios: reply, like and delete each complete after the `ViewModelStore` is cleared mid-flight;
   - the reply and like double-submit guards;
   - the like optimistic flip + exact-count revert, and the reply `replyPosted` one-shot;
   - own-reply delete: the optimistic removal + decrement, the keep on `Deleted`, the positional restore + count restore + failure message on `NetworkError`, the no-duplicate restore after a re-list, a no-op for a non-own reply, a null self id keeping both gates closed, and dismiss-without-request;
   - `onBlockPostClicked()` as a no-op without an `authorUserId` or on an own post.
2. `PostDetailUiStateTest` coverage of `ReplyUi.isOwn` (own / other / null self id).
3. `PostDetailApiTest` MockEngine coverage of the DELETE → `ReplyDeleteOutcome` mapping.
4. `PostDetailScreenTest` (Robolectric) coverage of:
   - the delete item present on the own reply and absent on another's;
   - dialog confirm → row removed + count decremented;
   - dialog cancel → no request;
   - a failed delete → row restored + failure snackbar;
   - the frame-7 chrome nodes (title "Postingan", the back arrow's "Kembali" description, the "N balasan" subhead, the bare like count, the send action described "Balas");
   - every pre-existing screen scenario migrated to the new selectors.
5. `PostDetailSourceGuardTest` scanning all four post-detail UI files (concatenated) for:
   - the no-literal guard and the existing `stringResource` presence checks;
   - the no-composition-launch guard, including the injected-dependency call pattern.

   It also scans `PostDetailViewModel.kt` for the single public `StateFlow` and no event bus, and adds it to the no-`println` / no-logging scan.
6. `PostDetailFlowIosTest` kept green under `:mobile:app:iosSimulatorArm64Test`. Only its selectors may change to follow the restyle; its scenarios are unchanged.

#### Scenario: The new and migrated tests pass in every gate

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest :mobile:app:iosSimulatorArm64Test :mobile:app:linkDebugFrameworkIosSimulatorArm64`
- **THEN** the post-detail VM, projection, API, screen, source-guard and iOS flow tests are discovered and pass, AND `PostDetailScreenTest` stays in the Release-variant `*ScreenTest` exclude

