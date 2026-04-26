## Context

Phase 2 social features are otherwise complete. Search is the last spec-driven Phase 2 capability per `docs/08-Roadmap-Risk.md` Phase 2 item 6, and a key Premium differentiator per `docs/02-Product.md` § Search (Premium, Month 1+).

The infrastructure required for search is partially shipped:

- **`visible_posts` / `visible_users` views** for shadow-ban safety (`visible-posts-view` capability) ✓ shipped
- **Bidirectional `user_blocks` exclusion pattern** enforced by `BlockExclusionJoinRule` Detekt rule (`block-exclusion-lint` capability) ✓ shipped
- **Shared `RateLimiter` interface** + `RedisRateLimiter` (`rate-limit-infrastructure` capability, shipped via `like-rate-limit` change). `ReportRateLimiter` (V9) is the precedent for non-daily Layer 2 callers — see `backend/ktor/.../moderation/ReportRateLimiter.kt` for the canonical key form `{scope:rate_<axis>}:{user:<uuid>}` ✓ shipped
- **`search_enabled` Remote Config flag** declared in `docs/05-Implementation.md:1413` (boolean, default TRUE) ✓ flag slot exists

NOT YET SHIPPED — V4's `V4__post_creation.sql` file comment explicitly says: *"FTS-specific columns (content_tsv + GIN indexes) are deferred to the Search change."* So this change owns the FTS schema build-out:

- `pg_trgm` extension (no migration creates it yet — `docs/05-Implementation.md:1154` describes it as setup; this change is the setup)
- `posts.content_tsv` TSVECTOR GENERATED column + `posts_content_tsv_idx` GIN + `posts_content_trgm_idx` GIN
- `users_username_trgm_idx` GIN

Plus: the endpoint + service + repository + rate-limit wiring; tests; smoke script.

## Goals / Non-Goals

**Goals:**

- Premium-only `GET /api/v1/search?q=&offset=` endpoint returning ranked post results with author profile, joined via `visible_posts` + `visible_users`, with bidirectional block exclusion + auto-hide filter + Premium-private-profile gate
- Free users receive `403 { error: "premium_required", upsell: true }`; guests receive `401`
- Hourly rate limit (60/hour Premium) via the shared `RateLimiter` interface, sliding-window TTL 3600s
- `search_enabled` Remote Config kill switch returning `503` when `FALSE`
- Query length guard `1 ≤ q.length ≤ 100`; `400` outside that range
- V13 Flyway migration adding `users_username_trgm_idx` GIN trigram index
- Pre-archive smoke script `dev/scripts/smoke-premium-search.sh` per `openspec/project.md` § Staging deploy timing

**Non-Goals:**

- **Redis search-result cache**: `docs/05-Implementation.md:1189` says "if added at scale" — defer until p95 latency or DB CPU on this query justifies it. The GIN indexes are auto-maintained, so no re-index trigger plumbing is needed in the MVP.
- **Re-index trigger plumbing on shadow-ban / unban / block / unblock**: only relevant once a Redis cache lands. The view + GIN combination handles correctness without extra hooks.
- **Indonesian stopword / stemming dictionary**: `'simple'` tsvector config is the explicit MVP choice (`docs/05-Implementation.md:1159`); upgrade to a custom Indonesian dictionary or Meta XLM-R tokenizer service is deferred to Month 6+.
- **Location-filtered search**: `docs/02-Product.md:278` ("No location filter (MVP)"). Adding a spatial filter would require deciding whether to use `display_location` (consistent with the Coordinate Fuzzing rule) and rethinking ranking.
- **Hashtag / mention indexing**: out of scope; FTS handles plain content tokens only.
- **Search analytics / popular-query telemetry**: out of scope; Amplitude event taxonomy can land later behind analytics consent.

## Decisions

### Decision 1 — New capability name: `premium-search`

The capability is named `premium-search` (not `search`) to make the Premium gate explicit at the spec-folder level, mirroring the precedent set by other engagement features (`post-likes`, `post-replies`, `following-timeline`, etc.) where the capability name describes the user-visible feature, not just the technical mechanism. If a future Free-tier search variant ever lands (e.g., guest-discoverable hashtag index), it would be a sibling capability rather than a modification to this one.

**Alternatives considered:**

- `search` — too generic; would obscure the Premium gate at the spec layer.
- `post-search` — undersells the username component. Search covers both posts AND usernames per `docs/02-Product.md:275-276`.

### Decision 2 — Hourly Layer-2 rate limit, NOT WIB-stagger daily

