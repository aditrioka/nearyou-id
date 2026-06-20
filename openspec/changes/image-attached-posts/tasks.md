## 1. Pre-flight: verification, mockups, substrate re-check

- [x] 1.0 Runtime sanity check: confirm `visible_posts.image_id` is live on a current schema (`SELECT image_id FROM visible_posts LIMIT 1` against a freshly-migrated DB) BEFORE any read query relies on it — fail loudly if a future migration enumerated the view columns (design D4). [Verified 2026-06-20 against the dev DB (V28): `visible_posts.image_id` live; full read-path manual-verified in dev — single-post (visible + self arm) + global timeline surface the 4-segment `imageUrl`, text-only posts omit it.]
- [x] 1.1 Apply-time dated library re-check (project.md § Pre-implementation library re-check, MUST): fresh `WebSearch` for Coil 3 KMP currency (e.g. `"Coil 3 Compose Multiplatform <current-month-year> stable"`); drop a one-line evidence note in the first feat commit body. If a materially-better loader surfaced since propose-time, STOP and surface via `AskUserQuestion`.
- [ ] 1.2 Consult mockups + generate measurement annexes (docs/11 §2.8): composer = frame 6 (`nearyou-screens-mockup.html`), post card = frames 1 + 19, post detail frame. Render each + run `dev/scripts/mockup-measure.sh` for the image affordance, thumbnail/preview, and in-card image spacing/tokens.
- [x] 1.3 Pin Coil 3 (+ Ktor network backend) in `gradle/libs.versions.toml`; wire the dependency into `:mobile:app` (commonMain). Confirm the pin does not regress the existing Ktor version alignment.

## 2. Backend read-path image surfacing (no migration)

- [x] 2.1 Extract a **shared** server-side delivery-URL builder reused by both the upload path and the read path (one source of truth — no divergent re-derivation) that maps `image_id` → `<deliveryBaseUrl>/<accountHash>/<image_id>/public` (the exact 4-segment shape `CloudflareImageStore` emits), from the env-aware `deliveryBaseUrl` (`Application.kt`: `img.nearyou.id` prod / `img-staging.nearyou.id` staging) + secret `accountHash` + `public` variant; null-safe (null `image_id` → null URL). [`CloudflareImagesConfig.deliveryUrl(imageId)`; write path migrated to it; the read DTOs consume it in 2.2/2.3.]
- [x] 2.2 `TimelineRoutes.kt`: add `val imageUrl: String?` (bare camelCase) to `NearbyPostDto`, `FollowingPostDto`, `GlobalPostDto`; project `image_id` and map via the shared builder. **Two-arm UNION caveat (Nearby + Global have a self-arm):** these read repos UNION a visible arm (`FROM visible_posts` + block NOT-IN) with a viewer-scoped self-arm (`FROM posts ... WHERE author_id = :viewer`, carrying `@AllowRawPostsRead`) — project `image_id` in **BOTH** arms, else a shadow-banned/own author's image silently drops on their own self-arm read. (Following has no self-arm.)
- [x] 2.3 Single-post-read DTO + post-detail read DTO: add `imageUrl: String?`, project `image_id`, map via the shared builder. Single-post-read is also a two-arm UNION — project `image_id` in **both** the `visible_posts` arm and the self-arm (per 2.2).
- [x] 2.4 Confirm image-bearing rows still flow through the existing `visible_posts` + block-exclusion joins unchanged; the self-arm's `image_id` projection stays under its existing `@AllowRawPostsRead` annotation (no new un-annotated raw `FROM posts` read; lint clean).

## 3. Mobile read-path rendering (PostCard + detail)

- [x] 3.1 Extend the shared `PostCard` model with `imageUrl: String?`; render a Coil 3 `AsyncImage` below the content when non-null, with aspect-ratio placeholder + graceful failure (no error chrome) and no scroll preload (on-screen load only). No image element when null.
- [x] 3.2 Thread `imageUrl` from the timeline DTOs → card model in Nearby + Global (and Following) feed mapping.
- [x] 3.3 Add `imageUrl: String? = null` to `PostDetailRoute` (defaulted; registered in the polymorphic `SerializersModule`); render the image on `PostDetailScreen` below the content; thread `imageUrl` from the feed card tap into the route payload.

## 4. Mobile image picker + compression seam (§2.5)

- [ ] 4.1 commonMain `interface ImagePicker { suspend fun pick(): PickedImage? }` + `PickedImage(bytes, mime)` in `id/nearyou/app/image/`.
- [ ] 4.2 Android actual: `ActivityResultContracts.PickVisualMedia` + `Bitmap` re-encode loop to ≤5 MB; bind in the Android Koin platform module (no business logic in androidMain).
- [ ] 4.3 iOS actual: `PHPickerViewController` + ImageIO/`UIImage` downscale to ≤5 MB; bind in the iOS Koin platform module. Add explicit `import platform.<Framework>.<symbol>` for any ObjC category members (docs/11 §2.5 K/N caveat).

## 5. Mobile upload data layer (§2.6)

