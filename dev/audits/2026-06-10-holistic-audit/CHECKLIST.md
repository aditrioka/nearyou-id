# Phase 3 review work-list — holistic audit 2026-06-10

Status legend: `[ ]` pending · `[~]` review done, fixes in progress · `[x]` reviewed + fixed + tests green · `[-]` reviewed, no action needed

> Skeleton — areas will be finalized from the repo-map pass before Phase 3 starts.

## Backend (`:backend:ktor` + `:infra:*`) — performance focus

Review pass: DONE for all areas (7 findings files under `findings/`). Fix status below.

- [~] B1. Auth — REVIEWED+PARTIAL: AuthPlugin deletedAt gate + bounded-dispatcher hop + verifier-instance reuse DONE; Apple-S2S OIDC-capture fix DONE (per-subtree gate + InternalRoutingIsolationTest); shared-httpClient timeout DONE; single Apple JwksCache DONE. REMAINING: JwksCache negative-cache/single-flight (01-#6 part), RefreshTokenService atomic claim (01-#15), signup 23505 conflation (01-#13), kid env-config (01-#23), auth-route dispatcher wraps (AuthRoutes/SignupService bodies)
- [~] B2/B3. Post + engagement + timelines — REVIEWED (findings/02): CRITICAL visible_posts shadow-ban gap + H1 deleted_at predicate + H3 batched-Lua + H4 daily-window bug + post-create cap PENDING (wave 2; needs migration + spec amendments)
- [x] B4. Social graph + user/profile — follow/block 50/h+30/h limiters shipped (FollowRateLimiter/BlockRateLimiter + 429 routes + SocialGraphRateLimitTest); follow-vs-block advisory pair-lock (UserPairLock, both repos); services suspend + pool-bounded dispatcher; UserProfileService dispatcher; ActorUsernameLookup annotation repositioned. DEFERRED-FLAGGED: /followers oracle differential (03-#5, QUESTION), bare-ID list N+1 (03-#6 → needs OpenSpec contract change)
- [x] B5. Chat — ChatService suspend + bounded dispatcher (all 4 ops); premium-badge formula aligned to D2; `left_at IS NULL` on partner join. REMAINING (wave 2, 03-#1): moderation verdict computed pre-transaction
- [x] B6. Search + notifications — SearchService/NotificationService bounded dispatcher + suspend; notification unknown-type SQL filter (cursor-truncation fix); Fcm/Consent repos dispatcher
- [ ] B7. Moderation, reports, guard, health, internal — findings/04 read pending triage (wave 2)
- [~] B8. Cross-cutting — DONE: DbDispatchers (pool-sized, DI), Hikari contract (10/maxLifetime/leak/prepareThreshold=0), shutdown grace, CallId+callIdMdc+Compression, StatusPages fixed-message+stack-log, flyway conditional repair, single Lettuce client (RedisHandles), StubRemoteConfig→real adapter (FLAGGED), version currency batch. REMAINING: shared AppJson consolidation (01-#14), engagement/timeline/moderation dispatcher swaps (wave 2 w/ R2 fixes)
- [ ] B9. Admin package — findings/04 light pass pending triage

## Mobile shared (`:mobile:app` commonMain + `:shared:resources`) — coherence focus

Review pass: DONE for all areas (findings/05 + 06). Fix status:

- [-] M1. App shell / Nav3 / DI / theme — REVIEWED-CLEAN on the critical checks (polymorphic NavKey SerializersModule ✓, decorator order ✓, single NavDisplay ✓, theme/font-preload ✓). REMAINING (deliberate, sketches in findings/05): VM single-StateFlow consolidation (#6), koinViewModel declarations (#7), shell unread-badge VM (#9), AuthFlow.handleTerminal401 dead seam (#8), TokenRefresher follower-CE edge (#16), theme CE-rethrow nit (#15)
- [~] M2. Components coherence — notifications trio FIXED (the patchwork exemplar); REMAINING: extract the duplicated list-state kit + post card into `ui/components/` (05-#11/06 duplication map — the §2.1 first move)
- [x] M3. Auth/session — proactive-refresh stale-token-cache fix (05-#4) + end-to-end regression test; HttpClient timeouts (06-#1) + GET-only retry (docs/11 §2.6)
- [~] M4. Timelines — reviewed; REMAINING: reload reentrancy guard, projection memoization, contentType (06 mediums)
- [~] M5. Post create/detail — PostDetail placeholder dots → Material icons (06-#2) + topBar/bottomBar system-bar+IME insets (06-#4); REMAINING: double-submit guards (05-#10), reply append order (06), VM migration (05-#5)
- [x] M6. Data layer — DTO sweep CLEAN (no wire mismatches); notifications terminal-401 → SessionExpired neutral redirect (06-#3, D4 parity); timeouts+retry shipped
- [~] M7. State patterns — notifications VM migrated to the split-flag canon (05-#2); spec amended; REMAINING: 5 remember-only screens → entry-scoped VMs (05-#5 — the largest §2.2 surface, drafts/data-loss risk documented), LocationGate fold (05-#12), collectAsStateWithLifecycle sweep (05-#13)
- [x] M8. Notifications screen — nested Scaffold removed (05-#1), split flags (05-#2), scrollable non-Content states (05-#3), spec MODIFIED accordingly

## Native specifics

- [~] N1. androidMain — `allowBackup=false` (07-#1: poisoned-keyset restore crash). REMAINING (sketches in findings/07): DataStore corruption handler, Tink main-thread init offload, permission-bridge launcher clear, release minify flag
- [~] N2. iosMain — `SecItemAdd` OSStatus surfaced via NSLog (07-#2: silent sign-out). REMAINING: CoreLocation/GIDSignIn main-thread guards + request timeout, `NSLocationDefaultAccuracyReduced` (COARSE parity), deployment-target coherence
- [-] N3. iosApp host — reviewed; deployment-target incoherence flagged (07 medium), no blocking defect

## Final gates — ALL GREEN (2026-06-10, final run exit 0)

- [x] G1. `ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :core:domain:test`
- [x] G2. `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest`
- [x] G3. iOS: `:mobile:app:linkDebugFrameworkIosSimulatorArm64`
- [x] G4. PROGRESS.md final summary + flagged items
