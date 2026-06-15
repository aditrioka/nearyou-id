## Why

The RevenueCat billing webhook (`revenuecat-subscription-webhook`, [PR #291](https://github.com/aditrioka/nearyou-id/pull/291)) sets `users.subscription_status = 'premium_billing_retry'` when a payment fails, holding the user in a 7-day grace window with premium access still active. There is no admin surface over that population — billing-retry triage is currently a raw-SQL exercise. Support-desk operators need to (a) see who is in the billing-retry window when a user complains about their premium status, and (b) record a manual "expedite resolution" against a support ticket (e.g. "user already paid but the webhook is late"). docs/07 § Core Features and admin mockup frame 18 both call for this surface; it is the operational-visibility counterpart to the now-shipped webhook.

## What Changes

- **New admin read surface** `GET /admin/subscriptions/grace` — a keyset-paginated, filterable table of users currently in `subscription_status = 'premium_billing_retry'`, each row showing the store/platform, the retry-since timestamp, the latest RevenueCat webhook event, and whether the row was already manually expedited. The 5th instance of the established read-only-admin-viewer pattern (mirrors `admin-privacy-flip-monitor`, `admin-rejected-identifiers-viewer`, `admin-block-registry`).
- **New admin write action** `POST /admin/subscriptions/grace/{user_id}/expedite` — a support-desk **bookkeeping** action. Per the canonical mockup frame 18 banner, expedite is **NOT** a free-premium grant and **NOT** a downgrade: it does **not** mutate `users.subscription_status` or any entitlement (RevenueCat remains the entitlement source of truth). It writes exactly one immutable `admin_actions_log` row (`action_type = 'subscription_grace_expedite'`) capturing the acting admin, target user, a required support-ticket reference + reason, and a before/after snapshot in which `subscription_status` is unchanged.
- **Write-action guards**: CSRF-token verified (`X-CSRF-Token` vs `admin_sessions.csrf_token_hash`; mismatch → 403 + `admin_csrf_violation` audit), role-gated to `owner`/`admin` (read remains open to any authenticated admin role), and **rate-limited at 20 expedites/hour per admin**. The rate limit **reuses the `admin-destructive-action-rate-limit` mechanism** (the immutable `admin_actions_log` IS the trailing-hour ledger; soft cap counted in-transaction; rejection surfaced as an inline "quota exceeded" state, never a 5xx) as a **distinct counter** keyed on `action_type = 'subscription_grace_expedite'` — expedite is non-punitive bookkeeping and is deliberately **outside** the destructive set, so it does not consume or modify the existing 20/hr destructive budget.
- **No database migration.** Reads existing schema only: `users.subscription_status` + the `users_subscription_idx` partial index (both V2), `subscription_events` (V21), and `admin_actions_log` (V16 — `action_type` is `VARCHAR(64)` with no CHECK, so the new action type adds no schema change).
- **No new notification.** The user-facing `subscription_billing_issue` notification already exists in the V10 catalog and is emitted by the webhook; this surface is admin-only.

## Capabilities

### New Capabilities
- `admin-subscription-grace-monitor`: the authenticated admin surface listing `premium_billing_retry` users (read) plus the audit-logged, CSRF-/role-/rate-limit-gated manual-expedite bookkeeping write action.

### Modified Capabilities
<!-- None. The expedite write reuses the admin-destructive-action-rate-limit MECHANISM via a distinct, separately-counted action type; it does not change that capability's destructive-set requirements, so no delta spec is needed. -->

## Impact

- **Code**: `:backend:ktor` `admin` package only — a new route group (`Application.adminSubscriptionGrace()` extension or equivalent), a read query + an expedite write service, Pebble templates + HTMX partial, and an admin-nav entry. No `:infra:*`, no `:mobile:app`, no `:shared:*`, no `:core:*` changes.
- **Schema / migrations**: none (reads V2 / V21 / V16 surfaces; no new Flyway version → disjoint from any in-flight migration-bearing change).
- **APIs**: two new admin-only HTTP routes under `/admin/subscriptions/grace`. No public `/api/v1/*` change.
- **Data access**: admin-module raw read of `users` + `subscription_events` (exempt from the `visible_*` view + block-join lint rules per the established admin-monitor precedent; annotated accordingly). One append-only `admin_actions_log` INSERT per expedite.
- **Dependencies**: none added to `gradle/libs.versions.toml` (no substrate change).
- **Docs**: admin mockup frame 18 flips from "Usulan" (proposed) to shipped; docs/07 § Subscription Grace Monitor reflected as PARTIALLY/SHIPPED on archive.
