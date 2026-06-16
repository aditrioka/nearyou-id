## 1. Backend — `editedAt` on `single-post-read`

- [x] 1.1 Add `editedAt: String? = null` (bare camelCase, like `createdAt`) to `SinglePostResponse` in `backend/ktor/.../post/SinglePostRoutes.kt`; keep the no-PII projection (no author UUID, no coordinates) and the `explicitNulls = false` omission-when-null behavior
- [x] 1.2 Extend the single-post read query/service to compute the most-recent edit time from `post_edits` (`MAX(post_edits.edited_at)` or a `LEFT JOIN LATERAL (… ORDER BY edited_at DESC LIMIT 1)`), non-null iff ≥1 `post_edits` row — derived from edit-history existence, NOT from `posts.updated_at`; leave the shipped `visible_posts` two-arm gate (visible arm + own-content self arm) and the constant `404` body unchanged
- [x] 1.3 **Visibility guardrail** — the `post_edits` derivation MUST hang off the already-resolved post row (after the `UNION ALL` / `LIMIT 1` two-arm gate), NOT a top-level/independent scalar subquery, so an invisible/blocked/shadow-banned post yields no row and thus no `editedAt` (never an edit-existence side-channel for a post the viewer can't see)
- [x] 1.4 **Lint-integrity guardrail** — add the `post_edits` subquery INSIDE the existing single triple-quoted SQL literal in `JdbcSinglePostRepository.findById` (or the leftmost element of the same `+` chain), so the four block tokens (`visible_posts`, `user_blocks`, `blocker_id =`, `blocked_id =`) stay co-located and `BlockExclusionJoinRule` still passes; keep the existing `@AllowRawPostsRead`; `post_edits` is in neither rule's pattern so it adds no new annotation — verify with `:lint:detekt-rules:test` + `detekt`
- [x] 1.5 Confirm no migration is needed (`post_edits` + `post_edits_post_id_idx` exist from V22); additive read-only change

## 2. Backend tests

- [x] 2.1 DB-tagged route test: an edited post returns `editedAt` equal to `MAX(post_edits.edited_at)`
- [x] 2.2 A never-edited post returns no non-null `editedAt` (field absent under `explicitNulls = false`)
- [x] 2.3 Wire-shape: `editedAt` serializes bare camelCase alongside `createdAt`; the existing all-camelCase negative-guard (`cityName` does not bind) and the `post_not_found` 404 byte-equality fixture still hold
- [x] 2.4 Regression: existing `single-post-read` scenarios (visibility/block/shadow-ban/own-arm/PII/casing) stay green

## 3. Mobile data layer (`data/postedit/`)

- [x] 3.1 `PostEditApiClient`: `PATCH /api/v1/posts/{id}` (request `{content}`) + `GET /api/v1/posts/{id}/edits`; `@Serializable` DTOs colocated, field names matching the wire truth (verify against the Kotlin route DTOs — `edited_at` snake per `PostEditRoutes`, not the stale spec JSON); use the shared `HttpClient` from `HttpClientFactory`
- [x] 3.2 `PostEditRepository` exposing a sealed `PostEditOutcome` (`Success` / `PremiumRequired` / `WindowExpired` / `NoChanges` / `ContentModerated` / `Conflict` / `RateLimited(retryAfter)` / `NotFound` / `Unknown`) mapping HTTP status + error code per design § D7; history fetch → typed versions list with 1-based "Versi ke-N" labels
- [x] 3.3 Koin DI module wiring the api client + repository

## 4. Mobile edit screen + navigation (`ui/postedit/`)

- [x] 4.1 `EditPostUiState` (data class: `content`, `isSubmitting`, validation flags, nullable one-shot `successContent` / `error` fields)
- [x] 4.2 `EditPostViewModel` (androidx `ViewModel`, commonMain, `koinViewModel()`): one `StateFlow<EditPostUiState>` via `stateIn(WhileSubscribed 5000)`; submit on `viewModelScope` → repository; one-shot fields cleared via `onSuccessShown()` / `onErrorShown()` (no `Channel`/`SharedFlow`)
- [x] 4.3 `EditPostScreen`: reuse the post-creation content editor (280-char counter + empty/over-length validation, content-only — no location control); `collectAsStateWithLifecycle()`; submit + per-outcome UI
- [x] 4.4 New `EditPostKey` (sealed `: NavKey`, `@Serializable`) registered in the `SavedStateConfiguration` `SerializersModule` polymorphic block; `entry<EditPostKey>` in the entry provider; navigate from the post-detail Edit affordance; pop back to detail on success

## 5. Mobile post-detail integration

- [x] 5.1 Show the Edit affordance on post-detail only for the viewer's own post within the 30-min window (`createdAt`-derived hint; backend authoritative); hidden for others / stale posts
- [x] 5.2 "Diedit [relative time]" label driven by `editedAt` — reuse the existing post-detail/post-card timestamp formatter; add a minimal local relative-time helper only if none exists (design § D6)
- [x] 5.3 "Riwayat edit" screen-local modal (bottom sheet/dialog): load `GET /…/edits`, list "Versi ke-N" chronological, loading / empty / error+retry states, render content + version + edit time only (no location)
- [x] 5.4 Reactive premium gating: `403 premium_required` → reuse `DailyCapUpsellDialog` "Aktifkan Premium" upsell (CTA consistent with the search-screen upsell); displayed content unchanged
- [x] 5.5 Error-contract → UX mapping per design § D7 (window-expired, no_changes, content_moderated, temporal "Coba lagi sebentar.", 429 + Retry-After, 404); each leaves displayed content unchanged

## 6. Strings (Compose Multiplatform Resources)

- [x] 6.1 Add Bahasa Indonesia `Res.string` entries (no hardcoded UI strings — invariant): Edit CTA, "Diedit" + relative-time, "Riwayat edit", "Versi ke-N", window-expired, no-changes, moderation-rejected, "Coba lagi sebentar.", rate-limit, not-found, empty-history, history-error/retry; reuse the existing "Aktifkan Premium" string if present

## 7. Mobile tests

- [x] 7.1 commonTest: `PostEditRepository` outcome mapping — EVERY status/code → its `PostEditOutcome`, including `400 no_changes → NoChanges` (distinct from over-length/empty), `404 → NotFound` (distinct from `409 WindowExpired`), and `429 → RateLimited(retryAfter)` with NO second `PATCH` fired before the `Retry-After` delay; history mapping → ordered "Versi ke-N" binding the snake_case wire keys (`version_label`/`content`/`edited_at`) + a camelCase negative-guard assertion (PR #128)
- [x] 7.2 commonTest: `EditPostViewModel` (success clears editor + emits success; each error outcome → correct UiState field, with `NoChanges` and `NotFound` surfacing distinct messages; client validation blocks submit with no network call; and the no-premium-pre-check path — the editor opens + `PATCH` is attempted with no client entitlement read, structurally guaranteed because no entitlement seam exists per design § D2, asserted rather than silently assumed)
- [x] 7.3 Robolectric `*ScreenTest`: `EditPostScreen` (prefilled, validation, submit); post-detail Edit-affordance visibility (own+fresh vs other / stale); "Diedit" label present iff `editedAt`; "Riwayat edit" modal (versions / empty / error+retry, **and asserts no location/coordinate is rendered for any version** — the spatial-fuzzing privacy invariant); `403` → upsell shown. Add to the Release-variant exclude; verify with `:mobile:app:testDevReleaseUnitTest`

## 8. Deferrals — tracked, not dropped

- [x] 8.1 File a `follow-up` issue (labels `follow-up`, `mobile`): timeline-card "Diedit" badge (Nearby/Following/Global) — requires the timeline DTOs/queries to carry a perf-considered edited indicator
- [x] 8.2 File a `follow-up` issue (labels `follow-up`, `mobile`): chat context-card edit-history navigation + "Post ini sudah di-edit setelah kamu chat" banner (Phase 4 item 14)
- [ ] 8.3 Resolve the mockup gap (design § Mockup gap): default to translating look-and-layout from `docs/03` § Post Edit UX + the existing post-detail/post-card frames + M3 modal patterns (the sanctioned `docs/11` § 2.8 precedence when no frame exists) and record that decision in the PR body; adding a dedicated post-edit / "Riwayat edit" frame to `dev/mockups/nearyou-screens-mockup.html` is optional polish

## 9. Verification & Definition of Done (`docs/11` § 5)

- [ ] 9.1 Pre-push gate green locally: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`
- [ ] 9.2 Mobile gate green: `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest` (run `linkDebugFrameworkIosSimulatorArm64` if `iosMain` is touched beyond NavKey registration)
- [ ] 9.3 UI-affecting → `verify-loop` bring-up with screenshot evidence in the PR body: edit own post → `200` → updated content + "Diedit"; open "Riwayat edit"; Free user `403` → upsell
- [ ] 9.4 Backend runtime impact (additive `editedAt`) → pre-archive staging branch deploy + smoke `GET /api/v1/posts/{id}` returns `editedAt` for an edited post
- [ ] 9.5 Keep PR title/body current at each phase boundary (proposal → first feat retitle `feat(mobile): …` → archive)
