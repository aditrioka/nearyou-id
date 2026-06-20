## Context

PR #325 (`premium-image-upload-pipeline`) shipped the full backend for premium image posts but no client uses it:

- `POST /api/v1/images` — multipart upload, `image_upload_enabled` flag gate, Premium gate, 50/day quota + 1/60s throttle, 5 MB streamed guard, `image/*` allowlist, Vision Safe-Search (adult/violence/racy `LIKELY`+ → 422), Cloudflare Images store, returns `201 {image_id, delivery_url}` (delivery via `https://img.nearyou.id/...`).
- `image_uploads` ownership ledger (V26): `cf_image_id` PK, `uploader_user_id`, `status ∈ {uploaded, attached}`. **No `delivery_url` column** — the URL is reconstructed from `cf_image_id`.
- `POST /api/v1/posts` already accepts `@SerialName("image_id")` and validates owned + unattached, flipping the ledger `uploaded → attached` in the same transaction (`CreatePostService.kt`).

Two specs deferred the rest with spec-visible guards: `mobile-post-creation` ("SHALL NOT render an attachment toolbar") and `premium-image-upload` ("Mobile UI and read-path image surfacing are deferred… tracked for the follow-on change `mobile-image-upload-ui`"). **This change is that follow-on** (renamed `image-attached-posts`, broadened to include rendering). It spans `:mobile:app` (authoring + rendering) and `:backend:ktor` (read-path surfacing).

**Standards conformance (docs/11 §4 Pattern Registry — anti-patchwork).** This change builds on existing registry patterns and introduces **no new pattern** for any listed concern:
- **§2.5 platform code** — the image picker + client-side compression is a commonMain `interface ImagePicker` with `androidMain`/`iosMain` actuals bound in Koin platform modules (not `expect class`; actuals hold only platform wiring).
- **§2.6 data layer** — `ImageUploadApiClient` + `ImageUploadRepository` with a sealed `ImageUploadOutcome`, on the single shared `HttpClient` (Auth bearer + single-flight refresh owned by the Auth plugin). ViewModel → Repository → ApiClient.
- **§2.2 state holder** + **§2.3 Navigation 3** — the composer is already a route; the attach affordance is local screen state.
- **mobile-post-card / mobile-post-detail** are MODIFY-by-extension of the canonical card + detail contracts (no parallel card).
The only new substrate is the image-loader dependency (D5) — a library pin, not a pattern fork. **No docs/11 amendment is required.**

## Goals / Non-Goals

**Goals:**
- A Premium user can attach one image to a new post from the composer: pick → client-compress (≤5 MB) → upload → preview/remove → post with `image_id`.
- Free users get a clear upsell at the attach affordance; the feature stays backend-authoritative (flag/premium/quota/moderation enforced server-side, mapped to graceful UI states).
- Attached images render in the Nearby/Following/Global feeds (shared `PostCard`) and on post detail, with cost-conscious delivery (no scroll preload, on-screen load only).
- Backend read responses surface the image delivery URL with **no schema migration** and no new shadow-ban/block invariant surface.

**Non-Goals:**
- CSAM admin-trigger workflow + `/internal/csam-webhook` (stays deferred in `premium-image-upload`).
- Delivery anomaly detection, rolling baselines, orphaned-upload cleanup, image cascade-delete on post/account deletion.
- More than one image per post; video/audio; client-side cropping/filters; a mobile Firebase Remote Config client; AdMob.

## Decisions

