# Follow-ups

Transient working file for findings discovered during a change cycle that are NOT in scope of the current change but need a tracked owner. Per repo convention:

- Add an entry when a finding is real, fixable, but should NOT be silently swept into the current change's scope.
- Tick the action-item checkboxes as they are completed.
- **Delete the entry once all its action items are merged.** Do NOT let `triaged` entries linger — if residual work remains, either (a) move it to the canonical doc that owns the topic (e.g., launch-prerequisite tasks → `docs/08-Roadmap-Risk.md` Pre-Launch list, runbook tweaks → `docs/07-Operations.md` Deployment Runbook), or (b) replace the entry with a fresh one scoped to the residual work. Triaged-but-not-deleted entries are how this file rots.
- Delete the file itself when it has zero entries left.
- Recreate the file (with this same intro blurb) the next time a finding arises.
- **Hard limit: max 30 open entries.** When breached, force a triage sweep before adding new entries; entries open for >2 weeks are candidates for migration to GitHub Issues if the team grows beyond solo. Audit on 2026-05-10 (verify-only sweep) found 22 open + 0 triaged; below the hard limit. Today's sweep verified all 22 entries against current code/specs/docs and found no drift — no silently-resolved entries (none of the referenced OpenSpec change names exist in `openspec/changes/` or `archive/`; none of the referenced Detekt rules / helper scripts / test fixtures have shipped), no superseded entries, no migrations needed. Composition holds steady from the 2026-05-09 sweep: 10 deferred-by-trigger (Phase 3.5 schema, rule of three, user-growth signals, SDK upstream fixes) + 7 OpenSpec-shaped pending promotion via `/next-change` (Detekt-rule and observability/auth capability work) + 5 regular-PR-shaped test/coverage tightening work (FCM payload structure, FCM shutdown determinism, FCM composite wiring, reply rate-limit moderator-spy, chat block-check moderator-spy). PR #79 (`chore: triage FOLLOW_UPS.md (2026-05-09)`) preserves the deletion-evidence audit trail for the prior sweep.

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

## internal-endpoint-auth-otel-attributes

**Discovered during:** `observability-otel-foundation` `/next-change` Phase D round-3 cross-doc-impact finding #1 — the new capability's `user.id` requirement implicitly contradicts itself for `/internal/*` requests authenticated via Cloud Scheduler service-account OIDC, which have no `users` row to hash.
**Status:** open

**Finding:** The `observability-otel-foundation` capability spec mandates `user.id` "when the request is authenticated" — but `/internal/*` requests are OIDC-authenticated by service accounts with no `users` row. The same spec forbids raw JWT claims (`sub`, `aud`, `iss`) on spans, blocking the obvious "use the OIDC sub" workaround. The foundation change resolves this by carving out `/internal/*` from the `user.id` requirement and deferring a sanctioned `service.account.id` shape (truncated SHA-256 of OIDC `sub`, mirroring the [`internal-endpoint-auth/spec.md:18`](openspec/specs/internal-endpoint-auth/spec.md) WARN-log token-correlation-id pattern) to this follow-up.

**Specs at fault:** `openspec/specs/internal-endpoint-auth/spec.md` — currently has no positive requirement on span attributes for `/internal/*` requests.
**Code at fault:** None.
**Docs at fault:** None.

**Impact (if shipped without follow-up):** Low. `/internal/*` server spans still carry `http.route` + `http.status_code` (auto-instrumentation), so operator-side dashboards work. Missing piece is principal-correlation: an operator looking at a slow `/internal/unban-worker` span can't easily correlate it to a specific Cloud Scheduler invocation. The truncated-SHA-256 `sub` correlation id is already in the WARN-log surface ([`internal-endpoint-auth/spec.md:18`](openspec/specs/internal-endpoint-auth/spec.md)), so log-trace correlation works; only span-attribute query-by-correlation is missing.

