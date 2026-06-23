## 1. Schema (Flyway V31)

- [x] 1.1 **Re-verify the next-free Flyway version at implementation start** (in-flight siblings hold V29×2 + V30; parallel collisions are a known risk) — rename `V31__appeals.sql` if a sibling has merged a higher version since proposal.
- [x] 1.2 Write `V31__appeals.sql`: `appeals` table per design D4 (columns + CHECKs + FKs: `user_id … ON DELETE CASCADE`, `reviewed_by … ON DELETE SET NULL`), the partial-unique `appeals_one_pending_per_user` index, and the partial `appeals_pending_created_idx` index. No `NOW()` in any partial-index predicate.
- [x] 1.3 Add the migration test (schema accepts a valid row; rejects >1000-char `appeal_text`; one-pending partial-unique enforced; `reviewed_by` SET NULL on admin delete) under `@Tags("database")`. — `AppealsSchemaTest` (6 tests, green on fresh PostGIS).

## 2. Auth — ban-exempt realm (MODIFIED auth-jwt) + appeal token (MODIFIED auth-signin)

- [x] 2.1 Factor the auth-jwt validate logic so the per-request `users`-row SELECT + `token_version` + soft-delete (`deleted_at`) checks are reused; add a dedicated named "appeal" realm that skips ONLY the `is_banned` / `suspended_until` 403 short-circuit (design D1), keeping the `token_version` + soft-delete + signature gates.
- [x] 2.2 Confine limited tokens: every standard (non-appeal) realm MUST reject a token bearing `scope = "appeal"` with 401 `token_revoked` (design D2). Standard realm's `is_banned`/`suspended_until` behavior otherwise unchanged.
- [x] 2.3 MODIFY the sign-in path (`auth-signin`): a banned/suspended sign-in (currently 403 `account_banned`, no tokens) additionally returns a limited-scope `appeal_token` (RS256; `sub`; current `token_version`; `scope = "appeal"`; ≤1h TTL) in the 403 body — NO normal access/refresh token, no `refresh_tokens` row (design D2). Reuse the existing RS256 issuance.
- [x] 2.4 Tests: appeal-realm route authenticates a banned subject holding a valid appeal token; stale `token_version` → 401 `token_revoked`; no-token/invalid-sig → 401, no principal; soft-deleted subject → 401; a `scope = "appeal"` token on a standard route → 401 `token_revoked`; sign-in for a banned user returns an `appeal_token` + inserts no `refresh_tokens` row; sign-in for a non-banned user is unchanged (200 + normal tokens). — `AppealEndpointsTest` (banned-reaches-realm via the 201 cases, stale-token→401, scope-confinement→401 + normal-token→200) + `SignInFlowTest` (banned→403 with `appeal_token` + 0 refresh rows; non-banned happy path unchanged).

## 3. Backend — appeal capability (`appeal` package)

- [x] 3.1 `JdbcAppealRepository`: insert appeal (server-derived `action_type` from the `users` row), read caller's latest appeal, one-pending guard surfaced from the partial-unique violation. Bounded dispatcher; test pool `autoClose(hikari())` + size 2.
- [x] 3.2 `AppealService`: eligibility (`is_banned = TRUE`, else the uniform `no_actionable_moderation` outcome — identical for shadow-banned-only and normal users, design D3), one-pending guard, server-derived `action_type`, transaction boundary.
- [x] 3.3 Per-user submission rate-limit via the canonical Redis key `{scope:rate_appeal_day}:{user:<user_id>}` (RedisHashTagRule two-segment shape) + `computeTTLToNextReset` (design D5).
- [x] 3.4 `AppealRoutes` mounted under the appeal realm: `POST /api/v1/appeals` (length guard ≤1000 before DB; 201 on success; 409 `no_actionable_moderation` / `appeal_already_pending`; 429 on rate-limit) + own-appeal-status GET (latest status, empty result when none). DTOs colocated.
- [x] 3.5 Tests: suspended → 201 (`action_type='suspension'`); permanent-ban → 201 (`action_type='permanent_ban'`); normal user → 409 `no_actionable_moderation`; shadow-banned-only → identical 409 (byte-for-byte, no state leak); second pending → 409 `appeal_already_pending`; **concurrent submissions race the partial-unique → exactly one 201 + one 409 `appeal_already_pending` (violation mapped, never 5xx)**; client `action_type` ignored; rate-limit → 429; own-status pending/decided/none. — `AppealEndpointsTest` (10 tests; concurrent race asserted at the repo `insertPending` seam — one `Inserted` + one `PendingConflict`).

## 4. Admin — appeals-review surface (`admin-appeal-review`)

