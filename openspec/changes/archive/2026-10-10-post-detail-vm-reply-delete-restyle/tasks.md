## 1. Data layer — own-reply delete (#497)

- [x] 1.1 `ReplyApiClient.deleteReply(postId, replyId)`: `DELETE /api/v1/posts/{postId}/replies/{replyId}` → `ReplyDeleteApiResult`:
  - `NoContent` (204);
  - `HttpError(status)`;
  - `NetworkError(cause)` — `CancellationException` rethrown, no body, no logging.
- [x] 1.2 `PostDetailFlow.deleteReply(postId, replyId): ReplyDeleteOutcome`:
  - a sealed outcome with `Deleted` and `NetworkError` (KDoc: the backend never emits 403/404/429 on this route);
  - `PostDetailRepository` maps `NoContent` → `Deleted` and everything else → `NetworkError`.
- [x] 1.3 `FakePostDetailFlow` (commonTest): add a configurable `deleteOutcome`, a call counter, and the recorded `(postId, replyId)`.

## 2. ViewModel owns post-detail work (#542, design D1–D5)

- [x] 2.1 `PostDetailUiState.kt`:
  - `ReplyUi.isOwn` + a `selfUserId` param on `repliesUiState` (fail-closed);
  - the screen-level `PostDetailUiState` data class: content / freshness / like / replies + paging / composer / report / block / delete targets / one-shots, with `authorUserId` as the gate/nav carve-out.
- [x] 2.2 `PostDetailViewModel`: one private `VmState` + `uiState = combine(state, isLoadingMore, loadMoreError).stateIn(WhileSubscribed(5_000))`. Retire the 10 public flows. Constructor takes the `PostDetailRoute` payload, `PostDetailFlow`, `PostEditFlow`, `SelfUserIdProvider`, `ReportSubmitter`, `BlockSubmitter`.
- [x] 2.3 VM init:
  - the replies first page (existing);
  - the like-count read;
  - the session self-id read.

  Add `refreshPost()` (the ON_RESUME freshness read: content / `editedAt` / `isAuthor` / `authorUserId`; degrades silently).
- [x] 2.4 `onToggleLike()`:
  - synchronous in-flight claim;
  - optimistic flip + count;
  - the network leg under `NonCancellable`;
  - revert to the exact prior count on any non-`Liked`/`Unliked` outcome;
  - `onLikeCapDismissed()` clears the cap-dialog state (the post-gone / network banner persists until the next toggle).
- [x] 2.5 `onSubmitReply(content)`:
  - `submitEnabled` gate + synchronous in-flight claim;
  - `NonCancellable` POST;
  - `201` → prepend + count + `replyPosted` one-shot (`onReplyPostedShown()`);
  - failure outcomes → `replyOutcome`; `onReplyCapDismissed()` clears the cap dialog.

  Remove `onReplyPosted(reply)` as a public entry.
- [x] 2.6 Delete:
  - `onDeleteReplyClicked(replyId)` / `onDeleteReplyDialogDismissed()`;
  - `onDeleteReplyConfirmed()` — no-op for a non-own reply; optimistic remove + count decrement floored at 0; `NonCancellable` DELETE; on `NetworkError` a positional restore (skipped if already re-listed or no longer loaded) + adding back exactly what was subtracted + `deleteFailed`;
  - `onDeleteMessageShown()`;
  - `ponytail:` note on the index-clamp ceiling.
