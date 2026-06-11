# 01 — Backend Core (bootstrap, config, auth, DB/Redis access, cross-cutting HTTP)

Audited 2026-06-10 against `docs/11-Engineering-Standards.md` §3/§4 and `openspec/project.md` § Coding Conventions. Scope: `Application.kt`, `config/ common/ guard/ internal/ lint/ auth/**`, `infra/supabase|redis|oidc`, `application.conf`.

## CRITICAL

### 1. Application.kt:903 + 926-931 — Apple S2S webhook is OIDC-gated by route-tree merging (endpoint unreachable by Apple)
`appleS2SRoutes` registers `POST /internal/apple/s2s-notifications`; a later `routing { route("/internal") { install(InternalEndpointAuth) ... } }` block reuses the SAME `/internal` node — Ktor's `RoutingNode.createChild` returns the existing child on selector equality (verified against ktor-server-core 3.5.0 bytecode; `afterIntercepted()` → `invalidateCachesRecursively()` makes descendants rebuild pipelines including later-installed ancestor plugins). So the route-scoped OIDC plugin gates the Apple webhook too: Apple sends no `Authorization: Bearer <Google-OIDC>` header → 401 `missing_authorization` before the handler runs. This violates `openspec/specs/internal-endpoint-auth` ("vendor-webhook endpoints MUST live in a sibling route block that does NOT install InternalEndpointAuth" — separate `routing {}` blocks do NOT create siblings for identical path segments). Latent only because Apple sign-in isn't live; `InternalEndpointSpanAttributeTest` "4.6" mounts the webhook WITHOUT co-mounting the OIDC subtree, so tests can't catch it. Fix: move the webhook off the `/internal` prefix (e.g. `/webhooks/apple/s2s-notifications`, cheap pre-launch) or nest the OIDC-gated routes under a distinct child segment (`/internal/jobs/...`); add a regression test that co-mounts BOTH blocks and asserts the webhook answers without OIDC. Confidence: high.

### 2. auth/AuthPlugin.kt:84, auth/routes/AuthRoutes.kt:113-183, auth/signup/SignupService.kt:67-133, post/CreatePostService.kt:94, health/HealthRoutes.kt:169 — blocking JDBC/Redis directly on request coroutines in the auth + write hot paths
`JdbcUserRepository.findById` is plain blocking JDBC and runs inside the JWT `validate {}` block — i.e. EVERY authenticated request does a synchronous DB round-trip on Ktor's Netty call-group thread (no `withContext` anywhere in the path). Same for signin/refresh/logout (`findBy*Hash`, `RefreshTokenService.rotate/issue/revoke*`), the whole signup flow, `CreatePostService.create` (tx + moderation Redis/Remote-Config fetch via non-suspending `ModerationListLoader.load`), and the health-route `tryAcquireByKey` (the `RedisRateLimiter` KDoc itself says callers SHOULD wrap in IO). docs/11 §3.2: "never the Netty event loop". With callGroupSize ≈ cores (1-2 vCPU on Cloud Run) and concurrency 80, one slow DB burst stalls all request processing including `/health/*` → instance recycling. Fix: suspend-ify repository call sites by hopping to the shared bounded dispatcher (finding 4) at the service/plugin boundary. Confidence: high.

