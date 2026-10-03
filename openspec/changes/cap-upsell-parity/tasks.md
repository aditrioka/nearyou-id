# Tasks: cap-upsell-parity

## 1. Paywall entries, headlines, benefit copy

- [x] 1.1 `NavKeys.kt`: append `CHAT_CAP`, `REPLY_CAP`, `POST_CAP`, `EDIT_GATE`, `RADIUS_GATE` to `PaywallEntry` (append-only, design D6); refresh the enum + `PaywallRoute` KDoc to list every entry and its call site
- [x] 1.2 `:shared:resources`: add `paywall_subhead_{chat_cap,reply_cap,post_cap,edit,radius,username}`; set `paywall_benefit_no_ads` = "Tanpa iklan · badge Premium"; refresh the paywall comment block
- [x] 1.3 `PaywallScreen.PaywallHero`: an exhaustive per-entry subhead `when` with no `else`; `IMAGE_ATTACH` stays on `paywall_subhead_default` with a comment citing the docs/01 disclosure rule
- [x] 1.4 `NavKeySerializationTest`: round-trip `PaywallRoute(e)` for every `e` in `PaywallEntry.entries`, and assert the declared value list and its order

## 2. Hoisted entry-naming callback (design D2)

- [x] 2.1 Home path: `onActivatePremium: (PaywallEntry) -> Unit` on `AppShellScreen` → `HomeScreen` → Nearby / Following / Global timeline screens. The like-cap dialog passes `LIKE_CAP`; the Nearby radius upsell passes `RADIUS_GATE`
- [x] 2.2 `AppEntryProvider` `HomeRoute`: `onActivatePremium = { entry -> backStack.add(PaywallRoute(entry)) }`

## 3. Chat send 429 (thread + share picker)

- [x] 3.1 `ChatMessagesApiClient`: `SendMessageApiResult.HttpError` gains `retryAfterSeconds: Long?`, parsed via the shared `retryAfterSeconds()`
- [x] 3.2 `ChatFlow`: add `SendOutcome.RateLimited(retryAfterSeconds)`. `ChatRepository.send`: `429` → `RateLimited(retryAfterSeconds ?: 0L)`, not the `Error` fallthrough (refresh the stale `429` comment)
- [x] 3.3 `sendBarState`: `RateLimited` → `Idle` (the dialog surfaces the cap)
- [x] 3.4 `:shared:resources`: add `chat_cap_upsell`
- [x] 3.5 `ChatThreadScreen`:
  - new hoisted `onActivatePremium: () -> Unit = {}`;
  - shows `DailyCapUpsellDialog` with the `chat_cap_upsell` body while `sendOutcome is RateLimited`;
  - dismiss → `clearSendOutcome()`, CTA → clear + `onActivatePremium()`;
  - the input is preserved;
  - refresh the KDoc
- [x] 3.6 `ConversationPickerViewModel`: add `ChatShareResult.RateLimited(retryAfterSeconds)` mapped from `SendOutcome.RateLimited` (exhaustive, no wildcard)
- [x] 3.7 `ConversationPickerScreen`:
  - new hoisted `onActivatePremium: () -> Unit = {}`;
  - the `LaunchedEffect` leaves `RateLimited` set;
  - shows the cap dialog with the `chat_cap_upsell` body;
  - dismiss → `clearShareResult()`, CTA → clear + `onActivatePremium()`
- [x] 3.8 `AppEntryProvider`: the `ChatThreadRoute` and `ConversationPickerRoute` entries wire `onActivatePremium` → `PaywallRoute(CHAT_CAP)`

## 4. Post-detail like + reply caps

- [x] 4.1 `PostDetailUiState`: `likeBanner` / `replyBanner` map `RateLimited` → `null`; delete `PostDetailBanner.LikeCap` / `ReplyCap`, `resetHours()` and `SECONDS_PER_HOUR`; refresh the KDoc
- [x] 4.2 `PostDetailScreen`:
  - new hoisted `onActivatePremium: (PaywallEntry) -> Unit = {}`;
  - `DailyCapUpsellDialog` while `likeOutcome is RateLimited` (like body, CTA → `LIKE_CAP`) and while `replyOutcome is RateLimited` (reply body, CTA → `REPLY_CAP`);
  - dismiss nulls the outcome;
  - drop the cap arms from `BannerText`;
  - refresh the KDoc