**Ambiguity to resolve first:** Whether `service.account.id` is the right OTel semconv name (the standard exists at OTel semconv as `cloud.account.id` / `gcp.client_id` / similar — verify the most-aligned name before settling).

**Action items:**
- [ ] File OpenSpec change `internal-endpoint-auth-otel-attributes` that (a) MODIFIES the `internal-endpoint-auth` spec to require a sanctioned `service.account.id` (or whatever semconv name turns out aligned) attribute on `/internal/*` server spans, (b) populates it via truncated SHA-256(`sub` claim) per the existing WARN-log pattern, (c) MODIFIES the `observability-otel-foundation` capability spec's `user.id` requirement to flip the "does NOT apply to `/internal/*`" carve-out (or to coexist; design decision in the follow-up).
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

## firebase-app-extraction

**Discovered during:** `content-moderation-keyword-lists` Phase 3 — scaffolding `:infra:remote-config` while `:infra:fcm` already initializes its own `FirebaseApp` from the same `firebase-admin-sa` secret slot.
**Status:** open

**Finding:** Both `:infra:fcm` (`FirebaseAdminInit.NEARYOU_FIREBASE_APP_NAME = "nearyou-default"`) and the new `:infra:remote-config` (`FirebaseAdminInitForRemoteConfig.NEARYOU_REMOTE_CONFIG_APP_NAME = "nearyou-rc"`) initialize their own named `FirebaseApp` from the same secret JSON. The two consumers share the secret slot but isolate their lifecycles via the named-app mechanism. A future `:infra:firebase-app` extraction module that owns the `FirebaseApp` init + is depended on by both `:infra:fcm` and `:infra:remote-config` would be the cleanest factoring.

**Specs at fault:** None.
**Code at fault:** [`infra/fcm/.../FirebaseAdminInit.kt`](infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/FirebaseAdminInit.kt), [`infra/remote-config/.../FirebaseAdminInitForRemoteConfig.kt`](infra/remote-config/src/main/kotlin/id/nearyou/app/infra/remoteconfig/FirebaseAdminInitForRemoteConfig.kt).
**Docs at fault:** None (the duplication is intentional in the current shape).

**Impact (if shipped):** Low — the duplication is small (~50 LOC across both files) and the named-app pattern is already idiomatic. Refactoring a third Firebase consumer arrives, OR when the per-startup-cost of two `GoogleCredentials.fromStream(...)` calls becomes measurable in startup latency budgets.

**Ambiguity to resolve first:** Choose factoring shape — `:infra:firebase-app` exposes a `FirebaseAppRegistry` interface? Or just one shared `FirebaseApp` instance? Same-instance is simpler but breaks the lifecycle-isolation invariant the named-app pattern provides today.

**Action items:**
- [ ] Defer until a third Firebase consumer arrives (e.g., Firebase Auth, Firestore) OR until the duplication becomes painful in some other dimension.
- [ ] Delete this entry once the extraction lands.

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

## content-write-moderation-detekt-rule

**Discovered during:** `content-moderation-keyword-lists` Phase 10 — Detekt rule was DEFERRED per task 10.4 decision gate.
**Status:** open

**Finding:** A Detekt rule `ContentWriteRequiresModerationRule` would detect handlers that write user-supplied content to `posts.content` / `post_replies.content` / `chat_messages.content` without going through `TextModerator.moderate(...)`. This is defense-in-depth — the spec call-order requirement is currently enforced by 3 static-source-scan tests (`PostCreationCallOrderTest`, `PostRepliesCallOrderTest`, `ChatModerationCallOrderTest`), but a Detekt rule would catch violations at compile time vs. test time.

**Specs at fault:** None — the static-source-scan tests cover the contract.
**Code at fault:** None.
**Docs at fault:** None.

**Impact (if shipped):** Low — the static tests are the canonical enforcement; a Detekt rule is belt-and-suspenders. Net new value depends on how often new content-write paths are added.

