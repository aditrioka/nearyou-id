# content-moderation-keyword-lists — Design

## Context

The text moderation pipeline has been canonical since Pre-Phase 1 but unbuilt: scaffolding (Remote Config keys, Secret Manager slot, reserved `moderation_queue.trigger` enum value) is in place, the [`docs/05-Implementation.md:1253`](../../../docs/05-Implementation.md) "Content Moderation Keyword Lists" stub is `Status: DESIGN`, and the multi-layer canonical at [`docs/06-Security-Privacy.md:151`](../../../docs/06-Security-Privacy.md) describes 3 tiers (manual blocklist, UU ITE wordlist, Perspective API) with this change covering tiers 1 and 2.

Constraints inherited from the project posture:
- **`secretKey(env, name)` helper for Secret Manager** ([`openspec/project.md`](../../project.md) § Critical invariants). Direct `client.accessSecretVersion("foo")` reads are a Detekt violation.
- **Redis hash-tag format `{scope:<value>}`** for cluster-safe multi-key ops. Cache key for moderation lists must follow this shape.
- **No vendor SDK import outside `:infra:*`** — limits where the Firebase Remote Config edge can land.
- **Content length guards** are already enforced upstream of any moderator call (post 280, reply 280, chat 2000); the moderator runs on already-length-validated content.
- **`:infra:remote-config` is `DESIGN`** in [`openspec/project.md`](../../project.md) § Module Structure. Until that scaffolds, vendor-touching Remote Config code lives wherever the proposal places it (constraint resolved in D3).
- **Redis client is `lettuce-core`** ([`docs/09-Versions.md`](../../../docs/09-Versions.md)) via `:infra:redis`. Reuse the existing `RedisRateLimiter`-style pattern; do not introduce a new Redis client.

Stakeholders:
- Product safety: every text-write path needs a moderation gate before launch (Pre-Launch security review §5).
- Operations: keyword lists must be tunable without a backend redeploy (Remote Config primary).
- Reliability: a Remote Config outage MUST NOT block content publishing — fail-soft to repo file is mandatory.
- Privacy: no user content lands in span attributes / logs / Sentry breadcrumbs (per `observability-otel-foundation` forbidden-attributes contract).

## Goals / Non-Goals

**Goals**:
- A single canonical `TextModerator.moderate(content): Verdict` entrypoint that every text-write path consumes.
- Deterministic 4-tier fallback ladder with explicit observability at each step.
- Pure-Kotlin matcher engine (testable in isolation, reusable by future writers).
- Two distinct severity outcomes (Reject vs Flag) cleanly mapped to HTTP status + DB side-effects.
- Tunable threshold and lists at runtime (Remote Config).
- Sentry surface for every fallback-step degradation.

**Non-Goals**:
- Perspective API integration (Phase 2 §16, separate change). This change provides the boundary that Perspective API will consume, but doesn't implement the async layer.
- Premium username customization moderation (separate change). The matcher is reusable; the username-specific writer (`trigger = 'username_flagged'`) lives in that change.
- Admin Panel keyword-list editor (Phase 3.5 admin work).
- Quarterly UU ITE legal-advisor review cadence (operations runbook in [`docs/07-Operations.md`](../../../docs/07-Operations.md), not a spec).
- Custom Indonesian dictionary / Hive Moderation paid (Month 6+ scope per [`docs/06-Security-Privacy.md:167`](../../../docs/06-Security-Privacy.md)).
- Rebuilding the Aho-Corasick automaton on every Remote Config push (deferred follow-up — see D4).
- Per-user / per-locale list selection (single global list per tier).

## Decisions

### D1 — Profanity blocklist hit semantics: **REJECT (4xx), not auto-hide + queue**

A single profanity-blocklist match SHALL produce HTTP 400 with `code = "content_moderated_profanity"` + a Bahasa Indonesia user-facing message. No `posts` row is inserted, no `moderation_queue` row is written.

