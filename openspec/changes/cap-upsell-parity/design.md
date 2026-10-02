## Context

The paywall (`mobile-paywall`, #309) is reachable today from four places: the feed like-cap dialog, the search `403` gate, the username gate, and the composer image-attach gate. Every other Free limit dead-ends:

| Surface | Backend signal (already shipped) | Client today |
|---|---|---|
| Chat send (thread + share picker) | `429 rate_limited` + `Retry-After`, Free-only (`ChatRoutes.kt`, Premium skips) | `SendOutcome.Error` → `NetworkRetry`, never rendered; the bubble vanishes. Picker: generic "failed" snackbar |
| Post-detail like | `429` + `Retry-After` (Free daily cap; plus a 500/h both-tier burst) | Red "N jam" banner, no CTA |
| Post-detail reply | `429` + `Retry-After`, Free-only | Red "N jam" banner, no CTA |
| Composer | `429 rate_limited` + `Retry-After`, Free-only (`PostRateLimiter`) | `post_create_error_rate_limited` banner |
| Post edit | `403 premium_required` | Upsell dialog whose CTA only dismisses |
| Nearby radius | client snap-back + `403 radius_premium_only` | Opens the paywall as `LIKE_CAP` |

Frame 18 (`dev/mockups/nearyou-screens-mockup.html`) declares one modal pattern for "batas post 10/hari, balasan 20/hari, chat 50/hari". `DailyCapUpsellDialog` already takes a caller-supplied body, so the component needs no change.

## Goals / Non-Goals

**Goals:**
- Every Free daily-cap `429` (like, reply, post, chat) shows the frame-18 dialog with a live countdown and an "Aktifkan Premium" CTA that opens the paywall.
- Every Premium gate opens the paywall with an entry naming that gate, and the hero headline matches the gate.
- The paywall advertises only features that ship.

**Non-Goals:**
- **#494**: chat NetworkRetry / TooLong rendering, pull-to-refresh, the notification-prompt persistence. This change only adds the rate-limited state and leaves `NetworkRetry` / `TooLong` exactly as they are.
- Moving the post-detail like/composer state from composition-local `remember` into `PostDetailViewModel`. This is a pre-existing posture, noted in that screen's KDoc.
- An icon slot on `DailyCapUpsellDialog`. Frame 18 shows a `favorite` glyph above the title; the shipped component and its spec have none. That is a pre-existing divergence, unchanged here.
- Advertising image upload on the paywall (Month 6, flag default `false`).
- Making the cap numbers in the copy follow the Remote Config overrides (see Risks).

## Decisions

### D1: One dialog component, a per-cap body

All four caps use `DailyCapUpsellDialog` with a different `body`:
- like: existing `post_detail_likes_cap_upsell`, the docs/03 verbatim copy;
- reply: existing `post_detail_reply_cap_upsell`;
- post: new `post_create_cap_upsell`;
- chat: new `chat_cap_upsell`.

The new copy follows the like sentence's shape, and docs/03 § Rate Limit Communication gains all three lines, so the copy is canonical rather than ad hoc.

*Alternative:* per-cap dialog composables. Rejected. That would add a second pattern for the same frame, and the component was built with the body as its reuse seam.

### D2: The surface names its entry when it hosts more than one gate

Surfaces with two gates take `onActivatePremium: (PaywallEntry) -> Unit` and pass the entry for the gate that fired:
- the Home path (`AppShellScreen` → `HomeScreen` → the three timeline screens): like cap + radius;
- post-detail: like + reply;
- the composer: image attach + post cap.

The host's wiring collapses to `{ entry -> backStack.add(PaywallRoute(entry)) }`.

Single-gate surfaces keep `() -> Unit`, and the host picks the entry:
- search → `SEARCH_GATE`;
- username → `USERNAME`;
- edit → `EDIT_GATE`;
- chat thread and share picker → `CHAT_CAP`.

Screens stay navigation-free, holding no back-stack reference.

*Alternative:* one extra lambda per gate (e.g. `onActivateRadiusPremium` threaded through three layers). Rejected: the lambdas multiply per gate. *Alternative:* make every surface `(PaywallEntry) -> Unit`. Rejected as churn: a single-gate surface has nothing to choose.

Existing `{ activated++ }` test lambdas still compile, because the parameter is implicit.

### D3: Dialog visibility is the existing one-shot state

Each surface reuses the state it already owns. No new event stream (docs/11 §2.2):

- **Chat thread**: `ChatThreadViewModel.sendOutcome` is already nullable one-shot state. The screen shows the dialog while it is `SendOutcome.RateLimited`, and dismissal calls the existing `clearSendOutcome()`. `sendBarState(RateLimited)` maps to `Idle`: the dialog is the surface, the bar stays usable.
- **Share picker**: `shareResult` normally clears inside the `LaunchedEffect`. `RateLimited` is instead left set while the dialog shows, and `clearShareResult()` runs on dismiss or CTA.
- **Composer**: `createOutcome` holds `RateLimited(retryAfterSeconds)`. A new `PostCreationViewModel.onCapDialogDismissed()` clears it. The projection gives it no banner.
- **Post-detail**: the screen-local `likeOutcome` / `replyOutcome` hold the `RateLimited`, and dismissal nulls them. `likeBanner` / `replyBanner` map `RateLimited` to `null`.

### D4: `Retry-After` reaches the chat and composer outcomes

`SendMessageApiResult.HttpError` and `PostCreationApiResult.HttpError` gain `retryAfterSeconds: Long?`, parsed by the existing `HttpResponse.retryAfterSeconds()` (module-`internal`, already used by the like/reply/edit clients). The repositories map `429` → `RateLimited(retryAfterSeconds ?: 0L)`. The dialog's established floor turns `0` into "1 mnt" rather than flash-dismissing. The fix sits in the shared repository mapping, so both chat callers (thread + picker) get it.

### D5: The chat bubble on 429 is dropped, the text is kept

The message was not accepted, so the optimistic bubble is removed (today's behavior), but the input text survives (`input` clears only on `Sent`). The dialog explains why and what to do. A failed bubble with a retry affordance belongs to #494's NetworkRetry design and is not invented here.

### D6: Per-entry hero headlines; `IMAGE_ATTACH` stays generic

New subheads (via `stringResource`):
- `paywall_subhead_reply_cap`
- `paywall_subhead_post_cap`
- `paywall_subhead_chat_cap`
- `paywall_subhead_edit`
- `paywall_subhead_radius`
- `paywall_subhead_username`

`LIKE_CAP` and `SEARCH_GATE` keep theirs. `IMAGE_ATTACH` deliberately keeps `paywall_subhead_default`. Image upload is a Month-6 launch behind `image_upload_enabled` (default `false`, docs/05), and docs/01 + docs/03 § Paywall & Premium Disclosure forbid mentioning image upload before it ships. A photo-led headline would promise a feature a buyer may not get. When the image launch flips the flag, a follow-up can tailor it. The spec carries this as a guard scenario.

`PaywallEntry` values are appended, never reordered. kotlinx.serialization encodes an enum by name, so already-persisted iOS back stacks keep decoding.

### D7: One surface per cap hit, banners removed

The dialog replaces the cap banners. They are not kept side by side, because two surfaces for one event is noise. The orphaned pieces are deleted along with their `SharedStringsCatalogTest` references:
- `PostDetailBanner.LikeCap` / `ReplyCap`
- `resetHours()`
- `post_detail_reset_hours`
- `PostCreationBanner.RATE_LIMITED`
- `post_create_error_rate_limited`

### Standards conformance (docs/11)

The change builds on the registered patterns and forks none:
- **§2.2**: one-shot UI events are nullable state cleared by a callback (D3).
- **§2.3**: `PaywallRoute` pushed on the root back stack by the host. No new `NavKey`, only enum values on a registered key.
- **§2.6**: sealed outcomes at the repository boundary, headers parsed in the ApiClient (D4).
- **`ui/components`** reuse: `DailyCapUpsellDialog` (D1).

No Pattern-Registry amendment is needed.

### Cross-layer scope (docs/12)

- **Layers**: mobile only.
- **Backend**: the wire contracts already ship. Every limiter returns `429` + `Retry-After`; the edit and radius gates return `403`. No change.
- **Admin**: no surface. Cap hits are not an admin-visible entity.
- **Mobile**: every consumer of each signal is covered: both chat send paths, like/reply on post-detail (the feed like path already ships), the composer, edit, and radius.

No layer is deferred.

### Mockups

- Frame 18 (cap dialog) governs every new dialog instance: same component, title, CTA pair and countdown treatment.
- Frame 17 (paywall) is unchanged apart from the subhead text and one benefit label.

Implementation renders both and runs `dev/scripts/mockup-measure.sh` for frame 18 (docs/11 §2.8).

## Risks / Trade-offs

- **The like burst limiter can show a Premium user the Free like-cap dialog.** The 500/h limiter applies to both tiers. This already happens on the feeds; post-detail now matches. At 500 likes/hour it is negligible. → Accepted. A tier-aware 429 is a backend envelope change, out of scope.
- **The cap numbers in the copy are literal (10/20/50).** The like and reply caps are Remote-Config-overridable (`premium_like_cap_override`, `premium_reply_cap_override`), so an operator override makes the copy drift. This is pre-existing for the like/reply strings, and the post and chat caps are fixed. → Accepted, flagged in the PR body.
- **PR #512 also edits `PaywallScreen.kt`, `strings.xml` and `mobile-paywall`.** The two touch different requirements, so the conflict is textual only. → Whichever merges second rebases and re-runs the paywall tests.
- **Post-detail dialog state is composition-local, so rotation drops an open dialog.** The like/reply state is already composition-local today. → Accepted. The cap still applies, and the next tap re-raises the dialog.

## Migration Plan

Client-only. A release ships the new behavior and there is no data migration. Persisted iOS back stacks containing `PaywallRoute(LIKE_CAP|SEARCH_GATE|USERNAME|IMAGE_ATTACH)` still decode, since enum values are append-only (D6). Rollback = revert the PR.

## Open Questions

None blocking. The `IMAGE_ATTACH` headline exception (D6) follows the docs/01 disclosure rule rather than the issue's "every entry gets its own tailored headline". The PR surfaces it for the operator.
