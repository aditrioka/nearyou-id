# 07 - Operations: Admin Panel

Internal operational tooling for NearYouID: admin panel stack, feature surface, multi-layer security model, database access pattern. Related: `04-Architecture.md` (infrastructure), `05-Implementation.md` (admin-related schemas), `06-Security-Privacy.md` (moderation policies), `08-Roadmap-Risk.md` (build phase 3.5 timeline).

> **Status (2026-06-06).** The Admin Panel is **PARTIALLY BUILT**. The `:backend:ktor` `admin` package mounts `/admin/*` (Pebble templating + HTMX client + vendored static assets + shared base layout). Shipped:
>
> - **Admin #3 `admin-login-argon2-totp`** — Argon2id + TOTP login → `__Host-admin_session` cookie + per-session CSRF gate; lifted the Admin #2 `KTOR_ENV != "production"` mount guard, so `/admin/*` is session-middleware-gated in all environments.
> - **Admin #4 `admin-actions-log-viewer`** — read-only `admin_actions_log` audit-trail viewer.
> - **Admin #5 `admin-suspend-unban-user-action`** — the panel's FIRST state-changing action: server-fixed 7-day suspend + manual unban under `/admin/users`; role-gated (`owner`/`admin`/`moderator`; permanent-ban unban `owner`/`admin` only), CSRF-gated, one immutable `admin_actions_log` row per applied action (atomic); suspend inserts a sanitized `account_action_applied` notification.
> - **Admin #6 `admin-report-queue`** + its in-row write-back **`admin-report-queue-resolution-actions`**, and the read-only **`admin-rejected-identifiers-viewer`** — full shipped surfaces in § Core Features; the rejected-identifiers manual-clear half is deferred to `admin-rejected-identifiers-clear-action`.
>
> Admin schema (V16 `admin-schema-bootstrap`, PR #107) is shipped; the lint invariants (`csrf_token_hash` required, admin-FK `ON DELETE SET NULL`) ARE Detekt-enforced. **What remains DESIGN**: `admin.nearyou.id` separate-Cloud-Run-service + IAP/Cloud Armor deployment (Phase 3.5 deployment task #2); a per-admin destructive-action rate limiter (deferred per `docs/08-Roadmap-Risk.md` § Pre-Launch + the Admin #5 design D11); the remaining § Core Features business features (full User Management search/profile/history page, Operational Dashboard, etc.) — those per-feature blocks stay forward-looking until each lands in its own change.
>
> The § Deployment Runbook and § Secret Management Runbook sections below ARE shipped — battle-tested against real Cloud Run incidents (PR #54) — and remain canonical.

---

## Admin Panel — DESIGN

### Stack

- Ktor server-side + Pebble/Freemarker + HTMX
- Module `admin-panel` with routes `/admin/*`
- Host: **separate subdomain** (`admin.nearyou.id`), NOT a path on the main service — reduces attack surface discovery
- SSL/TLS via Cloudflare managed (zone-wide)
- Build time estimate: 2-3 weeks for MVP

### Data Access Pattern

The admin Ktor service connects to Supabase Postgres as a dedicated scoped DB role (`admin_app`), connection string in GCP Secret Manager (`admin-app-db-connection-string`), distinct from the main API's credentials:

- Row-level read/write on operational tables (`users`, `posts`, `post_replies`, `reports`, `moderation_queue`, `admin_actions_log`, `csam_detection_archive`, etc.)
- No DDL (schema changes only via Flyway through the dedicated migration service account)
- No DELETE / UPDATE on `admin_actions_log` (immutable; role-enforced)
- Decrypt on `csam_detection_archive.encrypted_metadata` only via an admin-panel-only Ktor helper that pulls `csam-archive-aes-key` from GCP Secret Manager and audit-logs every decrypt

Direct SQL console access is never exposed to admin users — every administrative DB change goes through the Ktor admin module.

**Role provisioning (operational, not Flyway-managed).** The `admin_app` per-table grants + `REVOKE UPDATE, DELETE ON admin_actions_log` are applied out-of-band: Supabase is the canonical surface for role permissions, and a Flyway `REVOKE ... FROM admin_app` would fail the integration-test Postgres (no `admin_app` there). Provision via the idempotent [`dev/scripts/provision-admin-app-staging.sh`](../dev/scripts/provision-admin-app-staging.sh) — Cloud Run Job; enumerated per-table grants on base tables + views; role is `LOGIN`; no `ALTER DEFAULT PRIVILEGES`; no `BYPASSRLS`. **Staging:** done 2026-05-17 (PR [#109](https://github.com/aditrioka/nearyou-id/pull/109)); connection string at `staging-admin-app-db-connection-string` + 3 companion slots, all granted `secretAccessor` to the Cloud Run runtime SA. **Production:** same script with `PROJECT_OVERRIDE=nearyou-production` + slot overrides; create the prod password slot first (it fail-fasts if absent). Precondition for any production admin code path writing `admin_actions_log`.

### Core Features

- **Report Queue** — **PARTIALLY SHIPPED**. Triage viewer (Admin #6 `admin-report-queue`): `GET /admin/reports` — `reports` plus one representative `moderation_queue` row (`LEFT JOIN LATERAL`); keyset-paginated newest-first; composable parameterized filters (status / target_type / reason_category / trigger / `from`–`to` date range); HTML-escaped HTMX render + plain-`GET` fallback; per-row deep-link to the `/admin/users` suspend/unban surface (offending user resolved by `target_type`). In-row resolution (SHIPPED, `admin-report-queue-resolution-actions`): `POST /admin/reports/{id}/resolve` (decision `actioned`/`dismissed` — bookkeeping) + `POST /admin/moderation-queue/{id}/resolve` applying the named enforcement atomically — **Hide/Dismiss** = `is_auto_hidden` toggle on post/reply; **Suspend** = the shipped 7-day suspend; **Ban** = permanent, owner/admin tier only; **Shadow ban** = `is_shadow_banned`, no user notification (stealth invariant) — CSRF + role-gated, one immutable `admin_actions_log` row per action, idempotent re-resolution. **Still DESIGN**: the "post has edit history" prioritization filter ([#191](https://github.com/aditrioka/nearyou-id/issues/191) `admin-report-queue-has-edit-history-filter`, label `follow-up`).
- **User Management**: search by username/ID hash, profile + history, actions (warning, suspend 7 days via `suspended_until`, ban, shadow ban, unban). No direct username editing (handled per Premium Username Change Oversight below).
- **Hard Delete Queue**: reads `deletion_requests` with imminent scheduled hard-delete; 30-day countdown, manual expedite, audit log
- **Data Export Queue**: async job trigger, download link via in-app notif + Resend email
- **Operational Dashboard**: DAU/MAU, posts/hour, signups/hour, reports/hour, top 10 active cities, error rate (Sentry widget), anomaly spike alert, DB size trend, subscription source breakdown (paid vs referral), Realtime cost per MAU, refresh token reuse detection log, attestation failure rate, **CSAM detection events**, **Amplitude funnel embed**, health check status, RevenueCat webhook signature fail count, email delivery rate (Resend), `rejected_identifiers` insert rate, age gate rejection rate
- **Moderation Actions Log**: immutable, retained 1 year, filter by admin/target/action. Reads `admin_actions_log`.
- **Post Edit History**: full access via post detail page (reads `post_edits`)
- **Shadow Ban**: flag `is_shadow_banned`, enforced via views
- **Block User Registry** (read-only, for dispute resolution) — **SHIPPED** (`admin-block-registry`, mockup frame 12): `GET /admin/blocks` — search block pairs in `user_blocks` by username or user ID on either side; keyset-paginated newest-first over `(created_at, blocker_id, blocked_id)`; per-row bidirectional-block indicator (`EXISTS` reverse-pair); usernames deep-link to the `/admin/users` lookup; HTML-escaped HTMX render + plain-`GET` fallback; any authenticated admin role; strictly read-only (no create/remove block, no `admin_actions_log` write, no notification — enforcement stays in the product path via the bidirectional NOT-IN join). A `(created_at DESC, …)` keyset index is deferred until cardinality warrants it ([follow-up](https://github.com/aditrioka/nearyou-id/issues?q=label%3Afollow-up)).
- **Referral Manual Grant Path**: for support tickets where the automated gate false-negatives a legitimate referral; rate-limited, audit logged. Writes `granted_entitlements` with `source = 'manual_admin'` + `grant_role = 'manual_admin'` — counts against neither the inviter's 5-referral lifetime track nor the single lifetime inviter reward (support-desk remedy, not a referral-system action).
- **Attestation Fallback Review**: manual review queue for legitimate users whose attestation failed
- **CSAM Detection Log Viewer**:
  - Search + filter CF email-notified events archived in `csam_detection_archive`
  - **Admin-triggered handler invocation** — primary MVP path for the downstream auto-action: admin pastes the matched URL / image_id from the CF email notification; the panel calls `/internal/csam-webhook` internally with the admin's scoped session + a session-bound CSRF-style token. Handler: hard-delete post, permanent ban + token_version bump, cascade-delete of the user's other posts, encrypted metadata archival (AES-256-GCM), Kominfo report queue. Audit-logged with full `before_state`/`after_state`.
  - Review unblock requests (CF-provided review path surfaced here)
  - File-to-Kominfo workflow (track `kominfo_report_id` on the archive row)
  - Once the Phase 2+ Cloudflare Worker auto-forward path is enabled, Worker-triggered events appear alongside admin-triggered ones; filter `source = 'cf_worker'` vs `'admin_manual'`
- **Subscription Grace Monitor**: users in `subscription_status = 'premium_billing_retry'`, manual expedite option
- **Privacy Flip Monitor** — **SHIPPED** (`admin-privacy-flip-monitor`, frame 17): `GET /admin/privacy-flips` — read-only list of users with `privacy_flip_scheduled_at IS NOT NULL`, classified per row as IN_WINDOW (`eval_instant < privacy_flip_scheduled_at` — the 72h downgrade window) or OVERDUE (`privacy_flip_scheduled_at <= eval_instant` — past deadline but uncleared; a stuck-row / webhook-handler-bug signal surfaced beyond the canonical in-window predicate). Ascending keyset pagination over `(privacy_flip_scheduled_at, id)`; composable `status` (in_window/overdue) + `q` (UUID / exact username) filters; in-window-vs-overdue count summary (the OVERDUE total is the anomaly signal); index-served by the existing V2 partial index `users_privacy_flip_idx` (no migration); any authenticated admin role; HTML-escaped HTMX render + plain-`GET` fallback; identity-only PII (username deep-links to the `/admin/users?q=` lookup). For support tickets ("why is my profile still private / went public") and spotting webhook handler bugs (mass scheduling events, stuck rows past the deadline). Strictly read-only — no clear/expedite write; anomalies are escalated to an out-of-band worker fix. An in-panel expedite/clear-stuck-flip write action is a DESIGN follow-up.
- **Chat Message Redaction**: admin-triggered write of `redacted_at` + `redacted_by` (atomicity CHECK couples the two; CSRF-token verified; `role IN ('owner', 'admin')` only) + `redaction_reason`, for severe violations (PII leak, doxxing). Audit-logged as `admin_chat_redaction`; affected conversation participants receive a `chat_message_redacted` notification; client renders redacted messages as user-facing "Pesan ini telah dihapus oleh moderator."
- **Feature Flag Admin**: toggle Firebase Remote Config flags (`image_upload_enabled`, `attestation_mode`, `search_enabled`, `perspective_api_enabled`, `premium_username_customization_enabled`, `premium_like_cap_override`) + the content-moderation keyword-list parameters (`moderation_profanity_list`, `moderation_uu_ite_list`, `moderation_match_threshold`); audit logged.
- **Rejected Identifiers Viewer** — read-only anti-abuse audit/triage half **SHIPPED** (`admin-rejected-identifiers-viewer`, PR #156): `GET /admin/rejected-identifiers` — keyset-paginated newest-first over `(rejected_at, id)`; composable `reason` / `identifier_type` / UTC-date filters; per-reason/per-type count summary (rejection-spike detection); hash-only PII discipline (surfaces only the one-way `identifier_hash`); any authenticated admin role. The **manual clear path** (remove a row for legitimate adult re-verification on support request) remains DESIGN — deferred to the fast-follow `admin-rejected-identifiers-clear-action` (role-gated + CSRF-gated + audit-logged + rate-limited; [#190](https://github.com/aditrioka/nearyou-id/issues/190), label `follow-up`); until it ships, clearing stays the out-of-band raw-SQL path.
- **Reserved Usernames Editor**:
  - Paginated list; filter by `source` (`seed_system` vs `admin_added`), search by substring
  - Add single entry (username + reason; source auto-set `admin_added`)
  - Bulk add via CSV upload (columns: username, reason); duplicates skipped with a report
  - Edit `reason` on `admin_added` rows (system-seed reason is read-only)
  - Remove only rows with `source = 'admin_added'`; `seed_system` rows blocked at the UI AND the DB trigger (belt-and-suspenders)
  - Every add/edit/remove writes an `admin_actions_log` row with `action_type` in `{'reserved_username_added', 'reserved_username_edited', 'reserved_username_removed'}`
  - Rate limit: 100 add/edit/remove per hour per admin
- **Premium Username Change Oversight**:
  - Read-only `username_history` viewer (filter by user, date range; search by old/new username)
  - Surfaces `moderation_queue` entries with `trigger = 'username_flagged'` (profanity/UU ITE candidate rejections) for admin awareness + anomaly pattern detection
  - Resolution actions write back to `moderation_queue.resolution`: `accept_flagged_username` (override — candidate passes on re-submit) or `reject_flagged_username` (confirms the automated block); rate-limited 10/hour per admin; both write `admin_actions_log`
  - Manual release: force-release an old handle from the 30-day hold before `released_at` (edge case: impersonation complaint against a legitimately released handle); rate-limited 5/hour per admin, audit logged

### Security (Defense in Depth)

**Decision to make in Pre-Phase 1**: IAP vs Cloud Armor + VPN. Primary recommendation is IAP (free, Google-managed); fall back to Cloud Armor if the workflow doesn't fit.

**Layer 1: Network (IAP primary)**:
- Separate Cloud Run service with Identity-Aware Proxy enabled
- IAP = Google-managed auth layer, allowlist specific Google accounts
- Free (IAP itself); Google Workspace NOT strictly required — IAP can allowlist individual Gmail addresses, Cloud Identity users, or Google Groups
- Zero-maintenance, strong auth

**Layer 1 fallback: Cloud Armor + VPN**:
- Cloud Armor WAF attached to Cloud Run
- Rules: GEO block allow only ID, deny other countries; rate limit 100 req/min per IP; OWASP rule set; bot signature block
- WireGuard/Tailscale VPN with static exit IP, Cloud Armor whitelist

**Layer 2: Application auth**:

- Admin identity in `admin_users`, `admin_webauthn_credentials`, `admin_webauthn_challenges` (5-min TTL ceremony state, consumed-guard against replay), `admin_sessions` — schemas in `05-Implementation.md`
- **Session cookie**: `__Host-admin_session`, Secure/HttpOnly/SameSite=Strict/Path=/, opaque 256-bit random token (SHA256 at rest); full mechanism + CSRF token flow in `05-Implementation.md`
- **CSRF protection**: every state-changing request carries an `X-CSRF-Token` header matching `admin_sessions.csrf_token_hash`; mismatch returns 403 + audit log `admin_csrf_violation`
- **Solo admin period (Oka)**: email + Argon2id password + TOTP mandatory
- **Multi-admin period (mandatory before 2nd admin hire)**: WebAuthn (YubiKey / passkey) mandatory, TOTP backup only
  - Rationale: TOTP is phishable (evilginx2 + fake login page); admins have destructive capability
  - `webauthn4j` library + WebAuthn JS API (~5 days: backend 1.5d, frontend 1d, enrollment UI 0.5d, recovery path 1d, cross-browser testing 1d)
- IP allowlist (VPN static IP for travel)
- Session timeout 30 minutes idle (enforced via `admin_sessions.last_active_at`)
- Rate limit destructive actions: 20/hour per admin
- Rate limit feature flag toggle: 5/hour per admin (high-impact action)
- All actions auditable to `admin_actions_log` (schema canonical in `05-Implementation.md`)
- Admin login audit: IP, user agent, session start/end
- Session cookie rotates on role escalation (old session revoked; admin re-authenticates)

### Admin Actions Log (Reference)

Full schema lives in `05-Implementation.md`. Summary:

- Every destructive / high-impact admin operation writes an `admin_actions_log` row: `admin_id`, `action_type`, `target_type`, `target_id`, `reason`, `before_state`, `after_state`, `ip`, `user_agent`.
- Immutable at the DB role level (no UPDATE or DELETE for `admin_app`).
- Retention 1 year minimum; queried in-app via the Moderation Actions Log UI.

---

## Deployment Runbook

### Recovery from a failed-revision sequence on Cloud Run

After a sequence of revisions fails the startup probe (e.g., a `--update-secrets` deploy with a wrong slot, then a follow-up fix), Cloud Run traffic routing can stay **pinned** to the last-known-good revision instead of tracking `LATEST` — later successful deploys create revisions that serve no traffic until released.

**Symptom**: a deploy succeeds (image build OK, revision created, startup probe green) yet serves NO traffic; `gcloud run services describe <service>` lists the new revision while the `traffic` field still routes 100% to an older one.

**Recovery (one command)**:

```bash
gcloud run services update-traffic <service> \
    --region=<region> \
    --to-latest
```

Precedent: `health-check-endpoints` (PR #54) § 11.5 negative-smoke hit this on the broken-Redis revision sequence; recovery from `00049-bsx` to `00053-n6v` required the `--to-latest` release.

---

## Secret Management Runbook

### Creating a new GCP Secret Manager slot

A new slot does NOT inherit sibling slots' IAM bindings: the Cloud Run runtime service account needs an explicit `roles/secretmanager.secretAccessor` grant, or the next deploy fails with `Permission denied on secret: projects/.../secrets/<slot>/versions/latest for Revision service account <sa>@developer.gserviceaccount.com`.

**Procedure**:

```bash
# 1. Create the slot
gcloud secrets create <slot> --project=<project>

# 2. Add the value (or pipe via --data-file=-)
echo -n "<value>" | gcloud secrets versions add <slot> \
    --project=<project> \
    --data-file=-

# 3. Grant the Cloud Run runtime SA secretAccessor on the new slot
gcloud secrets add-iam-policy-binding <slot> \
    --project=<project> \
    --member="serviceAccount:<runtime-sa>" \
    --role=roles/secretmanager.secretAccessor
```

**Runtime service accounts** (filled per environment as provisioned):
- staging (`nearyou-staging` project): `27815942904-compute@developer.gserviceaccount.com` (default Cloud Run runtime SA).
- production (`nearyou-production` project): to be filled in after the first production tag-deploy provisions the project.

Rotating an existing slot's value needs no extra step — IAM is bound per-slot; only new-slot creation hits this. Precedent: `health-check-endpoints` (PR #54) task 11.1 surfaced the gap when adding the `staging-supabase-url` slot.

## Moderation Runbook

### Updating the keyword wordlists

The text-moderation pipeline (per [`openspec/specs/content-moderation-keyword-lists/spec.md`](../openspec/specs/content-moderation-keyword-lists/spec.md)) reads two operator-managed lists from Firebase Remote Config:

- `moderation_profanity_list` — Layer 1 profanity blocklist (sync REJECT 4xx).
- `moderation_uu_ite_list` — Layer 2 UU ITE wordlist (sync soft-flag → `moderation_queue` row).
- `moderation_match_threshold` — distinct-match count before Layer 2 flags (default 3, runtime-tunable, clamped to `[1, 10000]`).

**Update procedure** (production wordlist edit):

1. Open the Firebase Console for the relevant environment (staging / production) → Remote Config → Parameters.
2. **Switch to the Server template** (Client / Server selector in the dropdown next to the "Parameters / Conditions" tabs) — the backend reads via `getServerTemplate()`; Client-template parameters are NOT visible to it.
3. Edit `moderation_profanity_list` (or `moderation_uu_ite_list`) — a JSON array of strings, e.g., `["badword1","badword2"]`. Save + Publish.
4. After the 5-min Redis cache TTL elapses, the next moderator call refreshes from Remote Config and emits the new list.
5. Verify: post a test sentinel keyword via the relevant API → expect 400 (`content_moderated_profanity`) or 201 + `moderation_queue` row, per layer.

**Important:** the quarterly UU ITE legal-advisor review per [`docs/06-Security-Privacy.md`](06-Security-Privacy.md) updates BOTH the Remote Config parameter AND the repo-committed [`backend/ktor/src/main/resources/moderation/uu_ite.default.txt`](../backend/ktor/src/main/resources/moderation/uu_ite.default.txt), so a Remote Config outage falls back to a recent-and-vetted list via Tier 3 of the loader cascade. Same for the profanity list + [`backend/ktor/src/main/resources/moderation/profanity.default.txt`](../backend/ktor/src/main/resources/moderation/profanity.default.txt).

**Tier 4 (`content-moderation-fallback-list` Secret Manager slot)** — last-resort safety net: a JSON document `{"profanity":[...],"uu_ite":[...]}` for the catastrophic case where both Remote Config AND the repo file are unavailable. Update via the standard "Creating a new GCP Secret Manager slot" / existing-slot rotation procedure above.

### Boot-time integrity prime

On `Application.module()` startup the loader fires `load(ProfanityList)` + `load(UuIteList)` once each in a non-blocking coroutine, priming the Redis cache and verifying Tier 3 integrity pre-traffic. A missing or corrupt repo resource surfaces a Sentry WARN with `event=moderation_list_fallback tier=repo_file to=secret_manager` BEFORE the first user-content-write request, rather than degrading the first user's submission.