- [x] 4.3 `AppEntryProvider` `PostDetailRoute`: `onActivatePremium = { entry -> backStack.add(PaywallRoute(entry)) }`
- [x] 4.4 `:shared:resources`: remove `post_detail_reset_hours`, and its `SharedStringsCatalogTest` reference + count

## 5. Composer post cap

- [x] 5.1 `PostCreationApiClient`: `HttpError` gains `retryAfterSeconds: Long?`. `PostCreationOutcome.RateLimited` → `data class RateLimited(retryAfterSeconds: Long)`. `CreatePostRepository`: `429` → `RateLimited(retryAfterSeconds ?: 0L)`
- [x] 5.2 `PostCreationUiState`: remove `PostCreationBanner.RATE_LIMITED`; `RateLimited` projects no banner
- [x] 5.3 `PostCreationViewModel.onCapDialogDismissed()` clears `createOutcome`
- [x] 5.4 `PostCreationScreen`:
  - `onActivatePremium: (PaywallEntry) -> Unit = {}`;
  - the image-attach one-shot passes `IMAGE_ATTACH`;
  - the cap dialog shows while `outcome is RateLimited` (`post_create_cap_upsell` body, dismiss → `onCapDialogDismissed`, CTA → dismiss + `POST_CAP`)
- [x] 5.5 `AppEntryProvider` `PostCreationRoute`: `onActivatePremium = { entry -> backStack.add(PaywallRoute(entry)) }`
- [x] 5.6 `:shared:resources`: add `post_create_cap_upsell`; remove `post_create_error_rate_limited`; update the `SharedStringsCatalogTest` references + count (also covers 1.2 / 3.4 / 4.4)

## 6. Post-edit gate

- [x] 6.1 `EditPostScreen`:
  - new hoisted `onActivatePremium: () -> Unit = {}`;
  - the upsell CTA → `onPremiumUpsellDismissed()` + `onActivatePremium()`;
  - delete the "v1 has no paywall" comments
- [x] 6.2 `AppEntryProvider` `EditPostRoute`: `onActivatePremium = { backStack.add(PaywallRoute(PaywallEntry.EDIT_GATE)) }`

## 7. Docs

- [x] 7.1 `docs/03-UX-Design.md` § Rate Limit Communication: add the reply / post / chat cap modal copy (verbatim to the new and existing strings) next to the like-cap line
- [x] 7.2 `dev/mockups/nearyou-screens-mockup.html` frame 18 caption: retag "Belum dibangun" → shipped ("Sudah ada"); all four caps now ship on this one dialog. Keep the frame-18 icon divergence noted (design Non-Goals)

## 8. Tests (one per spec'd scenario)

- [x] 8.1 commonTest:
  - `ChatRepository` / `ChatMessagesApiClient` MockEngine: `429` + `Retry-After: 3600` → `RateLimited(3600)`; `429` without the header → `RateLimited(0)`; not `Error`
  - `ChatThreadUiStateTest`: `sendBarState(RateLimited)` → `Idle`
  - `ChatThreadViewModelTest`: a rate-limited send drops the optimistic bubble, `sendOutcome` = `RateLimited`, `clearSendOutcome` → null
- [x] 8.2 commonTest: `ConversationPickerViewModelTest`: a rate-limited share → `ChatShareResult.RateLimited(3600)` (not `Failed`), no navigation
- [x] 8.3 commonTest:
  - `CreatePostRepository` / `PostCreationApiClient` MockEngine: `429` + `Retry-After: 51540` → `RateLimited(51540)`; no header → `RateLimited(0)`
  - `PostCreationUiStateTest`: `RateLimited` projects no banner, CTA enabled
  - `PostCreationViewModel`: `onCapDialogDismissed` clears the outcome
- [x] 8.4 commonTest: `PostDetailUiStateTest`: `likeBanner` / `replyBanner(RateLimited)` → `null` (replaces the `LikeCap` / `ReplyCap` / `resetHours` cases)
- [x] 8.5 Robolectric `ChatThreadScreenTest`:
  - a `429` send shows the dialog with the `chat_cap_upsell` "19 mnt" body, no "halo" bubble, input keeps "halo";
  - "Tutup" clears it with no navigation;
  - the CTA under `TestNavHost` → top `PaywallRoute(CHAT_CAP)`
