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

- [ ] M1. App shell: navigation graph (Nav3), DI modules, theme, scaffolding/insets
- [ ] M2. Design system / shared components (cross-change coherence: post card, feeds, loading/error/empty states)
- [ ] M3. Auth + session feature (sign-in, age gate, token storage, refresh flow)
- [ ] M4. Timeline features (nearby/global/following+placeholder, home tab host, pager)
- [ ] M5. Post creation + post detail
- [ ] M6. Data layer (Ktor client setup, repos, DTOs, error mapping) + location services
- [ ] M7. State management patterns (ViewModels, UiState shapes, flows) — consistency sweep
- [ ] M8. Analytics consent + notifications + bottom-nav sections (recently merged changes)

## Native specifics

- [ ] N1. androidMain: MainActivity, lifecycle, permissions, location actuals, token storage (DataStore+Tink), credential manager
- [ ] N2. iosMain: app entry, lifecycle, permissions, CLLocationManager actuals, Keychain, expect/actual completeness
- [ ] N3. iosApp host project + build config sanity

## Final gates

- [ ] G1. `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`
- [ ] G2. `:mobile:app:testDevDebugUnitTest` + `:mobile:app:testDevReleaseUnitTest`
- [ ] G3. iOS: `linkDebugFrameworkIosSimulatorArm64` (if iosMain touched)
- [ ] G4. PROGRESS.md final summary + flagged items
