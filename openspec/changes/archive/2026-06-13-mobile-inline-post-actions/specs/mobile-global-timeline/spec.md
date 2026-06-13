# mobile-global-timeline — Delta Specification

## ADDED Requirements

### Requirement: Inline like on Global cards reuses the shared controller and like seam

Activating a Global card's like affordance SHALL run the SAME inline-like lifecycle as Nearby — the shared, Compose-free commonMain inline-like controller (target-shape `ui/timeline/`, per `mobile-nearby-timeline` § "Inline like on Nearby cards is optimistic, status-driven, and reuses the shipped like seam") driving the `LikeFlow` Koin singleton (the existing `PostDetailRepository`). `GlobalTimelineViewModel` SHALL delegate to that controller; this surface MUST NOT introduce its own copy of the optimistic/revert/in-flight/cap lifecycle, a second like ApiClient/repository, or a duplicate status→`LikeOutcome` mapping. Behavior on this surface is identical: optimistic flip of the tapped post's `likedByViewer` inside the retained `Loaded` outcome; per-post in-flight guard (re-taps ignored while in flight); `Liked`/`Unliked` → state stands; `RateLimited(retryAfterSeconds)` → revert + set the one-shot cap-dialog state (nullable state cleared via an `onLikeCapDialogDismissed()`-style callback per docs/11 § 2.2 — no `Channel`/`SharedFlow`); `PostGone` → revert + trigger the existing `reload()`; `NetworkError` → revert with NO error surface in v1 (the same spec-recorded deferral as Nearby).

#### Scenario: Global like tap optimistically flips through the shared seam

- **GIVEN** the Global feed in the `Content` state with a post whose `likedByViewer = false` AND a fake `LikeFlow` returning `LikeOutcome.Liked`
- **WHEN** the post's like affordance is activated
- **THEN** the card reflects the liked treatment immediately AND `toggleLike` was invoked exactly once with (that post's id, `currentlyLiked = false`)

#### Scenario: RateLimited on Global reverts and raises the same one-shot cap state

- **GIVEN** a fake `LikeFlow` returning `LikeOutcome.RateLimited(retryAfterSeconds = 1140)`
- **WHEN** a not-liked Global post's like affordance is activated
- **THEN** the flip is reverted AND the Global surface's cap-dialog state carries `1140` AND the dismiss callback clears it to null

#### Scenario: PostGone and NetworkError mirror the Nearby handling

- **WHEN** the toggle outcome is `PostGone`, and separately `NetworkError`, on a Global post
- **THEN** the `PostGone` case reverts the flip AND re-invokes the Global `loadFirstPage()` (reload), AND the `NetworkError` case reverts the flip with no error node, dialog, or banner added (the declared v1 posture)

#### Scenario: Global delegates to the shared controller — no per-feed duplicate

- **WHEN** inspecting `GlobalTimelineViewModel` and the inline-like controller
- **THEN** `GlobalTimelineViewModel` delegates the like lifecycle to the SAME shared controller class `NearbyTimelineViewModel` uses AND no Global-specific copy of the optimistic/revert/in-flight/cap logic and no second like client/repository exists

### Requirement: A rate-limited inline like opens the Free like-cap dialog on the Global surface

While the Global inline-like cap state is set, the Global surface SHALL render the shared `mobile-cap-upsell-dialog` component with the like body copy — `stringResource(Res.string.post_detail_likes_cap_upsell)` (the verbatim `docs/03-UX-Design.md:187` modal body) formatted with the live countdown derived from the carried `retryAfterSeconds`. Dismissing (the "Tutup" button, the scrim, or back) SHALL clear the one-shot state; the dialog SHALL NOT re-show until a new `RateLimited` like sets it again.

#### Scenario: A 429 like on Global shows the dialog with the verbatim body copy

- **GIVEN** the Global cap-dialog state is set with a `retryAfterSeconds` value
- **WHEN** the Global surface renders
- **THEN** the cap-upsell dialog is visible AND contains a node whose text matches `stringResource(Res.string.post_detail_likes_cap_upsell)` formatted with the countdown string

#### Scenario: Dismiss clears the Global one-shot state

- **WHEN** the "Tutup" control is activated
- **THEN** the dialog is gone AND the Global cap state is null AND recomposition does not re-show it

## MODIFIED Requirements

### Requirement: GlobalTimelineScreen renders the Global feed surface

The mobile app SHALL ship a composable `GlobalTimelineScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`) that renders the authenticated Global feed. The screen is navigation-free (it holds no back-stack reference; it is embedded by the tab host as the Global pager page). The screen SHALL be **inset-free**: it MUST NOT declare its own `Scaffold` or `TopAppBar` (the app section shell owns the single inset-owning `Scaffold` per `mobile-design-system` § "The app shell owns a single Scaffold and window insets"). The screen SHALL display: (a) a scrollable list of post cards — the shared `mobile-post-card` composable, whose action row is interactive as of `mobile-inline-post-actions`; field discipline per the § "Post card renders only API-returned display fields, no distance, no PII" requirement — wrapped in a pull-to-refresh container that **fills the available space** between the tab row and the bottom navigation; (b) the loading / empty / error / rate-limit states per the § "Screen state mapping" requirement. The screen SHALL NOT render a redundant in-screen header duplicating the selected section/tab (the `timeline_global_title` "*Seluruh Indonesia*" `TopAppBar` title is removed — the Global tab label already identifies the surface). No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark).

