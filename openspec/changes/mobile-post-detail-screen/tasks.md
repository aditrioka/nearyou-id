## 1. Strings (`:shared:resources`)

- [ ] 1.1 Add the 8 net-new Bahasa Indonesia keys to `shared/resources/src/commonMain/composeResources/values/strings.xml`: `post_detail_posted_from` (formatted, `"Diposting dari %1$s, %2$s"`), `post_detail_like_count` (formatted, `"%1$d suka"`), `post_detail_likes_cap_upsell` (`"Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam %1$s."`), `post_detail_replies_empty` (`"Belum ada balasan. Jadilah yang pertama."`), `post_detail_reply_placeholder`, `post_detail_reply_counter` (formatted, `"%1$d/280"`), `cta_reply` (`"Balas"`), `post_detail_reply_cap_upsell` (newly authored parallel to the like-cap copy — docs have no canonical reply-cap string; e.g. `"Kamu sudah menggunakan 20 balasan hari ini. Upgrade ke Premium untuk balas tanpa batas, atau tunggu reset dalam %1$s."`). Reuse existing `signin_error_network`, `cta_retry`, `timeline_loading` (the replies-loading + screen-loading state reuses `timeline_loading` — do NOT add a `post_detail_loading`; do NOT redeclare the reused keys). If the empty-`cityName` header needs its own format, add a 9th key `post_detail_posted_from_no_city` and adjust the count.
- [ ] 1.2 Update `SharedStringsCatalogTest` (`mobile/app/src/commonTest/kotlin/id/nearyou/app/resources/SharedStringsCatalogTest.kt`) to reference each new `Res.string.*` accessor by name and bump its declared-count `assertEquals` (current baseline **55** → **63** for the 8 new keys). NOTE: the count is shared catalog state — if a parallel strings-catalog change (e.g. #157 `mobile-analytics-consent-screen`) squash-merges first, rebase and re-derive the delta from the then-current baseline rather than assuming 55.

## 2. Networking — ApiClient(s) + DTOs (SHIPPED wire)

- [ ] 2.1 Add `LikeApiClient` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/post/`) issuing `POST` / `DELETE /api/v1/posts/{post_id}/like` and `GET /api/v1/posts/{post_id}/likes/count`; parse `LikesCountResponse(count: Long)` (`{ "count": <Long> }`). No `X-Session-Id` header; Bearer via the shipped `Auth` plugin. Rethrow `CancellationException`.
- [ ] 2.2 Add `ReplyApiClient` issuing `POST` / `GET /api/v1/posts/{post_id}/replies`; define `@Serializable` `ReplyCreateRequest(content: String)`, `ReplyDto` (`id`, `@SerialName("post_id")`, `@SerialName("author_id")`, `content`, `@SerialName("is_auto_hidden")`, `@SerialName("created_at")`, `@SerialName("updated_at") String?`, `@SerialName("deleted_at") String?`), `ReplyListResponse(replies, @SerialName("next_cursor") nextCursor: String? = null)` — generated from `backend/ktor/.../engagement/ReplyRoutes.kt`, NOT a spec JSON example. Record a comment that `next_cursor` is snake_case (unlike the timelines' camelCase `nextCursor`).
- [ ] 2.3 Map non-2xx responses to typed results (parse the `{ "error": { "code" } }` envelope best-effort; a non-2xx is a value, never a thrown exception), preserving `Retry-After` for 429.

## 3. Repository + sealed outcomes + `PostDetailFlow` seam

- [ ] 3.1 Define the sealed outcomes: `LikeOutcome` (`Liked` / `Unliked` / `RateLimited(retryAfterSeconds)` / `PostGone` / `NetworkError`), `ReplyPostOutcome` (`Success(reply)` / `ContentEmpty` / `ContentTooLong` / `RateLimited(retryAfterSeconds)` / `PostGone` / `NetworkError`), `RepliesOutcome` (`Loaded(replies, nextCursor)` / `NetworkError`), and a like-count result. No generic wildcard branch; `401` delegated to the `Auth` plugin.
- [ ] 3.2 Add `PostDetailFlow` interface exposing `loadReplies()`, `toggleLike(currentlyLiked)`, `postReply(content)`, `likeCount()`; implement `PostDetailRepository` mapping each HTTP status + transport-failure to exactly one sealed member. Append-on-201 is the caller's concern (the repo returns `Success(reply)`); the repo does NOT re-fetch the list.
- [ ] 3.3 Add `FakePostDetailFlow` (commonTest) driving all screen-test paths.

## 4. Pure UI-state projection

- [ ] 4.1 Add Compose-free `PostDetailUiState` + pure projection function(s) mapping each outcome → state, plus the reply code-point gate (≥1 non-blank ∧ ≤280 Unicode code points ∧ not in-flight). No PII, no wall-clock/platform dependency.

## 5. Navigation — `PostDetailRoute` + wiring

- [ ] 5.1 Add `PostDetailRoute` to `screens/routing/NavKeys.kt` as a `@Serializable` payload-carrying `NavKey` with `postId`, `content`, `cityName`, `distanceM: Double?`, `createdAtIso`, `likedByViewer`, `replyCount` — and NO `latitude`/`longitude`.
- [ ] 5.2 Register `PostDetailRoute` in `screens/routing/AppNavSerialization.kt`'s `navSavedStateConfiguration` polymorphic `SerializersModule` (`polymorphic(NavKey::class) { ... }`, iOS-saveable back stack) and map it to `PostDetailScreen` in `screens/routing/AppEntryProvider.kt`'s `appEntryProvider`.
- [ ] 5.3 Add a hoisted `onOpenPost(...)` lambda to `NearbyTimelineScreen` + `GlobalTimelineScreen` cards (carrying display fields, no coordinates; Global passes `distanceM = null`); the screens stay navigation-free.
- [ ] 5.4 In `HomeScreen` (tab host) wire `onOpenPost` from the Nearby + Global tab content to append `PostDetailRoute` (built from the card fields) to the **root** back stack; pass an `onBack` to `PostDetailScreen`. No per-tab `NavDisplay` introduced.

## 6. `PostDetailScreen` UI

- [ ] 6.1 Build `PostDetailScreen` (`screens/post/PostDetailScreen.kt`): header (content + `post_detail_posted_from`, empty-city tolerated, no PII/coords), like control (optimistic toggle + count + cap upsell), replies list (loading/empty/error states, reply cards = content + timestamp only), reply composer (placeholder + `N/280` code-point counter + "Balas" CTA disabled when empty/over-limit/in-flight), all copy via `stringResource`, under `NearYouTheme`. Back affordance invokes the hoisted `onBack`.
- [ ] 6.2 Wire the like flow: initial state from the `likedByViewer` route arg; optimistic flip + revert on non-204; 429 → `post_detail_likes_cap_upsell`; fetch + show count via `likeCount()` with graceful degradation.
- [ ] 6.3 Wire the reply flow: on 201 append the returned reply locally + increment the displayed count (no list re-fetch); 429 → `post_detail_reply_cap_upsell`; error banners for empty/too-long/network.

## 7. Koin wiring + PII discipline

- [ ] 7.1 Register `LikeApiClient`, `ReplyApiClient`, `PostDetailRepository` as singletons in `di/MobileModule.kt`; bind `single<PostDetailFlow> { get<PostDetailRepository>() }`; reuse the shared `HttpClient` (no new client, no `X-Session-Id`).
- [ ] 7.2 Confirm `HttpClientFactory` stays at `LogLevel.HEADERS` (not widened) and no coordinate/body/`author_id` is logged or rendered.

## 8. FOLLOW_UPS entries

- [ ] 8.1 Add `FOLLOW_UPS.md` entries: `mobile-post-detail-block-report-kebab`, `mobile-post-detail-inline-card-actions`, `mobile-post-detail-replies-infinite-scroll`, `backend-single-post-get-endpoint` (the last owned by the future notifications deep-link change).

## 9. Tests

- [ ] 9.1 commonTest: `PostDetailUiState` projection (each outcome→state + code-point counter incl. the 280/281 boundary + multi-byte emoji), and the `PostDetailRoute` serialized round-trip via the polymorphic serializer.
- [ ] 9.2 commonTest: MockEngine `LikeApiClient`/`ReplyApiClient`/`PostDetailRepository` tests — like POST/DELETE→204 mapping (DELETE never 404), `GET /likes/count` parse, reply `201`→`ReplyDto` parse against the shipped snake_case wire, the camelCase `nextCursor` negative-guard (must NOT populate; snake `next_cursor` must), replies-list parse + `next_cursor` retained-not-consumed, and each status→outcome (429 retry-after, 404 PostGone, 5xx/IO NetworkError, 400 invalid_request→ContentEmpty/ContentTooLong).
- [ ] 9.3 Robolectric `PostDetailScreenTest` (`mobile/app/src/androidUnitTest/...`): header render (no PII/coords, empty-city tolerated), replies states, like toggle (optimistic + 429 upsell + count), reply composer (counter, 280-disable, 201 local-append, 429 upsell, error banners), no-single-post-GET assertion, no block/report/inline affordance assertion — via `FakePostDetailFlow`.
- [ ] 9.4 Add `**/PostDetailScreenTest*` to the `mobile/app/build.gradle.kts` Release-variant `tasks.withType<Test>()` exclude block; verify `:mobile:app:testDevReleaseUnitTest` passes.
- [ ] 9.5 iosTest flow test `PostDetailFlowIosTest` (`mobile/app/src/iosTest/...`) mirroring `NearbyTimelineFlowIosTest` (CMP 1.11.1 `runComposeUiTest`).

## 10. Validate + gate

- [ ] 10.1 Run `openspec validate mobile-post-detail-screen --strict` — green.
- [ ] 10.2 Run the pre-push gate `./gradlew ktlintCheck detekt :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` (root-level detekt; flavor-qualified mobile test tasks) — green. Run iOS link/test locally where the CI Linux runner cannot (`:mobile:app:linkDebugFrameworkIosSimulatorArm64` + `iosSimulatorArm64Test`).
- [ ] 10.3 Staging deploy + smoke is **N/A** (mobile-only, no backend/schema change) — mark Section N/A in the archive commit body.
