## ADDED Requirements

### Requirement: PostDetailViewModel owns all post-detail work with cancel-safe writes

Every post-detail network operation SHALL be launched by `PostDetailViewModel` (`viewModelScope`). This covers the like toggle, the like-count read, the reply POST, the own-reply DELETE, the resume-time single-post freshness read, the session self-id read (`SelfUserIdProvider`), the replies first page, the replies load-more, report submission and block submission. `PostDetailScreen` and its split-out composable files SHALL NOT launch any of them from a composition scope: no `rememberCoroutineScope()`-launched repository call, and no `LaunchedEffect` whose body calls a repository / flow.

The screen MAY forward lifecycle triggers to the VM. Its `LifecycleEventEffect(ON_RESUME)` calls `viewModel.refreshPost()` (docs/11 §2.3 "silent re-read in the screen's VM").

The in-flight guards SHALL be VM state (`likeInFlight`, `replyInFlight`), claimed synchronously before the launch so a same-frame double tap cannot double-submit.

The VM SHALL expose exactly ONE `uiState: StateFlow<PostDetailUiState>` produced via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`, collected with `collectAsStateWithLifecycle()`. It SHALL NOT expose any other public `StateFlow`. One-shot events SHALL be nullable / boolean `uiState` fields cleared through `onXxxShown()` callbacks, never a `Channel` / `SharedFlow`. These are: the report / block / delete result messages, the post-block pop-back, and the reply-posted draft clear.

The network leg of every **write** (like toggle, reply POST, reply DELETE) SHALL run under `NonCancellable`. Once issued, a write SHALL complete even if the post-detail entry is popped and the VM cleared mid-flight. Reads SHALL remain cancellable.

The reply draft text SHALL remain composable-held saveable UI state. On a `201` the VM SHALL raise a one-shot `replyPosted` flag, and the screen clears the draft and acknowledges it.

#### Scenario: A reply POST completes after the entry is popped mid-flight

- **GIVEN** a `PostDetailViewModel` whose `PostDetailFlow.postReply` suspends on a gate
- **WHEN** a reply is submitted, the ViewModel is cleared (its `ViewModelStore` cleared, cancelling `viewModelScope`), and the gate is then released
- **THEN** the `postReply` call runs to completion (it is NOT cancelled)

#### Scenario: A like toggle completes after the entry is popped mid-flight

- **GIVEN** a `PostDetailViewModel` whose `toggleLike` suspends on a gate
- **WHEN** the like is toggled, the ViewModel is cleared, and the gate is released
- **THEN** the `toggleLike` call runs to completion (it is NOT cancelled)

#### Scenario: A double submit issues one reply POST

- **GIVEN** a reply submission in flight
- **WHEN** `onSubmitReply` is invoked a second time before the first resolves
- **THEN** exactly one `postReply` call is issued

#### Scenario: The screen launches no repository work from composition

- **WHEN** inspecting the post-detail UI source files (`PostDetailScreen.kt`, `PostDetailHeader.kt`, `PostDetailReplies.kt`, `PostDetailComposer.kt`) with comments stripped
- **THEN** none contains `rememberCoroutineScope`, and none references `PostDetailFlow`, `PostEditFlow`, `SelfUserIdProvider`, `ReportSubmitter` or `BlockSubmitter` outside the ViewModel construction in `PostDetailScreen`

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

- The reply SHALL be restored at its original position, clamped to the list size, unless a concurrent reload already re-listed it.
- The count SHALL be restored.
- A one-shot snackbar `post_detail_reply_delete_failed` SHALL show.

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

#### Scenario: The DELETE maps 204 to Deleted and anything else to NetworkError

- **GIVEN** a `MockEngine` answering `DELETE /api/v1/posts/p1/replies/r1`
- **WHEN** it returns `204`, then `500`, then a transport exception
- **THEN** the outcomes are `Deleted`, `NetworkError`, `NetworkError` respectively AND the request carried no body

### Requirement: Frame-7 elements outside the restyle are deferred

Post-detail SHALL NOT yet render three elements of mockup frame 7 (`dev/mockups/nearyou-screens-mockup.html` · "Detail postingan + balasan"). Each is tracked for a follow-up change that will MODIFY this requirement:

- **(a) A post-header "Ikuti" follow button** (tracked: [#569](https://github.com/aditrioka/nearyou-id/issues/569)). It will be an optimistic follow toggle beside the author identity, hidden on the viewer's own post, reusing the `mobile-profile` follow seam.
- **(b) Per-reply likes** (tracked: [#570](https://github.com/aditrioka/nearyou-id/issues/570)). Each reply row will show a heart + like count, backed by a future reply-likes capability.
- **(c) The reply composer's leading self-avatar** (tracked: [#569](https://github.com/aditrioka/nearyou-id/issues/569)). The viewer's own `LetterAvatar` will lead the composer bar once a cached self identity exists.

Until then, post-detail SHALL render none of the three.

#### Scenario: No follow button in the post header

- **GIVEN** the detail surface rendered for another user's post with a resolved `authorUserId`
- **WHEN** the rendered tree is inspected
- **THEN** no node with text "Ikuti" exists AND no follow request is issued

#### Scenario: Reply rows carry no like control

- **GIVEN** a loaded replies list
- **WHEN** a reply row is inspected
- **THEN** it renders no like / heart affordance and no per-reply like count

#### Scenario: The composer has no self-avatar

- **WHEN** the reply composer renders
- **THEN** it contains the reply field and the send action only — no avatar node

### Requirement: Post-detail ViewModel, delete, and restyle are covered by tests

The change SHALL ship:

1. `PostDetailViewModelTest` (commonTest) rewritten onto the single `uiState` (a background collector). It covers every previously-asserted VM behaviour, plus the cancel-safety scenarios (reply and like complete after the ViewModel is cleared), the double-submit guard, the like optimistic flip + exact-count revert, and the reply `replyPosted` one-shot. It also covers own-reply delete: the optimistic removal + decrement, the keep on `Deleted`, the positional restore + count restore + failure message on `NetworkError`, the fail-closed `isOwn` with a null self id, and dismiss-without-request.
2. `PostDetailUiStateTest` coverage of `ReplyUi.isOwn` (own / other / null self id).
3. `PostDetailApiTest` MockEngine coverage of the DELETE → `ReplyDeleteOutcome` mapping.
4. `PostDetailScreenTest` (Robolectric) coverage of:
   - the delete item present on the own reply and absent on another's;
   - dialog confirm → row removed + count decremented;
   - dialog cancel → no request;
   - a failed delete → row restored + failure snackbar;
   - the frame-7 chrome nodes (title "Postingan", the back arrow's "Kembali" description, the "N balasan" subhead, the bare like count, the send action described "Balas");
   - every pre-existing screen scenario migrated to the new selectors.
5. `PostDetailSourceGuardTest` scanning all four post-detail UI files for the no-literal and no-composition-launch guards.
6. `PostDetailFlowIosTest` kept green under `:mobile:app:iosSimulatorArm64Test`. Only its selectors may change to follow the restyle; its scenarios are unchanged.

#### Scenario: The new and migrated tests pass in every gate

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest :mobile:app:iosSimulatorArm64Test`
- **THEN** the post-detail VM, projection, API, screen, source-guard and iOS flow tests are discovered and pass, AND `PostDetailScreenTest` stays in the Release-variant `*ScreenTest` exclude

