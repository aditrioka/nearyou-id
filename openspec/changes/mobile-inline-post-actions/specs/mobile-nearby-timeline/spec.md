# mobile-nearby-timeline — Delta Specification

## ADDED Requirements

### Requirement: Inline like on Nearby cards is optimistic, status-driven, and reuses the shipped like seam

Activating a Nearby card's like affordance SHALL flip that post's `likedByViewer` **optimistically** inside the retained `Loaded` outcome (the list stays mounted; only the tapped post's card changes — no full-list teardown) and invoke the extracted `LikeFlow` seam (`suspend fun toggleLike(postId: String, currentlyLiked: Boolean): LikeOutcome`), which Koin binds to the SAME `PostDetailRepository` singleton that powers `PostDetailScreen` (per `mobile-post-detail` § "Repository and ApiClient wired as Koin singletons behind the PostDetailFlow seam"). The feed MUST NOT introduce a second like ApiClient/repository and MUST NOT duplicate the status→`LikeOutcome` mapping.

The toggle lifecycle SHALL be implemented in ONE shared, Compose-free commonMain inline-like controller (target-shape `ui/timeline/` package per docs/11 § 2.1) consumed by BOTH `NearbyTimelineViewModel` and `GlobalTimelineViewModel` — not per-feed copies. The controller SHALL keep a **per-post in-flight guard**: while a toggle for post X is in flight, further like activations on X are ignored (no concurrent duplicate `POST`/`DELETE` for the same post). Outcome handling per `LikeOutcome` member:

- `Liked` / `Unliked` → the optimistic state stands.
- `RateLimited(retryAfterSeconds)` → revert the flip AND set the one-shot cap-dialog state (§ "A rate-limited inline like opens the Free like-cap dialog").
- `PostGone` → revert the flip AND trigger the ViewModel's existing `reload()` (the post was deleted or became block-/shadow-hidden — the refreshed first page drops it; no error copy is shown).
- `NetworkError` → revert the flip with NO error surface in v1 — a deliberate, spec-recorded deferral (no transient-error substrate exists in the app), not an accidental omission.

The cap-dialog one-shot signal SHALL be modeled as nullable state cleared via an `onLikeCapDialogDismissed()`-style callback (docs/11 § 2.2 one-shot-events-are-state) — NOT a `Channel`/`SharedFlow` event bus. `CancellationException` handling stays per the seam's existing contract (rethrown, never mapped).

#### Scenario: Like tap optimistically flips and a confirming outcome keeps it

- **GIVEN** the Nearby feed in the `Content` state with a post whose `likedByViewer = false` AND a fake `LikeFlow` returning `LikeOutcome.Liked`
- **WHEN** the post's like affordance is activated
- **THEN** the card reflects the liked treatment immediately (before the outcome resolves) AND `toggleLike` was invoked exactly once with (that post's id, `currentlyLiked = false`) AND the liked state stands after the outcome

#### Scenario: RateLimited reverts the flip and raises the one-shot cap state

- **GIVEN** a fake `LikeFlow` returning `LikeOutcome.RateLimited(retryAfterSeconds = 51540)`
- **WHEN** the like affordance of a not-liked post is activated
- **THEN** the optimistic flip is reverted to not-liked AND the cap-dialog state carries `51540` AND invoking the dismiss callback clears it to null (the signal is nullable state with an explicit clear — no `Channel`/`SharedFlow` event bus in the implementation)

#### Scenario: PostGone reverts and self-heals via reload

- **GIVEN** a fake `LikeFlow` returning `LikeOutcome.PostGone` AND a counting `FakeNearbyTimelineFlow`
- **WHEN** a like is activated
- **THEN** the flip is reverted AND the ViewModel's reload path re-invokes `loadFirstPage()` (fetch count increases) AND no error copy is rendered

#### Scenario: NetworkError reverts silently — the declared v1 posture

- **GIVEN** a fake `LikeFlow` returning `LikeOutcome.NetworkError`
- **WHEN** a like is activated
- **THEN** the flip is reverted AND no error node, dialog, or banner is added to the tree (the deliberate v1 deferral until a transient-error substrate exists)

#### Scenario: In-flight re-taps are ignored

- **GIVEN** a fake `LikeFlow` whose `toggleLike` suspends until released
- **WHEN** the same post's like affordance is activated twice while the first call is in flight
- **THEN** `toggleLike` was invoked exactly once (the second activation was ignored by the per-post in-flight guard)

#### Scenario: One shared controller serves both feeds through the LikeFlow singleton

- **WHEN** inspecting the inline-like implementation and the Koin graph
- **THEN** ONE shared commonMain controller (in `ui/timeline/`) implements the optimistic/revert/in-flight/cap lifecycle AND `NearbyTimelineViewModel` and `GlobalTimelineViewModel` both delegate to it (no per-feed duplicate of the lifecycle) AND the like call resolves the `LikeFlow` Koin binding backed by the existing `PostDetailRepository` singleton (no second like client/repository is registered)

### Requirement: A rate-limited inline like opens the Free like-cap dialog

While the inline-like cap state is set (a like returned `RateLimited`), the Nearby surface SHALL render the shared `mobile-cap-upsell-dialog` component with the like body copy — `stringResource(Res.string.post_detail_likes_cap_upsell)` (the verbatim `docs/03-UX-Design.md:187` modal body) formatted with the live countdown derived from the carried `retryAfterSeconds` (countdown semantics per `mobile-cap-upsell-dialog` § "The countdown derives from Retry-After and ticks to the reset"). Dismissing the dialog (the "Tutup" button, the scrim, or back) SHALL clear the one-shot state; the dialog SHALL NOT re-show until a new `RateLimited` like sets the state again.

#### Scenario: A 429 like shows the dialog with the verbatim body copy

- **GIVEN** the cap-dialog state is set with a `retryAfterSeconds` value
- **WHEN** the Nearby surface renders
- **THEN** the cap-upsell dialog is visible AND it contains a node whose text matches `stringResource(Res.string.post_detail_likes_cap_upsell)` formatted with the countdown string for that `retryAfterSeconds`

#### Scenario: Dismissing clears the one-shot state and the dialog does not re-show

- **GIVEN** the dialog is visible
- **WHEN** the "Tutup" control is activated
- **THEN** the dialog is gone AND the cap state is null AND recomposing the surface does not re-show the dialog (it returns only when a new `RateLimited` outcome sets the state)

## MODIFIED Requirements

### Requirement: Nearby post card opens post detail via a hoisted onOpenPost lambda

The Nearby post card (the shared `mobile-post-card` composable) SHALL be tappable: it SHALL invoke a hoisted `onOpenPost(...)` lambda carrying the card's **non-PII display fields** (`postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`) — and explicitly NOT `latitude`/`longitude` and NOT the author UUID. The lambda is a host-level callback (wired by `mobile-home-tab-host` to push `PostDetailRoute` onto the root back stack), NOT a back-stack reference, so `NearbyTimelineScreen` SHALL remain navigation-free exactly as the existing hoisted `onSeeGlobal` callback already permits. As of `mobile-inline-post-actions` the card's action row is wired on this surface: the like affordance routes to the inline-like path (§ "Inline like on Nearby cards is optimistic, status-driven, and reuses the shipped like seam") and the reply affordance invokes a hoisted `onOpenPostReply(...)` lambda carrying the SAME non-PII display fields (wired by `mobile-home-tab-host` to push `PostDetailRoute` with `focusReplyComposer = true`); the whole-card `onOpenPost` keeps pushing with the default `focusReplyComposer = false`. The author identity remains NOT a separate tap target (per `mobile-post-card` § "Whole-card tap opens the detail and identity is not separately tappable").

#### Scenario: Tapping a Nearby card invokes onOpenPost with display fields and no coordinates

- **GIVEN** the Nearby feed composed with a loaded post (`content`, `cityName`, `distanceM`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName`) and a recording `onOpenPost` callback
- **WHEN** the post card is tapped (outside the action row)
- **THEN** `onOpenPost` fires exactly once carrying the card's `postId`/`content`/`cityName`/`distanceM`/`createdAtIso`/`likedByViewer`/`replyCount`/`authorUsername`/`authorDisplayName` AND the payload contains no `latitude`/`longitude` and no author UUID

#### Scenario: Tapping the reply affordance invokes the reply-shortcut callback, not the whole-card open

- **GIVEN** the Nearby feed composed with a loaded post and recording `onOpenPost` + `onOpenPostReply` callbacks
- **WHEN** the card's reply affordance is tapped
- **THEN** `onOpenPostReply` fires exactly once carrying the same non-PII display fields AND `onOpenPost` does NOT fire

#### Scenario: NearbyTimelineScreen remains navigation-free

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`
- **THEN** the card-tap and reply-shortcut are delivered via the hoisted `onOpenPost` / `onOpenPostReply` lambdas only; the screen holds no back-stack reference and performs no back-stack push of its own

### Requirement: No author identifier or coordinate is rendered or logged

`NearbyTimelineScreen` and its cards SHALL render only display fields returned by the API: the author **display identity** (`authorDisplayName`, the `authorUsername` handle — rendered via the shared `mobile-post-card` component as of `mobile-timeline-card-redesign`, per the canonical mockup frames 1/19 (docs/11 § 2.8); `docs/02-Product.md:176` specifies the username treatment on the Global card and the shared card keeps Nearby/Global identical), `content`, `city_name`, the `DistanceRenderer` string, the `created_at` treatment, and the `liked_by_viewer` + `reply_count` engagement state (interactive as of `mobile-inline-post-actions` — the like affordance and reply shortcut per § "Inline like on Nearby cards is optimistic, status-driven, and reuses the shipped like seam"; previously read-only). The `author_user_id` (a UUID) MUST NOT be rendered in any UI node, and the raw `latitude`/`longitude` MUST NOT be rendered. Tokens, raw coordinates, and response bodies MUST NOT be logged (the shipped `HttpClientFactory` `LogLevel.HEADERS` + `Authorization` sanitization already exclude bodies; this capability MUST NOT widen logging — the inline-like path inherits the same discipline: no post field, coordinate, or token is logged on any like outcome).

#### Scenario: author_user_id and raw coordinates are not in the rendered tree while display identity is

- **GIVEN** a loaded post with `author_user_id = "11111111-1111-1111-1111-111111111111"`, `authorUsername = "raka.jkt"`, `authorDisplayName = "Raka Pratama"`, `latitude = -6.21`, `longitude = 106.85`
- **WHEN** the card is rendered
- **THEN** the rendered tree contains NO node whose text contains the substring `"11111111-1111-1111-1111-111111111111"` AND NO node whose text contains `"-6.21"` or `"106.85"` AND contains the "Raka Pratama" display-name node and the "@raka.jkt" handle node (only the `DistanceRenderer` string + `city_name` represent location)