### 3. Application.kt:279-290 — `exception<Throwable>` leaks internal exception messages to clients AND swallows the stack trace
`"message" to (cause.message ?: ...)` echoes raw internals in 500 bodies: Hikari pool exhaustion ("request timed out after 1500ms..."), PSQLException SQL fragments, Lettuce errors — exactly the class of disclosure the serialization-1.11 bump was taken to prevent (docs/11 §3.3). The handler also never logs `cause`, so 500s reach Cloud Logging as status-only lines with no stack trace (CallLogging doesn't capture it). Fix: respond with a fixed `internal_error` message, `log.error("unhandled", cause)` inside the handler. Confidence: high.

## HIGH

### 4. backend-wide — no shared bounded JDBC dispatcher; services that do wrap use raw `Dispatchers.IO`
docs/11 §3.2's #1 rule — `Dispatchers.IO.limitedParallelism(maxPoolSize)` exposed via DI as a single instance — is implemented nowhere. The services that DO dispatch (LikeService:97-172, ReplyService:110, SearchService:123-137, ChatService:122, TimelineReadRateLimiter:103-168, FcmTokenRepository:35, ConsentRepository:41) each use raw `Dispatchers.IO` (64-thread default), so a request flood queues 64 threads on a 20-conn pool, starving unrelated IO and amplifying Hikari `connectionTimeout` failures. Fix: bind one `CoroutineDispatcher` in Koin sized to the pool; mechanical `withContext(dbDispatcher)` swap. Confidence: high.

### 5. infra/supabase/db/DataSourceFactory.kt:16-28 + application.conf:17 — Hikari config off the docs/11 §3.2 contract (pool size, maxLifetime, prepareThreshold, leak detection)
`maximumPoolSize` defaults to 20 and application.conf pins `maxPoolSize = 20` with no `${?DB_MAX_POOL_SIZE}` override — docs/11 says 2-10 per Cloud Run instance and `maxInstances × poolSize` must fit the Supavisor budget. No `maxLifetime` (Hikari default 30 min may exceed the pooler's idle cutoff → connection resets mid-checkout), no `leakDetectionThreshold`, and `prepareThreshold=0` is not enforced as a `dataSourceProperties` — correctness on Supavisor transaction mode (:6543) currently depends silently on whoever typed the `staging-db-url` secret. Fix: drop default to ≤10, make it env-tunable, set `maxLifetime` (~5 min), set `prepareThreshold=0` (or document the session-mode decision in DataSourceFactory). Confidence: high on the gaps, medium on real-world impact (can't see the secret URL params).

### 6. auth/provider/ProviderIdTokenVerifier.kt:59-62,71-84 + infra/otel/KtorOtelPlugins.kt:50-59 — JWKS cache: unauthenticated refresh amplification, no HTTP timeout, no single-flight
`keyFor(kid)` does `entry.keys[kid] ?: refresh().keys[kid]` — any unauthenticated `/auth/signin` POST bearing a bogus `kid` forces a fresh Google/Apple JWKS HTTPS fetch (no negative caching, no rate limit), and the shared `httpClientWithOtel()` (CIO) installs NO `HttpTimeout` — a hung JWKS endpoint suspends signins indefinitely while piling coroutines. Concurrent cold-cache callers all fetch (no single-flight). Contrast `infra/oidc/GoogleOidcTokenVerifier.googleJwkProvider()` which gets `.cached(10, 6h).rateLimited(10/min)` — the project already owns the right pattern. Fix: add HttpTimeout (~3s) to the shared client; rate-limit forced refreshes + cache the miss for ~60s, or reuse the auth0 JwkProvider here. Confidence: high.

### 7. Application.kt:698 + config/RemoteConfig.kt:59 — `StubRemoteConfig` still bound in production; kill-switch + cap-override flags are permanently inert
`RemoteConfig` (consumed by LikeService, ReplyService, SearchService — `premium_like_cap_override`, `search_enabled` kill switch) is bound to the stub that always returns null. Its KDoc says "until the Firebase Remote Config Admin SDK lands" — it HAS landed: `firebaseRemoteConfigClient` (`:infra:remote-config`) is constructed 260 lines above in the same function for the moderation pipeline. Net effect: the documented no-deploy kill-switch for search does nothing in staging/prod. Fix: adapt `RemoteConfigClient` → `RemoteConfig` (trivial delegation) or explicitly re-document the stub as deliberate. No `follow-up` issue found tracking this. Confidence: high on the inertness; medium on whether intent was "later phase".

## MEDIUM

### 8. application.conf — no `shutdownGracePeriod`/`shutdownTimeout` configured
docs/11 §3.3 requires sizing graceful shutdown to Cloud Run's SIGTERM window (10s). Ktor/Netty defaults (500ms grace / 1.5-5s timeout) cut in-flight timeline/DB requests at deploy time. Add the `ktor.deployment.shutdownGracePeriod/shutdownTimeout` keys. Confidence: high.

### 9. Application.kt:223 — plugin set missing CallId (+ `callIdMdc`) and Compression
docs/11 §3.3 standard set: "CallId + CallLogging with callIdMdc (safe ≥ Ktor 3.4.3)" — the Ktor bump to 3.5.0 was justified by exactly this pairing, yet CallId is not installed anywhere; logs have no request-correlation id. Compression is also absent (timeline JSON payloads compress well; Cloud Run egress is billed). RequestValidation unused is acceptable (length guards are lint-enforced backstop). Confidence: high.

### 10. Application.kt:426,665,689 + infra/redis/RedisModule.kt:81-102 + OtelInstrumentation.lettuceClientResources — three Lettuce RedisClients, each with its own ClientResources/event-loop group
Cache, rate limiter, and probe each call a `*FromUrl` factory that builds a fresh `RedisClient` AND a fresh `ClientResources` (via `lettuceClientResources()`, new per call). `LettuceRedisStringCache`'s own KDoc prescribes the opposite ("Constructor takes RedisClient (NOT a URL) so the same client backs both"). Cost: 3 Netty event-loop groups + 3 connections per instance against Upstash free-tier connection limits. Fix: build one client (+ shared ClientResources) in Application.kt and pass it to all three. Confidence: high.

### 11. Application.kt:313-324 — unconditional `flyway.repair()` before every migrate defeats checksum-drift detection (QUESTION)
`repair()` realigns checksums on every boot, so an edited already-applied `V*__*.sql` (the project's own "checksum-immutable" invariant) would deploy silently instead of failing validation. The comment documents the intent (retry of failed migrations on staging), but repair-on-every-boot is broader than that intent. Fix sketch: run plain `migrate()`; on `FlywayValidateException` only, log loudly + `repair()` + retry — or gate repair behind its own env var. Confidence: high on behavior, medium on severity (staging-only today; prod plan is a migrate Job).

### 12. auth/AuthPlugin.kt:84-101 + AuthRoutes.kt:113-114 — soft-deleted users can still authenticate (`deleted_at` never checked)
`UserRow` carries `deletedAt`, the admin module treats soft-deleted as terminal (`UserModerationRepository` rejects moderation on them), but `validate {}` and the signin `findBy*Hash` lookups never check it — a soft-deleted user whose `token_version` wasn't bumped keeps full API access, and signin would re-issue tokens. No deletion flow ships yet, so this is a latent defense-in-depth gap; `openspec/specs/auth-jwt` is silent on it. Fix: treat `deletedAt != null` as `token_revoked` in validate + signin. Confidence: high on the gap, medium on exploitability today.

### 13. auth/signup/UsernameGenerator.kt:125-131 + SignupService.kt:88-128 — any unique violation is treated as a username collision → concurrent duplicate signup yields 503, not 409
`tryInsert` maps ALL SQLSTATE 23505 to "username taken" and retries. The exists-check (step 3) and INSERT (step 5) are separate transactions, so two concurrent signups with the same provider identity both pass the check; the loser then burns 5 template + 1 fallback INSERTs all failing on `users.google_id_hash` UNIQUE and surfaces as 503 `username_generation_failed` (false "wordlist exhausted" alert). Same conflation for `invite_code_prefix` TOCTOU. Fix: inspect the violated constraint name; map identity-hash violations to `UserExistsException` (409). Confidence: high on mechanics, medium on frequency (double-tap signups are the realistic trigger).

### 14. docs/11 §3.3 "ONE shared Json" not implemented — 7+ scattered instances, one constructed per request
ContentNegotiation builds its own (Application.kt:216, no explicit `encodeDefaults`), plus `common/Cursor.kt:32`, `chat/ChatCursors.kt:48`, `auth/signup/WordPairResource.kt:30`, `auth/provider/ProviderIdTokenVerifier.kt:87`, admin files — and `auth/routes/AppleS2SRoutes.kt:115` constructs `Json { ... }` INSIDE the request handler on every S2S notification. Settings drift between them is the real risk the contract targets. Fix: one `val AppJson` in a shared module, referenced everywhere; hoist the AppleS2S instance to a companion at minimum. Confidence: high.

### 15. auth/session/RefreshTokenService.kt:55-81 — rotation is read-then-act with no row lock; markUsed + issue span two implicit transactions (QUESTION)
Two concurrent rotations of the same token both observe `usedAt == null` and both mint sibling tokens (same family). docs/05:38's 30s overlap window arguably blesses this (client single-flight + retry tolerance), and `COALESCE(used_at, ?)` keeps first-use, so reuse detection still works — but the >30s reuse path (`revokeFamily` + `incrementTokenVersion`, RefreshTokenService.kt:69-70) is two non-atomic statements, and docs/05:112 says the rotated token is "deleted immediately on successful rotation" while the impl retains it (retention is the BETTER behavior — it's what makes reuse detection possible; docs/05 should be amended). Fix sketch if tightening: single `UPDATE ... SET used_at = ? WHERE id = ? AND used_at IS NULL RETURNING` as the claim step. Confidence: high on the race existing, low on it being out-of-design.

### 16. auth routes — no rate limiting on unauthenticated `/auth/signin`, `/auth/refresh`, `/auth/signup` (QUESTION)
Every other surface (likes, replies, search, timelines, reports, even /health) has a Redis limiter; the unauthenticated auth endpoints — each costing a JWKS check + 2-4 DB round-trips — have none. Refresh-token guessing is infeasible (256-bit) and signin needs a provider-signed token, so this is a DB-load/abuse-cost question, not an auth-bypass one; Cloudflare/Cloud Armor is the assumed front-line per ClientIpExtractor.kt's KDoc. Flagging as QUESTION: is an `{scope:auth}:{ip:...}` limiter spec'd for a later phase (docs/06 anti-spam layers)? Confidence: medium.

## LOW

### 17. Error-envelope construction is split across three mechanisms
StatusPages + AuthPlugin challenge use `mapOf("error" to mapOf(...))`, AuthRoutes/AppleS2S use `@Serializable ApiError`, InternalEndpointAuth uses raw string constants (flat shape `{"error":"..."}` — that one is spec'd). Wire shape for the first two is identical; consolidating on `ApiError` everywhere removes the chance of a key typo and double-maintains nothing. Confidence: high, severity low.

### 18. guard/ContentLengthGuard.kt:71 — log line reports the post limit (280) as "registered keys"
`log.info("... {} registered keys", guard.limitFor("post.content"))` prints "280 registered keys". Pass `limits.size` (needs exposing) or drop the count. Confidence: high.

### 19. auth/jwt/JwtIssuer.kt:31 — `verifier()` is dead code
No callers (AuthPlugin builds its own verifier at AuthPlugin.kt:69). Delete, or use it in AuthPlugin so the algorithm instance is shared. Confidence: high.

### 20. Application.kt:355-356 — two `JwksCache` instances for the same Apple JWKS URL
`appleVerifier` and `appleS2SJwks` each maintain an independent cache of `APPLE_JWKS_URL` → double fetches and divergent cache states. Share one instance. Confidence: high.

### 21. auth/routes/AppleS2SRoutes.kt:35-45,124-128 — dedup is per-instance and marks-seen before the side effect succeeds
`InMemoryDedup` is an in-process LRU: multi-instance Cloud Run gives Apple retries a different instance (no dedup), and `seen()` claims the key BEFORE `setAppleRelayEmail` runs — a transient DB failure makes the retry a "duplicate", dropping the event. Harmless today (the only handled op is an idempotent boolean), worth a comment or a Redis SETNX when deletion events land. Confidence: high on behavior, low on impact.

### 22. Application.kt:545,622 — JVM `Runtime.addShutdownHook` instead of Ktor `ApplicationStopping` monitor events
The Layer-3 and FCM drain hooks run concurrently with (not ordered against) Netty's request drain; subscribing to the application monitor would sequence drains after the server stops accepting work and keeps lifecycle in one idiom. Confidence: medium.

### 23. auth/jwt/RsaKeyLoader.kt:11 — `kid` hardcoded to "dev-1" in every environment
Cosmetic but it's the publicly served JWKS kid in prod, and docs/05's scheduled-rotation plan is kid-based; make it configurable (e.g. `KTOR_RSA_KID`) before the first prod key event. Confidence: high.

### 24. /internal/cleanup (docs/04, docs/06) is not implemented — refresh_tokens grows unboundedly
Only `unban-worker` is mounted under `/internal`; the docs/05:112 daily/weekly token cleanup job has no endpoint. Expired + rotated-and-retained rows accumulate forever (every refresh inserts a row). Fine pre-launch; needs a tracked issue if not already on the roadmap. Confidence: high on absence.
