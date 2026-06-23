## MODIFIED Requirements

### Requirement: Richer anti-collision checks are out of scope

The referral-ticket-creation capability SHALL implement only the `device_fingerprint_hash` collision check at signup time. The IP-subnet (/24) overlap check against the inviter's recent login subnets and the recently-seen Google/Apple identifier check (docs/01-Business.md § Bonus Release Criteria item 3) are NOT performed here — they are evaluated **later, by the activity-check worker** (`referral-grant-worker`), against the durable `login_events` history shipped by `login-history-tracking`. The signup path applies only the point-in-time `device_fingerprint_hash` exact-equality check; the windowed/historical anti-collision legs (90-day device-fingerprint history, IP /24 overlap, recently-seen identifier) are the worker's responsibility, not ticket creation's. This capability MUST NOT claim to perform the richer legs.

#### Scenario: IP-subnet and identifier-history checks are not applied at signup
- **WHEN** a signup-time referral is evaluated
- **THEN** only the `device_fingerprint_hash` collision check is applied for anti-abuse (alongside the burst-rate limit) AND the IP-subnet and recently-seen-identifier checks are not consulted (those run later in the activity-check worker)
