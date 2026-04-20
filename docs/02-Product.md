# NearYouID - Product Features

Core product feature specifications: what each feature does from the user's perspective. For underlying schemas, algorithms, and technical implementation, see `05-Implementation.md`. For security/privacy/moderation aspects of these features, see `06-Security-Privacy.md`.

---

## 1. User Management

### Overview

- Age-gated signup: **18+ only**
- Google/Apple Sign-In (primary, platform-specific; Android uses Credential Manager API under the hood)
- Device attestation mandatory
- Auto-generated usernames from a database of Indonesian word pairs
- Free vs Premium privacy tiers
- **1 Google ID = 1 account, 1 Apple ID = 1 account. No account linking/merging in MVP scope.** The same person signing up with Google on Android and Apple on iOS will create two separate accounts; this is disclosed in the onboarding FAQ.

### Age Gate (Product Flow)

**18+ only policy at signup** (UU PDP compliance):

- **Mandatory date-of-birth declaration** at onboarding (not a "18+" checkbox)
- **<18**: Rejected + identifier added to `rejected_identifiers` blocklist (anti-bypass; see `06-Security-Privacy.md`). No user row created. User-facing: "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas."
- **18+**: Normal flow.

> See `06-Security-Privacy.md` for verification mechanisms (including the Apple Declared Age Range API which is available now on iOS 18+) and the `rejected_identifiers` blocklist.

### Authentication (Product Behavior)

**Primary buttons** (user-facing labels):
- Android: "Masuk dengan Google" (primary; implemented via Android Credential Manager)
- iOS: "Masuk dengan Apple" (primary, mandatory per App Store policy)

**User experience**:
- First app open then Global tab (read-only, no login)
- Login wall triggered when switching to Nearby/Following or on a write action
- Rejection message (user-facing): emulator/rooted detection then "Aplikasi tidak dapat digunakan di perangkat ini" + fallback manual review link

> See `05-Implementation.md` for JWT strategy, session management, and refresh token rotation. See `06-Security-Privacy.md` for device attestation & anti-spam defenses.

### Account Recovery: Intentionally None

Losing your Google/Apple account means losing your NearYouID account. By design. Disclosed in onboarding + FAQ explicitly. A "Hapus Akun" button (user-facing) lives in Settings.

### User Profiles

**Username auto-generation** from a database of Indonesian word pairs (assigned at registration, once per account).

**Dataset**: 600 adjectives × 600 nouns + 100 modifiers = 360,000 base + 36M effective combinations with a 3-part fallback.

**Reserved usernames**: stored in a dedicated `reserved_usernames` table seeded via Flyway. Signup flow checks `SELECT 1 FROM reserved_usernames WHERE username = LOWER(:candidate)` before the unique constraint applies. Seed list: `admin`, `support`, `moderator`, `system`, `nearyou`, `staff`, `official`, `akun_dihapus`, `deleted_user`, single-char + double-char range. The admin panel can add/remove entries (see `07-Operations.md`); system seeds (`source = 'seed_system'`) are immutable at the role level.

**No regenerate path**: users cannot regenerate the auto-generated username. If they want a different handle, they must subscribe to Premium (see next section).

### Premium Username Customization (Premium Only)

Premium users can change their username from the auto-generated one to a custom handle via Settings.

**Rules**:
- Free users: no customization. Attempting the flow surfaces the paywall.
- Premium users: 1 change per rolling 30 days, tracked via `users.username_last_changed_at`.
- Custom username constraints (enforced server-side):
  - Length: 3 to 30 characters (stricter than the 60-char schema ceiling that the auto-generator uses)
  - Charset regex `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$` (must start with a letter or digit, may contain letters/digits/underscore/dot in the middle, must end with a letter, digit, or underscore; dots allowed only in the middle)
  - No consecutive dots (application-layer `!candidate.contains("..")` check; single-pattern regex cannot cleanly forbid this)
  - Reserved usernames check (including `source = 'admin_added'` entries)
  - Profanity filter + UU ITE keyword check (soft flag to `moderation_queue` with trigger `username_flagged` on hit)
  - Cannot collide with a username currently held OR on the 30-day release hold (see below)