**Why REJECT over auto-hide + queue**:
- Layer 1 is "Manual keyword blocklist: profanity, slurs, scam patterns" ([`docs/06-Security-Privacy.md:155`](../../../docs/06-Security-Privacy.md)) — pre-curated, low-ambiguity terms by definition. Auto-hide-then-queue is the right pattern for ambiguous content (which is exactly what Layer 2 + Layer 3 cover); pre-curated terms warrant a sync UX.
- Cleaner UX: user gets immediate "Konten ini mengandung kata yang tidak diperbolehkan." feedback. They can edit in place. Ghost-publishing then auto-hiding creates confusion ("did my post go through? where is it?") + an admin-review backlog of obviously-bad content.
- Reduces moderation queue load: pre-curated profanity hits would dominate queue volume if auto-hidden, drowning the legitimate ambiguous-content review path.
- **Asymmetry with Layer 2 is documented behavior**: [`docs/06-Security-Privacy.md:158`](../../../docs/06-Security-Privacy.md) explicitly carves out UU ITE as "soft flag to the moderation queue (not auto-hide)" — the implication is that Layer 1's behavior is *not* soft flag. REJECT is the natural reading.

**Canonical-doc reconciliation**: [`docs/06-Security-Privacy.md:180`](../../../docs/06-Security-Privacy.md) (the "If flagged" line inside the § Endpoint Flow code block at lines 173–181) says "If flagged: set is_auto_hidden = TRUE + insert moderation_queue row" without distinguishing layers. Reading the FULL code block carefully (lines 174–180):

```
POST /api/v1/post
→ length validation (280 chars max)
→ keyword blocklist (sync)
→ UU ITE category check (sync)
→ Perspective API (async, 500ms timeout, fail-open)
→ Insert post
→ If flagged: set is_auto_hidden = TRUE + insert moderation_queue row
```

The "If flagged" line sits AFTER `Insert post`. Combined with [`docs/06-Security-Privacy.md:158`](../../../docs/06-Security-Privacy.md) carve-out for UU ITE as soft-flag-not-auto-hide, the natural reading is: "If flagged" describes the **post-INSERT auto-hide path** that the async Perspective API (Layer 3) populates retroactively. Layer 2 (UU ITE sync) is the soft-flag pre-INSERT path. Layer 1 (profanity sync) is the REJECT pre-INSERT path that never reaches INSERT. **Action: amend `docs/06-Security-Privacy.md:180` to clarify "If flagged" describes the Layer 3 (Perspective) post-INSERT auto-hide; Layer 1 is sync REJECT pre-INSERT; Layer 2 is sync INSERT + queue + no auto-hide.** Logged as a `FOLLOW_UPS.md` entry — the amendment lands separately so canonical docs match the spec at archive time.

**Alternative considered (Option A)**: auto-hide + queue with a new trigger enum value `profanity_keyword_match`. Rejected:
- Couples this change to a `moderation-queue` schema MODIFY (Flyway migration adding the enum).
- Drowns admin queue in obvious-profanity rows.
- Worse UX (no immediate feedback to author).

**Alternative considered (Option B)**: REJECT but ALSO write a `moderation_queue` row for audit/pattern-detection. Rejected:
- Queue is for human review; rejected content has nothing to review (already rejected).
- Pattern detection (repeat offenders) is the `anomaly-detection-metrics` change scope, not this one. The audit signal can go to Sentry instead (`event = "content_moderation_rejected"`) without touching the queue.

### D2 — `moderation_queue.trigger` enum: **no change**

D1 resolves to REJECT for Layer 1 → no Layer 1 queue writer → no new enum value needed.

The existing `uu_ite_keyword_match` value covers Layer 2 (the only new writer this change introduces). The `moderation-queue` spec is **NOT** modified by this change.