**Ambiguity to resolve first:** Annotation reasons enumeration — `tombstone`, `admin_redaction`, `seed`. Are there other legitimate exceptions?

**Action items:**
- [ ] Add `lint/detekt-rules/.../ContentWriteRequiresModerationRule.kt` per the canonical detekt-rule structure (mirror `BlockExclusionJoinRule`, `RawFromPostsRule`, etc.).
- [ ] Wire the new rule into `lint/detekt-rules` config + the `:lint:detekt-rules` test harness.
- [ ] Add unit tests covering: handler with moderate-then-INSERT passes, handler without moderate fails, annotated allow-list cases pass, admin path passes.
- [ ] Delete this entry once the rule lands.

---

## reply-rate-limit-moderator-spy

**Discovered during:** `content-moderation-keyword-lists` Phase 8 task 8.7 — rate-limit-precedence test for the moderator-not-called scenario.
**Status:** open

**Finding:** The 21st-reply-attempt test in `ReplyRateLimitTest` already verifies the rate-limiter returns 429 (NOT 400-content-moderated). A direct mock-spy assertion that `TextModerator.moderate(...)` is not called for the 21st request would require constructor-level injection of a recording moderator into the existing rate-limit test infrastructure. The static call-order assertion in the existing PostRepliesCallOrderTest already enforces the route-layer ordering structurally.

**Specs at fault:** None.
**Code at fault:** None — this is test-coverage tightening, not a behavior bug.
**Docs at fault:** None.

**Impact (if shipped):** Low — the route-layer rate-limit gate runs BEFORE the body parse + service.post invocation, so the moderator is structurally never called. The static call-order assertion captures this contract.

**Ambiguity to resolve first:** None.

**Action items:**
- [ ] Add a `RecordingTextModerator` fixture into `ReplyRateLimitTest` and assert `moderateCallCount == 0` after the 21st attempt.
- [ ] Delete this entry once the test is added.

---

## chat-block-check-moderator-spy

**Discovered during:** `content-moderation-keyword-lists` Phase 9 task 9.6 — block-check-precedence test.
**Status:** open

**Finding:** Same shape as `reply-rate-limit-moderator-spy`. The block check inside `ChatRepository.sendMessage` runs BEFORE the `preInsertHookInTx?.invoke(conn)` call (which is where the moderator runs). The static call-order assertion in `ChatModerationCallOrderTest` enforces this ordering structurally. A direct mock-spy assertion would require fixture-injecting a recording moderator into the existing block-check test in `ChatFoundationRouteTest`.

**Specs at fault:** None.
**Code at fault:** None.
**Docs at fault:** None.

**Impact (if shipped):** Low — same as `reply-rate-limit-moderator-spy`.

**Ambiguity to resolve first:** None.

**Action items:**
- [ ] Add a `RecordingTextModerator` fixture into the chat block-check test and assert the moderator is not called when blocked.
- [ ] Delete this entry once the test is added.

---

## vendor-ahocorasick-detekt-guard

**Discovered during:** `content-moderation-keyword-lists` Phase 2 task 2.7 — Detekt-rules guard against introducing a vendor Aho-Corasick library.
**Status:** open

**Finding:** The spec `### Requirement: :core:domain MUST NOT depend on a vendor Aho-Corasick library` is currently enforced via reviewer attestation + the spec scenario "No vendor Aho-Corasick library on the :core:domain classpath" (which is a structural assertion against the resolved classpath). A Detekt-rules guard at `lint/detekt-rules/...` checking the `:core:domain` `build.gradle.kts` would be defense-in-depth.

**Specs at fault:** None — the spec scenario covers the contract.
**Code at fault:** None.
**Docs at fault:** None.

**Impact (if shipped):** Low — `:core:domain` currently has only one dependency (`kotlinx-serialization-json`); the surface for accidentally-adding `org.ahocorasick:*` is small. The lint rule is meaningful only after the dependency surface grows.

