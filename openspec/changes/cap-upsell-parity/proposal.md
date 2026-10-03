## Why

Most Free cap and gate hits have no path to Premium. The spec'd upsell pattern (`mobile-cap-upsell-dialog`) shipped only for the three feeds' like cap. The 2026-09-25 spec-completion review ([#493](https://github.com/aditrioka/nearyou-id/issues/493), findings M4-C1, M2-C3/C4/C8, M3-C5/C7) found the gaps:

- The chat 50/day `429` maps to a generic `SendOutcome.Error` that is never rendered, so the optimistic bubble silently vanishes.
- The post-detail like and reply caps show an error-colored banner with a coarse "N jam" and no paywall path. Replies can only be written on post-detail, so the Free reply cap never reaches the paywall at all.
- The composer's 10/day cap is a banner only.
- The post-edit "Aktifkan Premium" CTA only dismisses (it predates the paywall).
- The radius upsell opens the paywall as `LIKE_CAP`.

These are the revenue loop's highest-intent moments: the user just hit the Free limit. With the paywall shipped ([#309](https://github.com/aditrioka/nearyou-id/pull/309)), each is a dead end we can close now.

## What Changes

- **Chat send `429`** maps to a new `SendOutcome.RateLimited(retryAfterSeconds)`.
  - Shared mapping: `ChatRepository`; `SendMessageApiResult.HttpError` now carries the parsed `Retry-After`.
  - Thread: shows the shared `DailyCapUpsellDialog` with a chat body and a live countdown. The dropped optimistic bubble is no longer silent, and the typed input is preserved.
  - Share-to-chat picker: the same `429` maps to a new `ChatShareResult.RateLimited` and shows the same dialog, so every send path upsells.
- **Post-detail like and reply `429`** show the `DailyCapUpsellDialog` (frame 18, live per-minute countdown) instead of the error-colored "N jam" banner. **BREAKING (internal)**: `PostDetailBanner.LikeCap` / `ReplyCap`, `resetHours()` and the `post_detail_reset_hours` string are removed.
- **Composer `429`**:
  - `PostCreationOutcome.RateLimited` becomes `RateLimited(retryAfterSeconds)`; `PostCreationApiResult.HttpError` carries `Retry-After`.
  - The composer shows the `DailyCapUpsellDialog` with a new post-cap body.
  - The `PostCreationBanner.RATE_LIMITED` banner and the `post_create_error_rate_limited` string are removed.
- **Post-edit `403 premium_required`**: the upsell's "Aktifkan Premium" now opens the paywall instead of only dismissing.
- **`PaywallEntry` gains** `CHAT_CAP`, `REPLY_CAP`, `POST_CAP`, `EDIT_GATE`, `RADIUS_GATE`. The existing `IMAGE_ATTACH` (wired by `mobile-image-attachment` but declared in no spec) is now declared.
  - Every entry gets a tailored hero subheadline. `USERNAME` reuses the docs/03 gate copy "Ganti username adalah fitur Premium.".
  - **Exception: `IMAGE_ATTACH`** keeps the generic headline. Image upload is a Month-6 feature behind the default-`false` `image_upload_enabled` flag, and docs/01 + docs/03 § Paywall & Premium Disclosure forbid advertising it.
  - Every entry round-trips in `NavKeySerializationTest`.
- **Every gated surface #493 names gets its own entry.** The Free timeline read cap is a separate change ([#516](https://github.com/aditrioka/nearyou-id/issues/516)). The hoisted `onActivatePremium` on the Home/feed path, post-detail and the composer becomes `(PaywallEntry) -> Unit`, so the radius upsell opens `RADIUS_GATE` while the like cap stays `LIKE_CAP`.
- **Paywall benefit copy** drops the unshipped tenure claim: "badge + tenure Premium" becomes "badge Premium". Only the profile Premium badge ships; the tenure counter is still deferred.
- **docs/03 § Rate Limit Communication** gains the reply, post and chat cap modal copy alongside the like copy. Frame 18's caption already declares one pattern for all four caps.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mobile-cap-upsell-dialog`:
  - The dialog serves all four Free daily caps (like / reply / post / chat), each with its own body.
  - Each host pushes its cap's `PaywallEntry`.
  - The post-detail banner divergence is retired.
- `mobile-paywall`:
  - The `PaywallEntry` set and its call sites.
  - The per-entry hero headline, with the `IMAGE_ATTACH` disclosure exception.
  - The benefit set without the tenure claim.
- `mobile-chat`: the send projection gains the rate-limited state, which shows the cap dialog with the input preserved.
- `mobile-chat-embedded-posts`: the picker maps a `429` share to a rate-limited result and shows the cap dialog.
- `mobile-post-detail`: a like or reply `429` shows the cap dialog and opens the paywall, replacing the banner.
- `mobile-post-creation`: the `429` outcome carries `Retry-After` and shows the cap dialog. The rate-limit banner and its stale string-catalog note are removed.
- `mobile-post-editing`: the reactive `403` upsell CTA opens the paywall (`EDIT_GATE`).
- `mobile-nearby-radius-slider`: the Free radius upsell CTA opens the paywall as `RADIUS_GATE`.

## Impact

- **Mobile only** (`:mobile:app` + `:shared:resources`). No backend, admin, schema, wire or dependency change. Every endpoint involved already returns `429` + `Retry-After` (Free-only daily limiters) or `403 premium_required`.
- **Code**:
  - Data layer: `chat/ChatMessagesApiClient.kt`, `chat/ChatRepository.kt`, `chat/ChatFlow.kt`, `post/PostCreationApiClient.kt`, `post/CreatePostRepository.kt`.
  - Chat screens: `screens/chat/{ChatThreadScreen,ChatThreadViewModel,ChatThreadUiState,ConversationPickerScreen,ConversationPickerViewModel}.kt`.
  - Post screens: `screens/post/{PostDetailScreen,PostDetailUiState,PostCreationScreen,PostCreationUiState,PostCreationViewModel,EditPostScreen}.kt`.
  - Home path: `screens/{shell/AppShellScreen,home/HomeScreen,timeline/*TimelineScreen}.kt`.
  - Routing and paywall: `screens/routing/{NavKeys,AppEntryProvider}.kt`, `screens/paywall/PaywallScreen.kt`.
  - Strings: `strings.xml`.
- **Docs**: `docs/03-UX-Design.md` § Rate Limit Communication (cap modal copy).
- **Sequencing**:
  - [#494](https://github.com/aditrioka/nearyou-id/issues/494) (chat NetworkRetry / TooLong send states, pull-to-refresh) is held until this merges and is **not** folded in. This change adds only the rate-limited state.
  - Open PR [#512](https://github.com/aditrioka/nearyou-id/pull/512) edits other requirements in `mobile-paywall` and `mobile-nearby-radius-slider`, plus `PaywallScreen.kt`, `NearbyTimelineScreen.kt` and `strings.xml`. The overlap is textual; whichever lands second rebases.
