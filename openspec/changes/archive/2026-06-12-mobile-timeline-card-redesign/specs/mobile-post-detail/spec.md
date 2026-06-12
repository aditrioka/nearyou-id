# mobile-post-detail — Delta Specification

## MODIFIED Requirements

### Requirement: PostDetailRoute is a payload-carrying, serializable, polymorphic-registered route that excludes PII

The change SHALL introduce a `PostDetailRoute` `NavKey` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`) — the first payload-carrying route (existing routes are parameterless `data object`s). It SHALL be `@Serializable` AND registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (so the back stack is saveable on Kotlin/Native per `mobile-app-scaffold` § "Back stack uses serializable NavKey routes"). `PostDetailRoute` SHALL carry exactly the non-PII display fields needed to render the post header: `postId: String`, `content: String`, `cityName: String`, `distanceM: Double?` (Nearby-origin only; `null` from Global), `createdAtIso: String`, `likedByViewer: Boolean`, `replyCount: Int`, and — as of `mobile-timeline-card-redesign` — `authorUsername: String = ""` and `authorDisplayName: String = ""` (the author **display** identity; defaulted so a back stack serialized before this change still decodes — an empty value degrades gracefully per § "The post header renders from nav args without a single-post re-fetch"). `PostDetailRoute` MUST NOT declare a `latitude` or `longitude` property (raw coordinates MUST NOT enter the serialized back stack — the same PII discipline `AgeGateRoute` applies to the `id_token`) and MUST NOT declare the author UUID.

#### Scenario: PostDetailRoute carries display fields but no coordinates

- **WHEN** inspecting the `PostDetailRoute` declaration in `NavKeys.kt`
- **THEN** it declares `postId`, `content`, `cityName`, `distanceM`, `createdAtIso`, `likedByViewer`, `replyCount`, `authorUsername`, `authorDisplayName` AND declares NO `latitude` / `longitude` (or any raw-coordinate) property AND no author-UUID property

#### Scenario: PostDetailRoute survives a serialized back-stack round-trip

- **GIVEN** a `PostDetailRoute` instance encoded + decoded via the `navSavedStateConfiguration` polymorphic serializer (the iOS-safe saved-state path)
- **THEN** the decoded route equals the original (no `SerializationException`), proving it is registered in the polymorphic module

#### Scenario: A payload predating the identity fields still decodes

- **GIVEN** a serialized `PostDetailRoute` payload that lacks the `authorUsername` / `authorDisplayName` properties (produced before `mobile-timeline-card-redesign`)
- **WHEN** it is decoded via the polymorphic serializer
- **THEN** decoding succeeds with `authorUsername = ""` and `authorDisplayName = ""` (the defaults), no `SerializationException`

### Requirement: The post header renders from nav args without a single-post re-fetch

Because no `GET /api/v1/posts/{id}` single-post endpoint exists (only `POST /api/v1/posts` plus the post-scoped like/reply sub-resources), `PostDetailScreen` SHALL render the post header SOLELY from the `PostDetailRoute` payload and SHALL NOT issue any single-post by-id GET. The only outbound requests the screen issues are to the like (`/like`, `/likes/count`) and reply (`/replies`) sub-resources. As of `mobile-timeline-card-redesign` the header SHALL render the author **display identity** from the payload — the letter avatar + `authorDisplayName` + the `authorUsername` handle, using the same avatar derivation and handle treatment as `mobile-post-card` (the header is not the shared card composable, but reuses its avatar/identity sub-components so the treatments cannot drift). When `authorUsername`/`authorDisplayName` are empty (a legacy restored payload), the identity row SHALL be omitted gracefully (no empty "@" handle, no crash). The identity is NOT a tap target (no profile screen exists — issue #196).

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

### Requirement: No author identifier or coordinate is rendered or logged

`PostDetailScreen`, its post header, and its reply cards SHALL render only non-PII display fields (the author **display identity** from the route payload — `authorDisplayName` + the `authorUsername` handle, as of `mobile-timeline-card-redesign` — plus `content`, `cityName`, the `created_at` treatment, the like state/count, `reply_count`). The `author_id` (a UUID) and any raw `latitude`/`longitude` MUST NOT be rendered in any UI node. Tokens, raw coordinates, and response bodies MUST NOT be logged — `HttpClientFactory` SHALL remain at `LogLevel.HEADERS` (this change MUST NOT widen it to `BODY`/`ALL`), and the post-detail client/repository MUST NOT `println`/log coordinates or bodies.

#### Scenario: No author UUID or coordinate appears in the rendered tree while display identity does

- **GIVEN** a route payload + replies whose underlying data includes an `author_id` UUID, with payload `authorDisplayName = "Raka Pratama"`, `authorUsername = "raka.jkt"`
- **WHEN** the detail surface renders
- **THEN** the rendered tree contains NO node whose text is a UUID author identifier AND NO node whose text contains a raw coordinate AND contains the "Raka Pratama" and "@raka.jkt" nodes

#### Scenario: Logging level is unchanged

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/network/HttpClientFactory.kt` after this change
- **THEN** the `Logging` plugin level remains `LogLevel.HEADERS` (NOT `BODY`/`ALL`)
