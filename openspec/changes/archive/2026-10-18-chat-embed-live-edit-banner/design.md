## Context

`chat-embedded-posts` shipped the context card, the "diedit sejak dibagikan" banner render, and the pure gate `editedSinceShared(anchorEditId, liveLatestEditId)`. Its design D2 compared the message's `embedded_post_edit_id` with the live post's latest `post_edits.id`. The thread fetches no live post state, so the runtime passes the anchor as its own live signal and the banner never shows (#440). The triage comment on #440 vetted option 2, which needs no backend change: compare `editedAt` through the existing `single-post-read` read.

Facts this design rests on (verified on `origin/main` dcfa6f28):

- The snapshot's `editedAt` is `MAX(post_edits.edited_at)` at share time (`JdbcEmbeddedPostResolver`), serialized with `Instant.toString()` (`ChatService.buildEmbeddedPostSnapshotJson`).
- `single-post-read`'s `editedAt` is `MAX(post_edits.edited_at)` at read time (`JdbcSinglePostRepository`), serialized the same way (`PostReadService`).
- `post_edits` has `UNIQUE (post_id, edited_at)` (`V22__post_edits.sql`, `post_edits_temporal_idx`). Edit rows are append-only; they go only through the `posts` cascade.
- Mobile already reads that field through `PostEditFlow.refreshPost(postId)` → `PostRefreshOutcome.Loaded(content, editedAt, …)` / `Unavailable`. Post-detail uses it for its "Diedit" label.

## Goals / Non-Goals

**Goals:** the banner fires at runtime when the live post was edited after the share; no false positive when live state is unknown; no backend change.

**Non-Goals:**

- **Refreshing live edit state while the thread stays open.** This covers pull-to-refresh, realtime, and returning from post-detail. The read runs once per post per visit. An edit made while the thread is open shows on the next visit.
- **A batch "edit state for N posts" endpoint.** Shares are a small fraction of messages, so per-card reads are cheap. Add the endpoint if a thread ever carries enough shares to matter.
- **The docs/03 § Chat Context Card UX post-detail banner** ("Post ini sudah di-edit setelah kamu chat") **and the edit-history highlight.** Both belong to the edit-history navigation (docs/08 Phase 4 #14), which is what the `embedded_post_edit_id` anchor exists for.

## Decisions

### D1 — Compare `editedAt`, not the edit id

The banner shows when the live `editedAt` is known and differs from the snapshot's `editedAt`. The values are compared as instants (stdlib `kotlin.time.Instant.parseOrNull`), not as strings, so a formatting difference between two equal instants cannot raise a banner. If either value fails to parse, the banner stays hidden.

This is equivalent to the original edit-id comparison, not an approximation. Because `(post_id, edited_at)` is unique and edit rows are append-only, `MAX(edited_at)` names exactly one row, the latest edit. The original anchor was chosen by `ORDER BY edited_at DESC` too, so both pick the same row. If `clock_timestamp()` ever stepped backwards, a new edit could leave `MAX` unchanged. The banner would then be missed, which fails in the safe direction. The table below shows each case.

| share-time `editedAt` | live `editedAt` | meaning | banner |
|---|---|---|---|
| null | null | never edited | no |
| null | T | edited after the share | yes |
| T | T | no edit since the share | no |
| T1 | T2 ≠ T1 | edited again after the share | yes |
| any | unknown | read in flight / failed / `404` | no |

**Alternative rejected: option 1, a backend wire that exposes the latest edit id.** It adds a field to `single-post-read` (or the edits list) for no extra correctness, which makes it a cross-layer change for an equivalent signal.

### D2 — The source is `PostEditFlow.refreshPost`, read once per post per thread visit

`ChatThreadViewModel` takes a `PostEditFlow`, the existing repository seam behind `PostEditRepository`. docs/11 §2.6 says ViewModels talk to repositories, never to ApiClients. `refreshPost` already maps every failure to `Unavailable`, so no exception reaches the VM.

The VM keeps a `postId → live editedAt` map:

- After each recompute, every live card row (non-redacted, `embeddedPostId != null`) whose post id is not yet in the map is claimed (`null` = unknown) and read in `viewModelScope`.
- A `Loaded` result stores its `editedAt` and recomputes. `Unavailable` leaves the entry unknown.
- A post shared in several messages is read once.

Reads stay cancellable. They are reads, and docs/11 §2.2 reserves `NonCancellable` for writes.

The read runs under the viewer's own visibility rules (`resolveVisiblePost`). A post the viewer can no longer see (block in either direction, shadow-banned author, auto-hidden, soft-deleted) returns `404 → Unavailable`, so the banner never reveals edit activity on content the viewer cannot open.

### D3 — The gate result is a row field inside the single `uiState`

`chatMessageRows(merged, viewerId, liveEditedAt)` computes `ChatMessageRow.editedSinceShared` with the pure gate. The rows already flow through the VM's one `uiState` (`ChatThreadUiState.Content(rows)`), so no second `StateFlow` appears (docs/11 §2.2), and the composable stops calling the gate. A redacted row or a deleted-source row always carries `false`.

### D4 — The client merge model drops `embeddedPostEditId`

After D1 nothing on the client reads the anchor. `ChatMessage` and `ChatMessageRow` drop it, so a dead field labelled "the anchor" cannot mislead the next reader. The wire DTO (`ChatMessageDto.embeddedPostEditId`) and the realtime inbound (`ChatMessageInbound.embeddedPostEditId`) keep it, because they mirror the server payload. The backend keeps stamping the column.

## Risks / Trade-offs

- **[N reads for N shared posts on entry]** → Deduplicated by post id, cancellable, and only for live cards. Upgrade path: the batch endpoint in Non-Goals.
- **[A stale "no banner" for an edit made while the thread is open]** → Accepted (Non-Goals). The next visit corrects it. The failure is the safe direction (never a false positive).
- **[`ChatThreadScreen` resolves one more Koin binding]** → The production `mobileModule` already binds `PostEditFlow`. Test Koin modules that host the thread add a `FakePostEditFlow` binding.
