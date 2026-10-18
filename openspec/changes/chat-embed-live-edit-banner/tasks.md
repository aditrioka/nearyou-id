## 1. Pure gate + row projection (design D1, D3, D4)

- [ ] 1.1 `ChatThreadUiState.kt`: `editedSinceShared(sharedEditedAt, liveEditedAt)` compares as `kotlin.time.Instant` via `parseOrNull` (unknown/unparseable live → false); KDoc drops the "DEFERRED" note
- [ ] 1.2 `ChatThreadUiState.kt`: `ChatMessageRow.editedSinceShared: Boolean = false`; `chatMessageRows(merged, viewerId, liveEditedAt: Map<String, String?> = emptyMap())` computes it for live, non-redacted cards only
- [ ] 1.3 `ChatThreadUiState.kt` + `ChatThreadViewModel.kt`: drop `embeddedPostEditId` from `ChatMessage` / `ChatMessageRow` / `mergeOne` / `toChatMessage` / `onRealtime` (wire DTO + inbound keep it)

## 2. Live edit-state source (design D2)

- [ ] 2.1 `ChatThreadViewModel`: new `postEditFlow: PostEditFlow` dependency; a `postId → editedAt?` map; after each recompute, claim + read every unseen live-card post id via `refreshPost` in `viewModelScope`; `Loaded` stores `editedAt` and recomputes, `Unavailable` stays unknown
- [ ] 2.2 `ChatThreadScreen.kt`: `koinInject<PostEditFlow>()` passed into the VM (production `mobileModule` already binds it)
- [ ] 2.3 `ChatMessageList.kt`: pass `row.editedSinceShared` to `EmbeddedPostCard` (the composable no longer calls the gate)
- [ ] 2.4 `ChatRealtimeSubscriber.kt` (`:infra:supabase-realtime`): `EmbeddedPostSnapshot.editedAt` KDoc — it now feeds the banner; `embeddedPostEditId` is the edit-history anchor

## 3. Tests (one per spec'd scenario)

- [ ] 3.1 `EmbeddedPostProjectionTest`: gate truth table (edited after share; unedited-then-edited; equal; both null; unknown live; equal instants in different ISO forms; unparseable → false); row projection carries `editedSinceShared` only for live non-redacted cards (redacted + deleted-source → false even when the map says edited)
- [ ] 3.2 `ChatThreadViewModelTest`: banner fires from a `Loaded` live read newer than the snapshot; equal → no banner; `Unavailable` → no banner; in-flight (suspended read) → no banner until it completes; one read per distinct post id across first page + older page + realtime; no read for deleted-source / redacted embeds
- [ ] 3.3 `ChatThreadScreenTest`: an embed message whose live read is newer renders `EMBEDDED_POST_EDITED_BANNER_TAG`; an unchanged one does not (real VM + Koin `FakePostEditFlow`)
- [ ] 3.4 Every Koin test module that hosts `ChatThreadScreen` binds a `PostEditFlow` (Android `*ScreenTest`s + iOS `ChatThreadReportFlowIosTest` / `FlowTestKoin`)

## 4. Verification & lifecycle

- [ ] 4.1 Gates: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` + `:mobile:app:ktlintCheck :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` + `:infra:supabase-realtime` tests + `:mobile:app:iosSimulatorArm64Test`
- [ ] 4.2 Manual verify (verify-loop): Android emulator AND iOS simulator screenshots of a thread showing a shared post edited after the share (banner) next to an unchanged one (no banner); evidence in the PR body
- [ ] 4.3 Sub-agent review (one general lens — no migration, no security-invariant path); apply findings; PR body carries the `Review findings applied` line
- [ ] 4.4 Archive: sync both delta specs; update `mobile-chat-embedded-posts` Purpose (the banner is no longer dormant); `Closes #440`
