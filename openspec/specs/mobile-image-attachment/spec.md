# mobile-image-attachment Specification

## Purpose
The mobile compose-with-image authoring surface in `:mobile:app` — the client half of the premium image-upload pipeline (whose backend shipped in `premium-image-upload`). It defines a vendor-SDK-free `ImagePicker` seam (commonMain `interface` + Android `PickVisualMedia` / iOS `PHPicker` actuals, per docs/11 §2.5) that returns an image client-side-compressed to ≤ 5 MB, the `ImageUploadApiClient` / `ImageUploadRepository` data layer (multipart `POST /api/v1/images` on the shared `HttpClient`, with a sealed 9-member `ImageUploadOutcome` mapping the shipped `ImageRoutes.kt` status + `error.code`), and the Premium-gated attach affordance in `PostCreationScreen` (Free → paywall, no picker) with the two-step pick → upload → preview/remove → attach-`image_id`-on-post submit flow. It composes the existing data-layer (§2.6) and state-holder (§2.2) patterns — no new Pattern-Registry entry. Read-path rendering of the attached image lives in `mobile-post-card` / `mobile-post-detail`; backend `imageUrl` surfacing lives in `premium-image-upload`.
## Requirements
### Requirement: A commonMain ImagePicker seam returns a compressed image with per-platform actuals

