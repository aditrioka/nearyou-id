## Why

The premium image-upload **backend** shipped on 2026-06-17 (PR #325 `premium-image-upload-pipeline`, capability `premium-image-upload`) but is entirely **clientless** — `POST /api/v1/images` and the `image_id` post-attach path on `POST /api/v1/posts` have no caller, and no read surface renders an attached image. Two specs explicitly defer the rest with spec-visible negative guards (`mobile-post-creation` "SHALL NOT render an attachment toolbar", `premium-image-upload` "Mobile UI and read-path image surfacing are deferred"). This change completes the premium image-post feature end-to-end so the shipped pipeline becomes a usable, demoable revenue feature.

## What Changes

- **Mobile compose-with-image authoring** (`:mobile:app`): a Premium-gated image-attach affordance in the post composer that picks an image, compresses it client-side to ≤5 MB, uploads it via `POST /api/v1/images`, previews the result with a remove option, and on "Posting" attaches the returned `image_id` to `POST /api/v1/posts`. Free users see an upsell; the backend remains the authority on the `image_upload_enabled` flag, the Premium gate, the 50/day quota + 1/60s throttle, and Safe-Search moderation (client maps the gated responses to graceful UI states — no new mobile Remote Config client).
- **Backend read-path image surfacing** (`:backend:ktor`, **no migration**): the Nearby/Following/Global timeline, single-post, and post-detail read DTOs gain an `imageUrl: String?` built server-side from `posts.image_id`. The column is already projected by `visible_posts` (V28 `SELECT p.*`; `posts.image_id` exists since V4) so attached-image posts inherit the existing shadow-ban + block-exclusion safety with zero new invariant surface.
- **Mobile read-path rendering** (`:mobile:app`): the shared `PostCard` and the post-detail screen render the attached image when `imageUrl` is present, using an async image loader (Coil 3 KMP) with the docs/02 §6 delivery-optimization rules (no preload during scroll, on-screen load only, aspect-ratio placeholder, graceful failure). Posts without an image render unchanged.
- **New dependency**: an async image loader (Coil 3 KMP) is pinned in `gradle/libs.versions.toml` (the only new substrate; verified current at propose time, re-checked at apply time).

**Out of scope** (stays deferred — do not pull in): CSAM admin-trigger workflow + `/internal/csam-webhook` (deferred in `premium-image-upload`), delivery anomaly detection + rolling baseline, orphaned-upload cleanup worker, image cascade-delete on post hard-delete / account deletion, AdMob, and any second image per post (hard limit stays 1).

## Capabilities

### New Capabilities
- `mobile-image-attachment`: the mobile compose-with-image authoring surface — the image-picker + client-side compression seam, the `ImageUploadApiClient`/`ImageUploadRepository` data layer issuing multipart `POST /api/v1/images`, the sealed upload-outcome → UI state mapping, the Premium/flag gating UX, and the two-step submit that attaches `image_id` to post creation.

### Modified Capabilities
- `mobile-post-creation`: the "SHALL NOT render an attachment toolbar" requirement + its "No attachment toolbar is rendered" negative-guard scenario are replaced — the composer now renders a Premium image-attach affordance and threads an optional `image_id` into the create request.
- `premium-image-upload`: the "Mobile UI and read-path image surfacing are deferred" requirement is replaced — read-path image surfacing now ships (backend read DTOs carry `imageUrl`); the mobile-UI half is discharged by `mobile-image-attachment`. CSAM/anomaly/cleanup deferrals are untouched.
- `mobile-post-card`: the "media are separate deferred capabilities" line is replaced — the shared card renders an attached image (lazy-loaded) when present, unchanged when absent.
- `mobile-post-detail`: the post-detail screen renders the attached image when present.

## Impact

- **Code (mobile)**: new `screens`/`ui` + `infra`-style picker interface (commonMain) with Android (`PickVisualMedia` + Bitmap recompress) and iOS (`PHPickerViewController` + ImageIO) actuals bound in Koin platform modules; new `ImageUploadApiClient`/`ImageUploadRepository`; edits to `PostCreationScreen` + `PostCreationApiClient` (optional `image_id` field); edits to the shared `PostCard` + post-detail composables for rendering; new Bahasa strings via `:shared:resources`.
- **Code (backend)**: `imageUrl` added to `NearbyPostDto`/`FollowingPostDto`/`GlobalPostDto` (`TimelineRoutes.kt`) + the single-post + post-detail DTOs and their `visible_posts` SELECTs; a server-side delivery-URL builder reusing the `premium-image-upload` `img.nearyou.id` pattern. **No Flyway migration** (column already projected).
- **Dependencies**: Coil 3 KMP (+ its Ktor network backend, reusing the existing Ktor stack) added to the version catalog.
- **Mockups**: composer frame 6 (image/camera buttons already drawn), post-card frames 1 + 19, post-detail frame — consult + measurement annex per docs/11 §2.8 before building.
- **No change** to the backend write path (`POST /api/v1/images`, `image_uploads` ledger, `POST /api/v1/posts` attach), the `image_upload_enabled` flag semantics, or any DB schema.
