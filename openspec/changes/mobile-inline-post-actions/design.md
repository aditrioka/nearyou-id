# Design: mobile-inline-post-actions

## Context

The shared `PostCard` (`ui/components/PostCard.kt`, shipped by `mobile-timeline-card-redesign`) renders a **read-only** counts row: reply icon + `replyCount`, like icon (filled + `locationPin` when `likedByViewer`). Its only interactive surface is the whole-card `onOpen` tap. All like/reply interaction routes through `PostDetailScreen`, whose like toggle is already optimistic and status-driven behind the `PostDetailFlow` seam: `PostDetailRepository.toggleLike(postId, currentlyLiked)` → `LikeApiClient` (`POST`/`DELETE /api/v1/posts/{post_id}/like`, both 204) → sealed `LikeOutcome` (`Liked` / `Unliked` / `RateLimited(retryAfterSeconds)` from the 429 `Retry-After` header / `PostGone` / `NetworkError`), wired in Koin as a stateless singleton.

The canonical mockup (docs/11 § 2.8) binds the look: frame 1's card action row (`mode_comment`+count, `send`, `favorite`+count; liked = coral, filled) and frame 18's cap dialog (M3 AlertDialog, title "Batas harian tercapai", verbatim docs/03:187 body with an "X j Y mnt" countdown, "Tutup" text button left, "Aktifkan Premium" filled button right). Constraints that shape the design:

- The timeline wire carries **no like count** (known, spec-declared mockup divergence) and the 429 carries **only `Retry-After`** (no `X-RateLimit-Reset` on the like endpoints, no body field).
- Chat is not built → the mockup's send action would be a dead control.
- No paywall screen exists → the dialog's Premium CTA has no destination yet (placeholder authorized by the operator).
- No transient-error substrate (snackbar host) exists in the app.
- docs/11 § 2 contracts: one-shot events are nullable state (not Channels); new files go in the § 2.1 target-shape packages; new test files target ComposeUiTest v2.

## Goals / Non-Goals

**Goals:**

- Inline like toggle on Nearby + Global cards, reusing the shipped like logic through a seam — one implementation, three consumers (detail, Nearby, Global).
- The frame-18 cap dialog as a shared, future-reusable `ui/components/` component with the verbatim docs/03 copy and a live countdown.
- Reply shortcut into the existing detail surface with composer autofocus.
- Explicit, hook-shaped deferrals for the send action and the paywall navigation.

**Non-Goals:**

