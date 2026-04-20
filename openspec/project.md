# NearYouID — Project Context

Location-based social media MVP (Indonesia, 18+ only). Text posts with location, nearby discovery, 1:1 chat, freemium + Premium. Modular monolith on Kotlin Multiplatform. Pre-launch build (~19–20 weeks) with soft launch after Pre-Launch phase.

Full design lives in [`docs/`](../docs/). This file is the minimum context an AI session needs; defer to the docs for anything load-bearing.

---

## Guiding Principles

1. **Vibe coding first** — development is AI-assisted. Optimize for clean interfaces, isolated modules, and standard frameworks over clever abstractions.
2. **Minimize runtime cost** — flat/predictable pricing > pay-per-use. Free tier maximized pre-launch.
3. **Minimize maintenance overhead** — solo operator (Oka). Pick managed services over self-hosting when it causes ops headaches.
4. **Portable by design** — every vendor integration sits behind a `:infra:*` abstraction so migrations are frictionless.

See [`docs/00-README.md`](../docs/00-README.md) for the full principle statement and cross-file reference map.

---

## Tech Stack (summary)

| Layer | Choice |
|---|---|
| Mobile | Kotlin Multiplatform + Compose Multiplatform (Android + iOS) |
| Backend | Ktor on Google Cloud Run (Jakarta) |
| Admin Panel | Ktor server-side + Pebble/Freemarker + HTMX (stateful, NOT an SPA) |
| DB | Supabase Pro (PostgreSQL + PostGIS), Flyway migrations via Cloud Run Jobs |
| Auth | Google Sign-In + Apple Sign-In → Ktor RS256 JWT (REST) + HS256 (Supabase Realtime WSS) |
| Realtime chat | Supabase Realtime Broadcast (Months 1–14) → Ktor WebSocket + Upstash Redis Streams (Month 15+) behind `ChatRealtimeClient` |
| Cache / rate limit | Upstash Redis |
| Media | Cloudflare R2 (non-image, zero egress) + Cloudflare Images (`img.nearyou.id`) + CF CSAM Scanning Tool |
| Push | FCM (Android data-only; iOS alert + NSE) |
| Attestation | Play Integrity + App Attest |
| Subscription | RevenueCat |
| Feature flags | Firebase Remote Config |
| Email | Resend (transactional only) |
| Observability | Sentry KMP (errors), OpenTelemetry → Grafana Cloud (traces), GCP Monitoring (metrics), Amplitude (consent-gated product analytics) |
| Serialization | kotlinx.serialization |
| DI | Koin |

Version pinning lives in the *Version Pinning Decisions Log* (Pre-Phase 1). Full stack table + architecture diagrams: [`docs/04-Architecture.md`](../docs/04-Architecture.md).

---

## Module Structure (dependency isolation)

```
:core:domain              (pure Kotlin, zero vendor deps)
:core:data                (interfaces + DTOs)
:shared:distance, :shared:resources
:infra:supabase, :infra:r2, :infra:cloudflare-images,
:infra:revenuecat, :infra:redis, :infra:attestation,
:infra:resend, :infra:remote-config, :infra:otel,
:infra:sentry, :infra:amplitude,
:infra:postgres-neon      (Plan B scaffold, keep compilable)
:infra:ktor-ws            (post-swap chat, Month 14+)
:backend:ktor             (routes + Koin wiring)
:mobile:app
```

Backend modules inside `:backend:ktor`: user, post, social, chat, media, moderation, admin, internal, health. Details + `ChatRealtimeClient` interface in [`docs/04-Architecture.md`](../docs/04-Architecture.md).

Rule: **no vendor SDK import outside `:infra:*`**. Domain/data code depends only on interfaces.

---

## Environments

Three tiers, identical code, different secrets/config (Pre-Phase 1 bootstraps staging; production not live until Pre-Launch):

- `dev` — local, Supabase CLI + Docker Compose (Ktor + Redis)
- `staging` — Cloud Run + Supabase Free + Upstash Free + RevenueCat sandbox. Subdomains `api-staging|admin-staging|img-staging.nearyou.id`. Synthetic data only, nuke-safe.
- `production` — full-spec. Subdomains `api|admin|img.nearyou.id`.

Secrets namespaced by env prefix in GCP Secret Manager (`staging-*` vs unprefixed prod). Ktor reads `KTOR_ENV` and resolves secrets via the `secretKey(env, name)` helper — direct secret-name reads are a lint violation. Mobile uses Android flavors / iOS xcconfig schemes (`staging` vs `production`). Deploy flow: merge → `main` auto-deploys staging; git tag `v*` → prod after manual approval. Details: [`docs/04-Architecture.md`](../docs/04-Architecture.md) § Deployment.

---

## Coding Conventions & CI Lint Rules

