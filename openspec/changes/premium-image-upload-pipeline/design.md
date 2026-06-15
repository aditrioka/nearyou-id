## Context

`docs/02-Product.md` §6 (Premium Media Upload) and `docs/08` Phase 4 #16 specify a Premium image-upload feature, gated dark behind `image_upload_enabled` (default FALSE) until a Month-6 launch. None of it exists today: no upload endpoint, no Cloudflare Images / Vision wiring, no `:infra:r2`/`:infra:cloudflare-images` modules. The posts table already carries an unused, unvalidated `image_id TEXT` (V4). The premium revenue loop (paywall, entitlement, billing webhook) is shipped; image upload is the missing premium content capability.

This change builds the **backend pipeline only**: upload → moderate → store → attach. CSAM handling, the mobile UI, delivery-cost optimization, and anomaly detection are deferred (proposal § Deferred; guarded as spec requirements). It must land green while Cloudflare Images / Google Cloud Vision are unprovisioned in staging, and stay dark until ops flips the flag.

**Standards conformance (docs/11).** Reuses existing Pattern-Registry patterns — no new pattern, no docs/11 amendment required:
- **Backend layering §3.1** — `ImageRoutes` (thin: parse/validate/authenticate/respond) → `ImageUploadService` (business rules + tx boundary) → `ImageUploadRepository` (JDBC). Routes never touch SQL; services never read `ApplicationCall`.
- **JDBC discipline §3.2** — Hikari pool, no per-request connections, parameterized SQL.
- **Remote-Config staleness §3.3** — D6 below is the §3.3-anticipated first realization of the per-flag short-TTL override (docs/11 names `image_upload_enabled` by name).
- **Rate-limit infra** — reuses the shared `RateLimiter` interface + `computeTTLToNextReset` + `{scope:…}` hash tags (like/reply/chat precedent).
- **`:infra:*` module shape** — interface + single vendor impl + fail-soft NoOp + factory (the `RemoteConfigClient` / `:infra:supabase-realtime` precedent); no vendor SDK import outside `:infra:*`.
- **Secrets** — `secretKey(env, name)` only. **clientIp** — `call.clientIp`, never raw `X-Forwarded-For`.

## Goals / Non-Goals

**Goals:**
- A Premium user can `POST /api/v1/images` (multipart) and, when the flag is ON, get back `{ image_id, delivery_url }` for an image that passed Safe Search and is stored in Cloudflare Images.
- `POST /api/v1/posts` can attach a caller-owned `image_id` (max 1/post).
- The whole feature is dark and inert when the flag is FALSE or CF/Vision are unconfigured — lands green now, ships at Month 6.

**Non-Goals (deferred, guarded by spec requirements):**
- CSAM webhook / `csam_detection_archive` / admin CSAM queue / Kominfo (`csam-detection-webhook-and-archive`).
- Mobile upload UI + client-side compression (`mobile-image-upload-ui`).
- Delivery-cost optimization (srcset/lazy-load), anomaly detection (>5× baseline), `:infra:r2`.
- Orphan-image cleanup job (uploaded-but-never-attached) — tracked as a follow-up; ledger status makes it a pure additive later.

## Decisions

**D1 — Cloudflare Images via server-side upload (not Direct Creator Upload); raw Ktor-client HTTP.**
The upfront moderation requirement forces the bytes through the backend *before* storage, so Direct Creator Upload (client → CF directly, one-time URL) is incompatible — it would bypass Safe Search. The backend receives the multipart, moderates, then `POST`s the bytes to `https://api.cloudflare.com/client/v4/accounts/{account_id}/images/v1`. Cloudflare ships no official JVM SDK (verified 2026-06-16: docs show Node/Ruby/C# only), so `:infra:cloudflare-images` uses the existing Ktor client (no new `libs.versions.toml` pin). Delivery URL = `https://img-staging.nearyou.id/<account_hash>/<image_id>/<variant>` (staging) / `img.nearyou.id` (prod) per resolved Open Decision #32; `imagedelivery.net` is the documented emergency fallback.
_Alternative rejected:_ Direct Creator Upload — cheaper bandwidth but defeats synchronous moderation.

**D2 — Vision Safe Search via `com.google.cloud:google-cloud-vision`; categorical likelihood mapping; reject adult/violence/racy.**
`SafeSearchAnnotation` returns a categorical `Likelihood` enum (`UNKNOWN, VERY_UNLIKELY, UNLIKELY, POSSIBLE, LIKELY, VERY_LIKELY`) — **not** a 0.0–1.0 score (verified 2026-06-16, Google Cloud Java client docs). docs/02's ">0.8" is conceptual; the policy maps to **reject when `adult` OR `violence` OR `racy` ∈ {LIKELY, VERY_LIKELY}**. **Racy is included in the reject set** to match the canonical reject set (docs/02 §6 Image Upload Flow line 359 + docs/06 "explicit content upfront" line 203 both list adult/violent/racy; the docs/02 Moderation-Flow table's adult/violent-only wording is the narrower outlier, reconciled by the broader statements). `LIKELY`/`VERY_LIKELY` (not `POSSIBLE`) is the block threshold — the conventional Safe Search block boundary, slightly stricter than a literal ">0.8" (VERY_LIKELY only), deliberate for CSAM-adjacent safety. `:infra:cloud-vision` isolates the SDK behind an interface returning a plain `SafeSearchVerdict`; **new pin** `com.google.cloud:google-cloud-vision` → the apply-time library re-check fires. Reconciliation: amend docs/02 §6 to state the categorical mapping + make the Moderation-Flow table consistent (racy in the reject set) (tasks item). A post-launch relaxation of `racy` to log-not-block is a tunable Open Question, NOT shipped here.
_Alternative considered:_ racy log-not-block (fewer false positives on suggestive-but-legal content) — rejected for this change as a divergence from canon + a moderation relaxation on an 18+ app; retained as a post-launch tunable.
_Alternative rejected:_ Cloudflare's own image classification — less mature for explicit-content categories; Vision is the docs-specified tool.