- Numeric like count on cards; send-message action; paywall navigation (issue #235); feed↔detail like-state sync; a snackbar/transient-error substrate; migrating post-detail's cap banner to the dialog; seconds-granularity countdown; infinite scroll (#188); Following-tab actions; any backend change.

## Decisions

**D1 — Extract a `LikeFlow` seam instead of duplicating a like client.** New interface `data/like/LikeFlow.kt` (target-shape package, docs/11 § 2.1) with the single member `suspend fun toggleLike(postId: String, currentlyLiked: Boolean): LikeOutcome`; `PostDetailFlow : LikeFlow` (the method it already declares moves up); Koin adds `single<LikeFlow> { get<PostDetailRepository>() }` — the SAME singleton behind both seams. `LikeOutcome` stays in `id.nearyou.app.post` (no mechanical file moves mixed into a feature change). *Alternatives rejected*: a second like ApiClient/repository in the timeline package (duplicates the status mapping — the exact patchwork docs/11 § 4 forbids); injecting `PostDetailFlow` into timeline ViewModels (works, but names a detail-screen dependency in feeds and drags reply/list methods along — wrong surface).

**D2 — One shared inline-like controller for both feeds.** A commonMain, Compose-free helper in target-shape `ui/timeline/` owns the per-post toggle lifecycle: optimistic flip → `LikeFlow.toggleLike` → outcome application (keep / revert+cap / revert+reload / revert), plus a per-post in-flight set (re-taps on an in-flight post are ignored — no double POST). `NearbyTimelineViewModel` and `GlobalTimelineViewModel` delegate to it; the spec-level invariant is **single shared implementation** (inspection scenarios in both feed deltas). Generic mechanics (copy-lambda vs tiny interface over the two DTO types) are an apply-time detail. *Alternative rejected*: ~30 duplicated lines per ViewModel — two copies of revert/cap logic that drift.

**D3 — Cap-hit is one-shot state, not an event stream.** The controller exposes the pending cap as a nullable value (`retryAfterSeconds`-bearing) surfaced through each ViewModel as state and cleared by an `onLikeCapDialogDismissed()` callback. docs/11 § 2.2 names `Channel`/`SharedFlow` event buses the anti-pattern; the dialog shows while the field is non-null.

**D4 — The 429 dialog speaks the daily-cap copy, even though the wire can't distinguish daily from burst.** Both limiters return the same `rate_limited` + `Retry-After` shape. The 10/day Free cap is the overwhelmingly common case; the 500/h burst (both tiers, anti-bot) would show the same dialog with a ≤1 h countdown. Precedent: post-detail's shipped banner already does exactly this. *Alternative rejected*: inferring "burst" from `retryAfterSeconds ≤ 3600` — a heuristic that misfires near the daily reset window.

**D5 — Countdown: `Retry-After`-derived, hours+minutes, minute tick, auto-dismiss at zero.** docs/03:185 mandates the in-app modal countdown be "realtime to the reset moment"; frame 18 renders "14 j 19 mnt". The dialog receives the 429's `retryAfterSeconds` (the wire's only reset signal — docs/03:186 mentions an `X-RateLimit-Reset` header, but the shipped like endpoints send only `Retry-After`, which encodes the same per-user staggered reset; declared divergence, no backend change), renders "X j Y mnt" (minutes-only under an hour), decrements via monotonic `delay` (no wall-clock platform API — keeps projections pure and tests deterministic), and auto-dismisses at zero (the cap has reset). *Alternatives rejected*: static-at-open text (diverges from docs/03 "realtime"); seconds ticker (over-engineering at hour-scale waits); post-detail's coarse "%1$d jam" format (frame 18 shows minutes — the detail **banner** keeps its existing format untouched; aligning it is out of scope).

**D6 — Premium CTA is dismiss-only v1.** "Aktifkan Premium" invokes a hoisted `onActivatePremium`; the v1 host wiring closes the dialog and does nothing else — no route push, no dead navigation (negative-guard scenario). The tap has a real effect (dismissal), the copy is mandated by docs/03:187, and the operator explicitly authorized the placeholder. GitHub issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) (`mobile-paywall-screen`) tracks the destination; the paywall change MODIFIES the deferred requirement. *Alternative rejected*: omitting the CTA until the paywall exists — diverges from the verbatim docs/03 modal spec and the canonical frame.

**D7 — Reply shortcut = `focusReplyComposer: Boolean = false` on `PostDetailRoute`.** The reply affordance pushes the SAME detail route with the flag set; `PostDetailScreen` requests composer focus (IME) once on first composition when `true`. Default-`false` keeps payloads serialized before this change decoding (the `authorUsername = ""` precedent + decode-compat scenario); the NavKey is already registered in the polymorphic module — a new field with a default needs no `SerializersModule` change (docs/11 § 2.3). The "exactly once" consumption is spec'd as observable behavior; the mechanism (consume-once saveable flag or equivalent) is an apply-time detail. *Alternative rejected*: a separate `PostDetailReplyRoute` NavKey — a second route + module registration + entry mapping for one boolean of intent.