## MODIFIED Requirements

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
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_title)` AND the back affordance (test tag `postDetailBack`) carries the `contentDescription` `stringResource(Res.string.cta_back)` and no visible "Tutup" text

#### Scenario: Empty city_name is tolerated in the header

- **WHEN** the route payload carries `cityName = ""`
- **THEN** the header renders without the city fragment (no crash, no literal `""`)

#### Scenario: Attached image renders when imageUrl is present, and nothing when absent

- **WHEN** a test composes `PostDetailScreen` once with a route payload carrying a non-null `imageUrl` and once with `imageUrl = null`
- **THEN** the first render contains an async image node below the content AND the second render contains no image element

#### Scenario: No hardcoded UI strings in the post-detail UI sources

- **WHEN** inspecting `PostDetailScreen.kt`, `PostDetailHeader.kt`, `PostDetailReplies.kt` and `PostDetailComposer.kt` under `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/`
- **THEN** every `Text(...)` / placeholder / `contentDescription = ...` call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: The post header renders from nav args without a single-post re-fetch

`PostDetailScreen` SHALL render the post header SOLELY from the `PostDetailRoute` payload. It SHALL NOT issue any single-post by-id GET **to source the initial header render**. A `GET /api/v1/posts/{post_id}` by-id endpoint exists as of the `single-post-read` capability, but the card-tap path deliberately does NOT block the first paint on it, because the nav-arg payload is already in hand.

The screen's outbound requests are:

- the like (`/like`, `/likes/count`) and reply (`/replies`) sub-resources, including the own-reply `DELETE /replies/{reply_id}`;
- the resume-time single-post **freshness read** (`mobile-post-editing`). It refreshes the displayed content and resolves `editedAt` / `isAuthor` / `authorUserId`. It is issued by `PostDetailViewModel`, and a failure degrades silently to the payload.

As of `mobile-timeline-card-redesign` the header SHALL render the author **display identity** from the payload:

- The letter avatar, then `authorDisplayName`, then a meta line. The meta line holds the `authorUsername` handle, followed — as of `post-detail-vm-reply-delete-restyle`, mockup frame 7 — by the separator and the `DistanceRenderer` distance when the payload's `distanceM` is non-null (Nearby origin; a Global / notification origin carries `null` and renders the handle alone).
- The header uses the same avatar derivation, handle treatment and distance rendering as `mobile-post-card`. It is not the shared card composable, but it reuses the card's avatar / identity sub-components so the treatments cannot drift.
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

#### Scenario: Header meta line carries the distance when the payload has one

- **GIVEN** a `PostDetailRoute` with `distanceM = 5000.0` and another with `distanceM = null`
- **WHEN** each detail surface renders
- **THEN** the first header contains the `DistanceRenderer.render(5000.0)` text AND the second contains no distance node

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
   - The like target SHALL announce its liked state via `stateDescription`.

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
- **A filled circular send `IconButton`** (`Res.drawable.ic_send`). This is the "Balas" CTA, labelled via `contentDescription = stringResource(Res.string.cta_reply)`. It SHALL be disabled while content is empty / over-limit / in-flight.

While the draft is non-empty, a live `N/280` counter (`stringResource(Res.string.post_detail_reply_counter)`) SHALL render above the row. It is computed in **Unicode code points** (NOT UTF-16 units) and uses the `error` color when over the limit.

The composer SHALL keep itself above the navigation bar and the IME.

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
- as of `post-detail-vm-reply-delete-restyle`, a boolean `isOwn`, projected as `selfUserId != null && authorId == selfUserId` (fail-closed). It drives the reply block gate (hidden when own) and the delete gate (shown only when own).

The session `selfUserId` itself is NOT projected. No other PII (coordinates, token material) enters projected state.

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
