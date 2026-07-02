## Why

The UU-PDP right to data portability is half-shipped: the backend producer (`POST`/`GET /api/v1/account/export`) landed with `account-data-export` (PR [#356](https://github.com/aditrioka/nearyou-id/pull/356)), but the mobile **"Unduh Data Saya"** Settings entry that drives it was explicitly deferred — captured as a deferred requirement in both the `account-data-export` and `mobile-settings` specs and tracked by follow-up issue [#362](https://github.com/aditrioka/nearyou-id/issues/362). The Settings screen already ships the *erasure* half (account deletion); this completes the *portability* half so the data-rights vertical slice is whole. No backend work is required — the endpoints exist and the change is a pure `:mobile:app` slice.

## What Changes

- Add a **"Unduh Data Saya"** row to the mobile `SettingsScreen` (per `docs/03-UX-Design.md` § Data Export — "Settings > Unduh Data Saya"). It is no longer a deferred "Segera hadir" row.
- Activating it presents a **confirmation dialog** with the canonical Bahasa copy ("Export akan dikirim sebagai link download via email dalam 7 hari. Link berlaku 24 jam setelah dikirim."); confirming issues `POST /api/v1/account/export`. Cancelling issues nothing.
- A **status affordance/banner** seeded on screen-open via `GET /api/v1/account/export`: `none` → request affordance only; `pending`/`processing` → non-blocking "sedang diproses" (single-active, not re-issuable); `ready` → banner with the download deadline (`downloadExpiresAt`) + an affordance to open the signed `downloadUrl` in-app; `expired`/`failed` → re-request affordance.
- Terminal `401` on either call routes to sign-in; a retryable (`5xx`/IO) error surfaces a non-trapping in-screen error (no crash, no optimistic stick).
- A new **data seam** `data/dataexport/{DataExportApiClient, DataExportFlow, DataExportOutcome}` + a `SettingsDataExportViewModel` state holder, mirroring the shipped `account-deletion` seam in the same screen field-for-field (anti-patchwork — no second networking pattern). DTOs match the shipped camelCase wire (`DataExportRequestResponse`, `DataExportStatusResponse`).
- The **test trio**: `commonTest` (DTO parse + UI-state projections for every status + Koin resolution), a Release-excluded Robolectric `SettingsScreenTest` addition, and an `iosTest` flow.
- **No backend, no Flyway, no `libs.versions.toml` change.** Closes [#362](https://github.com/aditrioka/nearyou-id/issues/362).

## Capabilities

### New Capabilities

<!-- None — this folds into the existing mobile-settings capability. -->

### Modified Capabilities

- `mobile-settings`: data export is **no longer out of scope** (it now has shipped backend endpoints) — the existing "Data export, suspension countdown, and notification chat-preview are explicitly out of scope" requirement is narrowed (suspension-countdown + chat-preview remain deferred), and new requirements add the "Unduh Data Saya" row + confirm dialog, the status banner, the data-export seam, and the test-trio addition (modelled on the shipped account-deletion requirements).
- `account-data-export`: the "Mobile Settings entry is deferred (out of scope)" requirement is updated to reflect that the mobile entry now ships (in `mobile-settings`, via this change).

## Impact

- **Module:** `:mobile:app` only (`screens/settings/*`, new `data/dataexport/*`, `di/MobileModule.kt`) + `:shared:resources` string additions. No `:backend:ktor`, no `:infra:*`, no migration.
- **APIs consumed (unchanged):** `POST /api/v1/account/export` (202), `GET /api/v1/account/export` (200) — shipped by `account-data-export`.
- **Cross-layer:** mobile-only slice of an existing backend capability; the backend + admin layers are unchanged (admin Data Export Queue is a separate in-flight admin-lane change, [#419](https://github.com/aditrioka/nearyou-id/pull/419)).
- **Follow-up closed:** [#362](https://github.com/aditrioka/nearyou-id/issues/362).