Enforced by CI (see [`docs/08-Roadmap-Risk.md`](../docs/08-Roadmap-Risk.md) § Development Tools for the full list). Notable:

- **Shadow-ban safety**: business code must query `visible_*` views, never raw `FROM posts|users|post_replies|chat_messages`. Raw reads allowed only in Repository own-content paths and the admin module.
- **Block enforcement**: business queries touching posts/users/chat_messages/post_replies must include the block-exclusion join.
- **Spatial**: non-admin paths must use `fuzzed_location`, never `actual_location`, for `ST_DWithin`/`ST_Distance`.
- **Client IP**: use the `clientIp` request-context value (populated by Cloudflare-aware middleware reading `CF-Connecting-IP`). Direct `X-Forwarded-For` reads are forbidden.
- **Rate-limit TTL**: must call `computeTTLToNextReset(user_id)` (per-user WIB midnight stagger). No hardcoded midnight math.
- **Redis keys**: must include hash tag `{scope:<value>}` for cluster-safe multi-key ops.
- **Username writes**: `UPDATE users SET username = ...` allowed only in signup flow + the single Premium customization transaction + admin module. Legitimate writers annotate `// @allow-username-write: signup|customization`.
- **Privacy flag writes**: `UPDATE users SET private_profile_opt_in = FALSE` only in the privacy flip worker + Settings flow. Annotate `// @allow-privacy-write: worker|user_settings`.
- **Content length guards**: input endpoints must length-check (post/reply 280 chars, etc.).
- **Admin sessions**: every `INSERT INTO admin_sessions` must populate `csrf_token_hash`.
- **Admin-user FKs** on operational tables must use `ON DELETE SET NULL`.
- **Mobile strings**: no hardcoded UI strings; must go through Moko Resources.
- **Partial indexes**: no `NOW()` in `WHERE` (non-immutable; PG rejects).
- **RLS changes**: mandatory test case "JWT `sub` not in `public.users` → deny" on every policy change.

Other conventions:
- API versioning: `/api/v1/...` from day one.
- Code style: ktlint.
- Tests: Kotest/JUnit5, Ktor test framework, Docker-based integration tests.
- DB role separation: `main_app`, `admin_app`, `flyway_migrator` — each its own Secret Manager slot. DDL only via `flyway_migrator`.

---

## Key Architectural Decisions

- **Modular monolith**, not microservices. One Ktor deployable.
- **Dual JWT**: RS256 for REST (with JWKS + kid rotation) + HS256 for Supabase Realtime WSS. Third-Party Auth migration is trivial later. See [`docs/05-Implementation.md`](../docs/05-Implementation.md) § Authentication.
- **Coordinate fuzzing**: posts store both `actual_location` and `fuzzed_location` (HMAC-SHA256 jitter with `JITTER_SECRET`). Non-admin reads use fuzzed only.
- **Chat write path is Ktor-authoritative**: client writes REST → Ktor persists → Ktor broadcasts. No direct Postgres Changes subscription from clients.
- **CSAM trigger path**: Cloudflare CSAM Tool does NOT emit webhooks. MVP path is admin-triggered from the Admin Panel after CF's daily email; Phase 2 adds an optional CF Worker forwarder.
- **18+ only + under-18 blocklist** (`rejected_identifiers`); no account recovery by design.
- **Backups**: Supabase PITR 7-day + weekly `pg_dump` encrypted via `age` to R2 + append-only deletion log (7-year retention).

---

## Doc Map

| File | Scope |
|---|---|
| [`docs/00-README.md`](../docs/00-README.md) | Overview, principles, cross-references |
| [`docs/01-Business.md`](../docs/01-Business.md) | Freemium tiers, pricing, payments, ads, GTM, cost forecast |
| [`docs/02-Product.md`](../docs/02-Product.md) | Feature specs (posts, timeline, social, chat, media, search, notifications) |
| [`docs/03-UX-Design.md`](../docs/03-UX-Design.md) | Copy, onboarding, empty/permission/consent flows |
| [`docs/04-Architecture.md`](../docs/04-Architecture.md) | Tech stack, diagrams, modules, deployment, observability, backup, push |
| [`docs/05-Implementation.md`](../docs/05-Implementation.md) | DB schemas, algorithms, auth/session, rate limits, cache keys, feature flags |
| [`docs/06-Security-Privacy.md`](../docs/06-Security-Privacy.md) | Attestation, anti-spam, moderation, CSAM, UU PDP, age gate |
| [`docs/07-Operations.md`](../docs/07-Operations.md) | Admin panel stack, admin schema, IAP/Cloud Armor/WebAuthn |
| [`docs/08-Roadmap-Risk.md`](../docs/08-Roadmap-Risk.md) | Phases, CI lint rules, risk register, open decisions |

Before making a non-trivial change, skim the relevant doc — many details (jitter, rotation, race-safe patterns, rate-limit layers) are load-bearing and not duplicated here.
