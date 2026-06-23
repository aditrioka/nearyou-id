## Context

The Cloudflare CSAM Scanning Tool (zone-level, free) auto-scans served images and, on an NCMEC/partner hash match, blocks the URL (HTTP 451) and emails the operator — it emits **no webhook**. Open Decision #33 is Resolved: the downstream `/internal/csam-webhook` handler is invoked **admin-triggered at MVP** (the admin pastes the matched URL from CF's email) and via a **Cloudflare Worker from Phase 2+**. Today neither the handler, the archive table, nor the encryption helper exists — `premium-image-upload-pipeline` (#325, V26 `image_uploads` ledger) shipped the upload pipeline and **deferred CSAM to this change by name** ([`premium-image-upload/spec.md:143`](../../specs/premium-image-upload/spec.md); [`V26__image_uploads.sql:7`](../../../backend/ktor/src/main/resources/db/migration/V26__image_uploads.sql)).

This change ships the backend safety core: the takedown handler, the legal-preservation archive (write + AES-256-GCM encryption), and the retention purge worker. The image feature ships dark behind `image_upload_enabled = FALSE` until Month-6, so no real CSAM events occur before launch — but the Pre-Launch security review gates on this being built + tested (`docs/08` lines 288–289, 324, 327, 331).

## Goals / Non-Goals

**Goals:**
- A `csam_detection_archive` table that preserves Kominfo-filing essentials for 90 days, surviving the offending account's deletion, with idempotent dedup.
- A fixed-policy `POST /internal/csam-webhook` takedown (tombstone post → permanent-ban uploader + bump `token_version` → cascade-tombstone → archive → enqueue admin awareness), idempotent and atomic.
- The two non-OIDC auth paths (admin-internal session+CSRF; CF-Worker Bearer+HMAC, rate-limited).
- AES-256-GCM encryption of supplementary metadata via a fail-soft secret-managed key.
- An OIDC-gated daily purge worker with a pending-report preservation hold.

**Non-Goals:**
- The **admin CSAM Detection Log Viewer** (list/filter/decrypt/paste-URL-trigger/file-to-Kominfo) — follow-on admin change `admin-csam-detection-log-viewer` (`docs/07` § CSAM Detection Log Viewer; has its own admin-mockup frame + visual-conformance gate).
- The **Cloudflare Worker deployment** (Phase-2 auto-forward) — operator ops; this change ships + tests the handler's CF-Worker auth path only.
- The **rich operator notification** (Resend email + in-app admin notif) — ships with the admin viewer; this change surfaces awareness via the `moderation_queue` `csam_detected` row.
- Any mobile work. Any zone-level CF CSAM Tool config (that is operator setup; restated as a launch precondition below).

## Decisions

**D1 — Scope boundary at "webhook-and-archive"; admin UI deferred.** The change name (pre-chosen by the codebase) draws the line: the takedown + archive + purge is the load-bearing safety core; the human review/decrypt/file surface is a separate admin capability with its own UI mockup frame and `verify-loop` gate. *Alternative (one mega-change incl. admin UI): rejected* — couples a backend-pure change to the admin-UI visual-conformance gate and bloats review.

**D2 — "Hard-delete" is implemented as the `deleted_at` tombstone, not a physical row `DELETE`.** The product docs say "hard-delete the post"; the project's canonical deletion mechanism is `posts.deleted_at` (filtered by every `visible_*` view; the account-deletion-tombstone pattern, #329). Setting `deleted_at` removes the post from all reads instantly; physical purge remains the account-cleanup worker's job. *Alternative (SQL `DELETE FROM posts`): rejected* — breaks referential history (replies, reports, audit `before_state`) and forks the deletion pattern.

**D3 — The uploader's identity lives inside `encrypted_metadata`; the archive has NO cascading FK to `users`.** Legal preservation requires the archive to outlive the offending account's eventual hard-delete. A plaintext `user_id` FK with `ON DELETE CASCADE` would erase the record; `ON DELETE SET NULL` would lose the identity needed for the Kominfo filing. Storing the uploader id (and other sensitive detail) in the AES-256-GCM `encrypted_metadata` blob both preserves it across deletion and keeps it behind the audit-logged admin-only decrypt. Only the non-identifying Kominfo essentials (`image_hash`, `ncmec_reference`, `cf_match_id`) are plaintext. **The `image_uploads` ledger row is `ON DELETE CASCADE` to `users` (V26), so it vanishes when the uploader is hard-deleted** — therefore the uploader identity MUST be captured **synchronously into the blob during the takedown transaction**, never re-resolved from the ledger afterward.

