## 1. `:shared:distance` multiplatform-ization (coordinate-jitter delta)

- [ ] 1.1 Pre-implementation re-check (per `openspec/project.md` § Apply-phase / pre-impl re-check): fresh dated WebSearch confirming the canonical Kotlin/Native iOS HMAC-SHA256 pattern (CommonCrypto `CCHmac` via `platform.CoreCrypto` + `usePinned`) is still current; drop a one-line evidence note in the first feat commit body. (No new `libs.versions.toml` pin enters, so this is a confirm-only check.)
- [ ] 1.2 Make `shared/distance/build.gradle.kts` multiplatform-consumable (mirror `shared/resources/build.gradle.kts`): apply `alias(libs.plugins.androidLibrary)`; add `androidTarget { compilerOptions { jvmTarget JVM_11 } }` + `iosArm64()` + `iosSimulatorArm64()` alongside the existing `jvm()` (NO `binaries.framework` block — `:shared:distance` is a transitive dependency of `:mobile:app`, not directly Xcode-consumed); add an `android { namespace = "id.nearyou.distance"; compileSdk; defaultConfig { minSdk }; compileOptions JVM_11 }` block. Remove the now-satisfied "deferred to the mobile change" comment.
- [ ] 1.3 Add `androidMain` `actual fun hmacSha256` (`javax.crypto.Mac` "HmacSHA256") + `actual fun unixMillis` (`System.currentTimeMillis()`) — share with `jvmMain` via an intermediate source set OR duplicate the ~6 lines (build-structure choice; verify against the convention plugin).
- [ ] 1.4 Add `iosMain` `actual fun hmacSha256` via CommonCrypto `CCHmac(kCCHmacAlgSHA256, …)` (`platform.CoreCrypto`, `usePinned` for key/msg/32-byte digest, `@OptIn(ExperimentalForeignApi::class)`, handle the empty-array pinning edge) + `actual fun unixMillis` via Foundation `NSDate` epoch.
- [ ] 1.5 Add a `commonTest` HMAC known-answer test asserting `hmacSha256(<fixed key>, <fixed msg>)` == a fixed expected 32-byte digest (published HMAC-SHA256 vector or a vector captured from the JVM actual), so JVM + Android + iOS actuals are verified byte-identical. Confirm the existing `commonTest` `DistanceRendererTest` now also compiles/runs on the mobile targets.
- [ ] 1.6 Add `implementation(projects.shared.distance)` to `mobile/app/build.gradle.kts` `commonMain.dependencies`.
- [ ] 1.7 Build green on all targets: `./gradlew :shared:distance:build` (jvm + android + ios) and confirm `:backend:ktor` still compiles (it only ever used the JVM target — `JitterEngine`/`UuidV7` unchanged in `commonMain`).

## 2. Bahasa Indonesia strings (shared-resources delta)

