## Why

The referral activity-gate worker (`referral-grant-worker`, #353) shipped only the durable engagement leg (invitee ≥ 2 posts). Five anti-abuse legs from the canonical Bonus Release Criteria (docs/01 §212–213) were deferred because nearyou-id stores **no durable login / IP / device-fingerprint history** — refresh-token rotation deletes its trail, and the client `session_start` event is consent-gated analytics, unreliable as a security signal. The archived `referral-grant-worker` spec captures this as a negative-guard requirement ("Deferred activity-gate legs are not evaluated") that explicitly names *"a follow-up change that ships that tracking and MODIFIES this requirement to add them."* This is that change.

Until these legs run, referral Premium grants are exposed to self-referral farming (one person, multiple Google/Apple accounts, same device/network) bounded only by a point-in-time signup fingerprint check and a per-inviter burst limit. Closing that surface is a launch-blocking integrity requirement for the referral revenue loop.

## What Changes

- **New durable login-history store** — a `login_events` table (Flyway **V34**) recording each authenticated sign-in and refresh: `user_id`, `occurred_at`, `event_type`, `ip` (+ generated `ip_subnet_24`), `device_fingerprint_hash`, `identifier_hash`. Written best-effort from the sign-in and refresh **routes** (where `call.clientIp` and the request body live), via a thin `LoginEventRecorder`; `RefreshTokenService` is untouched.
- **UU-PDP posture (operator-confirmed)** — login-history is **essential security / anti-abuse data processed under legitimate-interest**, always collected for authenticated users and **exempt from the opt-in `analytics_consent.analytics` toggle** (it is not product analytics). This replaces the consent-gated `session_start` event as the source for the app-sessions leg — the original deferral blocker.
- **Activate the 5 deferred activity-gate legs** — flip the `referral-grant-worker` negative-guard requirement to positive: invitee ≥ 3 distinct login-days; ≥ 5 app sessions (server-side sessionization); and three anti-collision checks against the inviter's history (device-fingerprint over 90 days, IP /24 over the last 10 subnets, recently-seen identifier). An anti-collision hit **voids** the ticket; an engagement shortfall leaves it `pending_activity`.
- **PII lifecycle integration (the load-bearing part)** — wire `login_events` into the **retention sweep** (90-day auto-purge, the canonical "Session trail" window), the **data-export scope** (it now fully satisfies the matrix's "Session history (fingerprint, IP)" row, previously best-effort with no IP), and the **account hard-delete cascade** (explicit delete — the tombstoned user row never fires the FK cascade).
- **Doc reconciliation** — amend docs/01 §212, docs/05 §1231/§1233, and docs/06 (Analytics Consent, Data Export Scope Matrix note, Account Deletion cascade list, Retention Policy) to reflect the now-tracked, security-purpose source.

## Capabilities

### New Capabilities

- `login-history-tracking`: durable, append-only, security-purpose per-user record of authenticated sign-in / refresh events (time, IP + /24 subnet, device-fingerprint hash, provider-identifier hash); its schema, write path, consent posture, and 90-day retention.

### Modified Capabilities

- `referral-grant-worker`: the negative-guard requirement "Deferred activity-gate legs are not evaluated" is renamed and flipped to "Activity gate evaluates the login-history legs" — the worker now evaluates the two engagement legs and three anti-collision legs against `login_events`.
- `scheduled-retention-cleanup`: the `/internal/cleanup` worker gains a fourth sweep — the 90-day `login_events` purge — and its per-invocation summary reports four sweep counts.
- `account-data-export`: the Session-history export row is sourced from `login_events` (now includes IP + the /24 subnet + event type), fully satisfying the canonical Data Export Scope Matrix instead of the prior best-effort fingerprint-only emission.
- `account-hard-delete-worker`: the post-grace hard-delete cascade explicitly deletes the departing user's `login_events` rows.
- `referral-ticket-creation`: the "richer anti-collision checks are out of scope" requirement is reconciled — its now-stale claim that the IP-subnet and recently-seen-identifier legs "remain unimplemented by the worker / deferred" is corrected (they are now evaluated by the activity-check worker); the signup-time scope is unchanged (only the point-in-time fingerprint check runs at signup).

## Impact

- **Schema**: new `login_events` table (V34); no changes to existing tables.
- **Backend**: new `LoginEventRecorder` + `LoginEventRepository` (`auth` package); 2 edits in `AuthRoutes.kt` (sign-in + refresh write); 5 new gate methods + evaluation wiring in `ReferralGrantRepository` / `ReferralActivityCheckWorker`; one new sweep in `RetentionCleanupRepository` / `RetentionCleanupWorker`; session-history switch in `DataExportGatherRepository`; one new delete in `AccountHardDeleteWorker`.
- **Privacy / compliance**: a new PII data category fully integrated into retention, export, deletion, and the consent posture (UU-PDP). Getting this integration wrong is a worse compliance hole than not shipping the feature — it is the reason this is a standalone, separately-reviewed change.
- **APIs**: no public API surface change (the write is a side effect of existing `signin`/`refresh`; the legs run inside the existing internal worker). No mobile client change required.
- **Invariants**: touches the `clientIp` accessor rule, partial-index immutability, raw-`posts`/`users` read annotations, and the bounded DB dispatcher — all honored, none relaxed.
