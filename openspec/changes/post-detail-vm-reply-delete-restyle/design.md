## Context

`PostDetailScreen` is a root-stack overlay (`PostDetailRoute` above `HomeRoute`) with its own `Scaffold`. Its current shape is the result of incremental additions:

- **State is split across two holders.**
  - `PostDetailViewModel` (`mobile-nearby-timeline-infinite-scroll` D5) owns the replies list, paging, report and block state, through 10 separate `StateFlow`s.
  - The composable owns the like state, the composer in-flight / outcome state, the freshness-read results (`displayedContent`, `editedAtIso`, `isAuthor`, `authorUserId`) and the session `selfUserId`.
  - The composable launches the writes from `rememberCoroutineScope()`.
  - The old spec explicitly carved this out as "a noted follow-up" (`mobile-post-detail` § "Replies list wires cursor load-more via PostDetailViewModel"). Issue #542 is that follow-up, raised by the 2026-10-03 architecture review (BARU-A1).
- **The file is 1161 LOC**, about three times the docs/11 §4 UI soft cap.
- **Own-reply delete exists only on the server** (`ReplyRoutes.kt:165`): author-only soft-delete, always `204`, not rate-limited, no visibility pre-check (`post-replies`).
- **Frame 7** of `dev/mockups/nearyou-screens-mockup.html` is the canonical look. The measurement annex (`dev/scripts/mockup-measure.sh nearyou-screens-mockup.html 7`) gives:
  - app bar: 64dp, back arrow + `titleLarge` "Postingan" + `more_vert`;
  - post block: 16dp padding, 40dp avatar, content at 17px/25.5;
  - meta line: `onSurfaceVariant` 12.5px;
  - action row between two `outlineVariant` dividers: `mode_comment` + count, `send`, coral `favorite` + count;
  - subhead: "4 balasan", 12.5px w700, `onSurfaceVariant`, padding 14/16/6;
  - reply list items: padding 12/16, gap 16, 40dp avatar, name 14px w700 + "· 25 mnt" 12px, content 13px/18.85;
  - composer bar: padding 10/12, 32dp self-avatar, a 50dp pill field on `surfaceContainerHigh`, and a 50dp `primary` circular send button.

  Shipped code already follows frame 7 for one thing only: the reply identity row (`mobile-block-from-content` D7).

## Goals / Non-Goals

**Goals:**

- Every post-detail network call runs in `PostDetailViewModel` (`viewModelScope`).
- A write that has been issued completes even if the entry is popped.
- One `uiState` replaces the 10 flows.
- Users can delete their own reply: confirm dialog, optimistic removal, revert on failure.
- The post-detail chrome matches frame 7 using M3 components and theme tokens.
- No composable file in the post-detail surface exceeds ~400 LOC.
- Settings logout no longer skips the repository layer.
- `PostDetailFlowIosTest` stays green. Only its selectors change, to follow the restyle.

**Non-Goals:**

