## 1. Pre-implementation gates

- [x] 1.1 **Dated RevenueCat API re-check** (design D3 + Open Questions): confirm via a current-dated `WebSearch` the canonical **v1** promotional-entitlement grant endpoint (`POST /v1/subscribers/{app_user_id}/entitlements/{entitlement_id}/promotional`), its auth (project **secret** API key), and the duration shape (`duration` enum vs absolute `end_time_ms`) needed for extend-by-7-days stacking. Record the verdict in the first feat commit body. If v2 has since matured to cover stacking, or v1 differs from the design assumption, STOP and surface via `AskUserQuestion` (apply-phase design-revision re-check rule, project.md).
- [x] 1.2 **Re-confirm the free Flyway version** at apply time: `git fetch origin main` and check the highest `V<N>__` — a sibling change may have taken **V29** since proposal. If so, renumber to the next free version + bump every doc/spec reference (parallel-session Flyway-collision precedent).

## 2. Schema — V29 granted_entitlements

- [x] 2.1 Write `V29__granted_entitlements.sql` per the `referral-grant-worker` spec § granted_entitlements schema: table + `UNIQUE (referral_ticket_id, user_id)` + `granted_entitlements_inviter_once_idx` partial-unique on `(user_id) WHERE grant_role = 'inviter'` + unique `dedup_key` index; both FKs `ON DELETE CASCADE`; **no `NOW()` / volatile expression in any index predicate**. Header comment documents scope + the V23 (`referral_tickets`) / V21 (`subscription_events`) / V2 (`inviter_reward_claimed_at`) reuse.
- [ ] 2.2 Verify the migration applies on a **fresh** PostGIS container + `flyway validate` green (disposable container — avoid dev-DB seed-pollution false-fails).
- [x] 2.3 `dev/supabase-parity-init.sql`: N/A — the migration assumes no new Supabase-provided state; confirm and note.

## 3. `:infra:revenuecat-api` — NEW JVM module for the outbound promotional-grant client

