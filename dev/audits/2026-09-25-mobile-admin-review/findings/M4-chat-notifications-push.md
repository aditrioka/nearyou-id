# M4 — mobile chat, notifications, push

### A. Completion matrix
| Capability | Reqs | Completion | Biggest gap |
|---|---|---|---|
| mobile-chat | 15 | ~80% | send path renders only Blocked; NetworkRetry/TooLong/429 silently drop the optimistic bubble (C1, C2); no thread pull-to-refresh (C5) |
| mobile-chat-embedded-posts | 8 | ~95% | edited-since-shared banner has no live source (spec'd deferral, #440); test gaps (C9) |
| mobile-chat-message-report | 7 | ~95% | iOS-sim run of `ChatThreadReportFlowIosTest` owed (#383) |
| mobile-notifications-list | 17 | ~95% | deferred actor username (#194) + reply/actor-less deep links (#379); stale Purpose (C10) |
| mobile-push-message-handling | 8 | ~80% | iOS path inert — no NSE target in `iosApp.xcodeproj` (#430); `offerPushTapRouting` untested + late delegate install (C7) |
| mobile-fcm-token-registration | 14 | ~85% | spec met on paper, but iOS never gets a token — FirebaseApp never configured (C3); iOS asks permission at shell entry (C4) |
| content-moderation-appeal (client) | 2 | 100% | deferral honoured; status read renders `Decided` (`AppealUiState.kt:26`, `AppealScreenTest.kt:32`); proactive notification = #390 |
| in-app-notifications (mobile clauses) | 1 | 50% | null-`preview` `chat_message` rows get no localized fallback (C11) |

### B. Follow-up validation
| # | Classification | Evidence | Scope |
|---|---|---|---|
| 194 | still-valid-openspec | no `actor_username` in `NotificationRoutes.kt:120-125` or mobile DTO | backend joins `visible_users` via `ActorUsernameLookup` → nullable `actor_username` on `NotificationDto`; mobile "{username} …" copy incl. "Pesan baru dari {username}", generic fallback when null. MODIFY mobile-notifications-list generic-actor requirement + in-app-notifications § GET list |
| 197 | still-valid-openspec | `AppShellViewModel.kt:46,53` fetch once + `AppShellScreen.kt:224` on section leave; nothing on push/resume | invalidate via shared signal from Android `IncomingPushHandler` / iOS delegate (`PushTapNavSignal` idiom) + refetch on ON_RESUME. MODIFY mobile-home-tab-host "Badge is one-shot" |
| 272 | still-valid-openspec | `ChatRoutes.kt` has 4 routes, no read/unread endpoint | backend `PATCH /api/v1/chat/{id}/read` (writes `last_read_at`) + `unread_count` on list rows; mobile calls on thread open + row badge. Amends chat-conversations + mobile-chat |
| 280 | still-valid-defer | not run; no chat realtime evidence | Trigger: staging two-device session. iOS realtime can't work yet — `Info.plist` only has `ApiBaseUrl`, `SupabaseConfig.kt:14-15` falls back to placeholders; iOS half of #273 (SupabaseUrl/SupabaseAnonKey in xcconfig + Info.plist) must land first |
| 286 | still-valid-regular-pr | untouched since #423/#415 | `mockup-measure.sh` frames 2 + 5, reconcile ConversationList/ChatThread spacing + tokens; combine with C6 |
| 379 | still-valid-openspec | no reply-by-id route, no `GET /api/v1/conversations/{id}` (`ChatRoutes.kt:58-181`); negative guards at `NotificationNavigation` / `NotificationsViewModelNavTest:169,189` | backend conversation-by-id (partner identity) + reply→parent-post read; RENAMED+MODIFIED § "Actor-less and reply-target deep-linking is deferred"; flip negative tests to positive |
| 383 | still-valid-regular-pr | iOS test exists, no recorded run, no redline | run `:mobile:app:iosSimulatorArm64Test` filtered to `ChatThreadReportFlowIosTest` (#348 reds pre-existing); redline DropdownMenu vs frame 5 |
| 440 | still-valid-openspec | `ChatThreadScreen.kt:377` passes anchor as live signal (`editedSinceShared(anchor, anchor)`); snapshot already carries `editedAt` (`ChatRealtimeSubscriber.kt:83`) | no backend change: lazily fetch live post via existing `SinglePostApiClient` minimal read (has `editedAt`), compare to `snapshot.editedAt`, unknown → no banner. MODIFY mobile-chat-embedded-posts banner requirement (D2 edit-id → editedAt) |
| 258 | still-valid-defer | no `google-services.json`, plugin unapplied (`build.gradle.kts:121-125`), only `.plist.template` | Trigger: operator provisions Firebase + APNs. Scope must include code too — see C3 |
| 430 | still-valid-defer | no NSE target / App Group / entitlement in `iosApp.xcodeproj`; `NotificationService.swift` inert source | Trigger: operator Xcode / Apple Developer setup. Pair with C7 |
| 390 | still-valid-openspec | spec'd deferral (§ "Decision outcome surfaced via status read, proactive notification deferred"); no emit in `AppealReviewRepository.kt` | new notification type: migration widening V10 CHECK, `body_data {appeal_id, approved}`, in-tx emit in approve/reject tx, `PushCopy` entry, mobile row copy + tap to `AppealRoute`. MODIFY content-moderation-appeal + in-app-notifications |

### C. New gaps
1. **[high]** Free-tier chat daily cap (429 `rate_limited`, 50/day) has no client surface — `ChatRepository.kt:54-58` maps 429 → `SendOutcome.Error` → NetworkRetry (never rendered) → optimistic bubble vanishes, no upsell/countdown. Frame 18 + docs/03 § Rate Limit Communication require the upsell modal for "chat 50/hari". Spec: mobile-chat § "Send message with client-side guard, optimistic append, and reconcile" lists no rate-limited state — docs/12 cohesion gap vs chat-conversations § daily limit. Evidence `ChatRepository.kt:54`, `ChatThreadScreen.kt:248` — openspec
2. **[medium]** `sendBarState` computes NetworkRetry + TooLong but only Blocked renders; transport failure drops bubble silently; too-long scenario only disables send — mobile-chat § Send message… — `ChatThreadScreen.kt:248-257`, `ChatThreadViewModel.kt:195-207` — regular-pr
3. **[medium]** iOS never calls `FirebaseApp.configure()` and has no `UIApplicationDelegateAdaptor` handing the APNs token to Firebase → even after #258 no iOS token — mobile-fcm-token-registration § "FcmTokenProvider is a platform-free commonMain seam…" — `iOSApp.swift:7-14`, `IosFcmTokenProvider.kt:45` — regular-pr (fold into #258)
4. **[medium]** iOS requests notification authorization at authenticated-shell entry (`AppShellScreen.kt:146-149` → `IosFcmTokenProvider.kt:70,81`), contradicting docs/03:74 ("at the first chat message … not at onboarding"); chat first-send rationale never shows on iOS (gated on `NOT_DETERMINED`, `ChatThreadScreen.kt:171-173`). APNs device-token registration doesn't need authorization — spec rationale doesn't hold. Specs: mobile-fcm-token-registration § "iOS requests only the minimal notification authorization…" + mobile-chat § "First-send notification-permission prompt" — openspec
5. **[medium]** No pull-to-refresh on chat thread (`PullToRefreshBox` only at `ConversationListScreen.kt:99`; `retry()` only on full-screen error, `ChatThreadScreen.kt:242`); spec wants refresh-over-content + "REST-only mode (send via REST + pull-to-refresh)" — mobile-chat § "ChatThreadScreen renders the 1:1 thread" + § "Reconnect resyncs via REST…" — regular-pr
6. **[low]** Conversation rows omit partner @username + premium badge though projection has both (`ConversationListUiState.kt:14-20` vs `ConversationListScreen.kt:170-174`) — mobile-chat § "Conversation rows render partner display identity…" — regular-pr
7. **[low]** iOS push-tap fragile: delegate installed at Compose root (`MainViewController.kt:25`), no app delegate → cold-start tap likely lost; no `willPresent` → foreground pushes suppressed; `offerPushTapRouting` untested though KDoc claims tested (`PushNotificationTapDelegate.kt:35`) — mobile-push-message-handling § "Tapping a push deep-links…" — regular-pr
8. **[low]** First-send prompt is once-per-process not once-per-install (`NotificationPermissionController.kt:39-47`); no behavioural test (`ChatThreadScreenTest.kt:86-87` DI only) — mobile-chat § "First-send notification-permission prompt" — regular-pr
9. **[low]** Test gaps: canonical channel name (`SupabaseChatRealtimeSubscriber.kt:85`); `ChatThreadRoute`/`ConversationListRoute` missing from `NavKeySerializationTest` (only Picker at :65); "Bagikan ke chat" → picker-with-postId unasserted (`PostDetailScreenTest.kt:573` presence only); picker empty state (`ConversationPickerViewModelTest`) — regular-pr
10. **[low]** mobile-notifications-list Purpose (`spec.md:4`) still says deep-link tap-through deferred — shipped — regular-pr (docs)
11. **[low]** Null-`preview` `chat_message` renders base copy with no localized fallback (`NotificationsUiState.kt:29`) — in-app-notifications § "body_data shape per emitted type" (:284) — regular-pr

### D. Unspecced product surface
1. Conversation-list extras (frame 2): SearchBar, last-message preview line, online-presence dot, "new message" FAB (start conversation from list). Unread badge = #272
2. Chat-thread extras (frame 5): read receipts (`done_all`), per-message timestamps, partner header with city + distance
3. Frame 5 pending-embed preview above input (✕-dismissable) when entering via "kirim pesan" — shipped picker sends immediately
4. docs/02 § Embedded Post Behavior: soft-deleted source post → "Post ini sudah dihapus" banner on card (only hard-delete spec'd/built)
5. docs/03 § Chat Context Card UX: post detail from embed shows "Post ini sudah di-edit setelah kamu chat" + "Riwayat edit" modal highlighting chat-time version (distinct from #440)
6. docs/03:74: notification permission also on first chat message RECEIVED (only first-send spec'd)
7. Frame 4: "Premium tier-up" notification type (marked proposal), per-type icon containers, day grouping ("Hari ini")
