## RENAMED Requirements

- FROM: `### Requirement: Block kebab action is deferred`
- TO: `### Requirement: Post header exposes a block affordance for non-authored posts`

## MODIFIED Requirements

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

### Requirement: Pure PostDetailUiState projection (Compose-free, unit-testable, PII-free)

The mobile app SHALL model the screen state as Compose-free `PostDetailUiState` data class(es) plus pure projection function(s) (mirroring `NearbyTimelineUiState` / `PostCreationUiState`) so the outcome→state mapping and the reply code-point gate are deterministically unit-testable in commonTest without composing the UI. The projection MUST carry no coordinates, no post-author UUID, and no wall-clock / platform dependency (the post-author `authorUserId` from the freshness read is held at the screen level alongside `isAuthor`, NOT in projected state). As of `mobile-block-from-content`, the **reply** UI model MAY carry the reply `author_id` (already on the reply wire) SOLELY to drive the client-side self-block gate (`SelfUserIdProvider` comparison) and as the block-request path param; this `author_id` MUST NOT be rendered in any UI node and MUST NOT be logged (the "No author identifier or coordinate is rendered or logged" requirement is preserved). The reply UI model SHALL also carry the reply author's **display identity** (`authorUsername` / `authorDisplayName` from the reply wire — design D7); display identity is renderable, not PII. No other PII (coordinates, post-author UUID, token material) enters projected state.

#### Scenario: Projection maps each outcome to its state deterministically

- **WHEN** the projection is invoked for the like states (liked / not-liked / rate-limited), the replies states (loading / loaded-non-empty / empty / error), and the reply-post states (success / content-empty / content-too-long / rate-limited / network-error)
- **THEN** each call returns the corresponding state deterministically (no wall-clock or platform dependency) AND no projected state carries a coordinate or the post-author UUID

#### Scenario: The reply model carries author_id only for the self-block gate, never rendered

- **GIVEN** a reply with `author_id = "33333333-3333-3333-3333-333333333333"` and `author_display_name = "Sinta Maharani"`
- **WHEN** the reply is projected into `PostDetailUiState`
- **THEN** the reply model carries `author_id` (available for the `SelfUserIdProvider` self-block comparison and the block path param) AND the display identity (renderable) AND no rendered node or log line contains `"33333333-3333-3333-3333-333333333333"`

### Requirement: Replies list mirrors the shipped snake_case wire with loading, empty, and error states