- **30-day release hold**: when a user changes from `oldname` to `newname`, `oldname` is written to `username_history` with `released_at = changed_at + 30 days`. Nobody else can claim `oldname` until `released_at` passes. Anti-impersonation safeguard.
- **Availability probe**: `GET /api/v1/username/check?candidate=foo` rate-limited 3/day per user, non-authoritative (race-safe check happens at PATCH time).
- **Change endpoint**: `PATCH /api/v1/user/username` with body `{ new_username }`.
- **Feature flag**: `premium_username_customization_enabled` (Firebase Remote Config, default TRUE). Kill switch if abuse pattern emerges.

**Downgrade to Free**: the custom username stays as last set (not reverted to auto-generated). Further changes are disabled until the user re-subscribes. Rationale: reverting would break `@mentions` in chats, notification references, and external profile links; reverting is a punitive UX that hurts re-conversion.

**Re-subscribe**: customization unlocks again; 30-day cooldown resumes from the last change (does NOT reset on subscription cycle).

**Content limits**:
- Username: schema `VARCHAR(60)` (accommodates auto-generated fallback with UUID suffix); Premium custom capped at 30 chars at the application layer
- Bio 160 chars, display name 50 chars

> Schema (`users`, `username_history`, `reserved_usernames`) + atomic generation algorithm + customization transaction flow: see `05-Implementation.md`.

### Privacy Tiers

- Free: public only, Nearby 20km fixed
- Premium: private optional (opt-in), Nearby 10/20/50/100 km

### Suspension vs Ban

- **7-day suspension** (admin action): sets `users.is_banned = TRUE` + `users.suspended_until = NOW() + 7 days`, increments `token_version`. A daily worker flips `is_banned = FALSE` + nulls `suspended_until` when the window elapses.
- **Permanent ban** (admin action): sets `users.is_banned = TRUE`, leaves `suspended_until = NULL`. No automatic unban.
- **Shadow ban** (admin action): sets `users.is_shadow_banned = TRUE`. See `06-Security-Privacy.md`.

### Privacy Downgrade Flow (Premium to Free)

When a user with `private_profile_opt_in = TRUE` downgrades to Free (RevenueCat `EXPIRATION` or grace elapse):

1. Webhook handler sets `users.privacy_flip_scheduled_at = NOW() + INTERVAL '72 hours'` (idempotent via `COALESCE`)
2. Inserts a `notifications` row with `type = 'privacy_flip_warning'` and the scheduled deadline in `body_data`
3. Sends an FCM push + in-app banner (user-facing text in `03-UX-Design.md`)
4. If the user re-subscribes before the deadline, the handler clears `privacy_flip_scheduled_at` on the `premium_active` transition
5. If the deadline elapses without re-subscribe, the hourly `/internal/privacy-flip-worker` flips `private_profile_opt_in = FALSE`, busts the Redis profile cache, and writes an audit log entry

During the 72h window the user is **still effectively private** (app-layer short-circuit on `privacy_flip_scheduled_at IS NOT NULL AND NOW() < privacy_flip_scheduled_at`). The schema-level formula `private_profile_opt_in AND premium_status` resolves to FALSE during the window, so the UX layer is explicitly responsible for honouring the grace.

> Worker SQL, cancellation logic, and audit format: see `05-Implementation.md` Privacy Flip Worker.

---

## 2. Post System

### Overview

- Text post (max 280 chars) + auto/manual location
- Location: coordinate + address resolution + optional naming
- **Visibility model**: the post has no reach control from the author's side. A post "exists" at the author's location when it was created. The viewer chooses the radius.
- Quota: Free 10/day, Premium unlimited
- **Premium edit window of 30 minutes** with server-side validation + transactional atomicity + race protection
- Soft delete only
- **Distance display**: floor 5km, round to nearest 1km above (e.g. 7.4km displays as "7km", 7.6km as "8km"). Actual <5km displays as "5km".
- Premium can hide distance globally (distance number only; city name visible)

### Coordinate Storage Policy (Anti-Triangulation)

Posts have 2 geography columns: `display_location` (fuzzed) for public rendering, and `actual_location` (precise) for admin/moderation/reverse-geocoding.

Fuzz is deterministic per `post_id`, non-reversible without the server secret, and distributed 0-500m along bearing 0-2π uniform.

> See `05-Implementation.md` for the HMAC-based jitter algorithm, query rules, and GIST index policy.

