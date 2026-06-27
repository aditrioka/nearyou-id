## ADDED Requirements

### Requirement: A single shared block-create seam serves all block surfaces

The mobile app SHALL expose ONE block-create implementation — a `BlockSubmitter` under `data/block/` wrapping `POST /api/v1/blocks/{userId}` and mapping the response to a sealed `BlockOutcome` with members `Blocked` (HTTP 204), `RateLimited(retryAfterSeconds)` (HTTP 429), and `NetworkError` (transport failure / other non-success). It SHALL reuse the single shared `HttpClient` (no per-feature client). The profile block path (`ProfileViewModel.onBlockConfirmed`) SHALL be refactored onto this shared seam behavior-preservingly. The post-detail post-header and reply-row block affordances SHALL consume the same seam. There SHALL NOT be a second or duplicated block-create implementation. This mirrors the shipped `data/report/ReportSubmitter` shared-seam pattern; no `user_blocks` schema, endpoint, rate-limit, or backend block-semantics change is introduced.

#### Scenario: One block-create implementation consumed by profile and post-detail

- **WHEN** inspecting the mobile block-create call sites
- **THEN** there is exactly one `BlockSubmitter` (under `data/block/`) and one `BlockOutcome` type, referenced by the profile, post-detail post-header, AND post-detail reply-row surfaces — with no second block-create call to `POST /api/v1/blocks/{userId}` elsewhere

#### Scenario: Profile block behavior is unchanged after the refactor

- **WHEN** running the existing profile block tests after `ProfileViewModel` is refactored onto the shared `BlockSubmitter`
- **THEN** they pass unchanged (same `Blocked` → success + navigate-back, same `RateLimited` → rate-limit message + no nav, same `NetworkError` → action-failed mapping)

#### Scenario: BlockOutcome enumerates exactly the three members

- **WHEN** inspecting the `BlockOutcome` sealed type
- **THEN** it has exactly `Blocked`, `RateLimited(retryAfterSeconds)`, and `NetworkError`, mapped from HTTP 204, 429, and transport/other-failure respectively

### Requirement: The block confirmation dialog presents the canonical copy

A shared `BlockConfirmDialog` (an M3 `AlertDialog` under `ui/components/`, mirroring `ui/components/ReportDialog`) SHALL gate every block action behind an explicit confirmation. It SHALL render the canonical `docs/03-UX-Design.md` §"Block User UX" copy verbatim: the body "Blokir @{username}? Kalian berdua tidak akan saling melihat post, profil, atau bisa memulai percakapan baru." (with `{username}` interpolated via a parameterized string resource), a destructive (error-colored) confirm button "Blokir", and a secondary dismiss button "Batal". All dialog strings SHALL be sourced from Compose Multiplatform Resources (`Res.string.*`) — NO hardcoded UI string literals.

#### Scenario: Dialog renders the canonical confirmation copy

- **WHEN** the block confirmation dialog opens for `@raka.jkt`
- **THEN** the body reads "Blokir @raka.jkt? Kalian berdua tidak akan saling melihat post, profil, atau bisa memulai percakapan baru." AND the confirm button reads "Blokir" (destructive/error-colored) AND the dismiss button reads "Batal"

#### Scenario: Dismissing the dialog issues no block

- **WHEN** the viewer opens the block dialog and taps "Batal"
- **THEN** no `POST /api/v1/blocks/{userId}` is issued AND the surface is unchanged

#### Scenario: No hardcoded block strings

- **WHEN** inspecting `BlockConfirmDialog` and the block menu items in source
- **THEN** every user-facing string is resolved via `Res.string.*` (no string literal in the composable)

### Requirement: Block submission outcome maps to exactly one UI result

Every block submission outcome SHALL map to exactly one UI result, mirroring the profile block treatment: `Blocked` → a success toast "Pengguna telah diblokir" plus the surface-specific navigation effect (defined below); `RateLimited` → a typed rate-limit message ("Terlalu banyak aksi blokir. Coba lagi nanti.") with NO navigation; `NetworkError` → a generic action-failed message with NO navigation. One-shot results (toast / message / navigation) SHALL be modeled as nullable UiState fields cleared via an `onXxxShown()` callback (the §2.2 events-are-state contract), NOT as event streams.

#### Scenario: Successful block shows the success toast

- **WHEN** a block submission returns `Blocked`
- **THEN** the success toast "Pengguna telah diblokir" is surfaced exactly once

#### Scenario: 429 shows the rate-limit message and does not navigate

- **WHEN** a block submission returns `RateLimited`
- **THEN** the rate-limit message is surfaced AND no navigation occurs

#### Scenario: Network error shows the action-failed message and does not navigate