**D4 — Fail-soft encryption: the takedown is NEVER blocked on the key.** The AES-GCM helper reads `secretKey(env, "csam-archive-aes-key")` and degrades to a no-op (writes `encrypted_metadata = NULL`) when the slot is unprovisioned — mirroring the `:infra:cloud-vision` / `:infra:cloudflare-images` fail-soft posture (the slot is operator-provisioned at the Month-6 launch). Safety-critical mutations (tombstone, ban, cascade) and the plaintext Kominfo essentials always persist. A launch-readiness task asserts the slot is set before `image_upload_enabled` flips TRUE. (Residual: with the key unset, the degraded row loses its blob-only uploader identity — pre-launch this is moot, since the feature is dark behind `image_upload_enabled = FALSE` and no real CSAM events occur; the launch-readiness gate closes it for production.)

**D5 — Both auth paths ship now; the CF-Worker path is the end-to-end-testable one.** The handler must accept both invocation paths (`internal-endpoint-auth` already names the opt-out). The admin-internal path's *auth contract* is specified + unit-tested here, but its production caller (the paste-URL form) lands with the admin viewer. The CF-Worker path (Bearer + HMAC, key `cf-worker-csam-secret`) is fully exercisable E2E in this change. *Alternative (defer the whole handler until the admin UI exists): rejected* — the load-bearing takedown should not wait on a UI, and the CF-Worker path is independently valuable.

**D6 — AES-256-GCM via JDK `javax.crypto`; no new module, no new pin.** Pure JDK crypto reading a secret is not a vendor SDK, so it needs neither an `:infra:*` module nor a `gradle/libs.versions.toml` entry. The helper lives as a backend crypto utility alongside the existing HMAC/jitter helpers. (This is why the substrate WebSearch gate does not fire.)

**D7 — CF-Worker rate-limit key shape.** The 100/hour-per-IP guard uses a Redis key `{scope:csam_webhook}:{ip:<clientIp>}` (two-segment hash-tag form per the `RedisHashTagRule` strict pattern) acquired via the non-per-user `tryAcquireByKey` path (this is an IP limit, not a per-user-daily-reset limit, so it does not use `computeTTLToNextReset`). `clientIp` comes from the request-context value, never raw `X-Forwarded-For`.

**D8 — `moderation_queue` `csam_detected` is the admin-awareness signal.** The auto-action enqueues a `moderation_queue` row (`trigger = 'csam_detected'`, enum reserved at V9 — no schema change), which surfaces in the existing admin report/triage queue. The richer operator email/in-app notification ships with the admin viewer.

### Standards conformance (`docs/11`)

Backend-only change; no mobile, no admin UI (§3.6 not engaged). Builds on the existing Pattern-Registry patterns with **no deviation → no `docs/11` amendment required**:
- **§3.1 layering** — `CsamRoutes` (thin: parse/validate/auth/respond) → `CsamService` (the atomic takedown, the transaction boundary) → `CsamArchiveRepository` + reuse of the post/user/moderation repositories through their interfaces (never their tables directly).
- **§3.2 JDBC** — the whole takedown is **one transaction per service operation**; runs on the shared pool-sized bounded dispatcher.
- **internal-endpoint-auth opt-out** — reuses the established vendor-webhook opt-out seam (the RevenueCat / Apple-S2S precedent) rather than inventing a parallel auth path.
- **Redis rate-limit + hash-tag invariants, `secretKey` helper, fail-soft infra-degrade, `system-actor` audit, `clientIp` request-context** — all reused as-is.
- **Lint annotations (corrected):** the uploader/post **resolution reads** (`FROM image_uploads` → `users`/`posts` in the internal-worker path, no viewer) carry `@AllowMissingBlockJoin("<reason>")` — `BlockExclusionJoinRule` protects `FROM users`/`FROM posts`. The ban/tombstone **writes** (`UPDATE users SET is_banned…`, `UPDATE posts SET deleted_at`) need **no** annotation: `RawFromPostsRule` matches `FROM posts` *reads* only (inert on writes and on `users`); the shipped permanent-ban precedent `ReportResolutionRepository`'s `UPDATE users SET is_banned = TRUE` carries none.