- [ ] 2.1 Add to `shared/resources/src/commonMain/composeResources/values/strings.xml`: `timeline_nearby_title` = "Post dari lokasi ini", `timeline_loading` = "Sedang memuat postingan…", `timeline_empty_nearby` = "Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?" (all three byte-identical to the cited docs). 
- [ ] 2.2 Add the derived rate-limit strings `timeline_limit_hard` + `timeline_limit_soft` (Bahasa Indonesia, consistent with the Mobile #3/#4 register; flagged for UX review in design Open Questions).
- [ ] 2.3 Confirm `home_placeholder_title` / `home_placeholder_version` remain in `strings.xml` (retained, no longer rendered) and no earlier string text is altered. Verify the generated `Res.string.*` accessors compile for the new keys.

## 3. Networking DTOs + `NearbyTimelineApiClient`

- [ ] 3.1 Define `@Serializable` response DTOs in `mobile/app/src/commonMain/.../timeline/` with snake_case `@SerialName`: top-level `{ posts, next_cursor: String?, upsell: UpsellDto? }`; `UpsellDto(soft: Boolean = false, hard: Boolean = false)`; per-post `{ id, author_user_id, content, latitude: Double, longitude: Double, distance_m: Double, created_at: String, liked_by_viewer: Boolean, reply_count: Int, city_name: String }`. `next_cursor`/`upsell` tolerate absence/null (`explicitNulls = false` is already configured on the shared `Json`).
- [ ] 3.2 Implement `NearbyTimelineApiClient.fetchNearby(lat, lng, radiusM, sessionId, cursor: String? = null)` → `client.get("/api/v1/timeline/nearby") { parameter("lat",…); parameter("lng",…); parameter("radius_m",…); cursor?.let { parameter("cursor", it) }; header("X-Session-Id", sessionId) }`; return a status-tagged result type (mirror `AuthApiClient`'s `*ApiResult`), NOT a thrown exception, for non-2xx.
- [ ] 3.3 Define `NEARBY_RADIUS_M = 20000` as a single named constant (the Free-tier fixed radius); no magic literal at the call site.

## 4. Repository, outcome mapping, providers

- [ ] 4.1 Define the sealed `NearbyTimelineOutcome` (`Loaded(posts, nextCursor, upsell)`, `NetworkError`, `Error`) and the `NearbyTimelineFlow` interface (`suspend fun loadFirstPage(): NearbyTimelineOutcome`).
- [ ] 4.2 Implement `NearbyTimelineRepository : NearbyTimelineFlow` mapping HTTP **status** → outcome with no generic fallthrough: 200 → `Loaded` (carrying parsed `upsell`); 400 → retryable `Error` + logged diagnostic; 5xx / IO → `NetworkError`; 401 delegated to the shipped `Auth` plugin (NOT reimplemented). Reuse `LocationProvider` + `SessionIdProvider` + `NEARBY_RADIUS_M`.
- [ ] 4.3 Define the commonMain `LocationProvider` interface (`suspend fun current(): LatLng`, reusing `id.nearyou.distance.LatLng`) and `StubLocationProvider` returning `LatLng(-6.2, 106.8)`. Do NOT reference any platform location/permission API.
- [ ] 4.4 Define `SessionIdProvider` returning a stable-per-process id (`kotlin.uuid.Uuid.random().toString()`, `@OptIn(ExperimentalUuidApi::class)`); confirm the value matches `^[A-Za-z0-9-]{1,64}$`.

## 5. Pure UI state + projection

- [ ] 5.1 Define the Compose-free `NearbyTimelineUiState` (loading / content+posts / empty / hard-limit / soft-limit+posts / error) and a pure `nearbyTimelineUiState(outcome, inFlight)` projection (mirror `AgeGateUiState`). Carry NO PII (no `author_user_id`, no coordinates) into the state.
- [ ] 5.2 Map the rate-limit presentation from the parsed `upsell` on a `Loaded` outcome (empty + `hard` → hard-limit; non-empty + `soft` → soft-limit+banner), distinct from the genuinely-empty (`Loaded` empty, no upsell) state.

## 6. `NearbyTimelineScreen` + `HomeScreen` host

- [ ] 6.1 Implement `NearbyTimelineScreen` (`mobile/app/src/commonMain/.../screens/timeline/NearbyTimelineScreen.kt`): inject `NearbyTimelineFlow` via `koinInject`; hold outcome + inFlight via `remember`/`LaunchedEffect`; render under `NearYouTheme`; top bar `timeline_nearby_title`; `LazyColumn` of post cards inside a Material 3 `PullToRefreshBox`. Every string via `stringResource` (zero literals).
- [ ] 6.2 Implement the read-only post card (D9 visual pattern): `content`, a metadata row (`city_name` + coral location-pin glyph + `DistanceRenderer.render(distance_m)` + relative `created_at`), and a read-only counts row (`liked_by_viewer` heart state + `reply_count`). Render NO `author_user_id`, NO raw lat/lng. Use `MaterialTheme.colorScheme.*` + `locationPin` + `NearYouTypography`.
- [ ] 6.3 Render the six states per the screen-state-mapping spec: loading skeleton + `timeline_loading`; content list; empty + `timeline_empty_nearby`; error + `signin_error_network` + `cta_retry` button (re-invokes load); hard-limit + `timeline_limit_hard` (no cards); soft-limit banner + `timeline_limit_soft` (above cards).
- [ ] 6.4 Wire pull-to-refresh to re-invoke `loadFirstPage()`. Parse + retain `next_cursor` on `Loaded` but do NOT wire load-more (deferred).
- [ ] 6.5 Repurpose `HomeScreen.Content()` to render `NearbyTimelineScreen` (drop the placeholder logo/title/version block); stop referencing `home_placeholder_*`. Do NOT edit `RootRouterScreen` routing.

## 7. Koin wiring

- [ ] 7.1 Register in `MobileModule.kt`: `single { NearbyTimelineApiClient(get()) }`, `single { NearbyTimelineRepository(get(), get(), get()) }`, `single<NearbyTimelineFlow> { get<NearbyTimelineRepository>() }`, `single<LocationProvider> { StubLocationProvider() }`, `single { SessionIdProvider() }`.

## 8. Tests

- [ ] 8.1 `NearbyTimelineUiStateTest` (commonTest): the pure projection maps each of in-flight / loaded-non-empty / loaded-empty-no-upsell / loaded-empty-hard / loaded-non-empty-soft / NetworkError to its state, deterministically.
- [ ] 8.2 MockEngine `NearbyTimelineApiClientTest` / `NearbyTimelineRepositoryTest` (commonTest): assert the request path `/api/v1/timeline/nearby` + `lat`/`lng`/`radius_m=20000` params + no first-page `cursor`; the `X-Session-Id` header is sent and matches the regex; snake_case body parse (all 10 post fields, `next_cursor`, optional `upsell`); the status→outcome mapping (200 Loaded incl. hard-cap empty+upsell.hard, 400 retryable+log, 5xx/IO NetworkError); `liked_by_viewer`/`city_name`/`reply_count` parsed.
- [ ] 8.3 `NearbyTimelineScreenTest` (androidUnitTest, Robolectric `runComposeUiTest`, `KoinContext` + `FakeNearbyTimelineFlow`): initial render shows `timeline_nearby_title`; each of the six states renders its asserted node(s); error retry is clickable; `author_user_id` + raw coordinates are NOT in the rendered tree.
- [ ] 8.4 Add `**/NearbyTimelineScreenTest*` to the `mobile/app/build.gradle.kts` `tasks.withType<Test>()` Release-variant exclude block (per the established `*ScreenTest` convention). Verify `:mobile:app:testDevReleaseUnitTest` passes (not just Debug).
- [ ] 8.5 Add/confirm a `FakeNearbyTimelineFlow` (commonTest) returning fixed outcomes + counting invocations (mirror `FakeAuthFlow`).
- [ ] 8.6 Full local gate: `./gradlew ktlintCheck detekt :mobile:app:testDebugUnitTest :mobile:app:testDevReleaseUnitTest :shared:distance:build`.

## 9. No-hardcoded-strings verification

- [ ] 9.1 Run the documented grep step (per `shared-resources` spec § negative-requirement grep) over `mobile/app/src/commonMain`, `androidMain`, `iosMain` for the new timeline source; confirm zero hardcoded UI string literals (every UI string via `stringResource(Res.string.X)`).

## 10. Pre-archive staging smoke (runtime-impacting → smoke before archive)

- [ ] 10.1 Build + run the app against `dev`/`staging` (per `dev/docs` runbook + the local-run memory): sign in → land on `HomeScreen`/Nearby → confirm posts render with distance + city + counts (or the empty/error state) against a real backend.
- [ ] 10.2 If a staging branch deploy is used: `gh workflow run deploy-staging.yml --ref mobile-nearby-timeline-screen` → poll → smoke. (Mobile-only change with no new backend route — staging smoke is mobile-client-against-existing-staging-API; mark backend-deploy tasks N/A.)

## 11. FOLLOW_UPS + docs

- [ ] 11.1 Add `FOLLOW_UPS.md` entries: `mobile-location-permission-flow` (real GPS + permission + UU-PDP consent modal + denial fallback; cite `docs/03-UX-Design.md` § Location Permission / § Permission Denial Fallback), `mobile-nearby-radius-slider` (10/20/50/100 km slider + Free-bounce/Premium-pick), `mobile-nearby-timeline-infinite-scroll` (cursor pagination / load-more), `mobile-timeline-empty-global-cta` (empty-state switch-to-Global affordance once a Global screen exists).
- [ ] 11.2 No new module added → README/`module-descriptions.txt` sync not required; confirm `dev/scripts/sync-readme.sh --check` stays clean (the `:shared:distance` target additions don't change the module list).
- [ ] 11.3 `openspec validate mobile-nearby-timeline-screen --strict` green; PR body updated to reflect implementation scope at the first feat commit (retitle to `feat(mobile): mobile-nearby-timeline-screen`).
