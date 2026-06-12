# 08 — Shipped-Item Detail Archive (cold storage)

Split from `docs/08-Roadmap-Risk.md` on 2026-06-12 to shrink the hot roadmap file that AI sessions load wholesale. Content here was moved **verbatim** from the parent file; headings mirror the parent file's phase/item numbering. Canonical behavior lives in `openspec/specs/` + `docs/05-Implementation.md` — this file is historical ship-manifest detail only.

## Development Phases — shipped-item elaborations

### Phase 1 — item 15: Nearby + Following + Global timeline spatial queries

Nearby (`nearby-timeline-with-blocks`, V5, `GET /api/v1/timeline/nearby`); Following (`following-timeline-with-follow-cascade`, V6, `GET /api/v1/timeline/following`); Global (`global-timeline-with-region-polygons`, V11 `admin_regions` schema + trigger + V12 552-row OSM polygon seed, `GET /api/v1/timeline/global`)

### Phase 1 — item 16: Block user feature

via `nearby-timeline-with-blocks` (V5 `user_blocks` + `POST/DELETE/GET /api/v1/blocks` + Detekt `BlockExclusionJoinRule`); follow-cascade via `following-timeline-with-follow-cascade` (transactional `user_blocks` INSERT + bidirectional `follows` DELETE in `BlockService.block()`)

### Phase 1 — item 24: 4-layer rate limiting — Layer 2 infrastructure detail

Lettuce-backed `RedisRateLimiter` with single-Lua sliding-window script, shared `RateLimiter` interface in `:core:domain`, `computeTTLToNextReset(userId)` WIB-stagger helper, hash-tag key standard `{scope:<value>}:{<axis>:<value>}` enforced by `RedisHashTagRule` Detekt rule, daily-cap call-site enforcement via `RateLimitTtlRule` Detekt rule. V9 `ReportRateLimiter` ported to the shared infra.

### Phase 2 — item 2: Following + Global timeline polygon reverse geocoding

V11 `posts_set_city_tg` BEFORE INSERT trigger + 4-step fallback ladder strict→buffered_10m→fuzzy_match→NULL; V12 552-row OSM polygon seed incl. 6 DKI kotamadya + 12nm maritime buffer on 48 coastal kabupaten

### Phase 2 — item 3: Like + reply rate limits

Like rate limit (`like-rate-limit`): 10/day Free + 500/hour burst (both tiers) on `POST /api/v1/posts/{post_id}/like`, `premium_like_cap_override` Firebase Remote Config flag (Decision 28) for ops-side adjustment without a mobile release.

