## Why

The premium post-editing **backend** shipped (PR #304, V22): the `post-editing` capability exposes `PATCH /api/v1/posts/{post_id}` (author-only edit within a 30-minute window, Premium-gated, re-moderated, race-safe) and `GET /api/v1/posts/{post_id}/edits` (chronological "Versi ke-N" history, visibility-respecting, no raw location). That spec's final requirement explicitly defers the client surface: *"the deferred mobile edit/history UI (Phase 4 item 13) … remain tracked as explicit follow-up work for a later change to MODIFY."* This change is that follow-up — it makes a shipped Premium feature reachable and demoable in `:mobile:app`, advancing the Phase 4 revenue loop. Source of truth: `docs/02` § Post Edit History (Product Behavior), `docs/03` § Post Edit UX, `docs/08` Phase 4 item 13, `openspec/specs/post-editing/spec.md`.

## What Changes

- **NEW** mobile capability `mobile-post-editing` in `:mobile:app`:
  - An **Edit** affordance on the author's own post in the **post-detail** screen, shown for own posts within the 30-minute window (`createdAt`-derived; backend stays authoritative).
  - An **edit screen** (Nav3 destination) that reuses the existing post-creation content editor (280-char counter + validation), prefilled with current content, submitting `PATCH /api/v1/posts/{post_id}`.
  - **Reactive premium gating** — the established house pattern (mirrors `mobile-search` #248): the client does not read a premium flag upfront; it attempts the edit and reacts to `403 premium_required` by showing the existing `DailyCapUpsellDialog`-style "Aktifkan Premium" upsell. The full backend error contract is mapped to Bahasa Indonesia UX (`409 edit_window_expired`, `400`/`400 no_changes`, `400 content_moderated_*`, `409` temporal → "Coba lagi sebentar.", `429` + `Retry-After`, `404`).
  - A **"Diedit [relative time]" label** in post-detail, opening the **"Riwayat edit" modal** — a screen-local modal listing content versions "Versi ke-N" via `GET /api/v1/posts/{post_id}/edits`, with loading / empty / error states (content + version label + edit time only; no location).
- **MODIFIED** backend capability `single-post-read`: add a nullable `editedAt` field to `SinglePostResponse`, non-null **iff the post has `post_edits` rows** (derived from edit-history existence — not from `posts.updated_at`, which is not a reliable edited-signal). Required so the post-detail can render the "Diedit" label and decide whether to surface the history entry. Preserves the response's no-PII discipline (no author UUID, no raw lat/long).
- **Explicitly deferred** (captured as positive deferral requirements + tracking scenarios, not just prose — so a later change has something to MODIFY, and filed as `follow-up` issues):
  - Timeline-card "Diedit" badge on Nearby/Following/Global cards — deferred to avoid changing the perf-sensitive timeline queries/DTOs in this change.
  - Chat context-card edit-history navigation (`docs/08` Phase 4 item 14: "Post ini sudah di-edit setelah kamu chat" banner + version highlight in chat embeds).

## Capabilities

### New Capabilities
- `mobile-post-editing`: the `:mobile:app` surface for the shipped post-editing backend — the post-detail Edit affordance + edit screen, the reactive-403 premium gating and error-contract mapping, the "Diedit" label, and the "Riwayat edit" history modal.

### Modified Capabilities
- `single-post-read`: add a nullable `editedAt` indicator to `SinglePostResponse` (derived from `post_edits` existence) so the client can render the "Diedit" label; no new PII exposure.

## Impact

- **Mobile** (`:mobile:app`, commonMain): new `ui/postedit/` (EditPostScreen + EditPostViewModel + EditPostUiState) and `data/postedit/` (PostEditApiClient + PostEditRepository + sealed PostEditOutcome); a new NavKey registered in the Nav3 `SerializersModule`; reuse of the post-creation content editor + `DailyCapUpsellDialog`; new Bahasa Indonesia `Res.string` entries; integration touch-points in the existing post-detail screen. New commonTest (ViewModel/repository/outcome-mapping) + Robolectric `*ScreenTest` (added to the Release-variant exclude).
- **Backend** (`:backend:ktor`, `post` package): a minimal additive field on `SinglePostResponse` + its read query/service (an `EXISTS`/`MAX(edited_at)` over `post_edits`); no migration (the `post_edits` table + indexes already exist from V22). Additive and backward-compatible under `explicitNulls = false` — and therefore **inert for the existing `single-post-read` consumer** (notification deep-links into a post/reply): that path is unchanged and the new field is simply absent for never-edited posts. The `post_edits` derivation hangs off the already-resolved visible/own-content row (no edit-existence leak for a post the viewer cannot see) and stays inside the existing single SQL literal so `BlockExclusionJoinRule` remains satisfied.
- **APIs consumed** (already shipped, unchanged): `PATCH /api/v1/posts/{post_id}`, `GET /api/v1/posts/{post_id}/edits`. **API modified** (additive): `GET /api/v1/posts/{post_id}` response gains `editedAt`.
- **Invariants**: all UI strings via Compose Multiplatform Resources; no vendor SDK in `:mobile:app`; spatial-fuzzing + shadow-ban + block-exclusion enforcement remain server-side (the client only renders what the visibility-respecting endpoints return).
- **No new dependencies** — reuses existing CMP / Ktor client / Koin substrate (no `libs.versions.toml` change).
