## Context

The chat foundation (V14, `chat-conversations` + `chat-realtime-broadcast` + `mobile-chat`) was deliberately built forward-compatible for embedded posts: `chat_messages` carries `embedded_post_id`, `embedded_post_snapshot JSONB`, `embedded_post_edit_id`; the empty-message CHECK already accepts embed-only rows; the broadcast payload already serializes the three keys (null today); and the mobile send DTO + `ChatMessageInbound` deliberately drop them. Two pieces were deferred until now: the `embedded_post_edit_id → post_edits(id)` FK (waiting on `post_edits`, shipped at V22) and the `embedded_post_snapshot` size cap (`chat-realtime-broadcast` § "Embedded-payload size cap" names `chat-embedded-posts` as its owner). This change populates the columns end-to-end and ships the two deferred schema pieces.

This is a **full vertical slice** (`docs/12-Integration-Contracts.md`): backend wire contract + realtime payload + mobile client surface, all in this change.

## Goals / Non-Goals

**Goals:**
- Let a user share a post into an existing-or-new 1:1 conversation as a self-contained context card.
- Persist a visibility-respecting, coordinate-free snapshot that survives later edit/hard-delete of the source post.
- Anchor the message to the post's version-at-share-time (`embedded_post_edit_id`) so the thread can show a "diedit sejak dibagikan" banner and (Phase 4 #14) navigate edit history.
- Ship the deferred FK + the snapshot size CHECK (V37).

