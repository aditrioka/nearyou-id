# 07 - Operations: Admin Panel

Internal operational tooling for NearYouID. Covers admin panel stack, feature surface, multi-layer security model, and database access pattern. Related files: `04-Architecture.md` (infrastructure), `05-Implementation.md` (admin-related schemas), `06-Security-Privacy.md` (moderation policies), `08-Roadmap-Risk.md` (build phase 3.5 timeline).

> **Status (2026-05-07).** The Admin Panel is **DESIGN** — Phase 3.5 deferred-to-post-MVP per `08-Roadmap-Risk.md`. The `:backend:ktor` `admin` package contains only `SuspensionUnbanWorker.kt` + `UnbanWorkerRoute.kt` (~189 LOC); there are no `/admin/*` routes, no admin UI, no Pebble templates. Admin schema (`admin_users`, `admin_sessions` + CSRF, `admin_actions_log`, `admin_webauthn_*`) is partly in V2/V9/scattered migrations and the lint invariants (`csrf_token_hash` required, admin-FK `ON DELETE SET NULL`) ARE Detekt-enforced — the data model is partially ready, but no consuming service code exists. Treat the entire § Admin Panel section below as forward-looking design until Phase 3.5 work begins.
>
> The § Deployment Runbook and § Secret Management Runbook sections at the bottom of this file ARE shipped — both are battle-tested against real Cloud Run incidents (PR #54). Those runbooks should remain canonical.

---

## Admin Panel — DESIGN

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

---

## Deployment Runbook

### Recovery from a failed-revision sequence on Cloud Run

When a sequence of Cloud Run revisions fails the startup probe (e.g., a `--update-secrets` deploy with a wrong slot, then a follow-up fix), Cloud Run's traffic-routing config can become **pinned** to the last-known-good revision rather than tracking `LATEST`. Subsequent successful deploys create new revisions but traffic stays on the pinned revision until explicitly released.

**Symptom**: a successful deploy (image build OK, revision created OK, startup probe green on the new revision) does NOT serve any traffic. `gcloud run services describe <service>` shows the new revision in the revision list, but the `traffic` field still routes 100% to an older revision.

**Recovery (one command)**:

```bash
gcloud run services update-traffic <service> \
    --region=<region> \
    --to-latest
```

Precedent: `health-check-endpoints` (PR #54) § 11.5 negative-smoke surfaced this failure mode while exercising the broken-Redis revision sequence; recovery from `00049-bsx` to `00053-n6v` required the `--to-latest` release.

---

## Secret Management Runbook

### Creating a new GCP Secret Manager slot

When a new slot is added to a GCP Secret Manager project, the Cloud Run runtime service account does NOT automatically inherit the IAM bindings of sibling slots. The new slot requires an explicit `roles/secretmanager.secretAccessor` grant. Without it, the next deploy fails with `Permission denied on secret: projects/.../secrets/<slot>/versions/latest for Revision service account <sa>@developer.gserviceaccount.com`.

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

**Runtime service accounts** (filled per environment as it is provisioned):
- staging (`nearyou-staging` project): `27815942904-compute@developer.gserviceaccount.com` (default Cloud Run runtime SA).
- production (`nearyou-production` project): to be filled in after first production tag-deploy provisions the project.

Existing rotation procedures (rotating the value on an existing slot) do NOT need this step — IAM is bound per-slot, so only new-slot creation hits this. Precedent: `health-check-endpoints` (PR #54) task 11.1 surfaced this gap when adding the `staging-supabase-url` slot.

## Moderation Runbook

### Updating the keyword wordlists

The text-moderation pipeline (per [`openspec/specs/content-moderation-keyword-lists/spec.md`](../openspec/specs/content-moderation-keyword-lists/spec.md)) reads two operator-managed lists from Firebase Remote Config:

- `moderation_profanity_list` — Layer 1 profanity blocklist (sync REJECT 4xx).
- `moderation_uu_ite_list` — Layer 2 UU ITE wordlist (sync soft-flag → `moderation_queue` row).
- `moderation_match_threshold` — distinct-match count required before Layer 2 flags (default 3, runtime-tunable, clamped to `[1, 10000]`).

**Update procedure** (production wordlist edit):

1. Open the Firebase Console for the relevant environment (staging / production).
2. Navigate to Remote Config → Parameters.
3. Edit `moderation_profanity_list` (or `moderation_uu_ite_list`) — value is a JSON array of strings, e.g., `["badword1","badword2"]`. Save + Publish.
4. The 5-min Redis cache TTL elapses; the next moderator call after the elapse refreshes from Remote Config and emits the new list.
5. Verify the change took effect: post a test sentinel keyword via the relevant API → expect 400 (`content_moderated_profanity`) or 201 + `moderation_queue` row depending on the layer.

**Important:** the quarterly UU ITE legal-advisor review per [`docs/06-Security-Privacy.md:159`](06-Security-Privacy.md) updates BOTH the Remote Config parameter AND the repo-committed [`backend/ktor/src/main/resources/moderation/uu_ite.default.txt`](../backend/ktor/src/main/resources/moderation/uu_ite.default.txt) file (so a Remote Config outage falls back to a recent-and-vetted list via Tier 3 of the loader cascade). The same holds for the profanity list and [`backend/ktor/src/main/resources/moderation/profanity.default.txt`](../backend/ktor/src/main/resources/moderation/profanity.default.txt).

**Tier 4 (`content-moderation-fallback-list` Secret Manager slot)** is a last-resort safety net containing a JSON document `{"profanity":[...],"uu_ite":[...]}` for the catastrophic case where both Remote Config AND the repo file are unavailable. Updating Tier 4 follows the standard "Creating a new GCP Secret Manager slot" + "rotating the value on an existing slot" procedure above.

### Boot-time integrity prime

On `Application.module()` startup, the loader fires `load(ProfanityList)` + `load(UuIteList)` once each in a non-blocking coroutine to prime the Redis cache and verify Tier 3 integrity pre-traffic. A missing or corrupt repo resource surfaces a Sentry WARN with `event=moderation_list_fallback tier=repo_file to=secret_manager` BEFORE the first user-content-write request, instead of degrading the first user's submission.