**Ambiguity to resolve first:** None.

**Action items:**
- [ ] Add a Detekt rule that scans `:core:domain` `build.gradle.kts` for `org.ahocorasick:*`, `com.hankcs:*` references.
- [ ] Delete this entry once the rule lands OR if `:core:domain` dep surface stays minimal indefinitely.

## production-deploy-workflow-cloud-run-flags-for-layer3

**Discovered during:** Issue [#88](https://github.com/aditrioka/nearyou-id/issues/88) resolution (2026-05-12) — staging investigation established that Cloud Run's default request-based CPU billing makes Layer 3 fire-and-forget dispatch fail-open 100% of the time. Staging deploy workflow was fixed via [PR #96](https://github.com/aditrioka/nearyou-id/pull/96); production deploy workflow does not yet exist.
**Status:** open

**Finding:** When the production deploy workflow lands (planned per [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Pre-Launch phase), it MUST mirror staging's Cloud Run sizing flags or Layer 3 moderation will fail-open 100% in production. The mandatory flags are:
- `--no-cpu-throttling` — switches to instance-based CPU billing so background coroutines (fire-and-forget Layer 3 dispatch after the 201 response) actually get CPU. Without this, the dispatch runs on Cloud Run's default ~5% throttled CPU and every async operation (Redis cache, TLS handshake, response parse) takes ~20x longer. Empirically verified in issue #88: identical Redis `isEnabled()` call measured 5-16ms during user request vs 2800-7400ms on background timer (1000x slowdown).
- `--cpu=2` — JVM headroom for the moderation dispatch + Hikari + OTel SDK; `cpu=1` was insufficient in iter 6 staging tests.
- `--min-instances=1` — eliminates cold-start of the Layer 3 path (Firebase RC cold init was measured at 14-16s on iter 14).
- `--memory=512Mi` — sufficient at staging volumes; production should re-evaluate after load testing.

The canonical source is now [`docs/06-Security-Privacy.md:185`](docs/06-Security-Privacy.md) (added via [PR #97](https://github.com/aditrioka/nearyou-id/pull/97)) which documents `--no-cpu-throttling` as a hard requirement for any environment running Layer 3.

**Specs at fault:** None — the spec is engine/vendor-neutral about deployment.
**Code at fault:** None — application code is correct; the issue is purely deployment config.
**Docs at fault:** `docs/06-Security-Privacy.md:185` covers the requirement after PR #97, but the production deploy workflow file (when authored) needs to reference + apply it.

**Impact (if shipped without these flags):** Layer 3 moderation fails-open ~100% in production. Posts with >0.8 toxicity score escape `is_auto_hidden = TRUE` flagging and reach the global timeline. The moderation_queue stays empty for Layer 3 triggers. Cost saving vs. always-on CPU billing is meaningless if the capability is non-functional.

**Cost trade-off:** Instance-based billing charges for CPU continuously. For a single production instance, roughly ~\$15-30/month additional vs. request-based default. Required to make Layer 3 functional; non-negotiable for any production Layer 3 deployment.

**Ambiguity to resolve first:** None — the staging deploy workflow at [`.github/workflows/deploy-staging.yml:118-128`](.github/workflows/deploy-staging.yml) is the reference implementation; copy-paste with prod secret names + URL substitutions.

**Action items:**
- [ ] When the production deploy workflow file is authored (separate change), mirror staging's `--cpu=2 --min-instances=1 --no-cpu-throttling --memory=512Mi --concurrency=80 --max-instances=1` flag set.
- [ ] Add a `merge-gate` check OR a Detekt-style YAML lint that asserts any new `deploy-*.yml` workflow targeting Cloud Run with Layer 3 enabled carries `--no-cpu-throttling`. Or accept this as a manual-review gate per `docs/06-Security-Privacy.md:185`.
- [ ] Delete this entry once the production deploy workflow ships with the correct flags.
