## RENAMED Requirements

- FROM: ### Requirement: Data export, suspension countdown, and notification chat-preview are explicitly out of scope
- TO: ### Requirement: Suspension countdown and notification chat-preview are explicitly out of scope

## MODIFIED Requirements

### Requirement: Suspension countdown and notification chat-preview are explicitly out of scope

This `mobile-settings` capability SHALL NOT implement a suspension-countdown surface or the notification chat-preview toggle. Each lacks a usable client read path (suspension is surfaced only at the auth/write-403 boundary with no client read endpoint; the chat-preview toggle has no endpoint) — shipping either now would ship a dead control. **Data export ("Unduh Data Saya") is NO LONGER out of scope** — as of `account-data-export` it has a real backend (`POST` / `GET /api/v1/account/export`) and is shipped per the "Settings offers data export" requirement below (via the `mobile-data-export-entry` change). **Account deletion ("Hapus Akun") is also NO LONGER out of scope** — as of `account-deletion-tombstone` it has a real backend (`POST` / `DELETE` / `GET /api/v1/account/deletion-request`) and is shipped per the "Settings offers account deletion" requirement below. Each still-deferred surface SHALL be tracked by a follow-up GitHub issue (label `follow-up` + `mobile`).

#### Scenario: No suspension or chat-preview control is rendered

- **WHEN** `SettingsScreen` is rendered and its tree inspected
- **THEN** it contains no suspension-countdown or notification chat-preview control (these surfaces remain deferred, not shipped as dead rows) — but it DOES contain the data-export ("Unduh Data Saya") affordance (see the "Settings offers data export" requirement) AND the account-deletion ("Hapus Akun") affordance (see the account-deletion requirement)

## ADDED Requirements

### Requirement: Settings offers data export with the canonical email-delivery disclosure

`SettingsScreen` SHALL render a non-destructive "Unduh Data Saya" affordance (this row is intentionally absent from mockup frame 16 — a docs-vs-mockup divergence recorded in `design.md` § D5, mirroring the "Hapus Akun" precedent; `docs/03-UX-Design.md` § Data Export governs behavior: "Settings > Unduh Data Saya"; all copy via `:shared:resources` Compose Multiplatform Resources, no hardcoded literals). This row is **no longer a deferred "Segera hadir" row**. Activating it SHALL present a confirmation dialog whose body carries the canonical Bahasa disclosure — "Export akan dikirim sebagai link download via email dalam 7 hari. Link berlaku 24 jam setelah dikirim." — and confirming SHALL issue `POST /api/v1/account/export` through the standard mobile data seam (a `DataExportApiClient` → `DataExportRepository` → sealed outcome, per docs/11 § 2.6 — NOT a second bespoke networking pattern). Cancelling the dialog SHALL issue no request. A terminal `401` SHALL route to the sign-in surface; a retryable error (`5xx`/IO) SHALL surface a non-trapping in-screen error (no crash, no optimistic stick). The request is **single-active** server-side (the endpoint is idempotent — an existing `pending`/`processing` request is returned, not duplicated), so the UI SHALL NOT re-issue a `POST` while a request is already in progress (see the status requirement).

#### Scenario: Confirming export issues the request

- **GIVEN** `SettingsScreen` over a MockEngine recording requests, with the data-export confirmation dialog shown
- **WHEN** the confirm affordance is activated and the server responds `202` with `{"requestId":"…","status":"pending"}`
- **THEN** exactly one `POST /api/v1/account/export` request was recorded AND a pending/in-progress state is reflected in the UI

#### Scenario: Cancelling the confirmation issues nothing

- **GIVEN** the data-export confirmation dialog shown
- **WHEN** the cancel affordance is activated
- **THEN** no outbound request is recorded and the dialog dismisses

#### Scenario: A 401 on the export request routes to sign-in

- **GIVEN** a MockEngine returning `POST /api/v1/account/export` → `401`
- **WHEN** the user confirms the export
- **THEN** a navigation event routing to the sign-in surface is emitted

