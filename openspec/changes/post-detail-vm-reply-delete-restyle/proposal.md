## Why

The post-detail screen has three open problems that touch the same 1161-line file, so they ship together:

- **#542 — writes are lost on back.** `PostDetailScreen.kt` starts the like toggle, the reply POST, the resume freshness read and the like-count read from `rememberCoroutineScope()`, even though a `PostDetailViewModel` exists. Leaving composition cancels them: rotation, the paywall push from the reply cap, or back while a reply is sending. A cancelled write can still land on the server while the UI loses it. The double-submit guards are `remember` vars. This breaks docs/11 §2.2, which says business work never launches from composables.
- **#497 — reply delete has no client.** The backend ships author-only soft-delete (`DELETE /api/v1/posts/{post_id}/replies/{reply_id}`, idempotent `204`; `post-replies` § "DELETE replies — author-only soft-delete, idempotent 204"). Nothing in `:mobile:app` calls it, and `mobile-post-detail` does not declare it deferred. That is a docs/12 §3 cohesion gap.
- **#242 — the screen does not match its mockup.** Post-detail chrome predates mockup frame 7 · "Detail postingan + balasan". It has a "Tutup" text button instead of a back arrow with a "Postingan" title. Reply rows are outlined cards instead of list items, and the composer is a two-line field with a full-width "Balas" button instead of a pill field with a send button.

Doing all three in one pass means the file is restructured once (#542 asks for exactly that).

## What Changes

- **ViewModel owns post-detail work (#542).**
  - `PostDetailViewModel` runs everything the screen launched itself:
    - the like toggle and the like-count read;
    - the reply POST;
    - the resume freshness read (`refreshPost`) and the session self-id read.
  - It exposes ONE `uiState: StateFlow<PostDetailUiState>` via `stateIn`. The 10-`StateFlow` debt named in docs/11 §2.2 is consolidated now that the VM is touched.
  - The in-flight guards become VM state (`likeInFlight` / `replyInFlight`).
  - Writes (like, reply, delete) run under `NonCancellable`, so a POST/DELETE already sent completes even if the entry is popped mid-flight.
  - The reply draft stays UI element state (`rememberSaveable`). The VM signals "clear it" after a `201` through a one-shot state field.
- **Split the screen file (#542).** `PostDetailScreen.kt` is split into the screen plus three composable files under the docs/11 §4 ~400-LOC UI soft cap:
  - `PostDetailHeader.kt` — top bar, post header, action row;
  - `PostDetailReplies.kt` — subhead, reply rows, list states, delete dialog;
  - `PostDetailComposer.kt` — the reply composer.
- **Settings logout goes through the repository (#542).** `SettingsViewModel` logout calls the new `AuthRepository.revokeSession(fcmToken)` instead of `AuthApiClient`, removing the only ViewModel→ApiClient layer skip (docs/11 §4). Behaviour is unchanged.
- **Own-reply delete (#497).**
  - Data path: `ReplyApiClient.deleteReply` → `PostDetailFlow.deleteReply` → `ReplyDeleteOutcome` (`Deleted` / `NetworkError`).
  - UI: a "Hapus balasan" item in the reply row's overflow kebab. It shows only on the viewer's own reply and fails closed while the session id is unresolved. A confirm dialog comes first.
  - Removal is optimistic: the row disappears and the header reply count drops by one. On failure the row comes back at its position, the count is restored, and a snackbar says the delete failed.
- **Frame-7 restyle (#242).**
  - Top bar: M3 `TopAppBar` with a back arrow, the title "Postingan", and the existing Edit + kebab actions.
  - Post header: identity row whose meta line reads `@handle · {distance}`, then the content, then the spec'd "Diposting dari …" line with a coral pin.
  - Action row between dividers:
    - reply icon + count;
    - a share-to-chat icon (the existing "Bagikan ke chat" action);
    - heart + a bare like count, still announced as "N suka".
  - A "N balasan" subhead.
  - Reply rows as full-bleed list items: avatar, then a "name · date" line, then the content, plus the kebab.
  - Composer: a pill `TextField` with a filled send `IconButton` labelled "Balas". The `N/280` counter shows while the draft is non-empty.
- **Explicit deferrals (docs/12 §3).** Three frame-7 elements are not built:
  - the header "Ikuti" button — #569;
  - per-reply likes, which have no backend — #570;
  - the composer self-avatar — #569.

  Each gets a deferred requirement with a negative guard.
- **Out of scope.** Feed-level propagation of a deleted reply's count stays the documented detail→feed deferral. The audit `05-#11` remainder (`Replies*` / `PostHeader` → shared kit) stays with `/audit-burndown`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `mobile-post-detail`:
  - The VM now owns all post-detail work, with cancel-safe writes and a single `uiState`. The old "like + composer migration is out of scope" carve-out is removed.
  - The projection carries the freshness-read `authorUserId` (gate / navigation argument only) and a per-reply `isOwn` flag.
  - New requirement: own-reply delete.
  - The delete outcome joins the "exactly one sealed member" mapping.
  - Requirements for the header, like row, replies list and composer are restyled to frame 7.
  - The no-hardcoded-strings scan covers the split files.
  - New requirements: frame-7 deferrals and test coverage.

## Impact

- **Mobile code**:
  - `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/post/`:
    - `PostDetailScreen.kt` (split);
    - new `PostDetailHeader.kt`, `PostDetailReplies.kt`, `PostDetailComposer.kt`;
    - `PostDetailViewModel.kt` and `PostDetailUiState.kt`.
  - `mobile/app/src/commonMain/kotlin/id/nearyou/app/post/` — `ReplyApiClient.kt`, `PostDetailFlow.kt`, `PostDetailRepository.kt`.
  - Auth and settings — `auth/AuthRepository.kt`, `screens/settings/SettingsViewModel.kt`, `screens/settings/SettingsScreen.kt` (Koin resolution only).
- **Resources** (`:shared:resources`):
  - new strings `post_detail_title`, `cta_back`, `post_detail_replies_header`, `post_detail_reply_delete_action`, `post_detail_reply_delete_title`, `post_detail_reply_delete_body`, `cta_delete`, `post_detail_reply_delete_failed`;
  - new drawable `ic_send.xml` (Material Symbols "send").
- **Tests**:
  - `PostDetailViewModelTest` (rewritten onto `uiState`, plus cancel-safety and delete);
  - `PostDetailScreenTest` (restyle selectors and delete);
  - `PostDetailUiStateTest`, `PostDetailApiTest` (DELETE mapping), `PostDetailSourceGuardTest` (split-file scan);
  - `SettingsLogoutViewModelTest` (through `AuthRepository`);
  - `PostDetailFlowIosTest` (selectors only — it must stay green);
  - `FakePostDetailFlow`.
- **No backend, admin, Flyway or wire change.** The DELETE endpoint already ships. `AppEntryProvider.kt` is untouched because the screen's public signature does not change.
