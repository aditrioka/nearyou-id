## Context

`PATCH /api/v1/user/username` and `GET /api/v1/username/check` are DESIGN-only ([`docs/02`](../../../docs/02-Product.md) § Premium Username Customization, [`docs/05`](../../../docs/05-Implementation.md) § Premium Customization Endpoint). The data model is already in place: `users.username VARCHAR(60) UNIQUE` + `users_username_lower_idx` + `users.username_last_changed_at` + `users.subscription_status` (V2), and `reserved_usernames` + `username_history` (+ `username_history_old_lower_idx` / `_released_idx` / `_user_idx`) (V3). Signup-time auto-generation (`UsernameGenerator.kt`) already implements the reserved + release-hold collision checks this change reuses. The text-moderation pipeline, rate-limit infrastructure, and the `username_release_scheduled` notification catalog type all already exist. This change is therefore **net-new backend wiring over an existing substrate — no migration, no new dependency.**

Premium status is read from the pre-existing `users.subscription_status` column (the same gate `premium-search` already uses); the column's *first writer* (the RevenueCat webhook) is in flight as [#291](https://github.com/aditrioka/nearyou-id/pull/291) but this change does not depend on it — the gate logic is correct whatever the column's current value.

## Goals / Non-Goals

**Goals:**
- Ship the two backend endpoints with the full validation pipeline, race-safe release-hold transaction, feature-flag kill switch, Premium gate, 30-day cooldown, and the three rate limits — the complete backend capability, not a thin "just rename" slice.
- Reuse existing seams (premium check, moderation pipeline, rate-limit infra, `UsernameGenerator` collision logic, notifications) rather than forking new ones.
- Stay migration-free and footprint-disjoint so it lands in parallel with the in-flight Phase 4 claims.

