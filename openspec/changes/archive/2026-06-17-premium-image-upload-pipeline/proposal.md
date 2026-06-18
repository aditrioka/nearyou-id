## Why

Image upload is the largest unbuilt premium feature (`docs/02-Product.md` §6 is **DESIGN — no code today**) and the keystone of the premium revenue loop: with the paywall + entitlement + billing-webhook backend already shipped, a Premium user still has no premium content capability beyond username/post-edit/search. This change builds the **backend image-upload pipeline** so a Premium user can upload a moderated image and attach it to a post — gated dark behind `image_upload_enabled` (default FALSE) until the Month-6 launch, so it lands safely now and ships when ops provisions Cloudflare Images + the launch flag flip.

## What Changes

- **New `POST /api/v1/images` upload endpoint** (multipart): flag-gate → premium-gate → throttle/quota → upfront moderation → store → return `{ image_id, delivery_url }`. The validation chain, in order:
  - **Flag gate** — reads the existing `image_upload_enabled` Remote Config flag (default **FALSE**); returns **403** when off. Mirrors the `search_enabled` kill-switch precedent in `SearchService` (with the opposite default).
  - **Premium gate** — Free tier = **0** uploads (rejected); Premium = entitled. Uses the `PREMIUM_STATES = {premium_active, premium_billing_retry}` precedent (grace-period users keep access), matching `CreatePostService`/`PostEditService`.
  - **Quota + throttle** — Premium **50 uploads/day** + **1 per 60 s** per user, on the existing Redis rate-limit infrastructure (`computeTTLToNextReset(user_id)` WIB-midnight stagger; `{scope:…}` hash tags), mirroring like/reply/chat limiters.
  - **Upfront moderation** — synchronous Google Cloud Vision **Safe Search**; **reject** (image never stored) when `adult` OR `violence` OR `racy` likelihood is `LIKELY` or `VERY_LIKELY` (the categorical `Likelihood` enum — docs/02's ">0.8" is conceptual).
  - **Input guards** — server-side **5 MB** streamed size limit (multipart-bomb guard) + an `image/*` content-type allowlist (415 on non-image).
  - **Store** — upload to Cloudflare Images; delivery URL on `img-staging.nearyou.id` / `img.nearyou.id` (resolved Open Decision #32).
- **New `:infra:cloudflare-images` module** — Cloudflare Images upload behind an interface; **fail-soft** (feature-unavailable) when unconfigured, per the FCM/RevenueCat precedent.
- **New `:infra:cloud-vision` module** — Google Cloud Vision Safe Search behind an interface; **fail-soft** when unconfigured (no vendor SDK import outside `:infra:*`).
- **New `image_uploads` ledger table** (Flyway) — binds each Cloudflare `image_id` to its uploader so post-attach can authorize ownership (the existing `posts.image_id TEXT` is unvalidated free text); also the future home for CSAM linkage. (See design.md for the two-step-vs-multipart decision and why the ledger is needed.)
- **Modified post creation** — `POST /api/v1/posts` accepts an optional `image_id` and **validates it is owned by the caller** (via the ledger) and not already attached; max **1** image per post. Stored in the existing `posts.image_id` column.
- **Doc reconciliation** — amend `docs/05-Implementation.md` to document the new `image_uploads` table alongside the bare `posts.image_id` it already lists.

### Explicitly DEFERRED (out of scope — guarded as spec requirements; each is a named follow-on)

- **CSAM subsystem** — `/internal/csam-webhook`, `csam_detection_archive` (AES-256-GCM, 90-day, `cf_match_id`/`image_hash` dedup), admin CSAM review queue + Kominfo workflow. (`csam_detection_archive` is confirmed DESIGN/no-migration today; Open Decision #33 = admin-triggered MVP.) → follow-on `csam-detection-webhook-and-archive`.
- **Mobile upload UI** — upload button (hidden when flag FALSE), client-side compression, picker. → follow-on `mobile-image-upload-ui`.
- **Delivery-cost optimization** (`srcset` single-variant, lazy-load), **anomaly detection** (>5× baseline), **`:infra:r2`** (non-image files).

## Capabilities

### New Capabilities
- `premium-image-upload`: the backend upload pipeline — `POST /api/v1/images` with the flag → premium → quota/throttle → Safe Search → Cloudflare Images chain, the `image_uploads` ownership ledger, fail-soft infra behavior, and the deferred-CSAM / deferred-mobile-UI guard requirements.

### Modified Capabilities
- `post-creation`: `POST /api/v1/posts` accepts an optional `image_id`, validates it is owned by the caller and unattached, and persists it (max 1 image/post). Previously `posts.image_id` was an unused, unvalidated column.

## Impact

- **New modules**: `:infra:cloudflare-images`, `:infra:cloud-vision` (both backend-included, non-mobile-gated) → require matching `Dockerfile` builder-stage COPY blocks (silent-deploy-break guard, PR #247 precedent) + `dev/module-descriptions.txt` entry + `sync-readme.sh --write`.
- **New dependency pin**: Google Cloud Vision JVM client (`com.google.cloud:google-cloud-vision`) → the pre-implementation library re-check fires at `/opsx:apply`. Cloudflare Images has no official JVM SDK → raw Ktor-client HTTP (no new pin expected).
- **Schema**: new Flyway migration for `image_uploads` (next free version — contention with in-flight `privacy-flip-worker` / `admin-premium-username-oversight`; renumber at rebase).
- **Secrets** (via `secretKey(env, name)`, `staging-*`-prefixed): Cloudflare Images API token + account hash, GCP Vision service-account JSON.
- **API**: new `POST /api/v1/images`; `POST /api/v1/posts` request DTO gains optional `image_id`.
- **Backend package**: new `image` package in `:backend:ktor` (Route → Service → Repository).
- **Flag**: enforces `image_upload_enabled` (default FALSE) — feature stays dark until the Month-6 launch flip.
