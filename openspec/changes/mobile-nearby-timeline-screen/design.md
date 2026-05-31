## Context

`:mobile:app` ships Google sign-in + 18+ signup end-to-end as of Mobile #3 (`mobile-auth-google-signin-flow`) and Mobile #4 (`mobile-age-gate-screen`): `SignInScreen` / `AgeGateScreen` → `AuthRepository` → backend `/auth/*` → `SecureTokenStore` → `RootRouterScreen` token-gated routing to a placeholder `HomeScreen`, with a shared Ktor `HttpClient` (`HttpClientFactory`) carrying the `Auth { bearer { … } }` plugin (`loadTokens`/`refreshTokens` + `SessionInvalidator` on terminal 401). The patterns to mirror: a Voyager `Screen` injecting Koin singletons via `koinInject`, a status-driven sealed outcome (`SignInOutcome`/`SignUpOutcome`), a pure Compose-free UI-state + projection (`AgeGateUiState`), an interface-seamed repository for fakes, `@Serializable` snake_case DTOs, and Robolectric `runComposeUiTest` screen tests added to the build's Release-variant exclude list.

The backend half is fully shipped and stable:
- `openspec/specs/nearby-timeline/spec.md` — `GET /api/v1/timeline/nearby?lat&lng&radius_m&cursor`, Bearer JWT, per-page cap 30, response post shape `{id, author_user_id, content, latitude, longitude, distance_m, created_at, liked_by_viewer, reply_count, city_name}` + `next_cursor`, coordinates derived from `display_location`, `distance_m` raw meters.
- `openspec/specs/timeline-read-rate-limit/spec.md` — Free-tier rolling-150/hour hard + 50/session soft caps; hard-cap → `200 {posts:[], next_cursor:null, upsell:{hard:true}}`; soft → posts present + `upsell:{soft:true}`; `X-Session-Id` header (`^[A-Za-z0-9-]{1,64}$`, else `no-session`); Premium exempt server-side.
- `openspec/specs/distance-rendering/spec.md` — `DistanceRenderer.render(distanceMeters): String` in `:shared:distance` commonMain (floor 5 km, round-1 km above; input is fuzzed-against-`display_location`).
- `openspec/specs/coordinate-jitter/spec.md` — `JitterEngine.offsetByBearing` MUST live in `:shared:distance` commonMain; `JITTER_SECRET` MUST stay out of all client-facing paths.

This change is mobile-only except for making `:shared:distance` Kotlin-Multiplatform-consumable (it is currently `jvm()`-only). No backend code, no schema, no new `gradle/libs.versions.toml` pin. The user chose "Material 3 default — Claude proposes" for the visual treatment (D9).

## Goals / Non-Goals

**Goals:**
- Ship `NearbyTimelineScreen` as the first product surface: load `GET /api/v1/timeline/nearby`, render posts with the shared `DistanceRenderer`, pull-to-refresh, and explicit loading / empty / error / rate-limit-hard / rate-limit-soft states.
- Make `:shared:distance` consumable by `:mobile:app` (android + iOS targets) without violating the `coordinate-jitter` spec and without shipping `JITTER_SECRET` to clients.
- Establish the read-only post-card + list visual pattern (built from `:shared:resources` tokens) that later product screens inherit.
- Map every fetch result to an explicit outcome with no generic fallthrough; keep PII discipline (render only API-returned fields; never log tokens/coordinates).

**Non-Goals:**
- Real device location, runtime location permission, the UU-PDP consent modal, and the permission-denial fallback — deferred to `mobile-location-permission-flow` (D1).
- The 10/20/50/100 km radius slider (Free-bounce / Premium-pick) — deferred to `mobile-nearby-radius-slider` (D2).
- Cursor-based infinite scroll / load-more — deferred (D8).
- Like/reply *actions* + post-detail navigation; the Nearby/Following/Global tab bar; the Premium upsell deep-link (billing not built — `upsell` flags surface as copy only).

