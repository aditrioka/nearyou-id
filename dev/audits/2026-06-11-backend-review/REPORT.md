# Backend implementation review — 2026-06-11

**Mandate (operator):** review all backend implementations against specs (`openspec/specs/`), requirements and supporting docs; per feature/endpoint estimate completion, identify gaps, flag suboptimal/buggy code; recommend and apply fixes.

**Delivery note:** the PR branch was rebased over #223 mid-review; this commit re-pokes CI after the documented force-push/path-filter skip (see memory: orphaned `github.event.before` → empty diff → heavy lanes skip).

**Method:** 8 parallel review agents, one per backend area (auth, posts/timelines, engagement/search, social/profiles, chat/notifications/push, moderation/reports, admin/workers, platform/cross-cutting), each reading the area's specs + implementation + tests + the 2026-06-10 holistic-audit findings as the known-issue exclusion baseline. Every load-bearing claim was re-verified in source before any fix was applied. This review is complementary to the 2026-06-10 audit (PR #209): it re-verified that audit's shipped fixes (all PRESENT; two arrived without their tests/spec sync — closed here) and hunted only NEW findings beyond the tracked backlog (#210–#214, #208, #202, #195, #194, #190, #191, #181, deferred 02/04 items).

## Completion matrix (vs spec requirements + scenario test coverage, before this PR's fixes)

| Area | Capability | Completion | Main gap (before fixes) |
|---|---|---|---|
| Auth | auth-signin | 90% | JWKS verifier/cache scenarios untested; forced-refresh bug (fixed here) |
| Auth | auth-signup | 92% | missing-field 400 cases untested |
| Auth | auth-jwt | 95% | single-SELECT spy scenario only via chat suite |
| Auth | auth-session | 85% | `POST /auth/logout` + reuse/invalid wire codes have zero route tests |
| Auth | age-gate | 82% | missing-DOB 403-vs-400 spec conflict; 23514 backstop log never implemented |
| Auth | username-generation | 95% | release-hold binding is Noop (documented deferral until Premium usernames) |
| Auth | users-schema | 97% | V2 realtime policy variance restored by V15 re-create |
| Auth | analytics-consent-update | 100% | — |
| Posts | post-creation | 95% | 10/day cap shipped unspec'd + untested (spec'd + tested here); UUIDv7 nibble untested (added) |
| Posts | visible-posts-view | 95% | spec self-contradiction post-V20 (fixed here) |
| Posts | nearby-timeline | 95% | envelope/radius validated AFTER limiter pre-check (fixed here) |
| Posts | following-timeline | 100% | spec column-name staleness only (fixed here) |
| Posts | global-timeline | 100% | same staleness (fixed here) |
| Posts | timeline-read-rate-limit | 95% | #212 batching (tracked); ordering deviation (fixed here) |
| Posts | coordinate-jitter / distance-rendering | 100% | — |
| Posts | region-polygons | 95% | optional backfill not built (spec marks optional); migration spec drift (reconciled) |
| Engagement | post-likes | 98% | burst-release fix (02-L2) unspec'd + untested (closed here) |
| Engagement | post-replies | 95% | soft-deleted-parent 404 became reachable post-V20, untested (added); spec test-class name stale |
| Engagement | premium-search | 99% | — (OFFSET pagination operator-ratified) |
| Social | follow-system | 95% | 429 unspec'd (spec'd here); `/following` 404 + cursor tests missing (added); dead racy `follow()` (removed) |
| Social | user-blocking | 97% | 429 unspec'd (spec'd here) |
| Social | user-profile-read | 100% | — |
| Chat | chat-conversations | 95% | #213 last_read_at (tracked); pair-lock formula duplicated (delegated here) |
| Chat | chat-realtime-broadcast | 95% | span attr carried a wrong channel name (fixed here) |
| Chat | auth-realtime | 80% | `expiresIn` vs spec'd `expires_in` wire (fixed here); secretKey-helper requirement stale vs deploy convention (spec reconciled) |
| Notif | in-app-notifications | 90% | #195 wire reconciliation (tracked; dispatcher-seam drift noted on the issue) |
| Push | fcm-push-dispatch | 95% | real `{}` body_data collapsed to `""` against spec (fixed here) |
| Push | fcm-token-registration | 90% | oversize token → 500 with raw token in PSQL detail via WARN throwable (fixed here, `token_too_long` added) |
| Moderation | reports | 97% | 404 released the rate-limit slot → unmetered existence oracle, against spec/precedent (fixed here) |
| Moderation | moderation-queue | 100% | — |
| Moderation | content-moderation-keyword-lists | 85% | **ContentWriteRequiresModerationRule never executed in project runs** (fixed here) |
| Moderation | text-moderation (OpenAI layer) | 92% | engine spec drift CIO→Apache5 (reconciled); Layer3 scope WARN fields + BUCKET_LOW spec inconsistencies (recommended) |
| Admin | admin-schema/panel/log/rejected-ids/report-queue/user-moderation/worker/system-actor | 97–100% | forged-cookie response didn't clear the cookie per spec (fixed); InetSanitizer IPv6 gap (fixed); Argon2id benchmark spec stale (recommended) |
| Platform | backend-bootstrap | 95% | spec stale (pool 20, ready body) — reconciled |
| Platform | health-check | 95% | outer 2s cap cooperative-only; driver timeouts unset (fixed here); `latencyMs` wire vs spec (spec reconciled) |
| Platform | client-ip-extraction | 100% | — |
| Platform | rate-limit-infrastructure | 95% | unimplementable `withContext` clause (spec reconciled); false "default-active" lint claim (corrected) |
| Platform | internal-endpoint-auth | 100% | — |
| Platform | observability-otel-foundation | 90% | SDK never shut down → tail spans dropped every deploy (fixed); `endpoint` alias missing (recommended); stripper long-key fast-path gap (fixed) |
| Platform | migration-pipeline | 90% | V11/V12 split never spec-reconciled (done here) |
| Platform | module-structure | 90% | core-module plugin-set scenarios stale (reconciled, incl. this PR's detekt addition) |

## Headline finding

**7 of 10 custom Detekt invariant rules were silently inactive in every `./gradlew detekt` run, and the `:infra:*`/`:core:*` modules (where most guarded SQL/Redis/OTel code lives) ran no custom rules at all.** detekt 1.23 treats a rule absent from a provided config as inactive, while `Config.empty` (the unit-test path) defaults rules to active — so `:lint:detekt-rules:test` stayed green while the project runs skipped `ContentWriteRequiresModerationRule`, `RateLimitTtlRule`, `RedisHashTagRule`, `RawXForwardedForRule`, `OtelForbiddenAttributeRule`, `CoordinateJitterRule`, `IpAxisMustUseTryAcquireByKeyRule`. Two specs even codified the wrong "default-active pattern". Fixed by: activating all 10 rules in `backend/ktor/config/detekt/detekt.yml`, a new `nearyou.detekt` convention plugin + root `config/detekt/invariants.yml` applied to all 9 infra/core modules, relocating the bypass annotations to `:core:domain`, triaging all 19 then-firing sites (every one annotated with a documented reason or fixed), 2 new enumerated annotation reasons (`embedded_snapshot`, `service_layer_moderated`) with rule + spec sync, and `DetektConfigActivationTest` pinning every provider rule against both configs so this class of gap cannot recur.

## Fixes applied in this PR

1. **Lint enforcement restored** (headline above).
2. **JwksCache mid-TTL key-rotation outage** — the unknown-`kid` forced refresh short-circuited on a TTL-valid cache, so a Google/Apple key rotation 401'd every new-kid sign-in until `max-age` expiry (hours). Forced path now bypasses the TTL short-circuit, keeps single-flight + 60s cooldown; 7-case `JwksCacheTest` added (incl. the previously untested `parseCacheControlMaxAge` spec MUST).
3. **Ops:** `%X{call_id}` finally emitted in the log pattern (the MDC was populated but config-dead); root logger TRACE→INFO (cost + third-party-log disclosure); CallId verify bounded (`^[A-Za-z0-9_.-]{1,128}$` — log-forging/header-injection vector); OTel SDK shutdown wired to `ApplicationStopped` (tail spans incl. crash-explaining errors were dropped on every deploy); `/health/ready` actually bounded at the driver layer (pg statement timeout, datasource `socketTimeout=30`, Lettuce command timeout 2s) — the cooperative coroutine caps cannot interrupt blocking I/O; stale comments truthed (Ktor 3.4.3 pin, RedisModule sanitize claim, HealthRoutes outer-cap claim).
4. **Security/privacy:** FCM WARN no longer passes the throwable (PSQLException detail embeds the raw token) + route-level `token_too_long` guard; `InetSanitizer` rejects structurally invalid colon-junk (`:::`, `12345::`, 9-group) that 500'd the admin login/audit path; Apple S2S `aud` check fails CLOSED on empty allowlist (mirrors sign-in); presented-but-invalid admin session cookies are cleared on redirect per spec; OTel `ForbiddenAttributeStripper` detects long-typed forbidden keys (`client.port` et al.) the string-only fast-path missed.
5. **Behavior conformance:** Nearby envelope/radius validation hoisted BEFORE the limiter pre-check (spec step-2 ordering; 400s were burning Free users' quota) + zero-Redis-call tests; report 404 no longer releases the rate-limit slot (spec releases only on 409; release made invalid-target existence probing unmetered over raw tables) + pin test; realtime token wire `expires_in` per spec; `body_data` NULL-vs-`{}` distinguished end-to-end (NULL→`""`, real `{}` round-trips, per fcm-push-dispatch spec) via nullable `NotificationRow.bodyDataJson`; chat publish span attr carries the real channel identifier.
6. **Dead/duplicated code:** non-transactional `UserFollowsRepository.follow()` (pre-pair-lock race shape, zero production callers) removed; `ChatRepository` now delegates to the canonical `UserPairLock` (two copies of the lock-key formula risked divergent lock keyspaces).
7. **Spec/test reconciliation** (shipped-behavior sync, audit-PR precedent): post-likes burst-release path + scenario + `LikeRateLimitTest` pin; post-creation daily-cap requirement + 3 scenarios + new `PostRateLimitTest` + UUIDv7 nibble pin; follow/block 429 contracts; visible-posts-view V9-requirement historical rephrase; `author_user_id`→`author_id` in 4 specs' SQL; text-moderation CIO→Apache5; auth-realtime secret env-binding convention (REDIS_URL precedent); backend-bootstrap pool/ready-body; health-check `latencyMs` wire; rate-limit `withContext` clause + KMP-era paths; migration-pipeline V11/V12 split; module-structure plugin sets; fcm-token-registration `token_too_long`; reply soft-deleted-parent 404 test (post-V20 reachable; stale "unreachable" comment removed); `/following` 404 + social malformed-cursor tests; CLAUDE.md + project.md #160 "parked" → shipped 2026-06-08.

## Recommended follow-ups (NOT applied — need operator decision or their own change)

- **Widen #210**: V20 also 404s a shadow-banned caller's like/reply/read-replies on their OWN post (`resolveVisiblePost` reads `visible_posts`) — an instant ban-detectability oracle beyond the feed self-visibility #210 already covers. Fix sketch: `OR p.author_id = :viewer` arm in both engagement `resolveVisiblePost` literals + post-likes/post-replies spec deltas. Sequenced with #210's OpenSpec change (comment posted on the issue).
- **FCM token invalidation on logout** (new issue): `POST /auth/logout(-all)` revokes refresh tokens but leaves `user_fcm_tokens` rows — on shared devices the signed-out account's pushes keep arriving (iOS renders content OS-side). Needs a spec'd requirement (deregister-on-logout or a `DELETE /user/fcm-token` endpoint).
- **Refresh/signin user-state gates**: banned + soft-deleted users can rotate refresh tokens indefinitely (the validate gate blocks data access, so no leak — but it compounds known 01-#24 unbounded `refresh_tokens` growth and the suspension-vs-ban semantics interplay is deliberate). Decide together with #208/#214 auth work.
- **age-gate**: missing-`date_of_birth` returns 400 (deserialization) where the spec's pre-check scenario wants 403 precedence — needs a product decision + spec reconcile; the 23514 DB-backstop structured log (`signup.dob_check_db_fallback_fired`) is spec'd but unimplemented.
- **Smaller spec/test debt**: Layer3DispatcherScope after-shutdown WARN can't carry spec'd target fields (API takes an opaque block); `BUCKET_LOW` Sentry event internally inconsistent in spec; Layer-3 re-parenting (04-#1) still lacks its prescribed real-parent-Job regression test; observability `endpoint` span alias unset; Redis-password sentinel test absent (`sanitizeRedisUri` has no production caller — by design, comment now says so); admin `last_login_at` has no writer (dead column); re-ban of an already-banned author re-emits `account_action_applied` (suspend has guards, ban doesn't); Argon2id benchmark spec text predates the Cloud-Run re-tune.
- **Known tracked backlog unchanged**: #210–#214, #208, #202, #195, #194, #190, #191, #181, 02-H3/L1/L3, 04-#3(ratified)/#5/#7/#9/#10/#11, 07-#13.
