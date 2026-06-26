## 1. Preflight — external-dependency + sequencing sanity checks (do FIRST)

- [ ] 1.1 Dated verification of Supabase Realtime broadcast's current per-message payload size limit (WebSearch + Supabase docs, dated). Confirm a `< 4096`-byte `embedded_post_snapshot` keeps the full broadcast payload (id + ids + content + snapshot) comfortably under that limit; if the limit is materially smaller, lower the V37 CHECK constant + design D5 before authoring the migration.
- [ ] 1.2 Confirm the next free Flyway version at apply time: `ls backend/ktor/src/main/resources/db/migration | sort -V | tail`. Main is at V35; #417 (`post-area-density-cap`) adds V36. Use the next free number (expected **V37**); if taken by a sibling that merged first, `git mv` up before parity fails (the parallel-session Flyway-collision precedent).
- [ ] 1.3 Confirm `post_edits` (V22) exists in the migration history (the FK target) and note its PK column name/type for the FK reference.

## 2. Schema — V37 migration (FK + size cap)

- [ ] 2.1 Author `V37__chat_embedded_post_edit_fk_and_snapshot_size.sql`: add `chat_messages_embedded_post_edit_id_fkey` FK to `post_edits(id) ON DELETE SET NULL` via `ADD CONSTRAINT … NOT VALID` then `VALIDATE CONSTRAINT` (V9/V16 deferred-FK pattern).
- [ ] 2.2 In the same migration, add `chat_messages_embedded_snapshot_size_check CHECK (embedded_post_snapshot IS NULL OR octet_length(embedded_post_snapshot::text) < 4096)` via `ADD CONSTRAINT … NOT VALID` then `VALIDATE CONSTRAINT`.
- [ ] 2.3 Replace the `embedded_post_edit_id` `COMMENT ON COLUMN` deferral text with text describing the now-shipped FK (mentions `post_edits(id)` + `SET NULL`), matching the V16 `redacted_by` comment-replacement precedent.
- [ ] 2.4 Verify `supabase/migrations` parity (the Flyway↔Supabase migrate CI lane) and that the migration applies cleanly on a fresh DB.
- [ ] 2.5 **Amend `docs/05-Implementation.md` § Direct Messaging — Chat Message Schema (Embedded Post)** to add the embedded-snapshot size CHECK to the canonical `chat_messages` schema block, so the `chat-conversations` "match docs/05 verbatim" requirement stays true (the canonical schema doc currently omits it). Keep the SQL block consistent with V37 (FK already shown live there).

## 3. Backend — send-path embeds a post

- [ ] 3.1 Extend the chat send request DTO to accept an optional `embedded_post_id` alongside `content`; add the at-least-one-of (`content` non-empty OR `embedded_post_id` present) guard, keeping the existing 2000-char content guard when `content` is present.
- [ ] 3.2 Implement the embedded-post resolver (service + repository read): resolve the post **for the sender as viewer** through `visible_posts` + the bidirectional `user_blocks` NOT-IN join (`BlockExclusionJoinRule`) + auto-hide/soft-delete filters; a non-visible/non-existent/soft-deleted post returns the project constant-404 (forbidden indistinguishable from absent).
- [ ] 3.3 Build the `embedded_post_snapshot` JSONB from the coordinate-free `single-post-read` projection (author handle + display name, `content`, `cityName`, `createdAt`, `editedAt`); assert **no** `latitude`/`longitude`/author-UUID field is included (spatial-fuzzing invariant — the highest-risk path).
- [ ] 3.4 Set `embedded_post_edit_id` to the source post's most recent `post_edits.id` at send time (NULL when unedited) — the version-at-share anchor.
- [ ] 3.5 Persist via the existing chat send transaction (INSERT `chat_messages` + UPDATE `conversations.last_message_at`); pass the populated `embedded_*` fields into the AFTER-commit `ChatRealtimeClient.publish(...)` projection.
- [ ] 3.6 Confirm the publish-side shadow-ban skip still governs embed messages (row persists, no broadcast for a shadow-banned sender).

## 4. Realtime — broadcast populates the embedded fields

- [ ] 4.1 Populate `embedded_post_id` / `embedded_post_snapshot` / `embedded_post_edit_id` in the `ChatMessageBroadcast` projection for embed messages (serialization is already forward-compatible; this just fills the values).
- [ ] 4.2 Verify the serialized payload shape: plain message → three keys present-with-null; embed message → populated (per the modified `chat-realtime-broadcast` Payload-schema delta).

## 5. Backend tests

