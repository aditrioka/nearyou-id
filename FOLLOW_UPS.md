# Follow-ups

Transient working file for findings discovered during a change cycle that are NOT in scope of the current change but need a tracked owner. Per repo convention:

- Add an entry when a finding is real, fixable, but should NOT be silently swept into the current change's scope.
- Tick the action-item checkboxes as they are completed.
- Delete the entry once all its action items are merged.
- Delete the file itself when it has zero entries left.
- Recreate the file (with this same intro blurb) the next time a finding arises.

Format per entry:

```
## <kebab-case-finding-name>

**Discovered during:** <change name + step that surfaced it>
**Status:** open | triaged | in-progress | resolved-not-merged

**Finding:** <one paragraph: what the divergence is, with file:line citations on both sides>

**Specs at fault:** <list>
**Code at fault:** <list>
**Docs at fault:** <list>

**Impact (if shipped):** <one paragraph>

**Ambiguity to resolve first:** <if any>

**Action items:**
- [ ] <step>
- [ ] <step>
```

---

## premium-search-reindex-trigger-doc-divergence

**Discovered during:** `premium-search` `/next-change` Phase D round 1 (general-lens sub-agent flagged that `proposal.md` defers re-index trigger plumbing, but `docs/02-Product.md:282` declares the trigger as live infrastructure).
**Status:** open

**Finding:** [`docs/02-Product.md:282`](docs/02-Product.md) under § Search (Premium, Month 1+) declares: *"Re-index trigger: async job on every shadow ban / block / unban applied."* No such async job has ever shipped — the project's view-based shadow-ban + GIN-auto-maintenance design (per [`docs/05-Implementation.md:1881`](docs/05-Implementation.md) `Search: FROM visible_posts JOIN visible_users` and [`docs/05-Implementation.md:1189`](docs/05-Implementation.md) "On shadow-ban / unban / block / unblock: application code invalidates any Redis search-result cache (if added at scale)") makes the trigger unnecessary until a Redis search-result cache lands. The `premium-search` proposal explicitly defers the cache to "Month 6+ at scale" and consequently does not implement the trigger either. The doc line is aspirational; the runtime does not implement it.

**Specs at fault:** None — `premium-search/specs/premium-search/spec.md` correctly specifies the view-based safety net + defers cache + trigger together.
**Code at fault:** None — the design is deliberate.
**Docs at fault:** [`docs/02-Product.md:282`](docs/02-Product.md) overstates current infrastructure.

**Impact (if shipped):** Low. The Search feature is correct without the trigger (views handle shadow-ban; GIN auto-maintains; no cache exists yet to invalidate). Risk is to a future maintainer reading `02-Product.md:282` and concluding the trigger is missing → spending time looking for it before discovering it was never needed. Also: when the Redis cache eventually lands (Month 6+), the trigger DOES become necessary, and at that point this divergence resolves itself by the canonical infrastructure catching up to the doc claim.

**Ambiguity to resolve first:** None. The fix shape is clear: amend `docs/02-Product.md:282` from `Re-index trigger: async job on every shadow ban / block / unban applied.` to `Re-index trigger (deferred to Month 6+, when the Redis search-result cache lands per docs/05-Implementation.md:1189): async job on every shadow ban / block / unban applied.` Docs-only change, no spec or code impact.

**Action items:**
- [ ] File a docs-only amendment to `docs/02-Product.md:282` clarifying the deferral. Either as a standalone docs PR OR batched with the next OpenSpec change that touches Phase 2 social-features documentation.
- [ ] When the Redis search-result cache eventually lands (Month 6+), the trigger requirement becomes a live spec deliverable — at that point, write the `search-result-cache` OpenSpec change with both the cache infrastructure AND the trigger plumbing in the same lifecycle.

---

## staging-smoke-before-archive-skill-codification