### UX Copy Strategy (Avoid Misinterpretation)

User-facing strings (kept in Bahasa Indonesia):
- Timeline header: "Post dari lokasi ini" (not "Orang di sekitar kamu")
- Post detail: "Diposting dari {city_name}, {relative_time}"
- Posts from an author who has since moved: NOT hidden, NOT updated to the new location. A post is a snapshot of the location at creation, forever.
- One-time onboarding hint: "NearYouID menampilkan post berdasarkan lokasi saat post dibuat, bukan lokasi terkini penulis"

### Post Edit History (Product Behavior)

Edited posts must have an audit trail.

**Frontend behavior**:
- Edited posts show a "Diedit [relative time]" label (user-facing)
- Tapping the label opens a "Riwayat edit" modal (user-facing) with full chronological history; version number computed via `ROW_NUMBER`
- Version display rendered as "Versi ke-N" (user-facing, `ROW_NUMBER OVER PARTITION BY post_id ORDER BY edited_at`).

**Admin Panel**: full access via the post detail page. Report queue filter "post has edit history" for prioritized review.

> Schema (`post_edits`), transactional SQL, and race-safety patterns: see `05-Implementation.md`.

---

## 3. Timeline Features

Three tabs: **Nearby**, **Following**, **Global**.

### Nearby Timeline

Posts within the viewer's filter range, chronological. 4-position slider: 10/20/50/100 km.

- Free: stuck at 20km. Trying to slide then bounces back + upsell
- Premium: can pick any of the 4

### Following Timeline

Posts from followed users, chronological.

### Global Timeline

Posts from all of Indonesia, chronological, no location filter. Each post shows the city name under the username.

- No province/island filter
- No ranking/algorithm in the early phase
- Global is the entry point; Nearby and Following are home
- Guests can view Global timeline only (read-only, no login required, capped at 10 posts/session soft + 30/hour hard)

### Polygon-Based Reverse Geocoding

Reverse geocoding runs once when a post is created, and the result is stored as `city_name`.

**Primary dataset**: BPS kabupaten/kota polygons. Public domain or CC-BY (verify in Pre-Phase 1). Backup: OpenStreetMap (ODbL, attribution required if derived data is shared. Internal DB use is generally safe with attribution in the Privacy Policy).

**Polygon scope**:
- BPS: kabupaten/kota level
- DKI Jakarta special: 5 kotamadya + Kepulauan Seribu treated at kabupaten/kota level
- Stored in the `admin_regions` table (schema in `05-Implementation.md`)

**Queries use `actual_location`** (not `display_location`, since accuracy matters for administrative boundaries).

**Fallback ladder**:
1. `ST_Contains(geom, actual_location)` strict match then return name
2. If 0 matches: `ST_DWithin(geom, point, 0.0001)` (~10m buffer) + `ORDER BY ST_Distance(geom_centroid, point) ASC LIMIT 1` then return name (deterministic tie-breaker for boundary points)
3. If still 0: nearest neighbor WITHIN 50km then return name + log `fuzzy_match` flag
4. If still 0: `city_name = NULL`, display "Indonesia" or "Luar Indonesia" (user-facing)

**Enclaves/exclaves**: the BPS dataset already includes enclaves. If using OSM: filter `admin_level=5 AND boundary=administrative AND place != island`. Manually spot-check 10 complex kabupaten in Pre-Phase 1.

**Maritime**:
- Points at sea within 12 nautical miles of a coastal kabupaten's shoreline are assigned to that kabupaten
- Buffer coastal kabupaten polygons by 12 nautical miles (~22km) maritime extension in the import script
- Points in the EEZ/international waters then `city_name = NULL`

**Cache**: Redis key `geocode:{geocell:<lat2dp>_<lng2dp>}` TTL 30 days. Hash tag `{geocell:...}` enforces Upstash cluster co-location consistency with other keys (see `05-Implementation.md` key format standard). Pre-warm top 100 popular cities in Pre-Phase 1. Eviction LRU cap 100k entries (~10MB).

### Shared Timeline Mechanics

