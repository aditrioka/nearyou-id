## Why

The chat context card's "diedit sejak dibagikan" banner never fires. `chat-embedded-posts` (PR #423) shipped the pure gate and the card render, but left the live edit-state source deferred ([#440](https://github.com/aditrioka/nearyou-id/issues/440)). `ChatMessageList.kt` passes the message's own anchor as the live signal, so the gate always returns false. A recipient whose shared post was edited after the share sees the stale snapshot with no hint that it changed.

The original design (D2) compared the message's `embedded_post_edit_id` with the live post's latest `post_edits.id`. No read path exposes that id: `single-post-read` and `GET /posts/{id}/edits` return `editedAt` timestamps only.

## What Changes

- **The banner compares timestamps, not edit ids.** The comparison is the snapshot's share-time `editedAt` against the live post's `editedAt` from `single-post-read`. Both come from `MAX(post_edits.edited_at)` for the post. `post_edits` carries `UNIQUE (post_id, edited_at)` (V22), so the latest timestamp identifies the latest edit row exactly: the two values differ if and only if an edit landed after the share. Unknown live state (read in flight, failed, or `404` because the viewer can no longer see the post) shows no banner.
- **`ChatThreadViewModel` reads each shared post's live edit state, once per post per thread visit.** It reads through the existing `PostEditFlow.refreshPost` repository seam, the same `single-post-read` minimal read post-detail uses for its "Diedit" label (docs/11 §2.6: ViewModels talk to repositories). Reads start as live cards enter the merged list (first page, older pages, realtime). A deleted-source card or a redacted message triggers no read.
- **The gate result lives in the single `uiState`.** `ChatMessageRow` gains `editedSinceShared`, computed in the pure row projection, so the composable reads one field (docs/11 §2.2). The card still renders its content from the immutable snapshot. The live read feeds only the banner.
- **The mobile merge model drops `embeddedPostEditId`.** Nothing on the client reads it any more. The wire DTOs and the realtime inbound model keep the field, and the backend keeps stamping the column. It remains the share-time version handle the docs/03 edit-history highlight will navigate by.
- **Spec text.** In `mobile-chat-embedded-posts`, the deferred banner requirement is renamed and rewritten as live behaviour. In `chat-embedded-posts`, the anchor requirement's prose no longer claims that the mobile banner compares edit ids. Backend behaviour is unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `mobile-chat-embedded-posts`: the edited-since-shared banner requirement goes from "gate only, live source deferred" to "gate fed by a per-card live `editedAt` read". The comparison basis changes from edit id to `editedAt`.
- `chat-embedded-posts`: the version-anchor requirement's description is corrected (it is no longer the banner's comparison input). Scenarios and server behaviour are unchanged.

## Impact

- **Mobile (`:mobile:app`)**: `ChatThreadViewModel` (new `PostEditFlow` dependency, per-post live read), `ChatThreadUiState.kt` (gate signature, row field, merge model), `ChatMessageList.kt` (reads `row.editedSinceShared`), `ChatThreadScreen.kt` (injects `PostEditFlow`). Tests: `EmbeddedPostProjectionTest`, `ChatThreadViewModelTest`, `ChatThreadScreenTest`, plus the Koin test modules that host the thread (Android + iOS).
- **`:infra:supabase-realtime`**: KDoc only (`EmbeddedPostSnapshot.editedAt` now also feeds the banner).
- **Backend / admin**: none. No new route, wire field, migration, or string. The existing `single-post-read` `editedAt` is the source (docs/12: the backend layer already ships, so this change is the mobile consumer).
- **Network**: one `GET /api/v1/posts/{id}` per distinct live shared post per thread visit.
