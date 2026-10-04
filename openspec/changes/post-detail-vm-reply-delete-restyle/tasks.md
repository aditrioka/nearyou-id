## 1. Data layer — own-reply delete (#497)

- [ ] 1.1 `ReplyApiClient.deleteReply(postId, replyId)`: `DELETE /api/v1/posts/{postId}/replies/{replyId}` → `ReplyDeleteApiResult`:
  - `NoContent` (204);
  - `HttpError(status)`;
  - `NetworkError(cause)` — `CancellationException` rethrown, no body, no logging.
- [ ] 1.2 `PostDetailFlow.deleteReply(postId, replyId): ReplyDeleteOutcome`:
  - a sealed outcome with `Deleted` and `NetworkError` (KDoc: the backend never emits 403/404/429 on this route);
  - `PostDetailRepository` maps `NoContent` → `Deleted` and everything else → `NetworkError`.
- [ ] 1.3 `FakePostDetailFlow` (commonTest): add a configurable `deleteOutcome`, a call counter, and the recorded `(postId, replyId)`.

## 2. ViewModel owns post-detail work (#542, design D1–D5)

- [ ] 2.1 `PostDetailUiState.kt`:
  - `ReplyUi.isOwn` + a `selfUserId` param on `repliesUiState` (fail-closed);
  - the screen-level `PostDetailUiState` data class: content / freshness / like / replies + paging / composer / report / block / delete targets / one-shots, with `authorUserId` as the gate/nav carve-out.
- [ ] 2.2 `PostDetailViewModel`: one private `VmState` + `uiState = combine(state, isLoadingMore, loadMoreError).stateIn(WhileSubscribed(5_000))`. Retire the 10 public flows. Constructor takes the `PostDetailRoute` payload, `PostDetailFlow`, `PostEditFlow`, `SelfUserIdProvider`, `ReportSubmitter`, `BlockSubmitter`.
- [ ] 2.3 VM init:
  - the replies first page (existing);
  - the like-count read;
  - the session self-id read.

  Add `refreshPost()` (the ON_RESUME freshness read: content / `editedAt` / `isAuthor` / `authorUserId`; degrades silently).
- [ ] 2.4 `onToggleLike()`:
  - synchronous in-flight claim;
  - optimistic flip + count;
  - the network leg under `NonCancellable`;
  - revert to the exact prior count on any non-`Liked`/`Unliked` outcome;
  - `onLikeOutcomeShown()` clears the cap-dialog / banner state.
- [ ] 2.5 `onSubmitReply(content)`:
  - `submitEnabled` gate + synchronous in-flight claim;
  - `NonCancellable` POST;
  - `201` → prepend + count + `replyPosted` one-shot (`onReplyPostedShown()`);
  - failure outcomes → `replyOutcome`, cleared by `onReplyOutcomeShown()`.

  Remove `onReplyPosted(reply)` as a public entry.
- [ ] 2.6 Delete:
  - `onDeleteReplyClicked(replyId)` / `onDeleteReplyDialogDismissed()`;
  - `onDeleteReplyConfirmed()` — optimistic remove + count decrement floored at 0, `NonCancellable` DELETE, on `NetworkError` a positional restore (skipped if already re-listed) + count restore + `deleteFailed`;
  - `onDeleteMessageShown()`;
  - `ponytail:` note on the index-clamp ceiling.
