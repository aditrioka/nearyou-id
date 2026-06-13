# mobile-settings — Delta Specification

## ADDED Requirements

### Requirement: SettingsRoute and its sub-routes are serializable parameterless NavKeys pushed onto the root back stack

The mobile app SHALL add three `@Serializable data object` NavKeys to `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt` — `SettingsRoute`, `BlockedUsersRoute`, and `ConsentSettingsRoute` — each registered in the `AppNavSerialization` polymorphic `SerializersModule` (required for iOS back-stack saveability, where Nav3 reflection-based serialization is unavailable, per `mobile-app-scaffold` "Back stack uses serializable NavKey routes"). All three SHALL carry NO identity payload (the user identity lives in the persisted token, never in the serialized back stack). The settings surfaces SHALL be appended onto the **root** back stack (above the section shell), so they overlay the section `NavigationBar` exactly as `PostDetailRoute` / `PostCreationRoute` do — they SHALL NOT introduce a per-tab `NavDisplay` back stack (still deferred per GitHub issue [#189](https://github.com/aditrioka/nearyou-id/issues/189) `mobile-home-tab-host-per-tab-backstacks`).

#### Scenario: Settings NavKeys round-trip through the nav serializer

- **WHEN** each of `SettingsRoute`, `BlockedUsersRoute`, `ConsentSettingsRoute` is serialized and deserialized via the `AppNavSerialization` polymorphic module
- **THEN** each round-trips to an equal value (registered in the `SerializersModule`) AND carries no identity payload field

#### Scenario: Opening settings appends onto the root back stack over the shell

- **GIVEN** the authenticated section shell composed over a test root back stack
- **WHEN** the settings entry is invoked
- **THEN** `SettingsRoute` is appended onto the root back stack (the surface overlays the bottom `NavigationBar`) AND no per-tab `NavDisplay` back stack is created

### Requirement: Settings is reachable via a profile-screen gear

`SettingsScreen` SHALL be reachable from a settings affordance (a Material `settings` gear icon, per `mobile-design-system` § "Material 3 icons are the canonical navigation and action affordance") rendered on the authenticated **profile** surface (the Profil section's self-profile screen introduced by the `mobile-profile` capability, PR [#245](https://github.com/aditrioka/nearyou-id/pull/245)). The gear's tap SHALL push `SettingsRoute` onto the **root** back stack. The `mobile-settings` capability OWNS the `SettingsRoute` contract and the push semantics; the gear control is wired on the profile surface as an integration step sequenced **after** `mobile-profile` (PR #245) merges (see `design.md` Decision D7). The gear's `contentDescription` SHALL be sourced via `stringResource` (no hardcoded literal).

#### Scenario: The profile-screen gear pushes SettingsRoute onto the root stack

- **GIVEN** the self-profile surface composed with a recording navigation callback (or over a test root back stack)
- **WHEN** the settings gear is tapped
- **THEN** `SettingsRoute` is appended onto the root back stack exactly once AND the gear exposes a non-empty `stringResource`-sourced `contentDescription`

### Requirement: SettingsScreen renders the frame-16 grouped list with its own Scaffold and app bar

`SettingsScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/settings/SettingsScreen.kt`) SHALL render as a **pushed root-stack overlay surface** owning its OWN Material 3 `Scaffold` with a top app bar titled via `stringResource(Res.string.settings_title)` ("Pengaturan") and a leading `arrow_back` icon (whose tap pops the back stack), following the `PostDetailScreen` pushed-overlay precedent — this does NOT violate the `mobile-design-system` "the app shell owns a single Scaffold and window insets" requirement, which governs shell-rendered *section* surfaces; root-stack overlay screens keep their own chrome (per `design.md` D2). The screen body SHALL be a scrollable grouped settings list with the canonical mockup-frame-16 section headers — **AKUN**, **PREMIUM**, **PRIVASI**, **LAINNYA** (`dev/mockups/nearyou-screens-mockup.html` frame 16, binding for look/layout per docs/11 § 2.8) — each header sourced via `stringResource`, each row a settings list item (Material leading icon + title + optional subtitle + trailing chevron OR M3 `Switch`). Every row title/subtitle/header SHALL be sourced via `:shared:resources` Compose Multiplatform Resources — NO hardcoded UI string literals SHALL appear in the settings source. The surface SHALL render under `NearYouTheme` correctly in both light and dark schemes, built from theme tokens only (no color/typography literals).

#### Scenario: Settings renders the four section headers and the app bar

- **WHEN** `SettingsScreen` is composed under `NearYouTheme`
- **THEN** the tree contains a top app bar node with text `stringResource(Res.string.settings_title)` and an `arrow_back` affordance AND nodes matching the four section-header strings (AKUN / PREMIUM / PRIVASI / LAINNYA)

#### Scenario: No hardcoded UI strings in the settings source

- **WHEN** the `screens/settings/**` source tree is scanned for string literals used as user-visible text
- **THEN** no hardcoded UI string literal is found (every label/title/subtitle/contentDescription resolves through `Res.string.*`)

#### Scenario: Settings renders under both schemes from theme tokens

- **WHEN** `SettingsScreen` is composed under `NearYouTheme` light and again under dark
- **THEN** it renders without crash in both AND the source contains no hex color literals (theme tokens only)

### Requirement: Backed rows are wired; deferred rows show a non-writing "Segera hadir" affordance and ship no dead control

Per the operator's mockup-faithful-shell scope decision, `SettingsScreen` SHALL render ALL frame-16 rows, partitioned into **backed** rows (wired to a real destination/action) and **deferred** rows (no backend yet). The backed rows are exactly: PRIVASI > "Pengguna diblokir" (→ `BlockedUsersRoute`), PRIVASI > "Privasi & data" (→ `ConsentSettingsRoute`), LAINNYA > "Ketentuan & kebijakan privasi" (→ the static legal/privacy URL), and LAINNYA > "Keluar" (logout). The deferred rows are exactly: AKUN > "Edit profil", AKUN > "Ganti username", PREMIUM > "Perjalanan Premium", PREMIUM > "Kelola langganan", PRIVASI > "Profil privat", PRIVASI > "Sembunyikan jarak". A deferred row SHALL render its mockup icon/title/subtitle but its activation SHALL surface a non-trapping "Segera hadir" affordance (a snackbar / inert state via `stringResource(Res.string.settings_coming_soon)`) and SHALL perform **no backend write and no navigation to a non-existent destination** — in particular the deferred "Profil privat" and "Sembunyikan jarak" toggles SHALL NOT issue any `UPDATE users` / privacy-flag write (the `@allow-privacy-write` invariant surface is deliberately not entered by this change). Each deferred row SHALL be tracked by a follow-up GitHub issue (label `follow-up` + `mobile`).

#### Scenario: A deferred row surfaces "Segera hadir" and writes nothing

- **GIVEN** `SettingsScreen` composed over a MockEngine that records all outbound requests
- **WHEN** a deferred row (e.g. "Profil privat") is activated
- **THEN** the "Segera hadir" affordance (`stringResource(Res.string.settings_coming_soon)`) is shown AND no outbound request is recorded (no privacy-flag write, no navigation to a missing destination)

#### Scenario: Backed rows navigate to their wired destinations

- **GIVEN** `SettingsScreen` composed over a test root back stack
- **WHEN** "Pengguna diblokir" and then "Privasi & data" are activated
- **THEN** `BlockedUsersRoute` and `ConsentSettingsRoute` respectively are appended onto the root back stack

### Requirement: Block-list management lists the viewer's blocked users and unblocks them

`BlockedUsersScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/settings/BlockedUsersScreen.kt`) SHALL list the authenticated viewer's outbound blocks by issuing `GET /api/v1/blocks` (via the existing `Auth { bearer }`-interceptor `HttpClient`) and SHALL allow unblocking a listed user by issuing `DELETE /api/v1/blocks/{user_id}`. Each list row SHALL render the blocked user's **display name** and **@username handle** (handle via a `stringResource` format) and an unblock affordance; the row SHALL NOT render the blocked user's UUID or any raw coordinate (PII discipline — the `userId` is held only as the `DELETE` path parameter, never displayed or logged). A successful unblock SHALL remove the row from the list; unblocking is reversible by re-blocking from the user's profile (no undo control is shipped). If the unblock `DELETE` fails (5xx / network), the row SHALL remain in — or be restored to — the list and a non-trapping error SHALL surface: the list SHALL NOT optimistically drop a row whose unblock did not succeed. The screen SHALL render the loading / empty / error states per the `mobile-design-system` loading-refresh + empty-error state contract — the empty state SHALL show `stringResource(Res.string.blocked_users_empty)` ("Belum ada pengguna yang diblokir"). A terminal `401` on either call SHALL route to the sign-in surface (token-invalid), consistent with the other authenticated screens. The blocked user's `userId` (UUID) SHALL never be logged anywhere in the block package.

#### Scenario: Block list renders the viewer's blocks without UUIDs

- **GIVEN** a MockEngine returning `GET /api/v1/blocks` → `{"blocks":[{"userId":"11111111-1111-1111-1111-111111111111","username":"raka.jkt","displayName":"Raka Pratama","isPremium":false,"createdAt":"2026-06-01T00:00:00Z"}],"nextCursor":null}`
- **WHEN** `BlockedUsersScreen` is rendered
- **THEN** the tree contains "Raka Pratama" and the handle node for "raka.jkt" AND contains NO node whose text contains "11111111-1111-1111-1111-111111111111"

#### Scenario: Unblock issues the DELETE and removes the row

- **GIVEN** a rendered block list with one blocked user `raka.jkt` (`userId = 1111…`) and a MockEngine recording requests
- **WHEN** the unblock affordance on that row is activated and the server responds success
- **THEN** exactly one `DELETE /api/v1/blocks/11111111-1111-1111-1111-111111111111` request was recorded AND the row is removed from the rendered list

#### Scenario: A failed unblock keeps the row and surfaces an error

- **GIVEN** a rendered block list with one blocked user (`userId = 1111…`) and a MockEngine returning `DELETE /api/v1/blocks/{userId}` → `500`
- **WHEN** the unblock affordance on that row is activated
- **THEN** the row remains present in the rendered list AND a non-trapping error is surfaced (no silent optimistic removal, no crash, no sign-in redirect)

#### Scenario: The block package logs no blocked-user identifier

- **WHEN** the block data-seam (`BlockedUsersApiClient`/`Repository`) and `BlockedUsersScreen` sources are scanned
- **THEN** no logging call site passes a blocked user's `userId` (UUID), `username`, or `displayName` as a logged argument

#### Scenario: Empty block list shows the empty-state copy and no error

- **GIVEN** a MockEngine returning `GET /api/v1/blocks` → `{"blocks":[],"nextCursor":null}`
- **WHEN** `BlockedUsersScreen` is rendered
- **THEN** the tree contains a node with text `stringResource(Res.string.blocked_users_empty)` AND renders no error state

#### Scenario: A 401 on the block-list read routes to sign-in

- **GIVEN** a MockEngine returning `GET /api/v1/blocks` → `401`
- **WHEN** `BlockedUsersScreen` loads
- **THEN** a navigation event routing to the sign-in surface is emitted AND no blocked-user rows are rendered

### Requirement: The block-list data seam mirrors the established ApiClient → Repository → sealed-Outcome pattern

The block-list read/unblock SHALL be implemented behind the project's standard mobile data seam (docs/11 § 2.6, the pattern the timeline + consent seams already use): a `BlockedUsersApiClient` (the HTTP boundary), a `BlockedUsersRepository` (mapping DTOs → domain + exposing a sealed outcome), and a sealed `BlockedUsersOutcome` (success / terminal-401 / retryable-error) — NOT a second bespoke networking pattern (anti-patchwork, docs/11 Pattern Registry). The response DTO SHALL match the shipped wire shape of `GET /api/v1/blocks` field-for-field: `BlockListResponse { blocks: List<BlockListItem>, nextCursor: String? }` where `BlockListItem { userId: String, username: String, displayName: String, isPremium: Boolean, createdAt: String }` — all bare camelCase (the shipped `BlockRoutes.kt` wire), parsed with `ignoreUnknownKeys`. The `nextCursor` cursor SHALL be threaded for load-more pagination (append the next page on scroll-end); if pagination wiring is deferred, the first page SHALL still render and the deferral SHALL be recorded as a follow-up GitHub issue (label `follow-up`).

#### Scenario: Block-list DTO parses the shipped wire shape

- **WHEN** the canonical `GET /api/v1/blocks` JSON (camelCase `userId`/`username`/`displayName`/`isPremium`/`createdAt` + `nextCursor`) is decoded by `BlockListResponse`
- **THEN** the fields populate correctly AND an unknown extra key does not fail the parse (`ignoreUnknownKeys`)

#### Scenario: A retryable error surfaces an error state, not a crash

- **GIVEN** a MockEngine returning `GET /api/v1/blocks` → `500`
- **WHEN** `BlockedUsersScreen` loads
- **THEN** the repository emits the retryable-error outcome AND the screen renders its error state (no crash, no sign-in redirect)

### Requirement: Consent settings reuse the existing mobile consent seam and submit via PATCH

The PRIVASI > "Privasi & data" sub-screen `ConsentSettingsScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/settings/ConsentSettingsScreen.kt`) SHALL present the three Material 3 toggles — Analytics, Crash Reporting, Ads Personalization — and SHALL submit the toggle triple via `PATCH /api/v1/user/consent` by **reusing the existing mobile consent seam** (`consent/ConsentApiClient`, `ConsentFlow`, `ConsentOutcome` from `mobile-analytics-consent`) — it SHALL NOT introduce a second consent networking path (anti-patchwork). The submit-outcome mapping SHALL be status-driven with no generic fallthrough (`200` → persist + confirm; `401` → terminal sign-in redirect; `5xx`/IO/`400` → retryable in-screen error), and a double-tap on submit SHALL issue exactly one `PATCH`. The screen and its repository SHALL never log the bearer token, the JWT `sub`, or the PATCH request/response body (PII discipline, reusing the `mobile-analytics-consent` posture; the Ktor `LogLevel` on this path MUST NOT include bodies).

#### Scenario: Consent submit issues the canonical PATCH with the toggle triple

- **GIVEN** `ConsentSettingsScreen` rendered with Analytics ON, Crash OFF, Ads ON and a MockEngine recording requests
- **WHEN** the save affordance is activated
- **THEN** the captured outbound request is `PATCH /api/v1/user/consent` whose JSON body parses as `{"analytics": true, "crash": false, "ads_personalization": true}`

#### Scenario: Double-tap save issues exactly one PATCH

- **GIVEN** a MockEngine that counts `PATCH /api/v1/user/consent` requests and responds `200` after a brief delay
- **WHEN** the save affordance is tapped twice in rapid succession
- **THEN** exactly one `PATCH /api/v1/user/consent` request was recorded

#### Scenario: A 401 on consent submit routes to sign-in

- **GIVEN** `ConsentSettingsScreen` with a MockEngine returning `PATCH /api/v1/user/consent` → `401`
- **WHEN** the save affordance is activated
- **THEN** a navigation event routing to the sign-in surface is emitted (terminal token-invalid) AND no in-screen retryable error is shown

#### Scenario: Consent sources contain no token/sub/body log argument

- **WHEN** the `screens/settings/**` consent sources and the reused `consent/**` package are scanned
- **THEN** no logging call site passes the bearer token, the `Authorization` header, the JWT `sub`, or the PATCH request/response body as a logged argument

### Requirement: Consent settings initialize from the last-submitted snapshot, falling back to the V2 safe defaults

Because there is NO server consent-read endpoint (the `analytics-consent-update` capability ships `PATCH` only; the `mobile-analytics-consent` onboarding screen issues no GET), `ConsentSettingsScreen` SHALL initialize its toggles from the **last-submitted consent snapshot** persisted on the device on each successful `PATCH`, falling back to the V2 column defaults — **analytics OFF, crash ON, ads OFF** — when no snapshot exists (e.g. a returning user who consented only at onboarding, before any settings submit). The persisted snapshot value SHALL be the triple the server **echoes in the `PATCH` `200` body** (`ConsentResponse` — the server's authoritative acknowledgement of the write), not a client-side guess, so the mirror cannot drift from the last server-acknowledged state on this single-device PATCH-only flow. The initial state SHALL be injectable for testability (a default-values parameter / initial `ConsentUiState`), not read from wall-clock or platform state. A true server-side consent-read endpoint (so settings could reflect a value changed on another device) and durable cross-session persistence hardening remain OUT of scope and SHALL be recorded as a follow-up GitHub issue (label `follow-up`), related to the deferred reliable-persist hardening tracked by issue [#198](https://github.com/aditrioka/nearyou-id/issues/198); the follow-up note SHALL record that the `PATCH` `200` already round-trips the authoritative triple, so a dedicated GET is a robustness nicety, not a correctness gap for the single-device case.

#### Scenario: No prior submit → toggles default to analytics OFF, crash ON, ads OFF

- **GIVEN** no persisted consent snapshot on the device
- **WHEN** `ConsentSettingsScreen` is rendered
- **THEN** the initial toggle state is `analytics = false`, `crash = true`, `ads_personalization = false` (the V2 default) AND no consent-read request is issued (there is no GET endpoint)

#### Scenario: A prior submit seeds the toggles from the snapshot

- **GIVEN** a persisted snapshot `{analytics = true, crash = true, ads_personalization = false}` from an earlier successful submit
- **WHEN** `ConsentSettingsScreen` is rendered
- **THEN** the toggles initialize to `analytics = true, crash = true, ads_personalization = false` (the snapshot, not the V2 default)

#### Scenario: A successful submit updates the persisted snapshot

- **GIVEN** `ConsentSettingsScreen` with the user toggling Analytics ON and submitting, the server responding `200` with the echoed triple
- **WHEN** the persisted snapshot is read back
- **THEN** the snapshot reflects the server-echoed triple from the `200` body (so a later settings re-entry initializes to it)

#### Scenario: A snapshot written by one instance seeds a freshly reconstructed instance

- **GIVEN** a shared on-device snapshot store and a first consent state-holder that submits `{analytics = true, crash = true, ads_personalization = false}` and receives `200`
- **WHEN** a SECOND consent state-holder is constructed from the SAME store (simulating a later app entry / process restart — NOT the same in-memory instance)
- **THEN** the second instance initializes its toggles to `{analytics = true, crash = true, ads_personalization = false}` (read back from the store, not the V2 default)

### Requirement: Logout clears the token store and routes to sign-in

The LAINNYA > "Keluar" row SHALL, after a confirmation dialog (`stringResource` copy: title + body + confirm/cancel), clear the persisted session by wiping `SecureTokenStore` and emit a navigation event routing to the sign-in surface via `replaceAll` (so the authenticated back stack is cleared and a system back gesture cannot return to the authenticated surface). Logout SHALL require no server call (the client-side token wipe is sufficient for the MVP; server-side token-version rotation is a separate concern). The confirm and cancel affordances SHALL be sourced via `:shared:resources`.

#### Scenario: Confirming logout wipes the token store and routes to sign-in

- **GIVEN** `SettingsScreen` with a populated `SecureTokenStore` and the logout confirmation dialog shown
- **WHEN** the confirm affordance is activated
- **THEN** `SecureTokenStore` is cleared AND a navigation event routing to the sign-in surface via `replaceAll` is emitted

#### Scenario: Cancelling logout leaves the session intact

- **GIVEN** the logout confirmation dialog shown over a populated `SecureTokenStore`
- **WHEN** the cancel affordance is activated
- **THEN** `SecureTokenStore` is unchanged AND no navigation event is emitted (the dialog dismisses)

### Requirement: The legal/privacy row opens the static policy URL

The LAINNYA > "Ketentuan & kebijakan privasi" row SHALL open the static Terms / Privacy Policy URL via the platform's external-link mechanism (the URL sourced as a non-secret build/string constant). It SHALL render its title via `stringResource`. This row performs no backend call.

#### Scenario: Legal row activation opens the policy URL

- **GIVEN** `SettingsScreen` with a recording external-link handler
- **WHEN** the "Ketentuan & kebijakan privasi" row is activated
- **THEN** the handler is invoked with the configured policy URL AND no backend request is issued

### Requirement: Settings state holders are scoped to their NavEntry routes

`SettingsScreen`'s logout/state holder, `BlockedUsersScreen`'s `BlockedUsersViewModel`, and the consent sub-screen's view model SHALL each be resolved via `viewModel { }` scoped to their respective NavEntry (`SettingsRoute` / `BlockedUsersRoute` / `ConsentSettingsRoute`), per the established mobile state-holder Pattern Registry entry (docs/11 § 2.2) — NOT a new state pattern. Their dependencies (the API clients / repositories, `SecureTokenStore`) SHALL be provided through the existing Koin module and resolve at runtime.

#### Scenario: The settings view models resolve from Koin at their route scope

- **WHEN** the Koin graph is validated (a Koin-resolution test) for the settings module
- **THEN** `BlockedUsersViewModel`, the consent settings view model, and the settings/logout holder each resolve with all dependencies satisfied

### Requirement: Settings ships its test trio and excludes the screen tests from the Release variant

The change SHALL ship: (1) Robolectric `*ScreenTest`s for `SettingsScreen` and `BlockedUsersScreen` (and the consent settings sub-screen), each added to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (per the `*ScreenTest` convention — the `ui-test-manifest` host activity is debug-only); (2) `commonTest`s covering the block-list DTO parse (shipped camelCase wire + unknown-key tolerance), the block-list/consent UI-state projections, the consent local-snapshot initialization (no-snapshot → V2 default; snapshot → snapshot), the no-dead-write deferred-row behavior, and the Koin resolution; (3) an `iosTest` flow (mirroring the existing screen iOS flow tests, e.g. `NearbyTimelineFlowIosTest`) exercising the settings surface on the simulator with Kotlin/Native-legal test function names.

#### Scenario: Screen tests are present and Release-excluded

- **WHEN** inspecting the mobile test sources and `mobile/app/build.gradle.kts`
- **THEN** `SettingsScreenTest` and `BlockedUsersScreenTest` exist under `androidUnitTest` AND both are named in the Release-variant test-exclude list

#### Scenario: commonTest covers the projections, snapshot init, and Koin resolution

- **WHEN** inspecting the `commonTest` sources
- **THEN** tests exist for the block-list DTO parse, the block-list / consent UI-state projections, the consent snapshot init (no-snapshot → V2 default; snapshot → snapshot; reconstruct → seed), and the settings Koin resolution

#### Scenario: An iOS flow test exercises the settings surface

- **WHEN** inspecting `mobile/app/src/iosTest/...`
- **THEN** a settings flow test exists (open settings → block list → consent → back) with Kotlin/Native-legal test function names

### Requirement: Account deletion, data export, suspension countdown, and notification chat-preview are explicitly out of scope

This change SHALL NOT implement account deletion ("Hapus Akun"), data export ("Unduh Data Saya"), a suspension-countdown surface, or the notification chat-preview toggle. Each is intentionally absent from the canonical settings mockup (frame 16) AND lacks a backend endpoint (account-deletion "ships later" per `AuthPlugin.kt`; suspension is surfaced only at the auth/write-403 boundary with no client read path; the chat-preview toggle has no endpoint) — shipping any of them now would ship a dead control. The live-menu-row-#5 text mentioned account-deletion + suspension; the canonical mockup (which governs the visible entry set) does not — this divergence is recorded in `design.md`. Each deferred surface SHALL be tracked by a follow-up GitHub issue (label `follow-up` + `mobile`).

#### Scenario: No account-deletion, export, or suspension control is rendered

- **WHEN** `SettingsScreen` is rendered and its tree inspected
- **THEN** it contains no "Hapus Akun", "Unduh Data Saya", suspension-countdown, or notification chat-preview control (these surfaces are deferred, not shipped as dead rows)
