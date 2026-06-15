## Context

The `post-editing` backend shipped (PR #304, V22): `PATCH /api/v1/posts/{post_id}` + `GET /api/v1/posts/{post_id}/edits`, with the `post_edits` table + `(post_id, edited_at)` indexes. That capability's last requirement explicitly defers the mobile UI ("Phase 4 item 13 … for a later change to MODIFY"). This change builds that UI in `:mobile:app` and adds the one backend signal the UI needs (an edited indicator on the by-id read).

Constraints in play: the mobile architecture contract (`docs/11` § 2), the reactive-gate house pattern established by `mobile-search` (#248), the reuse-first rule (`docs/11` § 4), the spatial-fuzzing / shadow-ban / block invariants (all enforced server-side — the client only renders the visibility-respecting endpoints' output), and the Compose-Multiplatform-Resources string rule.

## Goals / Non-Goals

**Goals:**
- Make the shipped Premium post-editing backend reachable and demoable from post-detail: edit own recent post, see the "Diedit" label, open the "Riwayat edit" history modal.
- Map the backend's full error contract to clear Bahasa Indonesia UX, including the reactive Premium upsell on `403`.
- Add the minimal, additive backend signal (`editedAt` on `single-post-read`) the label requires, with no migration and no new PII.
- Conform to the existing mobile patterns (state holder, Nav3, data layer) — consume the skeleton, don't fork it.

**Non-Goals:**
- No timeline-card "Diedit" badge (would touch the perf-sensitive timeline queries/DTOs) — deferred, tracked.
- No chat context-card edit-history navigation / "diedit setelah kamu chat" banner (Phase 4 item 14) — deferred, tracked.
- No change to the backend edit/history *business* rules (window, moderation, race-safety, rate-limit) — those shipped in #304 and are consumed as-is.
- No new library/substrate (`libs.versions.toml` unchanged).

## Decisions

### D1 — The edited signal is `editedAt` on `single-post-read`, derived from `post_edits` existence

`SinglePostResponse` gains a nullable `editedAt`, non-null iff the post has `post_edits` rows, computed as `MAX(post_edits.edited_at)`. Rationale: the "Diedit" label and the decision to surface the history entry need a per-post edited signal in the same round-trip that renders post-detail; an `EXISTS`/`MAX` over the already-indexed `post_edits` (`post_edits_post_id_idx` on `(post_id, edited_at DESC)`) is cheap on a by-id read and is additive (no migration).
- **Alternative rejected — derive from `posts.updated_at`:** semantically wrong and fragile. `updated_at` is a generic mutation timestamp; coupling a user-facing "edited" claim to it risks false positives if any future write touches `updated_at` for a non-edit reason. The audit-trail truth is the existence of a `post_edits` row.
- **Alternative rejected — client calls `GET /…/edits` to detect edits:** an extra round-trip per post-detail open just to decide whether to show a label, and impossible for the (deferred) timeline-card case. The label needs a flag in the post projection.

### D2 — Premium gating is reactive on the `403`, not a client-side flag

The flow attempts the `PATCH` and reacts: `403 premium_required` → the shared "Aktifkan Premium" upsell. This mirrors the shipped `mobile-search` gate (`403 → SearchUiState.PremiumGate`) and reuses the existing `DailyCapUpsellDialog` component.
- **Alternative rejected — read a current-user premium flag upfront to hide/disable Edit:** there is no shipped current-user entitlement signal on the client today (the search screen gates reactively; `is_premium` exists only on *other* users' chat/profile rows). Inventing one here would couple this change to the unmerged paywall's RevenueCat entitlement seam (#309) and fork the established reactive pattern. Reactive gating is correct and dependency-free.

### D3 — Edit screen is a Nav3 destination; the history modal is screen-local

The editor is a screen-level destination → a new `NavKey` (sealed interface `: NavKey`, `@Serializable`), registered in the app's `SavedStateConfiguration` `SerializersModule` polymorphic block (KMP-mandatory for iOS state restoration, `docs/11` § 2.3). The "Riwayat edit" history view is a transient modal over post-detail → screen-local state (modal bottom sheet / dialog), **not** a NavKey, per the tabs/modals rule (the back stack holds screen-level destinations only).

### D4 — Reuse, don't rebuild (rule-of-three, `docs/11` § 4)

Reuse the post-creation content editor (the 280-char text field + counter + empty/over-length validation) rather than a parallel editor; reuse `DailyCapUpsellDialog` for the Premium upsell. At apply time, scan `ui/components/` + the post-creation feature for the existing editor seam; extract a shared content-editor composable only if creation embeds it inline (parameterize content-only — the editor must not surface location capture in the edit context).

### D5 — V1 surfaces editing in post-detail only; two surfaces deferred as explicit requirements

The edit affordance, "Diedit" label, and history modal all live in post-detail (where `editedAt` is available and the audit-trail UX belongs). The timeline-card badge and the chat context-card edit-nav are deferred as **positive deferral requirements** (with tracking scenarios) in the spec, plus filed `follow-up` issues — so a later change has a concrete requirement to MODIFY (the project's defer-as-requirement convention) rather than rediscovering the gap.

### D6 — Relative-time rendering of `editedAt`

The "Diedit [relative time]" label renders `editedAt` via the app's existing timestamp formatting. At apply time, reuse whatever post-detail/post-card use to render `createdAt`; if no shared relative-time helper exists yet (a shared relative-timestamp formatter is an unshipped polish follow-up), add a minimal local formatter rather than blocking on it. See Open Questions.

### D7 — Backend error contract → UX mapping

The repository surfaces a sealed `PostEditOutcome`; the ViewModel maps each to a UiState field:

| Backend response | `PostEditOutcome` | UX |
|---|---|---|
| `200` | `Success(updatedContent)` | return to detail, show updated content + "Diedit" |
| `403 premium_required` | `PremiumRequired` | "Aktifkan Premium" upsell |
| `409 edit_window_expired` | `WindowExpired` | window-passed message |
| `400` length/empty | (client-validated pre-submit) | inline editor validation |
| `400 no_changes` | `NoChanges` | "no changes to save" |
| `400 content_moderated_*` | `ContentModerated` | moderation-rejected message |
| `409` temporal-collision | `Conflict` | "Coba lagi sebentar." |
| `429` (+ `Retry-After`) | `RateLimited(retryAfter)` | rate-limit message; no silent retry before the hint |
| `404` | `NotFound` | generic not-found |

### Standards conformance (`docs/11` § Pattern Registry)

This change **builds on the canonical patterns with no deviation**, so it requires **no `docs/11` amendment**:
- **State (§ 2.2):** `EditPostViewModel` (androidx `ViewModel`, commonMain, `koinViewModel()`) exposing one `StateFlow<EditPostUiState>` via `stateIn(WhileSubscribed 5000)`, collected with `collectAsStateWithLifecycle()`; one-shot events (edit-success, error) modeled as nullable `EditPostUiState` fields cleared via `onXxxShown()` — no `Channel`/`SharedFlow` event bus.
- **Navigation (§ 2.3):** edit screen as a registered `NavKey`; history as screen-local modal (D3).
- **Data layer (§ 2.6):** `PostEditApiClient` (HTTP + colocated DTOs) + `PostEditRepository` (sealed `PostEditOutcome`); the shared `HttpClient` from `HttpClientFactory`; DTO field names match the wire truth (`editedAt` bare camelCase like `createdAt`; verify against the Kotlin route DTOs, not the stale spec JSON examples).
- **Package shape (§ 2.1):** new code under `ui/postedit/` + `data/postedit/` (target shape), not the legacy flat `screens/`.
- **Reuse-first (§ 4):** D4.
- **Backend (§ 3.1):** the `editedAt` addition stays in the `post` read service/route (thin route → service → repository); no SQL in routes.

### Mockup gap (`docs/11` § 2.8)

`dev/mockups/nearyou-screens-mockup.html` has **no** post-edit / "Riwayat edit" frame (only "Edit Profil" + the new-post FAB). Per § 2.8 precedence (specs + `docs/02`/`docs/03` govern behavior; mockups govern look), this change translates from `docs/03` § Post Edit UX + the existing post-detail/post-card frames + M3 modal/bottom-sheet patterns. A `tasks.md` item optionally adds a frame to the board; absent that, the behavioral spec + existing frames are the reference. This is flagged, not silently followed-or-skipped.

## Risks / Trade-offs

- **Client window affordance vs clock skew** → the 30-min affordance hint is computed from device time; a boundary case can surface Edit and then receive `409 edit_window_expired`. Mitigation: the error mapping (D7) handles `409` gracefully as a first-class outcome, not a crash — backend stays authoritative.
- **`editedAt` subquery cost on the by-id read** → a `MAX`/`EXISTS` over `post_edits` (already indexed) on a single-row read is negligible; not the hot timeline path. Mitigation: implement as a `LEFT JOIN LATERAL (… ORDER BY edited_at DESC LIMIT 1)` or a scalar subquery, measured against the existing single-post query.
- **Reusing the creation editor** → if creation embeds the editor inline with creation-only concerns (location), reuse requires a small extraction. Mitigation: extract a content-only editor composable (D4) under the rule-of-three; keep the change's LOC honest.
- **String/relative-time coupling** → see D6/Open Questions; mitigated by a local fallback formatter.

## Open Questions

1. **Relative-time helper**: does a shared relative-timestamp formatter already exist (or does post-detail format `createdAt` inline)? Resolve at apply by scanning `ui/components/` + post-detail; reuse if present, else add a minimal local helper (D6). Not a blocker.
2. **Content-editor reuse seam**: is the 280-char editor already a shared composable, or embedded in `PostCreationScreen`? Resolve at apply (D4); extract content-only if needed.
3. **Edit entry placement within post-detail**: overflow menu vs inline button — a look-and-layout detail to settle against the post-detail frame + M3 conventions at apply time (behavioral spec is placement-agnostic).
