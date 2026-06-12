# 11 — Engineering Standards Baseline

**Status: MUST-READ for every product change** (mobile + backend), at BOTH the proposal phase (`/next-change` / `openspec-propose`) and the implementation phase (`/opsx:apply`) — both skills reference this file. It is the architectural design contract that keeps changes built in different sessions fitting the same skeleton: a change that deviates from a rule here MUST either conform or amend this doc in the same PR with rationale (see § Pattern Registry).

Verified against ecosystem state **2026-06-10** (dated WebSearch per `openspec/project.md` § pre-implementation re-check rules). Re-verify load-bearing claims when this stamp is >1 quarter old.

Relationship to other canon:
- [`openspec/project.md`](../openspec/project.md) — conventions, CI lint rules, delivery workflow (the 16 code-level invariants live there).
- [`docs/09-Versions.md`](09-Versions.md) — per-pin rationale log. This doc holds the currency *policy* and planned upgrades; 09 holds the *why* per pin.
- [`openspec/specs/mobile-design-system/spec.md`](../openspec/specs/mobile-design-system/spec.md) — machine-checkable mobile UI substrate rules (single-Scaffold inset ownership, loading/refresh split, icon + label rules, Bahasa Indonesia single-language).
- `.claude/skills/mobile-ui-foundation/` (ships via [PR #168](https://github.com/aditrioka/nearyou-id/pull/168)) — per-screen UI/UX application checklist; this doc is the layer *underneath* it: state, navigation, data, backend, versions.

---

## 1. Version currency policy

Current pins that are **correct and current** (per the 2026-06-10 stamp above): CMP `1.11.1`, Navigation 3 (JetBrains) `1.1.1`, Koin `4.2.1`, JetBrains lifecycle `2.10.0`, compileSdk/targetSdk `36`.

**Bumps applied by the 2026-06 holistic audit** (rationale rows in `docs/09-Versions.md`):

| Pin | From → To | Driver |
|---|---|---|
| `postgresql-jdbc` | 42.7.7 → 42.7.11 | CVE-2026-42198 (SCRAM iteration DoS) — security, immediate |
| `ktor` | 3.4.1 → 3.4.3 (3.5.0 attempted, rolled back) | 3.4.3 fixed `HttpRequestLifecycle` × `CallLogging.callIdMdc` cascading request failures (directly in our plugin set). The audit landed 3.5.0, rolled back 2026-06-11 (PR [#216](https://github.com/aditrioka/nearyou-id/pull/216), deploy-blocking): KTOR-9546 makes the OTel-instrumented client hang to `HttpTimeout` on every outbound call — rejected two staging deploys at the readiness probe. Upstream fix merged ([open-telemetry/opentelemetry-java-instrumentation#18779](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/18779)) but NOT in our pinned 2.28.1 instrumentation pair. Re-attempt 3.5.x ONLY together with an instrumentation-pair release that contains the fix |
| `kotlin` | 2.3.20 → 2.3.21 | drop-in patch |
| `kotlinx-coroutines` / `-serialization` / `-datetime` | 1.10.2→1.11.0 / 1.9.0→1.11.0 / 0.7.1→0.8.0 | bumped together (one PR); serialization 1.11 hides user input from JSON exception messages (PII-safe logs for a social app) |
| `material3` (JetBrains) | 1.10.0-alpha05 → 1.11.0-alpha07 | the CMP-1.11.x-aligned M3 artifact; alpha line is deliberate (stable 1.9.0 strips Expressive APIs) |
| `opentelemetry-bom` / instrumentation | 1.51.0→1.62.0 / 2.25.0-alpha→2.28.1-alpha | instrumentation 2.26.1 fixed CVE-2026-33701; SDK BOM pinned to what the instrumentation BOM requests (1.62.0), never ahead of it — api-internal classes churn between SDK minors (1.63 broke 2.28.1 at runtime) |
| `lettuce` | 6.5.0 → 6.8.2 | last 6.x line; 7.x is a major (deferred) |
| `hikaricp` | 6.3.2 → 6.3.3 | last 6.x patch; 7.x deferred |

**Planned upgrades (deliberate, NOT yet)** — each is a real change with its own migration pass, not a casual bump: Kotlin `2.4.0` (stable context parameters, rich-errors preview; let CMP/Koin/kotest soak first), AGP `8.13.x` then the AGP 9 migration (new DSL; legacy DSL removed in AGP 10, H2 2026), kotest `6.x` (breaking: InstancePerRoot, table-testing artifact split — 118 backend test files affected), Flyway `12.x`, HikariCP `7.x`, Lettuce `7.x`, DataStore `1.2.1` KMP (only when a multiplatform prefs need appears; tokens stay Keychain on iOS — DataStore is plaintext).

Rules:
1. Version changes only via `gradle/libs.versions.toml`; authoritative update cadence + per-pin rationale log: [`docs/09-Versions.md`](09-Versions.md) § Pinning Policy.
2. Keep alignment sets in lockstep: {CMP, JetBrains material3, JetBrains lifecycle, JetBrains nav3} on the same release cycle; {OTel SDK BOM, instrumentation BOM}; {kotlinx coroutines/serialization/datetime}.
3. The dated-WebSearch re-check rules in `openspec/project.md` (pre-implementation + apply-phase) apply to every new substrate AND to every "planned upgrades" entry before executing it.

## 2. Mobile architecture contract (`:mobile:app` + `:shared:resources`)

### 2.1 Package layout (target shape)

```
id.nearyou.app/
  ui/
    shell/          # app shell: root router, section scaffold, bottom nav, tab host
    components/     # design-system composables shared by ≥2 screens (reuse-first: scan here BEFORE writing a new one)
    <feature>/      # one package per screen/feature: XxxScreen.kt + XxxViewModel.kt + XxxUiState.kt
  navigation/       # NavKeys (sealed interface : NavKey), entry provider, back-stack ext, nav serialization
  data/
    <feature>/      # XxxApiClient (DTOs colocated) + XxxRepository
  auth/ location/ network/ config/ di/ diagnostics/  # cross-cutting singletons as today
```

The pre-audit flat `screens/` package (35 mixed files) is the legacy shape. **New code MUST follow the target shape.** Existing files move only as a mechanical move (package decl + imports, zero logic edits) with the full mobile test gate green in the same commit.

### 2.2 State management

- Screen-level state holder = **androidx `ViewModel` in commonMain** (JetBrains lifecycle), obtained via `koinViewModel()`, scoped to the Nav3 entry (see 2.3). Plain `*Flow`/`*State` holder classes are legacy; do not add new ones for screens. [verified d.android.com state-production 2026-05-14; kotlinlang compose-viewmodel 2026-06-08]
- Expose ONE `StateFlow<XxxUiState>` via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`. Collect with `collectAsStateWithLifecycle()` (works in commonMain via JetBrains lifecycle-runtime-compose).
- UiState shape: `sealed interface` when states are mutually exclusive (Loading/Content/Error); `data class` when fields vary independently (e.g., list + isRefreshing + errorBanner). Initial-load vs refresh are SEPARATE fields per the `mobile-design-system` spec.
- **One-shot events are state, not streams**: model as nullable UiState fields consumed via an `onXxxShown()` callback that clears them. `Channel`/`SharedFlow` ViewModel→UI event buses are an anti-pattern (official guidance: "ViewModel events should always result in a UI state update"). [verified d.android.com ui-layer/events 2026-05-18]
- Business/data work never launches from composables; it goes through the ViewModel (`viewModelScope`). No `GlobalScope`, no `CoroutineScope()` ad-hoc construction in UI code.

### 2.3 Navigation 3 (final substrate decision — optimize, never relitigate)

- Back stack: `rememberNavBackStack(...)` owned by the shell composable; keys are a `sealed interface ... : NavKey`, every key `@Serializable`.
- **KMP-mandatory**: pass a `SavedStateConfiguration` with a `SerializersModule { polymorphic(NavKey::class) { subclass(...) } }` — Android's reflection-based NavKey serialization does NOT work on iOS; missing this breaks iOS state restoration silently. Every new NavKey MUST be registered in the module. [verified kotlinlang compose-navigation-3 2026-05-21]
- Decorator order when overriding `entryDecorators`: `rememberSaveableStateHolderNavEntryDecorator()` FIRST, `rememberViewModelStoreNavEntryDecorator()` SECOND (the saveable decorator provides the `SavedStateHandle` plumbing the ViewModel decorator needs; without the ViewModel decorator, Koin VMs scope to the Activity and never clear). [verified DAC nav3 save-state 2026-06]
- Entries via `entryProvider { entry<Key> { } }` DSL; Koin screens declared with `navigation<Key> { }` modules + `koinEntryProvider` where applicable.
- Predictive back: use `NavigationEventHandler`; `PredictiveBackHandler` is deprecated since CMP 1.10. iOS start-edge swipe-back comes via navigationevent — do not hand-roll gesture handling.
- Deep links: manual URI→NavKey mapping at app entry for now; migrate to `UriDeepLinkMatcher`/`DeepLinkUri` when the JetBrains 1.2 port ships (androidx 1.2.0-alpha03 added first-class deep links).
- Tabs/sections inside the Home shell are pager/Tab state, NOT NavKeys (per `mobile-design-system`); the back stack holds screen-level destinations only.

### 2.4 Compose performance

- Strong skipping is compiler-default — never disable; don't blanket-annotate `@Stable`/`@Immutable` "for safety" (post-2.0.20 advice: annotations matter cross-module or with expensive `equals` only).
- Lazy lists: stable `key` + `contentType` on every `items()` over domain lists.
- Use `derivedStateOf` only to rate-limit composition reads; `snapshotFlow` for state→Flow bridges; never compute derived list state inline per recomposition.
- List-bearing UiState fields use `kotlinx.collections.immutable` (`ImmutableList`) or are produced from stable upstream types; declare external stable types via `stabilityConfigurationFiles` (plural API — singular is deprecated) when needed.
- iOS: concurrent rendering is default since CMP 1.11 (no manual flags); `Modifier.preferredFrameRate(...)` may cap static screens.
- Fonts: keep the `FontFamily.Resolver.preload()` + LaunchedEffect pattern for bundled fonts (NearYouTypography precedent).
- Release Android builds: R8 + `proguard-android-optimize.txt`; baseline profiles are a planned (not yet wired) optimization — Benchmark KMP support is still alpha.

### 2.5 expect/actual & platform code

- Prefer `interface` in commonMain + per-platform implementation bound in Koin platform modules. `expect class` is still Beta — reserve expect/actual for top-level functions (e.g., `httpClientEngine()`). [verified kotlinlang expect-actual, current]
- Kotlin/Native: ObjC *category* members need explicit `import platform.<Framework>.<symbol>` — compile-only, Linux CI can't catch; run `linkDebugFrameworkIosSimulatorArm64` locally when touching iosMain.
- Permissions, lifecycle bridges, and platform services live behind commonMain interfaces; androidMain/iosMain hold ONLY the actuals + platform wiring (no business logic).

### 2.6 Data layer (mobile)

- Per feature: `XxxApiClient` (HTTP + DTOs, kotlinx.serialization) + `XxxRepository` (domain mapping, token/session concerns delegated to the auth layer). ViewModels talk to repositories, never to ApiClients.
- One shared `HttpClient` from `HttpClientFactory` (Auth bearer plugin + single-flight refresh via `TokenRefresher`); never construct ad-hoc clients per feature.
- DTO field names MUST match the wire truth in `TimelineRoutes.kt`-style route files (mixed-case precedent — specs' snake_case JSON examples are stale; trust the Kotlin DTOs serverside).
- Error modeling: each feature exposes a sealed `XxxOutcome` (success/typed-failures) at the repository boundary; exceptions don't cross into ViewModels. Retries: Ktor client `HttpRequestRetry` with `exponentialDelay()` where idempotent — no hand-rolled retry loops.

### 2.7 Mobile testing

- Pure state projections + repositories + ViewModels → commonTest (kotlin.test; coroutines-test `runTest`).
- Screen behavior → Robolectric `*ScreenTest` in androidUnitTest; MUST be added to the Release-variant exclude; verify with `:mobile:app:testDevReleaseUnitTest` too. Async real-flow tests poll with `waitUntil`, not `waitForIdle`.
- iOS-specific behavior → `src/iosTest` with K/N-legal test names; `:mobile:app:iosSimulatorArm64Test`.
- v1 `runComposeUiTest` is deprecated as of CMP 1.11 — new tests SHOULD target the v2 ComposeUiTest API (note: v2 defaults to `StandardTestDispatcher`; advance the clock or keep `waitUntil` polling). Migrating existing tests is a tracked follow-up, not drive-by churn.

### 2.8 UI mockup reference (canonical visual target)

[`dev/mockups/nearyou-screens-mockup.html`](../dev/mockups/nearyou-screens-mockup.html) is the **canonical visual reference** for mobile screens — a 19-frame Material 3 board (light + dark) rendered from the real theme tokens (`NearYouColorScheme.kt`, Plus Jakarta Sans, the coral `locationPin` / amber `premiumBadge` accents). [`dev/mockups/nearyou-premium-tenure-badges.html`](../dev/mockups/nearyou-premium-tenure-badges.html) is the companion concept board for the premium-tenure badge/animation system. Frame inventory + consumption guide: [`dev/mockups/README.md`](../dev/mockups/README.md).

- **Every UI-affecting change (proposal AND implementation) MUST consult the matching frame(s) before building.** Render the HTML — open it in a browser/preview, or capture it via a browser screenshot tool, whichever gives the agent the clearest read — then translate to Compose Multiplatform idioms (the M3 components per § 2.1–2.6, theme tokens not literals).
- **Precedence**: `openspec/specs/` + `docs/02/03` govern *behavior*; the mockups govern *look and layout*. On conflict, specs/docs win — flag the divergence instead of silently following the mockup. Frame captions cite the governing doc per element and tag each frame as shipped ("Sudah ada") vs proposed ("Usulan").
- Mockups are HTML/CSS approximations: treat spacing/sizing as **dp intent on the 4dp grid**, not pixel-perfect contracts; the M3 component named in the caption is the source of truth for its metrics.
- Admin-panel counterpart of this rule: § 3.6.

## 3. Backend architecture contract (`:backend:ktor` + `:infra:*`)

### 3.1 Layering & boundaries

- Package-by-feature; inside a feature: `XxxRoutes` (thin: parse/validate/authenticate/respond) → `XxxService` (business rules + transaction boundary) → repository/JDBC (SQL). Routes never touch SQL; services never read `ApplicationCall`.
- DTOs (request/response `@Serializable` types) live with routes; domain types and SQL rows do not leak into response shapes.
- Cross-feature access goes through the other feature's service/repository interface — never its tables. Vendor SDKs only inside `:infra:*` (existing invariant).

### 3.2 JDBC & connection discipline (the #1 backend perf rule)

- Every JDBC call runs on a **bounded dispatcher sized to the Hikari pool**: `Dispatchers.IO.limitedParallelism(maxPoolSize)` exposed via DI (single shared instance) — never raw `Dispatchers.IO` (its 64-thread default lets request floods queue on the pool and starve unrelated IO), never the Netty event loop. [verified kt.academy dispatcher-for-backend; ktor.io 2026-06]
- One transaction per service-level operation; open/commit/rollback in a helper, not scattered `connection.use {}` with implicit autocommit where multi-statement consistency matters.
- Hikari pools stay SMALL per Cloud Run instance (2–10); `maxInstances × poolSize` must fit the Supavisor budget; `maxLifetime` below the pooler idle timeout. Supabase transaction-mode pooling (:6543) requires `prepareThreshold=0` (server-side prepared statements off). [verified supabase.com docs 2026-06]
- Test pools: `autoClose(hikari())` + size 2 (CI `max_connections` budget precedent, PR #157).

### 3.3 HTTP, serialization, plugins

- ONE shared `Json` instance: `ignoreUnknownKeys = true`, `explicitNulls = false`, `encodeDefaults = false`, never `isLenient`. kotlinx.serialization remains the right choice (compile-time codegen beats reflection-based mappers on JVM benchmarks). Keep serialization ≥1.11's debug-info-off default in prod (hides user input from parse-exception messages).
- Standard plugin set: ContentNegotiation, StatusPages (single error envelope), CallId + CallLogging with `callIdMdc` (safe ≥ Ktor 3.4.3), Compression, Caching/ConditionalHeaders where responses allow, RequestValidation for input-shape checks (length guards remain the lint-enforced backstop).
- Rate limiting stays Redis-backed custom (`computeTTLToNextReset` invariant); Ktor's built-in RateLimit plugin is per-instance in-memory — wrong on Cloud Run, do not adopt.
- Remote-Config staleness budget: server-side flag reads go through OUR Redis cache (`remote_config:{flag:*}`, 5-min default TTL — the staleness is ours, not Firebase's; server Admin SDK has no real-time channel). A genuinely emergency kill-switch flag MUST ship with a per-flag short TTL override (30–60 s) in the same change that introduces it (operator-ratified 2026-06-11; first expected candidate: `image_upload_enabled`). Dedicated streaming flag platforms (<200 ms) are deliberately NOT adopted at MVP scale.
- Engine: **Netty stays** (CIO is HTTP/1.1-only and slower in community benches; Netty is the h2c path if ever needed). Set `shutdownGracePeriod`/`shutdownTimeout` to fit Cloud Run's termination window.
- Streaming/pagination: timeline-style endpoints stay cursor-paginated (no OFFSET); response sizes bounded by limits validated at the route. **Deliberate exception: search** (`GET /api/v1/search`) keeps OFFSET pagination — results are relevance-ranked and shallow-paged (users rarely go past the first pages), so keyset's deep-page advantage doesn't apply, and a stable cursor over a mutating tsv-ranked result set is ill-defined. Operator-ratified 2026-06-11 (audit finding 03-#13); revisit only if search depth telemetry says otherwise.

### 3.4 Cloud Run runtime posture

- Startup CPU boost ON; gen2 env; default concurrency 80 is fine for Netty — the real bound is the DB pool budget (3.2). JDK 21+ now, JDK 25 LTS target (AOT cache 2–4× startup when we get to it; `synchronized` virtual-thread pinning removed since JDK 24 — JEP 491). Virtual-thread dispatcher for JDBC is an endorsed alternative to limitedParallelism once on JDK 21+ — adopt deliberately, not drive-by.

### 3.5 Backend testing

- kotest JUnit5 specs; `@Tags("database")` for service-container tests; deterministic seed-table inputs (deep-ocean coords pattern — project.md § Test-data conventions); migrations boot once per JVM via `KotestProjectConfig`.

### 3.6 Admin panel UI (Pebble + HTMX) — mockup reference (canonical visual target)

[`dev/mockups/nearyou-admin-mockup.html`](../dev/mockups/nearyou-admin-mockup.html) is the **canonical visual reference** for every `/admin/*` surface — a 23-frame board covering ALL admin features (shipped and planned; sole known gap: the appeal-review workflow, docs/08 § Open Decisions #2, gets its frame when its design lands), rendered from the same theme tokens as the mobile board (`NearYouColorScheme.light`, Plus Jakarta Sans, brand logo vector). Frame inventory + consumption guide: [`dev/mockups/README.md`](../dev/mockups/README.md).

- **Every admin-UI-affecting change (proposal AND implementation — whatever the skill) MUST consult the matching frame(s) before building.** Render the HTML (browser / preview / headless Chrome screenshot — agent's choice), then translate to the panel's idioms: Pebble templates + HTMX fragment swaps (with the existing no-JS fallback discipline) + **vendored vanilla CSS** (design tokens as CSS custom properties copied from the board's `.frame` block). No client framework, no CDN assets, no inline styles in production templates.
- **Status tags are load-bearing.** "Sudah ada" frames mirror shipped `templates/admin/*.peb` fields/columns/actions — their *styling* is the target, their *content contract* is already spec-bound (restyling them is a regular PR). "Usulan" frames (and "Usulan" elements inside shipped frames) need their OpenSpec change / design doc before any of their behavior ships.
- **Responsive contract (frame 4b).** Panel CSS is fluid via vanilla media queries: sidebar → hamburger drawer below ~1024 px, filter bars collapse to a "Filters (n)" disclosure + active chips, queue/triage surfaces are card-based (stack naturally), data tables get `overflow-x:auto` below ~900 px. Board frames are fixed-width *snapshots* of that contract — never implement fixed-width layouts from them.
- **Precedence**: `openspec/specs/admin-*` + `docs/07-Operations.md` govern *behavior*; the board governs *look and layout*. On conflict, specs/docs win — flag the divergence instead of silently following the mockup.

## 4. Cross-cutting engineering principles

- **SRP per file**: a screen file holds one screen; a service holds one feature's rules. Soft cap ~400 LOC for UI files, ~500 for services — crossing it is a signal to decompose, not a hard lint.
- **Reuse-first (rule of three)**: before writing a composable/helper, scan `ui/components/` + the feature packages for an existing one; extract to shared only at the 2nd-3rd use site. No speculative abstractions (YAGNI) — but also no third copy-paste.
- **Dependency direction**: UI → ViewModel → Repository → ApiClient/DB; backend routes → service → repository. Never skip layers in either direction; DI binds interfaces at module seams.
- **Naming coherence**: follow the existing feature's naming when extending it (`XxxScreen`/`XxxViewModel`/`XxxUiState`/`XxxRepository`/`XxxApiClient`/`XxxRoutes`/`XxxService`). One concept, one name — don't introduce synonyms (`Flow`/`Controller`/`Manager`) for an existing role.

### Pattern Registry (anti-patchwork mechanism)

The canonical pattern per concern is the one in this doc (state §2.2, nav §2.3, data §2.6, backend layering §3.1, UI substrate → `mobile-design-system` spec). **A change that wants a DIFFERENT pattern for a listed concern MUST amend this doc in the same PR with the rationale — no silent second patterns.** Reviewers treat an unexplained pattern fork as a blocking finding. This is the enforcement seam that prevents "component added later doesn't fit the skeleton built earlier".

## 5. Definition of Done (every product change)

1. All spec'd scenarios have tests; deferrals need explicit operator buy-in (CLAUDE.md § Engineering judgment).
2. Gates green locally before push: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`; mobile-touching changes add `:mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`; iosMain-touching changes add `:mobile:app:iosSimulatorArm64Test` (or at minimum `linkDebugFrameworkIosSimulatorArm64`).
3. **UI-affecting changes: manual bring-up via `verify-loop` BEFORE archive** — Android emulator AND iOS simulator, screenshot evidence in the PR body, checklist pass per `mobile-ui-foundation`. "Tests green" alone is NOT done for UI; this gate exists because build-green-but-broken-on-device was this project's recurring failure mode.
4. Runtime-impacting backend changes: pre-archive staging branch deploy + smoke (project.md § Staging deploy timing).
5. Dated re-checks honored (new substrate / spec-revision rules in project.md).
6. PR title/body current at the phase boundary (project.md hard rule).