**D3 — Two-step upload + `image_uploads` ownership ledger (not single-step multipart on post-create).**
`POST /api/v1/images` stores the image and returns `image_id`; `POST /api/v1/posts` then attaches it. Because `posts.image_id` is unvalidated free text, attaching needs an authorization source: a new `image_uploads` ledger (`cf_image_id` PK, `uploader_user_id` FK, `created_at`, `safe_search_*` verdict, `status` ∈ {uploaded, attached}). The ledger also (a) enables the deferred CSAM linkage (cf_match_id → uploader) and (b) tracks orphans for the deferred cleanup. Post-attach validates: row exists, `uploader_user_id = caller`, `status = 'uploaded'`; then atomically sets `posts.image_id` + flips ledger `status='attached'`.
_Alternative rejected:_ single-step multipart on `POST /api/v1/posts` — no orphans, but mixes multipart into the JSON post endpoint and still needs a moderation/CF round-trip inside the post tx (worse failure semantics). Net-new `image_uploads` table is beyond docs/05's bare `posts.image_id` → amend docs/05 (tasks item).

**D4 — Premium gate = `{premium_active, premium_billing_retry}`.** Matches `CreatePostService`/`PostEditService` (grace-period users keep premium; the post-creation path image upload attaches to). Free tier → rejected (cap 0). _Not_ the profile-read `premium_active`-only formula — image upload is a write capability like post-create, so it follows post-create.

**D5 — Quota 50/day + throttle 1/60s on the shared Redis `RateLimiter`, consumed at attempt.** Two limiter configs keyed per user with `computeTTLToNextReset(userId)` (WIB-midnight stagger) + `{scope:image_upload:<userId>}` hash tags. Both are **consumed at attempt** (before Safe Search + CF upload), so moderation-rejected attempts still count — this bounds Google Vision (pay-per-image) and CF spend against an abusive Premium user hammering rejects. Free cap = 0 (gate short-circuits before any limiter call). The **daily cap is overridable** via a `premium_image_upload_cap_override` Remote Config flag (default 50), mirroring the established `premium_like_cap_override` / `premium_reply_cap_override` / `premium_chat_send_cap_override` precedent. The cap-override is read via the **uncached** `RemoteConfig.getBoolean`/int path (matching those precedents), distinct from the D6 kill-switch flag which is Redis-cached with a short TTL — an intentional split (a capacity tweak does not need 30-second propagation; an emergency kill does). The override flag MUST be registered in `FeatureFlagCatalog.EDITABLE` (kind `IntRange`, **not** `Bool`) so it is settable + validated from the admin surface — the single edit needed to surface a flag (tasks item; do not copy the existing miscategorized-as-`Bool` `premium_like_cap_override` entry).
_Trade-off:_ a Safe-Search false-positive costs a legit user one quota slot — accepted for cost-safety; adult/violent-only (D2) keeps FP low. Surfaced in Open Questions as tunable.

