## 1. Reconciliation pass

- [x] 1.1 Re-read [`AuthPlugin.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/AuthPlugin.kt) and verify the `UserPrincipal` data class still carries exactly four fields (`userId`, `tokenVersion`, `subscriptionStatus`, `isShadowBanned`); abort if a fifth field has been silently added since proposal time.
- [x] 1.2 Re-read [`UserRepository`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/infra/repo/) (or equivalent) `findById` / `baseSelect` shape and confirm both `subscription_status` and `is_shadow_banned` are projected by the SAME SELECT; abort if the implementation has split these into separate round-trips since proposal time.
- [x] 1.3 Re-read [`chat-realtime-broadcast/spec.md`](../../specs/chat-realtime-broadcast/spec.md) lines 123-147 and verify the publish-side shadow-ban skip Requirement is unchanged from archive (the new auth-jwt Requirements MUST NOT contradict it).
- [x] 1.4 Re-read [`post-likes/spec.md`](../../specs/post-likes/spec.md) and confirm the "Read-site constraint" Requirement still depends on principal-loaded `subscriptionStatus` (i.e., not refactored to a different mechanism since archive).

## 2. Spec deltas (already drafted in propose phase)

- [x] 2.1 Confirm `specs/auth-jwt/spec.md` carries exactly three ADDED Requirements: `UserPrincipal.subscriptionStatus` field, `UserPrincipal.isShadowBanned` field, and the single-load-site invariant.
- [x] 2.2 Confirm each Requirement has at least one `#### Scenario:` block (validate counts: subscriptionStatus = 3 scenarios, isShadowBanned = 2 scenarios, single-SELECT invariant = 3 scenarios).
- [x] 2.3 Confirm spec text uses SHALL/MUST normative language consistently and references canonical authorities (chat-realtime-broadcast, post-likes) by spec path, not by line number.
- [x] 2.4 Run `openspec validate auth-jwt-principal-fields-documentation --strict`; abort if any error.

## 3. Multi-lens review (Phase D)

- [x] 3.1 Round 1 — general lens: dispatch a `general-purpose` sub-agent to read the proposal + design + specs deltas and report any factual misstatements, scope-creep risk, or unclear language. Cap report at 400 words.
- [x] 3.2 Round 1 — OpenSpec format-and-correctness lens: dispatch a `general-purpose` sub-agent to verify (a) `### Requirement:` / `#### Scenario:` heading depths, (b) every Requirement has ≥1 scenario, (c) WHEN/THEN structure on each scenario, (d) MODIFIES vs ADDED is correctly chosen, (e) no inadvertent REMOVED, (f) capability folder name `auth-jwt` matches existing canonical spec folder. Cap report at 300 words.
- [x] 3.3 Reconcile findings from rounds 1 and 2 against the proposal/design/specs; apply edits if findings warrant (do NOT silently accept "engineering judgment" overrides per `CLAUDE.md` § Engineering judgment over context budget).
- [x] 3.4 Skip security and test-coverage lenses (docs-only change, no behavioral surface — predetermined low-value lens per proposal § Multi-lens review).
- [x] 3.5 Round 2 only if round 1 findings warrant; otherwise stop.

## 4. PR + archive prep

- [x] 4.1 Confirm branch name follows convention: change name itself in kebab-case (`auth-jwt-principal-fields-documentation`), no `<area>/<slug>` since this is a spec change, not infra/tooling.
- [x] 4.2 PR title at propose phase: `docs(openspec): propose auth-jwt-principal-fields-documentation`. Body includes proposal § Why, the three ADDED Requirements (one-line each), and the link to the FOLLOW_UPS entry being closed.
- [x] 4.3 At archive time, the PR title MUST be retitled (per `CLAUDE.md` § Delivery workflow: "PR title and body MUST stay current at every phase boundary"). Recommended retitle: `docs(auth-jwt): document UserPrincipal.subscriptionStatus + isShadowBanned`.
- [x] 4.4 Pre-push lint check: run `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` even though this is docs-only (defense-in-depth per `CLAUDE.md` § Delivery workflow precedent — PR #31/#32 hit CI lint failures because `ktlintCheck` was skipped locally).

## 5. Archive

- [x] 5.1 `/opsx:archive` syncs the spec deltas into `openspec/specs/auth-jwt/spec.md` (post-archive).
- [x] 5.2 In the same archive commit: delete the `auth-jwt-spec-debt-userprincipal-subscription-status` entry from `FOLLOW_UPS.md` (the entry's third action item — RevenueCat webhook verification — stays open under the future Phase 4 `revenuecat-webhook` change's scope; document that in the archive commit body so the future change author has the context).
- [x] 5.3 Move `openspec/changes/auth-jwt-principal-fields-documentation/` under `openspec/changes/archive/<YYYY-MM-DD>-auth-jwt-principal-fields-documentation/`.
