## 1. Worker guard (privacy-flip-worker)

- [ ] 1.1 `PrivacyFlipWorker.SQL`: add `AND subscription_status NOT IN ('premium_active', 'premium_billing_retry')` to the `eligible` CTE; update the KDoc/comment block (design D5)
- [ ] 1.2 `PrivacyFlipWorkerTest`: a premium_active AND a premium_billing_retry user with an elapsed deadline are NOT flipped, not counted, and get no audit row; a `free` sibling in the same run IS flipped

## 2. Rate limiter + audit logger

- [ ] 2.1 New `admin/ratelimit/PrivacyFlipResolveRateLimiter` (audit-ledger soft-cap instance, cap 10/admin/trailing hour on `action_type = 'privacy_flip_resolved'`; in-tx `countInTrailingHour(conn, adminId)` + own-connection overload + `isAtOrOverCap`) (design D3)
- [ ] 2.2 `AdminAuditLogger.logPrivacyFlipResolved(conn, adminId, targetUserId, reason, beforeState, afterState, ip, userAgent)` → `insertWithConnection` with `action_type = 'privacy_flip_resolved'`, `target_type = 'user'`

## 3. Repository resolve transaction

- [ ] 3.1 `AdminPrivacyFlipsRepository`: take `auditLogger` + `rateLimiter` constructor deps; add `resolve(userId, evalInstant, actingAdminId, reason, ip, userAgent): ResolveOutcome` — `SELECT … FOR UPDATE` → OVERDUE eligibility (`scheduled IS NOT NULL AND scheduled <= evalInstant`) → cap → subscription-aware branch (free → `@allow-privacy-write: admin_resolve` flip UPDATE; premium → schedule-only clear) → one audit row → commit; rollback on any throw (design D2)
- [ ] 3.2 `ResolveOutcome` sealed type: `Applied`, `Cleared`, `Ineligible`, `RateLimited`; update the class KDoc (no longer "exposes NO write path")

## 4. Route + wiring + templates

- [ ] 4.1 `AdminPrivacyFlipsRoute`: extract the list render into `respondPrivacyFlipList(…, message)`; add `POST /privacy-flips/{user_id}/resolve` with CSRF → owner/admin → UUID (400) → required reason (400, `.take(200)`) → `repo.resolve` → HTMX re-render with outcome message / no-JS 303 PRG; model gains `canResolve`, `quotaUsed`/`quotaCap` (owner/admin only), per-row `resolveAction` (design D1/D6)
- [ ] 4.2 `AdminModule`: construct `PrivacyFlipResolveRateLimiter`, pass it + `auditLogger` into the repository and route
- [ ] 4.3 `privacy-flips-table.peb`: outcome message, quota chip, owner/admin-only "Action" column, `form.expedite` resolve control on OVERDUE rows only (hidden `_csrf`, required `reason` maxlength 200, `hx-post` + `hx-confirm`, `btn tonal sm` + `bolt` icon); no `admin.css` change
- [ ] 4.4 `privacy-flips.peb`: drop the `READ-ONLY` chip, update the sub-copy + template comment

## 5. Tests (admin-privacy-flip-monitor delta)

- [ ] 5.1 Route test: move the 405 assertion to the bare collection path wording; GET still writes no audit row / mutates nothing
- [ ] 5.2 Route test: owner sees the resolve control on an OVERDUE row and none on an IN_WINDOW row; read_only + moderator see no control and no quota chip
- [ ] 5.3 Route test: HTMX resolve of a `free` OVERDUE user → fragment with "applied" message, user gone from list, DB flipped, one audit row with reason; no-JS resolve → 303 to `/admin/privacy-flips`
- [ ] 5.4 Route test: premium_active AND premium_billing_retry OVERDUE users → schedule cleared, opt-in stays TRUE, `resolution = "cleared"`
- [ ] 5.5 Route test: audit `before_state` / `after_state` snapshot shape (applied branch)
- [ ] 5.6 Route test: soft-deleted `free` OVERDUE user is resolvable (flip applied + audit row)
- [ ] 5.7 Route test: gating — unauthenticated 302 + no write; bad CSRF 403 + `admin_csrf_violation` + no write; read_only + bad CSRF → CSRF violation (CSRF before role); moderator 403; read_only 403
- [ ] 5.8 Route test: blank reason 400 no write; malformed id 400 no write; over-long reason succeeds with stored reason ≤ 200 chars
- [ ] 5.9 Route test: IN_WINDOW target → "not resolvable" (200), no write; boundary row (scheduled == eval) IS resolvable; second resolve of the same user → no-op; unknown id → 200 no-op
- [ ] 5.10 Route test: 11th resolve in an hour → "quota exceeded", no write; 20 destructive rows don't block a resolve; per-admin cap (B unaffected by A)
- [ ] 5.11 Repository test: rate-limit count only includes in-window `privacy_flip_resolved` rows for the acting admin (excludes other action types + >1h-old rows)
- [ ] 5.12 Repository test: an audit-write failure inside `resolve` rolls back the UPDATE (fault-injected logger) — opt-in stays TRUE, schedule stays set, no audit row

## 6. Docs + invariants + board

- [ ] 6.1 `openspec/project.md` § Coding Conventions: privacy-flag write allowlist gains `admin_resolve` (scope spelled out) (design D4)
- [ ] 6.2 `docs/08-Roadmap-Risk.md` lint list: the privacy-write annotation set gains `admin_resolve`
- [ ] 6.3 `docs/05-Implementation.md` "Effective private" paragraph: name the admin resolve writer + the worker's premium exclusion
- [ ] 6.4 `docs/07-Operations.md` § Privacy Flip Monitor: the resolve write is SHIPPED (replace the "DESIGN follow-up" sentence); note the worker's premium exclusion
- [ ] 6.5 `dev/mockups/nearyou-admin-mockup.html` frame 17: resolve control on the OVERDUE row, drop `READ-ONLY`, caption path + prose updated (docs/11 §3.6)

## 7. Verification

- [ ] 7.1 Backend gate on a throwaway Postgres + Redis: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test`
- [ ] 7.2 Admin manual bring-up (verify-loop §B): screenshot the monitor (owner, OVERDUE row with control; read_only without), an applied resolve, a cleared resolve, and a quota/ineligible message; compare against board frame 17; evidence in the PR body
- [ ] 7.3 Pre-archive staging branch deploy + smoke (runtime-impacting backend change; docs/11 §5.4)
- [ ] 7.4 Archive: sync deltas; hand-edit the `admin-privacy-flip-monitor` Purpose paragraph (it still says "strictly read-only … exposes no clear/expedite control")