- Cursor-based pagination, 20-50 posts/page
- **Cursor format**: base64-encoded `{created_at_microsec}:{post_id}` tuple. Stable secondary sort by `post_id`
- PostGIS `ST_DWithin` + GIST index on `display_location` (Nearby only; Global and Following skip the spatial filter)
- Scroll limit uses two layers (soft per-session + hard rolling per-hour, see Business Model in `01-Business.md`)

> Composite indexes, cursor SQL pattern, Global timeline query, and Redis session tracking: see `05-Implementation.md`.

---

## 4. Social Features

- Follow/unfollow with relationship tracking (`follows` table)
- Like: **Free 10/day, Premium unlimited**. Both tiers cap 500/hour burst (anti-bot). Stored in `post_likes`.
- Reply: Free 20/day, Premium unlimited. Does not count toward post quota. Max 280 chars. Flat structure (no nested reply-to-reply threading in MVP). Stored in `post_replies`.
- Real-time notifications (FCM push, DB persisted in `notifications` table)

### Block User (MVP, Free & Premium)

Users can block other users from the profile page or a post/reply context menu.

**Block effect**:
- Blocker does not see posts, replies, or the profile of the blocked user
- Blocked user does not see posts, replies, or the profile of the blocker (symmetric)
- Blocked user cannot initiate a DM to the blocker. Existing conversations remain visible in history, but no new messages can be sent.
- Like/reply history is preserved (audit integrity), but hidden from each party's timeline/profile
- Follow relationships (both directions) are automatically removed when a block is applied
- Notifications from a blocked user: suppressed

**UX**:
- "Blokir" button (user-facing) in the post kebab menu + profile
- Confirmation modal
- "Daftar yang diblokir" (user-facing) in Settings, with an unblock path

**Rate limit**: 30 block/unblock actions per hour per user (anti-flip-flop abuse).

> Schema `user_blocks` + query rules + view integration: see `05-Implementation.md`.

### Report System

One-tap report from a post and profile. Report is recorded in the `reports` table with reporter, target entity type (post/reply/user), reason category, and free-text.

**Auto-hide**: 3 unique reporters (accounts >7 days old) then automatically hidden pre-review.

**Moderation queue**: auto-hidden posts and flagged content are queued in `moderation_queue` for admin review.

> Schemas `reports` + `moderation_queue`: see `05-Implementation.md`.

### Notifications (DB-Persisted)

Real-time in-app notification list backed by the `notifications` table. FCM push triggers the client to fetch the list.

- Event types: `post_liked`, `post_replied`, `followed`, `chat_message`, `subscription_billing_issue`, `subscription_expired`, `post_auto_hidden`, `account_action_applied`, `data_export_ready`
- Read state per notification (`read_at` timestamp)
- Retention: 90 days auto-purge via weekly cleanup worker

> Schema `notifications`: see `05-Implementation.md`.

### Search (Premium, Month 1+)

Users can search post content + usernames.

**Implementation**: PostgreSQL full-text search (`tsvector`) + `pg_trgm` for fuzzy matching, GIN index.

**Scope**:
- Search post content from users who are NOT private + NOT shadow-banned + NOT blocked
- Search usernames exact + fuzzy
- Global search (all of Indonesia)
- No location filter (MVP)

**Rate limit**: 60 queries/hour Premium (abuse prevention).

**Re-index trigger**: async job on every shadow ban / block / unban applied.

> FTS schema, query pattern, and index definition: see `05-Implementation.md`.

---

## 5. Direct Messaging (1:1 Chat)

### Strategy

Supabase Realtime **Broadcast mode** during the pre-swap period (Months 1-14). Ktor is the authoritative publisher. Swap to Ktor WebSocket + Redis Streams when cost triggers are hit (Month 15+).

**Rationale for Broadcast mode**: the cost is much lower because RLS evaluates once per subscribe (not per message). Aligned with the Ktor-as-publisher pattern, so migration to Ktor WS post-swap is smoother.

### Chat Flow (Pre-Swap Period, Product-Level)

```
Client A sends message via REST
  ↓
Ktor validates quota & permission (incl block check)
  ↓
Ktor persists to Postgres
  ↓
Ktor broadcasts via Supabase Realtime
  ↓
Client B (subscribed) receives and renders
  ↓
Client B fetches history via REST to resync if needed
```

> Full technical flow, RLS policies, schemas, and failure handling: see `05-Implementation.md`.

### Spec

