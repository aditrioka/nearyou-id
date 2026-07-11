# mobile-post-detail — delta for post-detail-tap-to-profile

## MODIFIED Requirements

### Requirement: The post header renders from nav args without a single-post re-fetch

`PostDetailScreen` SHALL render the post header SOLELY from the `PostDetailRoute` payload and SHALL NOT issue any single-post by-id GET (a `GET /api/v1/posts/{post_id}` by-id endpoint now exists as of the `single-post-read` capability, but the card-tap path deliberately does NOT use it — the nav-arg payload is already in hand; that endpoint serves the notification-deep-link path, where there is no feed card to source the header, a separate future change). The only outbound requests the screen issues are to the like (`/like`, `/likes/count`) and reply (`/replies`) sub-resources. As of `mobile-timeline-card-redesign` the header SHALL render the author **display identity** from the payload — the letter avatar + `authorDisplayName` + the `authorUsername` handle, using the same avatar derivation and handle treatment as `mobile-post-card` (the header is not the shared card composable, but reuses its avatar/identity sub-components so the treatments cannot drift). When `authorUsername`/`authorDisplayName` are empty (a legacy restored payload), the identity row SHALL be omitted gracefully (no empty "@" handle, no crash). As of `post-detail-tap-to-profile`, the identity row IS a tap target when the single-post freshness read has resolved an `authorUserId` (per § "Post header identity row opens the author profile"); it renders non-tappable while/if that read has not resolved one.

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

## ADDED Requirements

### Requirement: Post header identity row opens the author profile

The post-header author-identity row (the letter avatar + display name + @handle) SHALL be a tap target that emits navigation to the author's profile via a hoisted `onOpenProfile(userId: String)` lambda — wired by the host (`AppEntryProvider`) to push `ProfileRoute(authorUserId)` onto the ROOT back stack, the same profile-entry mechanism as the feed-card identity tap (docs/03 profile-entry convention; the screen stays navigation-free per § "PostDetailScreen is reached via the root back stack and is navigation-free"). The `authorUserId` SHALL be sourced from the single-post freshness read (`single-post-read` `SinglePostResponse.authorUserId`) — NOT from the `PostDetailRoute` payload, which continues to carry no author UUID (the serialized-back-stack PII discipline is preserved). When the freshness read has not resolved an `authorUserId` (in-flight, failed/`Unavailable`, or an older backend), the identity row SHALL NOT be tappable (graceful absence — the same dependence as the Edit and Block affordances). The identity tap SHALL apply on own AND non-authored posts alike (the profile surface itself renders the self/other distinction). The `authorUserId` SHALL NOT be rendered or logged (the "No author identifier or coordinate is rendered or logged" requirement is preserved); it is used solely as the navigation argument. The tap target SHALL carry a stable test tag so the affordance is assertable.

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
