## MODIFIED Requirements

### Requirement: PostDetailScreen renders the post-detail surface

The mobile app SHALL ship a composable `PostDetailScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostDetailScreen.kt`), mapped from the `PostDetailRoute` `NavKey` by the `appEntryProvider`, that renders the detail surface for a single post. The screen SHALL display: (a) the post header — the post `content` plus a "Diposting dari {city_name}, {relative_time}" line via `stringResource(Res.string.post_detail_posted_from)` (formatted with `cityName` + the same `created_at` treatment the feed cards use — the existing `postDateLabel` ISO-date-portion helper, i.e. `createdAt.substringBefore('T')`; true relative formatting stays deferred to the `mobile-timeline-relative-timestamp` follow-up), per `docs/03-UX-Design.md:14` / `docs/02-Product.md:129`, reusing the existing feed card visual where practical; (b) the **attached image** below the content when the route payload carries a non-null `imageUrl` — rendered via the async image loader (Coil 3) with an aspect-ratio placeholder and graceful failure (no error chrome), per the docs/02 § 6 delivery rules; when `imageUrl` is null no image element is rendered; (c) a like control (per the § "Like toggle is optimistic and status-driven" requirement); (d) a replies list (per the § "Replies list mirrors the shipped snake_case wire" requirement); (e) a reply composer (per the § "Reply composer posts with a 280-code-point guard" requirement); (f) the loading / empty / error / rate-limit states, all copy via `stringResource`. No hardcoded UI string literals SHALL appear in the screen source. The screen SHALL render under `NearYouTheme` (light/dark). When `cityName` is the backend's empty-string convention (`""`), the header SHALL render without the city fragment (no crash, no literal `""`).

#### Scenario: Initial render shows the post content and posted-from header

- **WHEN** a test composes `PostDetailScreen` under `NearYouTheme` with a `FakePostDetailFlow` and a route payload carrying `content = "halo"`, `cityName = "Jakarta Selatan"`
- **THEN** the rendered tree contains a node whose text is `"halo"` AND a node whose text matches `stringResource(Res.string.post_detail_posted_from)` formatted with `"Jakarta Selatan"`

#### Scenario: Empty city_name is tolerated in the header

- **WHEN** the route payload carries `cityName = ""`
- **THEN** the header renders without the city fragment (no crash, no literal `""`)

#### Scenario: Attached image renders when imageUrl is present, and nothing when absent

- **WHEN** a test composes `PostDetailScreen` once with a route payload carrying a non-null `imageUrl` and once with `imageUrl = null`
- **THEN** the first render contains an async image node below the content AND the second render contains no image element

#### Scenario: No hardcoded UI strings in PostDetailScreen source

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/PostDetailScreen.kt`
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