**Discovered during:** `reply-rate-limit` `/opsx:apply` Section 6 (the staging smoke step) — surfaced the gap that the prior `like-rate-limit` cycle ran the smoke AFTER squash-merge (auto-deploy from main), which means a deploy-config bug would have already shipped to staging-from-main before being caught.
**Status:** triaged — 3 of 4 action items shipped in PR [#50](https://github.com/aditrioka/nearyou-id/pull/50); item 4 deferred (rescoped below).

**Finding:** The current skill docs do NOT codify a staging deploy + smoke step before `/opsx:archive`. The `like-rate-limit` precedent ran the smoke as task 9.7 AFTER the archive (post-merge auto-deploy from main), and `tasks.md` 9.7's three follow-up fixes (PR #43 secret-name, PR #44 lazy-connect, GCP slot TLS scheme) all landed AFTER the change had already shipped to staging-from-main. A pre-archive smoke against a manual branch deploy gives us a chance to fix deploy-config bugs BEFORE the one-way-door of squash-merge.

The proposed canonical workflow:
1. `/next-change` opens the proposal PR (no code → no deploy/smoke).
2. `/opsx:apply` implements + tests + CI green (as today).
3. **NEW:** `/opsx:apply` triggers `gh workflow run deploy-staging.yml --ref <branch>` before smoke.
4. **NEW:** `/opsx:apply` runs the smoke against staging (script lives in the change's tasks.md section 6).
5. `/opsx:archive` lands doc sync + archive after smoke green.

**Specs at fault:** None.
**Code at fault:** None.
**Docs at fault:**
- `.claude/skills/openspec-apply-change/SKILL.md` (canonical apply skill) ✅ updated in PR #50.
- `.claude/commands/opsx/apply.md` (mirror) ✅ updated in PR #50.
- `openspec/project.md` § Change Delivery Workflow + § Staging deploy timing ✅ updated in PR #50.
- Optional: brief mention in `CLAUDE.md` § Delivery workflow — not done; low value (the canonical lifecycle doc already has it).

**Impact (if shipped):** Low. The `reply-rate-limit` cycle already exercises this pattern correctly. PR #50 codified it for future cycles.

**Ambiguity to resolve first (item 4 only):** Should `openspec-propose` / `next-change` skill templates default-include a Section 6 smoke-script stub for runtime-impact changes? Tradeoff: pro = the skill author can't forget it; con = bakes a specific tasks.md shape into the template that may not fit every change. Current state: per-change discretion (the skill author writing the propose-time task list decides). The `reply-rate-limit` cycle worked because the user explicitly asked for a Section 6 in the `/next-change` instructions.

**Action items:**
- [x] Spin up a `chore(skills): codify staging-deploy-before-archive in /opsx:apply` PR after `reply-rate-limit` squash-merges. → PR [#50](https://github.com/aditrioka/nearyou-id/pull/50) merged 2026-04-26T02:26:48Z.
- [x] In that PR: add a Section 6-equivalent step to `.claude/skills/openspec-apply-change/SKILL.md`. Mirror to `.claude/commands/opsx/apply.md`. → done in #50.
- [x] Update `openspec/project.md` § Change Delivery Workflow to reflect the new step in the lifecycle table. → done in #50 (§ Staging deploy timing + § Archive timing both updated).
- [ ] (Deferred — separate follow-up) Decide whether `openspec-propose` / `next-change` templates default-include a Section 6 smoke-script stub for runtime-impact changes. See "Ambiguity to resolve first" above. Spin off as `openspec-propose-default-section-6-smoke` if/when triaged.

---

## auth-jwt-spec-debt-userprincipal-subscription-status

**Discovered during:** `reply-rate-limit` proposal review (Phase D round 1 — the on-demand `claude.yml` review pass at PR #49 flagged that the reply spec depends on `UserPrincipal.subscriptionStatus` being populated by the auth-jwt plugin, but no spec documents the field).
**Status:** open

**Finding:** [`like-rate-limit` task 6.1.1](openspec/changes/archive/2026-04-25-like-rate-limit/tasks.md) added `subscriptionStatus: String` to `UserPrincipal` (populated from `users.subscription_status` by `AuthPlugin.configureUserJwt`) as a **code change with no corresponding spec amendment**. Neither [`openspec/specs/auth-jwt/spec.md`](openspec/specs/auth-jwt/spec.md) nor [`openspec/specs/auth-session/spec.md`](openspec/specs/auth-session/spec.md) documents this field. The `like-rate-limit/specs/post-likes/spec.md` § "Daily rate limit" requirement and now `reply-rate-limit/specs/post-replies/spec.md` § "Daily rate limit" both rely on the field being populated; a future maintainer reading the canonical auth-jwt spec will find no `subscriptionStatus` on the principal and may assume the rate-limit handlers are buggy.

**Specs at fault:** `openspec/specs/auth-jwt/spec.md`, `openspec/specs/auth-session/spec.md`
**Code at fault:** None — the field is correctly populated; the spec is the gap.
**Docs at fault:** None — `docs/05-Implementation.md` § Auth Session does describe principal contents abstractly but doesn't go field-by-field.

**Impact (if shipped):** Low. Code is correct. Risk is to future-maintainer cognitive load: rate-limit handlers reference an undocumented principal field; a refactor that adds new fields to `UserPrincipal` may forget `subscriptionStatus` because no spec lists it. Also: the same gap will accumulate as future rate-limit changes (post-rate-limit, search-rate-limit, etc.) all consume the field.

**Ambiguity to resolve first:** None. The fix shape is clear: a docs-only OpenSpec change `auth-jwt-principal-subscription-status` that adds a Requirement to `auth-jwt` spec documenting `UserPrincipal.subscriptionStatus: String` (loaded from `users.subscription_status`, three-state enum, populated at JWT issuance). No code change needed. Could be batched with similar principal-field documentation tasks if more land later.

**Action items:**
- [ ] File a docs-only OpenSpec change `auth-jwt-principal-subscription-status` with one ADDED Requirement to `auth-jwt` spec describing the field.
- [ ] In the same change, optionally amend `auth-session` spec to cross-reference the principal field for capability-spanning clarity.
- [ ] When the Phase 4 RevenueCat webhook handler lands, ensure `EXPIRATION` / cancellation events bump `users.token_version` so JWT re-issuance picks up the new tier (closes the JWT-TTL window risk documented in `reply-rate-limit/design.md` § Premium → Free downgrade window). Confirmed during `reply-rate-limit` apply (task 1.5): the RevenueCat webhook handler **does not yet exist in the backend** — the verification is moot until Phase 4 lands the handler, but should be the first item on the handler's spec checklist.

---

## reports-rate-limit-cap-doc-vs-spec-drift

**Discovered during:** `like-rate-limit` proposal scoping (Phase B step 7 reconciliation pass — checking the V9 in-process limiter port for any drift before reusing its hash-tag key shape as a precedent).
**Status:** open

**Finding:** The shipped `reports` capability enforces a 10/hour cap on `POST /api/v1/reports` ([`openspec/specs/reports/spec.md:170-192`](openspec/specs/reports/spec.md) — Requirement "Rate limit 10 submissions per hour per user", and the corresponding implementation in [`backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt`](backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt) with `DEFAULT_CAP = 10` at line 83). The canonical Layer 2 rate-limit table in [`docs/05-Implementation.md:1744`](docs/05-Implementation.md) prescribes **20/hour** for reports. The two values disagree by 2x. Both pre-date this change cycle.

**Specs at fault:** `openspec/specs/reports/spec.md` (or canonical docs — TBD)
**Code at fault:** `backend/ktor/src/main/kotlin/id/nearyou/app/moderation/ReportRateLimiter.kt` (`DEFAULT_CAP = 10`)
**Docs at fault:** `docs/05-Implementation.md:1744` Layer 2 table

**Impact (if shipped):** Low. The shipped code + spec are internally consistent (10/hour everywhere they appear together). Risk is mostly to future maintainers reading the canonical Layer 2 table and assuming the docs match the code. No user-facing impact unless ops decides to retighten or loosen the cap based on the wrong number.

**Ambiguity to resolve first:** Is the docs Layer 2 table over-stated (V9 deliberately shipped tighter, 10/hour, for early-launch anti-abuse), or did the spec drift below the canonical docs by accident? Both directions have been seen in this project's history (cf. PR #24 v10 notifications body_data audit — Direction Y resolved to "shipped code is correct; docs were over-specified"). A quick review of the V9 PR + design.md + commit history should clarify.

**Action items:**
- [ ] Triage X vs Y by re-reading the V9 `reports-v9` change's design.md / proposal.md for any explicit rationale on 10/hour vs 20/hour. Search the archived `openspec/changes/archive/2026-04-22-reports-v9/` directory.
- [ ] If X (docs are canonical, spec drifted low): amend the `reports` spec via a new OpenSpec change `reports-rate-limit-bump-to-20-per-hour`; bump `DEFAULT_CAP` and the spec scenarios from 10→20.
- [ ] If Y (spec is intentionally tighter, docs over-stated): amend `docs/05-Implementation.md:1744` table value from 20→10; no code/spec change.
- [ ] In either direction: the `like-rate-limit` change does NOT silently adjust either side — that's a separate ticket.

---

## tryacquirebykey-ip-derived-uuid-detekt-rule

**Discovered during:** `health-check-endpoints` `/next-change` Phase D round 1 review (security-and-invariant sub-agent lens).
**Status:** open

**Finding:** The `health-check-endpoints` change resolves the IP-keyed rate-limit convention by adding `RateLimiter.tryAcquireByKey(key, capacity, ttl)` to `rate-limit-infrastructure` and forbidding sentinel-UUID workarounds via spec scenario "tryAcquireByKey omits userId from telemetry" (which forbids the literal `00000000-0000-0000-0000-000000000000` UUID). However, a future maintainer could bypass this by passing `UUID.nameUUIDFromBytes(ip.toByteArray())` to `tryAcquire` — achieving the same effect (IP-axis bucket via the user-keyed method) without using the literal sentinel. The existing `RedisHashTagRule` Detekt rule checks the *key shape*, not whether `tryAcquire` is the right method for the call site's axis.

**Specs at fault:** None — the spec correctly forbids the literal sentinel.
**Code at fault:** None until a future regression introduces this pattern.
**Docs at fault:** None.

**Impact (if shipped):** Low until the regression occurs. If introduced, the IP-axis bucket would still function correctly (same Lua script), but: (a) telemetry would log a bogus user_id derived from IP; (b) the architectural intent (key-axis vs user-axis split) becomes invisible at the call site; (c) accumulating instances would silently re-introduce the tech debt this change explicitly avoided.

**Ambiguity to resolve first:** None. The fix is straightforward: a Detekt rule that fires on `tryAcquire(*, "{*ip:*}", ...)` — i.e., any `tryAcquire` whose key contains an `ip:` axis must use `tryAcquireByKey` instead.

**Action items:**
- [ ] After `health-check-endpoints` ships, add a Detekt rule `IpAxisMustUseTryAcquireByKeyRule` to `:lint:detekt-rules` that fires on calls to `RateLimiter.tryAcquire(...)` whose `key` argument matches the regex `\{[^}]*ip:`. Allow-list any legitimate use case (none expected). Wire into Detekt config + add unit tests.
- [ ] Standalone OpenSpec change `tryacquirebykey-ip-axis-lint` (under `rate-limit-infrastructure` capability MODIFIED) — small spec amendment + Detekt rule + unit tests.

---

## health-check-cloud-run-probe-terminology-docs-divergence

**Discovered during:** `health-check-endpoints` `/next-change` Phase B step 7 reconciliation pass — verifying Cloud Run probe flag terminology against canonical docs.
**Status:** open

**Finding:** [`docs/04-Architecture.md:166`](docs/04-Architecture.md) declares: *"Cloud Run deployed with readiness probe `/health/ready` and liveness probe `/health/live`."* This uses Kubernetes vocabulary, but Cloud Run does not implement a "readiness probe". The Cloud Run-native equivalents are `--startup-probe` (gates traffic during boot — fills the K8s readiness role) and `--liveness-probe` (continuous keepalive after startup). The `health-check-endpoints` spec aligns with Cloud Run-native vocabulary while noting the docs use K8s terminology — but the docs themselves should be amended for clarity.

**Specs at fault:** None — `health-check-endpoints/specs/health-check/spec.md` correctly uses Cloud Run vocabulary while citing the docs divergence.
**Code at fault:** None — the implementation will use the Cloud Run-native flags `--startup-probe` and `--liveness-probe` per `tasks.md` section 7.
**Docs at fault:** [`docs/04-Architecture.md:166`](docs/04-Architecture.md) uses K8s "readiness probe" wording.

**Impact (if shipped):** Low. The behavioral contract is correct — a Cloud Run startup probe targeting `/health/ready` does gate traffic during boot, which is what the docs describe semantically. Risk is to a future maintainer reading "readiness probe" in the docs and looking for a non-existent Cloud Run feature, or trying to use a `--readiness-probe` flag that doesn't exist.

**Ambiguity to resolve first:** None. The fix shape is clear: amend [`docs/04-Architecture.md:166`](docs/04-Architecture.md) from `Cloud Run deployed with readiness probe '/health/ready' and liveness probe '/health/live'.` to `Cloud Run deployed with startup probe '/health/ready' (the Cloud Run analog to a Kubernetes readiness probe — gates traffic during boot until the new revision is healthy) and liveness probe '/health/live' (continuous post-startup keepalive).` Docs-only change, no spec or code impact.

**Action items:**
- [ ] After `health-check-endpoints` ships, file a docs-only amendment to [`docs/04-Architecture.md:166`](docs/04-Architecture.md) clarifying the Cloud Run startup-probe vs K8s-readiness-probe distinction. Standalone docs PR or batched with whichever change next touches `04-Architecture.md`.

---

## like-rate-limit-sliding-window-vs-fixed-window-semantic

**Discovered during:** `like-rate-limit` section 8 testing (CI run 24936682400 caught scenario 18 failing when the wall clock was past WIB midnight; investigation revealed a fundamental spec-vs-impl mismatch).
**Status:** open

**Finding:** The `rate-limit-infrastructure` spec + `post-likes` spec describe the daily limiter using **fixed-window** language ("WIB day rollover restores the cap", "10/day Free with WIB stagger") but the implementation is **sliding-window with variable TTL**. Specifically: the Lua script in [`infra/redis/src/main/kotlin/.../RedisRateLimiter.kt`](infra/redis/src/main/kotlin/id/nearyou/app/infra/redis/RedisRateLimiter.kt) (and the matching [`InMemoryRateLimiter`](core/domain/src/main/kotlin/id/nearyou/app/core/domain/ratelimit/InMemoryRateLimiter.kt)) treats `ttl` as both the prune-older-than window AND the key TTL, where the call site passes `computeTTLToNextReset(userId)` — which varies as `now` approaches the next per-user reset moment.

The practical consequence:

- A user who clusters all 10 likes at hour 0 of their reset cycle has the cap "reset" only after those 10 entries age out — i.e., 24h after the OLDEST, not at the next per-user reset moment.
- The bucket key's `PEXPIRE` keeps getting refreshed on every admit, so the key never actually expires for an active user.
- "WIB midnight rollover" is approximated by the natural sliding-window aging, not by a hard reset at midnight.

For the canonical "10 per ~24h" use case with normal-cadence usage, the two semantics produce identical user-visible behavior. The mismatch surfaces only in edge cases (clustered usage at the start of a window, midnight-crossing tests).

**Specs at fault:** `openspec/specs/rate-limit-infrastructure/spec.md` § Redis-backed RateLimiter implementation (the spec describes sliding-window mechanics as if they implement fixed-window semantics) + `openspec/specs/post-likes/spec.md` § "Daily rate limit — 10/day Free, unlimited Premium, with WIB stagger" (the requirement language reads as fixed-window).
**Code at fault:** None — the implementation is internally consistent and matches the spec's mechanics. The mismatch is between the spec's user-facing language ("WIB rollover restores the cap") and the spec's technical mechanics (sliding-window pruning).
**Docs at fault:** `docs/05-Implementation.md` § Layer 2 / Rate Limiting Implementation describes the daily caps in fixed-window language too.

**Impact (if shipped):** Low for the canonical use case (steady-state usage). Edge cases:
- A user who hits the cap early in their day waits up to 24h for relief (vs the spec's implicit promise of "until next per-user midnight"). Worst-case ~12h discrepancy.
- The `LikeRateLimitTest` scenario 18 was rewritten in this change to test sliding-window aging (24h+1s past oldest) rather than midnight rollover — see commit fixing CI run 24936682400.

**Ambiguity to resolve first:** Is the user-facing promise "10/day with WIB stagger" intended as:
- **(α) Fixed-window per-day per-user**: each user has 10 likes from `00:00 WIB + offset` to `next 00:00 WIB + offset`. Bucket bulk-clears at midnight. Requires a different implementation (per-day bucket keys like `{scope:rate_like_day}:{user:U}:{day:YYYY-MM-DD}` with TTL = computeTTLToNextReset).
- **(β) Sliding-window with variable TTL**: each user has 10 likes within any rolling ~24h window, where the TTL stagger prevents thundering-herd at midnight. Current implementation. Update spec language to match.

**Action items:**
- [ ] Triage α vs β with product (likely β — the WIB stagger Phase-1-item-24 was always about preventing thundering herd, not about strict daily reset).
- [ ] If β (recommended): amend `openspec/specs/rate-limit-infrastructure/spec.md` + `openspec/specs/post-likes/spec.md` daily-cap requirement language from "WIB day rollover restores the cap" to "10 successful likes within any rolling ~24h window, with the per-user reset moment defining when an idle bucket is GC'd by Redis." Also amend `docs/05-Implementation.md` § Layer 2 wording. New OpenSpec change `rate-limit-spec-language-realignment` (docs-only).
- [ ] If α: implement true per-day bucket keys. Bigger change — new `rate-limit-fixed-window-per-day` change with a Lua-script revision + key-format change.
- [ ] In either direction: also clarify the `RateLimiter` interface contract — whether `ttl` is "key-expiry only" or "window-and-key-expiry". Currently it's both, which conflates two concepts.
