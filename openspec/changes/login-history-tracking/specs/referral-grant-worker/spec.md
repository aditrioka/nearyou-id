## RENAMED Requirements

- FROM: `### Requirement: Deferred activity-gate legs are not evaluated`
- TO: `### Requirement: Activity gate evaluates the login-history legs`

## MODIFIED Requirements

### Requirement: Activity gate evaluates the login-history legs

With the durable login-history store now available (`login-history-tracking` ships `login_events`), the worker SHALL evaluate the five previously-deferred activity-gate legs — the two engagement legs and the three anti-collision legs of docs/01 §212–213 — against `login_events`, in addition to the already-implemented `≥ 2 posts` engagement leg, the 14-day expiry, and the inviter good-standing check. All `login_events` reads are keyed by `user_id` (the invitee's own counts; the inviter's own history) — security / engagement signals, not visibility-sensitive content reads.

**Engagement legs** (the invitee's own `login_events`, within the ticket window `[ticket.created_at, NOW()]`), which join the existing `≥ 2 posts` leg:

1. **≥ 3 distinct login-days** — the invitee has `login_events` on at least 3 distinct calendar days, day-bucketed in `Asia/Jakarta` (the app's market timezone), within the window.
2. **≥ 5 app sessions** — the invitee has at least 5 distinct app sessions in the window, where a session is a `login_events` row (`signin` or `refresh`) not preceded by another of the invitee's events within the prior 30 minutes (server-side idle-gap sessionization). This replaces the deferred, consent-gated client `session_start` signal as the source of the app-sessions count.

**Anti-collision legs** (the invitee's identity vs. the inviter's `login_events` history) — each MUST clear:

3. **Device-fingerprint history** — the invitee's `device_fingerprint_hash` does NOT appear among the inviter's `login_events` device-fingerprint hashes in the last 90 days (the historical, windowed check — distinct from and stronger than the point-in-time exact-equality `device_fingerprint_hash` check already applied at signup by `referral-ticket-creation`).
4. **IP /24 overlap** — the invitee's `ip_subnet_24` is NOT among the inviter's 10 most-recent distinct login subnets (the 10 newest distinct `ip_subnet_24` values in the inviter's `login_events`).
5. **Recently-seen identifier** — the invitee's `identifier_hash` does NOT appear on any `login_events` row, within the inviter's last 90 days, that shares one of the inviter's device-fingerprint hashes or `ip_subnet_24` subnets (the self-referral "second provider account on the inviter's own device / network" signal — realizing docs/01 §213's "inviter's recently-seen list").

**Gate semantics:**

- The engagement legs (`≥ 2 posts`, `≥ 3 login-days`, `≥ 5 sessions`) are *thresholds reached over time*: a ticket that is anti-collision-clean and inviter-in-good-standing but has not yet met ALL engagement thresholds SHALL remain `pending_activity` (re-evaluated on each daily run until it passes or the 14-day TTL expires) — the existing posts-leg behavior, extended to the two new legs.
- The anti-collision legs are *abuse signals*: a ticket that fails ANY anti-collision leg SHALL be set to the terminal `status = 'voided'` (the banned-inviter precedent — a distinct terminal status from a TTL `'expired'`, so analytics can separate an abuse-void from a 14-day lapse) with no grant. An anti-collision failure is permanent for that ticket — it does not become passable by waiting.
- A ticket passes the full activity gate (and proceeds to the grant flow) only when ALL legs hold: `≥ 2 posts` AND `≥ 3 login-days` AND `≥ 5 sessions` AND the inviter is neither hard-banned nor shadow-banned AND all three anti-collision legs clear.

#### Scenario: An invitee meeting every engagement and anti-collision leg passes
- **WHEN** the worker evaluates a non-expired ticket whose invitee has `≥ 2` posts, `≥ 3` distinct login-days, and `≥ 5` app sessions in the window, whose `device_fingerprint_hash`, `ip_subnet_24`, and `identifier_hash` do not collide with the inviter's history, and whose inviter is in good standing
- **THEN** the ticket passes the activity gate and proceeds to the grant flow

#### Scenario: An invitee below the login-days threshold stays pending
- **WHEN** the worker evaluates an anti-collision-clean ticket whose invitee has logged in on fewer than 3 distinct days in the window
- **THEN** the ticket's `status` remains `'pending_activity'` AND no grant is dispatched (it is re-evaluated on the next run)

#### Scenario: An invitee below the app-sessions threshold stays pending
- **WHEN** the worker evaluates an anti-collision-clean ticket whose invitee has fewer than 5 sessionized app sessions in the window (even if the login-days and posts legs are met)
- **THEN** the ticket's `status` remains `'pending_activity'` AND no grant is dispatched

#### Scenario: A device-fingerprint collision against the inviter's 90-day history voids the ticket
- **WHEN** the worker evaluates a ticket whose invitee's `device_fingerprint_hash` matches one of the inviter's `login_events` fingerprints within the last 90 days
- **THEN** the ticket's `status` becomes `'voided'` AND no grant is dispatched (the collision is terminal, not re-evaluated)

#### Scenario: An IP /24 overlap with the inviter's recent subnets voids the ticket
- **WHEN** the worker evaluates a ticket whose invitee's `ip_subnet_24` is among the inviter's 10 most-recent distinct login subnets
- **THEN** the ticket's `status` becomes `'voided'` AND no grant is dispatched

#### Scenario: A recently-seen-identifier collision voids the ticket
- **WHEN** the worker evaluates a ticket whose invitee's `identifier_hash` appears on a `login_events` row, within the inviter's last 90 days, sharing one of the inviter's device-fingerprint hashes or subnets
- **THEN** the ticket's `status` becomes `'voided'` AND no grant is dispatched

#### Scenario: An anti-collision void is distinct from a TTL expiry
- **WHEN** a ticket is voided for an anti-collision collision AND a separate ticket lapses past its 14-day `expires_at` without meeting the engagement legs
- **THEN** the first ticket's terminal `status` is `'voided'` AND the second's is `'expired'` (analytics can tell an abuse-void from a 14-day lapse)

#### Scenario: Exactly meeting every engagement threshold passes
- **WHEN** the worker evaluates an anti-collision-clean, good-standing ticket whose invitee has exactly 2 posts, exactly 3 distinct login-days, and exactly 5 app sessions in the window
- **THEN** the ticket passes the activity gate (the thresholds are inclusive lower bounds: `≥ 2`, `≥ 3`, `≥ 5`)

#### Scenario: Continuous activity within 30 minutes is one session; an idle gap starts a new one
- **WHEN** the worker counts the invitee's app sessions in the window
- **THEN** consecutive `login_events` ≤ 30 minutes apart count as the SAME session AND an event more than 30 minutes after the invitee's prior event starts a NEW session AND the invitee's first event in the window counts as a session (so events at 29-minute spacing are one session, while a > 30-minute gap yields a second)

#### Scenario: Login-days are bucketed in Asia/Jakarta, not UTC
- **WHEN** the invitee has two `login_events` on adjacent UTC dates but the same calendar day in `Asia/Jakarta` (e.g. 22:00 and 02:00 UTC straddling UTC midnight on the same WIB day)
- **THEN** they count as ONE distinct login-day (day-bucketing uses `(occurred_at AT TIME ZONE 'Asia/Jakarta')::date`), not two

#### Scenario: An anti-collision void takes precedence over an engagement shortfall
- **WHEN** the worker evaluates a ticket whose invitee both falls below an engagement threshold AND has an anti-collision collision with the inviter's history
- **THEN** the ticket's `status` becomes the terminal `'voided'` (the abuse signal wins; it does NOT stay `'pending_activity'`, so a later run cannot rescue it)

#### Scenario: An inviter with no relevant login history never false-voids a ticket
- **WHEN** the worker evaluates a ticket whose inviter has little or no `login_events` history, so none of the inviter's fingerprints, subnets, or recently-seen identifiers match the invitee
- **THEN** no anti-collision leg fires (each leg voids only on a POSITIVE match) AND the ticket is NOT voided on anti-collision grounds (it proceeds on its engagement legs)

#### Scenario: Only the inviter's 10 most-recent distinct subnets are consulted
- **WHEN** the invitee's `ip_subnet_24` matches a subnet that is NOT among the inviter's 10 most-recent distinct login subnets (it appears only as the inviter's 11th-most-recent-or-older distinct subnet)
- **THEN** the IP /24 leg does NOT void the ticket (the leg considers only the 10 newest distinct subnets)