If a future change ever needs to enqueue profanity hits (e.g., Premium username customization where REJECT isn't the right UX), it can either: (a) reuse `uu_ite_keyword_match` (overloaded but no schema change) — *not recommended*, or (b) add `profanity_keyword_match` as that change's scope.

### D3 — Module placement: **`KeywordMatcher` in `:core:domain`; loader + Firebase Remote Config edge in a NEW `:infra:remote-config` module**

**`KeywordMatcher`** (pure-Kotlin Aho-Corasick implementation) lives in `:core:domain`. Pure data transformation, no vendor deps, testable in isolation, reusable by future writers (Perspective API change, Premium username customization). No new `:core` module needed — the matcher is one ~150 LOC class and `:core:domain` already houses similar pure-Kotlin primitives (`RateLimiter` interface, `ContentLengthGuard`).

**`RemoteConfigClient`** (Firebase Admin SDK consumer) lives in a NEW `:infra:remote-config` module scaffolded as part of this change. The architectural rule "no vendor SDK import outside `:infra:*`" ([`openspec/project.md`](../../project.md) § Critical invariants) forbids `import com.google.firebase.*` in `:backend:ktor` source files; the Firebase Admin SDK Remote Config surface MUST be encapsulated in an `:infra:*` module.

The new module:
- **Resolves the "DECISION NEEDED" status** that [`openspec/project.md`](../../project.md) § Module Structure currently flags for `:infra:remote-config` ("DB-backed feature flags already operational; a separate Remote Config module may be redundant"). The DB-backed `premium_*_cap_override` flags are per-user override columns, not platform-wide config; Firebase Remote Config IS the right tool for platform-wide tunable config (wordlists, kill switches like `image_upload_enabled` / `attestation_mode` / `perspective_api_enabled`). This change is the first concrete platform-wide-config consumer — the trigger to scaffold is met.
- **Initializes its OWN named FirebaseApp** (e.g., `"nearyou-rc"`) reading the service-account JSON from `secretKey(env, "firebase-admin-sa")` — the same secret slot already consumed by `:infra:fcm`'s `FirebaseAdminInit` ([`infra/fcm/.../FirebaseAdminInit.kt`](../../../infra/fcm/src/main/kotlin/id/nearyou/app/infra/fcm/FirebaseAdminInit.kt) reads from `firebase-admin-sa` per [`backend/ktor/.../Application.kt:386`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/Application.kt)). Two named FirebaseApps share the secret slot but isolate their lifecycles — keeps `:infra:remote-config` self-contained without a project-dependency edge to `:infra:fcm`.
- **Exposes `RemoteConfigClient` interface in `:core:domain` or a new `:core:remote-config`** — TBD at implementation time, lean toward leaving the interface in `:infra:remote-config` since there's no `:core` consumer that needs to mock it. (The mock pattern uses Koin test profiles, not interface extraction.)
- **`:backend:ktor` declares `implementation(project(":infra:remote-config"))`** in its `build.gradle.kts` and imports ONLY the public `RemoteConfigClient` symbol; no `import com.google.firebase.*` appears in `:backend:ktor` source files.

The Firebase Admin SDK BOM is already pinned at `9.5.0` in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) (per [`docs/09-Versions.md`](../../../docs/09-Versions.md) — the entry was added by `fcm-push-dispatch`). The new module reuses the existing pin; no new third-party version needed.

