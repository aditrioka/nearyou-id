## Why

The `login_events` table (V35, `login-history-tracking`) was built as the durable, security-purpose source of login time / IP / `/24` subnet / device-fingerprint history specifically to feed anti-abuse legs — but nothing yet consumes it for **anomaly detection**. `docs/05-Implementation.md` § Anomaly Detection Metrics calls out exactly this signal — "same `sub` issued from >5 geographic locations in 1h" — the classic "credentials shared / account takeover / bot farm" pattern (echoed on the roadmap at `docs/08-Roadmap-Risk.md` Phase 1 #29 "Anomaly detection metrics" + Phase 3.5 #19 "Anomaly detection dashboards"). The `moderation_queue` schema already **reserves** the `trigger = 'anomaly_detection'` value and a `target_type = 'user'` for exactly this, so the durable output surface exists; only the detector is missing. (The separate Phase 4 #17 "rolling 30-day baseline per user" is the *image-delivery* anomaly leg — out of scope here, see below.)

## What Changes

- Add a periodic, **OIDC-gated internal worker** — `POST /internal/login-anomaly-check` (Cloud Scheduler-driven) — that scans `login_events` for **per-user login-source spread**: a user with **strictly more than 5 distinct `ip_subnet_24` values within the trailing 1 hour** (operationalizing the docs/05 rule "same `sub` issued from >5 geographic locations in 1h"; the `/24` subnet is the coarse location proxy `login_events` actually stores).
- On detection, record a durable, admin-reviewable signal as a `moderation_queue` row (`target_type='user'`, `target_id=<user_id>`, `trigger='anomaly_detection'`, `status='pending'`, a **non-PII** `notes` summary), **idempotent** via the existing `UNIQUE(target_type, target_id, trigger)` (`ON CONFLICT DO NOTHING`) so repeated sweeps never duplicate a user's anomaly row.
- The sweep is **fail-soft per user** (one user's detection/insert error never aborts the rest) and **leaks no PII** (no IP / subnet / identifier-hash values in `notes` or any log line).
- **No Flyway migration**: both `login_events` and `moderation_queue` (incl. the `anomaly_detection` trigger + `user` target_type enum values) already exist — the footprint is pure `:backend:ktor` Kotlin, parallel-merge-safe against migration-bumping in-flight changes.
- **Scope is the subnet-spread leg only.** The other docs/05 § Anomaly Detection metrics (JWT-verify failure-rate spikes, >50 realtime channel subscriptions / 5 min, RevenueCat webhook signature-failure rate), the image-delivery >5× baseline / Phase 4 #17 rolling-30-day-baseline leg (image upload is feature-flag-gated off until Month 6), the deferred username-flagged anomaly-score increment (docs/05:295), the **Sentry + Slack alert *delivery*** that docs/05:30 frames this metric with (the durable `moderation_queue` row is the MVP surface; the alert hook is a deferred enhancement), and a **dedicated admin anomaly-review UI** are **explicitly deferred** (declared as requirements per `docs/12-Integration-Contracts.md` §3, each tracked by a `follow-up` issue) — not silently dropped.

## Capabilities

### New Capabilities

- `auth-login-anomaly-detection`: the periodic internal worker that detects per-user login-source-spread anomalies over `login_events` and records an idempotent, non-PII `moderation_queue` anomaly signal (`trigger='anomaly_detection'`, `target_type='user'`) for moderator review, fail-soft per user — plus the explicit deferral declarations for the other anomaly metrics and the admin review surface.

### Modified Capabilities

<!-- None. The signal lands in the existing moderation_queue (no requirement change to it); the admin review surface is declared-deferred WITHIN the new capability per docs/12 §3 (the operational-dashboard anomaly-spike-alert banner is already a tracked deferred follow-up in admin-operational-dashboard). No existing spec's behavior changes. -->

## Impact

- **Module**: `:backend:ktor` only — a new `id.nearyou.app.auth.anomaly` (or `admin/anomaly`) package: a detection Repository (windowed read over `login_events`), a sweep Service (idempotent `moderation_queue` insert), and an OIDC-gated worker Route mirroring `RetentionCleanupRoutes` / `PrivacyFlipWorker`.
- **APIs**: one new internal endpoint `POST /internal/login-anomaly-check` (OIDC-gated, never reachable by a user JWT).
- **Schema**: none (zero migration).
- **Ops**: a new Cloud Scheduler job hitting the worker (operator task; declared in Preflight). Reads `login_events` (DB-only `main_app` role), writes `moderation_queue`.
- **No mobile / no admin-UI surface** in this change (admin review UI declared-deferred).
