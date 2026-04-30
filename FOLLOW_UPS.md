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

## suspension-unban-worker-audit-log-after-phase-3.5

**Discovered during:** `suspension-unban-worker` `/next-change` Phase B step 7 reconciliation pass — verifying the canonical "Audit log inserted per unban" line at [`docs/05-Implementation.md:363`](docs/05-Implementation.md) against the current Flyway migration set.
**Status:** open

**Finding:** [`docs/05-Implementation.md:363`](docs/05-Implementation.md) prescribes an audit row per unban against `admin_actions_log`, but **the `admin_actions_log` and `admin_users` tables have not shipped yet**. They are deferred to the Phase 3.5 admin-panel build per the explicit comments in [`backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql:20,73,111`](backend/ktor/src/main/resources/db/migration/V9__reports_moderation.sql) (analogous columns `reports.reviewed_by` and `moderation_queue.reviewed_by` carry the marker *"FK to admin_users(id) ON DELETE SET NULL — deferred to the Phase 3.5 admin-users migration"*). The `suspension-unban-worker` change ships the worker + structured INFO logs in the interim and explicitly defers audit-row writes to a follow-up after Phase 3.5 lands the schema. See `suspension-unban-worker/design.md` § D3 for the full rationale.

**Specs at fault:** None — `openspec/specs/suspension-unban-worker/spec.md` (post-archive) will correctly require structured INFO logs and explicitly note the audit-row deferral.
**Code at fault:** None — the worker code is correct for Phase 1.
**Docs at fault:** None — [`docs/05-Implementation.md:363`](docs/05-Implementation.md) describes the eventual end-state. The doc does not need to be amended; it just isn't observed yet.

**Impact (if shipped):** Low. Cloud Logging carries the operational trail (default GCP retention, typically 30 days). The audit gap matters only if a dispute arises about an unban that happened outside the Cloud Logging window AND before Phase 3.5 ships — narrow combination given Phase 3.5 is on the near-term roadmap (Weeks 11–13 per `docs/08-Roadmap-Risk.md`). No back-fill of historical events is required when audit-row writes land — Cloud Logging holds the pre-Phase-3.5 record.

**Ambiguity to resolve first:** None. The migration is straightforward once `admin_users` + `admin_actions_log` exist:
- Seed a sentinel `system` row in `admin_users` (deterministic UUID derived from `UUID.nameUUIDFromBytes("system".toByteArray())`), no `password_hash`, no `totp_secret`, no role grant
- Add audit-row INSERT in `SuspensionUnbanWorker.execute()` with `admin_id = system_uuid`, `action_type = 'system_unban_applied'`, `target_type = 'user'`, `target_id = <user_id>`, `reason = 'suspension_elapsed'`, `before_state = {"is_banned": true, "suspended_until": "<ISO-8601>"}`, `after_state = {"is_banned": false, "suspended_until": null}`
- Wrap in the same transaction as the UPDATE (atomicity)
- Apply the same pattern to every other `/internal/*` worker that has shipped between this entry's creation and Phase 3.5 (privacy-flip, hard-delete cleanup, refresh-token cleanup, etc.)

**Action items:**
- [ ] When Phase 3.5 lands `admin_users` + `admin_actions_log` schema, file an OpenSpec change `system-actor-and-worker-audit-rows` that (a) seeds the `system` sentinel admin user and (b) adds audit-row writes to all `/internal/*` workers shipped to date — `suspension-unban-worker` plus whatever else has accumulated. The change MODIFIES the `suspension-unban-worker` capability spec to add the audit-row requirement (deleting or revising the structured-INFO-only requirement), and adds analogous requirements to each peer worker capability spec.
- [ ] In the same change: amend the `:backend:ktor` worker code to write audit rows in the same transaction as the user UPDATE.
- [ ] Add a regression test: failed audit INSERT rolls back the user UPDATE (atomicity).
- [ ] **Admin-login auth-bypass guard**: in the same Phase 3.5 admin-panel change (or whichever change first builds the admin-login flow), the auth handler MUST reject any `admin_users` row whose `password_hash IS NULL`. Without this guard, the seeded sentinel `system` row — created with no `password_hash` and no `totp_secret` so worker-driven audit rows can FK against it — would otherwise match `WHERE username = 'system' AND password_hash = ?` for any submitted password if the admin-login implementation is naive (e.g., uses string-equality on a NULL `password_hash` vs an empty submitted password). Add a regression test exercising the sentinel-system-row login attempt: `POST /admin/login {"username":"system","password":""}` → `401 Unauthorized`, audit-logged as a failed login. Include the SQL-level guard as a CHECK constraint on `admin_users` (or in the auth-handler query) so the protection is enforced at the data layer regardless of which auth path runs.
- [ ] Update `FOLLOW_UPS.md` to delete this entry once the change merges.

---

## extract-probe-error-classifier

