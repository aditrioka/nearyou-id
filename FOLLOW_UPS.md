# Follow-ups

Transient working file for findings discovered during a change cycle that are NOT in scope of the current change but need a tracked owner. Per repo convention:

- Add an entry when a finding is real, fixable, but should NOT be silently swept into the current change's scope.
- Tick the action-item checkboxes as they are completed.
- **Delete the entry once all its action items are merged.** Do NOT let `triaged` entries linger — if residual work remains, either (a) move it to the canonical doc that owns the topic (e.g., launch-prerequisite tasks → `docs/08-Roadmap-Risk.md` Pre-Launch list, runbook tweaks → `docs/07-Operations.md` Deployment Runbook), or (b) replace the entry with a fresh one scoped to the residual work. Triaged-but-not-deleted entries are how this file rots.
- Delete the file itself when it has zero entries left.
- Recreate the file (with this same intro blurb) the next time a finding arises.
- **Hard limit: max 30 open entries.** When breached, force a triage sweep before adding new entries; entries open for >2 weeks are candidates for migration to GitHub Issues if the team grows beyond solo. **Triage-sweep log** (per-sweep entry-level detail lives in each sweep's PR / git history):
  - **2026-05-09** — PR [#79](https://github.com/aditrioka/nearyou-id/pull/79) (`chore: triage FOLLOW_UPS.md (2026-05-09)`) preserves that sweep's deletion-evidence audit trail.
  - **2026-05-10** — 22 open, 0 triaged.
  - **2026-05-30** (full sweep, post-archive of `mobile-auth-google-signin-flow`) — 38 → 32 open, 0 rot. Closed 6 (3 docs-only fixes inline + 1 migrated to `docs/08-Roadmap-Risk.md` Pre-Launch + 2 accept-the-gap deletes); `system-actor-and-worker-audit-rows` promoted to `/next-change`. Breach = genuine deferred-work volume (PR #122 `mobile-auth-signin-*` cluster + otel cluster), not rot.
  - **2026-05-31** (targeted, `mobile-nearby-timeline-screen` apply §11.1) — added 6 Mobile-#5 deferrals → 37 open.
  - **2026-06-01** (full sweep) — 37 → 35 open, 0 rot. Migrated 2 to [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) Pre-Launch #6/#7 (`mobile-location-permission-flow`, `mobile-age-gate-stronger-verification` — the latter surfaces the **PP 17/2025 "PP TUNAS"** age-assurance deadline, previously absent from the roadmap). Surfaced a 6-entry test-coverage chore-PR scope (`fcm-payload-structural-tests`, `fcm-shutdown-drain-deterministic-tests`, `fcm-end-to-end-composite-test`, `reply-rate-limit-moderator-spy`, `chat-block-check-moderator-spy`, `mobile-theme-light-dark-direct-test` — the last still open because its two theme color-scheme scenarios remain untested in `:mobile:app` despite Mobile #5 shipping; merging the bundle → ~29 open). Kept 7 dormant-until-external-trigger entries (GitHub-Issues migration deferred; still solo-operator); promotions deferred. **Ended 35 open, 5 over the limit** — residual is verified-still-valid deferred work, not rot; the test-coverage bundle is the next drawdown lever.
  - **2026-06-04** (full sweep) — 32 → 28 open, **0 rot**: all 32 verified still-valid against current code/specs/docs (zero silently-resolved, zero superseded). Migrated 3 launch-gated entries to their canonical homes (`mobile-auth-signin-apple-ios` → [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) § Phase 3 iOS; `mobile-auth-signin-attestation-fingerprint-hash` → `docs/08` § Phase 3 + [`docs/06-Security-Privacy.md`](docs/06-Security-Privacy.md) § Attestation; `admin-app-revoke-staging-and-prod` residual → [`docs/07-Operations.md`](docs/07-Operations.md) § Data Access Pattern + Pre-Launch gate). Reconciled `post-creation-spec-error-enumeration-stale` inline (the `post-creation` spec's "exactly [5] codes" line now includes the 6th, `content_moderated_profanity`). Promoted `mobile-location-acquisition-latency` to a `/next-change` hand-off (entry stays open until that change ships). User accepted the remaining 28 as verified-valid backlog (no forced accept-the-gap deletes); GitHub-Issues migration still deferred (solo-operator). Audit trail in this sweep's PR.
  - **2026-06-06** (targeted, `admin-rejected-identifiers-viewer` archive) — **four** same-day changes merge-reconciled: `mobile-env-launcher-icons` (+1), `mobile-home-tab-host` (net 0 — deleted 2 precondition entries, added 2), `admin-report-queue-viewer` (+2 spec-mandated), and `admin-rejected-identifiers-viewer` (+1) → **33 open, 3 over the soft cap**. Per the "next add MUST sweep first" rule a targeted re-verification ran over the 8 likeliest-resolved entries (sub-agent, with file:line/CI/spec evidence): **0 rot — all 8 still-valid**, consistent with the 2026-06-04 full sweep. Nothing prunable, so the file rests at **31 open: verified-valid deferred work, not rot** (cf. the 2026-06-01 "35 open, 5 over — not rot" posture). Standing drawdown levers: merge `ci/mobile-android-emulator-encryption-test` (`c21c630`) → resolves `mobile-auth-signin-android-instrumented-encryption-test`; GitHub-Issues migration (still solo-operator). Per-entry detail in the consolidated sweep note at end-of-file.
  - **2026-06-08** (full sweep, dedicated session) — triaged the full backlog, **now entirely on `main`** after #162 (bottom-nav, +5) and #160 (report-queue resolution — which self-deleted `admin-report-queue-resolution-actions`, −1) landed mid-sweep → **42 open**. **0 rot** — all 42 verified still-valid against current code/specs/archive via parallel code-reading sub-agents (zero silently-resolved, zero superseded), consistent with the 2026-06-04/06 sweeps. Drew **42 → 35** via **7 migrations** to [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) (Pre-Launch items 8/9 = mobile CI lanes + per-admin destructive-action rate limit; Phase 3 Post-scaffold refinements = nearby-radius-slider, following-timeline, relative-timestamp, nav3-adaptive). **Superseded + closed PR #164** (stale: pre-#157 base diverged 3 merges; carried a wrong delete) — this sweep ([#166](https://github.com/aditrioka/nearyou-id/pull/166)) redoes its 7 migrations on current `main` and **rescopes** `mobile-negative-requirement-ci-grep` to its still-unenforced hardcoded-UI-strings axis (NOT deleted: the networking/auth axes are superseded by Mobile #3's in-spec carve-outs, but the strings axis has no automated gate). Surfaced 4 hand-offs (`in-app-notifications-spec-wire-reconciliation` + `like-rate-limit-sliding-window-vs-fixed-window-semantic` → regular/docs PR; `mobile-auth-signin-suspended-user-copy-split` + `backend-single-post-get-endpoint` → `/next-change`). Residual 35 = verified-valid spec-obliged backlog; the **GitHub-Issues migration** (still solo-operator) is the structural lever to get sustainably under 30.

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

## mobile-post-creation-refresh-nearby-on-return

**Discovered during:** `mobile-post-creation-screen` proposal (deferral; design D8).
**Status:** open

**Finding:** On a successful post the composer pops back to Home but does NOT refresh the Nearby feed, so the just-created post is invisible until the user pulls-to-refresh (the feed re-fetches only on its own reload key / `ON_RESUME` gate, not on a child-screen pop). Showing it immediately needs a cross-screen reload signal.

**Specs at fault:** none — deliberate, `mobile-post-creation` design D8.
**Code at fault:** none yet (PR [#145](https://github.com/aditrioka/nearyou-id/pull/145) in flight).
**Docs at fault:** none.

**Impact (if shipped):** Minor UX rough edge — the post succeeds but is not reflected in Nearby until a manual pull-to-refresh.

**Action items:**
- [ ] After the composer ships, wire a one-shot Nearby reload on composer success (a shared reload trigger or a Nav3 `ResultEventBus` signal) so the new post appears on return without a manual refresh.

---

## mobile-post-creation-ios-flow-tests

**Discovered during:** `mobile-post-creation-screen` Phase D test-coverage lens.
**Status:** open

**Finding:** The composer's commonTest projection + MockEngine tests + the Android Robolectric screen test cover the logic, but there is no `mobile/app/src/iosTest` coverage. The Nearby capability got iOS flow tests as a SEPARATE change ([#143](https://github.com/aditrioka/nearyou-id/pull/143): `NearbyTimelineFlowIosTest` etc.); the composer should get the same parity treatment.

**Specs at fault:** none.
**Code at fault:** none yet (PR [#145](https://github.com/aditrioka/nearyou-id/pull/145) in flight).
**Docs at fault:** none.

**Impact (if shipped):** iOS-actual behavior of the composer flow is verified only by the manual iOS-sim pass, not by an automated `iosTest` — a parity gap vs the Nearby surface.

**Action items:**
- [ ] After the composer ships, add `mobile/app/src/iosTest` flow coverage mirroring #143's Nearby iOS flow tests (CMP 1.11.1 `runComposeUiTest`).

---

## observability-otel-collector-tail-sampling

**Discovered during:** `observability-otel-foundation` `/next-change` Phase D round-3 adversarial-lens finding #11 — the round-1 design § D4 force-keep `SpanProcessor` re-emitting via `Tracer.spanBuilder().setNoParent()` is structurally wrong: it creates a fresh root span detached from the original trace, breaking trace_id linkage in Tempo.
**Status:** open

**Finding:** The canonical sampling profile at [`docs/05-Implementation.md:2042`](docs/05-Implementation.md) prescribes "10% base + 100% errors + 100% slow (>500ms)" in production. The `observability-otel-foundation` change ships only the 10% base via `ParentBased(TraceIdRatioBased(0.1))` — the force-keep tail (errors + slow) is deliberately deferred because correctly preserving trace_id linkage on force-keep promotion requires OTel Collector tail sampling, which is meaningful infrastructure work the architecture doc explicitly defers at [`docs/04-Architecture.md`](docs/04-Architecture.md): _"Tail sampling via OTel Collector if volume is high"_. Until this follow-up ships, MVP production accepts that 90% of error/slow traces drop; structured JSON logging at 100% retention via Cloud Logging is the authoritative incident-replay surface.

**Specs at fault:** `openspec/specs/observability-otel-foundation/spec.md` (post-archive) — its sampling-profile requirement explicitly does NOT promote error/slow spans; this follow-up adds that promotion via Collector.
**Code at fault:** None — there is no half-implemented force-keep in this change to fix; the deferral is clean.
**Docs at fault:** None — [`docs/04-Architecture.md`](docs/04-Architecture.md) already names the Collector as the upgrade path.

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

**Impact (if shipped):** Low — 5-min staleness is acceptable for moderation (legal-advisor review is quarterly per [`docs/06-Security-Privacy.md`](docs/06-Security-Privacy.md)).

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

## mobile-negative-requirement-ci-grep

**Discovered during:** `mobile-app-scaffold-replace-wizard` round-1 review test-coverage lens finding B3. **Rescoped (2026-06-08 triage):** the original six-axis scope (no Ktor client / no ad-hoc HTTP / no auth identifiers / no FCM identifiers / no hardcoded API URLs / no backend-infra deps) is **narrowed to the hardcoded-UI-strings axis only** — the networking/auth/FCM axes are obsolete now that Mobile #3+ legitimately ship those in dedicated namespaces, and `mobile-app-scaffold/spec.md` (≈ lines 112-122) already encodes the carve-outs in-spec (the scenarios self-maintain). The strings axis is the one residual with **no automated enforcement**.
**Status:** open

**Finding:** No Detekt rule or CI grep enforces the [`openspec/project.md`](openspec/project.md) § Coding Conventions invariant "Mobile strings: no hardcoded UI strings; must go through Compose Multiplatform Resources" against `:mobile:app`. The existing guards do NOT cover it: `SharedStringsCatalogTest` is a compile-time catalog-*completeness* check (a missing `Res.string` key fails the build) — not a hardcoded-literal scanner; the per-screen `*SourceGuardTest`s assert per-capability PII discipline (`Authorization` / `Bearer` / token-name absence), not raw UI strings. So a future PR adding a bare `Text("Masuk")` instead of `stringResource(Res.string.…)` ships green. The canonical defense is a Detekt rule (cf. [`RawFromPostsRule`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/RawFromPostsRule.kt) / [`BlockExclusionJoinRule`](lint/detekt-rules/src/main/kotlin/id/nearyou/lint/detekt/BlockExclusionJoinRule.kt)), but Detekt is still scoped to `:backend:ktor`'s `src/main/kotlin` only ([`build-logic/src/main/kotlin/nearyou.ktor.gradle.kts:20`](build-logic/src/main/kotlin/nearyou.ktor.gradle.kts)) — extending it to scan `:mobile:app` `src/commonMain/kotlin` is the work.

**Specs at fault:** None — the strings invariant lives in `openspec/project.md` § Coding Conventions; enforcement is the gap. (The original `mobile-app-scaffold` networking/auth scenarios are now self-maintaining via the Mobile #3 in-spec carve-outs — no longer part of this entry's scope.)
**Code at fault:** None — no `:mobile:app` source contains a forbidden hardcoded string today; the gap is enforcement against future regressions.
**Docs at fault:** None.

**Impact (if shipped without enforcement):** A hardcoded UI string silently regresses the CMP-Resources invariant (no fast-feedback gate; caught only in manual review). Low at current velocity; rises as the mobile surface grows.

**Ambiguity to resolve first:** Enforcement shape. Options: (a) extend the Detekt source-set to `:mobile:app` `src/commonMain/kotlin` + add a `MobileHardcodedStringRule` accepting `stringResource(Res.string.X)` / `Res.string.X` / `// hardcoded-string-allow:` as valid accessors — mirrors the backend Detekt pattern; (b) a one-off CI grep step over `mobile/app/src/{commonMain,androidMain,iosMain}` — simpler, less integrated.

**Action items:**
- [ ] File OpenSpec change `mobile-hardcoded-string-detekt-rule` adding a Detekt rule that scans `:mobile:app` `src/commonMain/kotlin` for hardcoded UI string literals (accepting the `Res.string` accessors + a `// hardcoded-string-allow:` escape), wiring the Detekt source-set extension in `build-logic`.
- [ ] Delete this entry once the rule ships.

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

## mobile-nearby-timeline-infinite-scroll

**Discovered during:** `mobile-nearby-timeline-screen` (Mobile #5) design D8 — the screen renders page 1 (≤ 30 posts) + pull-to-refresh; `next_cursor` is parsed/retained but load-more is deferred.
**Status:** open

**Finding:** [`NearbyTimelineOutcome.Loaded.nextCursor`](mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/NearbyTimelineFlow.kt) is parsed + retained, and [`NearbyTimelineApiClient.fetchNearby`](mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/NearbyTimelineApiClient.kt) accepts a `cursor` param, but the screen never issues a follow-up `cursor=`-bearing request. The backend `nearby-timeline` spec supports cursor pagination; only the mobile load-more UX (scroll-to-end detection + append) is missing. **Extended by `mobile-home-tab-host` (2026-06-06):** the new Global feed mirrors this exactly — [`GlobalTimelineOutcome.Loaded.nextCursor`](mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/GlobalTimelineFlow.kt) is parsed/retained and [`GlobalTimelineApiClient.fetchGlobal`](mobile/app/src/commonMain/kotlin/id/nearyou/app/timeline/GlobalTimelineApiClient.kt) accepts a `cursor`, but no load-more is wired. **Extended by `mobile-post-detail-screen` (#159):** the replies list mirrors this — [`RepliesOutcome.Loaded.nextCursor`](mobile/app/src/commonMain/kotlin/id/nearyou/app/post/PostDetailFlow.kt) (from `ReplyListResponse`'s snake_case `next_cursor`) is parsed/retained by `PostDetailRepository.loadReplies`, but `PostDetailScreen` never issues a `cursor=`-bearing follow-up `GET /api/v1/posts/{post_id}/replies`. **Extended by `mobile-bottom-nav-sections-and-notifications`:** the notifications feed mirrors this too — [`NotificationsOutcome.Loaded.nextCursor`](mobile/app/src/commonMain/kotlin/id/nearyou/app/notifications/NotificationsFlow.kt) is parsed/retained from the opaque `next_cursor` and [`NotificationsApiClient.fetch`](mobile/app/src/commonMain/kotlin/id/nearyou/app/notifications/NotificationsApiClient.kt) accepts a `cursor` (passed back verbatim), but `NotificationsScreen` wires only pull-to-refresh, no scroll-to-end load-more. This follow-up now covers load-more for the Nearby, Global, AND notifications feeds + the post-detail replies list.

**Specs at fault:** None — `openspec/specs/mobile-nearby-timeline/spec.md` § "Pull-to-refresh re-fetches the first page; infinite scroll is deferred" (and the matching `mobile-global-timeline` § "Pull-to-refresh re-fetches the first page; infinite scroll is deferred") defer this deliberately.
**Code at fault:** None — the cursor plumbing exists on both feeds; the load-more trigger is additive.
**Docs at fault:** None.

**Impact (if shipped):** Users see only the first page of Nearby AND Global posts (and the first page of a post's replies) until load-more lands. Acceptable for the scaffold; a real feed / reply thread needs pagination.

**Action items:**
- [ ] File OpenSpec change `mobile-nearby-timeline-infinite-scroll` adding scroll-to-end detection in the `LazyColumn`, a `loadNextPage(cursor)` path on `NearbyTimelineFlow`, `GlobalTimelineFlow`, **and `NotificationsFlow`** (and a `loadMoreReplies(cursor)` path on `PostDetailFlow`), and append-to-list state handling for **all three feeds (Nearby, Global, notifications) + the post-detail replies list**.
- [ ] Delete this entry once load-more ships for all three feeds and the replies list.

---

## mobile-home-tab-host-per-tab-backstacks

**Discovered during:** `mobile-home-tab-host` proposal review (Nav-model decision A) + `/opsx:apply` design D1 — the tab host renders each tab's screen directly under `HomeRoute` (selection via a serializable `Tab` enum in `rememberSaveable`), NOT per-tab `NavDisplay` back stacks, because there is no intra-tab navigation yet (no post detail / profile), so per-tab back stacks would be vestigial structure with nothing to push.
**Status:** open

**Finding:** [`HomeScreen`](mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/home/HomeScreen.kt) holds the selected `Tab` in `rememberSaveable` and renders the tab's screen via `when(selectedTab)` directly under the `HomeRoute` `NavEntry`, so each feed's `viewModel { }` is `HomeRoute`-scoped and survives tab switches (design D1/D2). No tab-root `NavKey` and no per-tab `NavDisplay` are declared. Per-tab back stacks (one saveable back stack per tab — the shape the original `mobile-home-tab-host` FOLLOW_UP sketched) land with the FIRST intra-tab destination (post detail / profile / chat): a `viewModel { }` resolved *inside* a per-tab `NavDisplay` scopes to that per-tab entry, whose store clears on tab switch → re-fetch, contradicting the no-refetch requirement — so introducing per-tab back stacks needs the design rework D1 deferred (hoisting the feed VMs out to preserve `HomeRoute` scoping).

**Specs at fault:** None — `openspec/specs/mobile-home-tab-host/spec.md` § "Tab selection is serializable and survives process death" deliberately defers per-tab `NavDisplay` back stacks (scenario "No per-tab NavDisplay or tab-root NavKey is introduced"); this follow-up MODIFIES that requirement when an intra-tab destination appears.
**Code at fault:** None — the direct-render tab host is the intended shape with no intra-tab nav.
**Docs at fault:** None.

**Impact (if shipped):** None today (no intra-tab navigation exists). Without per-tab back stacks, the first intra-tab destination (e.g. tapping a post → detail) has no per-tab stack to push onto; that lands with the destination.

**Action items:**
- [ ] When the first intra-tab destination ships (post detail / profile), file OpenSpec change `mobile-home-tab-host-per-tab-backstacks` introducing per-tab `NavDisplay` back stacks (a tab-keyed back-stack map) while preserving the `HomeRoute`-scoped feed-VM no-refetch invariant; MODIFIES `mobile-home-tab-host` § "Tab selection is serializable and survives process death".
- [ ] Delete this entry once per-tab back stacks ship.

---

## admin-rejected-identifiers-clear-action

**Discovered during:** `admin-rejected-identifiers-viewer` (`/opsx:apply` §8.3; design D3 defers the manual support-clear write action — the read-only viewer ships first).

**Status:** open

**Finding:** `admin-rejected-identifiers-viewer` ships the read-only `GET /admin/rejected-identifiers` triage table but NOT the manual support-clear action — the admin removal of a `rejected_identifiers` row so a falsely-rejected legitimate adult can re-verify (the "purgeable via legitimate adult re-verification workflow" path in [`docs/05-Implementation.md`](docs/05-Implementation.md) § Rejected Identifiers Schema). Until the clear action ships, clearing a rejected identifier remains the existing out-of-band raw-SQL `DELETE` a human runs against the Supabase dashboard. The viewer captures this as an explicit spec requirement ("The manual support-clear action is deferred to a fast-follow change") + a negative guard ("No clear / remove control is wired in this change"), so the fast-follow has a concrete requirement to MODIFY rather than inventing scope.

**Specs at fault:** none — `admin-rejected-identifiers-viewer` (post-archive) deliberately scopes out the write action; this follow-up MODIFIES the deferral requirement into the actual clear capability.
**Code at fault:** none — there is no half-implemented mutation to fix; the deferral is clean (read-only repository + GET-only route).
**Docs at fault:** none — `docs/07-Operations.md` § Core Features already describes the viewer's clear half as the deferred action; `docs/05-Implementation.md` names the re-verification workflow.

**Impact (if shipped):** Low-during-MVP (single trusted operator; the raw-SQL clear path works today and the viewer at least makes the row discoverable — find the hash, then run the existing manual clear). The clear action is materially more sensitive than the read view (destructive, must be role-gated + CSRF-gated + audit-logged + rate-limited), which is exactly why it is isolated into its own change.

**Ambiguity to resolve first:** Rate-limiter substrate — the clear action MUST be rate-limited per the destructive-action budget, which depends on the per-admin destructive-action rate limiter now tracked at [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) § Pre-Launch item 9 (substrate = Redis sliding-window vs `admin_actions_log` COUNT, decided at its design time — migrated out of `FOLLOW_UPS.md` in the 2026-06-08 sweep). Sequence this change AFTER (or co-design it WITH) the rate-limiter so the clear action lands behind a working limiter rather than re-deferring it.

**Action items:**
- [ ] File an OpenSpec change `admin-rejected-identifiers-clear-action`: a role-gated + CSRF-gated + audit-logged (`admin_actions_log`, e.g. action type `rejected_identifier_cleared`) + rate-limited `POST`/`DELETE` to remove a `rejected_identifiers` row, MODIFYing the viewer's deferral requirement.
- [ ] Resolve the rate-limiter dependency first (the per-admin destructive-action limiter — [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) § Pre-Launch item 9).
- [ ] Delete this entry once the clear action ships.

**Cap note:** see the consolidated 2026-06-06 cap + sweep note at the end of this file for the day's full accounting (a targeted sweep found 0 rot — nothing prunable). The optional `admin-rejected-identifiers-keyset-index` lever (design.md D2) is intentionally NOT logged (it stays a contingency in the change's design.md until cardinality actually grows).

---

## admin-report-queue-has-edit-history-filter

**Discovered during:** `admin-report-queue-viewer` archive §8.2 (deferred-by-design; recorded as the same spec requirement).

**Status:** open

**Finding:** `docs/07-Operations.md` § Core Features "Report Queue" lists a "post has edit history to prioritize" filter. The shipped viewer omits it: the composable filter set is `status` / `target_type` / `reason_category` / `trigger` / `from`–`to`, none of which expresses "the reported post has been edited." Implementing it needs an `EXISTS` join against `post_edits` (keyed on the report's `target_id` when `target_type = 'post'`), out of scope for the read-only v1. The viewer already tolerates the param: `GET /admin/reports?has_edit_history=true` is ignored (200, no filtering) — verified by spec scenario "The edit-history prioritization filter is absent."

**Specs at fault:** none — the spec's deferral requirement names this filter explicitly as a follow-up.
**Code at fault:** none.
**Docs at fault:** none — `docs/07-Operations.md` now marks the edit-history filter as Still DESIGN.

**Impact (if shipped):** lets a moderator prioritize reports on edited posts (a common evasion pattern — post clean, edit to violating). Low-complexity once v1 exists: one more composable `EXISTS (SELECT 1 FROM post_edits …)` predicate + a checkbox in the filter form.

**Action items:**
- [ ] Add the `has_edit_history` filter to the report-queue query (`EXISTS` over `post_edits` for `target_type='post'`) + a checkbox in the filter form, as a small follow-up change (or fold into `admin-report-queue-resolution-actions`).
- [ ] Delete this entry once shipped.

**Cap note + sweep (2026-06-06).** A busy reconciliation week — **five** changes touched this file (merge-reconciled across branches): `mobile-home-tab-host` (#153, **net 0** — deleted `mobile-home-tab-host` + `mobile-timeline-empty-global-cta`, added `mobile-following-timeline-screen` + `mobile-home-tab-host-per-tab-backstacks`); `mobile-env-launcher-icons` (#155, **+1** `mobile-env-launcher-icons-ios-dev-icon`); `admin-report-queue-viewer` (#154, **+2** spec-mandated `admin-report-queue-resolution-actions` + `admin-report-queue-has-edit-history-filter`); `admin-rejected-identifiers-viewer` (#156, **+1** `admin-rejected-identifiers-clear-action`); and `mobile-ios-build-config-matrix` (#158, **−1** — see below). The #156 archive ran a targeted sweep (the 8 likeliest-resolved entries — `mobile-auth-signin-logout-wire-up`, `mobile-auth-signin-android-instrumented-encryption-test`, `mobile-ios-ci-link-task`, `ci-paths-filter-switch-to-dorny`, `mobile-negative-requirement-ci-grep`, `mobile-post-creation-ios-flow-tests`, `mobile-auth-signin-credential-manager-legacy-fallback`, `firebase-admin-server-template-evaluate-bypass-removal` — each re-verified against current code / CI / specs): **0 rot, all still-valid**, consistent with the 2026-06-04 full sweep. **`mobile-ios-build-config-matrix` (#158)** then shipped BOTH halves of `mobile-env-launcher-icons-ios-dev-icon` — the dedicated iOS `Prod Debug` / `Prod Release` build configs (`.nearyou.app` + cobalt `AppIcon`), the removed `Debug`/`Release` APPICON hardcodes + per-config Pods wiring, AND a real `AppIcon-Dev` (forest green `#15803D`) dev icon via the env × build-type matrix — so that entry is **deleted** (−1). Net: 33 (origin/main after #156) − 1 = **32 open, 2 over the 30-entry soft cap**. Every remaining entry is spec-obliged or a clean deferred-by-design Non-Goal (zero half-implemented code). A full `/triage-follow-ups` sweep is **OVERDUE**; standing drawdown levers: the 2026-06-04 test-coverage bundle, merging `ci/mobile-android-emulator-encryption-test` (`c21c630`) → resolves `mobile-auth-signin-android-instrumented-encryption-test`, and the GitHub-Issues migration (solo-operator).

---

## mobile-analytics-consent-settings-toggle

**Discovered during:** `mobile-analytics-consent-screen` proposal (Non-Goals) + design OQ2.
**Status:** open

**Finding:** The consent screen writes `users.analytics_consent` at onboarding (signup path) via `PATCH /api/v1/user/consent`, but there is no Settings path to RE-EDIT consent post-onboarding. `docs/03-UX-Design.md` § Analytics & Tracking Consent Screen specifies "Settings page allows the user to change the toggle (applies going forward)". The re-edit path needs a `GET /api/v1/user/consent` (design OQ2: `200 {analytics, crash, ads_personalization}`, same authz as the PATCH) + a Settings screen that does not exist yet.

**Specs at fault:** none — deliberate `mobile-analytics-consent` Non-Goal (no Settings screen exists).
**Code at fault:** none — the onboarding-write path is the intended MVP shape.
**Docs at fault:** none.

**Impact (if shipped):** none today. Without it, a user who wants to change consent after onboarding has no in-app path until a Settings screen ships.

**Action items:**
- [ ] When a mobile Settings screen lands, add `GET /api/v1/user/consent` (per design OQ2) + the Settings consent-toggle UI reusing the existing `PATCH /api/v1/user/consent` + `ConsentApiClient`/`ConsentRepository`.
- [ ] Delete this entry once the Settings consent toggle ships.

---

## mobile-notifications-deep-link-targets

**Discovered during:** `mobile-bottom-nav-sections-and-notifications` design D5 — tapping a notification row marks it read but does NOT navigate to the target post/reply/profile (the destination screens don't exist yet).
**Status:** open

**Finding:** [`NotificationsScreen`](mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/notifications/NotificationsScreen.kt) wires a row tap to `NotificationsViewModel.markRead` only — no navigation to the notification's `target_type`/`target_id` destination (a deliberate negative guard, asserted by [`NotificationsDeepLinkAbsenceScanTest`](mobile/app/src/androidUnitTest/kotlin/id/nearyou/app/screens/notifications/NotificationsDeepLinkAbsenceScanTest.kt)). The targets are blocked on BOTH (a) the in-flight `mobile-post-detail` screen (#159, proposal-only at this change's impl time) AND (b) a backend `GET /api/v1/posts/{id}` by-id endpoint (which #159's proposal explicitly assigns to "the future notifications change") — neither exists. The list DTO carries `target_type` + `target_id` per the shipped wire, so the routing key is available once the destinations land.

**Specs at fault:** None — `openspec/changes/mobile-bottom-nav-sections-and-notifications/specs/mobile-notifications-list/spec.md` § "Tapping a row marks it read; deep-link navigation is deferred" defers this with a negative guard (the MODIFY anchor).
**Code at fault:** None — the mark-read tap is correct; deep-link is additive once the destinations exist.
**Docs at fault:** None.

**Impact (if shipped):** A notification tells the user *that* something happened but can't take them *to* it — they must find the post/profile manually. Acceptable for v1; deep-link is the natural next step.

**Ambiguity to resolve first:** The backend `GET /api/v1/posts/{id}` by-id endpoint must exist first (it does not — see #159). Sequencing: backend by-id endpoint → `mobile-post-detail` screen → this deep-link wiring.

**Action items:**
- [ ] Once `mobile-post-detail` (#159) ships AND a backend `GET /api/v1/posts/{id}` by-id endpoint exists, MODIFY the `mobile-notifications-list` § "Tapping a row marks it read; deep-link navigation is deferred" requirement to wire row tap → root-stack `PostDetailRoute` (and reply/profile routes as they land), keyed on `target_type`/`target_id`; keep the mark-read on tap.
- [ ] Delete this entry once deep-link tap-through ships.

---

## mobile-notifications-actor-username-enrichment

**Discovered during:** `mobile-bottom-nav-sections-and-notifications` design D4 — the shipped list endpoint returns only `actor_user_id` (a UUID) and NO actor username, so rows render generic-actor copy ("Seseorang …").
**Status:** open

**Finding:** [`NotificationRoutes.kt`](backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt) `NotificationDto` exposes `actor_user_id` (a UUID) but no username. Per design D4 the mobile rows render type-keyed copy with a GENERIC actor and NEVER the UUID ([`NotificationsScreen.notificationCopy`](mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/notifications/NotificationsScreen.kt) + the no-UUID-in-tree guard in `NotificationsScreenTest`). `chat_message` likewise drops `docs/03-UX-Design.md`'s "Pesan baru dari {username}" to a bare "Pesan baru". Rendering the real username needs a backend list-endpoint join over `visible_users` (the same `ActorUsernameLookup` the `fcm-push-dispatch` path already uses for the masked "Seseorang …" FCM fallback).

**Specs at fault:** `openspec/specs/in-app-notifications/spec.md` (the list response would gain an `actor_username` field).
**Code at fault:** None — generic copy is the correct, PII-safe v1; enrichment is a backend additive change.
**Docs at fault:** `docs/03-UX-Design.md` § In-App Notification List (its "{username}" copy is aspirational until enrichment).

**Impact (if shipped):** Rows read impersonally ("Seseorang menyukai postingan kamu") rather than "{username} menyukai…". Acceptable v1 (matches the FCM masking fallback); enrichment is a UX warmth improvement.

**Action items:**
- [ ] File a backend change adding an `actor_username` (via a block/shadow-ban-safe `visible_users` join, mirroring `ActorUsernameLookup`) to the `GET /api/v1/notifications` list response; then MODIFY the `mobile-notifications-list` copy to "{username} …" (incl. `chat_message` → "Pesan baru dari {username}"), keeping the generic fallback for null usernames.
- [ ] Delete this entry once actor-username enrichment ships end-to-end.

---

## in-app-notifications-spec-wire-reconciliation

**Discovered during:** `mobile-bottom-nav-sections-and-notifications` design D2 — the mobile DTOs were generated from the SHIPPED `NotificationRoutes.kt` wire, which DIVERGES from the stale `in-app-notifications` spec prose (bucket b: reconcile the spec to the shipped code).
**Status:** open

**Finding:** The `in-app-notifications` spec prose describes a wire the shipped [`NotificationRoutes.kt`](backend/ktor/src/main/kotlin/id/nearyou/app/notifications/NotificationRoutes.kt) does NOT emit (design D2 table): list filter `unread=true` (spec: `unread_only`); `next_cursor` opaque base64url (spec: ISO8601 timestamp); unread-count `{ count }` (spec: `{ unread_count }`); read-all `{ marked_read }` (spec: `{ marked }`); mark-read `204` (spec: `200 no body`); not-found `404 { code: "not_found" }` (spec: `notification_not_found`); malformed cursor `400 { code: "invalid_cursor" }` (spec: `invalid_request`); `limit` clamped `[1,50]` silently (spec: `400`). Mobile pinned the shipped shapes + a negative-regression guard ([`NotificationsApiClientTest`](mobile/app/src/commonTest/kotlin/id/nearyou/app/notifications/NotificationsApiClientTest.kt)); the SPEC itself is unreconciled. Exact precedent: PR #128/#132 reconciled the same trap for the timeline DTOs (camelCase wire vs snake spec).

**Specs at fault:** `openspec/specs/in-app-notifications/spec.md` (the eight divergences above).
**Code at fault:** None — `NotificationRoutes.kt` is the source of truth; the spec prose is stale.
**Docs at fault:** None.

**Impact (if shipped):** A future consumer regenerating DTOs from the spec (not the code) would mis-parse the wire (the exact trap mobile dodged). Low for mobile (pinned + guarded); medium for any new client.

**Action items:**
- [ ] Regular docs PR (NOT an OpenSpec product change): reconcile `openspec/specs/in-app-notifications/spec.md` to the shipped `NotificationRoutes.kt` (`count` / `marked_read` / `unread` / `204` / `not_found` / opaque-cursor / `limit` clamp), mirroring the PR #132 timeline-wire reconciliation.
- [ ] Delete this entry once the spec matches the shipped wire.

---

## mobile-profile-section-screen

**Discovered during:** `mobile-bottom-nav-sections-and-notifications` — the Profil bottom-nav section ships as a deferred placeholder ("Profil segera hadir."), not the real profile/settings surface.
**Status:** open

**Finding:** [`ProfilePlaceholderScreen`](mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/profile/ProfilePlaceholderScreen.kt) renders `profile_placeholder` and issues no fetch (mirroring `FollowingPlaceholderScreen`); it's wired as the Profil section body in [`AppShellScreen`](mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellScreen.kt). The real profile/settings surface (own profile, edit, sign-out, privacy-flag toggles, Premium) is a separate change. The `mobile-home-tab-host` § "The Profil section renders a deferred placeholder" requirement is the MODIFY anchor.

**Specs at fault:** None — `mobile-home-tab-host` § "The Profil section renders a deferred placeholder" defers this deliberately (the MODIFY anchor).
**Code at fault:** None — the placeholder is a real, documented "coming soon" state.
**Docs at fault:** None.

**Impact (if shipped):** The Profil section is a placeholder-only dead-end. Acceptable for the shell scaffold (it reserves the section slot); the real surface is a meaningful standalone change (sign-out alone is a launch prerequisite).

**Action items:**
- [ ] File OpenSpec change `mobile-profile-section-screen` MODIFYing the `mobile-home-tab-host` Profil-placeholder requirement to introduce the live profile/settings surface (own profile view, sign-out, privacy-flag toggles, Premium entry).
- [ ] Delete this entry once the real Profil surface ships.

---

## mobile-notifications-live-unread-badge

**Discovered during:** `mobile-bottom-nav-sections-and-notifications` design D6 — the Notifikasi unread badge is a ONE-SHOT fetch (on shell composition + on leaving the section), with no live/push-driven updates.
**Status:** open

**Finding:** [`AppShellScreen`](mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/shell/AppShellScreen.kt) fetches the unread count via `NotificationsFlow.unreadCount()` in a one-shot `LaunchedEffect(Unit)` on composition + a `DisposableEffect` onDispose when leaving the Notifikasi section; the badge shows when `count > 0`. NO polling timer / FCM-driven invalidation / live subscription is wired (asserted one-shot by the `mobile-home-tab-host` § "Badge is one-shot" scenario). So the badge can lag reality: a notification arriving while the user sits on the Home section won't bump the badge until the next composition / section-leave trigger.

**Specs at fault:** None — `mobile-home-tab-host` § "The Notifikasi section hosts the notifications surface with an unread badge" (+ its "Badge is one-shot (no live updates wired)" scenario) defer live updates deliberately.
**Code at fault:** None — the one-shot fetch is the correct scaffold scope.
**Docs at fault:** None.

**Impact (if shipped):** The badge count is bounded-stale (refreshed on composition + section-leave). Acceptable v1; a real engagement loop wants live updates.

**Ambiguity to resolve first:** Live strategy — FCM-data-message-driven count invalidation (preferred; the push path already exists) vs a bounded foreground poll (simpler, costlier). Decide during the change.

**Action items:**
- [ ] File a change wiring live unread-badge updates beyond the one-shot fetch (FCM-data-message invalidation of the count, or a bounded foreground poll), MODIFYing the `mobile-home-tab-host` badge requirement's "one-shot" scenario.
- [ ] Delete this entry once live badge updates ship.

---

## mobile-analytics-consent-persist-hardening

**Discovered during:** `mobile-analytics-consent-screen` design D4 (deferred).
**Status:** open

**Finding:** On a `PATCH /api/v1/user/consent` failure, `ConsentScreen` offers a retry + a non-trapping "Lewati untuk sekarang" skip that proceeds to Home keeping the server's prior (default) value — it does NOT background-retry/queue the failed write. Safe TODAY because no tracking SDK reads `analytics_consent` yet. Once the Amplitude/Sentry/AdMob suppress-wrappers land, a failed-then-skipped submit could leave a wrapper tracking against the user's intended (but unpersisted) choice. The `mobile-analytics-consent` spec captures this as an explicit "Reliable consent persistence is deferred" requirement + negative-guard scenario (so this entry has a requirement to MODIFY).

**Specs at fault:** none — `mobile-analytics-consent` § "Reliable consent persistence is deferred" is the MODIFY target.
**Code at fault:** `mobile/app/src/commonMain/kotlin/id/nearyou/app/consent/ConsentFlow.kt` (`ConsentRepository` — no retry/queue beyond the in-screen retry).
**Docs at fault:** none.

**Impact (if shipped):** none until the consent-aware SDK suppress-wrappers land; then a failed persist could mismatch tracking against the user's choice (UU-PDP-relevant).

**Action items:**
- [ ] When the consent-aware suppress-wrappers (`:infra:amplitude` / `:infra:sentry` / AdMob UMP) land, add reliable persistence (retry/queue, or block-on-success) so a failed consent PATCH cannot leave a tracking SDK mismatched — MODIFY the `mobile-analytics-consent` "Reliable consent persistence is deferred" requirement.
- [ ] Delete this entry once reliable persist ships.

---

## mobile-analytics-consent-rootrouter-regate

**Discovered during:** `mobile-analytics-consent-screen` design D4 (deferred).
**Status:** open

**Finding:** `ConsentScreen` lives only in the signup→Home transition (`AppEntryProvider` `entry<AgeGateRoute> onSignedUp = replaceAll(ConsentRoute)`). A user who force-quits at the consent screen holds a valid token, so the next launch routes straight to `HomeRoute` (consent bypassed). This change deliberately does NOT add a `consent_completed_at` flag + a `RootRouterScreen` consent re-gate, because the V2 defaults are privacy-safe (analytics=false/ads=false; crash=true is the documented opt-out-able default) and no SDK reads consent yet → the bypass is benign for MVP. The `mobile-analytics-consent` spec captures this as an explicit "RootRouter does not re-gate … (deferred)" requirement + negative-guard scenario.

**Specs at fault:** none — `mobile-analytics-consent` § "RootRouter does not re-gate returning token-bearing users on consent completion (deferred)" is the MODIFY target.
**Code at fault:** none — the no-re-gate is the intended MVP shape (safe defaults make it benign).
**Docs at fault:** none.

**Impact (if shipped):** none today. Once the suppress-wrappers land, a bypassing user sits at the V2 default rather than an explicit choice — acceptable (privacy-safe) but worth hardening alongside persist-hardening.

**Action items:**
- [ ] When the suppress-wrappers land (sequence AFTER this re-gate), add a `consent_completed_at` (or equivalent) flag + a `RootRouterScreen` check that interposes `ConsentRoute` for a token-bearing user who never completed consent — MODIFY the `mobile-analytics-consent` "RootRouter does not re-gate …" requirement.
- [ ] Delete this entry once the re-gate ships.

---

**Cap note (2026-06-07, `mobile-analytics-consent-screen` merge-reconcile):** appended this change's 3 spec-required deferrals (`-settings-toggle`, `-persist-hardening`, `-rootrouter-regate`) on top of origin/main's reconciled 32 → **35 open, 5 over the 30-entry cap**. All spec-obliged — each is referenced by name in a `mobile-analytics-consent` negative-guard scenario or Non-Goal; zero rot, zero half-implemented code. The OVERDUE `/triage-follow-ups` sweep is the drawdown lever.

---

## mobile-post-detail-block-report-kebab

**Discovered during:** `mobile-post-detail-screen` proposal (deferral; design Non-Goal + spec § "Block and report kebab actions are deferred").
**Status:** open

**Finding:** `PostDetailScreen` + its reply cards ship with NO block/report affordance (no post/reply kebab menu, no "Blokir"/"Laporkan" control, no block/report API call — verified by the spec's negative-guard scenario). The block + report UI ([`docs/02-Product.md:233`](docs/02-Product.md) / `:254`) is a separate feature: the shipped `user_blocks` + `reports` backends, a confirmation modal, and a reason picker. It is captured as a positive spec requirement on `mobile-post-detail` so this follow-up has a requirement to MODIFY.

**Specs at fault:** none — `openspec/specs/mobile-post-detail/spec.md` § "Block and report kebab actions are deferred" requires this be a separate change.
**Code at fault:** none — the deferral is clean (no half-implemented kebab).
**Docs at fault:** none.

**Impact (if shipped):** no in-app block/report from the post-detail surface until this lands — a moderation gap on the very surface a user reads a problematic post. Acceptable for the v1 scaffold (both backends exist; only the mobile UI is missing).

**Action items:**
- [ ] File OpenSpec change `mobile-post-detail-block-report-kebab` adding a post + reply kebab ("Blokir" / "Laporkan"), a confirmation modal + reason picker, wired to the shipped `user_blocks` + `reports` backends; MODIFIES `mobile-post-detail` § "Block and report kebab actions are deferred".
- [ ] Delete this entry once the block/report UI ships.

---

## mobile-post-detail-inline-card-actions

**Discovered during:** `mobile-post-detail-screen` proposal (deferral; design D8 + spec § "Inline-card like and reply shortcuts are deferred").
**Status:** open

**Finding:** v1 routes ALL like/reply interaction through `PostDetailScreen`. The Nearby + Global feed cards gain ONLY the `onOpenPost` open-detail tap (per the `mobile-nearby-timeline` / `mobile-global-timeline` deltas) — NO inline like button, NO inline reply field (verified by the cards' "open-detail tap only" scenario). Inline-card shortcuts are deferred so v1 ships one cohesive engagement surface.

**Specs at fault:** none — `openspec/specs/mobile-post-detail/spec.md` § "Inline-card like and reply shortcuts are deferred".
**Code at fault:** none — the cards expose only the hoisted `onOpenPost` tap.
**Docs at fault:** none.

**Impact (if shipped):** a user must open the detail to like/reply (one extra tap vs an inline shortcut). Acceptable for v1.

**Action items:**
- [ ] File OpenSpec change `mobile-post-detail-inline-card-actions` adding inline like + quick-reply affordances on the feed cards (reusing the `PostDetailFlow` seam); MODIFIES the `mobile-nearby-timeline` / `mobile-global-timeline` card deltas.
- [ ] Delete this entry once inline-card actions ship.

---

## backend-single-post-get-endpoint

**Discovered during:** `mobile-post-detail-screen` proposal (deferral; design D2 + spec § "By-id post fetch and replies infinite-scroll are deferred"). Owned by the future notifications deep-link change.
**Status:** open

**Finding:** No `GET /api/v1/posts/{id}` single-post endpoint exists (only `POST /api/v1/posts` + the post-scoped like/reply sub-resources). `PostDetailScreen` renders the post header SOLELY from the `PostDetailRoute` nav-arg payload captured at card-tap (design D2; verified by the "no single-post GET" scenario — the only captured post-scoped requests target `/like`, `/likes/count`, `/replies`). A by-id fetch is only truly needed for **notification deep-linking** — opening a post/reply from a notification, where there is no feed card to source the header — which the in-app notifications-list change (`mobile-bottom-nav-sections-and-notifications`, this PR) defers via its `mobile-notifications-deep-link-targets` entry.

**Specs at fault:** none — `openspec/specs/mobile-post-detail/spec.md` § "By-id post fetch and replies infinite-scroll are deferred".
**Code at fault:** none — the no-by-id-GET is the intended v1 shape.
**Docs at fault:** none.

**Impact (if shipped):** notification deep-links into post detail cannot render the header (no card to source it) until this lands. None today (the notifications list ships with deep-link tap-through deferred — `mobile-notifications-deep-link-targets`).

**Action items:**
- [ ] File OpenSpec change `backend-single-post-get-endpoint` adding `GET /api/v1/posts/{id}` (shadow-ban/block-aware via `visible_posts`, `display_location` fuzzing, NO author PII) so the notifications deep-link change can render a post header without a feed card; likely bundled with / preceding `mobile-notifications-deep-link-targets`.
- [ ] Delete this entry once the by-id endpoint ships.

**Cap note (2026-06-08, `mobile-bottom-nav-sections-and-notifications` × `mobile-post-detail-screen` (#159) merge-reconcile):** #159 squash-merged to main first (the design-D9 / task-14.5 ordering), so this change is the second-to-merge and absorbed #159's `onOpenPost`→`PostDetailRoute` wiring through the new `AppShellScreen`. FOLLOW_UPS reconciled: origin/main's consent-reconciled **35** + this change's 5 notifications deferrals + #159's 3 post-detail deferrals (`mobile-post-detail-block-report-kebab`, `mobile-post-detail-inline-card-actions`, `backend-single-post-get-endpoint`) → **43 open, 13 over the 30-entry hard cap** (supersedes the pre-merge per-branch "40"/"38" draft notes). All spec-obliged, zero rot. A `/triage-follow-ups` sweep is spawned as a background task; the in-flight PR [#164](https://github.com/aditrioka/nearyou-id/pull/164) (a separate sweep, 32 → 24 via 1 obsolescence-delete + 7 migrations to [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md), based on the pre-#157 tree) is the primary drawdown lever once it merges and the feature branches rebase onto it.

**Sweep note (2026-06-08, dedicated `/triage-follow-ups` session — PR [#166](https://github.com/aditrioka/nearyou-id/pull/166); supersedes the `#164`-drawdown plan in the note above).** By the time this sweep ran, both #162 (this file's +5) and #160 (which self-deleted `admin-report-queue-resolution-actions`, −1) had merged, so `main` settled at **42 open** (not the 43 the note above projected). The sweep drew that to **35** by migrating 7 verified-valid deferrals to [`docs/08-Roadmap-Risk.md`](docs/08-Roadmap-Risk.md) (Pre-Launch items 8/9 + four Phase 3 Post-scaffold refinements). **PR #164 was closed as superseded** — its pre-#157 base had diverged 3 merges and it carried a now-rejected delete of `mobile-negative-requirement-ci-grep` (rescoped here to its still-unenforced hardcoded-UI-strings axis instead). **0 rot** across all 42 (zero silently-resolved, zero superseded; parallel code-reading agents with file:line evidence); residual 35 is verified-valid spec-obliged deferred work — the GitHub-Issues migration (solo-operator) remains the structural lever for sustainably <30. Per-entry deletion/migration evidence: this sweep's PR.
