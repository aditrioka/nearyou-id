## RENAMED Requirements

- FROM: `### Requirement: Account deletion, data export, suspension countdown, and notification chat-preview are explicitly out of scope`
- TO: `### Requirement: Data export, suspension countdown, and notification chat-preview are explicitly out of scope`

## MODIFIED Requirements

### Requirement: Data export, suspension countdown, and notification chat-preview are explicitly out of scope

This `mobile-settings` capability SHALL NOT implement data export ("Unduh Data Saya"), a suspension-countdown surface, or the notification chat-preview toggle. Each lacks a backend endpoint (data export is blocked on `:infra:resend` modularisation; suspension is surfaced only at the auth/write-403 boundary with no client read path; the chat-preview toggle has no endpoint) — shipping any now would ship a dead control. **Account deletion ("Hapus Akun") is NO LONGER out of scope** — as of `account-deletion-tombstone` it has a real backend (`POST` / `DELETE` / `GET /api/v1/account/deletion-request`) and is shipped per the "Settings offers account deletion" requirement below. Each still-deferred surface SHALL be tracked by a follow-up GitHub issue (label `follow-up` + `mobile`).

#### Scenario: No data-export, suspension, or chat-preview control is rendered
- **WHEN** `SettingsScreen` is rendered and its tree inspected
- **THEN** it contains no "Unduh Data Saya", suspension-countdown, or notification chat-preview control (these surfaces remain deferred, not shipped as dead rows) — but it DOES contain the account-deletion ("Hapus Akun") affordance (see the account-deletion requirement)

## ADDED Requirements

### Requirement: Settings offers account deletion with a 30-day grace and in-grace restore

`SettingsScreen` SHALL render a destructive-styled "Hapus Akun" affordance (this row is intentionally absent from mockup frame 16 — a docs-vs-mockup divergence recorded in `design.md`; `docs/03-UX-Design.md` § Account Deletion governs behavior: "Hapus Akun button in Settings"). Activating it SHALL present a confirmation dialog (all copy via `:shared:resources`) explaining the 30-day grace and that the account can be restored within the window; confirming SHALL issue `POST /api/v1/account/deletion-request` through the standard mobile data seam (an `AccountDeletionApiClient` → `AccountDeletionRepository` → sealed outcome, per docs/11 § 2.6 — NOT a second bespoke networking pattern). A terminal `401` SHALL route to the sign-in surface; a retryable error SHALL surface a non-trapping in-screen error. No user-visible UI string SHALL be a hardcoded literal.

#### Scenario: Confirming deletion issues the request
- **GIVEN** `SettingsScreen` over a MockEngine recording requests, with the Hapus Akun confirmation dialog shown
- **WHEN** the confirm affordance is activated and the server responds success
- **THEN** exactly one `POST /api/v1/account/deletion-request` request was recorded AND a scheduled-deletion state is reflected in the UI

#### Scenario: Cancelling the confirmation issues nothing
- **GIVEN** the Hapus Akun confirmation dialog shown
- **WHEN** the cancel affordance is activated
- **THEN** no outbound request is recorded and the dialog dismisses

#### Scenario: A 401 on the deletion request routes to sign-in
- **GIVEN** a MockEngine returning `POST /api/v1/account/deletion-request` → `401`
- **WHEN** the user confirms deletion
- **THEN** a navigation event routing to the sign-in surface is emitted

#### Scenario: No hardcoded strings in the deletion affordance
- **WHEN** the account-deletion affordance + dialog sources are scanned for user-visible text
- **THEN** every label/title/body/confirm/cancel resolves through `Res.string.*`

### Requirement: A pending deletion shows a non-blocking restore banner

When the account has a pending deletion (`GET /api/v1/account/deletion-request` reports a scheduled hard-delete), `SettingsScreen` SHALL show a **non-blocking** banner stating the account is scheduled for deletion and the restore-by date (`scheduled_hard_delete_at`, formatted via `:shared:resources`), with a "Batalkan" action that issues `DELETE /api/v1/account/deletion-request` to restore. The account is otherwise fully functional during grace (the deletion request is NOT a suspension — see `account-deletion`). A successful cancel SHALL clear the banner; a failed cancel SHALL keep the banner and surface a non-trapping error (no optimistic clear).

#### Scenario: Pending deletion renders the restore banner with the deadline
- **GIVEN** a MockEngine returning `GET /api/v1/account/deletion-request` → a pending schedule with a `scheduled_hard_delete_at`
- **WHEN** `SettingsScreen` is rendered
- **THEN** a non-blocking banner is shown referencing the restore-by date AND a "Batalkan" action is present

#### Scenario: Batalkan restores the account and clears the banner
- **GIVEN** the restore banner shown and a MockEngine recording requests
- **WHEN** "Batalkan" is activated and the server responds success
- **THEN** exactly one `DELETE /api/v1/account/deletion-request` was recorded AND the banner is cleared

#### Scenario: A failed cancel keeps the banner
- **GIVEN** the restore banner shown and a MockEngine returning `DELETE /api/v1/account/deletion-request` → `500`
- **WHEN** "Batalkan" is activated
- **THEN** the banner remains AND a non-trapping error is surfaced (no optimistic clear, no crash)

#### Scenario: No pending deletion renders no banner
- **GIVEN** a MockEngine returning `GET /api/v1/account/deletion-request` → no pending schedule
- **WHEN** `SettingsScreen` is rendered
- **THEN** no scheduled-deletion banner is shown

### Requirement: The account-deletion data seam follows the established ApiClient → Repository → sealed-Outcome pattern

The account-deletion request / cancel / status calls SHALL be implemented behind the project's standard mobile data seam (an `AccountDeletionApiClient`, an `AccountDeletionRepository`, and a sealed `AccountDeletionOutcome` of success / terminal-401 / retryable-error), reusing the `Auth { bearer }`-interceptor `HttpClient` — NOT a second bespoke networking pattern (anti-patchwork, docs/11 Pattern Registry). Its state holder SHALL resolve via `viewModel { }` at the `SettingsRoute` NavEntry scope through the existing Koin module. The seam SHALL never log the bearer token, the JWT `sub`, or request/response bodies.

#### Scenario: The deletion seam resolves from Koin
- **WHEN** the Koin graph is validated for the settings module
- **THEN** the account-deletion state holder resolves with `AccountDeletionApiClient` / `AccountDeletionRepository` dependencies satisfied

#### Scenario: The deletion seam logs no token or sub
- **WHEN** the account-deletion seam sources are scanned
- **THEN** no logging call site passes the bearer token, the `Authorization` header, or the JWT `sub` as a logged argument