- **The header "Ikuti" button** (#569). It needs follow state the freshness read does not return. This is a behaviour change, not chrome.
- **Per-reply likes** (#570). There is no backend capability at all.
- **The composer self-avatar** (#569). No cached self identity exists, and it would cost one profile GET per detail open for decoration. The frame-6 composer has the same blocker.
- **Feed propagation of a delete.** The feed's `reply_count` refreshes on its own next read; this is the existing detail→feed deferral.
- **Audit `05-#11` remainder.** Folding the detail `Replies*` / `PostHeader` into the shared `ui/components` kit stays with `/audit-burndown`. This change only moves those composables into post-detail-local files.
- **`EditHistorySheet`'s own composition-scoped history load.** It is the `mobile-post-editing` overlay in its own file and is not named by #542. It is untouched here; the spec names it as the one exception and #576 tracks it.
- **Frame-7 distance in the header meta line.** Not rendered: `hide-distance` § "Scope is Nearby only — every other surface stays distance-free" governs (spec over mockup).
- **The Premium badge + name tint on a reply author** (#575). It needs an author-premium wire flag on every content projection.
- **The frame-6 composer top bar** (#574). #242 listed it as a "candidate to fold in", but it belongs to `PostCreationScreen`, not post-detail.
- **`NonCancellable` writes in other ViewModels** (#577). This change registers the convention; the sweep of composer / chat / profile / edit writes is its own change.
- **Rendering `is_auto_hidden`.** Unchanged.

## Decisions

### D1 — One private `VmState` + `stateIn` projection (the `ProfileViewModel` shape)

`PostDetailViewModel` holds a single `MutableStateFlow<VmState>`, a private data class with:

- the raw replies outcome;
- the freshness fields;
- like state;
- composer in-flight / outcome;
- the report / block / delete targets;
- the one-shot messages.

`uiState` is `combine(state, loadMoreController.isLoadingMore, loadMoreController.loadMoreError) { … toUiState() }.stateIn(viewModelScope, WhileSubscribed(5_000), initial)`.

The pure `repliesUiState(outcome, inFlight, selfUserId)` projection stays the unit-tested seam. A new `PostDetailUiState` data class is used, not a sealed interface, because its fields vary independently (docs/11 §2.2).

*Alternative considered:* keep separate flows and only add the like / composer flows. Rejected: docs/11 §2.2 names this VM as debt to consolidate "when next touched".

### D2 — Writes run under `NonCancellable`; reads stay cancellable

Moving to `viewModelScope` alone fixes rotation and push-forward (paywall), because the VM survives both. It does not fix **back**: popping the entry clears the VM and cancels `viewModelScope`.

So the network leg of each write (`toggleLike`, `postReply`, `deleteReply`, and the report and block submissions) runs inside `withContext(NonCancellable) { … }`. Once issued, the request completes and reaches the server. The state update after it is skipped by `withContext`'s prompt-cancellation check when the VM is already cleared.

This is bounded: the shared client installs `HttpTimeout` (06-#1), so a hung write cannot run forever. Reads (replies, like count, freshness, self id) stay cancellable, since abandoning them loses nothing.

*Alternative considered:* an app-scoped `CoroutineScope` Koin single. Rejected: it adds a new scope seam, and docs/11 §2.2 forbids ad-hoc `CoroutineScope()`. `NonCancellable` already has precedent in `SettingsViewModel.confirmLogout` and the `TokenRefresher` fix (#406).

No other writing ViewModel uses it yet, so this registers a convention. docs/11 §2.2 gains one line ("a user-initiated write's network leg runs under `NonCancellable`; reads stay cancellable"). The sweep of the other writing VMs is #577.

*Test note:* the cancel-safety tests run on `UnconfinedTestDispatcher` (the suite's Main), so the launched write parks on its gate before `ViewModelStore.clear()`. Precedent: `ChatThreadViewModelTest`.

### D3 — The reply draft stays UI element state; `201` clears it through a one-shot

The `TextField` value stays `rememberSaveable` in the composable. It survives the paywall round-trip, rotation and process death, and keeps the synchronous `TextField` state Google recommends over a `StateFlow`-backed field.

`onSubmitReply(content)` passes the text to the VM. On `201` the VM sets `replyPosted = true`. The screen clears the draft and calls `onReplyPostedShown()`. This is the docs/11 §2.2 one-shot-as-state rule, with no event bus.

### D4 — `authorUserId` lives in `uiState`; replies carry `isOwn`

The freshness read's `authorUserId` moves from a composable `var` into VM state, and is projected into `PostDetailUiState`. It is used only as the block target and the profile-navigation argument, and is never rendered or logged. The spec's projection requirement is MODIFIED to name this carve-out (the reply `authorId` carve-out is the precedent).

The session `selfUserId` is NOT projected. Instead, `repliesUiState` stamps `ReplyUi.isOwn = selfUserId != null && authorId == selfUserId` (fail-closed). It shows the delete item only on own replies and hides the block item on them.

One bit is not enough for the block gate: with an unresolved id `isOwn` is false, which would show "Blokir" on the viewer's own reply. So the state also carries a boolean `selfResolved`, and the block item requires it (the shipped fail-closed behaviour, review round 1). `onBlockPostClicked()` takes no arguments, because the VM already holds the target and the route username.

### D5 — Delete: optimistic removal with positional revert, two outcomes

- **Optimistic step.** `onDeleteReplyConfirmed()` closes the dialog, removes the row from the loaded list, remembers its index, and decrements `replyCount` (floored at 0). `reply_count` excludes `deleted_at IS NOT NULL` server-side (docs/05 V8 laterals), so unlike a viewer-local block a self-delete really changes the public count.
- **Outcome mapping.** `ReplyDeleteOutcome` has `Deleted` (`204`) and `NetworkError` (`5xx` / IO / any other status). The backend contract forbids `403`, `404` and `429` on this route, so there are no members for them. Mapping one to a distinct member would invent behaviour the server never emits. `401` is delegated to the `Auth` plugin as elsewhere.
- **Revert.** On `NetworkError` the row is reinserted at its original index, clamped to the list size. This is skipped if a concurrent reload already brought it back, or if the list is no longer `Loaded`. The count gets back exactly what was subtracted (1, or 0 when it was already 0). `deleteFailed` is set for a snackbar.
- **Defence in depth.** The VM ignores a confirmed delete for a reply that is not the viewer's own. The backend `204`s a stranger's reply too, which would otherwise hide that reply locally.
- **Known ceiling.** A concurrent prepend can shift the reinsert by one row. This is cosmetic; the next reload reconciles it.

### D6 — Delete lives in the reply kebab; report stays everywhere

"Hapus balasan" is the first item of the existing reply overflow menu, present iff `reply.isOwn`. The report item remains on every reply, unchanged (`mobile-content-report` makes it "ungated by authorship"). A swipe-to-delete or long-press alternative was not chosen: the kebab is the established per-row action surface, and frame 7 shows no other.

### D7 — File split

| File | Holds |
|---|---|
| `PostDetailScreen.kt` | The screen: VM wiring, the one `Scaffold`, the `LazyColumn` assembly, dialogs and one-shots, test-tag constants |
| `PostDetailHeader.kt` | `PostDetailTopBar`, the post kebab, the post header, the action row, the banner text |
| `PostDetailReplies.kt` | Subhead, reply row + kebab, replies loading / empty / error, the delete confirm dialog |
| `PostDetailComposer.kt` | The reply composer |

Moved composables become `internal`. Test tags stay as `PostDetailScreen.kt` constants so every test import is unchanged.

### D8 — Frame-7 → Compose mapping (tokens, not literals)

- **Top bar.** M3 `TopAppBar`, the same component the `ProfileScreen` and `FollowListScreen` overlays use. Its default `windowInsets` replace the hand-rolled `statusBarsPadding`.
  - The navigation icon is an `IconButton` with `ic_arrow_back`, labelled with the new `cta_back` ("Kembali"). There are already four per-feature "Kembali" keys; this change does not migrate them.
  - The `ProfileScreen` and `FollowListScreen` overlays still use a "Tutup" text button, so post-detail → profile mixes back styles until those screens get their own frame passes.
  - The title is `post_detail_title` ("Postingan").
  - Actions: the Edit `TextButton` (eligibility unchanged), then the post kebab.
- **Content size.** Content stays `bodyLarge`. The mockup's 17px is a 1sp delta from M3's 16sp, and the design system forbids invented sizes.
- **Header meta line.** `@handle` only. Frame 7's "· Jakarta Selatan · 5km" is not rendered: the city lives in the spec'd "Diposting dari {city}, {date}" line, and a distance would break `hide-distance` § "Scope is Nearby only — every other surface stays distance-free" (review round 1; spec over mockup).
- **Posted-from line.** It takes the frame's `.loc` treatment — a 16dp coral icon + `bodySmall` `onSurfaceVariant` — using `ic_post_location`. The frame's `schedule` glyph labels a bare time; the spec'd copy is a place sentence, so the pin is the honest glyph. This is a spec-over-mockup precedence call.
- **Action row.** It sits between `HorizontalDivider`s.
  - Reply: `ic_post_reply` + count (read-only). Its tag is unchanged.
  - Share: a new outlined `ic_send` `IconButton` → `onShareToChat`, labelled `chat_share_to_chat_action`. The kebab keeps its share item, so own posts still have a non-empty kebab.
  - Like: a coral filled / outlined heart + a **bare** count. The count node's `contentDescription` is `post_detail_like_count` ("N suka"). The like target carries the `post_card_action_like` label + a `stateDescription` (the `PostCard` a11y idiom).
  - Touch targets are ≥48dp.
- **Subhead.** `post_detail_replies_header` ("%d balasan"), `labelMedium` Bold `onSurfaceVariant`, keyed off the VM `replyCount`.
- **Reply rows.** Full-bleed list items. The `OutlinedCard` is gone. Each row has:
  - 16dp horizontal / 12dp vertical padding;
  - a leading `LetterAvatar` (iff a wire identity exists);
  - a column of `titleSmall` Bold name + `· {localDateLabel}` `bodySmall` / `onSurfaceVariant`, then the `bodyMedium` content;
  - the trailing kebab.

  The identity tap target (tag `postDetailReplyProfile`) is the name line. The avatar shares the same click handler.
- **Composer.** A `Row` (navigation-bar + IME insets kept, 12/10dp padding) containing:
  - a `TextField` with `shapes.extraLarge`, `surfaceContainerHigh` container and no indicator, `maxLines = 5`;
  - a `FilledIconButton` (48dp, the filled `ic_send_filled`) labelled `cta_reply` ("Balas"), enabled per the unchanged `submitEnabled` projection.

  The `N/280` counter (`labelSmall`, `error` when over the limit) renders above the row while the draft is non-empty.

  On-device verification found the composer floating a keyboard-height above the IME with the top bar pushed off-screen. The cause is pre-existing: `MainActivity` declared no `windowSoftInputMode`, so with `enableEdgeToEdge()` the system **panned** the window and `imePadding()` lifted the bar again. The fix is one manifest attribute, `android:windowSoftInputMode="adjustResize"`, which developer.android.com "Set up Edge-to-edge" requires for every activity using a soft keyboard (verified 2026-10-04). It is app-wide, and the root cause for every `imePadding` / `safeContentPadding` consumer; the post composer was spot-checked on the emulator with no regression. An empty composer reads like the frame; a typed one keeps the spec'd live count. The network / post-gone banner renders above the row.

### D9 — Settings logout through the `AuthFlow` seam

`AuthFlow` gains `suspend fun revokeSession(fcmToken: String?)`. `AuthRepository` implements it by reading the stored refresh token and calling `AuthApiClient.logout`. It does nothing without a token, and never throws except on cancellation.

`SettingsViewModel` takes `authFlow: AuthFlow?` in place of `authApi: AuthApiClient?` — the same seam SignIn and AgeGate use (review round 1). Its order (revoke → `NonCancellable` wipe) and swallow semantics are unchanged. `SettingsScreen` resolves `getOrNull<AuthFlow>()`.

`SettingsLogoutViewModelTest` passes the real `AuthRepository` (`FakeGoogleSignInGateway` + a MockEngine `AuthApiClient`), so its request-shape assertions stay end-to-end. `FakeAuthFlow` records the calls. Behaviour is identical, so `mobile-settings` gets no delta.

## Standards conformance

- **State (docs/11 §2.2).** Entry-scoped `viewModel { }` VM. One `stateIn` `uiState`, which retires the named 10-flow debt. One-shots are nullable / boolean state cleared by `onXxxShown()`. No business launch from composables. No ad-hoc `CoroutineScope`. The `ON_RESUME` freshness re-read is the registered §2.3 "silent re-read in the screen's VM" pattern.
- **Data (§2.6).** ApiClient (`ReplyApiClient.deleteReply`) → repository (`PostDetailRepository.deleteReply` → sealed `ReplyDeleteOutcome`) → VM. `CancellationException` is rethrown in the client. Logout moves onto the repository path (D9).
- **UI substrate (`mobile-design-system`).**
  - The root overlay owns exactly one `Scaffold` (the sanctioned overlay case).
  - Material icons via `Res.drawable.*`.
  - All copy via `stringResource`, guarded by `MobileHardcodedStringRule`.
  - Lazy `key` + `contentType` on every item.
- **Pattern Registry.** `NonCancellable` for must-complete writes has precedent (`SettingsViewModel` wipe, `TokenRefresher`) but no registry entry. This change adds one line to docs/11 §2.2 in the same PR (D2), and #577 sweeps the other writing VMs.

## Cross-layer scope declaration (docs/12)

| Layer | State |
|---|---|
| Backend | DELETE already shipped (`post-replies`); no wire change |
| Admin | No surface needed — reply moderation is the report queue, and self-deletes are user data |
| Mobile | The missing client layer, shipped here |
| Read paths | Every reply read (`GET /replies`, `reply_count` laterals) already excludes soft-deleted rows |

The frame-7 elements left unbuilt are declared as a deferred requirement with negative guards (#569, #570, #575).

## Risks / Trade-offs

- **[A cancelled entry still completes a write, and the UI does not show its result]** → This is intended: the server state is right and the next open re-reads it. The bounded `HttpTimeout` keeps the orphaned coroutine short-lived.
- **[The VM test rewrite is broad (10 flows → 1)]** → Each existing assertion is translated one-for-one onto `uiState`, using a background collector (the `stateIn`-needs-a-collector trap from #409). No scenario is dropped.
- **[Robolectric selectors change ("Tutup"/"Balas" text → icon content descriptions)]** → Tags are unchanged. Text selectors move to `onNodeWithContentDescription` with the same strings, and the iOS flow test gets the same selector-only edits.
- **[A positional revert can be off by one under a concurrent prepend]** → Cosmetic, documented as a `ponytail:` ceiling (D5); the next reload reconciles it.
- **[Inline share duplicates the kebab item]** → Accepted: frame 7 puts share in the action row, and removing it from the kebab would leave own posts with an empty kebab.

## Migration Plan

Client-only. No data or wire migration and no feature flag. Rollback is a revert of the squash commit.

## Open Questions

_None._ Scope boundaries were set by the operator: "Ikuti" and reply likes are out of scope.
