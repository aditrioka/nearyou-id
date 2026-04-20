# 07 - Operations: Admin Panel

Internal operational tooling for NearYouID. Covers admin panel stack, feature surface, multi-layer security model, and database access pattern. Related files: `04-Architecture.md` (infrastructure), `05-Implementation.md` (admin-related schemas), `06-Security-Privacy.md` (moderation policies), `08-Roadmap-Risk.md` (build phase 3.5 timeline).

---

## Admin Panel

### Stack

- Ktor server-side + Pebble/Freemarker + HTMX
- Module `admin-panel` with routes `/admin/*`
- Host: **separate subdomain** (`admin.nearyou.id`), NOT a path on the main service. Reduces attack surface discovery.
- SSL/TLS via Cloudflare managed (zone-wide)
- Build time estimate: 2-3 weeks for MVP

### Data Access Pattern

The admin Ktor service connects to Supabase Postgres using a dedicated, scoped DB role (`admin_app`) with a connection string in GCP Secret Manager (`admin-app-db-connection-string`), distinct from the main API's credentials. Properties:

- Row-level read/write access to operational tables (`users`, `posts`, `post_replies`, `reports`, `moderation_queue`, `admin_actions_log`, `csam_detection_archive`, etc.)
- No DDL (schema changes go through Flyway via the dedicated migration service account)
- No DELETE / UPDATE on `admin_actions_log` (immutable; enforced at the role level)
- Decrypt access on `csam_detection_archive.encrypted_metadata` via an admin-panel-only Ktor helper that pulls `csam-archive-aes-key` from GCP Secret Manager and audit-logs every decrypt

Direct SQL console access is never exposed to admin users; every administrative DB change happens through the Ktor admin module.

### Core Features

- **Report Queue**: reads from `reports` + joins `moderation_queue`. Filter by status/type, actions (Hide, Dismiss, Suspend, Ban, Shadow ban). Filter "post has edit history" to prioritize.
- **User Management**: search by username/ID hash, profile + history, actions (warning, suspend 7 days via `suspended_until`, ban, shadow ban, unban). Direct username editing is NOT available (see Premium Username Change Oversight section below for how username issues are handled).
- **Hard Delete Queue**: reads `deletion_requests` where scheduled hard-delete is imminent. Countdown 30 days, manual expedite, audit log
- **Data Export Queue**: async job trigger, download link via in-app notif + Resend email
- **Operational Dashboard**: DAU/MAU, posts/hour, signups/hour, reports/hour, top 10 active cities, error rate (Sentry widget), anomaly spike alert, DB size trend, subscription source breakdown (paid vs referral), Realtime cost per MAU, refresh token reuse detection log, attestation failure rate, **CSAM detection events**, **Amplitude funnel embed**, health check status, RevenueCat webhook signature fail count, email delivery rate (Resend), `rejected_identifiers` insert rate, age gate rejection rate
- **Moderation Actions Log**: immutable, retained 1 year, filter by admin/target/action. Reads `admin_actions_log`.
- **Post Edit History**: full access via post detail page (reads `post_edits`)
- **Shadow Ban**: flag `is_shadow_banned`, enforced via views
- **Block User Registry** (read-only, for dispute resolution): search block pairs in `user_blocks`
- **Referral Manual Grant Path**: for support tickets where the automated gate false-negatives a legitimate referral. Rate-limited, audit logged. Writes `granted_entitlements` with `source = 'manual_admin'` and `grant_role = 'manual_admin'`, so the grant does not count against the inviter's 5-referral lifetime track nor consume the single lifetime inviter reward; it is a support-desk remedy, not a referral-system action.
- **Attestation Fallback Review**: manual review queue for legitimate users whose attestation failed
- **CSAM Detection Log Viewer**:
  - Search + filter CF email-notified events archived in `csam_detection_archive`
  - **Admin-triggered handler invocation**: the primary MVP path for executing the downstream auto-action. Admin pastes the matched URL / image_id from the CF email notification; the Admin Panel calls `/internal/csam-webhook` internally using the admin's scoped session + a session-bound CSRF-style token. The handler runs the hard-delete post, permanent ban + token_version bump, cascade-delete of the user's other posts, metadata archival (AES-256-GCM encrypted), and Kominfo report queue. Audit-logged with full `before_state`/`after_state`.
  - Review unblock requests (CF-provided review path surfaced here)
  - File to Kominfo workflow (track `kominfo_report_id` on the archive row)
  - When the Phase 2+ Cloudflare Worker auto-forward path is enabled, this surface also shows Worker-triggered events alongside admin-triggered ones; filter by `source = 'cf_worker'` vs `'admin_manual'`.
