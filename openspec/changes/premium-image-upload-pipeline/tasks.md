## 1. Pre-flight (library re-check + version contention)

- [ ] 1.1 Pre-implementation library re-check (MUST — new substrate): dated WebSearch for `com.google.cloud:google-cloud-vision` (current stable 3.x, deprecations, JVM support 2026) AND confirm Cloudflare Images still has no official JVM SDK → raw Ktor-client HTTP. Record the outcome one-liner in the first feat commit body (`re-check 2026-MM-DD confirms …`).
- [ ] 1.2 Pick the next free Flyway version for `image_uploads` against current `origin/main` (latest is V22). If `privacy-flip-worker` (#321) or `admin-premium-username-oversight` (#323) merged a V23 first, renumber — never edit an applied migration (checksum-immutable).

## 2. Schema — image_uploads ledger (Flyway)

- [ ] 2.1 Add `V<next>__image_uploads.sql`: `image_uploads(cf_image_id TEXT PK, uploader_user_id UUID NOT NULL REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), safe_search_adult TEXT, safe_search_violence TEXT, safe_search_racy TEXT, status TEXT NOT NULL DEFAULT 'uploaded' CHECK (status IN ('uploaded','attached')))` + index on `(uploader_user_id, created_at)`. Confirm `posts.image_id TEXT` already exists (V4) — no posts DDL needed.
- [ ] 2.2 Verify the migration applies cleanly on a fresh DB container (CI-equivalent: disposable postgis container + Flyway boot).

## 3. :infra:cloud-vision module (Google Cloud Vision Safe Search)

- [ ] 3.1 Scaffold `infra/cloud-vision` (KMP/JVM as appropriate): interface `ImageModerator { fun isConfigured(): Boolean; suspend fun safeSearch(bytes: ByteArray): SafeSearchVerdict }` returning plain Kotlin types (the `RemoteConfigClient` interface precedent); `SafeSearchVerdict(adult, violence, racy)` with a `Likelihood` enum.
- [ ] 3.2 Single vendor impl (`google-cloud-vision` SDK) + fail-soft `NoOpImageModerator` (unconfigured → `isConfigured()=false`); factory reading the SA via `secretKey(env, "gcp-vision-sa")`. Never throws — null/unavailable on error.
- [ ] 3.3 Add the `com.google.cloud:google-cloud-vision` pin to `gradle/libs.versions.toml` + a `docs/09-Versions.md` Decisions-log row (rationale + re-check date).
- [ ] 3.4 Unit tests: verdict mapping (LIKELY/VERY_LIKELY → reject for adult/violence; racy logged-not-blocked); NoOp unconfigured behavior.

## 4. :infra:cloudflare-images module (server-side upload)

- [ ] 4.1 Scaffold `infra/cloudflare-images`: interface `ImageStore { fun isConfigured(): Boolean; suspend fun upload(bytes, contentType): StoredImage }` (`StoredImage(imageId, deliveryUrl)`); raw Ktor-client `POST .../accounts/{account}/images/v1` (server-side upload, NOT Direct Creator Upload — D1).
- [ ] 4.2 Fail-soft `NoOpImageStore` + factory reading `secretKey(env, "cloudflare-images-api-token")` + `secretKey(env, "cloudflare-images-account-hash")`. Delivery URL built on the `img` subdomain (Open Decision #32).
- [ ] 4.3 Unit tests (mock Ktor engine): success → `StoredImage`; unconfigured → NoOp; HTTP error → fail-soft.

## 5. Flag-cache for image_upload_enabled (docs/11 §3.3 short-TTL override)

- [ ] 5.1 Implement a Redis-cached flag read for `image_upload_enabled` with a 30s per-flag TTL (key `{scope:remote_config}:{flag:image_upload_enabled}`), following the `Layer3ConfigLoader` Redis-cache precedent — cache miss → `RemoteConfig.getBoolean(...)`; any error → default FALSE (fail closed).
- [ ] 5.2 Unit tests: flag TRUE/FALSE/null/Redis-error → effective value (default FALSE on error); TTL=30s asserted.

## 6. Backend image package — upload endpoint (Route → Service → Repository)

- [ ] 6.1 `ImageUploadRepository` (JDBC): insert `image_uploads` (status uploaded); select-for-attach by `(cf_image_id, uploader_user_id, status='uploaded')`; flip to attached.
- [ ] 6.2 `ImageUploadService` (business rules + tx boundary): the D9 validation order — flag → premium (`PREMIUM_STATES`) → throttle (1/60s) → daily quota (50, override `premium_image_upload_cap_override`, `computeTTLToNextReset`, `{scope:image_upload:<userId>}`) consumed at attempt → 5 MB size guard → Safe Search (reject adult/violence LIKELY|VERY_LIKELY) → CF upload → ledger insert. Fail-soft → 503 when infra unconfigured.
- [ ] 6.3 `ImageRoutes` (thin): `POST /api/v1/images` multipart parse, authenticate, `call.clientIp` for limiter keys, map service results → 201 / 403 / 413 / 422 / 429 / 503 with the existing error envelope. Wire into Koin + route registration.
- [ ] 6.4 Service tests (kotest, `@Tags("database")` where DB-backed; InMemory RateLimiter + fake ImageStore/ImageModerator): every spec scenario — happy path; flag-off 403; fail-closed 403; Free rejected; grace allowed; 51st/day 429; 2nd-in-60s 429; moderation-reject still consumes; adult/violence 422 not stored; clean passes (high racy ok); >5 MB 413; unconfigured 503; ledger row written.

## 7. Post-creation image attach

- [ ] 7.1 Extend the `POST /api/v1/posts` request DTO with optional `image_id`; in `CreatePostService`, validate ownership/unattached against `image_uploads` and atomically set `posts.image_id` + flip ledger to attached (max 1/post). No `image_id` → unchanged path.
- [ ] 7.2 Tests: attach owned-uploaded; reject other-user; reject non-existent; reject already-attached; atomic rollback on INSERT failure; text-only response field-set unchanged.

## 8. Doc reconciliation (canonical-source alignment)

- [ ] 8.1 Amend `docs/02-Product.md` §6: state the Safe Search policy as categorical (reject adult/violence `LIKELY`/`VERY_LIKELY`; racy logged-not-blocked) — replace the conceptual ">0.8" wording with the enum mapping; note racy is a launch-tunable.
- [ ] 8.2 Amend `docs/05-Implementation.md`: document the `image_uploads` table alongside the existing `posts.image_id`; add the three new secret slots (`cloudflare-images-api-token`, `cloudflare-images-account-hash`, `gcp-vision-sa`) to the secrets list; note `premium_image_upload_cap_override` in the feature-flags section.

## 9. Module-registration maintenance (silent-deploy-break guards)

- [ ] 9.1 Add `include(":infra:cloudflare-images")` + `include(":infra:cloud-vision")` to `settings.gradle.kts` (backend-included, NOT mobile-gated).
- [ ] 9.2 Add matching `Dockerfile` builder-stage COPY blocks for both modules (PR #247 precedent: a non-gated `include()` without COPY breaks every staging/prod deploy while CI stays green). Run `dev/scripts/check-dockerfile-module-copies.sh` — must pass.
- [ ] 9.3 Add one-line entries to `dev/module-descriptions.txt` for both modules + run `dev/scripts/sync-readme.sh --write`.

## 10. Verification gate (pre-push)

- [ ] 10.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally (CI runs both lint frameworks).
- [ ] 10.2 Confirm Detekt invariants pass: secrets-via-`secretKey`, no-vendor-SDK-outside-`:infra:*`, rate-limit-TTL, Redis hash-tags, `clientIp` (no raw `X-Forwarded-For`).
- [ ] 10.3 Full gate on fresh DB containers (CI-equivalent) to avoid dev-seed pollution false-fails.

## 11. Staging deploy + smoke (pre-archive)

- [ ] 11.1 `gh workflow run deploy-staging.yml --ref premium-image-upload-pipeline`; poll the deploy run to green (`/health/ready`).
- [ ] 11.2 Smoke (`dev/scripts/smoke-premium-image-upload-pipeline.sh`): with the flag default-FALSE + CF/Vision unprovisioned, `POST /api/v1/images` returns 403 (flag off) — NOT 500; the app boots and other routes serve. (Authenticated-route guard: a 302→/admin or 401 on unauth is a valid migration-applied signal.)

## 12. Deferred-work follow-up issues (file at apply, label follow-up)

- [ ] 12.1 `gh issue create --label follow-up,backend` — orphan-image cleanup job (delete CF blob + `image_uploads` row for `status='uploaded'` older than N).
- [ ] 12.2 `gh issue create --label follow-up,backend` — `csam-detection-webhook-and-archive` (the deferred CSAM subsystem; references the guard requirement).
- [ ] 12.3 `gh issue create --label follow-up,mobile` — `mobile-image-upload-ui` (upload UI + client compression + read-path image surfacing modifying the read specs/DTOs).
