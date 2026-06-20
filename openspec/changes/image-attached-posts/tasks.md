## 1. Pre-flight: verification, mockups, substrate re-check

- [ ] 1.0 Runtime sanity check: confirm `visible_posts.image_id` is live on a current schema (`SELECT image_id FROM visible_posts LIMIT 1` against a freshly-migrated DB) BEFORE any read query relies on it — fail loudly if a future migration enumerated the view columns (design D4).
- [ ] 1.1 Apply-time dated library re-check (project.md § Pre-implementation library re-check, MUST): fresh `WebSearch` for Coil 3 KMP currency (e.g. `"Coil 3 Compose Multiplatform <current-month-year> stable"`); drop a one-line evidence note in the first feat commit body. If a materially-better loader surfaced since propose-time, STOP and surface via `AskUserQuestion`.
- [ ] 1.2 Consult mockups + generate measurement annexes (docs/11 §2.8): composer = frame 6 (`nearyou-screens-mockup.html`), post card = frames 1 + 19, post detail frame. Render each + run `dev/scripts/mockup-measure.sh` for the image affordance, thumbnail/preview, and in-card image spacing/tokens.
- [ ] 1.3 Pin Coil 3 (+ Ktor network backend) in `gradle/libs.versions.toml`; wire the dependency into `:mobile:app` (commonMain). Confirm the pin does not regress the existing Ktor version alignment.

## 2. Backend read-path image surfacing (no migration)

- [ ] 2.1 Add a server-side delivery-URL builder that maps `image_id` → `<deliveryBaseUrl>/<image_id>/<variant>` (single variant), reusing the **same env-aware `deliveryBaseUrl`** the upload path wires (`Application.kt`: `img.nearyou.id` prod / `img-staging.nearyou.id` staging) + the same variant; null-safe (null `image_id` → null URL).
- [ ] 2.2 `TimelineRoutes.kt`: add `val imageUrl: String?` (bare camelCase) to `NearbyPostDto`, `FollowingPostDto`, `GlobalPostDto`; select `image_id` from `visible_posts` in each timeline query and map via the builder.
- [ ] 2.3 Single-post-read DTO + post-detail read DTO: add `imageUrl: String?`, select `image_id` from `visible_posts`, map via the builder.
- [ ] 2.4 Confirm image-bearing rows still flow through the existing `visible_posts` + block-exclusion joins unchanged (no new raw `FROM posts` read; lint clean).

## 3. Mobile read-path rendering (PostCard + detail)

- [ ] 3.1 Extend the shared `PostCard` model with `imageUrl: String?`; render a Coil 3 `AsyncImage` below the content when non-null, with aspect-ratio placeholder + graceful failure (no error chrome) and no scroll preload (on-screen load only). No image element when null.
- [ ] 3.2 Thread `imageUrl` from the timeline DTOs → card model in Nearby + Global (and Following) feed mapping.
- [ ] 3.3 Add `imageUrl: String? = null` to `PostDetailRoute` (defaulted; registered in the polymorphic `SerializersModule`); render the image on `PostDetailScreen` below the content; thread `imageUrl` from the feed card tap into the route payload.

## 4. Mobile image picker + compression seam (§2.5)

- [ ] 4.1 commonMain `interface ImagePicker { suspend fun pick(): PickedImage? }` + `PickedImage(bytes, mime)` in `id/nearyou/app/image/`.
- [ ] 4.2 Android actual: `ActivityResultContracts.PickVisualMedia` + `Bitmap` re-encode loop to ≤5 MB; bind in the Android Koin platform module (no business logic in androidMain).
- [ ] 4.3 iOS actual: `PHPickerViewController` + ImageIO/`UIImage` downscale to ≤5 MB; bind in the iOS Koin platform module. Add explicit `import platform.<Framework>.<symbol>` for any ObjC category members (docs/11 §2.5 K/N caveat).

## 5. Mobile upload data layer (§2.6)

- [ ] 5.1 `ImageUploadApiClient`: multipart `POST /api/v1/images` via `MultiPartFormDataContent` on the shared `HttpClient` (Auth plugin owns Bearer + refresh; no ad-hoc client); parse `201 {image_id, delivery_url}`; rethrow `CancellationException`.
- [ ] 5.2 `ImageUploadRepository` + sealed `ImageUploadOutcome` (`Success`/`PremiumRequired`/`FeatureDisabled`/`QuotaExceeded`/`Throttled`/`ModerationRejected`/`TooLarge`/`Network`); map HTTP status + `error.code`; exhaustive `when` (no `else`).
- [ ] 5.3 Koin wiring for `ImagePicker`-consumer, `ImageUploadApiClient`, `ImageUploadRepository`.

## 6. Mobile compose-with-image authoring (gating + submit + strings)

- [ ] 6.1 `PostCreationScreen`: render the Premium image-attach affordance (mockup frame 6 image button); Premium → `ImagePicker.pick()`, Free → shared cap-upsell/paywall (no picker). No new Remote Config client.
- [ ] 6.2 Upload-then-attach flow: in-progress state → thumbnail preview + remove affordance; disable "Posting" CTA while upload in flight; map each `ImageUploadOutcome` to a UI state; never submit a post referencing a failed/missing image.
- [ ] 6.3 `PostCreationApiClient` request DTO: add optional `@SerialName("image_id") val imageId: String? = null`; include only when attached; text-only body byte-identical to pre-change.
- [ ] 6.4 New Bahasa Indonesia strings in `:shared:resources` for the attach affordance, preview/remove, upload progress, and each outcome (`Res.string.*`); no hardcoded literals.

## 7. Tests (Definition of Done, docs/11 §5)

- [ ] 7.1 Mobile commonTest: attach affordance Premium-gated (Premium opens picker; Free upsold, picker not invoked); `image_id` present in body only when attached; remove clears attachment; CTA disabled during upload; each `ImageUploadOutcome` → UI state; no hardcoded strings.
- [ ] 7.2 Mobile commonTest: `PostCard` + `PostDetailScreen` render an image when `imageUrl` present and are unchanged when null (negative guard); `PostDetailRoute` decodes a pre-`imageUrl` payload with `imageUrl = null` (back-compat).
- [ ] 7.3 `ImageUploadApiClient` MockEngine tests: multipart `POST /api/v1/images`; 201 parse; 422→`ModerationRejected`; 403 flag vs premium → `FeatureDisabled` vs `PremiumRequired`; `CancellationException` rethrown.
- [ ] 7.4 iOS: `:mobile:app:iosSimulatorArm64Test` (or at minimum `linkDebugFrameworkIosSimulatorArm64`) for the PHPicker/ImageIO actual.
- [ ] 7.5 Backend `:backend:ktor:test`: timeline + single-post + detail responses carry `imageUrl` (built URL) for an image post and `null` for a text-only post; image-bearing post excluded under shadow-ban/block exactly as text-only.
- [ ] 7.6 Local gates green: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`.

## 8. Manual verification + staging (pre-archive)

- [ ] 8.1 verify-loop bring-up (UI-affecting; context-routed): compose a Premium image post, confirm upload→attach→render in feed + detail; screenshot evidence in the PR body (light + dark). Confirm Free-tier upsell path.
- [ ] 8.2 Staging branch deploy (`gh workflow run deploy-staging.yml --ref image-attached-posts`) + smoke the read-path `imageUrl` surfacing (runtime-impacting backend change, project.md § Staging deploy timing).

## 9. Deploy (prod — leave unchecked until prod infra/flag flip)

- [ ] 9.1 Confirm `image_upload_enabled` remains FALSE in production (feature stays gated until Month 6 launch rehearsal); dogfood in the QA variant only.
