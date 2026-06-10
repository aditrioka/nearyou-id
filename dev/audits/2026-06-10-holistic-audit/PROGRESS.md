# Holistic Audit — 2026-06-10

**Trigger (operator-reported problems):**
1. AI-built features pass build/CI but show bugs + deviations from modern Android/KMP/Ktor best practice on manual testing.
2. Features built across multiple `/next-change` cycles come out patchwork (e.g., home screen UI) — later components don't fit the skeleton built earlier.

**Mandate:** run all 3 phases sequentially without confirmation stops; document every significant decision here; flag ambiguous/risky calls for operator review at the end.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 1 — Research | Verify project standards against 2026-current CMP/M3/Nav3/KMP-libs/Ktor state (web search, dated) | DONE — consolidated into `docs/11-Engineering-Standards.md` |
| 2 — Workflow audit | Fix lifecycle gaps causing the two problems; audit + update skills/docs (Phase-1 alignment, SE principles, concision) | IN PROGRESS |
| 3 — Codebase review | Checklist-driven review+fix: backend Ktor, mobile shared, androidMain/iosMain | PENDING |

## Decision log

- **D1 (artifacts location):** audit artifacts live in `dev/audits/2026-06-10-holistic-audit/` (PROGRESS.md + CHECKLIST.md), committed to the branch so they survive session interruption and ship with the PR. Public-repo posture respected (no secrets, no PII).
- **D2 (delivery shape):** ONE branch, ONE PR (`audit/holistic-2026-06`, pushed from this worktree via `git push origin HEAD:audit/holistic-2026-06`), structured commits per phase/area. Rationale: single narrative for operator review via this file; avoids cross-PR conflicts between docs/skills edits and code fixes; this is an infra/tooling-shaped engagement (regular PR, not OpenSpec). FLAGGED: operator may prefer splitting at review time — commits are structured to allow cherry-pick splitting if so.
- **D3 (baseline):** review targets the worktree base (origin/main @ 3179aa7). Open PRs at audit start: only #165 (docs) + #168 (skill) — no open mobile/backend code PRs, so Phase 3 fixes are conflict-safe.
- **D4 (build on existing substrate, don't duplicate):** the patchwork problem was already partially attacked by #167 (`mobile-design-system` capability + home-shell redesign) and open PR #168 (`mobile-ui-foundation` skill, visual/UX layer). The new `docs/11-Engineering-Standards.md` deliberately covers the layers those DON'T: state management, Navigation 3 contract, data layer, package layout, backend architecture/perf, version policy, DoD gates — and references them rather than restating. FLAGGED: merge #168 — the standards doc + skill wiring reference `mobile-ui-foundation`.
- **D5 (version bumps — now vs deferred):** bump NOW (patch/minor, test-gated, CVE or direct-bug drivers): pgjdbc 42.7.11 (CVE-2026-42198), Ktor 3.5.0 (3.4.3 fixed a CallLogging/HttpRequestLifecycle cascade bug in our exact plugin set), Kotlin 2.3.21, kotlinx trio (coroutines/serialization/datetime 1.11.0/1.11.0/0.8.0), material3 1.11.0-alpha07 (CMP-1.11-aligned), OTel 1.63.0 + 2.28.1-alpha (CVE-2026-33701), Lettuce 6.8.2, Hikari 6.3.3. DEFER as deliberate planned upgrades: Kotlin 2.4.0 (1 week old), AGP 8.13/9, kotest 6 (breaking, 118 test files), Flyway 12, Hikari 7, Lettuce 7, DataStore 1.2.1-KMP. Full table in docs/11 §1. FLAGGED: Ktor 3.5.0 chosen over conservative 3.4.3 — full backend test suite is the gate; fallback to 3.4.3 if red.
- **D6 (mobile package restructure):** docs/11 §2.1 defines the target feature-package layout; the flat 35-file `screens/` package is declared legacy. Phase 3 M1 will do mechanical moves only (package decl + imports, zero logic edits, full test gate) and only where coupling analysis says it's low-risk. FLAGGED for review: extent of the restructure.

## Flagged for operator review

(populated as decisions accumulate; summarized at end of engagement)

## Phase 1 — Research findings (2026-06-10, 5 parallel dated-WebSearch agents)

Full consolidated output → **`docs/11-Engineering-Standards.md`**. Headlines:

- **Already current:** CMP 1.11.1, Nav3 (JetBrains) 1.1.1, Koin 4.2.1, lifecycle 2.10.0, compileSdk 36. The project's substrate choices (Nav3, CMP Resources, Credential Manager, DataStore+Tink, Netty, kotlinx.serialization, Redis-custom rate limiting) all re-verified as 2026-canonical.
- **Behind:** pgjdbc (security CVE), Ktor (3.4.3 fixed a bug in our exact plugin combo), kotlinx trio, JetBrains material3 alignment, OTel (CVE in instrumentation line), Lettuce/Hikari patch lines. → D5.
- **Practice deltas our code may violate** (Phase 3 hunting list): v1 `runComposeUiTest` now deprecated (CMP 1.11); Nav3-on-KMP requires polymorphic `SerializersModule` for NavKey restore on iOS; decorator order saveable→viewmodel; one-shot events must be state-driven (no Channel/SharedFlow buses); JDBC must run on a pool-sized bounded dispatcher (not raw `Dispatchers.IO`); Supavisor transaction mode needs `prepareThreshold=0`; `stabilityConfigurationFile` API renamed (plural); edge-to-edge mandatory at targetSdk 36 (no status/nav bar color setters); Json server defaults (`ignoreUnknownKeys`/`explicitNulls=false`/`encodeDefaults=false`).
- **Repo-map confirmations of the patchwork complaint:** flat `screens/` package (35 mixed files: nav + effects + VMs + screens), design system = 1 theme file with components inlined per screen, mixed state-holder patterns (3 androidx ViewModels vs many custom `*Flow` holders), `InvalidCursorException` duplicated in 2+ packages.

## Phase 2 — Changes applied

**Root-cause diagnosis of the two reported problems:**
1. *Build-green-but-buggy:* the lifecycle had NO mandatory manual-verification gate. `/opsx:apply` went tasks → (backend smoke if a script exists) → review → archive; `verify-loop` existed but nothing required invoking it. Mobile changes shipped on unit/Robolectric green alone.
2. *Patchwork:* `/opsx:apply` reads only the change's own contextFiles — implementation sessions never saw a cross-change architectural baseline; nothing forced pattern consistency (hence 3 ViewModels vs many custom `*Flow` holders, components inlined per screen).

**Applied (workflow/lifecycle):**
- `openspec-apply-change`: + mandatory docs/11 read at step 4 (+ mobile-design-system spec + mobile-ui-foundation checklist for UI changes); + per-task coherence/reuse-first check in step 6; + **new step 7.5 manual verification gate** (verify-loop bring-up + screenshot evidence in PR body, MANDATORY for UI-affecting changes; explicit N/A waiver otherwise); step 8 now gates on 7.5; completion output shows a Verification line; general review lens checks Pattern-Registry conformance.
- `next-change`: + `git worktree list` in BOTH claim surveys (A.1 + A.5.1 — memory precedent: unpushed sibling-worktree branches invisible to gh pr list/branch -r); + **new B.4 standards-conformance pre-check** (design.md must name the Pattern-Registry patterns it builds on; deviations = explicit Decision + docs/11 amendment task); general review lens extended.
- `openspec-propose`: + mandatory "Standards conformance" note in design.md for mobile/backend changes.
- `openspec-archive-change`: + **new step 3.5 DoD gate** (verification evidence / smoke / mobile gates checked before archive; AskUserQuestion on miss — never silent).
- `verify-loop`: §D now declares itself the docs/11 §5 DoD implementation; UI changes need §B/§C evidence, not just the test gate.
- `openspec-explore`: one-line grounding note (explore architecture against docs/11; deviations as amendments, not parallel patterns).
- `triage-follow-ups`: audited — **no changes needed** (accurate post-FOLLOW_UPS-retirement, concise, aligned).

**Applied (canonical docs):**
- `CLAUDE.md`: docs/11 added to canonical refs (MUST-read billing); reviewer lens for feat PRs now checks Pattern-Registry conformance + verification evidence.
- `openspec/project.md`: architectural-contracts pointer in Coding Conventions; CI-gates paragraph now includes mobile flavor-qualified tests + the pre-archive manual-verification requirement; Doc Map gained rows 09/10/11 (09+10 were missing — pre-existing drift).
- `docs/00-README.md`: row 11 + engineers' reading order includes 11.
- `README.md`: documentation map mentions docs/11.

Note: `.claude/skills/**` edits applied directly in-session (self-modification guard did not fire in this worktree session). All changes ship via the audit PR for operator review regardless.

## Phase 3 — Review log

Review pass: COMPLETE for all areas — 7 sub-agent findings files under `findings/` (01 backend-core, 02 timeline/engagement, 03 social/chat, 04 moderation/ops, 05 mobile-shell/state, 06 mobile-features/data, 07 native). Severity totals: 4 CRITICAL-class (1 routing, 1 privacy view, 2 agent-dispatch/dispatcher systemics), ~20 HIGH, ~35 MEDIUM/LOW.

### Fixes shipped — backend wave 1 (commit `5b898fa`)

See the commit body for the finding-by-finding list (DbDispatchers + auth gate, follow/block limiters + UserPairLock, Apple-S2S OIDC isolation + regression test, StatusPages/CallId/Compression/shutdown, RedisHandles single client, RemoteConfig activation, soft-delete auth gate, chat/notification small fixes).

### Fixes shipped — backend wave 2 (this commit)

- **02-C1 (CRITICAL): V20 `visible_posts` redefinition** — author-side shadow-ban + soft-delete filters per docs/05 § Shadow Ban (merged with the V4 auto-hide filter). `MigrationV20SmokeTest` pins all four exclusions + the live un-shadow-ban flip. Spec `visible-posts-view` amended (new requirement + updated definition); docs/05 view block reconciled; V4 smoke + ReportsReadPathNonRegression assertions updated for the join-rendered definition. **FLAGGED (product nuance):** per docs/05's own-content exception, a shadow-banned author sees own content via Repository own-content paths — NOT in shared feeds; if feed self-visibility is wanted for a stronger illusion, that's a viewer-aware query change (follow-up).
- **02-H1 + 02-M1:** the view's `deleted_at IS NULL` makes the V4 partial cursor indexes usable (Global/Following no longer full-scan); `posts_author_idx` shipped in V20 (was documented, never created).
- **02-H4: daily-cap window bug** — `_day}` keys now use FIXED-WINDOW INCR/PEXPIRE semantics (Redis Lua + InMemory mirror + `RateLimiter.FIXED_WINDOW_KEY_MARKER`); previously sliding-window-with-window=ttl let late-day usage refill daily caps ~6-8×. Spec `rate-limit-infrastructure` amended + 2 new scenarios; 3 new deterministic-clock tests.
- **02-L2:** burst-reject now releases the consumed daily slot (LikeService).
- **02-M2: post 10/day cap shipped** (`PostRateLimiter`, Premium skips, 429 + Retry-After via StatusPages) — docs/05 § Layer 2 prescribed it; never implemented while cheaper writes got limiters.
- **02-H2 + 04-#2 + 03-#1: dispatcher discipline completed backend-wide** — Like/Reply/CreatePost/Chat/Search/Timeline services + TimelineReadRateLimiter + the 3 timeline services all hop to the pool-bounded dispatcher; `moderate()`'s Redis/Remote-Config/Secret-Manager I/O now runs on the bounded dispatcher AND (chat) the verdict is computed BEFORE the transaction opens (no remote calls while holding a Hikari connection; response ordering preserved).
- **04-#1: Layer-3 dispatch re-parenting fixed** — `minusKey(Job)` so dispatches are children of the supervisor scope (shutdown drain was dead code; client disconnect could cancel a post's Layer-3 moderation).
- **02-M3 + 03-#10 (docs):** Nearby/Following canonical SQL blocks refreshed to the shipped shape; `user_blocks.blocked_at` → `created_at`.

### Fixes shipped — mobile + native wave (this commit)

- **05-#1/#2/#3 — the notifications patchwork trio** (the clearest cross-change drift exemplar): nested Scaffold+TopAppBar removed (shell-body contract), single `inFlight` → split `isInitialLoad`/`isRefreshing` (content stays mounted during refresh; no double indicator), non-Content states wrapped in the scrollable idiom so PTR works from Loading/Empty/Error. `mobile-notifications-list` spec MODIFIED (it had pinned the stale pattern); UiState/VM tests updated.
- **05-#4 — proactive refresh actually preempts now**: the trigger clears the Auth plugin's cached BearerTokens after a successful refresh (previously every post-refresh request still sent the STALE token → ate the 401 + a redundant rotation). New end-to-end regression test pins old-token→refresh→new-token via a real factory client.
- **06-#1 — HttpClient timeouts (connect 10 s / request+socket 15 s) + GET-only HttpRequestRetry (exponential, 2×)** per docs/11 §2.6; `installTimeouts` test seam documented (runTest virtual time × suspended MockEngines).
- **06-#3 — notifications terminal-401 → `SessionExpired`** + neutral redirect placeholder (D4 parity with the timelines; was the retryable "check your connection" banner on a dead session).
- **06-#2/#4 — PostDetail**: placeholder dots → the feed-card Material icons (like filled/outline + reply); BackBar gains `statusBarsPadding`, ReplyComposer gains `navigationBarsPadding+imePadding` (own-Scaffold overlay must own its insets).
- **07-#1 — `allowBackup=false`**: auto-backup/D2D restored the Tink keyset without the Keystore master key → un-unwrappable keyset crash after device migration.
- **07-#2 — iOS keychain write surfaces its OSStatus** (NSLog, status only) — silent dropped writes presented as mystery sign-outs.

### Mobile/native items deliberately remaining (next session; sketches complete in findings/05+06+07)

Larger refactors kept out of this pass to bound risk: the 5 remember-only screens → entry-scoped ViewModels (05-#5; carries real draft-loss/double-submit bugs — pair with 05-#10), shared list-state/post-card extraction into `ui/components/` (05-#11 — the docs/11 §2.1 first structural move), VM single-StateFlow + koinViewModel + collectAsStateWithLifecycle sweeps (05-#6/#7/#13), LocationGate fold (05-#12), shell unread VM (05-#9), timeline reload-reentrancy + projection memo + contentType (06 mediums), DiagnosticSink wiring for the 4 no-op repos (06), native mediums (DataStore corruption handler, Tink init offload, bridge launcher leak, iOS main-thread guards + reduced-accuracy key, release minify, deployment-target coherence). The flat `screens/` package restructure (D6) is NOT started — target shape documented in docs/11 §2.1.

### Backend items deliberately sequenced AFTER the mobile wave (full fix sketches in findings/01+02+04)

- 01-#6 part 2 (JwksCache negative-cache + single-flight), 01-#13 (signup 23505 conflation → 409), 01-#15 (refresh-token atomic claim — QUESTION: docs/05 30s-overlap may bless), 01-#14 (shared AppJson), 01-#19 (JwtIssuer dead verifier), 01-#23 (kid env-config), AppleS2S handler-local Json hoist.
- 02-H3 (batched-Lua timeline post-increment — needs `timeline-read-rate-limit` spec amendment; 58 round-trips/page is spec-mandated today), 02-L1 (per-request RC read+INFO log), 02-L3 (precomputed SQL constants).
- 04-#3/#10/#11 (RC default-caching + per-call template fetch — operational-tuning cluster; 04-#3 kill-switch latency is QUESTION-grade vs the spec'd 5-min cache), 04-#5 (FCM fan-out cap — benign pre-launch), 04-#6 (CIO→Apache5 comment drift), 04-#7 (APNs margin), 04-#9 (limiter default footgun).
- QUESTION-grade flags for operator: 01-#16 (no auth-endpoint rate limit — Cloudflare assumed front-line), 03-#5 (`/followers` 200 defeats profile constant-404 oracle), 03-#6 (bare-ID social lists force client N+1 — contract change, ties to #196), 03-#13 (search OFFSET vs docs/11 cursor rule — reconcile docs), 03-#14 (`last_read_at` dead schema → follow-up issue).

### Fixes shipped — backend wave 4 smalls (commit `bd74693`)

The "sequenced AFTER the mobile wave" quick items, now done: 01-#6 part 2 (JwksCache refresh single-flight + 60s bogus-kid cooldown — unauthenticated kid-flood can't amplify into per-request upstream JWKS fetches), 01-#13 (identity-hash 23505 → 409 `user_exists`, not false 503), 01-#14 (shared `AppJson` — ContentNegotiation + Apple S2S consume it; cursor codecs deliberately keep strict instances), 01-#23 (`KTOR_RSA_KID` env-configurable kid per docs/05 rotation plan), 01-#19 (JwtIssuer production-dead `verifier()` removed; test builds its own oracle), 04-#6 (OpenAI client timeout comment reconciled to Apache5).

### Fixes shipped — mobile wave 5, batch B mediums (commit `8e42ccb`)

- **05-#10 — double-submit claims**: `inFlight = true` synchronously BEFORE `scope.launch` in PostCreationScreen submit + PostDetail like/reply (the post-launch write left a same-frame double-tap window).
- **Reply ordering**: a 201 now PREPENDS the returned reply (list renders newest-first DESC; append put it at the visual bottom).
- **05-#8 — `handleTerminal401` removed** from AuthFlow/AuthRepository/fakes: production-dead duplicate session-invalidation seam; `SessionInvalidator.invalidate` is the funnel (test renamed to match reality).
- **05-#13 — `collectAsStateWithLifecycle`** in the 3 StateFlow screens (lifecycle-aware collection per docs/11 §2.1).
- **06 medium — `reload()` reentrancy guards** in Global/Nearby timeline + Notifications VMs (stacked PTR + retry raced concurrent fetches; latest-writer-wins flicker).
- **05-#15 — NearYouTheme font-preload catch rethrows `CancellationException`** (module-wide convention; this was the one catch that ate it).
- **05-#14/06 — `items(contentType = …)`** on the 4 feed lists (notification/post/reply) for cross-item-type recycling.

NOTE (cleanup): an accidental `git push origin HEAD` created stray remote branch `claude/xenodochial-jackson-8511cb` (same commits as the PR branch); deletion was permission-blocked in-session — operator can `git push origin --delete claude/xenodochial-jackson-8511cb`.

### Fixes shipped — native wave 6, batch C (this commit)

All of findings/07's remaining MEDIUMs + the cheap LOWs:

- **07-#3 — DataStore corruption handler** (`ReplaceFileCorruptionHandler { emptyPreferences() }`) + `IOException → null` in `read()`: a corrupted prefs file no longer crash-loops the cold-start router.
- **07-#4 — Android Keystore/prefs I/O off the main thread**: `SecureTokenStore.read/write/clear` + the permission controller's pref reads hop to `Dispatchers.IO` (first AEAD dereference does Keystore IPC + file I/O on the RootRouter routing path; iOS actual already hopped).
- **07-#1 part 2 — poisoned-keyset write recovery**: `write()` catches the unwrap `GeneralSecurityException`, clears the keyset prefs, rebuilds fresh; second failure skips the persist (in-memory session survives) instead of crashing sign-in. `read()` deliberately does NOT reset (Keystore is unavailable pre-first-unlock; a transient failure must not destroy a valid keyset) — recovery belongs to the always-unlocked write path.
- **07-#2 completion — `errSecDuplicateItem → SecItemUpdate` fallback** (delete-then-add is not atomic; a racing write now converges instead of dropping).
- **07-#6 — `MainActivity.onDestroy` clears the bridge launcher** (identity-guarded) — the Koin-singleton bridge no longer keeps a destroyed Activity reachable.
- **07-#7 — iOS main-thread confinement made structural**: `IosLocationPermissionController`, `IosLocationProvider`, `GoogleSignInClient` wrap their bodies in `withContext(Dispatchers.Main)` (CoreLocation delivers callbacks on the creating thread's run loop; GIDSignIn/UIApplication.windows are UIKit-main-only). **DECISION (diverges from the finding's sketch):** NO decision-timeout added to `request()` — the wait is on the user's prompt choice (unbounded by design); the hang class the timeout would have defended is removed structurally by the confinement, and a ceiling would misreport slow human decisions as DENIED.
- **07-#8 — `NSLocationDefaultAccuracyReduced=true`** in Info.plist: the OS prompt + grant now default to approximate, matching the manifest's coarse-only posture + UU-PDP copy.
- **07-#9 — release `isMinifyEnabled=true`** + `proguard-android-optimize.txt` + minimal `proguard-rules.pro` (3 `-dontwarn` for Tink's absent optional integrations). `assembleDevRelease` R8 gate green on first try (library consumer rules carry the keeps). **Runtime release smoke still required per docs/11 §5 before shipping a release build** — flagged.
- **07-#10 — deployment floor aligned at 16.0** (was: app 18.2 accidental Xcode default / framework+Pods 13.0 claiming-but-violating): pbxproj ×4 + `ios.deploymentTarget` + Podfile + regenerated `app.podspec`. **FLAGGED (operator decision):** 16.0 chosen as the defensible default — covers the iPhone 8/X-era second-hand market (real for an Indonesia MVP), exceeds every dependency minimum (CMP 13+, GoogleSignIn 13+, the iOS-14+ CoreLocation API K/N can't `@available`-check). Revisit if analytics say otherwise.
- **07-#11 — fused-location CTS wired** to `invokeOnCancellation` (navigating away cancels the radio request).
- **07-#12 — GID cancel detection requires the `com.google.GIDSignIn` domain** (a non-GID code -5 no longer masquerades as silent user-cancel).
- **07-#14 — iOS `apiBaseUrl` fail-fast**: missing plist key now `error()`s in release binaries (debug keeps the staging fallback); silently pointing a production build at staging was the failure mode.
- **07-#15 — Production.xcconfig declares all three GID placeholders** (server + reversed were inheriting STAGING values — provisioning-day 401 trap).
- **07-#17 — launch theme DayNight split** (`Theme.NearYou.Launch`, values/values-night): no more white flash for dark-mode users pre-first-frame.

Gates: `testDevDebugUnitTest` + `testDevReleaseUnitTest` + `assembleDevRelease` (R8) + `iosSimulatorArm64Test` (K/N compile + 4 iosTest specs on sim) + root `ktlintCheck`/`detekt` — all green.

Deliberately NOT done from findings/07: **07-#13 nonce binding** (needs a backend issue/verify round-trip — hardening follow-up, specs silent), **07-#16 `UIApplication.windows` → connectedScenes migration** (deprecated-but-functional at the 16.0 floor; retention sets are now main-confined by 07-#7 so the thread-safety half is moot), **07-#5** (already shipped in wave 3 via the commonMain `HttpTimeout`/retry factory).

### Remaining after wave 6 (sketches in findings/01–07)

- **Mobile larger refactors (deliberate, risk-bounded):** 5 remember-only screens → entry-scoped ViewModels (05-#5, carries draft-loss bugs), `ui/components/` extraction of list-state kit + post card (05-#11), VM single-StateFlow + `koinViewModel` declarations (05-#6/#7), LocationGate fold (05-#12), shell unread VM (05-#9), TokenRefresher follower-CE (05-#16), projection memoization + DiagnosticSink wiring (06), `screens/` package restructure (D6, not started).
- **Backend deferred:** 02-H3 batched-Lua (needs spec amendment), 02-L1/L3, 04-#3/#5/#7/#9/#10/#11, 01-#15 atomic claim (QUESTION).
- **Native deferred:** 07-#13 nonce binding (backend coordination), 07-#16 windows→connectedScenes (next touch).
- **QUESTION-grade operator flags:** unchanged from the list above.