## Decisions

### D1 — Device location is STUBBED; the real GPS + permission flow is deferred (LOAD-BEARING SCOPE)

`NearbyTimelineScreen` needs `lat`/`lng`/`radius_m` to call the endpoint. This change introduces a commonMain `LocationProvider` interface returning a `LatLng` (reusing `id.nearyou.distance.LatLng` from `:shared:distance` — already a dependency), with the **default Koin binding a `StubLocationProvider` returning a fixed Jakarta coordinate `LatLng(-6.2, 106.8)`**. The full device-location surface — fused/`CLLocationManager` providers (expect/actual), the runtime permission request, the UU-PDP consent modal, and the denial fallback ("*Aktifkan lokasi untuk lihat postingan sekitar*" + Settings deep-link per `docs/03-UX-Design.md` § Permission Denial Fallback) — is **deferred** to a dedicated follow-up `mobile-location-permission-flow`, which will swap the Koin binding for a real provider without touching the screen/repository.

**Rationale:** the Mobile #5 menu entry scopes this change to the timeline render + states, explicitly NOT permission; the permission flow is a distinct platform surface of comparable size to Mobile #3's auth ceremony (precedent: Mobile #3 deferred attestation behind a tracked follow-up). Decoupling location *acquisition* from timeline *rendering* keeps the change small and one-PR-shippable, and the `LocationProvider` seam means the follow-up is a binding swap, not a rewrite. A fixed coordinate is sufficient to demo rendering + all states and to run the Phase 2 fuzzing audit (which is coordinate-agnostic).

- **(rejected) Bundle the full permission flow here.** Doubles the change size with platform location APIs + consent UX + denial fallback; violates the "intentionally small scaffold" constraint and couples two independently-reviewable surfaces.
- **(rejected) Hardcode the coordinate inline in the screen.** Loses the swap seam; the follow-up would have to edit the screen. The `LocationProvider` Koin binding is the clean injection point.

### D2 — Radius fixed at 20 km (Free default); the slider is deferred