- [ ] 5.1 Send-path: embed-only accepted, content+embed accepted, neither rejected (400), over-length content rejected (400).
- [ ] 5.2 Visibility: blocked-author / shadow-banned-author / soft-deleted / non-existent all return the identical constant-404; visible post → snapshot persisted.
- [ ] 5.3 **Spatial-fuzzing negative test**: the persisted/serialized `embedded_post_snapshot` JSON contains no `latitude`/`longitude`/`lat`/`lng` key and no author UUID.
- [ ] 5.4 Anchor: unedited post → `embedded_post_edit_id` NULL; edited post → latest `post_edits.id`.
- [ ] 5.5 Size CHECK: an oversized snapshot INSERT is rejected; `pg_constraint` shows the FK on `embedded_post_edit_id` validated (`confrelid` = `post_edits`, `confdeltype = 'n'`, `convalidated = true`).
- [ ] 5.6 Broadcast: embed message publishes populated fields; shadow-banned sender embed persists but does not broadcast (`!network` CI-equivalent tag where the spec uses the DB).
- [ ] 5.7 Snapshot-survives-hard-delete: persist an embed message of post P, hard-delete P, assert the `chat_messages` row's `embedded_post_id` is set NULL by the FK while `embedded_post_snapshot` remains intact and the row stays valid under the empty-message CHECK; assert the `embedded_post_edit_id` deferred-comment text is removed post-V37 (`pg_description`).

## 6. Mobile — data layer

- [ ] 6.1 `:infra:supabase-realtime`: add `embeddedPostId: Uuid?`, `embeddedPostSnapshot: EmbeddedPostSnapshot?` (a plain vendor-free model decoded from the snapshot JSON), `embeddedPostEditId: Uuid?` to `ChatMessageInbound`; keep `redactionReason` off the model; keep the interface source vendor-import-free (impl-only supabase-kt).
- [ ] 6.2 Extend the chat send `ApiClient` method to optionally carry `embedded_post_id`; mirror the at-least-one-of guard client-side.
- [ ] 6.3 Map the REST history read (`GET /api/v1/chat/{id}/messages`) embedded fields onto the same client message model used by the thread (so REST + realtime render identically).

## 7. Mobile — UI (consult the chat-thread mockup frame first)

- [ ] 7.1 Render the chat-thread mockup frame (`dev/mockups`, `docs/11` § 2.8) + generate the per-frame measurement annex before building the card.
- [ ] 7.2 Post-detail: add the "Bagikan ke chat" affordance emitting a Navigation 3 event to a `ConversationPickerRoute(postId)`.
- [ ] 7.3 `ConversationPickerViewModel` (single-`stateIn` `uiState`) + picker screen: list recipients/conversations, reuse `createOrReturnConversation(recipientUserId)`, send with `embedded_post_id`, map 403/400/404 to distinct results, navigate to the thread on success.
- [ ] 7.4 `EmbeddedPostCard` composable: render the snapshot (author, content, city — no coordinate); tap → navigate to live post-detail when `embeddedPostId` is non-null.
- [ ] 7.5 Edited-since-shared banner: show "diedit sejak dibagikan" when the live post's latest edit differs from the message `embeddedPostEditId`.
- [ ] 7.6 Hard-deleted source post (snapshot present, `embeddedPostId` null): render the "post telah dihapus" state, not tappable.
- [ ] 7.7 Add all new `Res.string.*` keys (Bahasa Indonesia) — share action, banner, deleted state, picker labels; verify the no-hardcoded-UI-strings grep.

## 8. Mobile tests

- [ ] 8.1 Picker VM: recipient pick → embed send + navigate; 403 → blocked result (no nav).
- [ ] 8.2 Thread render: embed message → context card with snapshot fields, no coordinate; tap navigates for a live-post card.
- [ ] 8.3 Banner: anchor != live latest-edit → banner shown; equal/both-unedited → no banner.
- [ ] 8.4 Deleted state: snapshot present + `embeddedPostId` null → "post telah dihapus", no navigation.
- [ ] 8.5 Inbound-model: `ChatMessageInbound` exposes the embedded fields as vendor-free types, no `redactionReason`; interface-source vendor-import scan is clean.

## 9. Verification + Definition of Done (docs/11 §5)

- [ ] 9.1 Run the local pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (both lint frameworks).
- [ ] 9.2 Manual verification evidence (docs/11 §5 DoD): backend send-with-embed via the running app/curl + mobile share→thread→card→tap→banner/deleted-state on emulator/device; capture screenshots into the PR body per the verify-loop.
- [ ] 9.3 `openspec validate chat-embedded-posts --strict` is clean; confirm no Pattern-Registry deviation (no `docs/11` amendment needed).
