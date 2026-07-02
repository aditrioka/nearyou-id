## Context

The UU-PDP data-portability backend (`POST` / `GET /api/v1/account/export`) shipped with `account-data-export` (PR [#356](https://github.com/aditrioka/nearyou-id/pull/356)); both the producer spec and the `mobile-settings` spec carry an **explicit deferred requirement** for the mobile entry, tracked by follow-up [#362](https://github.com/aditrioka/nearyou-id/issues/362). The `mobile-settings` Settings screen already ships the sibling **account-deletion** entry (row + confirm dialog + status banner + `AccountDeletionApiClient`/`AccountDeletionFlow`/`AccountDeletionOutcome` seam + `SettingsAccountDeletionViewModel`), which is the field-for-field precedent for this change. This is a single-layer (mobile) completion of an already-shipped backend capability — no backend, no `:infra:*`, no Flyway, no `libs.versions.toml` change.

The shipped wire (from `AccountDataExportRoutes.kt`):
- `POST /api/v1/account/export` → `202` `{ requestId: String, status: String }`
- `GET /api/v1/account/export` → `200` `{ status: String, downloadExpiresAt: String?, downloadUrl: String? }`, status ∈ `none | pending | processing | ready | expired | failed`; `downloadExpiresAt`/`downloadUrl` present only when `ready`.

## Standards conformance (docs/11 Pattern Registry)

This change builds **only** on already-registered patterns — **no new pattern, so no docs/11 § Pattern Registry amendment**:

- **State holder** (docs/11 § 2.2): `SettingsDataExportViewModel` = ViewModel + `StateFlow`, resolved via `viewModel { }` at the `SettingsRoute` NavEntry scope through the existing Koin module (`di/MobileModule.kt`) — identical to `SettingsAccountDeletionViewModel`.
- **Navigation** (docs/11 § 2.3): no new route/NavKey — the row, dialog, and banner live inside the existing `SettingsScreen` / `SettingsRoute` NavEntry (Navigation 3, the final substrate).
- **Data layer** (docs/11 § 2.6): `DataExportApiClient` (HTTP boundary over the shared `Auth { bearer }`-interceptor `HttpClient`) → `DataExportRepository`/`DataExportFlow` (DTO→domain + sealed outcome) → sealed `DataExportOutcome` (success / terminal-401 / retryable-error). This reuses the established mobile data seam the block-list, consent, hide-distance, and account-deletion seams already use — NOT a second networking pattern (anti-patchwork).
- **Strings** (critical invariant): every user-visible string via `:shared:resources` Compose Multiplatform Resources (`Res.string.*`).
- **Testing** (docs/11 § 2.7): Release-excluded Robolectric `SettingsScreenTest` additions, `commonTest` for DTO parse + projections + Koin resolution, an `iosTest` flow with Kotlin/Native-legal function names.

## Cross-layer scope (docs/12 Integration Contracts)

- **Layers spanned:** **mobile only.** This is the deferred mobile layer of an already-shipped backend capability (`account-data-export`) — the sanctioned docs/12 §3 case: the deferred layer was captured as an explicit deferred requirement in both specs (now MODIFIED/lifted here), with a tracking follow-up (#362, closed by this change).
- **Backend / admin layers:** unchanged. The admin **Data Export Queue** operator surface is a separate, in-flight admin-lane change ([#419](https://github.com/aditrioka/nearyou-id/pull/419)) building on the same `data_export_requests` table — disjoint footprint (different layer/screen, no shared files, no migration).
- **Wire contract:** no response field added or altered — the mobile DTOs consume the shipped wire field-for-field. No docs/12 §4 wire-doc change needed.

## Goals / Non-Goals

**Goals:**
- Ship the "Unduh Data Saya" Settings row + confirm dialog → `POST /api/v1/account/export`.
- Reflect request status (seeded via `GET`) as a non-blocking affordance over the full status vocabulary, including a ready-state banner with the download deadline + an in-app open-`downloadUrl` affordance.
- Close follow-up #362 and lift the two deferred requirements.

**Non-Goals:**
- Any backend / `:infra:*` / Flyway / dependency change.
- The admin Data Export Queue surface (separate change, #419).
- Suspension-countdown UI and the notification chat-preview toggle (remain deferred in `mobile-settings`).
- Re-implementing the export scope/format/delivery (owned by the backend worker).

## Decisions

- **D1 — Mirror the account-deletion seam, don't invent.** New `data/dataexport/{DataExportApiClient, DataExportFlow, DataExportOutcome}` + `screens/settings/SettingsDataExportViewModel`, 1:1 with the shipped account-deletion seam in the same screen. *Alternative considered:* fold the export calls into `SettingsViewModel` — rejected (the established pattern is a dedicated per-concern seam + state holder, and the deletion precedent already sets it).
- **D2 — Ready-state offers an in-app download affordance, not just "check your email."** The `GET` returns a freshly-signed 24h `downloadUrl` when `status = ready`; surfacing an in-app "open download" affordance **fulfills** the `account-data-export` GET requirement ("a way to obtain the current signed download URL") and is strictly better UX than email-only. The **confirm-dialog copy stays the canonical email-centric `docs/03` copy** ("…dikirim sebagai link download via email dalam 7 hari…"), so there is no docs divergence — the email remains the primary durable channel; the in-app link is the convenience path. *Alternative considered:* email-only (hide `downloadUrl`) — rejected as worse UX that discards a value the backend already returns.
- **D3 — Respect single-active.** The endpoint is idempotent/single-active server-side; the UI seeds status on open and does NOT issue a second `POST` while `pending`/`processing`. This avoids a redundant round-trip and matches the server's structural guard.
- **D4 — Status projection is exhaustive, no generic fallthrough.** Mirrors the consent/deletion mapping discipline (`401` → terminal sign-in; `5xx`/IO → retryable in-screen error; each status → an explicit UI state).
- **D5 — Docs-vs-mockup divergence: "Unduh Data Saya" is absent from mockup frame 16.** The Settings mockup (frame 16) does not include a data-export row (data export was previously fully out-of-scope, not even a deferred "Segera hadir" row) — exactly the situation already recorded for the "Hapus Akun" row. Per the mockup-consultation rule (docs/11 § 2.8 / § 3.6), I consulted frame 16 and confirmed the absence; **`docs/03-UX-Design.md` § Data Export governs behavior** ("Settings > Unduh Data Saya"), and the row is styled consistently with the screen's other LAINNYA/PRIVASI rows. This divergence is recorded here (mirroring the account-deletion precedent) so a future mockup refresh adds the frame-16 row rather than treating its present absence as a conformance miss.

## Risks / Trade-offs

- **[Koin-refactor branch overlap]** `refactor/mobile-koinviewmodel-conversion` is in flight and touches mobile ViewModel wiring broadly. → Mitigation: this change adds a *new* state holder + a new line in `di/MobileModule.kt`; if the refactor lands first, rebase the single Koin registration onto the new convention (mechanical). Low risk — no shared file beyond the DI module's registration list.
- **[Opening `downloadUrl` on a platform]** Opening a signed URL needs a platform open-URL affordance. → Mitigation: reuse the existing legal/privacy-link open-URL path already in Settings (the legal row); no new expect/actual.
- **[Date formatting]** `downloadExpiresAt` is an ISO instant string. → Mitigation: format via `:shared:resources` using the same approach the deletion restore-banner uses for `scheduled_hard_delete_at` (no new formatter pattern).

## Migration Plan

Pure additive mobile change; no migration, no rollback concern. Squash-merges independently of the in-flight claims (disjoint footprint). Deploy is the normal mobile build.

## Open Questions

None — the backend contract is shipped and stable, and the account-deletion precedent resolves every structural choice.