The request uses `radius_m = 20000` — the Free-tier fixed radius per `docs/02-Product.md` § Nearby Timeline ("*Free: stuck at 20km*"). The 4-position slider (10/20/50/100 km) with the Free-bounce-back-and-upsell + Premium-pick behavior is **deferred** to `mobile-nearby-radius-slider` (it depends on Premium-tier UX that isn't built). A single named constant (not a magic literal) carries the value so the follow-up has one site to generalize.

### D3 — `HomeScreen` hosts `NearbyTimelineScreen`; `RootRouterScreen` (and `mobile-auth-signin`) is UNCHANGED

`NearbyTimelineScreen` is a new Voyager `Screen` (`mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt`). The existing placeholder `HomeScreen` is **repurposed** into a thin host whose `Content()` renders `NearbyTimelineScreen` (the sole content for now; a future tab-bar change makes `HomeScreen` the Nearby/Following/Global host — aligned with `docs/02-Product.md` line 180, "*Nearby and Following are home*"). `RootRouterScreen` still routes the authenticated path to `HomeScreen`, so the `mobile-auth-signin` § "RootRouterScreen routes based on token presence" requirement and its six scenarios are **untouched** (zero cross-spec churn). The `home_placeholder_title`/`home_placeholder_version` strings are no longer rendered (`HomeScreen` drops the wizard placeholder) but are **retained in the catalog** — consistent with how `signin_error_no_account` was kept after Mobile #4 stopped rendering it (a `shared-resources` ADDED delta brings the new strings without churning the large foundational-string requirement; catalog cleanup is a future task).

- **(rejected) New `NearbyTimelineScreen` as the routing target, modifying `mobile-auth-signin`.** Cleaner naming but rewrites six shipped, well-tested routing scenarios for no behavioral gain; the host-indirection keeps the auth spec stable.
- **(rejected) Fold the timeline directly into `HomeScreen.kt`.** Conflates "the home shell" with "the nearby feed," and makes the eventual tab-bar change a rewrite rather than an additive host change. A distinct, independently-testable `NearbyTimelineScreen` is the forward-compatible unit.

### D4 — `:shared:distance` becomes multiplatform via added KMP targets + platform crypto actuals; `JitterEngine` stays in commonMain

`:shared:distance` is currently `jvm()`-only — `:mobile:app` (android + iosArm64 + iosSimulatorArm64) cannot consume it (KMP target-attribute mismatch). This change adds `androidTarget()` + `iosArm64()` + `iosSimulatorArm64()` (mirroring `:shared:resources`' target set) and provides the `actual hmacSha256`/`unixMillis` that the new targets require for the `Crypto.kt` `expect`s:
- **androidMain**: `javax.crypto.Mac` + `System.currentTimeMillis()` — byte-for-byte the same as the shipped `jvmMain` actual (Android is JVM-based). The JVM and Android actuals share an intermediate source set (or duplicate the ~6 lines) — implementation choice.
- **iosMain**: `CCHmac(kCCHmacAlgSHA256, …)` from `platform.CoreCrypto` with `usePinned` for the key/msg/digest arrays (handling the empty-array pinning edge), and `NSDate().timeIntervalSince1970 * 1000` for `unixMillis`. *Verified 2026-05-31: CommonCrypto `CCHmac` via `platform.CoreCrypto` + `usePinned` remains the canonical Kotlin/Native iOS HMAC-SHA256 pattern (KotlinLang Slack #kotlin-native; Apple CommonCrypto). No new dependency required.*

`JitterEngine` + `UuidV7` + the `Crypto.kt` `expect`s **remain in commonMain** — required by `coordinate-jitter` spec § "JitterEngine lives in `:shared:distance` … commonMain." They are now compiled for the mobile targets too, so the jitter *algorithm* ships in the client binary; this is permitted by `coordinate-jitter` § Non-reversibility, which fences the **secret** (`JITTER_SECRET` MUST be absent from client paths — it is, it's injected at runtime backend-only), not the math. A new **commonTest known-answer test** asserts `hmacSha256` against a published **RFC 4231 HMAC-SHA-256 vector** (external oracle), so the new iOS/Android actuals are verified byte-identical to a known-correct value (today's crypto coverage is `jvmTest`-only). **Critical runner detail (from review):** the existing `:shared:distance` tests are Kotest `StringSpec`s run via JUnit5 (`useJUnitPlatform()`), which executes ONLY on JVM/Android — a Kotest `commonTest` compiles for the Native targets but never runs there. So the KAT MUST be written with **`kotlin.test`** (`@Test`, already a `commonTest` dep, executed by the Kotlin/Native test runner), and the verification command MUST include `:shared:distance:iosSimulatorArm64Test` (NOT just `:shared:distance:build`, which compiles but doesn't run Native tests) — otherwise the genuinely-new iOS CommonCrypto actual would ship with zero executed assertions. (The existing Kotest `DistanceRendererTest` remains JVM/Android-only; `render` is pure Kotlin so that pre-existing gap is low-risk and out of scope here.)

- **(rejected) Move `JitterEngine`/crypto to `jvmMain`** so mobile's commonMain stays pure (no iOS crypto). Architecturally tempting (server logic off the client) but **violates the `coordinate-jitter` spec's commonMain requirement** — it would force a MODIFY of a shipped, Detekt-enforced security capability for a much larger blast radius.
- **(rejected) Adopt a pure-Kotlin multiplatform crypto library (KotlinCrypto/MACs `hmac-sha2`, Okio)** to avoid the iOS cinterop. Surfaced by the 2026-05-31 search as a real alternative, but it adds a new `gradle/libs.versions.toml` pin (Version Decisions Log entry + substrate gate) to a security-sensitive module AND would rewrite the shipped `jvmMain` `hmacSha256`. CommonCrypto + javax.crypto add zero dependencies and leave the JVM actual untouched — strictly smaller surface for a scaffold.

### D5 — `X-Session-Id` is sent per app-process via `SessionIdProvider`

A `SessionIdProvider` (Koin `single`) supplies a stable-per-process id (`kotlin.uuid.Uuid.random().toString()` — stdlib, all targets; the UUID hex+hyphen shape matches the backend's `^[A-Za-z0-9-]{1,64}$`), sent as the `X-Session-Id` header on every Nearby fetch. This makes the backend's per-session soft-cap accounting actually per-session, rather than collapsing every read into the shared `no-session` bucket. The id is regenerated per process launch (acceptable: sessions are an hourly Redis bucket; a fresh id per launch simply starts a fresh soft-cap window).

### D6 — Status-driven outcome mapping + pure UI-state projection + repository interface seam

Mirroring Mobile #3/#4: `NearbyTimelineApiClient` (Koin `single`) holds `@Serializable` DTOs whose wire names mirror the SHIPPED backend serialization (mixed-case — see D10, NOT uniformly snake_case) and issues `client.get("/api/v1/timeline/nearby") { url params + header(X-Session-Id) }`. `NearbyTimelineRepository` (Koin `single`, bound behind a `NearbyTimelineFlow` interface so a `FakeNearbyTimelineFlow` drives screen tests) maps the **HTTP status** (never a parsed `error.code`) to a sealed `NearbyTimelineOutcome`:
- `200` → parse body → `Loaded(posts, nextCursor, upsell)`. The rate-limit **hard** state is *also* a `200` (empty posts + `upsell.hard`), so the screen derives the hard/soft presentation from the parsed `upsell` flags on a `Loaded` outcome — not from a distinct status.
- `401` → handled upstream by the shipped Ktor `Auth` refresh; a terminal 401 → `SessionInvalidator` clears the store and `RootRouterScreen` re-routes to `SignInScreen` (already shipped — the repository does not reimplement 401 logic).
- `400` (`invalid_request`/`location_out_of_bounds`/`radius_out_of_bounds`/`invalid_cursor` — not expected from the stub's always-valid params) → a retryable `Error` outcome with a logged diagnostic (not a silent no-op, not a crash).
- `5xx` / network-IO failure → `NetworkError` (retryable).

A pure Compose-free `NearbyTimelineUiState` + a `nearbyTimelineUiState(outcome, inFlight)` projection function is unit-tested in commonTest (deterministic outcome→state mapping), exactly as `AgeGateUiState`. There is no generic "load failed" fallthrough — every observed result maps to exactly one outcome.

### D7 — Screen states + canonical copy; pull-to-refresh

All copy via `stringResource(Res.string.X)` (zero hardcoded literals; verified by the Section-9 grep step). States:
- **Loading**: a skeleton/placeholder list + `timeline_loading` ("*Sedang memuat postingan…*", `docs/03-UX-Design.md` line 102).
- **Content**: a top bar titled `timeline_nearby_title` ("*Post dari lokasi ini*", `docs/02-Product.md` § UX Copy Strategy → "Timeline header") over a scroll list of read-only post cards (D9).
- **Empty** (posts `[]`, no `upsell`): `timeline_empty_nearby` ("*Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?*", `docs/03-UX-Design.md` line 100). The switch-to-Global affordance the copy implies is **deferred** (no Global screen exists yet) — render the message only; tracked by `mobile-timeline-empty-global-cta`.
- **Error** (network / 5xx / unexpected-4xx): reuse the existing `signin_error_network` + a `cta_retry` button.
- **Rate-limit hard** (posts `[]` + `upsell.hard`): `timeline_limit_hard` (derived BI copy — flag for review).
- **Rate-limit soft** (posts present + `upsell.soft`): a non-blocking banner above the list, `timeline_limit_soft` (derived BI copy — flag for review).
- **Pull-to-refresh**: Material 3 `PullToRefreshBox` re-fetches page 1.

### D8 — First page only + pull-to-refresh; infinite scroll deferred

The screen renders page 1 (≤ 30 posts) and re-fetches it on pull-to-refresh. `next_cursor` is parsed and retained on the `Loaded` outcome but not yet used to load more; cursor-based infinite scroll / load-more is **deferred** to `mobile-nearby-timeline-infinite-scroll`. A 30-post first page is sufficient to demo the scaffold and establish the card pattern.

### D9 — Visual pattern: Material 3 default built from `:shared:resources` tokens (sets the reference for later screens)

The user delegated the look ("Material 3 default — Claude proposes"); this Decision documents the proposed pattern for PR review. Built entirely on shipped tokens — no new colors/typography invented:
- **Theme**: rendered under `NearYouTheme` (light/dark); surfaces use `MaterialTheme.colorScheme.{background,surface,onSurface,onSurfaceVariant}`; the location/distance affordance uses the brand `MaterialTheme.colorScheme.locationPin` (coral, via `LocalNearYouColors`); type via `NearYouTypography` (Plus Jakarta Sans).
- **Post card**: a Material 3 surface/`Card` with the post `content` (`bodyLarge`), a metadata row of `city_name` + `DistanceRenderer.render(distance_m)` (with a coral location-pin glyph) + relative `created_at`, and a read-only counts row (`liked_by_viewer` heart state + `reply_count`). No author username is rendered (the API returns `author_user_id` only — a UUID, never displayed); no avatars.
- **List**: a `LazyColumn` inside `PullToRefreshBox`; loading shows shimmer/placeholder cards; empty/error/limit states are centered messages.
- **PII discipline**: render only API-returned display fields; never render `author_user_id`, never log tokens/coordinates/response bodies (`HttpClientFactory`'s `LogLevel.HEADERS` + `Authorization` sanitization already hold).

### D10 — Mobile DTOs mirror the SHIPPED mixed-case wire, not the spec's snake_case JSON example (review finding)

The `nearby-timeline` spec § Response shape documents the JSON in **snake_case** (`author_user_id`, `distance_m`, `created_at`, `next_cursor`), but the **shipped** `backend/.../timeline/TimelineRoutes.kt` `NearbyPostDto` / `NearbyResponse` (+ `Upsell.kt`) serialize **mixed-case**: `id`, `authorUserId`, `content`, `latitude`, `longitude`, `distanceM`, `createdAt` and top-level `nextCursor` are **bare camelCase** (no `@SerialName`); only `city_name`, `liked_by_viewer`, `reply_count` carry `@SerialName` snake_case; `upsell.{soft,hard}` are bare. There is no global `JsonNamingStrategy`. **The mobile DTOs MUST mirror the shipped wire** (camelCase for the seven + `nextCursor`; `@SerialName` only for the three) — generated directly from `TimelineRoutes.kt`, NOT from the spec's JSON example — or 4 fields would silently fail to parse against the deployed backend. This was caught by the multi-lens review (the security lens read the shipped DTO); my initial proposal incorrectly assumed uniform snake_case.

The spec-vs-code casing drift (the `nearby-timeline`/`following`/`global` Response-shape JSON examples say snake_case; the shipped DTOs emit camelCase for those fields) is a **pre-existing backend issue**, NOT fixed here (this is a mobile change). It is logged as a `FOLLOW_UPS.md` entry `timeline-response-dto-casing-drift` for the backend owner to reconcile — either add `@SerialName` to the backend DTOs to match the spec (then coordinate a mobile DTO update), or amend the timeline specs' JSON examples to the camelCase reality. Until that resolves, the mobile client tracks the deployed wire.

## Risks / Trade-offs

- **Stub location shows Jakarta posts regardless of the device's real location** → acceptable for a scaffold whose goal is rendering + states + the fuzzing audit (coordinate-agnostic); the `LocationProvider` seam makes the real-GPS follow-up a binding swap. Mitigation: D1 + a tracked `mobile-location-permission-flow` follow-up.
- **New iOS HMAC actual is security-adjacent code shipped to clients** → mitigated by (a) the secret never shipping (only the algorithm), (b) a commonTest known-answer test proving byte-identity with the audited JVM actual, and (c) using the canonical CommonCrypto pattern (no bespoke crypto). The mobile app never *calls* `hmacSha256`/`JitterEngine` — they exist only to satisfy the shared module's `expect`s.
- **Derived rate-limit copy (`timeline_limit_hard`/`soft`) is not docs-canonical** → only the empty-state + loading copy are verbatim in the docs. Mitigation: sensible BI copy consistent with the Mobile #3/#4 register, flagged in Open Questions for review.
- **Empty-state copy promises a "lihat Global" action that isn't wired** → rendering the message without the button avoids a dead control; tracked by `mobile-timeline-empty-global-cta`. Mitigation: ship the message, defer the affordance.
- **Per-launch session id starts a fresh soft-cap window each cold start** → a Free user who relaunches frequently resets the 50/session soft nudge. Acceptable (the rolling 150/hour hard cap is unaffected, being per-user not per-session); a persisted session id is a future refinement if telemetry shows abuse.
- **`upsell.hard` empty state vs. genuinely-empty area** are distinct outcomes that look similar → they use *different* copy (`timeline_limit_hard` vs `timeline_empty_nearby`) and are driven by the presence/absence of `upsell.hard`, so the screen never conflates "you hit your limit" with "your area is quiet."

## Migration Plan

No data migration. All changes are additive within `:mobile:app`, `:shared:distance`, and `:shared:resources`:
1. Add the three KMP targets + android/iOS crypto actuals to `:shared:distance`; add the commonTest HMAC known-answer test; add `implementation(projects.shared.distance)` to `:mobile:app` commonMain.
2. Add the timeline strings to `:shared:resources` (additive); stop rendering `home_placeholder_*` (retained in the catalog).
3. Add `NearbyTimelineApiClient` (DTOs), `NearbyTimelineRepository`/`NearbyTimelineFlow`, `NearbyTimelineOutcome`, `NearbyTimelineUiState` + projection, `LocationProvider`/`StubLocationProvider`, `SessionIdProvider`.
4. Add `NearbyTimelineScreen` + states + pull-to-refresh; repurpose `HomeScreen` to host it; wire Koin `mobileModule`.
5. Tests: Robolectric `NearbyTimelineScreenTest` (add to the Release-variant exclude list), commonTest `NearbyTimelineUiStateTest`, MockEngine `NearbyTimelineApiClient`/`Repository` tests; the commonTest HMAC KAT.
6. No-hardcoded-strings grep verification; pre-archive staging smoke (sign in → land on Nearby → render posts / empty / error).

Rollback: revert the branch; `HomeScreen` returns to the placeholder. `:shared:distance` reverts to `jvm()`-only (backend unaffected — it only ever used the JVM target). No persisted state to unwind.

## Open Questions

- **Rate-limit copy.** `timeline_limit_hard` ("hourly reading limit reached") and `timeline_limit_soft` (Premium nudge) are derived, not docs-canonical — confirm wording at review (and whether the soft banner should carry a Premium CTA given billing isn't built).
- **Empty-state Global CTA.** Ship the message-only now (button deferred to `mobile-timeline-empty-global-cta`) — confirm that's the intended sequencing rather than an omission.
- **`:shared:distance` android/jvm actual sharing.** Intermediate source set vs. duplicating the ~6-line javax.crypto actual — a build-structure choice settled at implementation (re-checked per the apply-phase rule if the convention plugin constrains it).
- **Bundling the `:shared:distance` multiplatform-ization here** vs. splitting it as a tiny precursor change — bundled per the build.gradle comment ("the mobile change" adds the targets) + one-PR-per-change; flag if the reviewer prefers a split.