- **Subscription Grace Monitor**: list of users in `subscription_status = 'premium_billing_retry'`, manual expedite option
- **Privacy Flip Monitor**: read-only list of users with `privacy_flip_scheduled_at IS NOT NULL AND NOW() < privacy_flip_scheduled_at` (i.e. currently in the 72h downgrade window). Useful for support tickets ("why is my profile still private / went public") and for spotting webhook handler bugs (mass scheduling events or stuck rows past the deadline).
- **Chat Message Redaction**: admin-triggered write of `redacted_at` + `redacted_by` (atomicity CHECK couples the two; CSRF-token verified; only `role IN ('owner', 'admin')` can invoke) + `redaction_reason` for severe violations (PII leak, doxxing). Audit logged with action type `admin_chat_redaction`. Affected conversation participants receive a `chat_message_redacted` notification. Client renders redacted messages as user-facing "Pesan ini telah dihapus oleh moderator."
- **Feature Flag Admin**: toggle Firebase Remote Config flags (`image_upload_enabled`, `attestation_mode`, `search_enabled`, `perspective_api_enabled`, `premium_username_customization_enabled`, `premium_like_cap_override`), audit logged. Also covers the content moderation keyword-list parameters (`moderation_profanity_list`, `moderation_uu_ite_list`, `moderation_match_threshold`).
- **Rejected Identifiers Viewer** (read-only, anti-abuse audit + manual clear path for legitimate adult re-verification on support request)
- **Reserved Usernames Editor**:
  - Paginated list view with filter by `source` (`seed_system` vs `admin_added`) and search by substring
  - Add single entry: input username + reason, source auto-set to `admin_added`
  - Bulk add via CSV upload (column: username, reason), duplicates skipped with a report
  - Edit `reason` field on existing `admin_added` rows (system seeds are read-only reason)
  - Remove: allowed only for rows with `source = 'admin_added'`; `seed_system` rows blocked at the UI AND at the DB trigger level (belt-and-suspenders)
  - All add/edit/remove operations write `admin_actions_log` rows with `action_type` in `{'reserved_username_added', 'reserved_username_edited', 'reserved_username_removed'}`
  - Rate limit: 100 add/edit/remove per hour per admin
- **Premium Username Change Oversight**:
  - Read-only viewer of `username_history` entries (filter by user, date range, search by old/new username)
  - Surfaces `moderation_queue` entries with `trigger = 'username_flagged'` (profanity/UU ITE candidate rejections) for admin awareness and anomaly pattern detection
  - Admin resolution actions write back to `moderation_queue.resolution`: `accept_flagged_username` (override allows candidate through on re-submit) or `reject_flagged_username` (confirms the automated block). Rate-limited 10/hour per admin; both write to `admin_actions_log`.
  - Manual release: force-release an old handle from the 30-day hold before `released_at` (edge case: impersonation complaint against a legitimately released handle); rate-limited 5/hour per admin, audit logged

### Security (Defense in Depth)

**Decision to make in Pre-Phase 1**: IAP vs Cloud Armor + VPN. Primary recommendation is IAP (free, Google-managed); fallback to Cloud Armor if workflow doesn't fit.

**Layer 1: Network (IAP primary)**:
- Deploy admin panel as a separate Cloud Run service with Identity-Aware Proxy enabled
- IAP = Google-managed auth layer, allowlist specific Google accounts
- Cost: free (IAP itself); does NOT strictly require Google Workspace (IAP can allowlist individual Gmail addresses, Cloud Identity users, or Google Groups)
- Zero-maintenance, strong auth

**Layer 1 fallback: Cloud Armor + VPN**:
- Cloud Armor WAF attached to Cloud Run
- Rules: GEO block allow only ID, deny other countries; rate limit 100 req/min per IP; OWASP rule set; bot signature block
- WireGuard/Tailscale VPN with static exit IP, Cloud Armor whitelist

**Layer 2: Application auth**:

- Admin identity stored in `admin_users`, `admin_webauthn_credentials`, `admin_webauthn_challenges` (5-min TTL ceremony state with consumed-guard against replay), `admin_sessions` (schemas in `05-Implementation.md`)
- **Session cookie**: `__Host-admin_session`, Secure/HttpOnly/SameSite=Strict/Path=/, opaque 256-bit random token (SHA256 at rest). Full mechanism + CSRF token flow in `05-Implementation.md`.
- **CSRF protection**: every state-changing request carries `X-CSRF-Token` header matching `admin_sessions.csrf_token_hash`; mismatched token returns 403 + audit log `admin_csrf_violation`
- **Solo admin period (Oka)**: email + Argon2id password + TOTP mandatory
- **Multi-admin period (mandatory before 2nd admin hire)**: WebAuthn (YubiKey / passkey) mandatory, TOTP backup only
  - TOTP is phishable via evilginx2 + a fake login page. Admins have destructive capability.
  - Implementation via `webauthn4j` library + WebAuthn JS API (~5 days of work: backend 1.5d + frontend 1d + enrollment UI 0.5d + recovery path 1d + cross-browser testing 1d)
- IP allowlist (VPN static IP for travel)
- Session timeout 30 minutes idle (enforced via `admin_sessions.last_active_at`)
- Rate limit destructive actions: 20/hour per admin
- Rate limit feature flag toggle: 5/hour per admin (high-impact action)
- All actions auditable to `admin_actions_log` (schema canonical in `05-Implementation.md`)
- Admin login audit: IP, user agent, session start/end
- Session cookie rotates on role escalation (old session revoked; admin re-authenticates)

### Admin Actions Log (Reference)

Full schema lives in `05-Implementation.md`. Summary for this file:

- Every destructive / high-impact admin operation writes an `admin_actions_log` row with `admin_id`, `action_type`, `target_type`, `target_id`, `reason`, `before_state`, `after_state`, `ip`, `user_agent`.
- Immutable at the DB role level (no UPDATE or DELETE allowed for the `admin_app` role).
- Retention 1 year minimum; queried in-app via the Moderation Actions Log UI.
