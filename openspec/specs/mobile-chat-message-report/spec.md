# mobile-chat-message-report Specification

## Purpose
The `:mobile:app` chat-thread surface for reporting an individual chat message — closing the **last unreportable user-generated-content surface** (posts, replies, and users were already reportable via `mobile-content-report` / `mobile-profile`). Long-pressing a *received* (non-own, non-redacted) message bubble opens a "Laporkan" menu → the shared `ui/components/ReportDialog` → `POST /api/v1/reports` with `target_type = "chat_message"`, `target_id = <message id>`. It **reuses the shared report seam** (`ReportReasonCategory` / `ReportOutcome` / `ReportTargetType` / `ReportApiClient` / `ReportSubmitter` + `ReportDialog`) introduced by `mobile-content-report`, adding only `CHAT_MESSAGE` to `ReportTargetType` — so there is exactly one report-submission implementation. Mobile-only: the backend `reports` endpoint already validates `chat_message`, and admin enforcement is the shipped chat-redaction path (no schema or migration). The report gate is the PII-safe `isReportable = !isOwn && !isRedacted` projection on `ChatMessageRow` (which drops the raw `senderId`); outcomes follow the post-detail anti-enumeration posture (`Submitted` and `Duplicate` fold into one success). Resolves [#364](https://github.com/aditrioka/nearyou-id/issues/364).
## Requirements
### Requirement: The chat thread exposes a report affordance on the other party's messages

The `:mobile:app` 1:1 chat thread (`screens/chat/ChatThreadScreen.kt`, `MessageBubble`) SHALL expose a report ("Laporkan") affordance reached by **long-pressing a received message bubble** — long-press being the chat-surface idiom (the bubble has no kebab; `docs/06` § Report System names chat-message reporting as canonical, `docs/03` § Report UX lists the kebab idiom only for post/reply/profile). The affordance SHALL be presented ONLY for the other party's, non-redacted messages, computed on the rendered `ChatMessageRow` as **`isReportable = !isOwn && !isRedacted`**. The chat row `ChatMessageRow` deliberately carries `isOwn` / `isRedacted` and **DROPS the raw `senderId`** for PII discipline; `isOwn` is derived at projection time from `senderId == viewerId` against the `ChatMessageDto`, and `senderId` itself SHALL NOT be re-threaded onto the row, the affordance, the menu, or any log (the `!isOwn` gate is the row-level realization of the conceptual "other party's message" / post `!isAuthor` gate). Activating it SHALL open the shared `ui/components/ReportDialog` (the six user-facing reason categories + optional ≤200-code-point note from `mobile-content-report`). All new UI strings (the menu label, content descriptions) SHALL come from `:shared:resources` Compose Multiplatform Resources — no hardcoded UI string literal SHALL appear in the chat report path.

#### Scenario: Long-pressing a received message reveals the report affordance

- **GIVEN** a chat thread rendering a row where `isReportable` is true (`!isOwn && !isRedacted`)
- **WHEN** the message bubble is long-pressed
- **THEN** a "Laporkan" affordance (sourced via `stringResource`) is shown

#### Scenario: One's own (sent) message exposes no report affordance

- **GIVEN** a chat thread rendering a row where `isOwn` is true (the viewer's own / sent message, including an optimistic send)
- **WHEN** the message bubble is long-pressed
- **THEN** no "Laporkan" affordance is shown for that row (`isReportable` is false)

#### Scenario: A redacted message exposes no report affordance

- **GIVEN** a chat thread rendering a row where `isRedacted` is true (already moderated)
- **WHEN** the message bubble is long-pressed
- **THEN** no "Laporkan" affordance is shown for that row (`isReportable` is false)

#### Scenario: Activating the affordance opens the shared report dialog

- **WHEN** "Laporkan" is activated on a reportable message
- **THEN** the shared `ReportDialog` is shown offering exactly the six user-facing reason categories and the optional note field (the same dialog consumed by post-detail and profile)

#### Scenario: No hardcoded UI strings in the chat report path

- **WHEN** the chat report affordance + menu sources are scanned for user-visible text
- **THEN** every label / content description resolves through `Res.string.*` (no hardcoded literal)

### Requirement: A chat-message report submits via the shared seam with target_type chat_message

A submitted chat-message report SHALL `POST /api/v1/reports` (the shipped `reports` capability — no new or changed endpoint) with `target_type = "chat_message"` and `target_id = <chat message id>`, plus the selected `reason_category` and the optional `reason_note`, by reusing the shared `ReportSubmitter` (`submit(target = ReportTargetType.CHAT_MESSAGE, targetId = message.id, category, note)`). `ReportTargetType.CHAT_MESSAGE.wire` SHALL equal `"chat_message"`. The submission SHALL reuse the single shared `Auth { bearer }`-interceptor `HttpClient`. NO backend, rate-limit, auto-hide, or schema change SHALL be introduced — and no second report-submission path SHALL be created.

#### Scenario: Reporting a chat message posts the chat_message target

- **GIVEN** the chat report path over a MockEngine recording requests, reporting a message with id `M`
- **WHEN** the report is submitted with a selected category
- **THEN** the request is `POST /api/v1/reports` with `target_type = "chat_message"` AND `target_id = "M"`

#### Scenario: CHAT_MESSAGE maps to the shipped wire value

- **WHEN** `ReportTargetType.CHAT_MESSAGE.wire` is read
- **THEN** it equals `"chat_message"` (the exact string the backend `reports` `target_type` CHECK validates)

#### Scenario: The chat report reuses the shared submitter, not a new path

- **WHEN** inspecting the chat report wiring
- **THEN** it calls the shared `ReportSubmitter` / `ReportApiClient` (no second or duplicated report-submission implementation, no new networking client)

### Requirement: Every chat report outcome maps to exactly one UI result with no review-outcome leak

The chat-message report submission SHALL map each `ReportOutcome` member to exactly one user-visible result with NO generic fallthrough, following the **post-detail (anti-enumeration) posture**: `Submitted` (204) AND `Duplicate` (409 `duplicate_report`) SHALL both render the same success message "Laporan terkirim. Tim moderasi akan meninjau." (the duplicate case is indistinguishable from a first report, so a reporter learns nothing about prior reports — `docs/03`:234); `RateLimited` (429) SHALL render a rate-limit message and SHALL NOT claim the report was recorded; `NetworkError` (5xx / transport / any unenumerated status) SHALL be retryable. The reporter SHALL NOT be shown any moderation review outcome. The result SHALL be modeled as one-shot UI state on the screen's single `StateFlow<…UiState>`, consumed and cleared via an `onXxxShown()` callback (not a `Channel`/`SharedFlow` event bus).

#### Scenario: Submitted shows the success message

- **WHEN** the submission returns `204`
- **THEN** the success message "Laporan terkirim. Tim moderasi akan meninjau." is shown AND the dialog is dismissed

#### Scenario: Duplicate is indistinguishable from success

- **WHEN** the submission returns `409` with `error.code = "duplicate_report"`
- **THEN** the SAME success message is shown (no "already reported" wording) AND no second submission is attempted

#### Scenario: Rate limited does not claim success

- **WHEN** the submission returns `429`
- **THEN** a rate-limit message is shown AND the success message is NOT shown

#### Scenario: Network error is retryable

- **WHEN** the submission fails with a 5xx / transport error
- **THEN** a retryable error is surfaced AND the success message is NOT shown

#### Scenario: One-shot result is cleared after being shown

- **WHEN** the result has been shown and `onXxxShown()` is invoked
- **THEN** the result field is cleared so it does not re-fire on recomposition or configuration change

### Requirement: Chat report PII discipline

The chat report path SHALL carry only the message `id` as `target_id` and SHALL never log the bearer token, the `Authorization` header, the JWT `sub`, the message `senderId`, or the message body. Reporting SHALL be permitted against a sender the viewer has blocked or who is shadow-banned (the `reports` path applies no block-exclusion — reporting blocked/shadow-banned users is valid). Reporting a chat message SHALL NOT trigger any client-side enforcement (no auto-hide, no local redaction) — admin enforcement is the server-side chat-redaction path (`admin-chat-message-redaction`).

#### Scenario: The chat report seam logs no sender, body, or token

- **WHEN** the chat report wiring (the `ChatThreadViewModel` report path and any chat report helper) is scanned
- **THEN** no logging call site passes the bearer token, the `Authorization` header, the JWT `sub`, the message `senderId`, or the message body as a logged argument

#### Scenario: Reporting a blocked sender's message is permitted

- **GIVEN** a received message from a sender the viewer has blocked, still present in the thread history
- **WHEN** the viewer reports that message
- **THEN** the submission proceeds (no client block-exclusion guard prevents it) AND only the message `id` is sent as `target_id`

### Requirement: The chat report reuses the existing chat state holder and Koin graph

The chat report submission SHALL be wired through the existing `ChatThreadViewModel` (resolved via `viewModel { }` scoped to the `ChatThreadRoute` NavEntry through the existing Koin module — the established mobile state-holder Pattern Registry entry, docs/11 §2.2; NOT a new state pattern). The view model SHALL inject the concrete `ReportSubmitter` (the same class `PostDetailViewModel` injects, with a `FakeReportSubmitter` substituted in `commonTest`). No new view model and no second report dialog SHALL be introduced.

#### Scenario: The chat report dependencies resolve from Koin

- **WHEN** the Koin graph is validated for the chat module
- **THEN** `ChatThreadViewModel` resolves with its `ReportSubmitter` dependency satisfied

#### Scenario: Exactly one report dialog composable is referenced

- **WHEN** inspecting the chat report wiring
- **THEN** it references the shared `ui/components/ReportDialog` (no second or chat-specific report dialog is defined)

### Requirement: The change introduces no backend, schema, or migration edit

This capability SHALL be mobile-only: it SHALL add no Flyway migration, no `db/migration/**` SQL, and no `:backend:ktor` route or repository change. It reuses the shipped `POST /api/v1/reports` endpoint unchanged.

#### Scenario: The diff is mobile-only

- **WHEN** inspecting the change's diff
- **THEN** it adds no `db/migration/*.sql` file AND modifies no `:backend:ktor` source (the only non-test edits are under `:mobile:app` and `:shared:resources`)

### Requirement: Test coverage for the chat-message-report capability

The change SHALL ship: (1) `commonTest` for the `CHAT_MESSAGE.wire` mapping, the chat report outcome→UI-state mapping (Submitted/Duplicate→success, RateLimited→message, NetworkError→retry, one-shot clear), and the affordance-visibility projection `isReportable = !isOwn && !isRedacted` (own → false, including an **optimistic-own** send row; redacted → false; received non-redacted → true); (2) a Robolectric `ChatThreadScreenTest` path exercising long-press → "Laporkan" → `ReportDialog` → pick category + submit → success message, AND asserting NO affordance on own/sent messages and on redacted messages — ADDED to the Release-variant `*ScreenTest` test-exclude list and passing under `:mobile:app:testDevReleaseUnitTest`; (3) an `iosTest` flow test mirroring **`PostDetailFlowIosTest`** (there is no pre-existing chat iOS flow test to copy), with Kotlin/Native-legal test function names (`commonTest`/Kotest does not run on Native); (4) an `androidUnitTest` Koin-resolution test (a `ChatReportKoinResolutionTest`, per the established `*KoinResolutionTest` pattern) asserting `ChatThreadViewModel` resolves with its `ReportSubmitter` dependency satisfied; (5) a PII source-scan verification (a structural test, or a `LogLevel`-is-body-free assertion) that no `senderId` / message body / bearer token is logged on the chat report path. The pre-existing `ReportTargetTypeTest` — which currently asserts chat_message is **absent** — SHALL be updated to expect the four members (or the apply step's CI red-fails on it).

#### Scenario: Mapping and projection tests exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** the `CHAT_MESSAGE.wire` mapping test, the outcome→state mapping test, and the affordance-visibility projection test (covering own / optimistic-own / redacted / received) are discovered AND each documented mapping/state corresponds to at least one `@Test`

#### Scenario: The new screen test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the new chat report screen test is listed in the Release-variant `*ScreenTest` exclude block AND `:mobile:app:testDevReleaseUnitTest` passes

#### Scenario: The Koin-resolution and PII-scan tests are present

- **WHEN** inspecting the `androidUnitTest` / `commonTest` sources
- **THEN** a Koin-resolution test asserting `ChatThreadViewModel` resolves `ReportSubmitter` exists AND a PII source-scan / body-free-`LogLevel` verification for the chat report path exists

#### Scenario: An iOS flow test exercises the chat report path

- **WHEN** inspecting `mobile/app/src/iosTest/...`
- **THEN** a chat report flow test (modeled on `PostDetailFlowIosTest`) exists (long-press a received message → report → success) with Kotlin/Native-legal test function names

