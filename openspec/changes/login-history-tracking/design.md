## Context

`referral-grant-worker` (#353, merged) implements the referral activity gate's durable legs (≥ 2 posts, 14-day expiry, inviter good-standing) but defers five anti-abuse legs — ≥ 3 login-days, ≥ 5 app sessions, and three anti-collision checks (device-fingerprint history, IP /24 overlap, recently-seen identifier) — because nearyou-id persists no durable login/IP/device history. Refresh-token rotation deletes its trail; the client `session_start` event is consent-gated analytics, unreliable as a security signal. The archived `referral-grant-worker` spec's negative-guard requirement explicitly names this follow-up.

This change ships the durable store (`login_events`) and activates the five legs. The hard part is not the table — it is integrating a new PII data category correctly into the UU-PDP lifecycle (consent, retention, export, deletion) and getting the anti-collision semantics right without false-voiding legitimate referrals. The relevant systems all already exist on `main`: the auth routes (`AuthRoutes.kt`), the referral gate (`ReferralGrantRepository` / `ReferralActivityCheckWorker`), the retention worker (`/internal/cleanup`), the export worker (`account-data-export`), and the hard-delete worker (`account-hard-delete-worker`).

## Goals / Non-Goals

**Goals:**
- A durable, append-only, per-user `login_events` record written from both the sign-in and refresh flows.
- Activate all five deferred activity-gate legs against that record; flip the negative-guard spec requirement to positive.
- Full UU-PDP lifecycle integration: 90-day retention purge, data-export inclusion (now with IP), hard-delete cascade, and a documented security/legitimate-interest consent posture.
- Honor every touched invariant (client-IP accessor, partial-index immutability, raw-read annotations, bounded DB dispatcher) without relaxing any.

**Non-Goals:**
- No admin UI for browsing login history (not requested; could be a future surface).
- No new referral `status` value — anti-collision voids reuse the existing terminal `'voided'` (V33).
- No change to the mobile client (the sign-in/refresh request shapes are unchanged; `device_fingerprint_hash` is already sent).
- No backfill — `login_events` starts empty; the legs evaluate whatever history has accrued (correct: pre-launch, no production tickets exist).

## Decisions

### D1 — Write at the route layer via a thin `LoginEventRecorder`, not inside `RefreshTokenService`
`RefreshTokenService` has no request context (no `call`, no IP, no request body). The IP MUST come from the `call.clientIp` request-context accessor (the client-IP invariant; raw `X-Forwarded-For` is forbidden by `RawXForwardedForRule`), and the device-fingerprint hash is on the request body. So the recorder is invoked from `AuthRoutes.kt`'s `signin` and `refresh` handlers, on the success path, after the token pair is issued. `RefreshTokenService` stays untouched — minimal blast radius. The recorder is a small class (`LoginEventRecorder`) over a `LoginEventRepository` (JDBC, Connection-per-call, bounded DB dispatcher per docs/11 §3.2 — the `ReferralGrantRepository` precedent). *Alternative considered:* thread request context into `RefreshTokenService` — rejected (pollutes a pure session-rotation service with HTTP concerns).

### D2 — The write is best-effort / fail-soft
A `login_events` insert failure MUST NOT break sign-in or refresh. Login history is a non-critical side effect; an auth response must never fail because an audit row could not be written (the `NoOpImageModerator` / fail-soft precedent). The recorder catches, logs, and returns. *Consequence:* a dropped write under-counts a legitimate invitee's engagement legs → at worst a false *pending* (re-evaluated next run), never a false *grant* — the safe direction.

### D3 — Schema: `INET` + a `STORED` generated `/24`
Store `ip INET` (Postgres-native) and derive `ip_subnet_24 INET GENERATED ALWAYS AS (network(set_masklen(ip, 24))) STORED`. `set_masklen` and `network` are `IMMUTABLE`, so the generated column is legal and the /24 is always consistent with the IP — no app-side bit-twiddling, no drift. The /24 overlap leg compares `ip_subnet_24` directly. *Alternative:* compute the /24 in the app at write time — rejected (duplicated logic, drift risk).

### D4 — IP stored raw (not hashed); fingerprint and identifier stored hashed
`ip` is stored raw because (a) it is the user's *own* data, surfaced to them in the data export for security transparency ("where you signed in" — the Data Export Scope Matrix already mandates IP), and (b) the /24 leg needs the network value. By contrast `device_fingerprint_hash` and `identifier_hash` are opaque hashes computed upstream (the identifier is the SHA-256 of the provider `sub`, never the raw subject) — there is no transparency value in a raw form and hashing minimizes exposure. This mix is deliberate: minimize what must be opaque, expose what the user benefits from seeing.

### D5 — Consent posture: security / legitimate-interest, exempt from the analytics toggle (operator-confirmed)
`login_events` is collected for **all** authenticated users as essential account-security + referral-anti-abuse data under a legitimate-interest basis (UU-PDP arts. 20–22 reserve *consent* for *non-essential* purposes; security/fraud-prevention is a recognized essential/legitimate-interest purpose). It is therefore NOT gated by `users.analytics_consent.analytics` (which governs Amplitude / product analytics only). This is what unblocks the legs: the original deferral reason was that the app-sessions signal depended on the consent-gated client `session_start` event — a user who declined analytics produced no signal and would unfairly fail the gate. Sourcing the signal server-side from `login_events` removes that coupling. The processing purpose, the 90-day retention, and the export inclusion are disclosed per docs/06's privacy-policy / consent-flow obligations. *Alternatives considered:* gate behind the analytics toggle (rejected — reintroduces the exact deferral problem and weakens anti-abuse for privacy-conscious users); a dedicated opt-out "security" consent category (rejected — heavier UI for data that is always-on for security anyway; legitimate-interest is the correct basis, not consent).

### D6 — "App session" = idle-gap sessionization (30-minute boundary)
A server-side "session" is a `login_events` row (signin or refresh) not preceded by another of that user's events within the prior 30 minutes — computed with a `LAG(occurred_at) OVER (ORDER BY occurred_at)` window function over the ticket window; the first event (NULL lag) and every event after a > 30-minute gap count as a new session. This mirrors the standard analytics sessionization definition and replaces the deferred `session_start` event. The 30-minute boundary must exceed the access-token TTL (otherwise a normal mid-use refresh would start a "new session"); the current access-token TTL is well under 30 minutes, so continuous use collapses to one session and a genuine return-after-idle starts a new one. *Alternative:* count signin events only — rejected (the app stays logged in for the 30-day refresh TTL, so signins are rare; ≥ 5 signins would almost never pass).

### D7 — Login-days bucketed in `Asia/Jakarta`
"≥ 3 different days" buckets `occurred_at` by calendar day in `Asia/Jakarta` (the app's single market), not UTC — `COUNT(DISTINCT (occurred_at AT TIME ZONE 'Asia/Jakarta')::date)`. A user active each evening WIB must not have those evenings split across UTC midnight. WIB is a fixed +07:00 offset (no DST), so this is unambiguous.

### D8 — Anti-collision voids reuse the existing terminal `'voided'`; engagement shortfalls stay `pending_activity`
The two engagement legs join the ≥ 2-posts leg as *thresholds reached over time*: a clean ticket below any engagement threshold stays `pending_activity` and is re-evaluated each daily run until it passes or expires at 14 days. The three anti-collision legs are *abuse signals*: any positive collision sets the terminal `'voided'` (added by V33 for banned-inviter voids) — no new status, no migration to `referral_tickets`. Reusing `'voided'` means analytics cannot sub-distinguish a banned-inviter void from an anti-collision void; accepted as minor (both are abuse terminal states; a `void_reason` column is a possible future refinement). **Absence of inviter history is NOT a collision** — the anti-collision legs void only on a *positive* match, so an inviter with little/no login history never false-voids.

### D9 — Leg 5 ("recently-seen identifier") realized as cross-account-on-shared-hardware
docs/01 §213 says the invitee's identifier must not be in "the inviter's recently-seen list" but does not define the mechanism. We realize it as: the invitee's `identifier_hash` does NOT appear on any `login_events` row, within the inviter's last 90 days, that shares one of the inviter's device-fingerprint hashes or `ip_subnet_24` subnets. This is the strongest self-referral tell (the same physical device/network signed into both the inviter and the would-be invitee account). It is distinct from leg 3 (which checks the invitee's *current* fingerprint against the inviter's history) — leg 5 catches the invitee *identity* having appeared on the inviter's hardware even from a different fingerprint. This interpretation is flagged for review (see Open Questions).

### D10 — One workhorse index, no volatile predicate
`(user_id, occurred_at DESC)` serves every leg read — the invitee's own windowed counts (legs 1, 2) and the inviter's windowed history scans (legs 3, 4, 5), each of which is `WHERE user_id = ? AND occurred_at >= ?` with an in-row filter on fingerprint/subnet/identifier. No partial `WHERE` predicate is used, so the partial-index immutability invariant (no `NOW()` in an index predicate) is satisfied trivially. A second index on `(device_fingerprint_hash)` / `(ip_subnet_24)` for the cross-user leg-5 scan is deferred unless profiling shows the per-inviter row count makes it necessary (the inviter's 90-day history is small).

### D11 — No new Pattern Registry entry
Every piece reuses an existing docs/11 pattern: route-layer side-effect write, JDBC `Connection`-per-call repository with the service owning the transaction (the `ReferralGrantRepository` precedent), bounded DB dispatcher (§3.2), and the internal-worker sweep pattern (the retention worker). No second pattern is introduced for any Pattern-Registry concern, so no docs/11 amendment is required.

## Risks / Trade-offs

- **IP /24 false positives on carrier-grade NAT** → The dominant risk. Indonesian mobile carriers (Telkomsel, Indosat, XL) heavily NAT subscribers; two *unrelated* users can share a /24. docs/01 §213 mandates the /24-alone check, so a legitimate referral where invitee and inviter happen to share a carrier /24 would be voided. *Mitigations:* the void is per-ticket (the inviter can still refer others), and the posts + login-days + sessions legs plus the per-inviter burst limit already gate heavily. *Softening option* (see Open Questions): require the /24 overlap to be *corroborated* by a fingerprint or identifier signal before voiding, rather than voiding on /24 alone. Surfaced for an operator decision because it trades anti-abuse strictness against legitimate-referral loss in exactly the market we serve.
- **Raw IP is PII** → Mitigated by the security-purpose legitimate-interest basis, the 90-day retention purge, hard-delete cascade, and export-to-the-user transparency. Stored only for authenticated events.
- **`login_events` growth** → Every signin + every refresh writes a row; refresh recurs roughly each access-token TTL while a user is active, so volume is non-trivial. Bounded by the 90-day purge and indexed by `(user_id, occurred_at)`. Acceptable for MVP scale; revisit if the table dominates DB size.
- **Sessionization threshold is a heuristic** → 30 minutes is a documented constant; too small over-counts sessions, too large under-counts. Tunable in one place if real data warrants.
- **Best-effort writes can drop events** → A dropped write biases toward a *pending* ticket (under-counted engagement), never a false grant — the safe direction (D2).

## Migration Plan

- **V34 is purely additive** — a new `login_events` table; no change to any existing table, no backfill. Rollback is a `DROP TABLE` with no data dependency until the legs read it.
- **Deploy atomicity** — the migration, the recorder wiring, and the leg evaluation ship together in one squash-merge. On first deploy `login_events` is empty, so the engagement legs naturally hold tickets `pending_activity` until history accrues, and the anti-collision legs find no matches (no false voids). Pre-launch there are no production tickets, so there is nothing to grandfather; were this to ship post-launch, in-flight tickets whose window predates the table's first row would need grandfathering (evaluate without the login-history legs) — noted, not implemented, because it does not apply pre-launch.
- **Staging smoke** — `/health/ready` green (V34 applied), a sign-in / refresh produces a `login_events` row, `/internal/cleanup` returns the new `login_events_deleted` count, and an export includes the IP-bearing session-history CSV.

## Open Questions

1. **IP /24 leg strictness (recommend surfacing to the operator).** Implement the canonical docs/01 §213 "/24-alone voids" check, or soften to "/24 overlap voids only when corroborated by a fingerprint or identifier signal"? The canonical form is stricter (more self-referral caught) but will false-void some legitimate same-carrier referrals in the Indonesian mobile market. Default in this proposal: implement canonical; flag the softening as a fast-follow if real-world void rates are high. This is the single decision most worth an explicit operator call.
2. **Leg-5 mechanism (D9).** The "inviter's recently-seen list" is undefined in docs/01 §213; the chosen realization (cross-account-on-shared-hardware) is one defensible reading. Confirm it matches intent, or narrow/widen the lookback.
3. **Anti-collision void analytics.** Reusing `'voided'` for both banned-inviter and anti-collision voids loses sub-attribution. Add a `void_reason` column now, or defer until analytics needs it? (Proposal defers.)