- **WHEN** a block submission returns `NetworkError`
- **THEN** the generic action-failed message is surfaced AND no navigation occurs

### Requirement: Post-context block pops back; reply-context block removes the row

On a successful `Blocked` outcome, the post-header block SHALL pop `PostDetailScreen` off the root back stack (returning to the timeline — the just-blocked post would 404 on any re-read, mirroring `ProfileViewModel`'s navigate-back), while the reply-row block SHALL remove the blocked reply row from the current replies list locally (the reply hides bidirectionally; the open post stays visible — no screen pop). A `RateLimited` or `NetworkError` outcome SHALL perform NO navigation and NO row removal on either surface.

#### Scenario: Post block pops back to the timeline

- **GIVEN** the viewer confirms a block on the post header and the submission returns `Blocked`
- **THEN** the success toast is surfaced AND `PostDetailScreen` is popped off the root back stack

#### Scenario: Reply block removes the blocked reply row

- **GIVEN** the viewer confirms a block on a reply and the submission returns `Blocked`
- **THEN** the success toast is surfaced AND the blocked reply row is removed from the current replies list AND `PostDetailScreen` is NOT popped

#### Scenario: A failed block leaves the surface unchanged

- **GIVEN** the viewer confirms a block and the submission returns `RateLimited` or `NetworkError`
- **THEN** no pop and no row removal occur AND the post/reply remains visible

### Requirement: The block action never renders or logs the author UUID

The author UUID used by a block action (the post's `authorUserId` from the single-post freshness read, or a reply's `author_id` from the reply wire) SHALL be used ONLY as the `POST /api/v1/blocks/{userId}` path param and the client-side self-block gate. It SHALL NOT be rendered in any UI node and SHALL NOT be logged; the `HttpClientFactory` `Logging` level SHALL remain `LogLevel.HEADERS` (not widened to `BODY`/`ALL`).

#### Scenario: The block UUID never appears in the rendered tree

- **GIVEN** a post or reply whose author UUID is `22222222-2222-2222-2222-222222222222`
- **WHEN** the block affordance and confirmation dialog render
- **THEN** no rendered node has text equal to or containing `"22222222-2222-2222-2222-222222222222"` (only the `@username` handle is shown)

#### Scenario: Logging level is unchanged

- **WHEN** inspecting `HttpClientFactory.kt` after this change
- **THEN** the `Logging` plugin level remains `LogLevel.HEADERS`

### Requirement: Timeline-card block entry point is deferred

This capability SHALL NOT add a block affordance to the shared timeline post card (`PostCard`) or modify the `mobile-post-card` spec — the timeline-card block entry point is deferred so this change stays footprint-disjoint from the in-flight `image-attached-posts` change ([#354](https://github.com/aditrioka/nearyou-id/pull/354)), which currently owns `PostCard` (the exact precedent the `mobile-content-report` capability set when it deferred the timeline-card report kebab as [#363](https://github.com/aditrioka/nearyou-id/issues/363)). A GitHub `follow-up` issue SHALL track the deferred timeline-card block kebab as the MODIFY hook for a future change.

#### Scenario: No block affordance is added to the timeline card

- **WHEN** inspecting the shared `PostCard` composable and the `mobile-post-card` spec
- **THEN** neither is modified by this change AND no block affordance or block API call is present on the timeline card

#### Scenario: Follow-up issue tracks the timeline-card deferral

- **WHEN** inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** an issue tracks the deferred timeline-card block kebab

### Requirement: Test coverage for the block-from-content capability

The change SHALL add the following coverage: a `BlockSubmitter` unit test mapping 204/429/transport-failure to `Blocked`/`RateLimited`/`NetworkError`; `PostDetailScreenTest` (Robolectric) assertions that the post-header block affordance is present on a non-authored post and absent on the viewer's own post, that a reply-row block affordance is present on another user's reply and absent on the viewer's own reply, that confirming the dialog issues `POST /api/v1/blocks/{uuid}` against the correct author UUID, that the post block pops back and the reply block removes the row, and that the author UUID never appears in the rendered tree; a backend `SinglePostRoutes` test asserting the response now carries `authorUserId` (additive) with `isAuthor` and all other fields unchanged; and a regression assertion that the existing profile block tests pass unchanged after the shared-seam refactor. Screen tests remain within the existing Release-variant `*ScreenTest` exclude.

#### Scenario: Block coverage exists and is discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the `BlockSubmitter` mapping test and the `PostDetailScreenTest` block-affordance / block-confirm / never-render assertions are present and pass

#### Scenario: Backend additive-field test passes

- **WHEN** running the `single-post-read` route test suite
- **THEN** a test asserts `SinglePostResponse` carries `authorUserId` equal to the post author's UUID AND `isAuthor` plus every pre-existing field is unchanged