- [x] 4.1 Consult `docs/11` §3.6 + the admin board (no appeal frame yet → unstyled; adopt existing column/action/CSRF idioms from `admin-report-queue` templates).
- [x] 4.2 `GET /admin/appeals` paginated pending list + per-appeal detail (Pebble + HTMX, no-JS fallback), behind owner/admin auth.
- [x] 4.3 Approve action: single tx guarded `WHERE status='pending'` → unban statement (`is_banned=FALSE`, `suspended_until=NULL`) + appeal→`approved` + `reviewed_by`/`reviewed_at`; idempotent (re-approve affects no rows AND writes no extra audit row); CSRF (NOT counted by `admin-destructive-action-rate-limit` — approve is restorative, design D6/B2); one `admin_actions_log` row `appeal_approved`.
- [x] 4.4 Reject action: appeal→`rejected` (+ optional `decision_reason` ≤1000) leaving moderation state intact; CSRF (NOT counted by the destructive-action rate-limit — reject alters no user state); one `admin_actions_log` row `appeal_rejected`.
- [x] 4.5 Register the `appeal_approved` / `appeal_rejected` action types wherever admin action types are enumerated; re-pin the admin static-asset `SHA256SUMS` if any `admin/static/*` changes (CI-only check, not in the local gate).
- [x] 4.6 Tests (`@Tags("database")`): queue lists only pending; approve lifts suspension + writes one audit row; approve a **permanently-banned** appellant (`suspended_until IS NULL`) → `is_banned=FALSE`; approve **concurrent with the daily unban worker** (already `is_banned=FALSE`) still transitions appeal→approved, no error; re-approve is idempotent + writes **no additional** audit row; reject leaves ban intact + writes one audit row; CSRF-missing rejected; unauthenticated denied.

## 5. Mobile — appeal surface (`mobile-appeal`)

- [x] 5.1 `:shared:resources` strings: "Ajukan Banding" entry, form label/hint, char counter, pending/approved/rejected status copy, rate-limit + already-pending + error copy, permanent-ban "Hubungi support" copy (Bahasa Indonesia; CMP Resources only). — `appeal_*` keys added (reusing cta_retry / timeline_loading / timeline_session_redirect / signin_error_network).
- [x] 5.2 Data layer: `AppealApiClient` (DTOs colocated, shared `HttpClient`) + `AppealRepository` exposing sealed `AppealOutcome` (success / already-pending / rate-limited / not-eligible / transport-failure) mapping the route envelopes exactly. — `AppealApiClient`/`AppealFlow`/`AppealRepository`; the limited appeal token is attached EXPLICITLY (banned caller has no normal token → raw client, no Auth plugin); `AppealRepositoryTest` 10 green (testDevDebugUnitTest).
- [x] 5.3 `AppealScreen` + `AppealViewModel` (androidx ViewModel in commonMain, one `StateFlow<AppealUiState>`, `collectAsStateWithLifecycle`) + `AppealRoute` NavKey (`@Serializable`, registered in the polymorphic `SerializersModule`). Form (1000-char counter) + outcome→state mapping + status display. — VM + UiState + Screen + AppEntryProvider entry + DI (raw client); `AppealSession` token holder; compiles + NavKeySerializationTest green.
- [ ] 5.4 Settings: show the "Ajukan Banding" entry only in the suspended session state; permanent-ban path shows the support copy (design D7), no in-app permanent-ban form.
- [x] 5.5 Tests: Robolectric `AppealScreenTest` (submit→pending; already-pending surfaces status; rate-limited→try-later; rejected+reason display; char limit) added to the Release-variant exclude; Settings entry visibility by session state. — `AppealScreenTest` 6 green (form, submit→Pending, on-entry Pending, rate-limit copy, rejected+reason, 1000-char cap). Settings-entry visibility is part of 5.4 (banned-session wiring).

## 6. Verification & gates

- [ ] 6.1 Local gate green: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + mobile `:mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (mobile-touching).
- [ ] 6.2 verify-loop: backend + admin appeals queue (approve/reject + audit) on a local Ktor boot; mobile appeal screen on emulator/device (UI-affecting DoD — screenshot evidence of submit→pending + status display).
- [ ] 6.3 Pre-archive staging smoke: branch deploy + exercise the appeal submission (ban-exempt realm reachable) + admin approve path.
- [ ] 6.4 Confirm no `docs/11` § Pattern Registry amendment is needed (design uses only listed patterns — no second pattern introduced).

## 7. Follow-ups (file as `follow-up` issues, do not silently drop)

- [ ] 7.1 Proactive in-app/FCM notification on appeal decision (deferred per spec; the own-status read is the MVP outcome surface).
- [ ] 7.2 In-app permanent-ban appeal entry (deferred per design D7; support-email path is the MVP recourse).
- [ ] 7.3 Styled admin appeal-review mockup frame (the "sole known gap" per `docs/11` §3.6) — lands with the admin design-foundation pass.
- [ ] 7.4 Appeal-text moderation posture: `appeal_text` is user free-text reaching admins unredacted; if doxxing / third-party-PII appears, have it inherit the chat-redaction posture (`admin_chat_redaction` / `redacted_at` pattern). Filed as a `follow-up`, not built in MVP (text-only, admin-only audience).