## Risks / Trade-offs

- **Migration V31 contention** (4 parallel sessions also add migrations — #353/#354/#355/#356) → the migration is additive + isolated (one new table), so if a sibling lands first, `git mv` to the next free `V<N>` + bump the in-file/docs refs pre-merge (the documented parallel-Flyway-collision fix). No rebase of logic needed.
- **Handler ships with no production admin caller** (admin-internal path) → acceptable: the CF-Worker path is fully tested, the endpoint is internal + dark behind `image_upload_enabled`, and the admin caller is the immediate follow-up. No real CSAM events occur pre-launch.
- **Cascade-tombstone of ALL the uploader's posts is aggressive** (may tombstone benign posts) → fixed CSAM policy (abundance of caution); the uploader is permanently banned regardless (their content is already hidden via ban/shadow views), and `deleted_at` is reversible by an admin if CF's unblock-review later clears a false positive (handled in the admin-viewer follow-up).
- **Mis-provisioned key at launch could write `encrypted_metadata = NULL` in production** → the launch-readiness task gates `image_upload_enabled = TRUE` on the key slot being set; the plaintext Kominfo essentials are always written, so even the degraded row is filing-sufficient.
- **Concurrent re-triggers for the same image** → `UNIQUE(image_hash)` + `ON CONFLICT DO UPDATE` (enrich-not-reset) + the naturally-idempotent ban (`is_banned` already TRUE) converge the race inside the transaction.

## Migration Plan

1. **`V31__csam_detection_archive.sql`** — additive new table (schema per the `csam-detection` spec); renumber to the next free `V<N>` if a sibling migration squash-merges first. No down migration (project convention). The `source` CHECK is **2-value** (`'admin_manual'`, `'cf_worker'`): the third design-era trigger path "email poll" (`docs/05` line 1281's design language) is deliberately **rejected** per Open Decision #33 (admin-manual MVP + CF-Worker Phase 2) — no schema ever shipped a 3-value enum, so this is a tightening of design language, not drift.
2. **Secrets** — `csam-archive-aes-key` + `cf-worker-csam-secret` are already DESIGN-reserved (`docs/05` line 22) with `staging-*` mirrors; fail-soft when unset, so **no deploy gate** is added. The admin-decrypt read path (which needs the key) ships with the follow-up.
3. **Launch precondition (restated, owned by `premium-image-upload/spec.md:147`)** — the zone-level CF CSAM Scanning Tool MUST be enabled on the `nearyou.id` zone **before** `image_upload_enabled` flips TRUE. This handler is the *response* to a 451 match, not the scanner; it cannot compensate for an unconfigured zone.
4. **Pre-archive smoke** (`docs/05` § Staging deploy timing) — branch-deploy + confirm both routes mount + auth-gate: unauthenticated `POST /internal/csam-webhook` is rejected (not 200), unauthenticated `POST /internal/csam-archive-purge` → `401`. The endpoints are internal + dark, so no functional staging exercise beyond mount/auth.
5. **Rollback** — the table is additive and unused until a trigger fires; the endpoints are dark. A revert, if ever needed, ships as a new forward migration (no retro-edit of a squashed commit).

## Open Questions

- **`premium-image-upload` reconciliation (for the B.3 pass).** Does [`premium-image-upload/spec.md:143-151`](../../specs/premium-image-upload/spec.md) ("CSAM subsystem is deferred / no `/internal/csam-webhook` exists") need a surgical `MODIFIED` now that this change ships it? Current read: **no** — that requirement is scoped "introduced by **this** change" (= #325) and stays true; the zone-CSAM ordering precondition it owns also stays. Default is to leave it untouched (also avoids an archive-conflict with `image-attached-posts` #354, which may also touch that spec). The reconciliation pass confirms.
- **Exact `encrypted_metadata` plaintext payload** (uploader `user_id`, `post_id`, upload timestamp, CF delivery URL?) — finalized at apply time; the spec only constrains "no image bytes" + round-trip. Non-blocking.