> **Apply-time correction (operator-approved 2026-06-20):** design originally targeted `:infra:revenuecat`, but that is a mobile-only KMP module (#309 paywall SDK, no JVM target) → the backend can't depend on it, and `VendorSdkLeakageScan` forbids the client in `:backend:ktor`. A backend outbound vendor REST client is a JVM `:infra:*` module (the `:infra:cloud-vision` / `:infra:cloudflare-images` / `:infra:openai-moderation` precedent).

- [x] 3.1 Scaffold the new JVM module mirroring `:infra:cloud-vision`: `infra/revenuecat-api/build.gradle.kts` (`id("nearyou.kotlin.jvm")` + `nearyou.detekt` + kotlinxSerialization; deps `ktor.clientCore` + `ktor.clientApache5` + `kotlinx.serialization.json` + `slf4j.api`; test deps `kotest` + `ktor.clientMock`). Add `include(":infra:revenuecat-api")` to `settings.gradle.kts` **outside** the `includeMobile` block (backend, non-gated).
- [x] 3.2 Dockerfile COPY lines (mirror cloud-vision): `COPY infra/revenuecat-api/build.gradle.kts …` + `COPY infra/revenuecat-api/src …`; run `dev/scripts/check-dockerfile-module-copies.sh` — a non-gated backend `include()` missing from the Dockerfile breaks every staging/prod deploy silently while CI stays green.
- [x] 3.3 README sync: add a one-line entry to `dev/module-descriptions.txt`, run `dev/scripts/sync-readme.sh --write`.
- [x] 3.4 Define the vendor-free seam interface `ReferralEntitlementGranter` in the module (the worker depends on this; **no RevenueCat vendor/HTTP-client symbol in `:backend:ktor`**); backend depends via the type-safe accessor `projects.infra.revenuecatApi`.
- [x] 3.5 Implement the live v1 client (raw Ktor client; `POST /v1/subscribers/{app_user_id}/entitlements/{entitlement_id}/promotional`, secret API key via `secretKey(env, "revenuecat-secret-api-key")`, absolute `end_time_ms`, `dedup_key`; entitlement id `premium`).
- [x] 3.6 Fail-soft `NoOpReferralEntitlementGranter` when the API key is unset (`NoOpImageModerator` precedent): logs the un-dispatched grant, returns a no-op result, never throws.
- [x] 3.7 Wire into `Application.kt` (imperative construction — the app uses no Koin): live client when the key resolves, NoOp otherwise.

## 4. Worker — route + service + repository (`:backend:ktor`)

- [x] 4.1 Repository (JDBC, docs/11 §3.2): pending-ticket scan (`status='pending_activity'`); expire (`UPDATE … SET status='expired' WHERE expires_at < NOW()`); invitee authored-post count over `[ticket.created_at, NOW()]` (**raw `FROM posts` self-count** — an engagement signal, not a visibility-sensitive read; satisfy the two lint rules via `@AllowRawPostsRead` + `@AllowMissingBlockJoin("referral self-count of the invitee's own posts")` on the SQL-holding property — **NOT** the `@allow-no-block-exclusion: chat-history-readable-after-block` marker, which is chat-specific and only matches by substring; the cleaner alternative is an own-content repository filename prefix that auto-exempts both rules); per-inviter `granted` ticket count; `granted_entitlements` insert `ON CONFLICT DO NOTHING`; `inviter_reward_claimed_at` set.
- [x] 4.2 Service: two-pass algorithm (expire → evaluate → grant); activity gate (posts ≥ 2 AND inviter `is_banned = FALSE` AND `is_shadow_banned = FALSE` — docs/01 §233 "shadow or hard"; banned-by-either-flag inviter → void to `expired`); stacking window `GREATEST(current_entitlement_end, NOW()) + INTERVAL '7 days'`; 5th-`granted`-referral inviter grant + sentinel; **per-ticket DB transaction** (not one batch tx); RevenueCat dispatch via the `:infra:revenuecat` client **outside** the DB tx (idempotent via `dedup_key`).
- [x] 4.3 Route: `POST /internal/referral-activity-check` — install `InternalEndpointAuth` (Google OIDC) on the worker's **own** `route("/referral-activity-check")` subtree, **NOT** the shared `/internal` node (that node also hosts `/internal/revenuecat-webhook`, which authenticates by Bearer + HMAC, not OIDC — a shared-node OIDC install would 401 the webhook; the `privacy-flip-worker` route + `InternalRoutingIsolationTest` precedent). Returns the `{expired, granted, pending}` summary JSON.
- [x] 4.4 Resolve a recipient's current entitlement end for the stacking computation from `subscription_status` / latest effective `subscription_events`.

## 5. Webhook GRANT handler — MODIFY `subscription-billing-webhook`

- [x] 5.1 Replace the `GRANT` no-op branch: in one transaction, record `subscription_events(event_type='grant', source='referral', revenuecat_event_id, entitlement_start/end)` + set `users.subscription_status='premium_active'`; idempotent via the existing `revenuecat_event_id UNIQUE`; orphan app-user id → `200` + WARN + no writes.
- [x] 5.2 Regression-guard the existing paid-path scenarios (initial_purchase / renewal / billing_issue / expiration / cancellation / privacy-flip scheduling) — no behavior change to them.

## 6. Tests (`:backend:ktor`)

- [ ] 6.1 Worker auth: unauthenticated `/internal/referral-activity-check` rejected; OIDC-authenticated admitted (reuse the `internal-endpoint-auth` test harness).
- [ ] 6.2 Expiry: a `pending_activity` ticket past `expires_at` → `expired`, no grant.
- [ ] 6.3 Activity gate: invitee ≥ 2 posts + inviter in good standing → pass; invitee < 2 posts → stays `pending_activity`; **exactly 2 posts → passes** (the `≥` boundary); inviter `is_banned = TRUE` → voided to `expired`; inviter `is_shadow_banned = TRUE` → voided to `expired` (both ban flags, per docs/01 §233).
- [ ] 6.4 Invitee grant: a passing ticket → `granted` + a `granted_entitlements` `invitee` row + the `:infra:revenuecat` grant client is invoked with the computed window + `dedup_key`.
- [ ] 6.5 Stacking: `premium_active` recipient with a future entitlement end → `entitlement_end` extends by 7 days; `free`/lapsed recipient → fresh `NOW()+7d`; **boundary** — a `premium_active` recipient whose entitlement end is already in the past (`end < NOW()`) → fresh `NOW()+7d` (the `GREATEST(end, NOW())` floor), not `end + 7d`.
- [ ] 6.6 **Invitee-grant uniqueness per ticket** (docs/08 §292): re-run AND concurrent worker invocations over an already-granted ticket → exactly one invitee `granted_entitlements` row (the `UNIQUE (referral_ticket_id, user_id)` ON CONFLICT path).
- [ ] 6.7 **Inviter lifetime cap** (docs/08 §292, verbatim scenario): 10 successful referrals from the same inviter produce **exactly one** `grant_role = 'inviter'` row, triggered at the **5th** ticket, and **zero thereafter**; `users.inviter_reward_claimed_at` set exactly once.
- [ ] 6.8 **Concurrent worker runs** (docs/08 §292): two concurrent invocations apply each grant at most once — at the invitee uniqueness path AND at the 5th-referral inviter boundary (the partial-unique index serializes the race).
- [ ] 6.9 Schema: `grant_role` CHECK rejects an out-of-vocab value; `granted_entitlements_inviter_once_idx` rejects a second `inviter` row for the same user; user hard-delete cascades `granted_entitlements`.
- [ ] 6.10 Webhook GRANT (MODIFY): a `GRANT` event activates `premium_active` + records a `source='referral'` grant row; re-delivered (same `revenuecat_event_id`) → `200` no-op duplicate; orphan user → `200` no writes; the referral grant is excluded from the paid MRR query.
- [ ] 6.11 Fail-soft: with the RC API key unset, a passing ticket still writes the `granted_entitlements` row + flips to `granted` + logs the un-dispatched grant + does not throw.
- [ ] 6.12 DB-test hygiene: any test creating posts/users does per-test cleanup by username-prefix (timeline-suite-pollution precedent), `autoClose` new Hikari pools at size 2 (CI connection-budget precedent), and truncates seeded timestamps to micros (macOS-vs-Linux CI clock precedent).
- [ ] 6.13 Worker does not write `subscription_status` (spec guard, D4): after a passing ticket is granted, the recipient's `users.subscription_status` is **unchanged** by the worker — the webhook `GRANT` echo (6.10) is the sole writer.
- [ ] 6.14 Deferred-legs guard (spec): a ticket passing posts + expiry + inviter-eligibility is granted **regardless** of any login-day / app-session / IP-subnet / recently-seen-identifier / 90-day-windowed-fingerprint signal — the gate consults none of them (asserts the deferred legs are not evaluated).
- [ ] 6.15 Partial-failure isolation: when the RC grant client **throws** on ticket N+1, ticket N stays `granted` (its row committed) AND N+1 stays `pending_activity`, AND a subsequent run completes N+1 — verifies the per-ticket transaction boundary (distinct from the key-unset fail-soft in 6.11).
- [ ] 6.16 Route isolation: the worker's OIDC gate does NOT affect `/internal/revenuecat-webhook` auth — a Bearer + HMAC webhook request (no OIDC token) still authenticates (mirror `InternalRoutingIsolationTest`).

## 7. Lint + invariants

- [ ] 7.1 Local gate green: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`.
- [ ] 7.2 Annotations verified: `@AllowRawPostsRead` + `@AllowMissingBlockJoin` (or an own-content filename prefix) on the invitee post-count SQL — **NOT** the chat-only `@allow-no-block-exclusion` marker; `secretKey(env,name)` for the RC key; no vendor import outside `:infra:*`; no `NOW()` in any V29 index predicate.
- [ ] 7.3 Doc-drift guards: the new `:infra:revenuecat-api` module IS added → confirm `dev/scripts/sync-readme.sh --check` passes (after task 3.3's `--write`) AND `dev/scripts/check-dockerfile-module-copies.sh` passes (after task 3.2's COPY lines).

## 8. Staging smoke + deploy (pre-archive)

- [ ] 8.1 (operator) Create the `staging-revenuecat-secret-api-key` Secret Manager slot (value = RevenueCat Test Store secret key) + grant the Cloud Run runtime SA `secretAccessor`.
- [ ] 8.2 Manual branch deploy: `gh workflow run deploy-staging.yml --ref referral-grant-worker`; poll the run; `/health/ready` all-green.
- [ ] 8.3 Smoke `dev/scripts/smoke-referral-grant-worker.sh`: seed a `pending_activity` ticket + 2 invitee posts; invoke `/internal/referral-activity-check` (with a minted OIDC token); assert ticket → `granted` + a `granted_entitlements` row; replay a simulated RevenueCat `GRANT` webhook and assert `premium_active` + a `source='referral'` event. Tick Section 6 of tasks before archive.
- [ ] 8.4 (deferred — prod) Provision the Cloud Scheduler job invoking `/internal/referral-activity-check` daily + the prod `revenuecat-secret-api-key` slot. Stays unchecked until prod infra is provisioned (does not block the squash-merge).

## 9. Archive

- [ ] 9.1 `openspec validate referral-grant-worker --strict` green before the squash-merge.
- [ ] 9.2 `openspec archive referral-grant-worker` + spec sync (`referral-grant-worker` added; `subscription-billing-webhook` + `referral-ticket-creation` updated); `openspec validate --specs referral-grant-worker subscription-billing-webhook referral-ticket-creation --strict` green.
