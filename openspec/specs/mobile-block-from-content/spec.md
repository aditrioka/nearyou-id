# mobile-block-from-content Specification

## Purpose
The mobile post-detail block-from-context-menu surface — the third of the three block entry points the safety contract (`docs/02` §"Block User", `docs/03` §"Block User UX": *"Kebab menu (post, reply, profile page): 'Blokir @{username}'"*) calls for, after the profile-page block shipped with `mobile-profile`. It adds the "Blokir @{username}" affordance to the post-detail post-header and reply-row overflow kebabs, backed by a single shared `data/block/BlockSubmitter` seam (the profile block path refactored onto it — one block-create implementation) and a shared `ui/components/BlockConfirmDialog` rendering the canonical confirmation copy. On a confirmed block the post-context pops back to the timeline and the reply-context removes the row locally; rate-limit and network outcomes surface a message without navigating. The author UUID that targets the block (`POST /api/v1/blocks/{userId}`) is sourced from the single-post freshness read (post) or the reply wire (reply) and is never rendered — only the `@username` display identity is shown. The timeline-card block kebab is a deliberately deferred layer (tracked in the deferred requirement below).
## Requirements
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

### Requirement: Test coverage for the block-from-content capability

The change SHALL add the following coverage: a `BlockSubmitter` unit test mapping 204/429/transport-failure to `Blocked`/`RateLimited`/`NetworkError`; `PostDetailScreenTest` (Robolectric) assertions that the post-header block affordance is present on a non-authored post and absent on the viewer's own post, that a reply-row block affordance is present on another user's reply and absent on the viewer's own reply, that confirming the dialog issues `POST /api/v1/blocks/{uuid}` against the correct author UUID, that the post block pops back and the reply block removes the row, and that the author UUID never appears in the rendered tree; a backend `SinglePostRoutes` test asserting the response now carries `authorUserId` (additive) with `isAuthor` and all other fields unchanged; and a regression assertion that the existing profile block tests pass unchanged after the shared-seam refactor. Screen tests remain within the existing Release-variant `*ScreenTest` exclude.

#### Scenario: Block coverage exists and is discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the `BlockSubmitter` mapping test and the `PostDetailScreenTest` block-affordance / block-confirm / never-render assertions are present and pass

#### Scenario: Backend additive-field test passes

- **WHEN** running the `single-post-read` route test suite
- **THEN** a test asserts `SinglePostResponse` carries `authorUserId` equal to the post author's UUID AND `isAuthor` plus every pre-existing field is unchanged

### Requirement: Timeline card exposes a block entry point