**D8 — Feed-side failure handling: self-heal on `PostGone`, silent revert on `NetworkError` (v1).** 404 means the post is gone or newly block-hidden — reverting AND triggering the ViewModel's existing `reload()` removes the stale card (self-healing, no copy needed). A transport failure reverts the flip with no message: the app has no snackbar/transient-error substrate, post-detail's banner-slot pattern has no home on a card list, and inventing a one-off banner here would fork the error-surface pattern (docs/11 § 4). The silent revert is an explicit spec scenario (declared, not accidental); a shared transient-error substrate is a candidate future `mobile-design-system` change. *Alternative rejected*: blocking the cap dialog into double duty as a generic error dialog — wrong copy, wrong weight.

**D9 — No like count on the card; send action deferred as a requirement-hook.** The wire carries no like count — the action row renders the like icon WITHOUT a number (the existing spec-declared divergence from frame 1's `favorite 12`). The send action is pinned by a deferred requirement in `mobile-post-card` (positive + negative-guard + tracking scenario); per operator instruction the requirement itself is the chat change's MODIFY hook — no tracking issue is filed for it.

**D10 — Feed↔detail like-state snapshots stay independent.** `PostDetailRoute` carries `likedByViewer` at open time; liking in detail does not retro-update the feed card (and vice versa) — the pre-existing v1 posture, now merely more visible. Safe: `POST /like` is idempotent and a re-like **releases** its rate-limit slot (post-likes § "Idempotent re-like releases the slot"), so a stale-state tap costs nothing and converges on the next refresh. A sync substrate (shared per-post store) is deliberately not introduced for this.

**Standards conformance (docs/11 § 4 Pattern Registry).** No new pattern forks: state per § 2.2 (ViewModel + StateFlow; the feed ViewModels gain fields following their existing shape; one-shot-as-state honored — D3), navigation per § 2.3 (a defaulted field on an already-registered NavKey; no new key, no module edit), data per § 2.6 (repository-seam reuse — D1; ViewModels never touch `LikeApiClient`), UI substrate per `mobile-design-system` (M3 AlertDialog, `NearYouTheme` tokens, `stringResource`-only copy). New files land in § 2.1 target-shape packages (`data/like/`, `ui/timeline/`, `ui/components/`); existing files are NOT moved. New test files target ComposeUiTest v2 (§ 2.7); extending the existing v1 `PostCardTest` is not drive-by migration. **No deviation → no docs/11 amendment task.**

## Risks / Trade-offs

- **[Optimistic mutation of retained `Loaded` outcomes]** — flipping `likedByViewer` inside the feed list state is the first per-item mutation in the timeline ViewModels; a bug could clobber the list during a concurrent refresh. → Mitigation: the mutation lives in ONE shared controller with commonTest coverage (flip, revert, in-flight, interleaved-refresh case); the server stays source of truth on every re-fetch.
- **[Reverted like with no message (D8)]** — a user on a dead connection sees the heart bounce back unexplained. → Mitigation: explicit spec scenario (deliberate v1), self-heals on retry/refresh; revisit when a transient-error substrate ships.
- **[Burst-429 shows daily copy (D4)]** — a bot-speed user sees "10 like hari ini" with a short countdown. → Accepted: anti-bot edge; countdown is still correct; precedent in the shipped detail banner.
- **[Dialog countdown drift]** — minute-granularity tick can lag the true reset by <60 s. → Accepted: docs/03 communicates the reset window coarsely ("sekitar jam 00:00–01:00 WIB"); auto-dismiss errs on the late side, and a premature tap just gets a fresh 429.
- **[Stale `likedByViewer` taps]** — feed data can be minutes old; "liking" an already-liked post sends `POST` again. → Safe by contract: idempotent 204 + slot release (D10).

## Migration Plan

No data, wire, or dependency migration. Ship sequence inside the one PR: seam extraction (compile-stable refactor, detail tests stay green) → shared controller → card action row → dialog component → feed wiring → reply-shortcut/autofocus. Rollback = revert the squash-merge commit; nothing external depends on the new surfaces.

## Open Questions

None blocking. Two deliberately parked (not for this change): when a transient-error substrate lands, revisit D8's silent revert; when the paywall ships (#235), rewire D6's CTA.
