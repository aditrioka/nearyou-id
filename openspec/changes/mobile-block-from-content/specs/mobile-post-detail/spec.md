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

The mobile app SHALL model the screen state as Compose-free `PostDetailUiState` data class(es) plus pure projection function(s) (mirroring `NearbyTimelineUiState` / `PostCreationUiState`) so the outcome→state mapping and the reply code-point gate are deterministically unit-testable in commonTest without composing the UI. The projection MUST carry no coordinates, no post-author UUID, and no wall-clock / platform dependency (the post-author `authorUserId` from the freshness read is held at the screen level alongside `isAuthor`, NOT in projected state). As of `mobile-block-from-content`, the **reply** UI model MAY carry the reply `author_id` (already on the reply wire) SOLELY to drive the client-side self-block gate (`SelfUserIdProvider` comparison) and as the block-request path param; this `author_id` MUST NOT be rendered in any UI node and MUST NOT be logged (the "No author identifier or coordinate is rendered or logged" requirement is preserved). No other PII (coordinates, post-author UUID, token material) enters projected state.

#### Scenario: Projection maps each outcome to its state deterministically

- **WHEN** the projection is invoked for the like states (liked / not-liked / rate-limited), the replies states (loading / loaded-non-empty / empty / error), and the reply-post states (success / content-empty / content-too-long / rate-limited / network-error)
- **THEN** each call returns the corresponding state deterministically (no wall-clock or platform dependency) AND no projected state carries a coordinate or the post-author UUID

#### Scenario: The reply model carries author_id only for the self-block gate, never rendered

- **GIVEN** a reply with `author_id = "33333333-3333-3333-3333-333333333333"`
- **WHEN** the reply is projected into `PostDetailUiState`
- **THEN** the reply model carries `author_id` (available for the `SelfUserIdProvider` self-block comparison and the block path param) AND no rendered node or log line contains `"33333333-3333-3333-3333-333333333333"`

## ADDED Requirements

### Requirement: Each reply row exposes a block affordance

Each reply row in `PostDetailScreen` SHALL expose a "Blokir @{username}" block affordance in its overflow kebab (alongside the existing "Laporkan" report item), shown ONLY when the reply is NOT authored by the viewer. Authorship SHALL be determined by comparing the reply's `author_id` (already carried on the reply wire) to the session user id from the existing `SelfUserIdProvider`; the `author_id` SHALL NOT be rendered in any UI node (the reply-card PII discipline is preserved) and is used only for this self-block gate and as the block-request path param. Activating it SHALL open the shared block confirmation dialog (`mobile-block-from-content`) targeting the reply author; on confirm it SHALL issue `POST /api/v1/blocks/{replyAuthorId}` via the shared block seam.

#### Scenario: Reply row exposes a block affordance for another user's reply

- **GIVEN** a reply with `author_id = "B"` where `B` is not the session user
- **WHEN** the reply card overflow kebab is inspected
- **THEN** it exposes a "Blokir @{username}" affordance AND activating it opens the block confirmation dialog targeting `B`

#### Scenario: Reply block is hidden on the viewer's own reply

- **GIVEN** a reply whose `author_id` equals the session user id from `SelfUserIdProvider`
- **WHEN** the reply card overflow kebab is inspected
- **THEN** no "Blokir" affordance is present on that reply

#### Scenario: Reply block uses the reply author UUID as the block target, never rendering it

- **GIVEN** a reply with `author_id = "11111111-1111-1111-1111-111111111111"` authored by another user
- **WHEN** the block dialog is opened from that reply and confirmed
- **THEN** the request is `POST /api/v1/blocks/11111111-1111-1111-1111-111111111111` AND no rendered node or log line contains `"11111111-1111-1111-1111-111111111111"`
