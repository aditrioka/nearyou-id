## 1. Schema (V23 migration)

- [ ] 1.1 Add `V23__referral_tickets.sql` creating `referral_tickets` (`id UUID PK DEFAULT gen_random_uuid()`, `inviter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `invitee_user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE`, `status VARCHAR(32) NOT NULL CHECK (status IN ('pending_activity','granted','expired'))`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `expires_at TIMESTAMPTZ NOT NULL`) with a leading comment header in the V9/V6 style.
- [ ] 1.2 Add the future-worker partial index `CREATE INDEX referral_tickets_pending_idx ON referral_tickets (expires_at) WHERE status = 'pending_activity';` — verify the `WHERE` predicate is the CONSTANT status only (NO `NOW()`/volatile expression, partial-index invariant).
- [ ] 1.3 Add the per-inviter counter index `CREATE INDEX referral_tickets_inviter_status_idx ON referral_tickets (inviter_user_id, status);`.
- [ ] 1.4 Confirm V23 is the next free migration number at apply time (rebase if another in-flight change landed a V23 first — e.g. privacy-flip-worker #321) and that `flyway validate` passes on a fresh DB.

## 2. Referral feature package (`id.nearyou.app.referral`)

- [ ] 2.1 Add `ReferralTicketCreator` interface (single best-effort method, e.g. `suspend fun createTicketIfInvited(inviteCode: String?, inviteeUserId: UUID, inviteeDeviceFingerprintHash: String?)`) — the narrow seam `SignupService` depends on (docs/11 §3.1 cross-feature-via-interface).
- [ ] 2.2 Add `ReferralRepository` with: inviter reverse lookup `SELECT id, is_banned, created_at, device_fingerprint_hash FROM users WHERE invite_code_prefix = :code AND deleted_at IS NULL` (O(1) on the UNIQUE index); ticket INSERT (`status='pending_activity'`, `expires_at = created_at + INTERVAL '14 days'`). Runs on the shared bounded JDBC dispatcher; one transaction for the INSERT.
- [ ] 2.3 Implement `ReferralService` (implements `ReferralTicketCreator`): resolve code → inviter; inviter checks (exists / not-deleted-by-construction-of-the-query / `is_banned=FALSE` / `created_at < NOW() - INTERVAL '30 days'` / `inviter_id != inviteeUserId`); invitee checks (device-fingerprint non-collision when both non-null); then the burst-rate gate; then INSERT. Swallow every failure; structured-log every attempt (created / rejected+reason) following the `signup.blocked` precedent.
- [ ] 2.4 Wire the per-inviter burst limiter via the shipped `RateLimiter` (7-day window, max 3), key `rate:{inviter:<inviter_id>}:referral_ticket` ({scope:value} hash-tag → `RedisHashTagRule`). Consult it as the LAST gate before INSERT; on limiter error, swallow → no ticket (fail-closed on referral only). Do NOT use `computeTTLToNextReset` (not a daily WIB limit).
- [ ] 2.5 Koin: register `ReferralRepository`, `ReferralService`, and bind `ReferralTicketCreator` to `ReferralService`; reuse the existing `RateLimiter`, `DataSource`, and bounded-dispatcher singletons (no new secret — `invite-code-secret` already wired for `InviteCodePrefixDeriver`).

## 3. Signup wiring

- [ ] 3.1 Add `@SerialName("invite_code") val inviteCode: String? = null` to `SignupRequestDto` (`SignupRoutes.kt`) and thread it onto the internal `SignupRequest` data class (default null).
- [ ] 3.2 In `SignupService`, add the `ReferralTicketCreator` constructor dependency and call it best-effort AFTER the `users` INSERT commits (step 5) and before/around token issuance — wrapped so any throwable is logged and never propagates. Signup result + status code unchanged.
- [ ] 3.3 Confirm the success response and all five existing signup error codes are unchanged (referral introduces no new code on the signup path — `auth-signup` Error-taxonomy-stability requirement stays satisfied).

## 4. Tests

- [ ] 4.1 `ReferralService` service-container tests (`@Tags("database")`, `autoClose(hikari())` + size 2): valid code → `pending_activity` ticket with `expires_at = created_at + 14d`; absent/blank code → no ticket; unresolvable code → no ticket; soft-deleted inviter → no ticket; banned inviter → no ticket; inviter `created_at` within 30d → no ticket; self-invite → no ticket; device-fingerprint collision → no ticket; null invitee fingerprint → not treated as collision; 4th ticket within 7-day window → rejected; duplicate `invitee_user_id` → swallowed (idempotent). Use deterministic seed-independent inputs (project.md § Test-data conventions).
- [ ] 4.2 Signup integration test: signup with a valid seeded inviter code returns 201 AND a ticket row exists; signup with a garbage code returns 201 AND no ticket; signup with no code returns 201 AND no ticket (referral non-blocking + silent).
- [ ] 4.3 Extend `AuthWireFormatTest` (or equivalent) to pin the new `invite_code` wire field name (snake_case) and its optionality (absent field parses, default null).
- [ ] 4.4 Assert the partial-index migration applies on a fresh DB (covered by the boot-once migration path; add a focused assertion if a gap exists).

## 5. Conformance, docs, verification

- [ ] 5.1 Reconcile docs/05-Implementation.md § Referral System — DESIGN: narrow the DESIGN note to reflect that ticket creation at signup + `referral_tickets` now ship in this change, while the activity-gate worker, `granted_entitlements`, grants, and the richer IP/identifier anti-collision checks remain DESIGN (deferred). Keep docs/01/08 narrative intact.
- [ ] 5.2 Run the full pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` — all green (CI runs both lint frameworks).
- [ ] 5.3 Pre-archive staging smoke (runtime-impacting backend change, docs/11 §5 DoD): deploy the branch to staging, seed a >30-day inviter, signup with their `invite_code_prefix` → confirm one `pending_activity` ticket; signup with a garbage code → 201 + no ticket. Capture evidence in the PR body.
- [ ] 5.4 Update the PR title/body at the phase boundary (`feat(backend): referral ticket creation at signup (V23)`) and post `/review` per the apply workflow.