### D1 — Image picker + client-side compression seam (§2.5)
A commonMain `interface ImagePicker { suspend fun pick(): PickedImage? }` returning `PickedImage(bytes: ByteArray, mime: String)` already downscaled/recompressed to ≤5 MB. Android actual: `ActivityResultContracts.PickVisualMedia` (Android Photo Picker — no storage permission) + `Bitmap` re-encode (JPEG quality step-down loop until ≤5 MB). iOS actual: `PHPickerViewController` (no Photos permission for the modern picker) + `ImageIO`/`UIImage` downscale. Bound per-platform in Koin. Rationale: `expect class` is still Beta (docs/11 §2.5); an interface + actuals is the registry pattern and keeps `androidMain`/`iosMain` to platform wiring only. **K/N caveat:** ObjC category members need explicit `import platform.<Framework>.<symbol>` — run `linkDebugFrameworkIosSimulatorArm64` locally (Linux CI can't catch it).
- *Alternative considered:* a third-party KMP picker library — rejected (new substrate + vendor surface for a thin platform call we can own in ~2 actuals).

### D2 — Upload data layer (§2.6)
`ImageUploadApiClient.upload(bytes, mime): ImageUploadResult` issues multipart `POST /api/v1/images` via Ktor `MultiPartFormDataContent` on the shared `HttpClient` (never an ad-hoc client; Bearer + 401 refresh owned by the Auth plugin). `ImageUploadRepository` maps transport + `error.code` to a sealed `ImageUploadOutcome`: `Success(imageId, deliveryUrl)`, `PremiumRequired`, `FeatureDisabled` (flag off), `QuotaExceeded`, `Throttled`, `ModerationRejected` (HTTP 422), `TooLarge`, `Network`. `CancellationException` is rethrown, never mapped to `Network`. ViewModels talk to the repository only.
- *Alternative:* fold upload into `CreatePostRepository` — rejected; upload is a distinct capability with its own outcome taxonomy and is reused independently of post creation.

### D3 — Two-step submit + gating (mirror the shipped server-side flag pattern)
Compose flow: attach affordance → `ImagePicker.pick()` → compress → `ImageUploadRepository.upload()` (progress) → thumbnail preview + remove → on "Posting" pass `image_id` into `PostCreationApiClient`. The create request DTO gains an **optional** `@SerialName("image_id") val imageId: String? = null` (snake_case wire, matching the shipped `CreatePostRequestDto`; `content`/`latitude`/`longitude` stay bare). Gating mirrors the precedent set by search (`503 search_disabled`) and premium-username (`503 feature_disabled`): the attach affordance is Premium-gated **client-side** from already-known subscription status (Free → reuse the cap-upsell/paywall surface), and the backend `POST /api/v1/images` is the authority (`403` flag-off / non-premium, `422` moderation) which the client maps to graceful states. **No new mobile Remote Config client** — consistent with every other gated mobile feature.
- *Alternative:* a client-side `image_upload_enabled` Remote Config read to pre-hide the button — rejected; the project enforces flags server-side and reacts to the gated response. A `FeatureDisabled` outcome renders the same "not available yet" state without a new client dependency.

### D4 — Read-path image surfacing (backend, NO migration)
Add `imageUrl: String?` (bare camelCase wire — per the `TimelineRoutes.kt` mixed-case precedent; the spec snake_case examples are stale) to `NearbyPostDto`, `FollowingPostDto`, `GlobalPostDto`, the single-post-read DTO, and the post-detail payload. The read queries select `image_id` from `visible_posts` — **already projected** (V28 `visible_posts = SELECT p.* FROM posts p JOIN users u …`; `posts.image_id` exists since V4), so **no Flyway migration and no view recreate**. A server-side builder constructs the single-variant `<deliveryBaseUrl>/<image_id>/<variant>` URL reusing the **same env-aware `deliveryBaseUrl`** the shipped upload path already wires (`Application.kt`: `img.nearyou.id` prod / `img-staging.nearyou.id` staging) + the same Cloudflare-Images variant, keeping Cloudflare specifics off the client; `imageUrl` is null when `posts.image_id` is null. Because images ride `visible_posts`, they inherit the existing shadow-ban + block-exclusion joins — **no new invariant surface**. A Phase-1 task-0 runtime check confirms `visible_posts.image_id` is live before code relies on it (mirrors the external-data sanity-check convention).
- *Alternative:* join `image_uploads` for a stored delivery URL — rejected; the ledger has no URL column and the URL is deterministic from `image_id`, so a join is pure overhead.

### D5 — Image rendering substrate (NEW dependency)
Render with **Coil 3 (KMP)** `AsyncImage` in the shared `PostCard` + post detail. Propose-time re-check (verified 2026-06-20): Coil 3.5.x is the production-stable CMP async image loader with full iOS support and a **Ktor network backend** (reuses the project's existing Ktor stack — no new networking lib) — per coil-kt.github.io + the Coil 3.0 release notes. Pin in `gradle/libs.versions.toml`. Apply-time **MUST** re-run the dated library re-check (project.md § Pre-implementation library re-check) before the first feat commit. Delivery rules (docs/02 §6): no thumbnail preload during timeline scroll (load on on-screen render only), aspect-ratio placeholder, immutable cache headers (already server-set), graceful failure → placeholder (no error chrome).
- *Alternative:* Kamel — rejected; smaller ecosystem, Coil 3's Compose-team alignment + Ktor backend fit the stack better.

### D6 — PII / safety
Image bytes are never logged (logging never widened past `LogLevel.HEADERS`). The delivery URL is coordinate-independent (image path carries no location). The picker uses platform APIs (`PickVisualMedia`/`PHPickerViewController`), **not** a vendor SDK, so the actuals live in `androidMain`/`iosMain` without tripping the vendor-SDK-leakage scan (confirm in a task). Post-detail receives `imageUrl` via the route payload (D4) — a public URL, not PII — preserving the "no author UUID / no coordinate in nav args" rule.

## Risks / Trade-offs

- **iOS actual is the highest-risk surface** (PHPicker + ImageIO + K/N category imports) → mitigate with `iosSimulatorArm64Test`/`linkDebugFrameworkIosSimulatorArm64` locally + the docs/11 §2.5 import caveat called out in tasks.
- **Detail renders from nav args, not a re-fetch** (`mobile-post-detail` "By-id post fetch is deferred") → `imageUrl` must flow through the `PostDetailRoute` payload; a tap from a feed card already has it, so no extra fetch. Trade-off: a detail reached without a card-supplied `imageUrl` shows no image until by-id fetch ships (acceptable — same constraint the screen already has for other fields).
- **Coil 3 is new substrate** → apply-time dated re-check (MUST) + version-catalog pin; fail-soft rendering bounds blast radius.
- **`visible_posts.image_id` assumption** → task-0 runtime check; if a future migration ever enumerates the view's columns, this surfaces immediately rather than silently dropping images.
- **Gated feature (Month 6)** → shipping the client now matches the "infrastructure BUILT but flag-gated" roadmap posture; the affordance is invisible/inert in production until `image_upload_enabled` flips, and dogfoggable in the QA variant.

## Migration Plan

No DB migration. Ship order (tasks.md phases): (1) read-path surfacing backend + mobile rendering can land first and is independently testable against a seeded image post; (2) the picker/upload/authoring half; (3) wire-through + verify-loop bring-up. Rollback is the squash-merge revert; no schema or data state to unwind. Staging: the read-path change is runtime-impacting → pre-archive staging branch deploy + smoke per project.md § Staging deploy timing.

## Open Questions

- **Delivery variant name**: the exact Cloudflare Images variant slug for single-variant timeline delivery (`/public` vs a named `feed` variant) — confirm against the `premium-image-upload` delivery config at apply time; does not block the spec.
- **Compression target**: JPEG quality step-down vs a fixed max dimension for the ≤5 MB guarantee — an implementation tuning detail resolved in apply (both satisfy the 5 MB server guard).
