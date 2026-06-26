## 1. Preconditions (no backend work)

- [x] 1.1 Confirm the backend endpoints are live and the wire is unchanged: `POST`/`GET /api/v1/account/export` in `backend/ktor/.../account/AccountDataExportRoutes.kt` returning `DataExportRequestResponse { requestId, status }` (202) and `DataExportStatusResponse { status, downloadExpiresAt?, downloadUrl? }` (200). **No backend, `:infra:*`, Flyway, or `libs.versions.toml` change is made by this task list** (endpoints shipped in PR #356).
- [x] 1.2 Re-read the shipped account-deletion seam (`data/accountdeletion/*` + `screens/settings/SettingsAccountDeletionViewModel.kt` + its tests) as the field-for-field template before writing any code.

## 2. Strings (`:shared:resources`)

- [x] 2.1 Add Bahasa strings for the data-export surface (row title/subtitle, confirm-dialog title/body/confirm/cancel using the canonical copy "Export akan dikirim sebagai link download via email dalam 7 hari. Link berlaku 24 jam setelah dikirim.", the "sedang diproses" in-progress label, the ready-banner deadline + open-download labels, the expired/failed re-request labels, and the error copy) to `:shared:resources` — no hardcoded literals anywhere in the surface.

## 3. Data seam (`data/dataexport/`)

- [x] 3.1 `DataExportApiClient` — `POST`/`GET /api/v1/account/export` over the shared `Auth { bearer }`-interceptor `HttpClient`; DTOs `DataExportRequestResponse { requestId: String, status: String }` and `DataExportStatusResponse { status: String, downloadExpiresAt: String? = null, downloadUrl: String? = null }` (bare camelCase, `ignoreUnknownKeys`).
- [x] 3.2 `DataExportOutcome` — sealed success / terminal-401 / retryable-error (mirror `AccountDeletionOutcome`).
- [x] 3.3 `DataExportFlow`/`DataExportRepository` — map DTO→domain status + emit the sealed outcome; never log token / `Authorization` / JWT `sub` / `downloadUrl` / bodies.

## 4. State holder + Settings wiring

- [x] 4.1 `SettingsDataExportViewModel` — seeds status via `GET` on init; exposes an exhaustive status projection (`none`/`pending`/`processing`/`ready`/`expired`/`failed`); confirm → `POST` (suppressed while `pending`/`processing`); maps `401` → sign-in nav event, `5xx`/IO → retryable in-screen error.
- [x] 4.2 Register the seam + ViewModel in `di/MobileModule.kt`; resolve via `viewModel { }` at the `SettingsRoute` NavEntry scope.
- [x] 4.3 `SettingsScreen` — replace the deferred "Unduh Data Saya" affordance with the live row + confirmation dialog + non-blocking status banner (ready → deadline + open-`downloadUrl` affordance, reusing the existing legal-link open-URL path). All copy via `Res.string.*`.

## 5. Tests (the test trio)

- [x] 5.1 `commonTest` — DTO parse (camelCase wire + unknown-key tolerance), status UI-state projections over the full vocabulary, and the data-export Koin resolution.
- [x] 5.2 Robolectric `SettingsScreenTest` additions — row → dialog → `POST` recorded; cancel → nothing; `processing` → in-progress + no second `POST`; `ready` → banner with deadline + download affordance; `failed` → re-request issues a new `POST`; `401` on **both** the `POST` and the `GET` read → sign-in. Ensure `SettingsScreenTest` stays in the Release-variant test-exclude list in `mobile/app/build.gradle.kts`.
- [x] 5.3 `iosTest` flow — open settings → "Unduh Data Saya" → confirm, with Kotlin/Native-legal function names (mirror the existing settings iOS flow test).
- [x] 5.4 Source-guard tests — extend `SettingsSourceGuardTest` (or add a sibling) to assert (a) every user-visible string in the data-export row/dialog/banner resolves through `Res.string.*` (no hardcoded literal); (b) the data-export seam sources pass no bearer token / `Authorization` / JWT `sub` / `downloadUrl` to any logging call site — **add `downloadUrl` to the denylist explicitly** (the reused block/consent guard does not cover it); and (c) no call site persists `downloadUrl` to DataStore / preferences / disk / any cache. Maps the "No hardcoded strings in the data-export affordance", "The data-export seam logs no token, sub, or download URL", and "The signed download URL is not persisted" scenarios.

## 6. Spec sync + verification + close-out

- [x] 6.1 Verify the mobile UI/UX fundamentals (layout/insets, theming/tokens, contrast, touch targets, loading/empty/error states) per the `mobile-ui-foundation` checklist + the relevant Settings mockup frame.
- [x] 6.2 Run the local mobile gate (`./gradlew :mobile:app:testStagingDebugUnitTest` + `ktlintCheck detekt`) and a device/emulator verify of the data-export row → confirm → status flow (verify-loop); capture manual-verification evidence for the PR body (docs/11 §5 DoD).
- [ ] 6.3 On merge: close follow-up issue #362 (the deferred mobile data-export entry).
