## Context

The `reports` backend (V9) ships a complete content-report pipeline: `POST /api/v1/reports` accepts `target_type ∈ {post, reply, user, chat_message}`, enforces a 10/hour rate limit (`{scope:rate_report}:{user:<id>}`), returns `409 duplicate_report` on the `(reporter_id, target_type, target_id)` UNIQUE, and auto-hides a post/reply once 3 distinct >7-day reporters flag it (plus a `moderation_queue` row). The mobile app already exercises this endpoint — but only for `target_type = "user"`, via the profile "Laporkan" kebab shipped in `mobile-profile`. That surface already contains everything a post/reply report needs: `ReportReasonCategory` (six wire-mapped user-facing categories, in `id.nearyou.app.profile`), the sealed `ReportOutcome` (`Submitted` / `Duplicate` / `RateLimited(retryAfter)` / `NetworkError`), the repository `report(...)` call, and an M3 `AlertDialog` reason picker (`PROFILE_REPORT_DIALOG_TAG`). `docs/03 §Report UX` and `docs/06 §Report System` both specify the kebab on **post** and **reply** too; today those targets have no mobile entry point.

`PostDetailScreen` is the natural and sufficient home: it renders the post header (already showing an Edit affordance for the viewer's own post via server-authoritative `isAuthor`) and the replies list (reply rows render content + `created_at` only, dropping `author_id` for PII discipline) in a single `LazyColumn`. Reporting from detail covers 100% of reply surfaces (replies exist only in detail) and every post (any post is openable). The shared timeline `PostCard` is owned right now by the in-flight `image-attached-posts` (#354); this change deliberately avoids it.

## Goals / Non-Goals

**Goals:**
- Let a user report an individual **post** and **reply** from `PostDetailScreen`, reusing the shipped report enum, outcome model, and dialog.
- Consolidate report submission into **one** shared seam consumed by both profile and post-detail (anti-patchwork).
- Stay **mobile-only and disjoint** from the five in-flight claims — no `PostCard`, no `mobile-post-card` spec, no backend, no migration.

**Non-Goals:**
- Timeline-card (`PostCard`) report kebab (deferred behind #354 — explicit requirement + tracking issue).
- Chat-message report (`target_type = "chat_message"` — separate chat-surface change).
- Any backend, rate-limit, auto-hide, or schema change (all shipped).
- A backend self-report guard for replies (out of scope; see D4).
- Surfacing review outcomes to the reporter (forbidden by `docs/03`:234).

## Decisions

### D1 — Entry points live on `PostDetailScreen` only; the card kebab is deferred
Report affordances go on the post header (non-authored posts) and each reply row. The timeline `PostCard` kebab is **not** built here. Rationale: detail-surface entry points already deliver the full *capability* (report any post or reply) — the card kebab is a redundant *entry point*, not missing capability — and #354 currently mutates `PostCard`. Keeping our footprint off `PostCard` + `mobile-post-card` lets both PRs squash-merge in parallel with no rebase. The deferral is captured as a positive requirement + negative-guard scenario + a `follow-up` issue, so the future card change has a clean MODIFY hook. *Alternative considered:* build the card kebab now (most complete) — rejected for the parallel-landing conflict with #354; available as a fast follow-up once #354 lands.

### D2 — Extract the report-submission seam from `profile/` into shared `data/report/` + `ui/components/`
`ReportReasonCategory`, `ReportOutcome`, the report ApiClient/Repository call, and the report `AlertDialog` composable move out of `profile/` into a shared location consumed by both profile and post-detail. Rationale: this is the one report-submission pattern; duplicating it into post-detail would create the exact second-pattern drift the Pattern Registry forbids. The profile migration is **mechanical** (package decl + imports, zero logic edits) and lands in the same PR with the full mobile test gate green — the existing `ReportReasonCategoryTest` + profile report tests are the regression oracle. *Alternative considered:* leave the seam in `profile/` and have post-detail depend on `profile` internals — rejected (post-detail → profile coupling + still effectively two call sites of an un-shared seam).

### D3 — `Duplicate` (409) maps to the **same success toast** as `Submitted`
Both terminal-success outcomes render "Laporan terkirim. Tim moderasi akan meninjau." Rationale: a distinct "you already reported this" message leaks that a prior report exists, enabling enumeration/retaliation; `docs/03`:234 mandates no reporter-visible outcome. This mirrors the shipped profile behavior. `RateLimited` surfaces a rate-limit message (the action genuinely did not record); `NetworkError` is retryable.

### D4 — Affordance gating: post by `isAuthor`; reply ungated
The post report affordance shows only when `!isAuthor` (mirrors the Edit gate). Reply rows intentionally drop `author_id` (PII discipline), so the client cannot tell which reply is the viewer's own → the reply report affordance shows on **all** replies. The backend `self_report_rejected` guard fires only for `target_type = "user"`, so reporting one's own reply would create a (harmless, rare) report row. Accepted in v1 rather than regressing PII discipline (sending `author_id` to gate the UI) or adding a backend guard (out of scope). Recorded as a deferred requirement.

### D5 — One-shot toast/dialog result is nullable `UiState`, cleared via `onXxxShown()`
The submission result (toast text, dialog dismissal) is modeled as nullable fields on the single `StateFlow<…UiState>`, consumed and cleared by an `onReportResultShown()` callback — **not** a `Channel`/`SharedFlow` event bus (docs/11 §2.2). Consistent with the rest of the app.

### D6 — Visual source of truth is the shipped profile report dialog (no dedicated mockup frame)
`dev/mockups/nearyou-screens-mockup.html` has no post/reply-report frame (only the admin report-queue frames + an unrelated Sentry toggle). Per docs/11 §2.8 the mockup governs look/layout, but where no frame exists the canonical look is the **shipped** profile report dialog — reused verbatim. The §2.8 "consult the matching frame before building" rule is satisfied by reusing the already-design-system-conformant component; the kebab/overflow placement on the post header follows the existing Edit-affordance treatment.

### Standards conformance (docs/11 §2)
Builds on existing Pattern-Registry patterns, introduces **no** new one:
- **State holder (§2.2):** `PostDetailViewModel` (existing) gains report state on its single `StateFlow<UiState>`; one-shot result as nullable-field-cleared-via-callback.
- **Data layer (§2.6):** the sealed `ReportOutcome` at the repository boundary + the single shared `HttpClient`; reused, relocated to `data/report/`.
- **Components/layout (§2.1):** the shared report dialog lives in `ui/components/` (reuse-first); the ApiClient+Repository in `data/report/`.
- **Testing (§2.7):** `commonTest` for pure mappings + ViewModel; Robolectric `*ScreenTest` for affordances/dialog, added to the Release-variant exclude.
- **Visual (§2.8):** reuse of the shipped dialog (D6).

Because this **reuses and consolidates** the existing report-submission pattern (rather than introducing a parallel one for the same concern), **no docs/11 § Pattern Registry amendment is required**. If review judges the extracted shared report seam a registry-worthy entry, a one-line Pattern-Registry note can be added — flagged as an open question, not assumed.

## Risks / Trade-offs

- **Mechanical profile-seam extraction silently breaks the profile report** → land the move + both consumers in one commit; gate on the full mobile test suite (`:mobile:app:testDevReleaseUnitTest` + `testStagingDebugUnitTest`); the existing `ReportReasonCategoryTest` and profile report screen tests must stay green unchanged.
- **Self-reporting one's own reply succeeds** (D4) → harmless (admin sees a self-report; auto-hide needs 3 *distinct* reporters so a self-report can't hide one's own content alone). Tracked as a deferred requirement; promote to a backend guard only if abuse appears.
- **`image-attached-posts` (#354) and this PR both reach `PostDetailScreen`?** → #354's scope is post **creation** + card image rendering; this touches post **detail**. If #354 also edits `PostDetailScreen`, the overlap is small and mechanical. The hard-disjoint guarantee is on `PostCard`/`mobile-post-card`, which this change never touches.
- **Reply rows have no existing kebab/overflow** → adding one per row must respect list performance (§2.4: stable `key`/`contentType` already on the replies `items()`); the affordance is a static trailing icon, no per-recomposition derived state.

## Migration Plan

Pure additive UI + a mechanical in-module refactor. No database, no feature flag (a UI affordance over a shipped, rate-limited endpoint goes live on merge). Rollback = revert the PR; nothing persists server-side beyond the reports the endpoint already accepts from the profile surface today.

## Open Questions

- **Reply report affordance form** — trailing kebab icon vs overflow menu vs long-press. A look detail, not behavior; resolve at apply-time by mirroring the post-header Edit/overflow treatment and the profile dialog. Non-blocking.
- **Pattern Registry note for the shared report seam** — whether the extracted seam warrants an explicit docs/11 § Pattern Registry line. Defaulting to "no" (it's a reuse/relocation, not a new pattern); flag for reviewer confirmation.