- [x] 8.6 Robolectric `ConversationPickerScreenTest` (new or extended): a rate-limited share shows the cap dialog, not the failed snackbar; the CTA under `TestNavHost` → `PaywallRoute(CHAT_CAP)`
- [x] 8.7 Robolectric `PostDetailScreenTest`:
  - like `429` → flip reverted + cap dialog with the like body + no banner;
  - reply `429` → cap dialog with the reply body + draft kept + no banner;
  - like CTA under `TestNavHost` → `PaywallRoute(LIKE_CAP)`;
  - reply CTA → `PaywallRoute(REPLY_CAP)`
- [x] 8.8 Robolectric `PostCreationScreenTest`:
  - `429` → cap dialog with the `post_create_cap_upsell` "19 mnt" body + no banner + draft kept;
  - "Tutup" clears it without invoking the callback;
  - the CTA under `TestNavHost` → `PaywallRoute(POST_CAP)`;
  - the existing image-attach tests still see `IMAGE_ATTACH`
- [x] 8.9 Robolectric `EditPostScreenTest`:
  - the `403` upsell CTA fires `onActivatePremium` once + dismisses;
  - dismiss does not fire it;
  - the CTA under `TestNavHost` → `PaywallRoute(EDIT_GATE)`
- [x] 8.10 Robolectric:
  - `NearbyTimelineScreenTest`: the radius upsell CTA passes `RADIUS_GATE` and the like-cap CTA passes `LIKE_CAP`;
  - `HomeTabHostScreenTest`: under the real host, the radius CTA → `PaywallRoute(RADIUS_GATE)`, and the existing like-cap → `LIKE_CAP` test stays green
- [x] 8.11 Robolectric `PaywallScreenTest`:
  - each of `CHAT_CAP` / `REPLY_CAP` / `POST_CAP` / `EDIT_GATE` / `RADIUS_GATE` / `USERNAME` shows its mapped subhead (not the default);
  - `IMAGE_ATTACH` shows the default subhead and no image-upload node;
  - the no-ads row reads "Tanpa iklan · badge Premium" and no node contains "tenure"
- [x] 8.12 Every new or extended `*ScreenTest` is on the `mobile/app/build.gradle.kts` Release-variant exclude list
- [x] 8.14 Source guards for the inspection scenarios:
  - `PostDetailSourceGuardTest`: `PostDetailUiState.kt` declares no `resetHours` / `LikeCap` / `ReplyCap`;
  - `PostCreationSourceGuardTest`: `PostCreationBanner` has no rate-limit member;
  - both: `strings.xml` has no `post_detail_reset_hours` / `post_create_error_rate_limited` and declares `post_create_cap_upsell` / `chat_cap_upsell`
- [ ] 8.13 iosTest (K/N-legal test names):
  - `ChatThreadReportFlowIosTest` (or a sibling `ChatThread*FlowIosTest`): the chat `RateLimited` cap dialog renders on K/N;
  - `PaywallFlowIosTest`: one new-entry headline (e.g. `CHAT_CAP`) renders

## 9. Verification & lifecycle

- [ ] 9.1 Gate (docs/13):
  - `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`
  - `:mobile:app:ktlintCheck :mobile:app:detekt :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`
  - `:mobile:app:iosSimulatorArm64Test` (pre-existing #348 drift: compare against an `origin/main` baseline)
- [ ] 9.2 Mockups: render frames 17 + 18 and run `dev/scripts/mockup-measure.sh dev/mockups/nearyou-screens-mockup.html 18`; confirm the new dialog instances match frame 18 (same component)
- [ ] 9.3 Manual verify (verify-loop §B + §C, local `verify36` emulator + iOS simulator) with a temporary uncommitted Koin harness:
  - chat `429` → dialog → paywall headline;
  - post-detail reply `429` → dialog → paywall;
  - composer `429` → dialog;
  - edit `403` → paywall;
  - radius → paywall with the radius headline.

  Screenshots go in the PR body (docs/11 §5 DoD)
- [ ] 9.4 PR title/body current at each phase boundary; body ends with `Closes #493`; archive via `/opsx:archive`
