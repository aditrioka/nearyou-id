# mobile-post-detail — Delta Specification

## ADDED Requirements

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

## MODIFIED Requirements

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

### Requirement: Repository and ApiClient wired as Koin singletons behind the PostDetailFlow seam

`PostDetailRepository` and its ApiClient(s) (`LikeApiClient`, `ReplyApiClient`, or a combined client) SHALL be registered as singletons in the commonMain Koin `mobileModule`. `PostDetailRepository` SHALL be bound behind a `PostDetailFlow` interface (`single<PostDetailFlow> { get<PostDetailRepository>() }`) so a `FakePostDetailFlow` can drive the screen tests (mirroring `mobile-nearby-timeline`'s `NearbyTimelineFlow` seam). As of `mobile-inline-post-actions`, the like half of the seam is extracted for cross-surface reuse: a `LikeFlow` interface (new target-shape file `mobile/app/src/commonMain/kotlin/id/nearyou/app/data/like/LikeFlow.kt`, docs/11 § 2.1) declares `suspend fun toggleLike(postId: String, currentlyLiked: Boolean): LikeOutcome`; `PostDetailFlow` EXTENDS `LikeFlow` (its `toggleLike` member moves up to the super-interface — same signature, same `LikeOutcome` semantics; `LikeOutcome` itself stays in its existing `id.nearyou.app.post` location, no mechanical file moves); and `mobileModule` ADDITIONALLY binds `single<LikeFlow> { get<PostDetailRepository>() }` — the SAME singleton serving the detail surface and the feeds' inline like. The repository SHALL reuse the existing shared `HttpClient` — it MUST NOT construct a new client and MUST NOT register or send an `X-Session-Id` header (the like/reply endpoints are not session-soft-capped). The post-detail screen's behavior is unchanged by the extraction.

#### Scenario: mobileModule registers the post-detail graph behind the flow interfaces

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/di/MobileModule.kt`
- **THEN** `mobileModule` declares singletons for the post-detail ApiClient(s) and `PostDetailRepository` AND binds `single<PostDetailFlow> { get<PostDetailRepository>() }` AND binds `single<LikeFlow> { get<PostDetailRepository>() }` (the same singleton behind both seams) AND the repository resolves the existing shared `HttpClient` (no new client, no `X-Session-Id` registration)

#### Scenario: PostDetailFlow extends the extracted LikeFlow seam

- **WHEN** inspecting `data/like/LikeFlow.kt` and `PostDetailFlow.kt`
- **THEN** `LikeFlow` declares `suspend fun toggleLike(postId: String, currentlyLiked: Boolean): LikeOutcome` AND `PostDetailFlow` extends `LikeFlow` without re-declaring an incompatible `toggleLike` AND no second like repository/ApiClient registration exists in the module

## REMOVED Requirements

### Requirement: Inline-card like and reply shortcuts are deferred

**Reason**: Un-deferred — `mobile-inline-post-actions` ships the inline card actions (closes GitHub issue [#201](https://github.com/aditrioka/nearyou-id/issues/201)): the inline like and the reply shortcut now live on the Nearby/Global feed cards.
**Migration**: The positive contracts replacing this deferral live in `mobile-post-card` § "Action row renders interactive reply and like affordances per mockup frame 1" (and § "Send-message card action is deferred" for the still-deferred third action), the timelines' inline-like requirements (`mobile-nearby-timeline` / `mobile-global-timeline`), and `mobile-home-tab-host`'s reply-shortcut wiring.