**Non-Goals:**
- **Admin surface** — none required; embedded-post moderation already flows through shipped chat-message redaction + the source post's own report path (docs/12 §3: justified single-layer omission, not a deferred slice).
- **Sharing anything other than a post** (profiles, external links) — out of scope.
- **Timeline post-card overflow share entry** — v1 ships the post-detail affordance only (see Open Questions).
- **Edit-history *navigation* UI inside the card** (Phase 4 #14 full nav) — this change ships the *anchor* + the "edited-since-shared" banner; the deep version-by-version nav modal is a follow-up that builds on the anchor.
- **Server-side broadcast idempotency / ordering** — unchanged from `chat-realtime-broadcast` (explicit non-goals there).

## Decisions

### D1 — Snapshot is built once, at send time, and is immutable thereafter
The server resolves the post and serializes `embedded_post_snapshot` at send. The card renders from the snapshot, never from a live re-fetch. **Why:** the snapshot must survive hard-delete of the source post (`embedded_post_id` FK is `ON DELETE SET NULL`; the empty-message CHECK keeps the row valid via the snapshot term) and must not leak a post that later becomes blocked/shadow-banned/deleted. **Alternative rejected:** re-resolve the post live on every render — re-introduces per-render visibility joins, breaks the "snapshot survives delete" schema design, and would expose post state changes the sender never shared.

### D2 — `embedded_post_edit_id` stores the post's latest `post_edits.id` at share time (version anchor), NULL if unedited
The thread shows a "diedit sejak dibagikan" banner when the **live** post's current latest `post_edits.id` differs from the message's `embedded_post_edit_id`. **The banner comparison uses the `embedded_post_edit_id` anchor (the edit-row id), NOT the snapshot's `editedAt` timestamp** — the snapshot `editedAt` is for the card's own "Diedit" label, while the anchor is the share-time version handle; an implementer must not conflate the two. **Why store the edit id, not just `editedAt`:** it is an FK-integral version handle that the Phase 4 #14 edit-history nav navigates by; a bare timestamp gives no version to open. **Alternative rejected:** snapshot `editedAt` only — no FK, no version handle for the follow-up nav.

### D3 — Visibility is resolved for the SENDER as viewer; the snapshot is static relayed content; live navigation re-applies the *viewer's* rules
At send, the post is resolved through the sender's visibility view (block / shadow-ban / fuzzing). The recipient sees the resulting static snapshot regardless of the recipient's own relationship to the author — the same model as a sender pasting quoted text. **Tapping the card navigates to `GET /api/v1/posts/{id}`, which re-applies the *tapping viewer's* live rules** (a recipient who blocked the author gets the post-detail constant-404; a since-deleted post is handled as the hard-delete state). **Why:** keeps the message an immutable artifact the sender authored, while ensuring no card grants live access that bypasses the tapper's own block/shadow-ban state. **Alternative rejected:** re-filter the snapshot per recipient — the snapshot is already committed and broadcast; per-recipient filtering of a stored message has no coherent semantics.

### D4 — Spatial fuzzing: the snapshot carries `cityName` only, never `latitude`/`longitude` (the highest-risk invariant)
The snapshot DTO mirrors the shipped `single-post-read` projection (author handle + display name, content, `cityName`, `createdAt`, `editedAt`), which already omits coordinates. A **negative-guard test** asserts the serialized JSONB contains no `lat*`/`lng*`/`longitude`/`latitude` key. **Why:** `display_location`-only is a CLAUDE.md critical invariant; a snapshot is a new serialization path that must not regress it.

### D5 — Size cap via schema CHECK paired with a verified Supabase Realtime budget
`CHECK (octet_length(embedded_post_snapshot::text) < 4096)` (V37). A 280-char post + author/city/timestamp metadata serializes well under 4 KB, and 4 KB sits far under Supabase Realtime broadcast's per-message limit. The exact Realtime limit is verified by a dated `tasks.md` item before finalizing the constant (external-dependency sanity check; see Open Questions). **Why a schema CHECK, not app-only:** `chat-realtime-broadcast` assigns *schema-layer* enforcement here, so a malformed oversized snapshot can never be persisted or broadcast.

### D6 — Mobile reuses the shipped state-holder / nav / data-layer patterns (no new pattern)
- **Share entry point:** post-detail gains a "Bagikan ke chat" action that emits a Navigation 3 event to a new `ConversationPickerRoute(postId)`.
- **Picker:** a new `ConversationPickerViewModel` on the shipped single-`stateIn` `uiState` shape (audit #406/#409 convention); on pick it calls the shipped `createOrReturnConversation(recipientUserId)` then sends with `embeddedPostId`.
- **Thread render:** the existing `ChatThreadViewModel` (single-`stateIn`, audit #414/#415) surfaces embed messages; a new `EmbeddedPostCard` composable renders the snapshot.
- **Data layer:** the chat send `ApiClient` gains the optional `embeddedPostId`; `:infra:supabase-realtime`'s `ChatMessageInbound` gains the embedded fields as plain data (D7).

### D7 — `ChatMessageInbound` gains the embedded fields as plain data; the vendor import stays in the impl
The `:infra:supabase-realtime` interface model adds `embeddedPostId: Uuid?`, `embeddedPostSnapshot` (a plain Kotlin model / `JsonElement` decoded to a domain `EmbeddedPostSnapshot`), `embeddedPostEditId: Uuid?`. The supabase-kt symbol stays confined to `SupabaseChatRealtimeSubscriber`. **Why:** the existing `mobile-chat` invariant ("interface source file has no vendor import") must hold; this change MODIFIES the "inbound omits embedded fields" requirement but preserves the no-vendor-import one.

### D8 — `content` is optional when an embed is present (server CHECK already allows it)
The send DTO becomes `{ content: String?, embeddedPostId: String? }` with an at-least-one-of guard, mirrored client-side. The 2000-char content guard is unchanged when content is present.

### D9 — Admin redaction suppresses the card by render precedence, not by scrubbing the snapshot
The shipped chat redaction path nulls `content` only; it does not clear the `embedded_*` columns. To keep a redacted message from still showing the shared post, the **mobile render applies redaction precedence**: a message with `redacted_at` set renders the neutral redaction placeholder and SHALL NOT render the context card, even when a snapshot is present (the placeholder check keys on `redacted_at`, not on a null `content`, so an embed-only-but-not-redacted message still renders the card). **Why display-suppression, not at-rest scrub:** the moderation contract is "the participant no longer sees the content," which display precedence satisfies without a new admin write path or a backfill; deeper at-rest snapshot scrubbing is out of scope (none is needed for the moderation goal). **Review-surfaced** (security/general lenses): without this rule a redacted embed would leak the shared post.

### D10 — Snapshot author identity is NOT re-anonymized on author tombstone (explicit deferral)
The snapshot freezes the author handle/display-name at share time (D1). A later author **account**-tombstone (not just post hard-delete) would leave the live `single-post-read` path showing "Akun Dihapus" while the frozen snapshot keeps the real identity — a UU-PDP erasure-completeness gap. This is captured as an **explicit deferred requirement** in the `chat-embedded-posts` spec (positive + negative-guard scenario) and tracked by follow-up [#425](https://github.com/aditrioka/nearyou-id/issues/425), per the project's "capture deferred behaviors as explicit spec requirements" rule — so a future change MODIFIES the requirement instead of rediscovering the gap. Bounded: the recipient already saw the identity at share time, and the card grants no live access. **Review-surfaced** (security lens).

### Standards conformance (`docs/11` § Pattern Registry)
- **Backend layering:** route → service (snapshot resolver) → repository read; reuses the existing chat send transaction + AFTER-commit `ChatRealtimeClient.publish(...)`. No new layering pattern.
- **Mobile state holder:** single-`stateIn` `uiState` ViewModels (`ConversationPickerViewModel` new; `ChatThreadViewModel` reused). **Navigation:** Navigation 3 `rememberNavBackStack` route. **Data layer:** `ApiClient` interface. No deviation.
- **No Pattern-Registry deviation → no `docs/11` amendment task** in this change.

## Risks / Trade-offs

- **Spatial-fuzzing leak in the new snapshot path** → snapshot built from the coordinate-free `single-post-read` projection + a negative-guard test asserting no lat/lng key in the JSONB (D4).
- **Recipient sees a card for an author they blocked** (D3) → deliberate: the snapshot is static relayed content; tapping re-applies the tapper's live rules (constant-404 on a blocked author). No live access is granted by the card.
- **Realtime payload exceeds Supabase's per-message limit** → schema CHECK (D5) + a dated verification of the actual Realtime limit before fixing the constant.
- **Snapshot staleness (post edited or deleted after share)** → by design: the "diedit sejak dibagikan" banner and the hard-deleted "post telah dihapus" state cover both; tapping always resolves the live post.
- **V37 on existing rows** → the FK is `NOT VALID` + `VALIDATE CONSTRAINT` (no long write lock; V9/V16 precedent); existing `embedded_post_edit_id` are all NULL so VALIDATE is trivially satisfied, and existing `embedded_post_snapshot` are all NULL so the size CHECK passes.

## Migration Plan

- **V37** is additive and online: `ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_embedded_post_edit_id_fkey FOREIGN KEY (embedded_post_edit_id) REFERENCES post_edits(id) ON DELETE SET NULL NOT VALID;` then `VALIDATE CONSTRAINT …;` plus `ADD CONSTRAINT chat_messages_embedded_snapshot_size_check CHECK (octet_length(embedded_post_snapshot::text) < 4096) NOT VALID;` then `VALIDATE`.
- **Rollback:** drop both constraints — no embed rows exist until the backend ships, so there is no data to unwind.
- **Deploy order:** V37 (backend release) → mobile release. The backend send-path is additive (existing `{ content }` sends unaffected), so an old mobile client keeps working; a new mobile client against an un-migrated backend simply never receives a populated embed (graceful).
- **Flag:** none — the feature is additive UI surfaced by the new share affordance; no kill switch required.

## Open Questions

1. **Snapshot size-cap constant (4096 bytes)** — pending the dated verification of Supabase Realtime broadcast's current per-message size limit (`tasks.md` Phase 1 verification item). If the limit is materially smaller than expected, lower the CHECK constant before the migration is authored.
2. **Share entry point breadth** — v1 ships the post-detail "Bagikan ke chat" affordance only. Adding the entry to the timeline post-card overflow menu is a deliberate follow-up (avoids a `mobile-post-card` MODIFY in this already-wide change). Confirm v1 scope is post-detail-only.