- [x] 5.1 `ImageUploadApiClient`: multipart `POST /api/v1/images` via `MultiPartFormDataContent` on the shared `HttpClient` (Auth plugin owns Bearer + refresh; no ad-hoc client); parse `201 {image_id, delivery_url}`; rethrow `CancellationException`.
- [x] 5.2 `ImageUploadRepository` + sealed `ImageUploadOutcome` (`Success`/`PremiumRequired`/`FeatureDisabled`/`QuotaExceeded`/`Throttled`/`ModerationRejected`/`TooLarge`/`Unavailable`/`Network` — all 9 members); map the exact HTTP status + `error.code` per `ImageRoutes.kt` (incl. `503 image_upload_unavailable` → `Unavailable`); exhaustive `when` (no `else`).
- [ ] 5.3 Koin wiring for `ImagePicker`-consumer, `ImageUploadApiClient`, `ImageUploadRepository`.

## 6. Mobile compose-with-image authoring (gating + submit + strings)

- [ ] 6.1 `PostCreationScreen`: render the Premium image-attach affordance (mockup frame 6 image button); Premium → `ImagePicker.pick()`, Free → shared cap-upsell/paywall (no picker). No new Remote Config client.
- [ ] 6.2 Upload-then-attach flow: in-progress state → thumbnail preview + remove affordance; disable "Posting" CTA while upload in flight; map each `ImageUploadOutcome` to a UI state; never submit a post referencing a failed/missing image.
- [ ] 6.3 `PostCreationApiClient` request DTO: add optional `@SerialName("image_id") val imageId: String? = null`; include only when attached; text-only body byte-identical to pre-change.
- [ ] 6.4 New Bahasa Indonesia strings in `:shared:resources` for the attach affordance, preview/remove, upload progress, each of the 9 `ImageUploadOutcome` states, and the **image `contentDescription`/alt-text** rendered in the card + detail (`Res.string.*`); no hardcoded literals.

## 7. Tests (Definition of Done, docs/11 §5)

- [ ] 7.1 Mobile commonTest: attach affordance Premium-gated (Premium opens picker; Free upsold, picker not invoked); `image_id` present in body only when attached; remove clears attachment; CTA disabled during upload; each `ImageUploadOutcome` → UI state; no hardcoded strings.
- [x] 7.2 Mobile commonTest: `PostCard` + `PostDetailScreen` render an image when `imageUrl` present and are unchanged when null (negative guard); `PostDetailRoute` decodes a pre-`imageUrl` payload with `imageUrl = null` (back-compat).
- [x] 7.3 `ImageUploadApiClient` + `ImageUploadRepository` MockEngine tests: multipart `POST /api/v1/images`; 201 parse (4-segment `<base>/<accountHash>/<image_id>/public` body); `CancellationException` rethrown; and a status→outcome table covering ALL mapped branches — `422 image_rejected`→`ModerationRejected`, `403 image_upload_disabled`→`FeatureDisabled` vs `403 premium_required`→`PremiumRequired`, `429 image_upload_throttled`→`Throttled` vs `429 image_upload_quota_exceeded`→`QuotaExceeded`, `413 image_too_large`→`TooLarge`, **`503 image_upload_unavailable`→`Unavailable`**, transport→`Network` (the spec's "every outcome is a declared sealed member" exhaustiveness scenario has an explicit test home here).
- [ ] 7.4 `ImagePicker` actual tests: cancelled selection → `null` (no upload attempted); a returned `PickedImage` satisfies the ≤5 MB size guard + `image/*` mime (exercise the Android `Bitmap` re-encode loop via Robolectric; iOS via the simulator test).
- [ ] 7.5 iOS: `:mobile:app:iosSimulatorArm64Test` (or at minimum `linkDebugFrameworkIosSimulatorArm64`) for the PHPicker/ImageIO actual.
- [ ] 7.6 Backend `:backend:ktor:test`: **all 5 read surfaces** — Nearby, Following, Global, single-post, and post-detail responses — carry `imageUrl` (built 4-segment URL) for an image post and `null` for a text-only post; an image-bearing post is excluded under shadow-ban/block exactly as a text-only post; a viewer reading their OWN shadow-banned image post via the self-arm still sees their `imageUrl` (two-arm UNION self-arm projection, per task 2.2).
- [ ] 7.7 Local gates green: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`.

## 8. Manual verification + staging (pre-archive)

- [ ] 8.1 verify-loop bring-up (UI-affecting; context-routed): compose a Premium image post, confirm upload→attach→render in feed + detail; screenshot evidence in the PR body (light + dark). Confirm Free-tier upsell path.
- [ ] 8.2 Staging branch deploy (`gh workflow run deploy-staging.yml --ref image-attached-posts`) + smoke the read-path `imageUrl` surfacing (runtime-impacting backend change, project.md § Staging deploy timing). **Seeding note:** the read-path lands before the mobile upload client (phases 2–3 vs 4–6), so verify it against a **manually-seeded** `image_uploads` ledger row + a `posts.image_id` reference (or a real Premium upload once the QA-variant client exists) — the backend unit tests in 7.6 seed the same way.

## 9. Deploy (prod — leave unchecked until prod infra/flag flip)

- [ ] 9.1 Confirm `image_upload_enabled` remains FALSE in production (feature stays gated until Month 6 launch rehearsal); dogfood in the QA variant only.