**D6 — Flag read conforms to docs/11 §3.3: Redis-cached with a 30s per-flag short-TTL override; fail-soft default FALSE.**
docs/11 §3.3 (operator-ratified 2026-06-11) names `image_upload_enabled` as the first expected emergency kill-switch needing a short TTL. This change realizes it: the read goes through a Redis-cached flag read (`{scope:remote_config}:{flag:image_upload_enabled}`, **TTL 30s** — fastest kill propagation within the 30–60s band) following the `Layer3ConfigLoader` Redis-cache precedent, **not** the uncached `SearchService` path. On cache miss → `RemoteConfig.getBoolean("image_upload_enabled")`; on any error (Redis down, SDK throw, unset) → **default FALSE** (feature stays dark — opposite of search's default-TRUE, because a launch-gated flag must fail closed). `SearchService` remains uncached (pre-existing, out of scope). No Pattern-Registry amendment — this is §3.3's anticipated first realization.

**D7 — Infra fail-soft.** `:infra:cloudflare-images` + `:infra:cloud-vision` expose `isConfigured()` + return a NoOp/unavailable result when their secrets are absent (FCM `AndroidFcmTokenProvider` / `NoOpRemoteConfigPublisher` precedent). When unconfigured, the upload endpoint returns **503 feature-unavailable** (never 500/crash), so an unprovisioned staging boots and serves every other route. Combined with the flag defaulting FALSE, the change is inert on deploy.

**D8 — Secrets via `secretKey(env, name)`; new `staging-*`-prefixed slots.** `cloudflare-images-api-token`, `cloudflare-images-account-hash`, `gcp-vision-sa` (service-account JSON). Names documented in `docs/05` secrets list (tasks item); values are operator-provisioned (slot names in source are non-sensitive per the public-repo posture).

**D9 — Validation order (fail cheap first).** flag-gate (Redis-cached) → authenticate → premium-gate → throttle (1/60s) → daily-quota reserve (50) → size guard (5 MB, streamed) → Safe Search → CF upload → ledger INSERT → 201. Each earlier stage avoids the cost of the later ones (no Vision/CF call for a flag-off / Free / throttled / over-quota / oversized request).

## Risks / Trade-offs

- **Orphan uploads** (uploaded, never attached) accrue CF Images storage + ledger rows → cost. → *Mitigation:* ledger `status='uploaded'` makes a later periodic cleanup (delete CF blob + row for unattached uploads older than N) a pure-additive follow-up; flag it as a `follow-up` issue at apply.
- **Vision pay-per-image cost.** → *Mitigation:* Free cap 0 + Premium 50/day + consume-at-attempt + flag-dark-by-default bound total spend; cost alert is an ops concern at launch.
- **Safe-Search false-positive** costs a legit quota slot (D5). → *Mitigation:* adult/violent-only; tunable.
- **Migration version contention** with in-flight `privacy-flip-worker` (#321) / `admin-premium-username-oversight` (#323). → *Mitigation:* renumber the migration at rebase; the table name (not version) is what specs reference.
- **CF/Vision unprovisioned in staging** → 503 on the endpoint. → *Mitigation:* D7 fail-soft + flag FALSE; smoke verifies 403 (flag off) not 500.
- **Double new `:infra:*` modules** → silent deploy break if Dockerfile COPY blocks are missed (PR #247). → *Mitigation:* explicit tasks items + `check-dockerfile-module-copies.sh`.

## Migration Plan

1. Ship Flyway `V<next>__image_uploads.sql` (next free version; renumber at rebase). Table is unused while the flag is FALSE → safe to deploy dark.
2. Deploy lands with `image_upload_enabled` defaulting FALSE and CF/Vision unconfigured → endpoint 503/403, no behavior change for users.
3. **Month-6 launch (ops, out of this PR) — ordered checklist:** (a) **enable the Cloudflare CSAM Scanning Tool on the `nearyou.id` zone** (Dashboard → Caching → Configuration; auto HTTP-451 on NCMEC match — docs/06) **— this MUST precede the flag flip** so no served image is un-CSAM-scanned; (b) provision `img.nearyou.id` + CF Images + Vision SA and populate the three secret slots; (c) only then flip `image_upload_enabled` → TRUE. The 30s flag TTL (D6) makes the flip — and any emergency flip back to FALSE — propagate within 30s. (CSAM *reporting/preservation* — the `/internal/csam-webhook` handler + archive + Kominfo — is the separate deferred `csam-detection-webhook-and-archive` change; detection/blocking is zone-level and covered by step (a).)
4. **Rollback:** flag → FALSE (instant kill) and/or revert the feat commits; the migration can stay (inert table) or be reverted as a new migration (never edit an applied one).

## Open Questions

- **Racy auto-reject** — this change rejects racy (matches canon, D2). A post-launch *relaxation* to log-not-block (fewer false positives on suggestive-but-legal content) is the tunable; confirm with product whether to relax after observing the launch false-positive rate.
- **Orphan-cleanup cadence** — deferred to a follow-up; confirm acceptable to ship without it while dark.
- **Short-TTL value** — 30s chosen (D6); 30–60s band per docs/11. Tunable.
- **Migration version number** — resolved at rebase against whatever `privacy-flip-worker` / `admin-premium-username-oversight` land.