Reply rate limit (`reply-rate-limit`): 20/day Free, unlimited Premium on `POST /api/v1/posts/{post_id}/replies` — daily-only (no burst clause per `02-Product.md`; the asymmetry with likes is canonical, not an oversight — replies lack the velocity-fingerprint surface that anti-bot burst caps target), `premium_reply_cap_override` Remote Config flag (mirrors the like flag; canonical authority `05-Implementation.md:1416`), oversized-flag clamp at 10,000 (anti-typo guard), no `releaseMostRecent` escape hatch (every successful POST is a real new row — no idempotent re-action analogous to the like handler's INSERT-ON-CONFLICT no-op). DELETE / GET unaffected (V8 author-only idempotent-204 contract preserved; read-side throttling lives at the timeline session/hourly layer).

### Phase 2 — item 9: Chat send rate limit

50/day Free, unlimited Premium on `POST /api/v1/chat/{conversation_id}/messages` — daily-only (no burst clause per `02-Product.md`; canonical asymmetry with likes, not an oversight — same rationale as replies in item 3, chat being a 2000-char compose surface without the velocity-fingerprint surface that anti-bot burst caps target), `premium_chat_send_cap_override` Firebase Remote Config flag (mirrors the like + reply flags; canonical authority `05-Implementation.md:1416`), oversized-flag fallback threshold at 10,000 (anti-typo guard — values above it fall back to the default 50, not a clamp applied to the override), no `releaseMostRecent` escape hatch (same real-new-row rationale as replies, item 3). GET /messages, GET /conversations, and POST /conversations are NOT rate-limited at the per-endpoint layer (read-side throttling lives at the session/hourly layer; conversation-create is rare and already serialized by the user-pair advisory lock from chat-foundation).

### Phase 2 — item 16: Text moderation Layer 3 (Perspective → OpenAI vendor pivot)

vendor pivoted mid-implementation from Google Perspective → OpenAI Moderation `omni-moderation-latest` after Perspective announced end-of-2026 sunset (OpenSpec change name + Firebase RC flag names + V9 SQL trigger value preserved as historical-artifact carve-outs per the proposal.md Vendor Swap Amendment). Final timeout budget: 3000ms regional baseline for asia-southeast1 (originally 500ms; bumped iteratively to cover empirical bimodal TTFB from Singapore → US OpenAI).

## Open Decisions — resolved entries (full bodies)

### Open Decision 4: Kabupaten/Kota Polygon Dataset — ✅ Resolved

~500 kabupaten/kota GeoJSON. **Resolved: OpenStreetMap** (`admin_level=5` kabupaten/kota + `admin_level=4` provinces, via Overpass API); shipped in `global-timeline-with-region-polygons` (V12 552-row seed = 38 provinces + 514 kabupaten/kota incl. 6 DKI kotamadya). Attribution (V12 migration header + app legal section): *"Administrative boundaries © OpenStreetMap contributors, available under the Open Database License (ODbL)"*. BPS rejected during acquisition: availability risk dominated (no reliable kabupaten/kota MULTIPOLYGON GeoJSON source). ODbL share-alike doesn't cascade to our use (we project `city_name` strings, not derived polygon data). Import pipeline: `dev/scripts/import-admin-regions/`. Full rationale: archived `openspec/changes/archive/2026-04-25-global-timeline-with-region-polygons/design.md` Open Question 1.

### Open Decision 12: OTel Vendor Final Decision — ✅ Resolved

**Resolved 2026-05-07: Grafana Cloud Tempo via OTLP/HTTP** (PR [#66](https://github.com/aditrioka/nearyou-id/pull/66) `observability-otel-foundation`); `:infra:otel` owns the exporter wiring; staging is live and emitting traces. Honeycomb + Cloud Trace evaluated; Grafana won on Free-tier ceiling at MVP volumes + the unified Tempo / Loki / Prometheus / Pyroscope surface (canonical `gcx` CLI per memory). **Vendor swap remains a within-`:infra:otel` change** per its encapsulation contract (`:infra:otel` is the sole owner of the OTel SDK + vendor exporter; the rest of the codebase stays vendor-neutral). **Trigger to revisit**: free-tier ceiling breach (currently 50 GB traces/month + 14-day retention), regional latency complaint (Grafana stacks are not in Asia), or a compliance regime forcing a single-region data-residency story. Staging credentials + Pre-Launch token rotation to a least-privilege Access Policy: `docs/10-Setup-Checklist.md` § 3.7.

### Open Decision 13: IAP vs Cloud Armor Admin Panel — ✅ Resolved

**Resolved 2026-04-26: Identity-Aware Proxy (IAP)** for staging + production admin access. Allowlist scoped to Gmail `nearyouid.founder@gmail.com`; expandable when a secondary admin is hired (Open Decision #9). Rationale (solo-operator MVP): IAP is free, ~15-min setup, Google-managed auth (MFA/hardware key inherited from the Google account), no daily VPN-connect friction, mobile-friendly across changing networks (Indonesia mobile-first). Cloud Armor + VPN rejected as **overkill for current scope**: $5+/mo minimum, 2-4hr setup, IP allowlist fragile against changing mobile/cafe networks, VPN client required on every device. Defense-in-depth path open: Cloud Armor + IAP layered later if a compliance regime (SOC2 / ISO27001) demands network isolation. Audit log free via Cloud Logging. **Trigger to revisit**: secondary admin hire OR formal compliance audit OR confirmed Google-account compromise vector.

### Open Decision 32: CF Images URL Pattern — ✅ Resolved

**Resolved 2026-04-26: custom subdomain `img.nearyou.id` (production) + `img-staging.nearyou.id` (staging)**, both CNAME'd via Cloudflare DNS to the CF Images delivery edge. Standard `imagedelivery.net/<account-hash>/<image-id>/<variant>` rejected because: (a) third-party domain weakens user trust + Privacy Policy clarity (UU PDP disclosure surface), (b) account hash leaks in the URL, (c) URLs are stored in DB — a vendor-specific hostname makes provider migration (Imgix / Cloudinary / self-host) painful. Custom subdomain costs +1 DNS record per env (~30 sec) + initial TLS provisioning (~15-60 min via Cloudflare DNS automation), and gives branding consistency with `api.nearyou.id` / `admin.nearyou.id`. `imagedelivery.net` retained as **emergency fallback** if subdomain provisioning breaks at launch (per Risk Register row "CF Images custom subdomain URL structure unverified"). Verified per `04-Architecture.md` (already pre-commits to the subdomain). Pre-Phase 1 gate: smoke-test upload + CSAM scan reaches scope via the custom subdomain (Pre-Phase 1 step 2).

### Open Decision 33: CSAM Trigger Path — ✅ Resolved

**Resolved 2026-04-26: MVP = admin-triggered manual via Admin Panel; Phase 2+ = Cloudflare Worker auto-forward.** The Cloudflare CSAM Scanning Tool emits no webhooks (only HTTP 451 + daily email), so bridging CF detection → backend `/internal/csam-webhook` enforcement needs an explicit trigger path. MVP path: admin reads CF email → pastes URL/`image_id` into the Admin Panel CSAM viewer → Admin Panel calls the handler with a session-bound CSRF token (per `06-Security-Privacy.md`). Phase 2+ path: CF Worker watches the `img.nearyou.id` route for `451` and POSTs a signed payload (Bearer + HMAC-SHA256, secret `cf-worker-csam-secret` in GCP Secret Manager) — same endpoint, different auth path. IMAP / email-poller rejected: worst latency (24h) + worst fragility (CF email format breaks). Manual MVP fits current scope: expected detection ~0/month given attestation enforcement + 18+ gating + Vision Safe Search upfront filter, so building the Worker now optimizes a problem that doesn't yet exist. `csam_detection_archive.source` column (`admin_manual` | `cf_worker` | `email_poll`) already in schema (Phase 1 §21 manifest; canonical DDL in `docs/05`) — both paths converge to the same archive row, dedup via UNIQUE on `image_hash` + partial UNIQUE on `cf_match_id`. **Triggers to migrate to Phase 2+ Worker**: detection frequency ≥1/week, OR documented founder absence ≥48h regularly, OR compliance audit requires real-time enforcement, OR Phase 4+ scale traffic. Until any trigger fires, MVP path stays canonical and Worker is documented-not-built.