#### Scenario: No hardcoded strings in the data-export affordance

- **WHEN** the data-export row + confirmation dialog sources are scanned for user-visible text
- **THEN** every label/title/body/confirm/cancel resolves through `Res.string.*`

### Requirement: A data-export request shows non-blocking status driven by GET /api/v1/account/export

On screen open `SettingsScreen` SHALL seed the data-export status via `GET /api/v1/account/export` and SHALL reflect the caller's latest request as a **non-blocking** affordance/banner, with copy + dates formatted via `:shared:resources`. The status projection SHALL be status-driven with no generic fallthrough over the shipped vocabulary: `none` → only the request affordance is offered (no banner); `pending`/`processing` → a non-blocking "sedang diproses" state AND the request is NOT re-issuable (single-active); `ready` → a banner referencing the download deadline (`downloadExpiresAt`) with an affordance to open the freshly-signed `downloadUrl` the read returns (the in-app path the `account-data-export` GET requirement provides, complementing the email delivery in the confirm-dialog copy); `expired`/`failed` → a note allowing a fresh request. A `GET` failure (`5xx`/IO) SHALL surface the screen's error state (no crash); a terminal `401` on the read SHALL route to the sign-in surface. The seam SHALL never display or log the `downloadUrl` beyond handing it to the platform open-URL affordance, and SHALL NOT persist it to DataStore / preferences / disk / any cache — it is held only transiently in UI state for the duration of the open-URL hand-off.

#### Scenario: A ready export shows the deadline and an in-app download affordance

- **GIVEN** a MockEngine returning `GET /api/v1/account/export` → `{"status":"ready","downloadExpiresAt":"2026-07-01T00:00:00Z","downloadUrl":"https://r2.example/signed"}`
- **WHEN** `SettingsScreen` is rendered
- **THEN** a non-blocking banner is shown referencing the restore-by/download deadline AND an affordance to open the signed download is present

#### Scenario: A pending export is shown as in-progress and is not re-issuable

- **GIVEN** a MockEngine returning `GET /api/v1/account/export` → `{"status":"processing"}` and recording requests
- **WHEN** `SettingsScreen` is rendered and the user attempts to start another export
- **THEN** a non-blocking "sedang diproses" state is shown AND no second `POST /api/v1/account/export` is recorded

#### Scenario: No prior export renders the request affordance and no banner

- **GIVEN** a MockEngine returning `GET /api/v1/account/export` → `{"status":"none"}`
- **WHEN** `SettingsScreen` is rendered
- **THEN** the "Unduh Data Saya" request affordance is offered AND no status banner is shown

#### Scenario: An expired export allows a fresh request

- **GIVEN** a MockEngine returning `GET /api/v1/account/export` → `{"status":"expired"}`
- **WHEN** `SettingsScreen` is rendered
- **THEN** a re-request affordance is offered (the export is no longer single-active-locked)

#### Scenario: A failed export allows a fresh request that issues a new POST

- **GIVEN** a MockEngine returning `GET /api/v1/account/export` → `{"status":"failed"}` and recording requests
- **WHEN** `SettingsScreen` is rendered, the user starts a new export, confirms, and the server responds `202`
- **THEN** the re-request affordance is offered (the export is not single-active-locked after a failed run) AND exactly one new `POST /api/v1/account/export` is recorded

#### Scenario: A failed status read surfaces an error, not a crash

- **GIVEN** a MockEngine returning `GET /api/v1/account/export` → `500`
- **WHEN** `SettingsScreen` loads
- **THEN** the screen renders its error state (no crash, no sign-in redirect)

#### Scenario: A 401 on the status read routes to sign-in

- **GIVEN** a MockEngine returning `GET /api/v1/account/export` → `401`
- **WHEN** `SettingsScreen` loads
- **THEN** a navigation event routing to the sign-in surface is emitted AND no status banner is rendered

