# Proposal: mobile-inline-post-actions

## Why

Today the timeline cards are read-only: every like or reply costs an extra hop through `PostDetailScreen` (the v1 deferral recorded in `mobile-post-detail` § "Inline-card like and reply shortcuts are deferred", tracked as GitHub issue [#201](https://github.com/aditrioka/nearyou-id/issues/201)). The backend side of the loop is fully shipped — `post-likes` (idempotent POST/DELETE, V7), the like rate limit (10/day Free + 500/h burst, `Retry-After`), and `post_liked` notifications — and the shared `PostCard` from `mobile-timeline-card-redesign` (merged, [PR #221](https://github.com/aditrioka/nearyou-id/pull/221)) is the single seam both feeds render through. Un-deferring the inline actions now closes the core-engagement friction on the demo path (mobile-first priority) with zero backend work. Closes #201.

## What Changes

- **Inline like on the shared post card (Nearby + Global)**: the card's read-only counts row becomes the mockup frame-1 action row — a reply affordance (icon + `replyCount`) and a like affordance (filled + `locationPin` coral when liked, outlined + muted otherwise). The like tap toggles optimistically and **reuses the shipped post-detail like logic** via a `LikeFlow` seam extracted from `PostDetailFlow` — the same `PostDetailRepository`/`LikeApiClient` singleton; no second like client. One shared inline-like controller serves both feed ViewModels (no per-feed duplication).
- **Free like-cap dialog (mockup frame 18)**: a like rejected with HTTP 429 reverts the optimistic flip and opens a new shared M3 `AlertDialog` component with the **verbatim `docs/03-UX-Design.md:187` copy** ("Kamu sudah menggunakan 10 like hari ini…" — the existing `post_detail_likes_cap_upsell` string already carries it), a countdown to the per-user reset derived from `Retry-After` (hours + minutes, ticking per minute, auto-dismiss at zero), CTA "Aktifkan Premium" (primary) + "Tutup" (secondary). The Premium CTA is a **dismiss-only placeholder** — paywall navigation is an explicit deferred requirement tracked by issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) (`mobile-paywall-screen`).
- **Reply shortcut**: tapping the reply affordance pushes the existing `PostDetailRoute` with a new `focusReplyComposer: Boolean = false` field; `true` autofocuses the reply composer (IME up) once on entry. Default-`false` keeps previously-serialized back stacks decoding (the `authorUsername = ""` precedent).
- **Send-message action NOT rendered**: mockup frame 1 shows a third (kirim pesan) action; chat is not built, and the no-dead-controls rule forbids rendering it. An explicit deferred requirement (positive + negative-guard + tracking scenario) in `mobile-post-card` is the future chat change's MODIFY hook, with backlog visibility via issue [#238](https://github.com/aditrioka/nearyou-id/issues/238).
- **Failure handling on the feed**: 404 `PostGone` → revert + silent `reload()` (self-heal: the post is gone/hidden, the refresh drops it); `NetworkError` → revert with **no error surface in v1** (no transient-error substrate exists in the app — declared deferral, see design.md D8).

Out of scope (explicit non-goals): a numeric like count on cards (the timeline wire carries none — the known mockup divergence stays declared); paywall navigation (#235); feed↔detail like-state sync (snapshots stay independent; safe because the like endpoints are idempotent and a re-like releases its rate-limit slot); a transient-error (snackbar) substrate; migrating post-detail's cap **banner** to the new dialog; a realtime-seconds countdown; infinite scroll (#188); Following-tab actions (no feed yet); **any backend change**.

## Capabilities

### New Capabilities

- `mobile-cap-upsell-dialog`: the shared daily-cap upsell dialog component (`ui/components/`, M3 AlertDialog per mockup frame 18) — verbatim docs/03 copy, `Retry-After`-derived ticking countdown, "Aktifkan Premium" / "Tutup" CTAs, deferred paywall navigation. Body copy is parameterized because frame 18's caption declares the same modal pattern for the future post/reply/chat caps; this change instantiates it for likes only.

### Modified Capabilities

- `mobile-post-card`: the read-only counts row becomes the interactive action row (reply + like affordances, hoisted callbacks, a11y labels); the whole-card-tap requirement admits exactly these two additional tap targets; a new deferred requirement pins the absent send-message action.
- `mobile-nearby-timeline`: the card delta gains the inline-like and reply-shortcut wiring; new requirements for the optimistic status-driven inline like (shared seam, in-flight guard, outcome handling) and the 429 cap dialog.
- `mobile-global-timeline`: mirror of the Nearby deltas (Global flavor, `distanceM = null`), sharing the SAME controller + `LikeFlow` singleton.
- `mobile-post-detail`: the "Inline-card like and reply shortcuts are deferred" requirement is REMOVED (un-deferred by this change); `PostDetailRoute` gains `focusReplyComposer` (default `false`, decode-compat); a new autofocus-on-entry requirement; the Koin requirement now binds the extracted `LikeFlow` seam (`PostDetailFlow : LikeFlow`, same singleton).
- `mobile-home-tab-host`: the `onOpenPost` call-site wiring requirement grows the reply-shortcut path (`PostDetailRoute(focusReplyComposer = true)` from the reply affordance, `false` from whole-card opens; Following tab still wires nothing).

## Impact

- **Code** (`:mobile:app` + `:shared:resources` only): `ui/components/PostCard.kt` (action row), new `ui/components/DailyCapUpsellDialog.kt`, new `data/like/LikeFlow.kt` (seam interface; `LikeOutcome` stays in `id.nearyou.app.post` — no mechanical moves), new shared inline-like controller in target-shape `ui/timeline/`, `NearbyTimelineViewModel` / `GlobalTimelineViewModel` (+ their screens), `screens/post/PostDetailScreen.kt` (autofocus), `screens/routing/NavKeys.kt` (`focusReplyComposer`), `screens/routing/AppEntryProvider.kt` + `screens/shell/AppShellScreen.kt` + `screens/home/HomeScreen.kt` (reply-shortcut wiring/forwarding), `di/MobileModule.kt` (`single<LikeFlow>`), `strings.xml` (new: `cap_dialog_title`, `cta_activate_premium`, `cap_countdown_hours_minutes`, `cap_countdown_minutes`, action-row content descriptions; reused: `post_detail_likes_cap_upsell`, `cta_close`).
- **APIs**: none added or changed — consumes the shipped `POST/DELETE /api/v1/posts/{post_id}/like` exactly as post-detail does. No new wire fields (notably: no like count).
- **Dependencies**: none (no `libs.versions.toml` touch).
- **Specs**: 1 ADDED capability, 5 MODIFIED capability deltas (above).
- **Tests**: PostCardTest extensions; new `DailyCapUpsellDialogTest` (Robolectric, Release-exclude); commonTest for the shared controller, countdown formatter, `PostDetailRoute` decode-compat; ViewModel tests for both feeds; PostDetailScreen autofocus scenarios; tab-host wiring scenario.
- **Risk**: low-moderate — UI + state-holder work behind shipped endpoints; the riskiest seam (optimistic mutation of retained `Loaded` outcomes) is isolated in one shared controller with commonTest coverage.