**Discovered during:** `suspension-unban-worker` `/opsx:apply` Section 7 — the `UnbanWorkerRoute` handler-level 500 classifier (`SQLTimeoutException → "timeout"`, `SQLTransientConnectionException → "connection_refused"`, `SQLNonTransientConnectionException → "connection_refused"`, fallback message-substring matching, else `"unknown"`) is the second call site for "classify a thrown JDBC-shaped exception into one of `timeout|connection_refused|unknown` for a sanitized 500 response body". The first call site was the `health-check-endpoints` probe layer (PR #54), which inlined its own classifier rather than extracting a helper.

**Status:** open (rule of three — wait for a third call site before extracting).

**Finding:** Two distinct call sites now own a near-identical small classifier:

1. `health-check-endpoints` (`backend/ktor/src/main/kotlin/id/nearyou/app/health/JdbcPostgresProbe.kt` and the analogous Redis/Supabase probes) — classifies probe-level failures into `ProbeError` constants (`TIMEOUT`, `CONNECTION_REFUSED`, `UNKNOWN`, plus `DNS_FAILURE` and `TLS_FAILURE` for network-layer errors not relevant to JDBC).
2. `suspension-unban-worker` (`backend/ktor/src/main/kotlin/id/nearyou/app/admin/UnbanWorkerRoute.kt` `classifyHandlerError`) — classifies handler-level failures into the response vocabulary (`timeout`, `connection_refused`, `unknown`).

The two vocabularies overlap on `timeout` / `connection_refused` / `unknown` and could share a small helper that returns one of those three classifications from a thrown JDBC-shaped exception. A shared helper would (a) eliminate duplicated when-branches, (b) ensure consistent classification across call sites (e.g., HikariCP `Connection is not available` substring matching is currently inlined in this change but could naturally apply to the probe layer too), and (c) make adding a new classification (e.g., `pool_exhausted`) a single-edit operation.

**Specs at fault:** None.
**Code at fault:** `backend/ktor/src/main/kotlin/id/nearyou/app/admin/UnbanWorkerRoute.kt` (`classifyHandlerError`) + the analogous probe-level helper in `backend/ktor/src/main/kotlin/id/nearyou/app/health/`.
**Docs at fault:** None.

**Impact (if shipped):** None today — both call sites work correctly. Risk is divergence between the two classifiers as future maintainers bug-fix one without the other.

**Trigger to act:** rule of three — extract when a third call site appears. Likely candidates: future `/internal/*` workers (privacy-flip, hard-delete, refresh-token-cleanup, fcm-cleanup, image-lifecycle, notifications-purge, moderation-archival per `proposal.md`) — each carries a sanitized 500 path with the same vocabulary.

**Migration sketch when triggered:** add `backend/ktor/src/main/kotlin/id/nearyou/app/common/JdbcErrorClassifier.kt` exporting `fun classifyJdbcError(e: Throwable): String` returning one of `timeout|connection_refused|unknown`. Both existing call sites delegate to it. Probe-layer call site additionally maps the network-layer cases (DNS, TLS) inline since those are probe-only concerns.

**Action items:**
- [ ] When the third call site lands: extract `JdbcErrorClassifier` per the sketch above.
- [ ] Refactor `UnbanWorkerRoute.classifyHandlerError` to delegate to it.
- [ ] Refactor `JdbcPostgresProbe` (and any sibling probes) to delegate to it.
- [ ] Delete this entry once the extraction lands.

---

## fcm-tokens-schema-check-doc-amendment

**Discovered during:** `fcm-token-registration` `/next-change` Phase D round 1 (security-and-invariant sub-agent flagged that the canonical schema lacks DB-level CHECKs on `token` and `app_version` length, recommending defense-in-depth additions).
**Status:** open

**Finding:** The `fcm-token-registration` change ships with two additive DB-level CHECK constraints (`CHECK (char_length(token) BETWEEN 1 AND 4096)` and `CHECK (app_version IS NULL OR char_length(app_version) <= 64)`) on `user_fcm_tokens` per design D9. These constraints are NOT in the canonical schema at [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md). The canonical docs are not wrong — they specify the minimum schema; this change adds belt-and-suspenders that the security review rated as worth-having now while we're touching the schema. The deviation is documented in proposal § What Changes (marked "additive"), spec § Schema (with the deviation note), and design § Reconciliation notes (item 2). The doc still needs to be updated to match the as-shipped schema so a future maintainer reading [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md) doesn't conclude the CHECKs are an undocumented production deviation.

**Specs at fault:** None — `openspec/specs/fcm-token-registration/spec.md` (post-archive) will correctly carry the CHECKs.
**Code at fault:** None — V14 migration carries the CHECKs.
**Docs at fault:** [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md) (FCM Token Registration § Schema block — needs the two CHECKs added).

**Impact (if shipped):** Low. The DB CHECKs work correctly as shipped; risk is to a future maintainer reading the canonical docs and finding the CHECKs missing → spending time looking for them or assuming the production schema diverges. Same-shape fix as the v10 notifications `body_data` doc amendment in PR #24.

**Ambiguity to resolve first:** None. The fix shape is clear: insert two CHECK clauses inside the canonical `CREATE TABLE user_fcm_tokens (...)` block at [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md), and add a one-sentence note explaining the defense-in-depth rationale (mirror the spec's framing).

**Action items:**
- [ ] After `fcm-token-registration` ships, file a docs-only amendment to [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md) adding the two CHECK constraints to the canonical schema block. Standalone docs PR or batched with the next OpenSpec change that touches the FCM section.

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

## cloud-run-traffic-pinning-after-failed-revisions

**Discovered during:** `health-check-endpoints` `/opsx:apply` Section 11 negative-smoke (tasks 11.5/11.6) — observed when recovering from the broken-Redis revision sequence.
**Status:** open

**Finding:** When a sequence of Cloud Run revisions fails the startup probe (e.g., `nearyou-backend-staging-00050-cwf` → `00051-bxt` → `00052-tpc` during the 11.5 negative smoke), Cloud Run's traffic-routing config can become **pinned** to the last-known-good revision (`00049-bsx` in this case) rather than tracking `LATEST` automatically. Subsequent successful deploys (e.g., the recovery deploy `00053-n6v`) create the new revision but traffic STAYS on the pinned revision until explicitly released.

The fix is one command — `gcloud run services update-traffic <service> --region=<region> --to-latest` — but the gotcha isn't surfaced anywhere in the project's docs or runbooks. A future operator hitting a recovery scenario would see "deploy succeeded, but `/health/ready` from the new revision is unreachable" and might spend time debugging the wrong layer.

Reproduction sequence in this case:
1. `gcloud run services update --update-secrets=REDIS_URL=staging-redis-url-broken-test:latest` → revision `00051-bxt` created with broken Redis → startup probe fails → Cloud Run keeps traffic on `00049-bsx` (correct gate behavior; this is what 11.5 verifies).
2. `gcloud run services update --update-secrets=REDIS_URL=staging-redis-url:latest` → revision `00052-tpc` created with correct Redis → ... but Cloud Run had already pinned the traffic config to `00049-bsx` from step 1, so `00052-tpc` doesn't auto-promote even though its config is now valid.
3. `gh workflow run deploy-staging.yml` → revision `00053-n6v` created with new image + correct config → still doesn't auto-promote (traffic config still pinned).
4. `gcloud run services update-traffic --to-latest` → traffic config released → `00053-n6v` becomes serving.

**Specs at fault:** None.
**Code at fault:** None — this is a Cloud Run platform behavior, not an app behavior.
**Docs at fault:** `docs/07-Operations.md` § Deployment runbook (or wherever the staging-deploy ops live) does not mention the pinning failure mode.

**Impact (if shipped):** Low. The misbehavior is visible (traffic stays on old revision) and the fix is a one-liner. Risk is operator confusion during a real outage recovery — could add 10-15 minutes to MTTR while the operator figures out why a "successful" deploy isn't actually serving.

**Action items:**
- [ ] Amend `docs/07-Operations.md` § Deployment runbook (or create a new § Recovery from failed-revision sequence) with the failure-mode description + the `update-traffic --to-latest` recovery command. Cite the `health-check-endpoints` 11.5 smoke as the precedent.
- [ ] Optionally: bake `gcloud run services update-traffic --to-latest` into `deploy-staging.yml` AFTER the `gcloud run deploy` step, as a defensive belt-and-suspenders. Trade-off: extra step in every deploy (slow path) vs. eliminating the gotcha class entirely. Lean towards: amendment first, codification only if the gotcha recurs.

---

## gcp-secret-manager-iam-grant-on-new-slot

**Discovered during:** `health-check-endpoints` `/opsx:apply` Section 11 first deploy attempt (task 11.1) — Cloud Run revision creation failed with `Permission denied on secret: projects/.../secrets/staging-supabase-url/versions/latest for Revision service account 27815942904-compute@developer.gserviceaccount.com`.
**Status:** open

**Finding:** When a new slot is added to GCP Secret Manager (e.g., `staging-supabase-url` in this change), the Cloud Run runtime service account does NOT automatically inherit the IAM bindings of sibling slots. The new slot requires an explicit `roles/secretmanager.secretAccessor` grant — `gcloud secrets add-iam-policy-binding <slot> --member=serviceAccount:<sa> --role=roles/secretmanager.secretAccessor`. This is `gcloud`'s default least-privilege model and is correct security posture, but it's a process gap that surfaces as a confusing "first deploy fails, second works" pattern.

The existing staging runbook in `docs/07-Operations.md` covers secret VALUE rotation (`gcloud secrets versions add ...`) but does NOT cover NEW slot creation IAM. This is the second time the gap surfaced (first was during the original staging environment buildout — slots were bound manually one-off; the runbook was never updated).

**Specs at fault:** None.
**Code at fault:** None.
**Docs at fault:** `docs/07-Operations.md` § Secret rotation runbook — missing "new slot creation" subsection.

**Impact (if shipped):** Low. Per-deploy-attempt time cost is small (one extra failed run + IAM grant + retry = ~10 min). Risk is mostly: future engineer adds a new slot, deploy fails, has to context-switch to figure out the IAM model.

**Action items:**
- [ ] Amend `docs/07-Operations.md` § Secret rotation runbook with a new subsection "Creating a new staging/prod secret slot": cite the `gcloud secrets create <slot>` + the mandatory `gcloud secrets add-iam-policy-binding <slot> --member=serviceAccount:<runtime-sa> --role=roles/secretmanager.secretAccessor` step, with the runtime SA name documented per environment.
- [ ] Optionally: Terraform-wrap the secret-creation pattern so the IAM grant is declarative + can't drift. Out of scope for the runbook fix; flag as a Terraform-introduction follow-up if the project ever grows a Terraform module.

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

## extract-staging-psql-helper-script

**Discovered during:** `fcm-token-registration` `/opsx:apply` Section 8 (8.6/8.7/8.11 SQL verify + cleanup) — surfaced two related gotchas in `gcloud run jobs create --args` parsing that took 4 iterations to land cleanly, even though the same script-shape already exists in `dev/scripts/promote-staging-user.sh`.

**Status:** open

**Finding:** `gcloud run jobs create --args=VALUE` parses VALUE as a comma-separated list by default. For a Postgres psql job that needs to pass:

1. **A multi-statement SQL string** containing commas (column lists, `IN (...)` lists), AND
2. **A Postgres DSN** of the form `postgresql://user@host:port/db?sslmode=require` (which has `@`),

…the default comma parser splits the SQL on every column-list comma, and the natural escape-via-custom-delimiter approach `--args=^@^VALUE` collides with the `@` inside the DSN (between user and host).

The fix is the gcloud custom-delimiter syntax: `--args=^X^VALUE` declares `X` as the delimiter for THIS specific arg. The chosen delimiter must NOT appear in any of the values. For SQL + DSN, `|` (pipe) is safe — it doesn't appear in column lists, identifiers, or the URL form. So:

```bash
gcloud run jobs create "$JOB" --project="$PROJECT" \
    --region="$REGION" \
    --image=postgres:16-alpine \
    --command=psql \
    --args="^|^$DSN" \
    --args="^|^-X" \
    --args="^|^-c" \
    --args="^|^$SQL" \
    --set-secrets="PGPASSWORD=staging-db-password:latest" \
    ...
```

This pattern repeats across `dev/scripts/promote-staging-user.sh` (existing, but only warns about commas — not about the `@` collision), and the in-conversation Cloud Run psql job for `fcm-token-registration` smoke verify + cleanup. A third call site is statistically likely as more OpenSpec changes need staging Supabase access for verify/cleanup steps.

**Specs at fault:** None.
**Code at fault:** None today — both call sites work. The risk is the 4-iteration debugging loop being repeated by the next operator who writes a Cloud Run psql job from scratch.
**Docs at fault:** `dev/scripts/promote-staging-user.sh` lines 109-112 inline comment warns about the comma issue but does NOT recommend the `^|^` custom-delimiter fix — readers who hit the issue will discover the fix on their own (as I did, ~30min of debugging).

**Impact (if shipped):** Zero today. Pure tech-debt entry. If left unaddressed, the third operator hitting this footgun will spend the same ~30min debugging that has already been spent twice. By the third occurrence, extracting a `dev/scripts/run-staging-psql.sh` helper has positive ROI.

**Trigger to act:** any of the following events makes this entry active:
- A third Cloud Run psql job appears in this codebase (rule of three).
- The next operator hits the `--args` comma or `@`-delimiter footgun in any new script.
- A smoke step for a future OpenSpec change needs staging Supabase access for verify/cleanup that doesn't fit the existing `promote-staging-user.sh` shape.

**Migration sketch when triggered:** extract `dev/scripts/run-staging-psql.sh`:

```bash
#!/usr/bin/env bash
# Run a one-shot psql command against the staging Supabase database via a
# Cloud Run Job. Handles secret resolution, DSN construction, the
# gcloud --args ^|^ delimiter dance, execution, log retrieval, and cleanup.
#
# Usage:
#   echo "SELECT 1" | dev/scripts/run-staging-psql.sh [--keep-job]
#   dev/scripts/run-staging-psql.sh --sql-file path/to/queries.sql
#
# Output: psql stdout (clean, no Cloud Run wrapper noise) on stdout;
# diagnostics on stderr.
set -euo pipefail
PROJECT=nearyou-staging
REGION=asia-southeast2
JOB="staging-psql-$(date -u +%Y%m%dT%H%M%SZ)-$$"
# ... resolve DSN, ^|^ args, create + execute + tail logs + delete job ...
```

Then `promote-staging-user.sh` becomes a thin wrapper that pipes its UPDATE + verifier SELECT into this helper. Future smoke scripts that need SQL access just `echo "$SQL" | dev/scripts/run-staging-psql.sh`.

**Action items:**
- [ ] When the trigger fires: extract `dev/scripts/run-staging-psql.sh` per the sketch above.
- [ ] Either with the extraction OR as a tiny standalone PR before then: expand the inline comment in `dev/scripts/promote-staging-user.sh` (around lines 109-112) to recommend `^|^` custom delimiter for any new caller of the same pattern, with a one-line example. ~5-line edit, immediately useful at moment-of-need even before extraction lands.
- [ ] When extracting: refactor `dev/scripts/promote-staging-user.sh` to use the new helper (validates that the helper shape covers the existing call site).

## ci-paths-filter-switch-to-dorny

**Discovered during:** `ci/per-push-docs-skip` (PR #56) — design conversation about whether the hand-rolled `git diff + grep` filter is the right long-term shape.

**Status:** open

**Finding:** `.github/workflows/ci.yml` currently uses a hand-rolled `changes` job that runs `git diff --name-only "$BEFORE" "$AFTER"` and greps against a docs-only allowlist (`docs/`, `**/*.md`, `.gitignore`, `LICENSE`). This works fine for the single "is this push docs-only?" axis. But it does NOT scale gracefully if we ever need multiple filter axes — e.g.:

- "Skip Android tests when only backend changed" once a `:android:app` test lane lands (Phase 3 mobile work).
- "Skip backend tests when only mobile changed."
- "Skip migrate-supabase-parity when no Flyway migration changed" (only `backend/ktor/src/main/resources/db/migration/**`).
- "Run a separate iOS lane only when iOS-specific code changed" once iOS work begins.

For multi-axis filtering, [`dorny/paths-filter@v3`](https://github.com/dorny/paths-filter) is the ecosystem-standard. Declarative YAML filter rules, battle-tested across 100k+ repos, edge cases (synchronize / push / first-push / merge-commits) handled out of the box.

**Specs at fault:** None.
**Code at fault:** `.github/workflows/ci.yml` — `changes` job's hand-rolled bash filter. Adequate for single-axis docs-only skip; not adequate for multi-axis.
**Docs at fault:** None.

**Impact (if shipped):** None today. The hand-rolled filter handles the docs-only case correctly. The risk is that as the build matrix grows (Android + iOS + backend), adding more axes to the bash grep gets brittle, and someone may end up running android tests on a backend-only push (or vice versa) — wasted runner time, slower PR feedback.

**Trigger to act:** any of the following events makes this entry active:
- Adding a second filter axis to the `changes` job (e.g., a third allowlist for android-only paths).
- Adding a second `needs: changes` heavy lane that has a meaningfully different relevant-paths set than the existing lint/test/migrate trio.
- The `changes` step bash exceeds ~30 lines or starts needing nested logic.

**Migration sketch:** when triggered, replace the inline bash filter step with:

```yaml
changes:
  runs-on: ubuntu-latest
  outputs:
    backend: ${{ steps.filter.outputs.backend }}
    mobile: ${{ steps.filter.outputs.mobile }}
    migration: ${{ steps.filter.outputs.migration }}
    docs-only: ${{ steps.filter.outputs.docs-only }}
  steps:
    - uses: actions/checkout@v4
    - uses: dorny/paths-filter@v3
      id: filter
      with:
        filters: |
          backend:
            - 'backend/**'
            - 'core/**'
            - 'shared/**'
            - 'infra/**'
            - 'lint/**'
            - 'gradle/**'
            - '*.gradle.kts'
            - 'build-logic/**'
          mobile:
            - 'mobile/**'
            - 'shared/**'
          migration:
            - 'backend/ktor/src/main/resources/db/migration/**'
            - 'dev/supabase-parity-init.sql'
          docs-only:
            - 'docs/**'
            - '**/*.md'
            - '.gitignore'
            - 'LICENSE'
```

Then heavy jobs reference `needs.changes.outputs.backend == 'true'` etc. The current docs-only axis maps cleanly to `docs-only != 'true'` for "should run heavy jobs."

**Action items:**
- [ ] Wait for one of the trigger events above. Don't migrate preemptively — the hand-rolled filter is fine for single-axis docs-only.
- [ ] When migrating: pin to a specific dorny/paths-filter SHA (not `@v3` floating tag) for supply-chain hygiene.
- [ ] When migrating: keep the inline workflow-level `paths-ignore` as the outermost gate — it still saves the `changes` job runner cost on all-docs PRs.

## fcm-payload-structural-tests

**Discovered during:** `fcm-push-dispatch` apply, task 6.2 / 6.3
**Status:** open

**Finding:** The Firebase Admin SDK's `Message`, `AndroidConfig`, `ApnsConfig`, and `Aps` classes have package-private fields (Java `@Key` annotations on private members) and provide no public read-side accessor surface. The unit-test suite at `infra/fcm/src/test/kotlin/.../PayloadBuildersTest.kt` therefore cannot make brittle structural assertions like "the Android payload has no notification block" or "iOS payload sets `aps.mutable-content = true`" without relying on Java reflection that would drift on SDK minor bumps. Spec scenarios "Android payload has no notification block", "Android payload sets priority HIGH", "iOS payload sets aps.mutableContent = true", and "iOS payload carries body_full as JSON-stringified body_data" are currently smoke-tested only (the builder is invoked and produces a non-null Message).

**Specs at fault:** `openspec/specs/fcm-push-dispatch/spec.md` (the four scenarios listed above)
**Code at fault:** `infra/fcm/src/test/kotlin/id/nearyou/app/infra/fcm/PayloadBuildersTest.kt` (smoke-only coverage; deeper structural assertions deferred via file-level KDoc note)
**Docs at fault:** none

**Impact (if shipped):** A regression in `buildAndroidMessage` or `buildIosMessage` that produces structurally-wrong-but-valid output (e.g., a notification block accidentally added to the Android payload, or `mutable-content` flipped off) would NOT be caught by the unit-test suite. Detection would happen at integration / staging smoke time, after the wrong-shaped push has already been delivered to a real device. Risk is bounded because both builders are short, declarative wrappers around the SDK Builder calls — visual review catches most regressions — but defense-in-depth coverage is missing.

**Action items:**
- [ ] Add an integration test in `:backend:ktor` that initializes a Firebase test app via a synthetic service-account JSON AND uses `FirebaseMessaging.send(msg, dryRun=true)` to validate payload shape. The dry-run path runs full SDK validation (rejects oversized payloads, rejects malformed structures) without dispatching to FCM.
- [ ] Alternatively, write a custom `MessageInspector` helper that uses the SDK's internal Jackson/Gson serialization (via `MessagingProvider` or equivalent) to dump a Message to JSON for structural assertions. Requires identifying the SDK's preferred public-API serialization path.

## fcm-shutdown-drain-deterministic-tests

**Discovered during:** `fcm-push-dispatch` apply, tasks 7.7 + 7.7.a
**Status:** open

**Finding:** Tasks 7.7 (shutdown drains in-flight up to 5s) and 7.7.a (boundary-case 5.0s cancel) require a virtual-time test dispatcher (e.g., `TestScheduler` from `kotlinx-coroutines-test`) for deterministic timing. That test dependency is not currently on the test classpath of `:infra:fcm` or `:backend:ktor`. The shutdown logic itself (`FcmDispatcherScope.shutdown(drainMillis)` calling `cancelAndJoin` inside `withTimeoutOrNull`) is exercised only by the §10 staging smoke test indirectly (graceful Cloud Run revision rollover). Task 7.8 (dispatch-after-shutdown WARN) IS covered deterministically.

**Specs at fault:** `openspec/specs/fcm-push-dispatch/spec.md` § "Dispatcher coroutine scope SHALL drain on JVM shutdown" — scenarios "Shutdown hook drains in-flight dispatches up to 5 seconds", "Dispatches exceeding the 5-second drain are abandoned", "Shutdown 5-second boundary is deterministic"
**Code at fault:** none — `FcmDispatcherScope.shutdown` is correct; it's the test surface that needs deepening
**Docs at fault:** none

**Impact (if shipped):** A regression in `FcmDispatcherScope.shutdown` (e.g., the `withTimeoutOrNull` budget being miscalculated, or `cancelAndJoin` being swapped for plain `cancel`) would not be caught by any test in the suite. Detection would require manual smoke against a real shutdown event.

**Action items:**
- [ ] Add `kotlinx-coroutines-test` to the libs.versions.toml + `:infra:fcm` test deps.
- [ ] Write `FcmDispatcherScopeShutdownTest` exercising the 1.0s-completes / 10.0s-cancels / 5.0s-boundary scenarios via `runTest` + `TestScheduler.advanceTimeBy`.

## fcm-end-to-end-composite-test

**Discovered during:** `fcm-push-dispatch` apply, tasks 7.1 / 7.2 / 7.3 / 7.9
**Status:** open

**Finding:** The end-to-end integration tests for the composite dispatcher path (post_liked emit → mocked `FirebaseMessaging.send(...)` invoked once per active token AND on-send-prune contract via real DB) are not present. The functionality IS covered by:
  - `:infra:fcm/FcmDispatcherTest` covering all FcmDispatcher behaviours (mocked sender + reader; tasks 6.4.1 — 6.4.16).
  - `:backend:ktor/JdbcUserFcmTokenReaderTest` covering the SQL contract end-to-end against real Postgres (race-guard predicate, peer preservation).
  - `:backend:ktor/JdbcActorUsernameLookupTest` covering the shadow-ban MASK contract against real Postgres.
  - `:backend:ktor/FcmDispatchAfterShutdownTest` covering the shutdown-WARN path.
  - `:backend:ktor/FcmDispatchStructuralTest` covering the no-Firebase-import contract for `NotificationService` + emit-site services.

What's missing: a test that boots `Application.module()` with a test-only override binding `FcmAndInAppDispatcher` over a mocked `FirebaseMessaging`, drives a `POST /api/v1/posts/.../like` (or equivalent emit), AND asserts the mocked send was invoked exactly once with the registered token. The composition-level "all the parts wired together correctly" assertion is therefore deferred to the §10 staging smoke.

**Specs at fault:** `openspec/specs/fcm-push-dispatch/spec.md` (scenarios "Recipient with multiple tokens triggers parallel dispatch" composition-level, "Dispatcher invocation count is exactly once per notifications row")
**Code at fault:** none — production wiring is correct; the test surface is missing.
**Docs at fault:** none

**Impact (if shipped):** A wiring bug in `Application.module()` (e.g., the composite getting bound twice, or `notificationDispatcher` accidentally still being `inAppDispatcher` in non-test profile) would not be caught by any test. Detection would happen at staging smoke (which IS in scope of §10) — that's the safety net, but it requires a manual deploy to detect.

**Ambiguity to resolve first:** does the test-only override module set `KTOR_RSA_PRIVATE_KEY` etc. via `MapApplicationConfig`, or does it run `module()` with the prod profile minus the secret read? The former is cleaner.

**Action items:**
- [ ] Add an integration test class `FcmCompositeWiringTest` in `:backend:ktor` that uses `testApplication { application { module() } }` with a Koin override module binding `NotificationDispatcher` to a constructed `FcmAndInAppDispatcher(mockFcm, NoopNotificationDispatcher())`. Drive a like → assert the mock was invoked once per token.
- [ ] If the override approach proves brittle, fall back to a directly-constructed test surface that bypasses `Application.module()` and exercises only the composite + emit-site service wiring.

## fcm-firebase-import-boundary-detekt-rule

**Discovered during:** `fcm-push-dispatch` apply, design D16 acknowledgement
**Status:** open

**Finding:** Per `openspec/changes/fcm-push-dispatch/design.md` D16, the `:backend:ktor` module is permitted to import `com.google.firebase.*` strictly for DI-binding signatures, but this rule is currently enforced only at code-review time. As of this change, ZERO `:backend:ktor` files actually import any `com.google.firebase.*` symbol (the `buildFcmComposite(...)` factory in `:infra:fcm` hides the SDK types behind a non-Firebase return shape, narrowing the boundary further than D16 admits). A future contributor could regress this by importing `FirebaseMessaging` into a non-DI file without triggering any CI rule. The static-analysis test `FcmDispatchStructuralTest` covers `NotificationService` + the four emit-site services but not the rest of `:backend:ktor`.

**Specs at fault:** `openspec/specs/fcm-push-dispatch/spec.md` § "`:backend:ktor` Firebase imports are scoped to DI-binding files only" (currently a SHOULD, not enforced)
**Code at fault:** none — production code respects the boundary
**Docs at fault:** none

**Impact (if shipped):** Long-term boundary erosion. A `FirebaseMessaging` import sneaking into a route handler or service would couple the request-path code to a vendor SDK, violating the `CLAUDE.md` "No vendor SDK import outside `:infra:*`" invariant — but only at code-review-grade, not CI-grade.

**Action items:**
- [ ] Add a Detekt rule `FirebaseImportBoundaryRule` in `:lint:detekt-rules` that allowlists `*Module.kt` filenames OR a `@FcmDiBinding` KtAnnotation. Modeled on `RawXForwardedForRule` (which has a similarly tight allowlist).
- [ ] Update spec scenario "`:backend:ktor` Firebase imports are scoped to DI-binding files only" from SHOULD to SHALL once the rule is in place.

## chat-realtime-broadcast-publish

**Discovered during:** `chat-foundation` apply, scope-deferral discipline (proposal § Out of scope)
**Status:** open

**Finding:** `chat-foundation` ships the data plane (schema + REST endpoints) for 1:1 chat but explicitly defers the Supabase Realtime broadcast layer per [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Phase 2 #9. Without broadcast publish from Ktor, the user-visible product is "1:1 chat with REST polling" — the data plane is correct and shippable for testing, but it is not a real-time chat experience until the publish layer lands. The V2-drafted RLS policy on `realtime.messages` is now installed correctly by V15 (subscriber-side authorization works end-to-end), and the realtime token endpoint shipped in `auth-realtime` is ready, so the only missing piece is the Ktor-side publish that emits a Supabase Realtime broadcast on every successful `chat_messages` INSERT.

**Specs at fault:** None — `openspec/specs/chat-conversations/spec.md` (post-archive) deliberately scopes to REST.
**Code at fault:** None — REST data plane is complete and correct.
**Docs at fault:** None — [`docs/04-Architecture.md`](docs/04-Architecture.md) and [`docs/05-Implementation.md`](docs/05-Implementation.md) describe the realtime layer; `chat-foundation` simply doesn't implement it yet.

**Impact (if shipped):** No regression risk — the REST data plane works in isolation. Until broadcast publish ships, clients fall back to REST polling for new messages, which is acceptable for staging-side testing but not for production traffic. The fcm-push-dispatch composite (PR #60) currently has no `chat_message` emit-site to push from; that emit-site is tracked separately in `chat-message-notification-emit-sites`.

**Ambiguity to resolve first:** Publish strategy — Ktor calls Supabase REST `realtime.broadcast` after the chat_messages INSERT commits, OR Ktor uses a Postgres LISTEN/NOTIFY relay that Supabase realtime picks up automatically. The canonical pattern in [`docs/05-Implementation.md` § Chat Flow](docs/05-Implementation.md) is the post-commit REST publish; confirm that hasn't changed before implementing.

**Action items:**
- [ ] File OpenSpec change `chat-realtime-broadcast` after `chat-foundation` archives. Add an ADDED capability `chat-realtime-broadcast` covering the post-commit publish, the publish-side shadow-ban filter (see `chat-realtime-broadcast-publish-side-shadow-ban-filter`), and the publish-failure-fallback strategy (logged + retry-batch via background job; no client-visible error).
- [ ] Wire the publish-after-commit hook in `ChatRepository.sendMessage` (or pull it up to a service-level transaction-aware hook) so the publish runs only after successful commit.
- [ ] Cover the publish path with an integration test against a real Supabase realtime container (or staging integration if a containerized Supabase realtime image isn't available).
- [ ] Update `FOLLOW_UPS.md` to delete this entry once the change merges.

## chat-rate-limit-50-per-day

**Discovered during:** `chat-foundation` apply, scope-deferral discipline
**Status:** open

**Finding:** `chat-foundation` does NOT enforce a daily send-rate cap on `POST /api/v1/chat/{id}/messages`. Per [`docs/02-Product.md:318`](docs/02-Product.md), Free-tier users SHALL be capped at 50 messages/day; Premium SHALL be unlimited. The shape is canonical and matches the existing like-rate-limit + reply-rate-limit changes (limiter BEFORE body parse, per-user WIB-midnight TTL via `computeTTLToNextReset(userId)`, `{scope:rate_chat_day}:{user:U}` Redis key, RemoteConfig override `premium_chat_cap_override` for ops dial). The `rate-limit-infrastructure` capability is already shared across like + reply, so the chat extension is mostly route-layer wiring + a service tryRateLimit method.

**Specs at fault:** None — `chat-foundation` is correctly scoped to data plane only.
**Code at fault:** `backend/ktor/src/main/kotlin/id/nearyou/app/chat/ChatRoutes.kt` (POST handler does not gate on rate-limiter).
**Docs at fault:** None — `docs/02-Product.md:318` is canonical.

**Impact (if shipped):** Without a daily cap, a Free user can spam an arbitrary number of `POST /api/v1/chat/{id}/messages` calls per day. The data plane has correct length-guard (2000-char) and block enforcement, so spam payloads are bounded per-message; the cap is a fairness + abuse-prevention layer, not a correctness layer. Acceptable to defer for staging-side testing; MUST land before any beta with external users.

**Ambiguity to resolve first:** None — same shape as like-rate-limit + reply-rate-limit.

**Action items:**
- [ ] File OpenSpec change `chat-rate-limit` after `chat-foundation` archives. ADD a `chat-rate-limit` capability following the like-rate-limit + reply-rate-limit shape: limiter BEFORE body parse, Free-only daily cap (default 50, override via `premium_chat_cap_override`), `{scope:rate_chat_day}:{user:U}` Redis key, per-user WIB-midnight TTL.
- [ ] Wire the limiter into `ChatRoutes.kt` POST handler at the same ordering position as `ReplyRoutes.kt` uses (auth → UUID validation → limiter → body parse → content guard → repo).
- [ ] Add `ChatRateLimitTest` mirroring `ReplyRateLimitTest` shape.
- [ ] Update `FOLLOW_UPS.md` to delete this entry once the change merges.

## chat-message-notification-emit-sites

**Discovered during:** `chat-foundation` apply, fcm-push-dispatch cross-reference
**Status:** open (hard prerequisite is `chat-realtime-broadcast-publish` landing first)

**Finding:** The fcm-push-dispatch composite shipped in PR #60 wires the dispatcher with all four current emit-site services (post_liked, post_replied, follow, etc.) but has NO `chat_message` or `chat_message_redacted` emit-site to push from. `chat-foundation` deliberately defers adding these emit-sites because (a) without the realtime broadcast layer there's no async surface to push from, and (b) the canonical Phase 2 #11 per-conversation FCM push batching depends on the broadcast layer being in place. Once `chat-realtime-broadcast-publish` lands, the chat send path will have a natural emit-site (after the post-commit broadcast publish) where `notifications` table emits + FCM dispatch can be added. The fcm-push-dispatch dispatcher will pick them up automatically once emitted.

**Specs at fault:** None — `chat-foundation` is correctly scoped.
**Code at fault:** None — pre-existing emit-sites are correct; chat emit-sites are net-new and live in the future change.
**Docs at fault:** None.

**Impact (if shipped):** No FCM push for chat until both this and `chat-realtime-broadcast-publish` land. In-app notifications via the existing `notifications` table also won't fire for chat-message events until then. Acceptable for the chat-foundation cut (staging testing only); MUST land before any user-visible chat beta.

**Ambiguity to resolve first:** Notification body shape for chat — what fields land in `notifications.body_data`? Sender username + content excerpt (truncated to 80 code points like reply excerpt)? Or sender + conversation_id only, with the client fetching content on tap? Decide in the change's design.md.

**Action items:**
- [ ] After `chat-realtime-broadcast-publish` lands, file OpenSpec change `chat-message-notification-emit-sites`. ADD `chat_message` and `chat_message_redacted` notification types in `core/data/.../NotificationType.kt`. Wire the `chat_message` emit in the chat-message send transaction (mirror the `post_replied` + `post_liked` emit-site pattern in ReplyService / LikeService).
- [ ] Add the per-conversation FCM push batching design (Phase 2 #11) — this may be a separate change depending on scope.
- [ ] Cover the emit + dispatch end-to-end against a real Postgres + mocked FCM (mirror `JdbcUserFcmTokenReaderTest` shape).
- [ ] Update `FOLLOW_UPS.md` to delete this entry once the change merges.

## chat-realtime-broadcast-publish-side-shadow-ban-filter

**Discovered during:** `chat-foundation` apply, design § D9 reconciliation
**Status:** open (hard prerequisite for `chat-realtime-broadcast-publish`)

**Finding:** V15 installs the `realtime.messages` RLS policy WITHOUT V2's subscriber-side `is_shadow_banned` clause (per `chat-foundation/design.md` § D9 + `chat-conversations` spec § Requirement: Realtime RLS policy installed with shadow-ban-aware subscriber semantics). The subscriber-side reconciliation is COMPLETE — shadow-banned users retain their own realtime view, consistent with the invisible-actor model. **What remains:** the publish-side filter. When `chat-realtime-broadcast-publish` ships, the publish path SHALL skip emit when `sender.is_shadow_banned = TRUE`, mirroring the read-path inline filter in `GET /api/v1/chat/{id}/messages` per `chat-conversations` spec § Requirement: List-messages endpoint. Without this filter, shadow-banned senders' messages would broadcast normally to non-banned recipients via WSS while being filtered from the REST `GET /messages` path — REST/WSS asymmetry that defeats the invisible-actor model on the realtime surface.

**Specs at fault:** Future `chat-realtime-broadcast` capability (does not exist yet) — must include the publish-side filter requirement.
**Code at fault:** Future `chat-realtime-broadcast-publish` change must implement the filter.
**Docs at fault:** None — `chat-foundation/design.md` § D9 documents the deferral; `chat-conversations` spec includes the read-path filter that the publish-side must mirror.

**Impact (if shipped):** Only relevant once `chat-realtime-broadcast-publish` ships. Without the publish-side filter at that point, a shadow-banned user's messages would silently reach non-banned recipients via WSS while being filtered from REST — a privacy hole and shadow-ban-detection oracle (the punished user could ask a non-banned friend "do you see my messages on the websocket?" to detect the shadow-ban state in seconds rather than the canonical 24-48 hour friction window).

**Ambiguity to resolve first:** Implementation site — filter at the publish call (Ktor checks `sender.is_shadow_banned` before invoking the broadcast publish), OR at the broadcast routing layer (Supabase realtime evaluates a server-side filter via the policy or a function). The natural fit is Ktor-side because (a) the sender's shadow-ban state is already loaded as part of the send transaction and (b) the subscriber-side policy was deliberately kept simple per D9.

**Action items:**
- [ ] When `chat-realtime-broadcast-publish` is filed, the proposal MUST include this publish-side filter as part of its scope (NOT as a follow-up to that change).
- [ ] Cover the filter end-to-end with an integration test: shadow-banned sender A sends to conversation X with non-banned recipient B; assert (a) the row persists, (b) `GET /messages` for B does NOT return the row, (c) the realtime broadcast for `realtime:conversation:X` does NOT carry the row.
- [ ] Update `FOLLOW_UPS.md` to delete this entry once the change merges.

## compute-ttl-to-next-reset-test-flake

**Discovered during:** `chat-foundation` round-2 PR-readiness scan (CI flaked 3 times across the lifecycle on `ComputeTtlToNextResetTest > Different users produce different offsets at high probability`)
**Status:** open

**Finding:** [`core/domain/src/test/kotlin/id/nearyou/app/core/domain/ratelimit/ComputeTtlToNextResetTest.kt:55-71`](core/domain/src/test/kotlin/id/nearyou/app/core/domain/ratelimit/ComputeTtlToNextResetTest.kt) asserts `(differingPairs >= 999) shouldBe true` over 1000 pairs of `UUID.randomUUID()`. The spec scenario at [`openspec/specs/rate-limit-infrastructure/spec.md:158-160`](openspec/specs/rate-limit-infrastructure/spec.md) prescribes "at least 999 of 1000 random pairs differ" — but the math is over-specified relative to the achievable randomness:

- Per-pair collision probability = 1/3600 (the `hashCode() % 3600` offset bucket).
- Expected collisions in 1000 pairs ≈ 0.278.
- Variance ≈ 0.278 → standard deviation ≈ 0.527.
- P(≥2 collisions) ≈ 3.2% via Poisson approximation.

So the test has a baked-in ~3-4% flake rate even on a perfect hash distribution. Confirmed empirically: 3 flakes in this lifecycle alone (CI runs 25132387140 attempts 1, 2, 3; passed on attempt 4). The test's own KDoc comment at lines 65-69 says "If this ever flakes, the hashCode distribution is the suspect, not the assertion" — that diagnosis is incorrect; the assertion threshold is mathematically too tight.

**Specs at fault:** [`openspec/specs/rate-limit-infrastructure/spec.md:160`](openspec/specs/rate-limit-infrastructure/spec.md) — `Different users different offsets (with high probability)` scenario over-specifies the threshold to "999 of 1000" when ~96-97% of randomized 1000-pair samples meet that bar.
**Code at fault:** `core/domain/src/test/kotlin/id/nearyou/app/core/domain/ratelimit/ComputeTtlToNextResetTest.kt` — the test as written depends on the spec's tight threshold.
**Docs at fault:** None — `docs/` doesn't prescribe a specific threshold.

**Impact (if shipped):** Ongoing CI flake at ~3-4% per push. Costs a manual retry click per occurrence (~5-7 min of CI runtime per retry on top of the legitimate test run). Not a correctness regression, not a security issue, not blocking. Has not blocked any feature merge to date — every change has shipped after retry. Pre-launch (no live customers) so reliability cost is low.

**Ambiguity to resolve first:** Three approaches with different trade-offs — pick one in the change's design.md:

1. **Seeded RNG**: replace `UUID.randomUUID()` with `kotlin.random.Random(seed = <empirically-chosen>).nextLong()`-derived UUIDs. Deterministic, zero flake. Trade-off: weakens "random pairs" semantics — we're now testing one specific sample of UUIDs rather than the random distribution. Reasonable since `computeTTLToNextReset` is deterministic and testing one sample with 999/1000 differing IS a valid implementation of the property.
2. **Loosen threshold + amend spec**: change to ">= 995 of 1000" (P(≥6 collisions) ≈ 0.0001%, effectively zero flake) and amend `rate-limit-infrastructure/spec.md:160` to say "for the vast majority of random pairs" or ">= 99.5%". Preserves random-pair semantics. Spec-level wording change.
3. **Increase sample size**: 100,000 pairs with threshold 99,500. Variance shrinks relative to threshold by sqrt(100) → P(failure) effectively zero. Preserves random-pair semantics + threshold percentage. Cost: 100x slower test (currently ~50ms; would become ~5s). Acceptable for a test that runs once per CI invocation.

Recommend approach 3 — preserves spec semantics, no spec amendment needed (spec wording could even tighten to "for at least 99.5% of 100,000 random pairs" if desired), test runtime cost is trivial.

**Action items:**
- [ ] File OpenSpec change `harden-compute-ttl-test` (or similar). Pick approach (recommend 3) in `design.md`. The change MODIFIES the `rate-limit-infrastructure` capability spec (loosens or restates the threshold to match achievable randomness) AND updates the test to match.
- [ ] If approach 3: bump the `repeat(N)` from 1000 to 100,000; update threshold proportionally; verify test runtime stays under ~5s.
- [ ] If approach 1: empirically choose a seed that produces ≥999 differing pairs and document why (lock the seed for reproducibility).
- [ ] If approach 2: amend `rate-limit-infrastructure/spec.md:160` to use a threshold compatible with the math; lower test threshold to match.
- [ ] Update `FOLLOW_UPS.md` to delete this entry once the change merges.