#### Scenario: Screen renders inset-free with no redundant header

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** the screen declares no `Scaffold` and no `TopAppBar` AND renders no node whose text matches `stringResource(Res.string.timeline_global_title)` (the redundant "Seluruh Indonesia" header is removed)

#### Scenario: The post list fills the available space

- **GIVEN** `GlobalTimelineScreen` composed under `NearYouTheme` with a fake emitting a loaded list, inside the shell's padded body
- **THEN** the pull-to-refresh list occupies the full height between the tab row and the bottom navigation (the list is `fillMaxSize` under the shell-provided padding, with no extra header band or unfilled gap)

#### Scenario: No hardcoded UI strings in GlobalTimelineScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** every `Text(...)` / `contentDescription = ...` / similar UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear in such call sites

### Requirement: Global post card opens post detail via a hoisted onOpenPost lambda

The Global post card (the shared `mobile-post-card` composable as of `mobile-timeline-card-redesign`) SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`, and `distanceM = null` since Global has no spatial filter) — and explicitly NOT `latitude`/`longitude` and NOT the author UUID. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `GlobalTimelineScreen` SHALL remain navigation-free. As of `mobile-inline-post-actions` the card's action row is wired on this surface: the like affordance routes to the shared inline-like path (§ "Inline like on Global cards reuses the shared controller and like seam") and the reply affordance invokes a hoisted `onOpenPostReply(...)` lambda carrying the SAME non-PII display fields with `distanceM = null` (wired by `mobile-home-tab-host` to push `PostDetailRoute` with `focusReplyComposer = true`); the whole-card `onOpenPost` keeps pushing with the default `focusReplyComposer = false`. NO distance is rendered or passed (Global has no distance), consistent with `mobile-global-timeline` § "Post card renders only API-returned display fields, no distance, no PII"; the author identity remains NOT a separate tap target (per `mobile-post-card` § "Whole-card tap opens the detail and identity is not separately tappable").

#### Scenario: Tapping a Global card invokes onOpenPost with no distance and no coordinates

- **GIVEN** the Global feed composed with a loaded post and a recording `onOpenPost` callback
- **WHEN** the post card is tapped (outside the action row)
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`createdAtIso`/`likedByViewer`/`replyCount`/`authorUsername`/`authorDisplayName` with `distanceM = null` AND the payload contains no `latitude`/`longitude` and no author UUID

#### Scenario: Tapping the reply affordance on a Global card invokes the reply-shortcut callback

- **GIVEN** the Global feed composed with a loaded post and recording `onOpenPost` + `onOpenPostReply` callbacks
- **WHEN** the card's reply affordance is tapped
- **THEN** `onOpenPostReply` fires exactly once carrying the same non-PII display fields with `distanceM = null` AND `onOpenPost` does NOT fire

#### Scenario: GlobalTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/GlobalTimelineScreen.kt`
- **THEN** the card-tap and reply-shortcut are delivered via the hoisted `onOpenPost` / `onOpenPostReply` lambdas only; the screen holds no back-stack reference and performs no back-stack push of its own

### Requirement: Post card renders only API-returned display fields, no distance, no PII

`GlobalTimelineScreen` and its cards (the shared `mobile-post-card` composable as of `mobile-timeline-card-redesign`) SHALL render only display fields returned by the API: the author **display identity** (`authorDisplayName`, the `authorUsername` handle — per `docs/02-Product.md` § Global Timeline, the city name shows under the author), `content`, `city_name`, the `created_at` value, and the `liked_by_viewer` + `reply_count` engagement state (interactive as of `mobile-inline-post-actions` — the like affordance and reply shortcut per § "Inline like on Global cards reuses the shared controller and like seam"; previously read-only). No distance is rendered (Global has no spatial filter — the shared card receives `distanceM = null` on this surface). The `author_user_id` (a UUID) MUST NOT be rendered in any UI node, and the raw `latitude`/`longitude` MUST NOT be rendered. Tokens, raw coordinates, and response bodies MUST NOT be logged (the shipped `HttpClientFactory` `LogLevel.HEADERS` + `Authorization` sanitization already exclude bodies; this capability MUST NOT widen logging — the inline-like path inherits the same discipline: no post field, coordinate, or token is logged on any like outcome). An empty `city_name = ""` (the backend's never-null empty-string convention) SHALL render without the city label (no crash, no literal `""`).

#### Scenario: author_user_id and raw coordinates are not in the rendered tree while display identity is

- **GIVEN** a loaded post with `author_user_id = "11111111-1111-1111-1111-111111111111"`, `authorUsername = "dewi.kuliner"`, `authorDisplayName = "Dewi Lestari"`, `latitude = -6.21`, `longitude = 106.85`
- **WHEN** the card is rendered
- **THEN** the rendered tree contains NO node whose text contains `"11111111-1111-1111-1111-111111111111"` AND NO node whose text contains `"-6.21"` or `"106.85"` AND NO distance string AND contains the "Dewi Lestari" display-name node and the "@dewi.kuliner" handle node

#### Scenario: Empty city_name tolerated

- **WHEN** a post has `city_name = ""`
- **THEN** parsing succeeds AND the card renders without the city label (no crash, no literal `""`)