- [ ] 2.7 Block post: `onBlockPostClicked()` uses the VM-held `authorUserId` + route username. Report / block / load-more behaviour is unchanged otherwise.
- [ ] 2.8 Settings logout (#542 tail, design D9):
  - `AuthRepository.revokeSession(fcmToken)`;
  - `SettingsViewModel` takes `authRepository: AuthRepository?` instead of `authApi: AuthApiClient?`;
  - `SettingsScreen` resolves `getOrNull<AuthRepository>()`.

## 3. Screen split + frame-7 restyle (#542 split, #242, #497 UI)

- [ ] 3.1 Resources:
  - `strings.xml`: `post_detail_title`, `cta_back`, `post_detail_replies_header`, `post_detail_reply_delete_action`, `post_detail_reply_delete_title`, `post_detail_reply_delete_body`, `cta_delete`, `post_detail_reply_delete_failed`;
  - `ic_send.xml` (Material Symbols "send").
- [ ] 3.2 `PostDetailScreen.kt`:
  - VM wiring;
  - `LifecycleEventEffect(ON_RESUME) → refreshPost()`;
  - one `Scaffold` (top bar / composer bottom bar / snackbar);
  - the `LazyColumn` (header, action row, subhead, replies, footer — each with `key` + `contentType`);
  - dialogs (report, block, delete, both caps);
  - one-shots (snackbars, block pop, draft clear);
  - autofocus unchanged.

  No `rememberCoroutineScope`; no flow/repository reference outside the VM construction.
- [ ] 3.3 `PostDetailHeader.kt`:
  - `PostDetailTopBar` (M3 `TopAppBar`: back-arrow `IconButton` with `cta_back`, `post_detail_title`, Edit + kebab);
  - the post kebab (unchanged items);
  - `PostHeader` (identity row + `@handle · distance` meta, content, image, the pin-led posted-from line, the edited label);
  - `PostActionRow` (dividers; reply count; inline share → `onShareToChat`; like heart + bare count with the `post_detail_like_count` description + `stateDescription`; ≥48dp);
  - `BannerText`.
- [ ] 3.4 `PostDetailReplies.kt`:
  - `RepliesSubhead`;
  - `ReplyRow` (full-bleed list item: avatar, name · date line as the profile tap target, content, kebab);
  - `ReplyActionsMenu` ("Hapus balasan" first iff `isOwn`, then block iff eligible, then "Laporkan");
  - `RepliesLoading` / `RepliesEmpty` / `RepliesError`;
  - `DeleteReplyDialog`.
- [ ] 3.5 `PostDetailComposer.kt`:
  - pill `TextField` (`surfaceContainerHigh`, `shapes.extraLarge`, no indicator, `maxLines = 5`);
  - 48dp `FilledIconButton` `ic_send` described by `cta_reply`;
  - counter while the draft is non-empty;
  - banner above;
  - nav-bar + IME insets.
- [ ] 3.6 Each of the four UI files ≤ ~400 LOC. Every KDoc that names the old composable locations is updated.

## 4. Tests

- [ ] 4.1 `PostDetailUiStateTest`: `isOwn` own / other / null self id; the existing projection tests adapted to the new param.
- [ ] 4.2 `PostDetailApiTest`: DELETE `204` → `Deleted`, `500` → `NetworkError`, transport throw → `NetworkError`; request path + method + no body.
- [ ] 4.3 `PostDetailViewModelTest` rewritten onto `uiState` with a background collector. Every existing assertion is migrated one-for-one. Add:
  - **cancel-safety:** a reply POST and a like toggle each complete after the `ViewModelStore` is cleared mid-flight;
  - double-submit → one POST;
  - like flip + exact-count revert;
  - `replyPosted` one-shot;
  - **delete:** remove + decrement + keep on `Deleted`; positional restore + count + `deleteFailed` on `NetworkError`; dismiss → zero calls; `isOwn` false with a null self id;
  - `refreshPost` populates the freshness fields, and `Unavailable` keeps the payload.
- [ ] 4.4 `PostDetailScreenTest` (Robolectric):
  - migrate selectors ("Tutup" → `cta_back` description; "Balas" → `cta_reply` description; "42 suka" → bare "42" + description);
  - add the frame-7 nodes (title, subhead, inline share → `onShareToChat`, counter hidden when empty);
  - delete item own vs other; confirm → removed + count; cancel → zero calls; failure → restored + snackbar;
  - no "Ikuti", no reply like control, no composer avatar (the deferral guards).
- [ ] 4.5 `PostDetailSourceGuardTest`: scan all four UI files for the no-literal guard, `stringResource(Res.string.cta_reply)` / `post_detail_posted_from`, and no `rememberCoroutineScope` / no flow references.
- [ ] 4.6 `SettingsLogoutViewModelTest`: build `AuthRepository(FakeGoogleSignInGateway, AuthApiClient(MockEngine), store, SessionInvalidator)`; every existing assertion is preserved.
- [ ] 4.7 `PostDetailFlowIosTest`: selector-only edits for the restyled back + send actions. The scenarios are unchanged and pass under `:mobile:app:iosSimulatorArm64Test`.

## 5. Verification & lifecycle

- [ ] 5.1 Mockup: frame 7 rendered + annex generated (done at proposal); compare the finished screen against it.
- [ ] 5.2 Gate (docs/13):
  - `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest :mobile:app:iosSimulatorArm64Test`;
  - throwaway PG on :5434 if the dev DB is dirty.
- [ ] 5.3 Manual verify (verify-loop): Android emulator `verify36` + iOS simulator, light + dark. Cover the post-detail chrome, own-reply delete (dialog → removal), and a failed delete (revert + snackbar). Screenshots go in the PR body (docs/11 §5 DoD).
- [ ] 5.4 PR title/body current at each phase boundary; the body carries `Closes #497`, `Closes #242`, `Closes #542` on separate lines; archive via `/opsx:archive`.