**Cost of scaffolding `:infra:remote-config`** (per `tasks.md` Phase 3):
- 1 new directory `infra/remote-config/`
- 1 new `build.gradle.kts` (~10 lines, mirror `:infra:fcm`'s)
- 1 new entry in `settings.gradle.kts`
- 1 new line in `dev/module-descriptions.txt` + run `dev/scripts/sync-readme.sh --write` (auto-syncs the README module list per [`CLAUDE.md`](../../../CLAUDE.md) maintenance trigger)
- 1 new `RemoteConfigClient.kt` + `FirebaseAdminInitForRemoteConfig.kt` (~150 LOC total)
- 1 new Koin module wiring

This is bounded scope. The module-trigger from [`openspec/project.md`](../../project.md) § Module Structure is met; deferring to a follow-up would push 150 LOC of Firebase code into `:backend:ktor` (architectural violation) just to keep the change diff smaller, which is the wrong trade-off.

**Future extraction follow-up**: a `:infra:firebase-app` module that owns the FirebaseApp init + is depended on by both `:infra:fcm` and `:infra:remote-config` would be the cleanest factoring. Out of scope here — `:infra:fcm` is shipped; refactoring its Firebase init is a separate change. Logged as a `FOLLOW_UPS.md` entry.

**Alternative considered**: keep `RemoteConfigClient` in `:backend:ktor` with a hand-rolled OAuth2 token-mint + raw HTTP REST call to Firebase Remote Config REST API. Rejected — the OAuth2 flow alone (sign service-account-JWT, exchange at Google's token endpoint, refresh on expiry) is ~80 LOC of crypto code that would need its own tests; the Firebase Admin SDK already implements all of it. Pulling the Admin SDK into `:backend:ktor` violates the "no vendor SDK outside `:infra:*`" rule. Scaffolding the module is cheaper than rolling our own auth.

**Alternative considered**: put `RemoteConfigClient` in `:infra:fcm`. Rejected — `:infra:fcm` is named for FCM dispatch; cramming Remote Config into it is semantically wrong + grows the module's surface non-orthogonally. Modules should reflect single responsibilities.

**`tasks.md` Phase 1 verification** (per `/next-change` skill external-data sanity-check guidance): confirm the staging Firebase project has the three Remote Config parameters (`moderation_profanity_list`, `moderation_uu_ite_list`, `moderation_match_threshold`) reserved per Pre-Phase 1 §36; confirm the staging GCP Secret Manager has `staging-content-moderation-fallback-list` slot reserved (or document the gap as operations work). Both are verified BEFORE any module scaffolding so wiring against missing remote state doesn't surface ambiguous failures.

### D4 — Cache strategy: **Redis 5-min TTL, no explicit invalidation**

The Redis cache layer uses a 5-minute fixed TTL keyed at `mod:list:{tier:profanity}` and `mod:list:{tier:uu_ite}` (hash-tag format per [`openspec/project.md`](../../project.md) § Critical invariants). Cache content is the parsed `List<String>` of keywords, serialized as JSON.

When Remote Config is updated by an operator, the change propagates within 5 minutes max (TTL elapse + next loader call refreshes from Remote Config). No push-based invalidation, no cache busting endpoint.

**Why TTL alone**:
- 5-min staleness is acceptable for content moderation (legal-advisor review is quarterly per [`docs/06-Security-Privacy.md:159`](../../../docs/06-Security-Privacy.md)). Operator edits in the Admin Panel are not time-critical.
- Push invalidation requires either Pub/Sub on Remote Config webhooks (Firebase doesn't emit them per the same constraint that makes the CSAM CF Tool webhook-less per [`docs/02-Product.md:373`](../../../docs/02-Product.md)) OR a polling endpoint (adds operational surface).
- Consistency with the existing Redis-backed feature flag pattern (`token_version` cache, 5-min TTL).

**Aho-Corasick rebuild cost**: building the automaton from a list of ~50 patterns is sub-millisecond. Per-call rebuild is acceptable; no need to cache the *built* automaton object across calls. (If profiling shows it matters at p99, cache the built automaton with the same 5-min TTL — but defer until measured.)

**Follow-up reserved**: if operators report a too-stale issue (Month 3+ data), introduce a `POST /internal/moderation-list-bust` endpoint (OIDC-authed) that deletes the cache keys. **Not in scope here.**

### D5 — UU ITE threshold: **honor `moderation_match_threshold` Remote Config value, not the literal "1 match" docs phrase**

[`docs/06-Security-Privacy.md:158`](../../../docs/06-Security-Privacy.md) says "Higher threshold: 1 match = soft flag to the moderation queue (not auto-hide)" — a literal reading is "any single match triggers a flag." But:
- Pre-Phase 1 §36 ([`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md)) defines `moderation_match_threshold` as a Remote Config parameter with default value 3, explicitly tied to "the Aho-Corasick matcher" (which is the matcher this change builds).
- The default-3 makes operational sense: a single SARA-adjacent term in a long post is high false-positive surface (e.g., "saya orang Jawa" — which mentions an `antargolongan` term but isn't incitement); 3 distinct hits is closer to genuine UU ITE-pattern signal.

The proposal honors the Remote Config parameter (3) and surfaces this as a canonical-doc reconciliation: amend [`docs/06-Security-Privacy.md:158`](../../../docs/06-Security-Privacy.md) to align "1 match" wording with the threshold-driven semantics. Logged as a `FOLLOW_UPS.md` entry; the amendment lands separately. **At runtime, the matcher reads the Remote Config value (default 3); operators can drop it to 1 if false-negative rate is unacceptable.**

### D6 — Fail-closed vs fail-open posture

**Loader posture**: the 4-tier ladder is fail-soft (each tier failure cascades to the next tier, with a Sentry WARN). If ALL FOUR tiers fail, the loader returns `emptyList()` and emits a Sentry ERROR `event = "moderation_list_unavailable" tier = "<list>"`. The downstream `TextModerator` interprets `emptyList()` as "no matches possible" — i.e., **fail-open** at the policy level: the post/reply/chat-message is allowed through (treated as `Verdict.Allow`).

**Why fail-open**:
- Availability over moderation strictness: a Remote Config outage + Secret Manager outage + missing repo file shouldn't block content publishing (matches the `perspective_api_enabled` 500ms-timeout fail-open posture for Layer 3 per [`docs/06-Security-Privacy.md:178`](../../../docs/06-Security-Privacy.md)).
- The repo file is checked into the binary (resource); Tier 3 cannot fail unless the JAR is corrupt.
- Tier 4 (Secret Manager) is an explicit safety net; it would fail only if both Remote Config + the repo resource are gone, which is an operational catastrophe that warrants a Sentry-paged response.

**Mitigation for fail-open exposure**: every tier-failure emits Sentry; a complete fallthrough emits ERROR with PagerDuty-eligible severity. In practice the repo file (Tier 3) is always present, so genuine fail-open paths are vanishing.

**Alternative considered**: fail-closed at the policy level (treat empty list as "everything matches everything → REJECT all"). Rejected — operationally catastrophic; one Remote Config blip blocks all writes platform-wide.

### D7 — Sentry surface: per-tier WARN, full-fallthrough ERROR

Each fallback step emits one Sentry breadcrumb-style event:

| Tier consulted | Tier outcome | Sentry severity | Event name | Tags |
|---|---|---|---|---|
| 1 (Redis cache) | hit | none (hot path) | — | — |
| 1 (Redis cache) | miss | none (cache miss is normal) | — | — |
| 2 (Remote Config) | fetched + cached | none (success) | — | — |
| 2 (Remote Config) | network error / parse error | WARN | `moderation_list_fallback` | `tier = "remote_config"`, `to = "repo_file"`, `list = "<profanity|uu_ite>"`, `reason = "<network|parse|empty>"` |
| 3 (repo file) | read OK | none | — | — |
| 3 (repo file) | resource missing | WARN | `moderation_list_fallback` | `tier = "repo_file"`, `to = "secret_manager"`, `list = "<...>"` |
| 4 (Secret Manager) | resolved | WARN | `moderation_list_fallback` | `tier = "secret_manager"`, `to = "(empty list)"` only if subsequently empty; otherwise no event |
| 4 (Secret Manager) | unresolved | ERROR | `moderation_list_unavailable` | `list = "<profanity|uu_ite>"`, `outcome = "fail_open"` |

Rate-limit Sentry events: at most 1 per loader call (not per cache miss). Use Sentry's built-in deduplication so a sustained Remote Config outage doesn't flood Sentry.

### D8 — Endpoint integration order

For each integration site (`POST /api/v1/posts`, `POST /api/v1/posts/{post_id}/replies`, `POST /api/v1/chat/{conversation_id}/messages`):

```
1. Authentication / authorization (existing)
2. Rate limit gate (existing where applicable: like-rate-limit, reply-rate-limit, chat-rate-limit)
3. Block check (existing where applicable: chat-conversations)
4. Content length guard (existing — content_length_guard middleware)
5. → TextModerator.moderate(content)  ← THIS CHANGE
   ├─ Verdict.Reject(profanity) → return 400 + code = "content_moderated_profanity" + i18n message
   ├─ Verdict.Flag(uuIteHit) → fall through to step 6, write moderation_queue row in same transaction as the INSERT
   └─ Verdict.Allow → fall through to step 6
6. INSERT into posts / post_replies / chat_messages (existing)
7. (chat path only) broadcast publish via SupabaseBroadcastChatClient (existing)
```

**Transaction shape for `Verdict.Flag`**: the `moderation_queue` row is written in the same SQL transaction as the content INSERT. UNIQUE constraint on `(target_type, target_id, trigger)` ([`openspec/specs/moderation-queue/spec.md:22`](../../specs/moderation-queue/spec.md)) handles idempotency: re-tries with the same `target_id` are no-ops. Use `ON CONFLICT DO NOTHING`.

**Why moderation gates AFTER rate limit + length guard**:
- Length guard is cheap, deterministic, and rejects before the moderator (which has Redis + Remote Config network surface) is even consulted.
- Rate limit rejects abusive volume before any moderation cost. Pattern-aligned with `like-rate-limit` ordering.
- Block check (chat path) rejects unauthorized recipients before content moderation runs.

### D9 — Bahasa Indonesia user-facing messages

Per [`openspec/project.md`](../../project.md) § Coding Conventions, mobile UI strings go through Moko Resources, but **API error messages are not mobile UI strings** — they're API contract data. Inline Bahasa Indonesia strings in `:backend:ktor` for API responses (mirrors existing pattern in `auth-signin` / age-gate rejection / block service rejection).

Initial messages:
- `code = "content_moderated_profanity"` → message `"Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi."` (User content contains a disallowed word. Please edit and try again.)

The matched-keyword list is **NOT** included in the response (would tip off bypass attempts). The Sentry event captures it for ops review.

### D10 — Test data / sentinel keywords

Tests use synthetic sentinel keywords (e.g., `"sentinel-profanity-DO-NOT-LEAK"`, `"sentinel-uuite-DO-NOT-LEAK"`) injected via test-only Remote Config override. This avoids couplinge tests to the real wordlist (which legitimately churns) and avoids the test suite shipping actual profanity in the repo (a CI / repo-hygiene concern).

The repo-committed `*.default.txt` files SHOULD ship with at least 1-2 placeholder entries for boot integrity (Tier 3 fallback verification); these CAN be banal placeholders like `__seed_profanity_placeholder__` that no real user input would match. Production Remote Config is the source of truth for the actual wordlist — checked in lists are fail-soft fallbacks, not the operational list.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| **Remote Config outage degrades to repo-file (potentially stale) wordlist** | Sentry WARN at every fallback; repo file ships with the latest known-good wordlist + commit-time-stamped header so operators can audit staleness; quarterly UU ITE legal review (per [`docs/06-Security-Privacy.md:159`](../../../docs/06-Security-Privacy.md)) bumps the repo file. |
| **Aho-Corasick false positives on legitimate Indonesian text** (e.g., word collision with profanity term) | Word-boundary-aware matcher; manual quarterly review process surfaces false positives via admin reports; threshold parameter tunable (raise from 3 → 5 if FP rate spikes). Layer 1 REJECT is the highest-friction layer — if FP becomes a launch-blocker, downgrade Layer 1 from REJECT to soft-flag (D1 reversal — a single Remote Config change away if encoded as a flag). |
| **Profanity wordlist bypass via leetspeak / unicode tricks** (`f@ggot`, `fµck`) | Out of scope for this change. Mitigation: the matcher is a stopgap per [`docs/06-Security-Privacy.md:160`](../../../docs/06-Security-Privacy.md); Phase 2 §16 Perspective API picks up the slack on patterns the keyword list misses. Heavier ID-language model lands Month 6+. |
| **Sentry event flood during sustained Remote Config outage** | Sentry-side dedup + 1-event-per-loader-call rate limit (D7); ERROR events fire only on full-fallthrough, not on tier-cascade. |
| **Aho-Corasick build cost on hot path** | Sub-millisecond for ~50 patterns; profiling-deferred if it shows up at p99. Cache built automaton under same 5-min TTL if needed (follow-up). |
| **`moderation_queue` admin volume from Layer 2 Flag rows** | Threshold default 3 (not the docs literal "1") keeps volume modest; Remote Config tunable. Admin Panel filtering ([`docs/07-Operations.md`](../../../docs/07-Operations.md) Phase 3.5) handles triage. |
| **Test-data leakage of real profanity into the repo / CI logs** | Synthetic sentinels (D10), test-only Remote Config override, no real wordlist in repo or test fixtures. |
| **Race: Remote Config is updated mid-loader-call** | Acceptable: the call uses the value resolved at start of call; next call (≤5 min) picks up the update via TTL elapse. |
| **Detekt rule false positives in admin / tombstone / system-content paths** | `// @allow-content-write-without-moderation: <reason>` annotation per [`openspec/project.md`](../../project.md) § Coding Conventions pattern. Documented allowed reasons: `tombstone` (system-set "Akun Dihapus"), `admin_redaction` (admin replaces chat content with `"[Redacted by moderator]"`), `seed` (Flyway test fixtures). |

## Migration Plan

**Order of landings within this change** (single PR, multi-commit):

1. **Spec + design land first** (this PR's proposal phase)
2. **`KeywordMatcher` + tests in `:core:domain`** — pure Kotlin, no integration, reviewable in isolation
3. **`ModerationListLoader` + `RemoteConfigClient` + tests in `:backend:ktor`** — vendor surfaces wired, full ladder coverage
4. **Repo-committed `*.default.txt` files** — placeholder/seed wordlist
5. **Secret Manager slot population** — operations task; not code, but the slot needs a Tier 4 sentinel value before deploy (operations runbook entry)
6. **`TextModerator` orchestrator** — wires loader + matchers
7. **Integration into `PostService` / `PostReplyService` / `ChatMessageService`** — one commit per service, each with tests
8. **Detekt rule** (optional, may defer)

**Pre-archive smoke checklist** (per [`openspec/project.md`](../../project.md) § Staging deploy timing):
- Manual staging deploy via `gh workflow run deploy-staging.yml --ref content-moderation-keyword-lists`
- Smoke: POST a profanity-sentinel post → 400 + correct code; POST a UU-ITE-sentinel post (3 hits) → 200 + queue row visible via direct DB query
- Smoke: pull staging-`moderation_profanity_list` Remote Config slot to empty → POST a profanity-sentinel post → 200 (fail-open works); restore Remote Config → POST same → 400 (recovery works)
- Smoke: Sentry shows `moderation_list_fallback` events for the simulated outage

**Rollback strategy**:
- Layer 1 REJECT is the riskiest UX surface (false positives block users). If operators report unacceptable FP rate post-launch, Remote Config-flip `moderation_profanity_list` to `[]` (empty) — short-circuits the matcher to `Allow`, no redeploy required. Covers Layer 1 rollback.
- Layer 2 Flag is lower-risk (200 success, just a queue row). Same Remote Config-flip rollback works.
- Full pipeline kill: introduce `text_moderation_enabled` Remote Config boolean (default TRUE) gating `TextModerator.moderate(...)` to `Verdict.Allow` short-circuit. **Reserved as a follow-up**, not built in this change — Remote Config-flip of both lists to empty already covers the same operational surface without the additional flag plumbing.
- Schema rollback: this change does NOT alter `moderation_queue` schema (D2). Rollback is purely a code-revert. Flyway is untouched.

## Open Questions

- **OQ1 — `moderation_match_threshold` boundary semantics**: is the threshold "≥ N" or "> N"? Default 3 means "flag at 3 or more matches" feels natural; spec scenarios will codify "≥ N." [Resolved in spec.md.]
- **OQ2 — Cache key scope**: is `mod:list:{tier:profanity}` adequate, or should the key include the env (`mod:list:staging:{tier:profanity}`)? Redis is per-env per [`openspec/project.md`](../../project.md) § Environments — same `:infra:redis` connection points to different Upstash instances per env — so the env is implicit in the connection. No env in the key. [Resolved.]
- **OQ3 — Should the matched keyword set be exposed in the API error response for Layer 1 REJECT?** No — would tip off bypass attempts (D9). The Sentry event captures it for ops review.
- **OQ4 — Is the Detekt rule in scope or deferred?** Deferred to follow-up unless the multi-writer surface (3 services) makes the rule trivially short. **Bias toward in-scope** since 3 writer sites is enough to warrant defense-in-depth, AND a future Premium-username writer + Perspective API writer add more surface. Decision in `tasks.md` Phase 5 — implement if time permits, defer to follow-up if review-loop iterations consume the budget.