- **Chat context card** (post embed): stores `embedded_post_id` + `embedded_post_snapshot JSONB` + `embedded_post_edit_id` at chat initiation
- Persistence: all messages stored in Postgres
- Quota: Free 50/day, Premium unlimited
- Message content: max 2000 chars
- Block enforcement: blocked user cannot initiate a DM. For existing conversations, both parties still see history, but sending is rejected with 403 and user-facing message "Tidak dapat mengirim pesan ke user ini."

### Embedded Post Behavior

**Snapshot policy**:
- At chat initiation from a post: copy `{content, location_display, city_name, author_display_name, author_username_at_embed_time, created_at, edited_at_at_embed_time}` into `embedded_post_snapshot`
- Post edit: snapshot is NOT updated (version pinning via `embedded_post_edit_id`)
- Post soft-delete: snapshot still renders + warning banner (user-facing): "Post ini sudah dihapus"
- Post hard-delete: `embedded_post_id` is SET NULL via FK, snapshot still renders + permanent label (user-facing): "Post ini sudah dihapus" + author label "Akun Dihapus" if the author has been tombstoned

**Edit history navigation**:
- Tap embed then redirect to the post detail at the **current content version**, with banner (user-facing) "Post ini sudah di-edit setelah kamu chat" if current version ≠ snapshot version
- Tap "Riwayat edit" (user-facing) then modal with all content versions; the version at chat initiation is highlighted via `embedded_post_edit_id`

**Storage growth monitoring**: snapshot ~500 bytes/message. Monitor growth, compress JSONB if >200 bytes per message (gzip at application layer). Archive to R2 for messages >6 months old if Supabase DB size approaches 60% of cap.

**Encryption**:
- **In transit**: HTTPS (REST) and WSS over TLS (Supabase Realtime). Both are mandatory in production.
- **At rest**: Supabase Pro includes database-level encryption.
- **Not E2E**: admins can read for moderation appeals (transparently documented in the Privacy Policy + explicit disclaimer at onboarding).

---

## 6. Premium Media Upload (Image, Month 6+)

### Feature Flag Gating

Built during Phase 4 (Weeks 14-16), released to users in Month 6. Gated via:

- **Firebase Remote Config flag** `image_upload_enabled` (boolean, default FALSE)
- Backend: `POST /api/v1/post` with `image_id` rejects 403 if the flag is FALSE
- Mobile UI: upload button hidden if the flag is FALSE
- Admin Panel toggle to flip the flag (with audit log)
- Pre-Month 6 launch rehearsal: enable the flag in the internal QA build variant, dogfood for 2 weeks before public enable

### Storage & Processing

**Cloudflare Images**. Auto-generates variants, format negotiation (WebP/AVIF), and global CDN built-in. R2 is retained for non-image files (future video/audio post-MVP).

**Delivery via custom subdomain**: `https://img.nearyou.id/...`. The subdomain sits in the `nearyou.id` zone which already has the CSAM Tool enabled, so the delivery path is cached by CF and auto-scanned.

**Pre-Phase 1 verification**: confirm the exact CF Images URL structure when using a custom subdomain (default is `/cdn-cgi/imagedelivery/<account_hash>/<image_id>/<variant>`; CF Images also offers "Custom Image URLs" for a cleaner path). Document the final format in the Version Pinning Decisions Log.

### CSAM Detection

**Cloudflare CSAM Scanning Tool** (free, zone-level). Automatic fuzzy hash scan on cached images as they enter the cache, matching against NCMEC + partner NGO lists. On match: blocks the URL (HTTP 451) and sends a daily email notification. Cloudflare files third-party reports to NCMEC automatically.

**Important: the CF CSAM Scanning Tool does NOT emit webhooks.** The downstream `/internal/csam-webhook` handler must be invoked by one of the supported paths (see `04-Architecture.md` for full detail):

- **Primary (MVP)**: admin reviews the CF email notification in the Admin Panel and triggers the handler manually by pasting the matched URL / image_id.
- **Automated Phase 2+**: a Cloudflare Worker attached to the `img.nearyou.id` route watches for `451 Unavailable For Legal Reasons` responses and POSTs to `/internal/csam-webhook`.
- **Alternative (deferred)**: a daily Cloud Run Job that parses the inbound email via IMAP or the email provider API.

