## ADDED Requirements

### Requirement: Session-history export is sourced from login_events with the IP included

The "session history" category of the personal-data export (the canonical Data Export Scope Matrix row "Session history (fingerprint, IP) — 90-day window only") SHALL be sourced from `login_events`, emitting — for each of the requester's own `login_events` rows whose `occurred_at` is within the last 90 days — a CSV record carrying `occurred_at`, `event_type`, `device_fingerprint_hash`, `ip`, and `ip_subnet_24`. This supersedes the prior best-effort emission (the `refresh_tokens`-sourced rows that omitted IP because that schema carried no IP column): the IP and /24 subnet are now included, fully satisfying the matrix row. The session-history read is the requester's own keyed read of their own login history (no peer references, so no peer-hashing applies); it carries the own-content lint annotations consistent with the other own-data export reads.

#### Scenario: Session-history export includes the IP from login_events
- **WHEN** a user with login history requests an export
- **THEN** the session-history CSV is sourced from `login_events` AND each row carries `occurred_at`, `event_type`, `device_fingerprint_hash`, `ip`, and `ip_subnet_24` for the user's own sessions (the IP is now present, no longer omitted)

#### Scenario: Session-history export is bounded to the 90-day window
- **WHEN** a user whose login history still contains rows older than 90 days (not yet purged) requests an export
- **THEN** only `login_events` rows within the last 90 days appear in the session-history CSV (matching the matrix's "90-day window only")
