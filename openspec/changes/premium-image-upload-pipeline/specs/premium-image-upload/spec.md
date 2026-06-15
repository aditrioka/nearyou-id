## ADDED Requirements

### Requirement: POST /api/v1/images upload endpoint

The system SHALL expose `POST /api/v1/images` accepting a single multipart image file. On success it SHALL store the image in Cloudflare Images, record an ownership ledger row, and return HTTP 201 with a JSON body containing the Cloudflare `image_id` and the `img` subdomain `delivery_url`. The endpoint SHALL require a valid authenticated JWT (same auth boundary as other `/api/v1/*` routes — a suspended/banned user is rejected at the auth boundary).

#### Scenario: Premium user uploads a clean image with the flag on
- **WHEN** an authenticated `premium_active` user POSTs a ≤5 MB image that passes Safe Search, the `image_upload_enabled` flag is TRUE, and Cloudflare Images + Vision are configured, and the user is within quota and throttle
- **THEN** the system stores the image in Cloudflare Images, inserts an `image_uploads` row binding the `image_id` to the user, and returns 201 with `{ image_id, delivery_url }` where `delivery_url` is on `img-staging.nearyou.id` (staging) / `img.nearyou.id` (prod)

#### Scenario: Missing JWT
- **WHEN** the request carries no valid JWT
- **THEN** the system returns 401 and performs no upload, Vision call, or ledger write

### Requirement: image_upload_enabled flag gate (default FALSE, Redis-cached short TTL, fail-closed)

The endpoint SHALL be gated by the `image_upload_enabled` Remote Config flag. The flag value SHALL be read through a Redis-cached flag read with a per-flag TTL of 30 seconds (the docs/11 §3.3 short-TTL emergency-kill-switch override, not the 5-minute default). The flag SHALL default to FALSE and SHALL fail closed: when the flag is unset, malformed, or unreadable (Redis or SDK error), the effective value is FALSE. When the effective value is FALSE the endpoint SHALL return 403 before any premium check, Vision call, or Cloudflare upload.

#### Scenario: Flag off rejects upload
- **WHEN** `image_upload_enabled` resolves to FALSE
- **THEN** the system returns 403 and performs no quota consumption, Vision call, or Cloudflare upload

#### Scenario: Flag read fails closed on error
- **WHEN** the Redis cache and the underlying Remote Config read both error or return null
- **THEN** the effective flag value is FALSE and the endpoint returns 403

#### Scenario: Flag flip propagates within the short TTL
- **WHEN** an operator flips `image_upload_enabled` from TRUE to FALSE
- **THEN** the cached read reflects FALSE within 30 seconds (the per-flag TTL), without a deploy

### Requirement: Premium-only entitlement (Free tier rejected)

The endpoint SHALL be available only to users whose `subscription_status` is in `{premium_active, premium_billing_retry}` (grace-period users retain access, matching the post-creation entitlement formula). Free-tier users SHALL be rejected and SHALL consume no upload quota.

#### Scenario: Free user rejected
- **WHEN** an authenticated user with `subscription_status = 'free'` POSTs an image with the flag on
- **THEN** the system returns a premium-required rejection and performs no Vision call or Cloudflare upload

#### Scenario: Grace-period user allowed
- **WHEN** an authenticated user with `subscription_status = 'premium_billing_retry'` POSTs a valid image with the flag on
- **THEN** the upload proceeds (the user is treated as Premium)

### Requirement: Daily quota and per-minute throttle, consumed at attempt

The endpoint SHALL enforce a daily quota of 50 uploads per user and a throttle of 1 upload per 60 seconds per user, using the shared Redis rate-limit infrastructure with `computeTTLToNextReset(user_id)` (per-user WIB-midnight stagger) and `{scope:…}` hash-tagged keys. Both limiters SHALL be consumed at attempt — before Safe Search and Cloudflare upload — so that moderation-rejected attempts still count (bounding per-image Vision/Cloudflare cost against abuse). The daily quota SHALL be overridable via the `premium_image_upload_cap_override` Remote Config flag (default 50), mirroring the `premium_like_cap_override` precedent. A Free-tier user's effective cap is 0 (enforced by the premium gate, before the limiter).

#### Scenario: 51st upload in the same WIB day is rejected
- **WHEN** a Premium user has consumed 50 upload slots in the current WIB day and attempts a 51st
- **THEN** the system returns 429 with a TTL-to-next-reset computed via `computeTTLToNextReset(user_id)`, and performs no Vision call or Cloudflare upload

#### Scenario: Second upload within 60 seconds is throttled
- **WHEN** a Premium user uploads successfully and attempts another upload within 60 seconds
- **THEN** the system returns 429 (throttled) and performs no Vision call or Cloudflare upload

#### Scenario: Moderation-rejected attempt still consumes quota and throttle
- **WHEN** a Premium user's upload is rejected by Safe Search
- **THEN** the daily quota and the 60-second throttle have both been consumed for that attempt

#### Scenario: Cap override flag adjusts the daily quota
- **WHEN** `premium_image_upload_cap_override` is set to a value N
- **THEN** the daily quota enforced is N (default 50 when unset)

### Requirement: Upfront Safe Search moderation rejects explicit content

The endpoint SHALL run Google Cloud Vision Safe Search on the uploaded bytes synchronously, before storing the image. The system SHALL reject (HTTP 422, image never stored, no `image_uploads` row) when the `adult` OR `violence` likelihood is `LIKELY` or `VERY_LIKELY`. The `racy` category SHALL be logged but SHALL NOT by itself cause rejection in this change. The rejection response SHALL NOT leak the per-category likelihoods to the client.

