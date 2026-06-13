## Why

Premium username customization is a headline paid perk in the freemium model ([`docs/01-Business.md`](../../../docs/01-Business.md) § Freemium tiers), but it is **DESIGN-only today**: [`docs/02-Product.md`](../../../docs/02-Product.md) § Premium Username Customization and [`docs/05-Implementation.md`](../../../docs/05-Implementation.md) § Premium Customization Endpoint both state `PATCH /api/v1/user/username` + `GET /api/v1/username/check` "do NOT exist in `backend/ktor/`". The mobile-first phase is complete and the operator's chosen direction is **Phase 4 (premium/payment)**; the subscription billing webhook (the foundation that *writes* `users.subscription_status`) is in flight as [#291](https://github.com/aditrioka/nearyou-id/pull/291). This change ships the backend that lets a Premium user actually spend that entitlement on a custom handle — the next Phase 4 premium feature, and the one whose footprint is **migration-free** (the entire data model already exists since V2/V3), so it can land in parallel with the other in-flight Phase 4 work without rebase contention.

## What Changes

- **New `PATCH /api/v1/user/username`** (body `{ new_username }`) — change the authenticated user's username, gated by feature flag → Premium entitlement → 30-day cooldown, then run through the full validation pipeline, committed in one race-safe transaction.
- **New `GET /api/v1/username/check?candidate=<handle>`** — a non-authoritative availability probe (rate-limited 3/day) so a client can show live feedback; the authoritative uniqueness/reserved/release-hold check happens under a row lock at PATCH time.
- **Validation pipeline** on the candidate: length 3–30 (application-layer cap, stricter than the `VARCHAR(60)` schema ceiling), charset regex `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$`, application-layer no-consecutive-dots guard, `reserved_usernames` collision (incl. `source = 'admin_added'`), `username_history` 30-day-release-hold collision, and a profanity + UU ITE keyword check via the **existing** text-moderation pipeline.
- **Moderation-hit behavior**: the change is **REJECTED upfront** and a `moderation_queue` row with `trigger = 'username_flagged'` is inserted for admin awareness (authoritative per [`docs/06-Security-Privacy.md`](../../../docs/06-Security-Privacy.md) § Premium Username Moderation); >3 flagged attempts / 24h raises the user's anomaly score.
- **Successful-change transaction** (single tx, `SELECT … FOR UPDATE` on the `users` row): write the new username (+ `username_last_changed_at = NOW()`), write the old handle to `username_history` with `released_at = changed_at + 30 days` (anti-impersonation hold), and insert a `username_release_scheduled` notification (`body_data {old_username, released_at}`, an existing catalog type).
- **Rate limits** (Redis, via `computeTTLToNextReset` + `{scope:<value>}` hash-tag keys): 1 successful change / 30 days (the cooldown), 10 **failed** attempts / hour (anti-probing), 3 availability probes / day.
- **Downgrade-to-Free**: custom username **stays as last set** (no revert — a revert would break `@mentions`, notification references, and external links); further changes are disabled until re-subscribe, and the 30-day cooldown resumes from the last change (not reset by the subscription cycle).
- **Explicitly deferred** (separate follow-on changes, captured as guard requirements here — NOT built in this change): the mobile Settings UI (Premium entry, paywall, live probe, cooldown countdown, downgrade banner) and the Phase 3.5 admin username-change oversight (`username_history` viewer + borderline-candidate override + manual handle release).
- **No new migration, library, or module** — `users.username` / `users.username_last_changed_at` / `users.subscription_status` (V2) and `reserved_usernames` / `username_history` + its three indexes (V3) all pre-exist.

## Capabilities

### New Capabilities

- `premium-username-customization`: the Premium-gated username change endpoint, the availability-probe endpoint, the validation + moderation pipeline, the race-safe release-hold transaction, the feature-flag kill switch, the rate limits, and the downgrade/re-subscribe behavior.

### Modified Capabilities

<!-- None. This change reuses (does not change the requirements of) username-generation's collision-check logic, the text-moderation pipeline, rate-limit-infrastructure, and the in-app-notifications catalog (username_release_scheduled is an existing type). No existing spec's behavior changes. -->

## Impact

- **New backend code** (`:backend:ktor`): a `user`-package route + service + JDBC repository for the two endpoints; reuses `UsernameGenerator`'s reserved/release-hold collision-check logic, the text-moderation pipeline, and rate-limit-infrastructure.
- **Critical invariant touched**: the `users.username` write requires the username-write allowlist annotation (invariant #7, [`openspec/project.md`](../../../openspec/project.md) § Coding Conventions). Content-length guard (30-char app cap) and the rate-limit TTL / Redis hash-tag invariants also apply.
- **Schema**: none (read/write of pre-existing tables only). **No Flyway migration.**
- **APIs**: two new routes under the authenticated `/api/v1` tree. No existing endpoint changes.
- **In-flight adjacency** (read-side, no file/migration collision): reads `reserved_usernames` (admin CRUD in flight as [#294](https://github.com/aditrioka/nearyou-id/pull/294)); gated by the `premium_username_customization_enabled` flag (generic flag editor in flight as [#297](https://github.com/aditrioka/nearyou-id/pull/297)); reads `users.subscription_status` (first writer in flight as [#291](https://github.com/aditrioka/nearyou-id/pull/291) — column pre-exists, so no dependency).