The shared timeline post card (`PostCard`, `mobile-post-card`) SHALL expose a block entry point on all three feed surfaces (Nearby / Global / Following): the overflow kebab's menu carries a "Blokir @{username}" item (resource `profile_block_action`), supplied by the feed host ONLY for **non-authored** posts. Authorship SHALL be resolved in the feed ViewModel by comparing the raw timeline DTO's `authorUserId` against the viewer's `SelfUserIdProvider` id (fail-closed while unresolved — the shipped report-kebab gate) — never on the PII-free `PostCardModel`, which stays UUID-free. Selecting the item SHALL open the shared `BlockConfirmDialog` (canonical copy, unchanged); a confirmed block SHALL go through the shared `BlockSubmitter` seam with the raw DTO's `authorUserId` as the path param. The outcome SHALL map onto the canonical block result contract: `Blocked` → the success toast (`profile_block_success_toast`) AND the feed locally removes every currently-loaded post whose `authorUserId` is the blocked author (the timeline's surface-specific effect — mutual invisibility made immediate; paging cursor/anchor untouched, later pages are server-side block-excluded); `RateLimited` → the block rate-limit copy with NO removal; `NetworkError` → the generic action-failed copy with NO removal. Results surface as one-shot snackbar messages held as nullable state cleared via a shown-callback (docs/11 § 2.2). The block-flow state (dialog target + one-shot message) SHALL exist exactly once, in a shared controller instantiated per feed ViewModel (the `TimelineReportController` precedent) — not three per-feed copies — and the dialog target SHALL carry the author UUID only as the un-rendered submit param (the display identity shown is the `@username` handle). This supersedes the prior "Timeline-card block entry point is deferred" posture ([#354](https://github.com/aditrioka/nearyou-id/pull/354) merged and the card kebab shipped with `timeline-card-report-kebab`; issue [#456](https://github.com/aditrioka/nearyou-id/issues/456) is closed by this change).

#### Scenario: Another user's post is blockable from the feed

- **WHEN** a feed renders a post NOT authored by the viewer and the viewer opens the card kebab, picks "Blokir @{username}", and confirms the dialog
- **THEN** the shared `BlockConfirmDialog` is shown with the canonical copy AND exactly one block is submitted through the shared `BlockSubmitter` against the post author's UUID

#### Scenario: The viewer's own post exposes no block entry point

- **WHEN** a feed renders a post whose `authorUserId` equals the viewer's `SelfUserIdProvider` id
- **THEN** that card offers no "Blokir" item AND no block affordance is reachable for it from the timeline

#### Scenario: A confirmed block removes the author's loaded posts and shows the success toast

- **GIVEN** a loaded feed containing three posts by author A and two by author B
- **WHEN** the viewer blocks author A from one of A's cards and the submission returns `Blocked`
- **THEN** the success toast (`profile_block_success_toast`) is surfaced exactly once AND all three of A's posts leave the rendered list AND B's posts remain

#### Scenario: Rate-limited and network-failed blocks leave the feed unchanged

- **WHEN** a timeline-card block submission resolves as `RateLimited` (429), and separately as `NetworkError`
- **THEN** the rate-limit copy renders for the former and the generic action-failed copy for the latter, each as a one-shot snackbar cleared after showing AND no post is removed from the list in either case

#### Scenario: Dismissing the dialog issues no block

- **WHEN** the viewer opens the block dialog from a timeline card and taps "Batal"
- **THEN** no block submission is issued AND the feed is unchanged

#### Scenario: One shared block-flow implementation across the three feeds

- **WHEN** inspecting the mobile source tree
- **THEN** the timeline block-flow state machine (dialog target + one-shot message + submission + removal signal) is defined exactly once (a shared controller under `ui/timeline/`), instantiated by the Nearby, Global, AND Following feed ViewModels, consuming the existing shared `BlockSubmitter`/`BlockConfirmDialog` seam (no second block path)

### Requirement: Test coverage for the timeline-card block entry point

The change SHALL ship: (1) unit coverage for the shared timeline block controller — dialog-target open/dismiss one-shot, confirm → exactly one `BlockSubmitter.submit` with the target UUID, outcome→message mapping (`Blocked`→success + the removal signal fired with the author UUID, `RateLimited`, `NetworkError`→no removal signal), and message clear; (2) Robolectric coverage for the card-level affordance — the "Blokir @{username}" item present with a block action, each kebab item gated on its own action, kebab absent when both actions are null — and for the feed-level entry point (kebab → "Blokir" item → shared dialog → confirmed submission → the author's cards leave the list); (3) the `PostDetailSourceGuardTest` negative guard asserting `PostCard` stays block-free SHALL flip to assert the timeline card DOES expose the block item (superseding the deferral it guarded). New `*ScreenTest`-shaped tests SHALL be added to the Release-variant test-exclude list, keeping `:mobile:app:testDevReleaseUnitTest` green.

#### Scenario: Controller and affordance tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the timeline block controller tests and the card/feed block-affordance tests are discovered AND each documented outcome mapping corresponds to at least one `@Test`

#### Scenario: Release variant stays green

- **WHEN** running `./gradlew :mobile:app:testDevReleaseUnitTest`
- **THEN** the task passes, with any new screen-shaped tests listed in the Release-variant exclude block of `mobile/app/build.gradle.kts`

