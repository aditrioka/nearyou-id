# Follow-ups

Transient working file for findings discovered during a change cycle that are NOT in scope of the current change but need a tracked owner. Per repo convention:

- Add an entry when a finding is real, fixable, but should NOT be silently swept into the current change's scope.
- Tick the action-item checkboxes as they are completed.
- **Delete the entry once all its action items are merged.** Do NOT let `triaged` entries linger — if residual work remains, either (a) move it to the canonical doc that owns the topic (e.g., launch-prerequisite tasks → `docs/08-Roadmap-Risk.md` Pre-Launch list, runbook tweaks → `docs/07-Operations.md` Deployment Runbook), or (b) replace the entry with a fresh one scoped to the residual work. Triaged-but-not-deleted entries are how this file rots.
- Delete the file itself when it has zero entries left.
- Recreate the file (with this same intro blurb) the next time a finding arises.
- **Hard limit: max 30 open entries.** When breached, force a triage sweep before adding new entries; entries open for >2 weeks are candidates for migration to GitHub Issues if the team grows beyond solo. **Audit on 2026-05-30 (`/triage-follow-ups` full sweep, post-archive of `mobile-auth-google-signin-flow`): 38 → 32 open + 0 triaged.** Zero silently-resolved / superseded entries found (no rot) — the breach was genuine deferred-work volume (the 8 `mobile-auth-signin-*` entries from PR #122 + the 6-entry otel cluster). Closed 6: 3 docs-only fixes applied inline (`post-cmp-swap-spec-text-cleanup`, `docs-host-prefix-domain-attribute-incongruity`, `docs-ios-primary-auth-mobile-3-vs-eventual-state`) + 1 migrated to `docs/08-Roadmap-Risk.md` Pre-Launch (`production-deploy-workflow-cloud-run-flags-for-layer3`) + 2 accept-the-gap deletes (`firebase-app-extraction`, `vendor-ahocorasick-detekt-guard`); `system-actor-and-worker-audit-rows` promoted to `/next-change`. **Note: 32 is still 2 over the 30 limit** — the residual is all verified-still-valid deferred work (not rot), drawn down as promoted + test-coverage-bundle work ships. **Targeted check on 2026-05-31 (`mobile-nearby-timeline-screen` apply §11.1):** no full re-sweep — the 2026-05-30 full sweep was 1 day prior and found zero rot, and only PRs #125/#126 shipped since (neither resolves an open entry; `system-actor-and-worker-audit-rows` was already promoted + removed). Verified the 6 new Mobile-#5 deferrals are not duplicates of any open entry, then added them (`mobile-location-permission-flow`, `mobile-nearby-radius-slider`, `mobile-nearby-timeline-infinite-scroll`, `mobile-timeline-empty-global-cta`, `timeline-response-dto-casing-drift`, `mobile-timeline-relative-timestamp`) → **37 open**. The breach is legitimate Mobile-#5 deferred-work volume, not rot; a dedicated `/triage-follow-ups` sweep + GitHub-Issues migration (per the solo→team note above) is the recommended drawdown path. Prior sweeps: 2026-05-10 found 22 open + 0 triaged; PR #79 (`chore: triage FOLLOW_UPS.md (2026-05-09)`) preserves the 2026-05-09 sweep's deletion-evidence audit trail. **Audit on 2026-06-01 (`/triage-follow-ups` full sweep): 37 → 35 open + 0 triaged.** Zero rot again (no silently-resolved / superseded) — verified none of the 16 `File OpenSpec change <name>` slugs exist in `openspec/changes/` or `archive/`, the `ColorSchemeExtensionsTest` exclude is still live in `shared/resources/build.gradle.kts` (`mobile-compose-ui-tests-android-instrumented` still-open), and `mobile-theme-light-dark-direct-test`'s two theme color-scheme scenarios are still untested in `:mobile:app` despite Mobile #5 shipping (runner wired since Mobile #3, but no theme assertion test — deletion condition only half-met). Closed 2 via migration to [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Pre-Launch #6/#7 (`mobile-location-permission-flow`, `mobile-age-gate-stronger-verification` — both launch-gating mobile work; the latter surfaces the PP 17/2025 "PP TUNAS" age-assurance deadline that was absent from the roadmap). Per user dispositions: a 6-entry test-coverage chore-PR scope was surfaced (`fcm-payload-structural-tests`, `fcm-shutdown-drain-deterministic-tests`, `fcm-end-to-end-composite-test`, `reply-rate-limit-moderator-spy`, `chat-block-check-moderator-spy`, `mobile-theme-light-dark-direct-test` — closes those on merge → ~29 open); the 7 dormant-until-external-trigger entries kept (still solo-operator, GitHub-Issues migration deferred); all promotions deferred. **35 is still 5 over the 30 limit** — residual is verified-still-valid deferred work, not rot; the test-coverage bundle is the next drawdown lever.

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

## observability-otel-collector-tail-sampling

**Discovered during:** `observability-otel-foundation` `/next-change` Phase D round-3 adversarial-lens finding #11 — the round-1 design § D4 force-keep `SpanProcessor` re-emitting via `Tracer.spanBuilder().setNoParent()` is structurally wrong: it creates a fresh root span detached from the original trace, breaking trace_id linkage in Tempo.
**Status:** open

**Finding:** The canonical sampling profile at [`docs/05-Implementation.md:2042`](docs/05-Implementation.md) prescribes "10% base + 100% errors + 100% slow (>500ms)" in production. The `observability-otel-foundation` change ships only the 10% base via `ParentBased(TraceIdRatioBased(0.1))` — the force-keep tail (errors + slow) is deliberately deferred because correctly preserving trace_id linkage on force-keep promotion requires OTel Collector tail sampling, which is meaningful infrastructure work the architecture doc explicitly defers at [`docs/04-Architecture.md:394`](docs/04-Architecture.md): _"Tail sampling via OTel Collector if volume is high"_. Until this follow-up ships, MVP production accepts that 90% of error/slow traces drop; structured JSON logging at 100% retention via Cloud Logging is the authoritative incident-replay surface.

**Specs at fault:** `openspec/specs/observability-otel-foundation/spec.md` (post-archive) — its sampling-profile requirement explicitly does NOT promote error/slow spans; this follow-up adds that promotion via Collector.
**Code at fault:** None — there is no half-implemented force-keep in this change to fix; the deferral is clean.
**Docs at fault:** None — [`docs/04-Architecture.md:394`](docs/04-Architecture.md) already names the Collector as the upgrade path.

**Impact (if shipped):** Low-during-MVP, rising as production traffic grows. Errors at p99 latency are the spans operators most need; today they're 90%-dropped in production. Cloud Logging fills the gap (100% retention, structured JSON includes `trace_id` so Tempo correlation is possible via log↔trace cross-link), but trace-tree-style debugging in Tempo isn't available for dropped traces. Acceptable transitional shape until volume warrants the Collector ops cost.

**Ambiguity to resolve first:** Collector deployment shape. Options: (a) Cloud Run sidecar (per-instance, simplest deploy), (b) separate Cloud Run service receiving from a published OTLP endpoint (operationally cleanest), (c) OTel Collector Operator if we ever migrate to GKE (out of scope today). Open question for the follow-up's design.md.

**Action items:**
- [ ] File OpenSpec change `observability-otel-collector-tail-sampling` that (a) deploys an OTel Collector with tail-sampling rules `status=ERROR` OR `duration>500ms` → 100% keep, else 10% sample, (b) reconfigures the production `OtelBootstrap` to point the OTLP exporter at the Collector instead of Grafana Cloud directly, (c) the Collector's outbound exporter targets Grafana Cloud Tempo, (d) MODIFIES the `observability-otel-foundation` capability spec's sampling-profile requirement to flip the "does NOT force-keep error / slow" scenarios.
- [ ] Update this `FOLLOW_UPS.md` entry to delete once the change merges.

---

## observability-otel-fcm-traceparent

**Discovered during:** `observability-otel-foundation` `/next-change` Phase D round-3 implementation-realism finding #3 + adversarial finding #11 — the round-1 design § D8 assumed `:infra:fcm` exposed `FirebaseOptions.Builder.setHttpTransport(...)` to `:backend:ktor`. Codebase reality: `:infra:fcm`'s `buildFcmComposite(...)` factory owns Firebase Admin SDK initialization internally, with no surfaced HttpTransport hook.
**Status:** open

**Finding:** The Firebase Admin SDK FCM send (`FirebaseMessaging.send(message)`) uses an internal HTTP transport that the OTel auto-instrumentation does NOT cover. Cross-service `traceparent` propagation requires plumbing a custom `HttpTransport` (specifically a `TracingHttpTransport` that delegates to the SDK's default while injecting the active OTel context's `traceparent` header) through `:infra:fcm`'s public API. This is a real `:infra:fcm` API change that deserves a focused proposal — too much surface to inline-patch on the foundation change. The `observability-otel-foundation` change ships only the LOCAL `withSpan("fcm.dispatch", ...)` wrap; cross-service propagation lands in this follow-up.

**Specs at fault:** `openspec/specs/observability-otel-foundation/spec.md` (post-archive) — its W3C-propagation requirement explicitly carves out FCM ("FCM Admin SDK send does NOT carry `traceparent`"); this follow-up flips that.
**Code at fault:** [`infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/FcmDispatcher.kt`](infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/FcmDispatcher.kt) — `buildFcmComposite(...)` factory needs an `HttpTransport` parameter or a `:infra:otel` injection point.
**Docs at fault:** None.

**Impact (if shipped):** Operationally medium. The user's chat-send → FCM-dispatch trace ends at the FCM dispatch local span until this lands; cross-service correlation into Google's Cloud Trace surface is unavailable. Cloud Logging timestamp correlation across the two surfaces remains possible (workaround). Phase 2 §14 benchmark uses staging at head-100% sampling; this gap doesn't affect the benchmark.

**Ambiguity to resolve first:** API shape for the `:infra:fcm` change. Options: (a) `buildFcmComposite(httpTransport: HttpTransport? = null)` — additive parameter; (b) `buildFcmComposite(otel: OpenTelemetry? = null)` — let `:infra:fcm` construct the transport internally with an OTel reference; (c) a separate `FcmTracingConfig` DI surface. Open question for the follow-up's design.md.

**Action items:**
- [ ] File OpenSpec change `observability-otel-fcm-traceparent` that (a) refactors `:infra:fcm`'s `buildFcmComposite(...)` to surface the `HttpTransport` hook, (b) ships `TracingHttpTransport` in `:infra:otel`, (c) MODIFIES the `fcm-push-dispatch` spec to require unconditional `traceparent` injection on the FCM Admin SDK's outbound HTTPS request, (d) MODIFIES the `observability-otel-foundation` spec's W3C-propagation requirement to flip the "FCM does NOT carry traceparent" scenario.
- [ ] Update this `FOLLOW_UPS.md` entry to delete once the change merges.

---

## otel-attribute-rule-value-aware-userid-aliases

**Discovered during:** `otel-attribute-lint-rule` design § "Explicitly deferred follow-ups" (multi-lens Phase D review surfaced the gap between the canonical spec's value-semantic forbidden list and the shipped lint rule's key-name-only enforcement).
**Status:** open

**Finding:** The canonical spec at [`openspec/specs/observability-otel-foundation/spec.md:186`](openspec/specs/observability-otel-foundation/spec.md) enumerates raw `user_id` UUID under generic-named keys (`principal`, `actor`, `subject`, `owner`) as forbidden by semantics. The shipped `OtelForbiddenAttributeRule` (PR [#99](https://github.com/aditrioka/nearyou-id/pull/99)) intentionally does NOT include `principal` / `actor` / `subject` / `owner` in Tier 1 because key-name-exact-match alone would block legitimate auth-domain code (e.g., `setAttribute("principal", principalRole)` with a non-UUID value). Closing the gap requires value-aware analysis: fire only when the value-arg expression resolves to a UUID-shaped literal (regex `[0-9a-f-]{36}`) OR a `UUID`-typed variable.

**Specs at fault:** None — `observability-otel-foundation/spec.md:186` correctly enumerates the value-semantic forbidden set; lint coverage is the gap.
**Code at fault:** [`lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt) — `TIER_1_GROUP_A` omits these aliases by design (see KDoc § "Why value-aware user-id alias detection is deferred").
**Docs at fault:** None.

**Impact (if shipped):** Low at MVP scale. No current writer in `:backend:ktor` / `:infra:*` sets these keys with a raw UUID; risk is regression rather than active leak. Runtime `ForbiddenAttributeStripper.FORBIDDEN_KEYS` does not enumerate these either — they're solely a code-review-defended surface today. Integration-test sentinel scenario "No raw user_id appears in any span" (value-side) gives backstop coverage.

**Ambiguity to resolve first:** Value-resolution strategy. Options: (a) PSI-only heuristic — walk the value-arg expression to identify UUID-shaped literals or `UUID.toString()` / `userId.toString()` patterns; (b) Detekt full type-resolution mode — use compile-classpath info to detect `UUID`-typed variables. Option (a) is lighter and matches existing rules' approach; option (b) is more precise but adds build-graph complexity. Decide at design time.

**Action items:**
- [ ] File OpenSpec change `otel-attribute-rule-value-aware-userid-aliases` that extends `OtelForbiddenAttributeRule` with value-aware analysis for `principal` / `actor` / `subject` / `owner` keys; fires only when the value-arg expression resolves to a UUID-shaped literal OR a `UUID`-typed variable.
- [ ] Validate the chosen value-resolution strategy against `setAttribute("principal", "system")` (string role) — must NOT fire.
- [ ] Delete this `FOLLOW_UPS.md` entry once the change merges.

---

## otel-attribute-rule-location-key-patterns

**Discovered during:** `otel-attribute-lint-rule` design § "Explicitly deferred follow-ups" — spec mandate vs lint coverage gap.
**Status:** open

**Finding:** The canonical spec at [`openspec/specs/observability-otel-foundation/spec.md:191`](openspec/specs/observability-otel-foundation/spec.md) mandates forbidding any attribute key matching `*location*` / `*lat*` / `*lng*` / `*coord*` unless explicitly sanctioned. The shipped `OtelForbiddenAttributeRule` does NOT enumerate these patterns — Tier 1 is exact-match key strings only. A substring-match would false-positive on `display_location` (the sanctioned key from the `coordinate-jitter` capability, projected by every non-admin read path per `docs/05-Implementation.md`) and would overlap with `CoordinateJitterRule` (which already enforces `actual_location` in `KtStringTemplateExpression`s).

**Specs at fault:** None — `observability-otel-foundation/spec.md:191` correctly mandates the patterns; lint coverage is the gap.
**Code at fault:** [`lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt) — Tier 1 does not include any `*location*` / `*lat*` / `*lng*` / `*coord*` regex.
**Docs at fault:** None.

**Impact (if shipped):** Low at MVP. `CoordinateJitterRule` already covers `actual_location` literals in Kotlin source (PR-shipped 2026-04, see [`CoordinateJitterRule.kt`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/CoordinateJitterRule.kt)); `display_location` is the sanctioned key. Active leak risk is near-zero for the MVP writer surface, but a future maintainer setting `span.setAttribute("geo.lat", lat)` or `span.setAttribute("user.coord", coords)` would not trip any current automated check.

**Ambiguity to resolve first:** Pattern shape that excludes `display_location` and avoids overlap with `CoordinateJitterRule`. Candidate: `\b(?!display_)([a-z_]*(location|lat|lng|coord)[a-z_]*)\b` (negative-lookahead carve-out for `display_`). Plus the same path-allowlist (`/infra/otel/src/main/`, `/lint/detekt-rules/src/main/`, `/src/test/`) and `@AllowForbiddenSpanAttribute` annotation bypass. Decide composition: a new Tier or a sub-mode of existing Tier 1?

**Action items:**
- [ ] File OpenSpec change `otel-attribute-rule-location-key-patterns` that adds `*location*` / `*lat*` / `*lng*` / `*coord*` key-name pattern enforcement to `OtelForbiddenAttributeRule`, with `display_location` carve-out and a regression test asserting no overlap with `CoordinateJitterRule` (each rule fires independently, no cross-suppression).
- [ ] Delete this `FOLLOW_UPS.md` entry once the change merges.

---

## otel-attribute-rule-opaque-secrets

**Discovered during:** `otel-attribute-lint-rule` design § Decision 4 + "Explicitly deferred follow-ups" — Tier 2 narrowed to 4 high-confidence patterns; broader opaque-secret patterns deferred.
**Status:** open

**Finding:** The shipped `OtelForbiddenAttributeRule` Tier 2 contains 4 high-confidence value-regex patterns: PEM private-key marker, JWT three-segment shape, Redis URI with userinfo, JWKS RSA-key JSON shape. The canonical spec at [`openspec/specs/observability-otel-foundation/spec.md`](openspec/specs/observability-otel-foundation/spec.md) § "Forbidden span attributes" additionally enumerates OAuth `client_secret` values, raw refresh tokens, and plaintext password fields as forbidden-by-semantics. These are opaque strings without distinguishing markers — any value-regex would over-match (firing on coincidentally-shaped legitimate data) or under-match (missing real secrets that don't fit the regex). Tier 2 stays narrow per Decision 4: "wrong-positives are worse than wrong-negatives here".

**Specs at fault:** None — `observability-otel-foundation/spec.md` correctly enumerates the forbidden categories; the runtime `ForbiddenAttributeStripper` plus integration-test sentinel-string regression scenarios cover the production output path; compile-time lint coverage of opaque-secret VALUES is the residual gap.
**Code at fault:** [`lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt) — Tier 2 patterns intentionally narrow.
**Docs at fault:** None.

**Impact (if shipped):** Low. No current writer attempts to set these. Code review + the sentinel-string regression scenarios at integration time (which scan ALL span attribute values for sentinel literals injected at request boundary) remain the canonical defense for these categories.

**Ambiguity to resolve first:** Pattern strategy. Options: (a) known-prefix conventions — e.g., the OAuth client used by `internal-endpoint-auth` may issue `client_secret` values with a stable prefix; verify with the auth-flow design; (b) accept that code review remains canonical and amend the spec's "Forbidden span attributes" closing prose to make code-review-only an explicit defense layer for opaque-secret categories. Option (a) is the higher-leverage outcome IF a stable prefix exists; option (b) is the honest fallback otherwise.

**Action items:**
- [ ] Decide pattern strategy by inspecting the actual OAuth `client_secret` / refresh-token formats used by the production auth flow (Supabase, GCP OIDC) — does any have a stable prefix that would yield a high-confidence regex?
- [ ] Per the chosen strategy: file OpenSpec change `otel-attribute-rule-opaque-secrets` to either extend Tier 2 with prefix-anchored patterns OR amend the canonical spec to acknowledge code-review-only enforcement for these categories.
- [ ] Delete this `FOLLOW_UPS.md` entry once the change merges (or the spec amendment lands).

---

## otel-attribute-rule-psi-context-restricted-mode-a

**Discovered during:** `otel-attribute-lint-rule` design § Decision 3 — the `"user_id"` carve-out rationale + Phase D round-2 multi-lens review (security lens flagged the carve-out as a residual gap).
**Status:** open

**Finding:** The shipped `OtelForbiddenAttributeRule` carves out `"user_id"` from Tier 1 Group A because the string literal appears in ~12 production paths today as SQL column name (`rs.getObject("user_id", UUID::class.java)`), `@SerialName` JSON key, and Ktor route parameter (`call.parameters["user_id"]`) across `:backend:ktor` chat / follow / block routes and JDBC repositories. These uses are semantically unrelated to OTel span attribute writes; exact-match enforcement would produce ~12 false positives with no canonical fix today. A PSI-context-restricted Mode A enforcement that fires only when the literal appears in a setAttribute-like call context (PSI parent-walk finding a `KtCallExpression` with callee identifier `setAttribute` / `addEvent` / `AttributesBuilder.put` / inside a `mapOf(...)` argument passed to `withSpan(...)`) would allow re-introducing `"user_id"` to Tier 1 Group A without false-positives on SQL / JSON / route-param uses.

**Specs at fault:** None — [`openspec/specs/observability-otel-foundation/spec.md`](openspec/specs/observability-otel-foundation/spec.md) § Tier 1 Group A correctly documents the carve-out + rationale + this deferred follow-up.
**Code at fault:** [`lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/OtelForbiddenAttributeRule.kt) — `TIER_1_GROUP_A` omits `"user_id"` (see KDoc § "user_id carve-out").
**Docs at fault:** None.

**Impact (if shipped):** Low. The runtime `ForbiddenAttributeStripper.FORBIDDEN_KEYS` still defensively strips emitted `"user_id"` attributes at export; the integration-test sentinel scenario "No raw user_id appears in any span" (value-side) covers leakage end-to-end. Lint coverage of the developer-written half of this specific key is the residual gap — a developer typing `setAttribute("user_id", userId.toString())` would not trip any compile-time check, only the runtime stripper.

**Ambiguity to resolve first:** PSI walk design. Two sub-issues: (i) the `Map.put(...)` false-positive — generic `put` callees from `kotlin.collections.MutableMap` should NOT trigger the rule, only the OTel `AttributesBuilder.put` (decide whether to restrict by callee short-name + heuristic argument shape, or by Detekt full type-resolution to disambiguate); (ii) the `mapOf(...) → withSpan(...)` indirection — the literal `"user_id"` inside `mapOf("user_id" to ...)` has PSI parent `KtValueArgument` inside `mapOf`, NOT inside `withSpan`'s call expression directly. The PSI walk needs to handle the two-step parent chain (literal → mapOf KtCallExpression → withSpan KtCallExpression).

**Action items:**
- [ ] File OpenSpec change `otel-attribute-rule-psi-context-restricted-mode-a` that adds PSI-context-restricted Mode A enforcement firing only in setAttribute-like call sites; the change MODIFIES Tier 1 Group A to re-introduce `"user_id"` (or adds a new tier with PSI restriction scoped to `"user_id"`).
- [ ] Validate against the 12 pre-existing `"user_id"` literal sites (SQL columns / `@SerialName` JSON / Ktor route params) — none should fire under the PSI-restricted mode (regression test asserts each existing site passes).
- [ ] Delete this `FOLLOW_UPS.md` entry once the change merges.

---

## admin-app-revoke-staging-and-prod

**Discovered during:** `admin-schema-bootstrap` `/opsx:apply` Section 7 — direct psql to staging Supabase was blocked by Supabase's IPv6-only direct-Postgres host (the dev environment is IPv4-only). The V16 Flyway migration shipped successfully via Cloud Run Jobs, but the `admin_app` REVOKE statements per [`docs/05-Implementation.md:1208`](docs/05-Implementation.md) and [`docs/07-Operations.md` § Data Access Pattern](docs/07-Operations.md) are operational (NOT Flyway-managed per `admin-schema-bootstrap/design.md` D4) and were NOT applied during the deploy.
**Status:** in-progress — **staging COMPLETE (2026-05-17)**, production pending. Provisioned via [`dev/scripts/provision-admin-app-staging.sh`](dev/scripts/provision-admin-app-staging.sh) (Cloud Run Job pattern, idempotent, enumerated per-table grants, no `ALTER DEFAULT PRIVILEGES`, no `BYPASSRLS`). Verified by connecting AS admin_app: `current_user = admin_app`, `SELECT count(*) FROM admin_users = 0`, `SELECT count(*) FROM admin_actions_log = 0`. The REVOKE on `admin_actions_log` (UPDATE + DELETE) landed in the same idempotent script. Connection string stored at `staging-admin-app-db-connection-string` GCP Secret Manager slot (+ 3 companion slots for user/password/jdbc-url shape, all granted `secretAccessor` to the Cloud Run runtime SA). Entry stays open until production gets the same treatment + the runbook record lands.

**Finding:** [`docs/05-Implementation.md:1208`](docs/05-Implementation.md) states `admin_actions_log` immutability "is enforced at the role level for `admin_app`" (UPDATE/DELETE revoked). [`docs/07-Operations.md` § Data Access Pattern](docs/07-Operations.md) prescribes the same. The V16 migration deliberately excludes role-level REVOKE/GRANT statements per [`admin-schema-bootstrap/design.md`](openspec/changes/archive/2026-05-17-admin-schema-bootstrap/design.md) D4 (Supabase Console is the canonical surface for role permissions; including `REVOKE ... FROM admin_app` in Flyway would fail in the integration-test Postgres which doesn't provision `admin_app`). The REVOKE landing is therefore an operational follow-up.

**Specs at fault:** None — `openspec/specs/admin-schema/spec.md` (post-archive) Requirement 6 correctly enumerates GRANT/REVOKE as out of scope for V16.
**Code at fault:** None — V16 is environment-portable by design (verified by spec scenario "Migration applies cleanly without admin_app role" + by the successful CI integration-test runs against a vanilla Postgres without admin_app).
**Docs at fault:** None — both [`docs/05-Implementation.md:1208`](docs/05-Implementation.md) and [`docs/07-Operations.md`](docs/07-Operations.md) correctly describe the end state; the operational gap is the REVOKE not yet being applied to staging Supabase.

**Impact (if shipped):** Operationally low until Admin #2 / Admin #3 ship. Today zero admin rows exist and zero admin code writes audit rows, so the absence of role-level REVOKE has no exploitable surface. Once Admin #3 lands the first admin-login flow and Admin #4 lands the audit-log viewer, the REVOKE becomes load-bearing — a compromised `admin_app` connection without the REVOKE could mutate `admin_actions_log` (defeating the immutability invariant). Pre-condition for any production admin code: REVOKE applied to both staging AND production Supabase.

**Ambiguity to resolve first:** None. SQL is straightforward:
```sql
REVOKE UPDATE, DELETE ON admin_actions_log FROM admin_app;
```
Apply via Supabase Console → SQL Editor on each environment. If `admin_app` role doesn't yet exist (Pre-Phase 1 #28 not run), this errors with "role admin_app does not exist" — in that case, defer to the `admin_app`-role-provisioning task and bundle the REVOKE alongside the role CREATE.

**Action items:**
- [x] **Provision the `admin_app` Postgres role in staging Supabase** per [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Pre-Phase 1 #28. Done 2026-05-17 via [`dev/scripts/provision-admin-app-staging.sh`](dev/scripts/provision-admin-app-staging.sh) (PR [#109](https://github.com/aditrioka/nearyou-id/pull/109)). Role is `LOGIN`, enumerated per-table grants on 23 base tables + 2 views, `REVOKE UPDATE, DELETE ON admin_actions_log` in place, no `ALTER DEFAULT PRIVILEGES`, no `BYPASSRLS`.
- [x] **Store the staging `admin_app` connection string** at `staging-admin-app-db-connection-string` (combined DSN per Pre-Phase 1 #28 prescription) + 3 companion slots (`staging-admin-app-db-{user,password,url}`) following the existing main-app slot-split pattern. All 4 slots granted `secretAccessor` to the Cloud Run runtime SA `27815942904-compute@developer.gserviceaccount.com` per [`docs/07-Operations.md` § Secret Management Runbook](docs/07-Operations.md).
- [ ] Once production is provisioned, run the script with `PROJECT_OVERRIDE=nearyou-production` + appropriate slot overrides. Production-equivalent password slot must be created first (the script fail-fasts if absent).
- [ ] Record the role-provisioning procedure in the deployment runbook at [`docs/07-Operations.md`](docs/07-Operations.md) § Data Access Pattern (or in a new admin-app provisioning runbook the Admin #2 lifecycle will introduce — Admin #2's `tasks.md` SHOULD call this out as a gate). The script itself + this entry serve as the operational record until that runbook lands.
- [ ] Block Admin #2 squash-merge until at minimum the staging admin_app role exists AND the REVOKE has landed. **Staging side: cleared.** Admin #2 can proceed on staging-track work.
- [ ] Delete this `FOLLOW_UPS.md` entry once production also has the role AND the runbook records the procedure.

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

## chat-message-notification-per-conversation-fcm-batching

**Discovered during:** `chat-foundation` apply (originally tracked under `chat-message-notification-emit-sites`; reduced to the only remaining open scope after `chat-message-notification` shipped via PR #65).
**Status:** open

**Finding:** The `chat_message` emit-site + end-to-end FCM dispatch wiring shipped in `chat-message-notification` (PR #65) — every successful chat send produces a `notifications` row AND fans out one FCM push per active recipient token. What did NOT ship is the **per-conversation push batching** behavior described as a Phase 2 chat scope item: when a sender pumps multiple messages into one conversation in quick succession (a typing burst), the receiver currently gets one FCM push per message rather than a single coalesced push for the burst. At MVP scale this is acceptable noise; at scale it becomes a notification-fatigue + FCM quota concern. The `chat_message_redacted` emit-site (the second originally-open item under `chat-message-notification-emit-sites`) is deferred to the Phase 3.5 admin redaction change per `chat-message-notification` proposal § Open Questions Q3 and does NOT need its own follow-up entry — the Phase 3.5 admin work owns it.

**Specs at fault:** None.
**Code at fault:** None — current behavior (one push per message) is correct, just unbatched.
**Docs at fault:** None.

**Impact (if shipped):** Low at MVP. Receiver sees N pushes for N rapid messages instead of 1 coalesced push. Premium-chat user experience consideration; not a blocker.

**Ambiguity to resolve first:** Batching strategy — debounce-on-send (delay each push by ~3s and merge incoming siblings), OR per-conversation rate-limit (cap pushes per conversation per minute), OR FCM-side notification grouping (Android `setGroup` + iOS `thread-id`). The third option is purely client-display batching, no server change; cheapest. Likely correct first move.

**Action items:**
- [ ] When user growth or feedback signals notification fatigue, file OpenSpec change `chat-message-notification-per-conversation-batching`. Most likely shape: client-display grouping (Android `setGroup`, iOS `thread-id` keyed on `conversation_id`) shipped in `:infra:fcm`'s payload builders + minimal spec amendment to `fcm-push-dispatch`.
- [ ] If client-display grouping proves insufficient: add server-side debounce/coalesce in a separate change.
- [ ] Update `FOLLOW_UPS.md` to delete this entry once the change merges.

## firebase-admin-server-template-evaluate-bypass-removal

**Discovered during:** `content-moderation-keyword-lists` Phase 11 staging smoke — first request to `getServerTemplate(emptyDefaults).evaluate()` against the staging Firebase project threw `IllegalArgumentException: List of conditions must not be empty.` even though the project has 3 published Server-template parameters.
**Status:** open

**Finding:** Firebase Admin Java SDK 9.7.0+ has a regression in `ConditionEvaluator.evaluateConditions(...)`:

```java
checkArgument(!conditions.isEmpty(), "List of conditions must not be empty.");  // ← throws
if (context == null || conditions.isEmpty()) { return ImmutableMap.of(); }      // ← dead code
```

The early-return on the second line is unreachable because the `checkArgument` on the first line throws first. Original intent was clearly that empty conditions → empty conditions-evaluation map (the early-return guards it explicitly). The regression breaks `ServerTemplate.evaluate()` for any Firebase project that has parameters but zero conditions.

We work around this in [`infra/remote-config/.../RemoteConfigClient.kt`](infra/remote-config/src/main/kotlin/id/nearyou/app/infra/remoteconfig/RemoteConfigClient.kt) by bypassing `evaluate()` and parsing the template JSON via `template.toJson()` to extract `parameters.<name>.defaultValue.value` directly. Since our use case has no per-request condition evaluation (wordlists are platform-wide, not per-user / per-locale), the bypass is semantically equivalent.

**Specs at fault:** None — the spec calls for a `RemoteConfigClient` interface returning plain Kotlin types; the bypass is an implementation detail.
**Code at fault:** Firebase Admin Java SDK 9.7.0+ — `ConditionEvaluator.java` (vendor; we cannot fix directly).
**Docs at fault:** None.

**Impact (if shipped without bypass):** Total moderation pipeline unavailability — every `load()` call cascades through Tier 1 (Redis miss on first run) → Tier 2 (Remote Config throws IAE) → Tier 3 (repo-file placeholder, no real wordlist) → Tier 4 (Secret Manager, also empty unless populated) → fail-open `Verdict.Allow` for everything. Sentry would log per-call WARN/ERROR, but operationally the moderator becomes a no-op. **The bypass is therefore production-load-bearing**, not optional.

**Ambiguity to resolve first:** None.

**Action items:**
- [ ] Track upstream issue at [github.com/firebase/firebase-admin-java/issues](https://github.com/firebase/firebase-admin-java/issues) — file one if not yet reported.
- [ ] When the SDK fix lands (e.g., 9.9.0+), bump the pin in `gradle/libs.versions.toml` and revert `FirebaseServerConfigSource.fetchRawString` to use `template.evaluate() + ServerConfig.getString()` for cleaner code. The bypass JSON parser becomes equivalent-but-redundant; we can keep it as fallback or remove.
- [ ] Delete this entry once the SDK is bumped and the bypass is removed.

---

## content-moderation-cache-invalidation-endpoint

**Discovered during:** `content-moderation-keyword-lists` design.md D4 — Cache strategy: Redis 5-min TTL, no explicit invalidation.
**Status:** open

**Finding:** The moderation list loader uses a 5-minute Redis TTL with NO push-based invalidation. When operators edit the Remote Config wordlist, the change propagates within 5 min max (TTL elapse + next loader call). If operators report this is too stale (Month 3+ data), introduce an OIDC-authed `POST /internal/moderation-list-bust` endpoint that deletes the cache keys.

**Specs at fault:** None.
**Code at fault:** None — this is a future enhancement, not a bug.
**Docs at fault:** None.

**Impact (if shipped):** Low — 5-min staleness is acceptable for moderation (legal-advisor review is quarterly per [`docs/06-Security-Privacy.md:159`](docs/06-Security-Privacy.md)).

**Ambiguity to resolve first:** Endpoint shape — `POST /internal/moderation-list-bust` (clears all 3 keys) or per-list `?list=profanity`?

**Action items:**
- [ ] Defer until Month 3+ data shows the 5-min TTL is too stale.
- [ ] Implement under a new change `text-moderation-cache-invalidation-endpoint` if needed.
- [ ] Delete this entry once the change merges OR if 5-min TTL proves acceptable indefinitely.

---

## infra-sentry-kmp-module-isation

**Discovered during:** `mobile-app-scaffold-replace-wizard` design.md Decision 5 — Sentry KMP wiring was explicitly carved out of the scaffold change per the [`openspec/project.md`](openspec/project.md) § Mobile + Admin Scaffolding Priority menu Mobile #1 ("Sentry KMP wiring MAY split out as a focused follow-up `infra-sentry-kmp-module-isation` if scaffold scope grows beyond ~300 LOC").
**Status:** open

**Finding:** [`docs/04-Architecture.md`](docs/04-Architecture.md) § Observability Stack → Sentry KMP names `:infra:sentry` as the canonical module for unified Android + iOS + backend Sentry SDK wiring (with the `SentryProvider expect/actual` snippet documented inline). The module does NOT yet exist in `settings.gradle.kts`; the table in `docs/04-Architecture.md` § Dependency Isolation Pattern marks it `SCAFFOLD NEXT` for this follow-up. Scaffolding it requires: a new Gradle module + convention plugin alignment + `gradle/libs.versions.toml` Sentry SDK pins (Android Sentry SDK + Sentry KMP wrapper) + dSYM upload CI step (iOS) + ProGuard mapping upload CI step (Android) + iOS framework reconfig + GCP Secret Manager DSN slot wiring (`staging-sentry-dsn` + prod).

**Specs at fault:** None — the spec for `:infra:sentry` lands with this follow-up.
**Code at fault:** None — placeholder absent by design.
**Docs at fault:** None — `docs/04-Architecture.md` § Observability Stack → Sentry KMP already prescribes the API surface.

**Impact (if shipped):** Closes the "no crash reporting wired" gap for the mobile app — Android + iOS crashes go to Sentry with proper symbolication. Backend already has Sentry Java wired (see `:backend:ktor` config); the follow-up unifies the dashboard.

**Ambiguity to resolve first:** Sentry KMP SDK choice — Sentry's official SDK has improved KMP support since 2024; verify the latest stable supports Compose Multiplatform + Kotlin 2.3.x at proposal time.

**Action items:**
- [ ] File OpenSpec change `infra-sentry-kmp-module-isation` that scaffolds `:infra:sentry`, wires Sentry KMP SDK + per-platform initializers, adds dSYM + ProGuard mapping upload CI steps, provisions GCP Secret Manager DSN slots.
- [ ] Update `docs/04-Architecture.md` § Dependency Isolation Pattern table to flip `:infra:sentry` from `SCAFFOLD NEXT` to `shipped` once the change archives.
- [ ] Delete this entry once the change merges.

---

## mobile-ios-ci-link-task

**Discovered during:** `mobile-app-scaffold-replace-wizard` design.md Decision 6 + tasks.md Section 9 — iOS framework link CI was explicitly deferred because [`.github/workflows/ci.yml`](.github/workflows/ci.yml) does NOT currently run on a macOS runner (the project's CI is Linux-only), and wiring this up requires a paid macOS runner + Pod install step + codesign infrastructure for any future archive task.
**Status:** open

**Finding:** [`.github/workflows/ci.yml`](.github/workflows/ci.yml) `lint` / `test` / `migrate-supabase-parity` jobs all run on `ubuntu-latest`. There is no Linux-side way to invoke `:mobile:app:linkDebugFrameworkIosSimulatorArm64` because Kotlin/Native's iOS targets require Xcode + the macOS SDK. Until iOS CI lands, iOS build regressions can only be caught by the author running `./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64` locally on macOS during each mobile change's lifecycle. The `mobile-app-scaffold-replace-wizard` change verifies iOS locally per task 8.2 — every subsequent mobile change (#2-5) MUST do the same.

**Specs at fault:** None — `mobile-app-scaffold` spec requirement "Android and iOS targets build green" includes both targets, but the scenario "iOS framework link passes locally" is explicitly local-only because CI doesn't run it.
**Code at fault:** None — the production code builds correctly on iOS; CI just doesn't verify it.
**Docs at fault:** None.

**Impact (if shipped):** iOS build silently regresses between mobile changes if an author forgets to verify iOS locally. Risk grows as Mobile #2-5 land features that aren't structurally identical across Android + iOS (e.g., expect/actual splits). Currently mitigated by the convention that every mobile change author MUST run iOS locally before pushing — but that's a soft enforcement.

**Ambiguity to resolve first:** Runner cost vs frequency. GitHub Actions macOS minutes are ~10x the cost of Linux minutes. Options: (a) macOS runner on every mobile-touching PR, (b) macOS runner only on PRs that touch `mobile/**` or `iosApp/**` via path filter, (c) macOS runner only on PRs whose title matches `feat(mobile)` / `fix(mobile)`. Decide at proposal time.

**Update (2026-06-01) — CI feasibility research:** Confirmed possible on GitHub-hosted runners — they offer Apple-silicon macOS runners (up to M2) that run Xcode + the iOS simulator; the current Linux-only CI is a cost choice, NOT a hard limit. Cost nuance to weigh: macOS bills at ~10× the Linux multiplier and **free minutes are Linux-only** (100 macOS min draws 1000 from the allowance), and GitHub's Xcode images lag release + cap at M2 (the Jan-2026 ~39% hosted-runner price cut lowers macOS to ≈ $0.048/min but keeps the multiplier). Platform options for a KMP repo: (a) a **path-filtered `macos-14` lane on GitHub Actions** — lowest friction, one system, recommended for this build-verification job; (b) **Codemagic** (KMP-native: official `codemagic.yaml` sample, M2 + automatic signing, pay-as-you-go) if signing/release automation later gets painful; (c) **Bitrise** (mobile-first, M4 Pro, new Xcode <24h). **Xcode Cloud** (25 free hrs, auto-signing) is Apple-ecosystem-only → poor fit for shared KMP CI. Ref: [GitHub Actions runner pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing).

**Action items:**
- [ ] File regular PR (not OpenSpec — pure CI infra) `ci/mobile-ios-link-task` that adds a `mobile-ios-link` job to `.github/workflows/ci.yml` running on `macos-14` (or whichever Xcode-bundled runner is current), invoking `./gradlew :mobile:app:linkDebugFrameworkIosSimulatorArm64`. Wire path filter to `mobile/**` + `iosApp/**` + `gradle/libs.versions.toml` + `settings.gradle.kts` to bound cost.
- [ ] Update the `merge-gate` job's required-status-check matrix to include `mobile-ios-link` so the iOS build is mandatory at merge time on mobile-touching PRs.
- [ ] Delete this entry once the workflow change merges.

---

## mobile-negative-requirement-ci-grep

**Discovered during:** `mobile-app-scaffold-replace-wizard` round-1 review test-coverage lens finding B3 — six negative-requirement grep scenarios in `mobile-app-scaffold/spec.md` (no Ktor client, no ad-hoc HTTP, no auth identifiers, no FCM identifiers, no hardcoded API URLs, no backend/infra module deps) declare "WHEN grepping ... THEN no matches" but no CI step / Detekt rule enforces them.
**Status:** open

**Finding:** [`mobile-app-scaffold/spec.md` Requirement "Scaffold does not introduce networking, auth, or feature behavior"](openspec/specs/mobile-app-scaffold/spec.md) has six negative-grep scenarios. A future PR that adds `io.ktor:ktor-client-core` to `mobile/app/build.gradle.kts` or sneaks in `signIn` / `fcmToken` / `nearyou\.id` hardcoded URL references would ship green today — none of the existing CI lanes catch these. The canonical defense pattern in this project is a Detekt rule (see [`RawFromPostsRule`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/RawFromPostsRule.kt), [`BlockExclusionJoinRule`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/BlockExclusionJoinRule.kt), [`RedisHashTagRule`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/RedisHashTagRule.kt)) but extending Detekt to scan `:mobile:app` requires extending the Detekt source-set configuration (currently scoped to `:backend:ktor`'s `src/main/kotlin` only per [`build-logic/src/main/kotlin/nearyou.ktor.gradle.kts:20`](build-logic/src/main/kotlin/nearyou.ktor.gradle.kts)).

**Specs at fault:** [`mobile-app-scaffold/spec.md`](openspec/specs/mobile-app-scaffold/spec.md) — six scenarios are spec'd but not enforced.
**Code at fault:** None — the scaffold itself does NOT contain any forbidden identifier; the gap is enforcement against future regressions.
**Docs at fault:** None.

**Impact (if shipped without enforcement):** Mobile #3 (Google Sign-In) is the first change that legitimately adds auth identifiers — at that point the spec scenarios become moot for the mobile module's commonMain surface (auth lives in a dedicated namespace). The window of risk is the period before Mobile #3 ships. Specifically: Mobile #2 (`shared-resources-moko-bootstrap`) is a docs/strings-only change with no real auth-identifier risk; a coincidental violation is improbable but not impossible without a CI grep.

**Ambiguity to resolve first:** Detekt source-set extension cost. Options: (a) extend Detekt to scan `:mobile:app` `src/commonMain/kotlin` only — clean, mirrors backend pattern; (b) add a one-off shell script run in CI that greps `mobile/app/src/{commonMain,androidMain,iosMain}` — simpler but less integrated; (c) accept the gap until Mobile #3 ships and the negative requirements become obsolete.

**Action items:**
- [ ] File OpenSpec change `mobile-negative-requirement-detekt-rule` that adds a Detekt rule `MobileScaffoldNegativeRequirementsRule` to `:lint:detekt-rules`, scanning `:mobile:app` `src/commonMain/kotlin` for the six forbidden identifier patterns enumerated in the spec scenarios — **plus** the hardcoded-UI-strings axis from [`openspec/project.md`](openspec/project.md) § Coding Conventions ("Mobile strings: no hardcoded UI strings; must go through Compose Multiplatform Resources"), for which `shared-resources-swap-to-cmp-resources` (PR [#119](https://github.com/aditrioka/nearyou-id/pull/119)) ships an interim grep step in `tasks.md` Section 8.7. The eventual Detekt rule should cover both axes under a single rule, accepting `stringResource(Res.string.X)` / `Res.string.X` / `// hardcoded-string-allow:` as the valid accessor patterns (NOT the legacy `MR.strings.X` from Mobile #2's pre-swap Moko shipping). Wire the Detekt source-set extension in `build-logic`.
- [ ] Delete this entry once the rule ships AND Mobile #3's `proposal.md` updates the `mobile-app-scaffold` spec's negative requirements to acknowledge auth identifiers now belong to dedicated namespaces.

## mobile-compose-ui-tests-android-instrumented

**Discovered during:** `shared-resources-moko-bootstrap` `/opsx:archive` ([PR #116](https://github.com/aditrioka/nearyou-id/pull/116)) — surfaced when running the pre-archive `./gradlew :shared:resources:build` and `:shared:resources:testDebugUnitTest` failed with `NullPointerException: Cannot invoke "String.toLowerCase(java.util.Locale)" because "android.os.Build.FINGERPRINT" is null` on all 6 `ColorSchemeExtensionsTest.runComposeUiTest` cases.
**Status:** open

**Finding:** `androidx.compose.ui.test.runComposeUiTest` reads `Build.FINGERPRINT.toLowerCase(...)` to detect the runtime environment. Android JVM unit tests (`testDebugUnitTest`) stub `android.os.Build` static fields as `null` by default, so every Compose UI test crashes during framework init. The Compose UI test framework expects either Robolectric setup or instrumented (`androidTest/`) execution on a real device/emulator. `testOptions { unitTests.isReturnDefaultValues = true }` does NOT help — it stubs method returns, not static fields.

**Specs at fault:** None.
**Code at fault:** [`shared/resources/src/commonTest/kotlin/id/nearyou/resources/theme/ColorSchemeExtensionsTest.kt`](shared/resources/src/commonTest/kotlin/id/nearyou/resources/theme/ColorSchemeExtensionsTest.kt) runs correctly on iOS sim (`iosSimulatorArm64Test` PASSED with all 6 cases) but cannot run on Android JVM unit tests as written. Workaround applied in PR #116: [`shared/resources/build.gradle.kts`](shared/resources/build.gradle.kts) `testOptions { unitTests.all { it.exclude("**/ColorSchemeExtensionsTest*") } }` skips these tests on the Android JVM lane.
**Docs at fault:** None.

**Impact (if shipped without resolving):** Android-specific Compose UI behavior regressions would only surface on iOS sim runs, which is asymmetric coverage. In practice the `ColorSchemeExtensionsTest` cases test pure CompositionLocal wiring (no Android-specific behavior), so iOS sim execution is sufficient proof. But future Compose UI tests that DO touch Android-specific paths (e.g., adaptive layouts that read `WindowInsets`, dynamic color schemes that read `Material You`) would silently skip on this codebase until the gap is fixed.

**Ambiguity to resolve first:** Choice between Robolectric setup (heavier — adds `org.robolectric:robolectric` test dep + `@RunWith(AndroidJUnit4::class)` annotations, but keeps tests in `testDebugUnitTest` for fast feedback) vs `androidTest/` instrumented setup (canonical Android pattern, but requires CI emulator infrastructure that doesn't exist yet in `.github/workflows/ci.yml` and adds 5-10 min per test run).

**Update (2026-06-01) — CI feasibility research:** The "instrumented setup needs CI emulator infra that doesn't exist / `macos-latest`" assumption (action item 2) is now **outdated**. Since **April 2024**, GitHub-hosted **Linux** runners (`ubuntu-latest`) support **KVM hardware-accelerated Android emulators**, so [`reactivecircus/android-emulator-runner`](https://github.com/ReactiveCircus/android-emulator-runner) running `connectedCheck` works on the existing Linux lane — 2–3× faster than macOS AND inside the free-minutes allowance (just add a KVM-group-perms step). This re-scopes the heavier alternative from "blocked on a paid emulator lane" to "cheap, runs on current GitHub Actions Linux." The Robolectric path (action item 1) still remains the lower-friction fix for `ColorSchemeExtensionsTest` (pure CompositionLocal, no device behavior); the emulator lane is only worth it for genuinely device-dependent UI. Ref: [GitHub: hardware-accelerated Android virtualization (Apr 2024)](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/).

**Action items:**
- [ ] File an OpenSpec change `shared-resources-android-compose-ui-tests-robolectric` (recommended path — Robolectric is the lower-friction fix and keeps tests fast). Add `org.robolectric:robolectric` test dependency to `shared/resources/build.gradle.kts` androidUnitTest source set; annotate `ColorSchemeExtensionsTest` with `@RunWith(AndroidJUnit4::class)`; configure Robolectric to stub `Build.FINGERPRINT`; remove the exclude added by PR #116.
- [ ] Alternative (heavier): file `mobile-android-instrumented-test-ci-runner` to add a `macos-latest` or `ubuntu-latest`-with-Android-emulator job to `.github/workflows/ci.yml` for `connectedAndroidTest` execution.
- [ ] Delete this entry once either OpenSpec change ships AND the `unitTests.all { it.exclude(...) }` workaround is removed from `shared/resources/build.gradle.kts`.

## mobile-auth-signin-apple-ios

**Discovered during:** `mobile-auth-google-signin-flow` (Mobile #3) `/next-change` Phase A.4 — the user chose "Google Sign-In on both Android + iOS" so the substrate-proving change ships one SDK end-to-end; iOS-primary = Apple Sign-In is deferred.
**Status:** open

**Finding:** Two canonical docs prescribe iOS primary auth = Apple Sign-In at the eventual state — [`docs/03-UX-Design.md`](docs/03-UX-Design.md) § Auth Flow line 38 (`2. iOS: "Masuk dengan Apple" (primary, user-facing)`) and [`docs/04-Architecture.md`](docs/04-Architecture.md) § Tech Stack (`Auth | Google Sign-In (Android Credential Manager) + Apple Sign-In`). Mobile #3 ships Google on iOS as a substrate-proving stopgap (one SDK on both platforms is simpler to integrate end-to-end + carries less Apple-Developer-cert setup risk for the first auth integration). The Apple-Sign-In-iOS swap is real outstanding work.

**Specs at fault:** None — `openspec/specs/mobile-auth-signin/spec.md` (post-archive) ships Google-on-both deliberately; this follow-up adds the Apple path.
**Code at fault:** None — `GoogleSignInClient` iosMain is correct for Mobile #3; the Apple path is additive.
**Docs at fault:** None directly — the Mobile #3 status-tag note was added to `docs/03-UX-Design.md` § Auth Flow + `docs/04-Architecture.md` § Tech Stack in the 2026-05-30 triage sweep (formerly tracked by the `docs-ios-primary-auth-mobile-3-vs-eventual-state` entry, now closed).

**Impact (if shipped):** iOS users sign in with Google rather than the docs-prescribed Apple Sign-In. Functionally complete; the gap is the eventual-state UX + App Store review expectations (Apple requires Sign in with Apple when other social logins are offered, per App Store Review Guideline 4.8 — a launch-readiness concern, not an MVP blocker).

**Ambiguity to resolve first:** Whether Apple Sign-In iOS bundles with Mobile #4 (age-gate/signup) or ships as its own change. Apple Developer Program enrollment + entitlements + cert setup are the gating cost.

**Action items:**
- [ ] File OpenSpec change `mobile-auth-signin-apple-ios` adding an `AppleSignInClient` iosMain actual (`ASAuthorizationController` / Sign in with Apple) + swapping the iOS primary CTA to "Masuk dengan Apple"; keep Google as the Android primary; map the Apple identity-token exchange onto the same backend `/signin` contract (`provider: "apple"`, already supported by `auth-signin`).
- [ ] Delete this entry once that change ships.

---

## mobile-auth-signin-attestation-fingerprint-hash

**Discovered during:** `mobile-auth-google-signin-flow` (Mobile #3) Decision 9 — the `/signin` + `/refresh` request bodies omit `device_fingerprint_hash` because attestation (Play Integrity / App Attest) hasn't landed.
**Status:** open

**Finding:** `auth-signin/spec.md` accepts `device_fingerprint_hash` as optional ("MUST NOT be required for sign-in to succeed"). Mobile #3's `SignInRequest` carries only `{provider, id_token}` (verified by the §5.8a test + §9.6 grep). Fingerprint generation requires platform-specific entropy that lands canonically alongside attestation per [`docs/06-Security-Privacy.md`](docs/06-Security-Privacy.md) § Attestation.

**Specs at fault:** `openspec/specs/mobile-auth-signin/spec.md` (post-archive) — the "signin request body does not carry device_fingerprint_hash" scenario is intentional-for-now; the attestation change flips it.
**Code at fault:** [`AuthApiClient.kt`](mobile/app/src/commonMain/kotlin/id/nearyou/app/auth/AuthApiClient.kt) `SignInRequest` (no fingerprint field).
**Docs at fault:** None — `docs/06-Security-Privacy.md` § Attestation already names this as the landing context.

**Impact (if shipped):** Refresh-token rows persist with `device_fingerprint_hash = NULL` (compatible with the schema). No anti-abuse fingerprint binding until attestation lands — accepted pre-launch risk.

**Action items:**
- [ ] File OpenSpec change `mobile-auth-signin-attestation-fingerprint-hash` (likely bundled with the Play Integrity / App Attest change) that adds platform fingerprint generation + the `device_fingerprint_hash` body field to `SignInRequest` / `RefreshRequest`.
- [ ] Delete this entry once attestation + the fingerprint field ship.

---

## mobile-auth-signin-logout-wire-up

**Discovered during:** `mobile-auth-google-signin-flow` (Mobile #3) Non-Goals — no Settings screen ships in Mobile #3, so the backend logout endpoints have no mobile caller.
**Status:** open

**Finding:** [`openspec/specs/auth-session/spec.md`](openspec/specs/auth-session/spec.md) § Logout endpoints defines `POST /api/v1/auth/logout` (revoke one refresh token) + `POST /api/v1/auth/logout-all` (revoke all + bump `token_version`). Mobile #3 ships token persistence + the bearer-refresh client but no logout UI/caller — the only store-clear path today is the terminal-401 `SessionInvalidator`. A user-initiated logout lands with the Settings screen.

**Specs at fault:** None — `auth-session` already defines the endpoints; this is mobile-caller-wiring.
**Code at fault:** None — `AuthApiClient` + `SecureTokenStore.clear()` exist; a logout method + Settings CTA are additive.
**Docs at fault:** None.

**Impact (if shipped):** No user-facing sign-out until the Settings screen ships. Acceptable — there's no Settings surface in Mobile #3.

**Action items:**
- [ ] When the Settings screen ships, add `AuthApiClient.logout(refreshToken)` + `logoutAll()` + a Settings "Keluar" CTA that calls logout-all, clears `SecureTokenStore`, and routes to SignInScreen (reuse `SessionInvalidator`).
- [ ] Delete this entry once logout is wired.

---

## mobile-auth-signin-credential-manager-legacy-fallback

**Discovered during:** `mobile-auth-google-signin-flow` (Mobile #3) Decision 1 + propose-time WebSearch — Credential Manager can fail on older Android devices (API 24-27 with corrupted/old Play Services).
**Status:** open

**Finding:** The Android `GoogleSignInClient` actual uses Credential Manager exclusively; `GetCredentialException` maps to `GoogleSignInResult.Failed` → `NetworkError` UI ("Tidak bisa terhubung… Coba lagi"). On devices where Credential Manager is structurally unavailable, the user can never sign in. The deprecated `com.google.android.gms.auth.api.signin.GoogleSignInClient` legacy path is the documented fallback, deliberately NOT shipped in Mobile #3 (it's deprecated; the sealed `Failed` result degrades gracefully).

**Specs at fault:** None.
**Code at fault:** [`GoogleSignInClient.kt`](mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/GoogleSignInClient.kt) androidMain — Credential-Manager-only.
**Docs at fault:** None.

**Impact (if shipped):** A subset of older-device users may be unable to sign in. Severity Low (subset; the Failed result is diagnosable). Trigger to act: user reports / a Sentry signal showing a `GoogleSignInResult.Failed` rate above threshold.

**Ambiguity to resolve first:** Whether the legacy fallback is worth the deprecated-API dependency vs. just requiring a Play Services update. Decide on real signal.

**Action items:**
- [ ] If `GoogleSignInResult.Failed` rate (Sentry/OTel) exceeds threshold, file `mobile-auth-signin-credential-manager-legacy-fallback` adding a try-Credential-Manager-then-fall-back-to-legacy path in the androidMain actual.
- [ ] Delete this entry once the fallback ships OR the signal confirms it's unnecessary.

---

## mobile-auth-signin-suspended-user-copy-split

**Discovered during:** `mobile-auth-google-signin-flow` (Mobile #3) Decision 7 — the backend `/signin` emits `account_banned` for ANY `is_banned = TRUE` row without inspecting `suspended_until`, so temporarily-suspended users hit the permanent-ban copy.
**Status:** open

**Finding:** [`docs/03-UX-Design.md`](docs/03-UX-Design.md) § Suspension UX prescribes different copy for temporary suspension ("Akun kamu dalam suspensi sementara sampai {date}.") vs permanent ban ("Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."). Mobile #3 ships only the permanent-ban copy (`signin_error_banned`) as a uniform 403 path because the backend `/signin` doesn't differentiate `is_banned = TRUE AND suspended_until > NOW()` (temp) from `is_banned = TRUE AND suspended_until IS NULL` (permanent). `auth-jwt`'s middleware DOES differentiate (`account_suspended` vs `account_banned`) on authenticated requests, but `/signin` (pre-auth) does not.

**Specs at fault:** [`openspec/specs/auth-signin/spec.md`](openspec/specs/auth-signin/spec.md) § "Banned user blocked at sign-in" — emits `account_banned` uniformly; the eventual differentiation adds an `account_suspended` + `suspended_until` response shape. `openspec/specs/mobile-auth-signin/spec.md` (post-archive) ships the uniform copy as documented.
**Code at fault:** Backend `/signin` handler (the `is_banned` check) + mobile `AuthRepository` `403 → Banned` mapping (would gain a `Suspended` outcome + date-formatted copy).
**Docs at fault:** None — `docs/03-UX-Design.md` § Suspension UX already prescribes the split.

**Impact (if shipped long-term):** Temporarily-suspended users see "permanently deactivated, contact support" instead of "suspended until {date}" — misleading + generates avoidable support contacts. Acceptable short-term (both block sign-in correctly); the copy accuracy is the gap.

**Action items:**
- [ ] File OpenSpec change differentiating `/signin` 403s: backend emits `account_suspended` + `suspended_until` when `suspended_until > NOW()`, else `account_banned`; mobile adds a `SignInOutcome.Suspended(until)` + date-formatted `signin_error_suspended` copy.
- [ ] Delete this entry once the backend differentiation + mobile copy split ship.

---

## mobile-auth-signin-android-instrumented-encryption-test

**Discovered during:** `mobile-auth-google-signin-flow` (Mobile #3) `/opsx:apply` §3.5 — the Android raw-byte-leak encryption test for `SecureTokenStore` (DataStore + Tink) was deferred for lack of instrumented-test infra.
**Status:** open

**Finding:** `tasks.md` §3.5 specifies an Android test that writes `TokenPair("at-SENTINEL", "rt-SENTINEL", t)` then asserts NO file under the DataStore dir OR the Tink keyset dir contains the plaintext sentinels (+ a keyset-regeneration assertion). This needs the REAL Tink `AndroidKeysetManager` + Android Keystore crypto, which Robolectric does NOT faithfully emulate (its Keystore shadow may no-op the AEAD, making a raw-byte-leak assertion meaningless). Mobile #3 wired Robolectric for the Compose UI tests (`:mobile:app` androidUnitTest), but the encryption-leak test specifically needs a real device/emulator (`connectedAndroidTest`). Runtime correctness is currently covered by the §10.4 + §10.4b device smoke (real-token round-trip, uninstall/reinstall keyset regeneration); the architectural assertions (no `EncryptedSharedPreferences`, canonical master-key URI, no `kSecAttrAccessGroup`) are covered by the §9.10a-d CI greps.

**Specs at fault:** None — the round-trip contract is covered by `SecureTokenStoreContractTest`; this is the at-rest-encryption RUNTIME proof.
**Code at fault:** None — [`SecureTokenStore.kt`](mobile/app/src/androidMain/kotlin/id/nearyou/app/auth/SecureTokenStore.kt) androidMain is architecturally correct; the gap is automated runtime verification.
**Docs at fault:** None.

**Impact (if shipped without resolving):** A future refactor that accidentally drops the Tink AEAD wrap (e.g., writing the TokenPair as plain DataStore JSON) would not trip a CI assertion — only the §9.10b grep (no `EncryptedSharedPreferences`) + device smoke catch it. The raw-byte-leak guarantee has no fast-feedback automated test.

**Ambiguity to resolve first:** `connectedAndroidTest` needs a CI emulator runner (doesn't exist in `.github/workflows/ci.yml` today — see `mobile-ios-ci-link-task` for the analogous macOS-runner cost question). Decide emulator-CI cost vs. relying on device smoke.

**Update (2026-06-01) — CI feasibility research:** The "emulator CI lane doesn't exist" premise is **outdated** — GitHub-hosted **Linux** runners run KVM-accelerated Android emulators since **April 2024** ([`reactivecircus/android-emulator-runner`](https://github.com/ReactiveCircus/android-emulator-runner) on `ubuntu-latest`, free-minutes, no paid macOS lane), which unblocks the cheap path. Caveat specific to THIS test: the Tink AEAD raw-byte-leak assertion exercises the Android **Keystore-backed** master key, and a software emulator may not faithfully emulate hardware-backed AEAD — same risk the Finding flags for Robolectric. So if the leak assertion proves meaningless on an emulator, the real-device fallback is **Firebase Test Lab** (a physical-device `androidInstrumentedTest` run), NOT a self-hosted device. Ref: [GitHub: hardware-accelerated Android virtualization (Apr 2024)](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/).

**Action items:**
- [ ] File a change adding an `androidInstrumentedTest` `SecureTokenStoreEncryptionTest` (raw-byte-leak + keyset-regeneration assertions per §3.5) once an Android-emulator CI lane exists (or run it as a documented manual gate).
- [ ] Delete this entry once the instrumented encryption test ships.

---

## mobile-nearby-radius-slider

**Discovered during:** `mobile-nearby-timeline-screen` (Mobile #5) design D2 — the request uses `NEARBY_RADIUS_M = 20000` (Free-tier fixed 20 km); the radius slider is deferred (it depends on Premium-tier UX that isn't built).
**Status:** open

**Finding:** [`NEARBY_RADIUS_M`](mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/NearbyTimelineRepository.kt) is a single named constant (`20_000`) carrying the Free-tier fixed radius per [`docs/02-Product.md`](docs/02-Product.md) § Nearby Timeline ("*Free: stuck at 20km*"). The 4-position slider (10/20/50/100 km) with the Free-bounce-back-and-upsell + Premium-pick behavior is NOT shipped — the constant is the single site the follow-up generalizes.

**Specs at fault:** None.
**Code at fault:** None — `NEARBY_RADIUS_M` is the intended single generalization site.
**Docs at fault:** None — `docs/02-Product.md` § Nearby Timeline already describes the Free/Premium radius behavior.

**Impact (if shipped):** Free users cannot adjust the radius (it's the intended Free behavior); Premium radius selection is unavailable until Premium-tier UX + the slider land.

**Action items:**
- [ ] File OpenSpec change `mobile-nearby-radius-slider` adding the 10/20/50/100 km slider, the Free-bounce-back-to-20km + upsell behavior, and the Premium-pick path; replace the `NEARBY_RADIUS_M` call-site usage with the selected radius.
- [ ] Delete this entry once the slider ships.

---

## mobile-nearby-timeline-infinite-scroll

**Discovered during:** `mobile-nearby-timeline-screen` (Mobile #5) design D8 — the screen renders page 1 (≤ 30 posts) + pull-to-refresh; `next_cursor` is parsed/retained but load-more is deferred.
**Status:** open

**Finding:** [`NearbyTimelineOutcome.Loaded.nextCursor`](mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/NearbyTimelineFlow.kt) is parsed + retained, and [`NearbyTimelineApiClient.fetchNearby`](mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/NearbyTimelineApiClient.kt) accepts a `cursor` param, but the screen never issues a follow-up `cursor=`-bearing request. The backend `nearby-timeline` spec supports cursor pagination; only the mobile load-more UX (scroll-to-end detection + append) is missing.

**Specs at fault:** None — `openspec/specs/mobile-nearby-timeline/spec.md` § "Pull-to-refresh re-fetches the first page; infinite scroll is deferred" defers this deliberately.
**Code at fault:** None — the cursor plumbing exists; the load-more trigger is additive.
**Docs at fault:** None.

**Impact (if shipped):** Users see only the first 30 nearby posts until load-more lands. Acceptable for the scaffold; a real feed needs pagination.

**Action items:**
- [ ] File OpenSpec change `mobile-nearby-timeline-infinite-scroll` adding scroll-to-end detection in the `LazyColumn`, a `loadNextPage(cursor)` path on `NearbyTimelineFlow`, and append-to-list state handling.
- [ ] Delete this entry once load-more ships.

---

## mobile-timeline-empty-global-cta

**Discovered during:** `mobile-nearby-timeline-screen` (Mobile #5) design D7 — the empty-state copy implies a switch-to-Global action, but no Global screen exists yet, so the message renders without the button.
**Status:** open

**Finding:** The empty state renders `timeline_empty_nearby` ("*Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?*", [`docs/03-UX-Design.md`](docs/03-UX-Design.md) § Empty State) as a message only. The copy implies a "lihat Global" affordance, but there is no Global-timeline screen to navigate to, so shipping the button would create a dead control. The switch-to-Global CTA lands once a Global screen exists (a future tab-bar / Global-timeline change).

**Specs at fault:** None — `openspec/specs/mobile-nearby-timeline/spec.md` § "Screen state mapping" renders the empty message only, deferring the affordance.
**Code at fault:** None — the message-only empty state is intentional.
**Docs at fault:** None.

**Impact (if shipped):** The empty-area copy hints at an action the user can't take yet. Low — the message still informs; the affordance is additive once Global exists.

**Action items:**
- [ ] Once a Global-timeline screen ships, add a "lihat Global" CTA to the Nearby empty state that navigates to it (likely bundled with the Nearby/Following/Global tab-bar change).
- [ ] Delete this entry once the empty-state Global CTA is wired.

---

## timeline-response-dto-casing-drift

**Discovered during:** `mobile-nearby-timeline-screen` (Mobile #5) design D10 / multi-lens review — the mobile DTOs had to mirror the SHIPPED mixed-case wire, not the spec's snake_case JSON example.
**Status:** open

**Finding:** [`backend/.../timeline/TimelineRoutes.kt`](backend/ktor/src/main/kotlin/id/nearyou/app/timeline/TimelineRoutes.kt) `NearbyPostDto` / `FollowingPostDto` / `GlobalPostDto` (and their `*Response`) serialize **mixed-case**: `id`, `authorUserId`, `content`, `latitude`, `longitude`, `distanceM`, `createdAt`, and top-level `nextCursor` are **bare camelCase** (no `@SerialName`); only `city_name`, `liked_by_viewer`, `reply_count` carry `@SerialName` snake_case. But the [`nearby-timeline`](openspec/specs/nearby-timeline/spec.md) / [`following-timeline`](openspec/specs/following-timeline/spec.md) / [`global-timeline`](openspec/specs/global-timeline/spec.md) specs' Response-shape JSON examples show **uniform snake_case** (`author_user_id`, `distance_m`, `created_at`, `next_cursor`). The mobile client tracks the deployed wire (camelCase for those 4 fields), but the spec examples are stale relative to the shipped code — a client generated from the spec example would silently fail to parse those 4 fields. (See also [`reference_timeline_dto_camelcase_wire`] precedent from PR #128.)

**Specs at fault:** `openspec/specs/nearby-timeline/spec.md`, `following-timeline/spec.md`, `global-timeline/spec.md` — Response-shape JSON examples are snake_case while the shipped DTOs emit camelCase for `author_user_id`/`distance_m`/`created_at`/`next_cursor`.
**Code at fault:** `backend/.../timeline/TimelineRoutes.kt` — the DTOs emit camelCase for those 4 fields (no global `JsonNamingStrategy`).
**Docs at fault:** None beyond the specs above.

**Impact (if shipped):** Any future client generated from the spec JSON examples (rather than the shipped DTO) silently drops 4 fields. This is a backend↔spec contract drift, not a mobile bug (the Mobile #5 client correctly mirrors the deployed wire).

**Ambiguity to resolve first:** Two valid fixes — (a) add `@SerialName` snake_case to the backend DTOs to match the specs (then coordinate a mobile DTO update + bump the wire), OR (b) amend the three specs' Response-shape JSON examples to the camelCase reality. (b) is lower-risk (no wire change); (a) is more consistent (uniform snake_case) but a breaking wire change for any deployed client.

**Action items:**
- [ ] Backend owner: reconcile the casing drift — either add `@SerialName` to the three `*PostDto`/`*Response` DTOs to match the specs (+ coordinate a mobile DTO update), OR amend the `nearby-timeline`/`following-timeline`/`global-timeline` specs' JSON examples to the shipped camelCase.
- [ ] Delete this entry once the specs and the shipped wire agree.

---

## mobile-timeline-relative-timestamp

**Discovered during:** `mobile-nearby-timeline-screen` (Mobile #5) `/opsx:apply` §6.2 — the post card renders the `createdAt` DATE portion ("2026-05-31"), not a relative label ("2j lalu"), because relative formatting needs a localized unit-string set + a clock seam.
**Status:** open

**Finding:** [`NearbyTimelineScreen.postDateLabel`](mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/timeline/NearbyTimelineScreen.kt) renders `createdAt.substringBefore('T')` (the ISO date). Design D9 prescribes a "relative `created_at`" but a proper relative formatter ("baru saja" / "5 menit lalu" / "2 jam lalu" / "kemarin") needs (a) a localized Bahasa Indonesia unit-string set — which would extend this change's declared 5-string timeline surface (spec drift) — and (b) an injected clock for deterministic testing. Shipping the date is deterministic, needs no new strings, and is honest for a scaffold; relative formatting is a polish refinement.

**Specs at fault:** None — no `mobile-nearby-timeline` spec scenario asserts `created_at` rendering; D9 is the delegated "Claude proposes" visual.
**Code at fault:** None — `postDateLabel` is a correct, deterministic scaffold choice.
**Docs at fault:** None.

**Impact (if shipped):** The card shows an absolute date rather than a friendly relative label. Low — informative either way; relative time is a UX polish.

**Action items:**
- [ ] File a change (or fold into a later timeline-polish change) adding a pure `relativeTime(createdAtIso, now)` formatter + a localized BI unit-string set (`timeline_time_just_now` / `_minutes_ago` / `_hours_ago` / `_yesterday` / …), replacing `postDateLabel` at the card metadata row; inject the clock for deterministic tests.
- [ ] Delete this entry once relative timestamps ship.

---

## admin-destructive-action-rate-limit

**Discovered during:** `admin-suspend-unban-user-action` (Admin #5) `/opsx:apply` §11.1 — design D11 defers the per-admin destructive-action rate limiter (`docs/07-Operations.md` § Security: "Rate limit destructive actions: 20/hour per admin") to a focused follow-up.
**Status:** open

**Finding:** The admin panel's first state-changing actions (suspend / unban, `admin-user-moderation`) ship with NO per-admin rate limit. [`docs/07-Operations.md`](docs/07-Operations.md) § Security prescribes "20/hour per admin" for destructive actions; design D11 defers it because a correct limiter needs its own substrate decision that would balloon this first-write change. **This is deferred-MITIGATED, not a non-risk:** [`docs/07-Operations.md:92`](docs/07-Operations.md) explicitly notes TOTP is phishable (evilginx2), so the project's own threat model contemplates a phished admin session — without the limiter, such a session can mass-suspend (reversibly) at unbounded rate until the 30-min idle / 8-hour absolute session cap fires. Interim mitigations bound the blast radius: CSRF, the idle + absolute session caps, the IAP network gate, the full immutable audit trail, and reversibility (every suspend is undoable via unban).

**Specs at fault:** None — `admin-user-moderation` (post-archive) deliberately scopes out the limiter; this follow-up adds the requirement (a MODIFIED `admin-user-moderation` requirement or a new shared `admin-action-rate-limit` capability gating all admin writes).
**Code at fault:** None — there is no half-implemented limiter to fix; the deferral is clean.
**Docs at fault:** None — `docs/07-Operations.md` § Security already names the "20/hour per admin" target.

**Impact (if shipped):** Low-during-MVP (single trusted operator; the multi-admin period is gated behind WebAuthn per `admin-login`), rising with admin headcount. A compromised/phished admin session is the motivating threat; the audit trail + reversibility make the damage detectable and undoable, but unbounded-rate mass-suspension is a real (if recoverable) abuse vector until the limiter lands.

**Ambiguity to resolve first:** Counter substrate. Options: (a) a Redis-backed per-admin sliding-window counter (reuses the existing `RedisRateLimiter` machinery + the `{scope:<admin_id>}` hash-tag pattern, but a destructive-action limiter that *fails soft* when Redis is down is a weaker guarantee), vs (b) a `COUNT(*) FROM admin_actions_log WHERE admin_id = ? AND created_at > NOW() - INTERVAL '1 hour'` DB check (no new infra, authoritative, no fail-soft hole, but a read-per-write coupled to the audit-table write cadence). Resolve in the follow-up's design.md; (b) is the likely first cut given low admin write volume.

**Action items:**
- [ ] File an OpenSpec change adding a per-admin destructive-action rate limiter (~20/hour) gating `POST /admin/users/{id}/suspend` + `/unban` (and future admin writes), with the substrate decision (Redis sliding-window vs `admin_actions_log` COUNT) resolved in its design.md.
- [ ] Delete this entry once the limiter ships.

**Cap note (2026-06-02):** adding this entry brings `FOLLOW_UPS.md` to ~30 open entries — at the 30-entry hard limit. Added per CLAUDE.md "documented debt is still debt"; flag as a candidate for the next `/triage-follow-ups` sweep (the verified-still-valid deferred-work backlog + the GitHub-Issues migration noted in the 2026-06-01 sweep remain the drawdown levers).