The mobile app SHALL define a commonMain interface `ImagePicker` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/image/ImagePicker.kt`) exposing a single `suspend fun pick(): PickedImage?` that returns a `PickedImage(bytes: ByteArray, mime: String)` already downscaled and recompressed client-side to **≤ 5 MB**, or `null` when the user cancels. `ImagePicker` SHALL be an `interface` (NOT `expect class`, per docs/11 §2.5) with an Android actual (`ActivityResultContracts.PickVisualMedia` + `Bitmap` re-encode) in `androidMain` and an iOS actual (`PHPickerViewController` + ImageIO/`UIImage` downscale) in `iosMain`, each bound in its Koin platform module. The `androidMain`/`iosMain` sources SHALL hold ONLY the platform actual + wiring (no business logic). The image content type returned SHALL be an `image/*` MIME accepted by the backend allowlist.

#### Scenario: Picker interface lives in commonMain with platform actuals bound in Koin

- **WHEN** inspecting `mobile/app/src/commonMain/kotlin/id/nearyou/app/image/ImagePicker.kt` and the Android + iOS Koin platform modules
- **THEN** `ImagePicker` is declared as a commonMain `interface` AND a concrete implementation is bound per platform (Android + iOS) AND no `expect class ImagePicker` exists

#### Scenario: Cancelled selection yields null

- **WHEN** the user dismisses the platform picker without choosing an image
- **THEN** `pick()` returns `null` AND no upload is attempted

#### Scenario: Returned image is within the size guard

- **WHEN** `pick()` returns a non-null `PickedImage`
- **THEN** `bytes.size` is ≤ 5 MB (the client compresses before returning, so the server-side 5 MB guard is never the first line of defense) AND `mime` is an `image/*` type

### Requirement: ImageUploadApiClient issues multipart POST /api/v1/images on the shared HttpClient

The mobile app SHALL ship `ImageUploadApiClient` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/image/ImageUploadApiClient.kt`) that uploads a `PickedImage` via `POST /api/v1/images` using Ktor `MultiPartFormDataContent` (the canonical premium-image-upload endpoint). The Bearer `Authorization` header and 401 refresh SHALL be provided by the shared `HttpClient` `Auth` plugin — this client MUST NOT reimplement token attachment or refresh, and MUST NOT construct an ad-hoc `HttpClient`. The client SHALL NOT swallow `CancellationException` (it MUST rethrow it; transport failures map to a network result). The success body SHALL parse into a minimal `@Serializable` DTO carrying at least the image id and delivery URL (relying on the shared `Json` `ignoreUnknownKeys = true`).

#### Scenario: Request shape and method

- **GIVEN** a Ktor `MockEngine` capturing outbound requests
- **WHEN** `ImageUploadApiClient.upload(bytes, mime = "image/jpeg")` runs
- **THEN** the captured request is `POST` with path `/api/v1/images` AND its body is multipart form-data carrying the image bytes

#### Scenario: 201 parses image id and delivery URL

- **GIVEN** a `MockEngine` returning `201 {"image_id":"abc","delivery_url":"https://img.nearyou.id/acct123/abc/public"}` (the real 4-segment `<base>/<accountHash>/<image_id>/public` shape the server emits)
- **WHEN** `upload(...)` runs
- **THEN** the result carries `image_id = "abc"` AND `delivery_url = "https://img.nearyou.id/acct123/abc/public"` (the client treats the delivery URL as opaque — it never reconstructs the path)

#### Scenario: CancellationException is rethrown, not swallowed

- **GIVEN** an `upload(...)` call whose coroutine is cancelled mid-flight
- **WHEN** the client's catch handling runs
- **THEN** the `CancellationException` is rethrown (structured concurrency unwinds) and is NOT mapped to a network result

### Requirement: ImageUploadRepository maps every backend outcome to a sealed ImageUploadOutcome

The mobile app SHALL ship `ImageUploadRepository` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/image/ImageUploadRepository.kt`) exposing a sealed `ImageUploadOutcome` with exactly these members and NO generic fallthrough: `Success(imageId: String, deliveryUrl: String)`, `PremiumRequired`, `FeatureDisabled`, `QuotaExceeded`, `Throttled`, `ModerationRejected`, `TooLarge`, `Unavailable`, `Network`. The repository SHALL map the shipped backend's exact HTTP status + `error.code` envelope (`ImageRoutes.kt`) to these members:

- `403` + `error.code = "image_upload_disabled"` → `FeatureDisabled`
- `403` + `error.code = "premium_required"` → `PremiumRequired`
- `429` + `error.code = "image_upload_throttled"` → `Throttled`
- `429` + `error.code = "image_upload_quota_exceeded"` → `QuotaExceeded`
- `413` + `error.code = "image_too_large"` → `TooLarge`
- `422` + `error.code = "image_rejected"` → `ModerationRejected`
- `503` + `error.code = "image_upload_unavailable"` → `Unavailable`
- transport failure → `Network`

Note the two `403`s and the two `429`s are disambiguated by `error.code`, not status alone. Any other unexpected non-2xx (e.g. the client-prevented `415 unsupported_image_type` / `400 no_image`, which the picker's `image/*` + file-part guarantees make unreachable in correct operation) SHALL map to `Unavailable` (a safe, retryable terminal state) rather than a silent success. ViewModels SHALL consume the repository, never the ApiClient directly.

#### Scenario: Moderation rejection maps to ModerationRejected

- **GIVEN** the upload endpoint returns HTTP 422 with the Safe-Search rejection envelope
- **WHEN** `ImageUploadRepository.upload(...)` runs
- **THEN** the outcome is `ImageUploadOutcome.ModerationRejected` (no exception crosses into the ViewModel)

#### Scenario: Feature-disabled and premium-required are distinct outcomes

- **WHEN** the endpoint returns `403 image_upload_disabled` versus `403 premium_required`
- **THEN** the first maps to `FeatureDisabled` AND the second maps to `PremiumRequired` (the client renders distinct copy for "not available yet" versus "Premium only"), disambiguated by `error.code` not status

#### Scenario: Throttle and quota are distinct outcomes on the same status

- **WHEN** the endpoint returns `429 image_upload_throttled` versus `429 image_upload_quota_exceeded`
- **THEN** the first maps to `Throttled` AND the second maps to `QuotaExceeded` (both `429`, disambiguated by `error.code`)

#### Scenario: Service-unavailable maps to a retryable terminal state

- **WHEN** the endpoint returns `503 image_upload_unavailable` (Vision or Cloudflare Images unconfigured/down)
- **THEN** the outcome is `ImageUploadOutcome.Unavailable` (no exception crosses into the ViewModel; the client shows a "try again later" state)

#### Scenario: Every outcome is a declared sealed member

- **WHEN** inspecting `ImageUploadOutcome`
- **THEN** it is a `sealed` type whose members are exactly `Success`, `PremiumRequired`, `FeatureDisabled`, `QuotaExceeded`, `Throttled`, `ModerationRejected`, `TooLarge`, `Unavailable`, `Network` (a `when` over it is exhaustive without an `else`)

### Requirement: The composer image-attach affordance is Premium-gated with a Free upsell

`PostCreationScreen` SHALL render an image-attach affordance whose enabled/visible behavior is driven by the viewer's already-known subscription status: a Premium viewer sees an active attach affordance; a Free viewer sees the affordance route to the shared cap-upsell/paywall surface (the `mobile-cap-upsell-dialog` / paywall pattern) rather than opening the picker. No new Firebase Remote Config client SHALL be introduced; the backend remains the authority on the `image_upload_enabled` flag (a `FeatureDisabled` outcome renders a "not available yet" state). Every UI string SHALL be sourced via `:shared:resources` `Res.string.*`.

#### Scenario: Premium viewer opens the picker

- **WHEN** a Premium viewer activates the attach affordance
- **THEN** the platform image picker is invoked (`ImagePicker.pick()`)

#### Scenario: Free viewer is upsold, not given the picker

- **WHEN** a Free viewer activates the attach affordance
- **THEN** the shared cap-upsell/paywall surface is shown AND `ImagePicker.pick()` is NOT invoked

#### Scenario: No hardcoded UI strings in the attach surface

- **WHEN** inspecting the image-attach composable source
- **THEN** every UI-string-bearing call site sources its text via `stringResource(Res.string.<name>)`; zero literal string arguments appear

### Requirement: Two-step submit uploads then attaches image_id to post creation

When an image is attached, the composer SHALL perform a two-step submit: first upload via `ImageUploadRepository` (showing in-progress, then a thumbnail preview with a remove affordance), and on "Posting" pass the returned `imageId` into the create-post request. While an upload is in progress, the "Posting" CTA SHALL be disabled. A failed upload SHALL surface the mapped `ImageUploadOutcome` state and SHALL NOT submit a post referencing a missing image. Removing the previewed image SHALL clear the attached `imageId` so the subsequent post is text-only. The image bytes SHALL NOT be logged.

#### Scenario: Successful image post attaches the uploaded id

- **GIVEN** a Premium viewer who picked an image that uploaded successfully (`Success(imageId = "abc", …)`)
- **WHEN** they activate "Posting"
- **THEN** the create-post request carries `image_id = "abc"`

#### Scenario: Posting is blocked while the upload is in flight

- **WHEN** an upload is in progress
- **THEN** the "Posting" CTA is disabled until the upload resolves to a terminal `ImageUploadOutcome`

#### Scenario: Removing the preview clears the attachment

- **GIVEN** an uploaded image is previewed in the composer
- **WHEN** the viewer activates the remove affordance and then posts
- **THEN** the create-post request carries no `image_id` (the post is text-only)

#### Scenario: A failed upload does not post a dangling image reference

- **GIVEN** an upload that resolved to `ModerationRejected` (or any non-`Success` outcome)
- **WHEN** the viewer is on the composer
- **THEN** the error state is shown AND no create-post request carrying that image is issued