- [x] 2.7 Block post: `onBlockPostClicked()` uses the VM-held `authorUserId` + route username. Report + block submissions also run their network leg under `NonCancellable` (review round 1). Load-more behaviour is unchanged. `PostDetailUiState.selfResolved` keeps the reply block item fail-closed while the session id is unresolved.
- [x] 2.8 Settings logout (#542 tail, design D9):
  - `AuthFlow.revokeSession(fcmToken)`, implemented by `AuthRepository`;
  - `SettingsViewModel` takes `authFlow: AuthFlow?` instead of `authApi: AuthApiClient?`;
  - `SettingsScreen` resolves `getOrNull<AuthFlow>()`;
  - `FakeAuthFlow` + the `RootRouterScreenTest` anonymous `AuthFlow` implement it.
- [x] 2.9 `docs/11-Engineering-Standards.md` §2.2: register the convention that a user-initiated write's network leg runs under `NonCancellable` while reads stay cancellable. Reference #577 for the other VMs (design D2, the Pattern-Registry rule).

## 3. Screen split + frame-7 restyle (#542 split, #242, #497 UI)

- [x] 3.1 Resources:
  - `strings.xml`: `post_detail_title`, `cta_back`, `post_detail_replies_header`, `post_detail_reply_delete_action`, `post_detail_reply_delete_title`, `post_detail_reply_delete_body`, `cta_delete`, `post_detail_reply_delete_failed`;
  - `ic_send.xml` + `ic_send_filled.xml` (Material Symbols "send", outlined for the action-row share, filled for the composer).
- [x] 3.2 `PostDetailScreen.kt`:
  - VM wiring;
  - `LifecycleEventEffect(ON_RESUME) → refreshPost()`;
  - one `Scaffold` (top bar / composer bottom bar / snackbar);
  - the `LazyColumn` (header, action row, subhead, replies, footer — each with `key` + `contentType`);
  - dialogs (report, block, delete, both caps);
  - one-shots (snackbars, block pop, draft clear);
  - autofocus unchanged.

  No `rememberCoroutineScope`; no flow/repository reference outside the VM construction.
- [x] 3.3 `PostDetailHeader.kt`:
  - `PostDetailTopBar` (M3 `TopAppBar`: back-arrow `IconButton` with `cta_back`, `post_detail_title`, Edit + kebab);
  - the post kebab (unchanged items);
  - `PostHeader` (identity row + `@handle` — no distance, per `hide-distance`; content, image, the pin-led posted-from line, the edited label);
  - `PostActionRow` (dividers; reply count; inline share → `onShareToChat`; like heart + bare count with the `post_detail_like_count` description + `stateDescription`; ≥48dp);
  - `BannerText`.
- [x] 3.4 `PostDetailReplies.kt`:
  - `RepliesSubhead`;
  - `ReplyRow` (full-bleed list item: avatar, name · date line as the profile tap target, content, kebab);
  - `ReplyActionsMenu` ("Hapus balasan" first iff `isOwn`, then block iff `selfResolved && !isOwn` + a wire username, then "Laporkan");
  - `RepliesLoading` / `RepliesEmpty` / `RepliesError`;
  - `DeleteReplyDialog`.
- [x] 3.5 `PostDetailComposer.kt`:
  - pill `TextField` (`surfaceContainerHigh`, `shapes.extraLarge`, no indicator, `maxLines = 5`);
  - 48dp `FilledIconButton` `ic_send_filled` described by `cta_reply`;
  - counter while the draft is non-empty;
  - banner above;
  - nav-bar + IME insets.
- [x] 3.7 `AndroidManifest.xml`: `MainActivity` declares `android:windowSoftInputMode="adjustResize"` (found on device: the window panned + `imePadding` doubled the lift; design D8). `PostDetailSourceGuardTest` pins it.
- [x] 3.6 Each of the four UI files ≤ ~400 LOC. Every KDoc that names the old composable locations is updated.

## 4. Tests

- [x] 4.1 `PostDetailUiStateTest`: `isOwn` own / other / null self id; the existing projection tests adapted to the new param.
- [x] 4.2 `PostDetailApiTest`: DELETE `204` → `Deleted`, `500` → `NetworkError`, transport throw → `NetworkError`; request path + method + no body.
- [x] 4.3 `PostDetailViewModelTest` rewritten onto `uiState` with a background collector. Every existing assertion is migrated one-for-one. Add:
  - **cancel-safety:** a reply POST, a like toggle and an own-reply DELETE each complete after the `ViewModelStore` is cleared mid-flight;
  - double-submit → one POST; like double-tap → one toggle;
  - like flip + exact-count revert;
  - `replyPosted` one-shot;
  - **delete:** remove + decrement + keep on `Deleted`; positional restore + count + `deleteFailed` on `NetworkError`; no duplicate when a reload re-listed it; a non-own reply is a no-op; dismiss → zero calls; with a null self id `isOwn` is false and `selfResolved` is false;
  - `onBlockPostClicked()` is a no-op without an `authorUserId` and on an own post;
  - `refreshPost` populates the freshness fields, and `Unavailable` keeps the payload.
- [x] 4.4 `PostDetailScreenTest` (Robolectric):
  - migrate selectors ("Tutup" → `cta_back` description; "Balas" → `cta_reply` description; "42 suka" → bare "42" + description; the unavailable-count case asserts the count node absent by tag);
  - the top bar shows no "Tutup" text;
  - add the frame-7 nodes (title, subhead, inline share → `onShareToChat`, counter hidden when empty);
  - no distance rendered for a `distanceM = 5000.0` payload (`hide-distance`);
  - delete item own vs other; absent while the self id is null; confirm → removed + count; cancel → zero calls; failure → restored + snackbar;
  - the 201 path also asserts the composer field is empty afterwards;
  - no "Ikuti", no reply like control, no reply Premium badge, no composer avatar (the deferral guards);
  - the no-UUID check also covers content descriptions.
- [x] 4.5 `PostDetailSourceGuardTest`:
  - scan the four UI files concatenated for the no-literal guard and the existing presence checks (`cta_reply`, `post_detail_posted_from`, `profile_block_action`, …);
  - no `rememberCoroutineScope`, and no `(flow|editFlow|selfUserIdProvider|reportSubmitter|blockSubmitter).x(` call;
  - in `PostDetailViewModel.kt`: one public `StateFlow` and no `Channel` / `SharedFlow`; add it to the no-`println` scan;
  - the four UI files hold no back-stack reference.
- [x] 4.6 `SettingsLogoutViewModelTest`: build `AuthRepository(FakeGoogleSignInGateway, AuthApiClient(MockEngine), store, SessionInvalidator)` as the `AuthFlow`; every existing assertion is preserved; add `revokeSession` with no stored token → no request.
- [x] 4.7 `PostDetailFlowIosTest`: selector-only edits for the restyled back + send actions. The scenarios are unchanged and pass under `:mobile:app:iosSimulatorArm64Test`.

## 5. Verification & lifecycle

- [x] 5.1 Mockup: frame 7 rendered + annex generated (done at proposal); compare the finished screen against it.
- [x] 5.2 Gate (docs/13):
  - `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest :mobile:app:iosSimulatorArm64Test :mobile:app:linkDebugFrameworkIosSimulatorArm64`;
  - throwaway PG on :5434 if the dev DB is dirty.
- [x] 5.3 Manual verify (verify-loop): Android emulator `verify36` + iOS simulator, light + dark. Cover the post-detail chrome, own-reply delete (dialog → removal), and a failed delete (revert + snackbar). Screenshots go in the PR body (docs/11 §5 DoD).
- [x] 5.4 PR title/body current at each phase boundary; the body carries `Closes #497`, `Closes #242`, `Closes #542` on separate lines; archive via `/opsx:archive`.