### Requirement: The data-export seam follows the established ApiClient → Repository → sealed-Outcome pattern

The data-export request / status calls SHALL be implemented behind the project's standard mobile data seam (a `DataExportApiClient`, a `DataExportRepository`, and a sealed `DataExportOutcome` of success / terminal-401 / retryable-error), reusing the `Auth { bearer }`-interceptor `HttpClient` — NOT a second bespoke networking pattern (anti-patchwork, docs/11 Pattern Registry; this mirrors the shipped account-deletion seam in the same screen). Its state holder SHALL resolve via `viewModel { }` at the `SettingsRoute` NavEntry scope through the existing Koin module. The response DTOs SHALL match the shipped wire shape field-for-field, parsed with `ignoreUnknownKeys`: `DataExportRequestResponse { requestId: String, status: String }` (the `POST` 202 body) and `DataExportStatusResponse { status: String, downloadExpiresAt: String? = null, downloadUrl: String? = null }` (the `GET` 200 body) — all bare camelCase (the shipped `AccountDataExportRoutes.kt` wire). The seam SHALL never log the bearer token, the `Authorization` header, the JWT `sub`, the `downloadUrl`, or request/response bodies.

#### Scenario: Data-export DTOs parse the shipped wire shape

- **WHEN** the canonical `GET /api/v1/account/export` JSON (camelCase `status`/`downloadExpiresAt`/`downloadUrl`) and `POST` JSON (camelCase `requestId`/`status`) are decoded by `DataExportStatusResponse` / `DataExportRequestResponse`
- **THEN** the fields populate correctly AND an unknown extra key does not fail the parse (`ignoreUnknownKeys`)

#### Scenario: The data-export seam resolves from Koin

- **WHEN** the Koin graph is validated for the settings module
- **THEN** the data-export state holder resolves with `DataExportApiClient` / `DataExportRepository` dependencies satisfied

#### Scenario: The data-export seam logs no token, sub, or download URL

- **WHEN** the data-export seam sources are scanned
- **THEN** no logging call site passes the bearer token, the `Authorization` header, the JWT `sub`, or the `downloadUrl` as a logged argument

#### Scenario: The signed download URL is not persisted

- **WHEN** the data-export seam + `SettingsDataExportViewModel` sources are scanned
- **THEN** no call site writes the `downloadUrl` to DataStore / preferences / disk / any cache — it is held only transiently in UI state for the open-URL hand-off

#### Scenario: A retryable error surfaces an error outcome, not a crash

- **GIVEN** a MockEngine returning `GET /api/v1/account/export` → `500`
- **WHEN** the repository maps the response
- **THEN** it emits the retryable-error outcome (no crash, no sign-in redirect)

#### Scenario: commonTest covers the data-export DTO parse, status projections, and Koin resolution

- **WHEN** inspecting the `commonTest` sources
- **THEN** tests exist for the data-export DTO parse (camelCase wire + unknown-key tolerance), the status UI-state projections over the full vocabulary (`none`/`pending`/`processing`/`ready`/`expired`/`failed`), and the data-export Koin resolution

#### Scenario: A Robolectric SettingsScreen test covers the data-export path and is Release-excluded

- **WHEN** inspecting the mobile test sources and `mobile/app/src/build.gradle.kts` test-exclude list
- **THEN** `SettingsScreenTest` covers the data-export path (row → dialog → `POST` recorded; cancel → nothing; `ready` → banner with deadline + download affordance; `401` → sign-in) AND remains named in the Release-variant test-exclude list

#### Scenario: An iOS flow test exercises the data-export path

- **WHEN** inspecting `mobile/app/src/iosTest/...`
- **THEN** an iOS flow test exists exercising the open-settings → "Unduh Data Saya" → confirm path on the simulator with Kotlin/Native-legal test function names (mirroring the existing settings iOS flow test; `commonTest`/Kotest does not run on Native)