**Non-Goals:**
- Mobile Settings UI (Premium entry, paywall, live probe, cooldown countdown, downgrade banner) — separate follow-on `mobile-*` change.
- Admin username-change oversight (`username_history` viewer, borderline-candidate override, manual handle release) — separate Phase 3.5 admin change ([`docs/07`](../../../docs/07-Operations.md), docs/08 Phase 3.5 #24).
- Changing the signup-time auto-generation behavior, the moderation pipeline's internals, or the notifications catalog.
- The RevenueCat webhook / subscription-status *writes* (#291).

## Decisions

**D1 — Backend layering (Pattern Registry conformance).** New `user` feature package: `UserUsernameRoutes` (thin — parse/validate/authenticate/respond) → `UsernameChangeService` (business rules + the transaction boundary) → `JdbcUsernameRepository` (SQL). Conforms to [`docs/11`](../../../docs/11-Engineering-Standards.md) § 3.1 layering and § 3.2 JDBC (every JDBC call on the shared pool-sized `limitedParallelism` dispatcher, never raw `Dispatchers.IO`). **No new pattern for any listed concern → no docs/11 amendment required.** (Standards-conformance note, per B.4 / `openspec-propose` rule.)

**D2 — Gate ordering: flag → premium → cooldown → validation → moderation → commit.** Cheapest/most-decisive checks first, so an off flag or a Free user never reaches moderation or the DB lock. Distinct HTTP codes per gate make the failure modes testable and let the (deferred) mobile UI branch correctly: flag OFF → **503**; not Premium → **403** (paywall); within 30-day cooldown → **429**; malformed candidate → **422**; reserved / release-held / taken → **409**; moderation hit → **422** + advisory queue row. *(Exact codes are pinned in the spec scenarios; chosen over 402 for the paywall because 402 is non-standard for auth-style gating and the existing premium-gated endpoints use 403.)*

**D3 — Cooldown is DB-authoritative; Redis only throttles abuse.** The "1 successful change / 30 days" rule is enforced against `users.username_last_changed_at` (durable, survives a Redis flush, and is the value the spec/test matrix references). Redis rate-limit-infrastructure handles only the **10 failed attempts / hour** (anti-probing) and **3 availability probes / day** counters — via `computeTTLToNextReset` + `{scope:<value>}` hash-tag keys (rate-limit invariants; no hardcoded midnight math). Alternative considered (a Redis cooldown key) rejected: a Redis eviction would silently let a second change through inside the window.

**D4 — The availability probe is non-authoritative; correctness lives under the row lock.** `GET /username/check` runs the read-only validation (format + reserved + release-hold + uniqueness) but is explicitly advisory. The authoritative recheck runs inside the PATCH transaction after `SELECT … FOR UPDATE` on the caller's `users` row, closing the probe→commit TOCTOU window. The unique index on `username` is the final backstop (a caught unique-violation maps to 409).

**D5 — Single transaction for the change.** Under `SELECT … FOR UPDATE` on the user row: re-validate (uniqueness via `LOWER`, reserved, release-hold) → `UPDATE users SET username = ?, username_last_changed_at = NOW()` (the write carrying the **invariant-#7 username-write allowlist annotation**) → `INSERT INTO username_history (user_id, old_username, new_username, changed_at, released_at)` with `released_at = changed_at + INTERVAL '30 days'` → `INSERT` the `username_release_scheduled` notification. All-or-nothing; a failure rolls back the whole change (no orphaned history row, no half-applied rename).

**D6 — The confirmation notification is a raw in-transaction INSERT (in-app only), not the async emitter.** Atomicity requires the `notifications` row to commit with the rename, so it cannot go through the fire-and-forget `NotificationEmitter`/FCM path. This follows the established **admin-emit precedent** (raw in-tx INSERT, in-app-only, no FCM — e.g. the admin chat-redaction notification). To respect § 3.1 cross-feature boundaries, the insert goes through the notifications feature's transaction-aware repository helper (passing the open `Connection`), not hand-rolled SQL against another feature's table. `username_release_scheduled` is a confirmation of the user's own action → no push needed. *(If no transaction-aware notifications insert helper exists yet, adding one is the minimal cross-feature seam; flagged in tasks.)*

**D7 — Reuse, don't fork, the collision + moderation + premium seams.** Reserved + release-hold collision logic is the same predicate `UsernameGenerator` already runs at signup — extract/share it rather than duplicate. The profanity + UU ITE check calls the existing text-moderation pipeline (`content-moderation-keyword-lists` / `text-moderation-perspective-api-layer`) against the candidate handle. The premium check reuses whatever accessor `premium-search` uses on `users.subscription_status`. Cross-feature access is via service/repository interfaces (§ 3.1), never foreign tables directly.

**D8 — Moderation hit ⇒ REJECT upfront + advisory queue row.** Per [`docs/06`](../../../docs/06-Security-Privacy.md) § Premium Username Moderation (and the docs/08 Pre-Launch test #5): the change is rejected (422), the user is told to pick another handle, and a `moderation_queue` row with `trigger = 'username_flagged'` is inserted for admin awareness; >3 flagged attempts / 24h raises the user's anomaly score. This resolves the wording gap with docs/02 ("soft-flags") in favor of the more specific, test-backed docs/06 behavior — recorded so reviewers don't re-flag it.

## Risks / Trade-offs

- **Probe→commit TOCTOU (two users racing for the same handle)** → mitigated by the `FOR UPDATE` recheck + the `username` unique index as the final backstop (D4); the probe is documented non-authoritative.
- **Moderation false-positive on a legitimate Indonesian word matching the UU ITE list** → the user simply picks another handle now; the admin override that un-blocks a borderline candidate is the deferred Phase 3.5 admin change. Acceptable for this slice (no data loss, just friction).
- **`subscription_status` not yet written by a real billing source (#291 unmerged)** → no functional risk: the gate reads the column correctly; until the webhook lands, no user is `premium_active` in staging, so the path is exercised via test fixtures / a manually-set column. Documented, not blocking.
- **Cross-feature notification insert** → the only boundary-crossing seam; handled via the notifications repository's tx-aware helper (D6) to avoid a raw foreign-table write, preserving § 3.1.
- **Reserved-list adjacency with #294** → read-only; no schema or file overlap. If #294 lands a `reserved_usernames` migration first, this change is unaffected (it only reads).

## Migration Plan

**No Flyway migration.** A design task verifies the next free V-number (V21) is *not* consumed by this change — all read/written tables and indexes pre-exist (V2/V3). Rollback = revert the PR; no schema to unwind. Deploy is a standard backend rollout; the feature flag `premium_username_customization_enabled` (default TRUE) is the runtime kill switch if an abuse pattern emerges post-deploy.

## Open Questions

- Does a transaction-aware `notifications` insert helper already exist, or must this change add one (D6)? Resolved at implementation by inspecting the notifications feature's repository; adding a tx-overload is the fallback. (Captured as a tasks item; not a blocker.)
- Exact reuse shape for the shared reserved/release-hold predicate (extract a shared function vs. call a `UsernameGenerator`-adjacent repository method) — an implementation-detail choice, settled in `/opsx:apply`, constrained by "no duplicated SQL."
