# premium-image-upload Specification

## Purpose
The backend pipeline for a Premium user to upload a moderated image and attach it to a post — the keystone premium content capability of the revenue loop (`docs/02-Product.md` §6; Phase 4 #16). It defines `POST /api/v1/images`: the `image_upload_enabled` flag gate (Redis-cached short-TTL, fail-closed), the Premium entitlement gate, the Redis daily-quota + per-minute throttle (consumed at attempt), upfront Google Cloud Vision Safe Search moderation, server-side Cloudflare Images storage on the `img` subdomain, and the `image_uploads` ownership ledger that binds each stored image to its uploader for post-attach authorization. The feature ships dark behind the default-FALSE flag and fail-soft when Cloudflare Images / Vision are unprovisioned, until the Month-6 launch flip. The CSAM reporting/preservation subsystem, the mobile upload UI, and read-path image surfacing are deferred to follow-on changes.
## Requirements
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
- **THEN** the system returns 403 (premium required) and performs no Vision call or Cloudflare upload

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

The endpoint SHALL run Google Cloud Vision Safe Search on the uploaded bytes synchronously, before storing the image. The system SHALL reject (HTTP 422, image never stored, no `image_uploads` row) when the `adult` OR `violence` OR `racy` likelihood is `LIKELY` or `VERY_LIKELY` — matching the canonical reject set (docs/02 §6 Image Upload Flow + docs/06 "explicit content upfront"). The `Likelihood` enum is categorical (`UNKNOWN, VERY_UNLIKELY, UNLIKELY, POSSIBLE, LIKELY, VERY_LIKELY`); `POSSIBLE` and lower do NOT reject. The rejection response SHALL NOT leak the per-category likelihoods to the client. (A future post-launch relaxation of the `racy` category to log-not-block is a tunable Open Question, NOT shipped in this change.)

#### Scenario: Adult-likely image is rejected and not stored
- **WHEN** Safe Search returns `adult = VERY_LIKELY` for the uploaded bytes
- **THEN** the system returns 422, performs no Cloudflare upload, writes no `image_uploads` row, and the response body does not include the category likelihoods

#### Scenario: Violence-likely image is rejected
- **WHEN** Safe Search returns `violence = LIKELY`
- **THEN** the system returns 422 and the image is not stored

#### Scenario: Racy-likely image is rejected
- **WHEN** Safe Search returns `racy = VERY_LIKELY` with `adult` and `violence` both `UNLIKELY`
- **THEN** the system returns 422 and the image is not stored

#### Scenario: Below-threshold image passes
- **WHEN** Safe Search returns every category at `POSSIBLE` or lower (e.g. `adult = POSSIBLE`, `violence = UNLIKELY`, `racy = POSSIBLE`)
- **THEN** moderation passes and the upload proceeds to Cloudflare storage

### Requirement: Server-side input guards — 5 MB streamed size limit and image content-type allowlist

The endpoint SHALL guard the upload before the Vision call and Cloudflare upload:
- **Size:** reject (HTTP 413) any payload exceeding 5 MB. The limit SHALL be enforced as a **streamed** byte-count that aborts once the threshold is crossed — the endpoint MUST NOT fully buffer an arbitrarily large part before rejecting (multipart-bomb / OOM guard).
- **Content type:** reject (HTTP 415) any part whose declared content type is not an allowed image type (`image/*` allowlist — e.g. jpeg/png/webp); arbitrary bytes SHALL NOT be forwarded to Vision or Cloudflare.

Client-side compression is out of scope (deferred to the mobile UI).

#### Scenario: Oversized upload rejected without full buffering
- **WHEN** the multipart image payload exceeds 5 MB
- **THEN** the system returns 413, aborts once the streamed byte count crosses 5 MB (no full-payload buffering), and performs no Vision call or Cloudflare upload

#### Scenario: Exactly 5 MB accepted
- **WHEN** the multipart image payload is exactly 5 MB and otherwise valid
- **THEN** the size guard passes and the request proceeds to Safe Search

#### Scenario: Non-image content type rejected
- **WHEN** the uploaded part declares a non-image content type (e.g. `application/pdf`)
- **THEN** the system returns 415 and performs no Vision call or Cloudflare upload

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

#### Scenario: Unconfigured Vision returns 503, not 500
- **WHEN** the flag is TRUE and the user is Premium and within quota but Google Cloud Vision is unconfigured
- **THEN** the endpoint returns 503 feature-unavailable and the application does not crash

#### Scenario: Unconfigured Cloudflare Images returns 503, not 500
- **WHEN** the flag is TRUE and the user is Premium and the image passes Safe Search but Cloudflare Images is unconfigured
- **THEN** the endpoint returns 503 feature-unavailable, writes no `image_uploads` row, and the application does not crash

### Requirement: Vendor SDKs isolated to :infra:* and secrets via helper

The Cloudflare Images and Google Cloud Vision vendor SDKs/HTTP clients SHALL live only inside `:infra:cloudflare-images` and `:infra:cloud-vision`; `:backend:ktor` SHALL depend only on their interfaces. All credentials SHALL be read via `secretKey(env, name)`; direct secret-name reads are forbidden.

#### Scenario: Static analysis confirms isolation
- **WHEN** the codebase is scanned
- **THEN** no Cloudflare/Vision SDK or HTTP-client import appears outside `:infra:cloudflare-images` / `:infra:cloud-vision`, and no credential is read other than via `secretKey(env, name)`

### Requirement: CSAM subsystem is deferred (not shipped in this change), but served-image CSAM coverage is a launch precondition

This change SHALL NOT implement the CSAM application subsystem. There SHALL be no `/internal/csam-webhook` handler, no `csam_detection_archive` table or migration, and no admin CSAM review queue introduced by this change. CSAM reporting/preservation is tracked for the follow-on change `csam-detection-webhook-and-archive` (admin-triggered MVP per resolved Open Decision #33).

CSAM **detection + blocking** is NOT part of the deferred subsystem: it is zone-level (the Cloudflare CSAM Scanning Tool on the `nearyou.id` zone auto-scans cached images and returns HTTP 451 on a match — docs/06), independent of any backend code. Therefore the safety property "served images are CSAM-scanned" depends on the CF CSAM Scanning Tool being enabled on the zone BEFORE `image_upload_enabled` is flipped TRUE. That ordering SHALL be named as an explicit launch precondition in the Migration Plan so a flag-flip cannot precede zone CSAM coverage.

#### Scenario: No CSAM application artifacts introduced
- **WHEN** this change is archived
- **THEN** no `/internal/csam-webhook` route, `csam_detection_archive` migration, or admin CSAM-queue surface exists in the diff

#### Scenario: Zone-CSAM ordering precondition is documented
- **WHEN** the change's Migration Plan launch checklist is read
- **THEN** it names "enable the Cloudflare CSAM Scanning Tool on the zone" as a precondition that MUST precede flipping `image_upload_enabled` TRUE

### Requirement: Delivery optimization, anomaly detection, and orphan cleanup are deferred

This change SHALL NOT implement delivery-cost optimization (`srcset` single-variant, lazy-load), per-user delivery anomaly detection (>5× baseline), the `:infra:r2` module, or a cleanup job for orphaned (uploaded-but-never-attached) images. Orphan cleanup is enabled-but-not-implemented: the `image_uploads.status` field makes it a pure-additive follow-up.

#### Scenario: Orphaned upload is retained
- **WHEN** an image is uploaded but never attached to a post
- **THEN** its `image_uploads` row remains with `status = 'uploaded'` and is not auto-deleted by this change (cleanup is a tracked follow-up)

### Requirement: Post read responses surface the attached image delivery URL

Post read responses SHALL surface the delivery URL of an attached image. The single-post-read response and the three timeline responses (Nearby, Following, Global) SHALL each carry an `imageUrl` field that is the single-variant delivery URL (`<deliveryBaseUrl>/<accountHash>/<image_id>/public` — the exact 4-segment shape the shipped upload path emits, `CloudflareImageStore.kt`) when the post has an attached image, and `null`/absent when it does not. The read path SHALL reuse the **same delivery-URL builder** the upload path uses (one source of truth — extract a shared pure builder rather than re-deriving a divergent string) so the env-aware `deliveryBaseUrl` (`img.nearyou.id` prod / `img-staging.nearyou.id` staging — `Application.kt`), the secret-sourced `accountHash` (non-sensitive per `ImageStore`), and the `public` variant cannot drift between write and read. Cloudflare-specific URL structure SHALL NOT be reconstructed on the client. `image_id` SHALL be read from `visible_posts` (already projected as part of `SELECT p.*`), so this requirement adds **no Flyway migration and no new shadow-ban or block-exclusion surface** — image-bearing posts are filtered by the same `visible_posts` joins as every other read. The corresponding mobile compose-with-image UI is delivered by the `mobile-image-attachment` capability and the rendering by `mobile-post-card` / `mobile-post-detail`; this requirement discharges the read-path half of the prior deferral (the `image-attached-posts` change supersedes the follow-on previously tracked under the name `mobile-image-upload-ui`). (CSAM admin-trigger, delivery anomaly detection, and orphaned-upload cleanup remain deferred per their own requirements in this capability.)

#### Scenario: Read response carries the delivery URL for an image post

- **WHEN** a post with an attached image (`posts.image_id` non-null) is read via `GET /api/v1/posts/{id}` or surfaced in any of the Nearby/Following/Global timelines
- **THEN** the response item's `imageUrl` is the `<deliveryBaseUrl>/<accountHash>/<image_id>/public` delivery URL for that `image_id` (the same 4-segment shape + shared builder the upload path emits)

#### Scenario: Text-only post carries no image URL

- **WHEN** a post with no attached image (`posts.image_id` null) is read via any post-read or timeline endpoint
- **THEN** the response item's `imageUrl` is `null`/absent (the read shape is otherwise unchanged from the pre-image baseline)

#### Scenario: Image posts respect shadow-ban and block exclusion

- **GIVEN** an image-bearing post whose author is shadow-banned to the viewer, or blocked, or whose post is auto-hidden
- **WHEN** the viewer reads any timeline or the single post
- **THEN** the post is excluded exactly as a text-only post would be (the `imageUrl` surfacing rides `visible_posts` + the existing block-exclusion joins, adding no leak surface)

#### Scenario: No migration is introduced for surfacing

- **WHEN** inspecting the change's `tasks.md` and migration directory
- **THEN** no new `V<N>__*.sql` is added for read-path image surfacing (`visible_posts` already projects `image_id` via `SELECT p.*`; a task-0 runtime check confirms the column is live before the read queries rely on it)