`docs/05-Implementation.md:1746` lists "Search query (Premium) | 60/hour". The existing `RateLimitTtlRule` Detekt lint enforces `computeTTLToNextReset(user_id)` ONLY for daily caps (where the 00:00 WIB thundering-herd matters). For hourly caps, a fixed 3600-second sliding-window TTL is canonical — `ReportRateLimiter` (V9) is the precedent.

- Redis key shape: `{scope:rate_search}:{user:<user_id>}` — canonical two-segment hash-tag form matching `ReportRateLimiter.keyFor` (`backend/ktor/.../moderation/ReportRateLimiter.kt:92`). Both segments hash-tagged so cluster-safe multi-key ops remain available if a burst clause ever joins
- Behavior on cap exceeded: `429` with `Retry-After: <seconds-until-window-rolls>` header populated from `RateLimiter.Outcome.RateLimited.retryAfterSeconds`
- Window: `Duration.ofHours(1)`, sliding window via the same single-Lua script that backs `RedisRateLimiter`

**Alternatives considered:**

- WIB-stagger daily cap (1440/day Premium): would force users to use search continuously to avoid the daily reset, encourages binge-search behavior, and doesn't match the `60/hour` listing in the canonical rate-limit table. Rejected.
- Burst clause (e.g., 10/minute on top of 60/hour): the canonical doc lists no burst clause for search (unlike likes' `500/hour` burst). Adding one would diverge from canonical without justification. Deferred until abuse data shows the velocity-fingerprint surface needs it.

### Decision 3 — Schema delta ownership: `posts` FTS lives in `post-creation`, username trigram lives in `users-schema`

A single Flyway migration `V13__premium_search_fts.sql` ships all five schema artifacts — but the spec deltas split by table ownership, matching the project's existing pattern (`users-schema` owns `users` columns; `post-creation` owns `posts` columns). So:

- `posts.content_tsv` GENERATED column + `posts_content_tsv_idx` + `posts_content_trgm_idx` + `CREATE EXTENSION pg_trgm` → `post-creation` capability ADDED requirement
- `users_username_trgm_idx` → `users-schema` capability ADDED requirement
- The endpoint, service, repository, rate limiter, and FTS query behavior → `premium-search` (new capability)

The single migration file is the deployment unit; the spec deltas are organized by long-term schema ownership. This means future `posts` schema changes land in `post-creation`, future `users` schema changes land in `users-schema`, and future `search` behavior changes land in `premium-search` — without cross-capability churn.

**Alternatives considered:**

- Put all five schema artifacts in `premium-search` (since V4's deferral note says "the Search change" owns them): would split `users.username` schema ownership across capabilities going forward (any future username-related index would need to choose which capability owns it). Rejected for inconsistency.
- Put posts FTS in `premium-search` and username trigram in `users-schema`: hybrid; rejected for the same reason — splits posts schema ownership.
- Two separate migrations (V13 for posts, V14 for users): rejected — both depend on `pg_trgm`, and shipping them as one atomic migration is cleaner. The migration file vs. spec-delta dimensions are independent.

### Decision 4 — Canonical query is copied verbatim into the spec

`docs/05-Implementation.md:1163-1181` is the canonical FTS query. The spec's WHEN/THEN scenarios reference the SQL exactly — joins, filter clauses, the privacy gate's three-arm boolean, the LIMIT/OFFSET — so that any drift between implementation and canonical doc surfaces during proposal review (per `CLAUDE.md` § Reviewing a PR §8 reconciliation rule, and the reconciliation pass in `/next-change` Phase B step 7).

**Alternatives considered:**

- Paraphrase the query in the spec: rejected — paraphrasing has caused divergence in past changes (PRs #18 / #19 global-timeline incident, PR #24 v10 notifications spillover) and is the exact failure mode the reconciliation pass exists to prevent.

### Decision 5 — Premium private-profile gate uses the canonical 3-arm boolean

The query MUST include `(u.private_profile_opt_in = FALSE OR u.subscription_status NOT IN ('premium_active', 'premium_billing_retry') OR EXISTS (SELECT 1 FROM follows WHERE follower_id = :viewer_id AND followee_id = u.id))` per `docs/05-Implementation.md:1176-1178`.

This encodes the documented Privacy Tier behavior (`docs/02-Product.md` § Privacy Tiers): only Premium users can be effectively private. Non-Premium users with `private_profile_opt_in = TRUE` (e.g., a former Premium user past the 72h privacy-flip warning) still surface to non-followers because the second arm short-circuits the OR. The third arm exempts followers regardless.

This is load-bearing and must be tested with all three branches active (Premium private + non-follower → hidden; Premium private + follower → visible; Free private (legacy) → visible to anyone).

### Decision 6 — Query length guard at 100 chars is application-layer, not DB

PostgreSQL FTS handles arbitrarily long queries, but a 100-char hard cap defends against quadratic-cost trigram matching on enormous queries and matches the existing content-length-guard CI lint pattern (`CLAUDE.md` § Critical invariants — "Content length guards"). Lower bound `q.length >= 1` rejects empty-string queries that would behave as a degenerate full-table scan with no useful ranking.

**Alternatives considered:**

- 280-char cap (matching post content): rejected — search queries don't need to be as long as posts; tighter cap reduces server-side CPU on adversarial input.
- No cap: rejected — risks DoS surface on `pg_trgm`.

### Decision 7 — Auth required (no guest access)

Even though Search is Premium-gated (and Premium requires an account anyway), the endpoint declares `authenticate("rest")` at the route level rather than relying on the Premium-status check to also imply auth. This means guests without a JWT receive `401` (not `403 premium_required`), which is the correct semantic. Symmetric with how other Premium endpoints (e.g., the future `PATCH /api/v1/user/username`) layer auth → entitlement.

## Risks / Trade-offs

- **Risk: `'simple'` tsvector config matches lexemes literally — Indonesian stemming/stopwords missed.** → Mitigation: documented MVP limitation in proposal + design; upgrade path noted in `docs/05-Implementation.md:1159`. Added scenario verifies the literal-match behavior so the upgrade is detectable as a behavior change.

- **Risk: Trigram fuzzy match on usernames returns false positives at low similarity thresholds.** → Mitigation: rely on PostgreSQL's default `pg_trgm.similarity_threshold` (`0.3`); document that threshold is not customized. If false positive rate becomes a UX problem, raise the threshold via `set_limit()` in a follow-up — single-knob change with no schema impact.

- **Risk: GIN index on `users.username` increases write amplification on signup + Premium username change.** → Mitigation: GIN insert cost is bounded; the precedent of `posts_content_trgm_idx` on a much higher-write table (`posts`) is acceptable. No projected benchmark issue for the ~MVP scale.

- **Risk: Canonical query SCANs visible_posts when the FTS predicate selectivity is low.** → Mitigation: the `posts_content_tsv_idx` GIN supports `@@` directly; `EXPLAIN` should show GIN bitmap scan paths. If query plan regresses under load, add a pre-filter on `created_at >= NOW() - INTERVAL '90 days'` (recency filter is a standard FTS optimization). Out of scope for V1.

- **Risk: Hourly rate limit with sliding window may surprise users hitting the 60th query at minute 50, then expecting a fresh window at minute 60.** → Mitigation: Return `Retry-After` header with the exact window-roll seconds; the existing rate-limit-infrastructure error envelope (per `like-rate-limit` precedent) is the contract.

- **Risk: Premium gate via `subscription_status IN (...)` could miss a state transition if the RevenueCat webhook is delayed.** → Mitigation: `private_profile_opt_in` flip uses 72h grace + `privacy_flip_scheduled_at` worker (out of scope for this change but Premium subscription state is the canonical source). Search use is short-window — a few minutes of stale Premium state on either side of a webhook is acceptable.

- **Trade-off: No Redis search-result cache means every query hits Postgres.** → Acceptable at MVP scale (hundreds to low thousands of Premium users); cache add is a clean retrofit if p95 ever approaches the timeline target (200ms per `docs/08-Roadmap-Risk.md` Phase 2 benchmark).

- **Trade-off: No re-index hooks on shadow-ban / block events.** → Acceptable because the view + GIN combination always reflects current truth; the only thing missing is a cache layer that doesn't yet exist.

## Migration Plan

1. Land V13 Flyway migration adding `users_username_trgm_idx` (additive; reversible by `DROP INDEX`)
2. Deploy `SearchService` + `SearchRoute` behind the `search_enabled` flag (default TRUE per existing Remote Config declaration — no flag flip required)
3. Pre-archive: `gh workflow run deploy-staging.yml --ref premium-search` → smoke against branch deploy → tick task list
4. Squash-merge → main-staging auto-deploys
5. **Rollback path**: if abuse / load surfaces, flip `search_enabled = FALSE` via Remote Config (5-min cache TTL) — instant kill-switch; no code revert required. Index is left in place (no harm).

## Open Questions

None at proposal time. All decisions trace to canonical sources (`docs/02-Product.md` §4 Search, `docs/05-Implementation.md` § Search Implementation, `docs/08-Roadmap-Risk.md` Phase 2 item 6 + rate limit table). If the proposal-review loop surfaces a question that can't resolve from the docs, it lands here as an addendum before implementation begins.