#### Scenario: Adult-likely image is rejected and not stored
- **WHEN** Safe Search returns `adult = VERY_LIKELY` for the uploaded bytes
- **THEN** the system returns 422, performs no Cloudflare upload, writes no `image_uploads` row, and the response body does not include the category likelihoods

#### Scenario: Violence-likely image is rejected
- **WHEN** Safe Search returns `violence = LIKELY`
- **THEN** the system returns 422 and the image is not stored

#### Scenario: Clean image passes
- **WHEN** Safe Search returns all categories at `POSSIBLE` or lower (including a high `racy` but low `adult`/`violence`)
- **THEN** moderation passes and the upload proceeds to Cloudflare storage

### Requirement: Server-side file size guard — 5 MB maximum

The endpoint SHALL reject any upload whose payload exceeds 5 MB, server-side, before the Vision call and Cloudflare upload. Client-side compression is out of scope (deferred to the mobile UI).

#### Scenario: Oversized upload rejected before moderation
- **WHEN** the multipart image payload exceeds 5 MB
- **THEN** the system returns 413 and performs no Vision call or Cloudflare upload

### Requirement: Cloudflare Images server-side storage and delivery URL

The system SHALL upload the moderated bytes to Cloudflare Images via server-side upload (the backend holds the bytes — Direct Creator Upload is NOT used, because it would bypass upfront moderation). The returned delivery URL SHALL be served from the `img` custom subdomain (`img-staging.nearyou.id` / `img.nearyou.id`) per resolved Open Decision #32. The Cloudflare API token and account hash SHALL be read via the `secretKey(env, name)` helper.

#### Scenario: Stored image is addressable via the img subdomain
- **WHEN** an image is successfully stored in Cloudflare Images
- **THEN** the returned `delivery_url` is rooted at the `img` custom subdomain, not `imagedelivery.net` (the documented emergency fallback only)

### Requirement: image_uploads ownership ledger

A new `image_uploads` table SHALL record one row per successfully stored image: the Cloudflare `image_id` (primary key), the uploader's `user_id` (FK), `created_at`, the Safe Search verdict, and a `status` in `{uploaded, attached}`. The ledger is the authorization source for attaching an image to a post and the future linkage point for CSAM handling. A successful upload SHALL insert a row with `status = 'uploaded'`.

#### Scenario: Successful upload writes an uploaded ledger row
- **WHEN** an image is stored in Cloudflare Images
- **THEN** an `image_uploads` row exists with that `image_id`, `uploader_user_id = caller`, and `status = 'uploaded'`

### Requirement: Fail-soft when Cloudflare Images or Vision is unconfigured

The `:infra:cloudflare-images` and `:infra:cloud-vision` modules SHALL expose configuration state and SHALL behave as fail-soft NoOps when their secrets are absent (the FCM / RevenueCat precedent). When either dependency is unconfigured, the endpoint SHALL return 503 (feature unavailable) — never 500 — so an unprovisioned environment boots and serves all other routes.

#### Scenario: Unconfigured infra returns 503, not 500
- **WHEN** the flag is TRUE and the user is Premium but Cloudflare Images (or Vision) is unconfigured
- **THEN** the endpoint returns 503 feature-unavailable and the application does not crash

### Requirement: Vendor SDKs isolated to :infra:* and secrets via helper

The Cloudflare Images and Google Cloud Vision vendor SDKs/HTTP clients SHALL live only inside `:infra:cloudflare-images` and `:infra:cloud-vision`; `:backend:ktor` SHALL depend only on their interfaces. All credentials SHALL be read via `secretKey(env, name)`; direct secret-name reads are forbidden.

#### Scenario: Static analysis confirms isolation
- **WHEN** the codebase is scanned
- **THEN** no Cloudflare/Vision SDK or HTTP-client import appears outside `:infra:cloudflare-images` / `:infra:cloud-vision`, and no credential is read other than via `secretKey(env, name)`

### Requirement: CSAM subsystem is deferred (not shipped in this change)

This change SHALL NOT implement the CSAM subsystem. There SHALL be no `/internal/csam-webhook` handler, no `csam_detection_archive` table or migration, and no admin CSAM review queue introduced by this change. CSAM handling is tracked for the follow-on change `csam-detection-webhook-and-archive` (admin-triggered MVP per resolved Open Decision #33).

#### Scenario: No CSAM artifacts introduced
- **WHEN** this change is archived
- **THEN** no `/internal/csam-webhook` route, `csam_detection_archive` migration, or admin CSAM-queue surface exists in the diff

### Requirement: Mobile UI and read-path image surfacing are deferred (not shipped in this change)

This change SHALL NOT add a mobile upload UI, client-side image compression, or read-path image surfacing. Read DTOs (single-post-read, the three timelines) SHALL be unchanged — an attached image is persisted but not yet returned on read. These are tracked for the follow-on change `mobile-image-upload-ui`, which will MODIFY the read specs to surface the delivery URL alongside the rendering UI.

#### Scenario: Read responses unchanged
- **WHEN** a post with an attached image is read via `GET /api/v1/posts/{id}` or any timeline
- **THEN** the response shape is unchanged by this change (no image field added on the read path)

### Requirement: Delivery optimization, anomaly detection, and orphan cleanup are deferred

This change SHALL NOT implement delivery-cost optimization (`srcset` single-variant, lazy-load), per-user delivery anomaly detection (>5× baseline), the `:infra:r2` module, or a cleanup job for orphaned (uploaded-but-never-attached) images. Orphan cleanup is enabled-but-not-implemented: the `image_uploads.status` field makes it a pure-additive follow-up.

#### Scenario: Orphaned upload is retained
- **WHEN** an image is uploaded but never attached to a post
- **THEN** its `image_uploads` row remains with `status = 'uploaded'` and is not auto-deleted by this change (cleanup is a tracked follow-up)