`ReplyApiClient` SHALL issue `GET /api/v1/posts/{post_id}/replies` and parse `@Serializable` DTOs whose wire names match the SHIPPED backend serialization in `backend/ktor/.../engagement/ReplyRoutes.kt` (`ReplyDto` / `ReplyListResponse`) — **snake_case**, NOT the timelines' camelCase. Specifically: `ReplyDto` = `id` (bare String), `@SerialName("post_id") postId`, `@SerialName("author_id") authorId`, `@SerialName("author_username") authorUsername: String? = null` + `@SerialName("author_display_name") authorDisplayName: String? = null` (the author **display identity**, added by `mobile-block-from-content` design D7; nullable-with-default so a body from an older backend still decodes), `content` (bare), `@SerialName("is_auto_hidden") isAutoHidden` (Boolean), `@SerialName("created_at") createdAt`, `@SerialName("updated_at") updatedAt: String?`, `@SerialName("deleted_at") deletedAt: String?`; `ReplyListResponse` = `replies: List<ReplyDto>` (bare), `@SerialName("next_cursor") nextCursor: String? = null`. The `next_cursor` key is snake_case and MUST differ from the timelines' camelCase `nextCursor`. The screen SHALL render reply cards showing the author **display identity row** (the shared `LetterAvatar` + `authorDisplayName`, the same avatar/name treatments as the post header — canonical mockup frame 7 · "Detail postingan + balasan") plus `content` + the `created_at` treatment; when the identity fields are null/blank (an older-backend body) the identity row SHALL be omitted gracefully (no empty row, no crash — the post header's legacy-payload precedent). The reply `author_id` (a UUID) stays NEVER rendered. States: a loading state (`stringResource(Res.string.timeline_loading)`), an empty state (`stringResource(Res.string.post_detail_replies_empty)`), the reply-card list, or an error state (`stringResource(Res.string.signin_error_network)` + a `stringResource(Res.string.cta_retry)` control). `next_cursor` SHALL be parsed + retained AND SHALL drive replies cursor load-more per the § "Replies list wires cursor load-more via PostDetailViewModel" requirement. A returned reply MAY carry `is_auto_hidden = true` ONLY when it is the viewer's OWN reply (the backend's author-bypass `is_auto_hidden = FALSE OR author_id = :viewer` lives in the `PostReplyRepository.listByPost` query — impl in `core/data/.../repository/PostReplyRepository.kt`, surfaced via `engagement/ReplyService.list` — so no other reply with the flag set is ever returned); in v1 the `is_auto_hidden` flag SHALL be **parsed but NOT surfaced** (the viewer's own auto-hidden reply renders identically to a live reply, matching the backend's author-bypass intent) — no "under review" badge or dimming is added in this change. Similarly `deleted_at` is faithfully parsed (DTO mirrors the wire) but is effectively dead on this list path (the backend excludes `deleted_at IS NOT NULL` rows).

#### Scenario: Replies parse against the shipped snake_case wire

- **GIVEN** a `MockEngine` returning `200` with `{ "replies": [ { "id": "...", "post_id": "...", "author_id": "...", "author_username": "sinta.mhr", "author_display_name": "Sinta Maharani", "content": "hi", "is_auto_hidden": false, "created_at": "2026-06-06T00:00:00Z", "updated_at": null, "deleted_at": null } ], "next_cursor": "tok" }`
- **WHEN** the response is parsed
- **THEN** parsing succeeds AND the reply exposes `content = "hi"`, `authorUsername = "sinta.mhr"`, `authorDisplayName = "Sinta Maharani"` AND `nextCursor = "tok"`

#### Scenario: A body without the identity fields still decodes — older-backend guard

- **GIVEN** a `MockEngine` returning a reply object WITHOUT `author_username` / `author_display_name` keys (an older backend)
- **WHEN** the response is parsed
- **THEN** parsing succeeds with null identity fields AND the reply card renders content + timestamp with NO identity row (graceful omission, no crash)

#### Scenario: camelCase next_cursor does NOT populate — negative guard against the timeline assumption

- **GIVEN** a `MockEngine` returning `{ "replies": [], "nextCursor": "tok" }` (the timelines' camelCase key, NOT the shipped reply wire)
- **THEN** `ReplyListResponse.nextCursor` is `null` (the camelCase key does not bind under the `@SerialName("next_cursor")` mapping) — a fixture MUST assert this so the casing regression cannot slip in

#### Scenario: Empty replies show the empty-state copy

- **WHEN** the replies outcome is `Loaded` with an empty list
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_replies_empty)`

#### Scenario: Reply card renders the display identity but never the author UUID

- **GIVEN** a reply with `author_id = "11111111-1111-1111-1111-111111111111"`, `author_username = "sinta.mhr"`, `author_display_name = "Sinta Maharani"`
- **WHEN** the reply card renders
- **THEN** the rendered tree contains a node whose text is `"Sinta Maharani"` (the identity row, mockup frame 7) AND NO node whose text contains `"11111111-1111-1111-1111-111111111111"`

#### Scenario: Viewer's own auto-hidden reply is parsed but rendered normally (v1)

- **GIVEN** a reply returned with `is_auto_hidden = true` (the only reachable case: it is the viewer's own reply, per the backend author-bypass)
- **WHEN** the reply card renders
- **THEN** parsing succeeds AND the card renders identically to a live reply (no "under review" badge, no dimming) — the flag is parsed but not surfaced in v1

## ADDED Requirements

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