**Pre-launch verification in Pre-Phase 1**:
1. Set up `img.nearyou.id` as a CNAME to the Cloudflare edge
2. Enable the CSAM Scanning Tool on the `nearyou.id` zone via Dashboard > Caching > Configuration
3. Verify email for match notifications
4. Upload sample legal test content via Cloudflare Images, request via `img.nearyou.id/...`, verify it appears in the CF Images dashboard and scan log
5. Document the SOP (including the admin-triggered handler invocation flow)

### Explicit Content Upfront

Google Cloud Vision Safe Search at upload time to block adult/violent content before it enters the cache. Pay-per-image (verify actual rate in Pre-Phase 1).

### Image Upload Flow (Product View)

```
User uploads image (5MB max, compressed client-side)
  ↓
Ktor: checks image_upload_enabled flag; validates file size, quota Premium 50/day, 1 per 60 sec throttle
  ↓
Ktor: Google Cloud Vision Safe Search scan (sync, ~200-500ms)
  ↓ (if adult/violent/racy >0.8: REJECT upload)
  ↓
Ktor: upload to Cloudflare Images API
  ↓
Ktor: INSERT INTO posts + images relation, status 'published'
  ↓
Return 201 to client with https://img.nearyou.id/... URL
  ↓
(async, separate path)
Client views image then the request hits CF edge at img.nearyou.id
  ↓
CF cache + CF CSAM Scanning Tool fuzzy hash match against NCMEC
  ↓ (on match)
CF: block URL (HTTP 451) + daily email notification to admin address
  ↓
Admin receives CF email + reviews in Admin Panel (or CF Worker auto-forwards 451 to the handler in Phase 2+)
  ↓
/internal/csam-webhook handler executes:
  - Mark affected post as hard-deleted + audit log
  - Ban user permanently + increment token_version (kick sessions)
  - Cascade delete user's other posts (abundance of caution)
  - Archive metadata to csam_detection_archive (90-day preservation)
  - Admin panel notification for review
  - Queue Kominfo report
```

> CSAM archive schema, Kominfo reporting SOP, and encryption approach: see `06-Security-Privacy.md`.

### Hard Limit Policy

| Limit | Value |
|-------|-------|
| Max images per post | 1 |
| Max image uploads Premium | 50/day |
| Max file size | 5MB (guarded client + server, client auto-compresses) |
| Upload throttle | 1 per 60 seconds per user |
| Free tier | 0 image uploads |

### Delivery Optimization (mandatory for cost control)

- Single-variant delivery via `<img srcset>` (saves ~50% delivery cost)
- Stricter lazy-load: no thumbnail preload during timeline scroll, only on-screen render (cuts ~30%)
- Aggressive caching: `Cache-Control: public, max-age=31536000, immutable`

### Monitoring

- Daily cost alert in the Cloudflare dashboard
- Per-user delivery tracking
- Anomaly detection: delivery >5x baseline per user then auto-flag (baseline is rolling 30-day per user; new users <30 days use global avg)
- CSAM detection count (expected: near-zero, any positive triggers urgent admin review)

### Moderation Flow (Human-in-the-Loop)

| Stage | Trigger | Detection | Decision | Execution |
|-------|---------|-----------|----------|-----------|
| Upfront block | Vision Safe Search adult/violent >0.8 | Auto | Auto | Auto reject upload |
| Soft warning | User at 40/50 uploads today | Auto | Auto | Auto in-app toast |
| Daily limit | Upload number 51 | Auto | Auto | Auto modal reject |
| CSAM positive | CF CSAM Scanning Tool match (URL blocked + daily email) | Auto block + admin notify | Admin-triggered handler (or CF Worker auto-forward in Phase 2+) | Auto block URL + ban user + cascade + archive |
| Anomaly alert | Delivery >5x baseline or unusual upload pattern | Auto | Manual (Oka) | Auto alert admin |
| Suspend 7 days | Admin decision | Auto flag | Manual (Oka) | Auto kick session + `suspended_until` set |
| Permanent ban | Admin decision, last resort | Auto flag | Manual (Oka) | Auto sticky ban |

**Image lifecycle**: when a post is hard-deleted, the image is cascade-deleted in the Cloudflare Images API. CSAM-flagged images are retained in the archive for 90 days (preservation obligation).
