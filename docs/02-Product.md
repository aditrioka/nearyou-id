# NearYouID - Product Features

Feature specs from the user's perspective. Schemas/algorithms/implementation: `05-Implementation.md`. Security/privacy/moderation: `06-Security-Privacy.md`.

---

## 1. User Management

### Overview

- Age-gated signup: **18+ only**
- Google/Apple Sign-In (primary, platform-specific)
- Device attestation mandatory
- Auto-generated usernames from a database of Indonesian word pairs
- Free vs Premium privacy tiers
- **1 Google ID = 1 account, 1 Apple ID = 1 account; no account linking/merging in MVP scope.** Same person on Google (Android) + Apple (iOS) = two separate accounts — disclosed in the onboarding FAQ.

### Age Gate (Product Flow)

**18+ only at signup** (UU PDP compliance) — **mandatory date-of-birth declaration** at onboarding, not an "18+" checkbox:

- **<18**: rejected; identifier added to the `rejected_identifiers` blocklist (anti-bypass); no user row created. User-facing: "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas."
- **18+**: normal flow.

> Verification mechanisms (incl. the Apple Declared Age Range API, available now on iOS 18+) + the blocklist: `06-Security-Privacy.md`.

### Authentication (Product Behavior)

**Primary buttons** (user-facing): Android "Masuk dengan Google" (via Android Credential Manager); iOS "Masuk dengan Apple" (mandatory per App Store policy).

**UX**: first app open then Global tab (read-only, no login); login wall on switching to Nearby/Following or any write action. Emulator/rooted detection then "Aplikasi tidak dapat digunakan di perangkat ini" (user-facing) + fallback manual review link.

> JWT strategy, session management, refresh token rotation: `05-Implementation.md`. Device attestation & anti-spam: `06-Security-Privacy.md`.

### Account Recovery: Intentionally None

Losing the Google/Apple account means losing the NearYouID account — by design, disclosed explicitly in onboarding + FAQ. "Hapus Akun" button (user-facing) in Settings.

### User Profiles

**Username auto-generation** at registration, once per account, from the Indonesian word-pair database: 600 adjectives × 600 nouns + 100 modifiers = 360,000 base, 36M effective combinations with a 3-part fallback. **No regenerate path** — a different handle requires Premium (next section).

**Reserved usernames**: `reserved_usernames` table, Flyway-seeded; signup checks `SELECT 1 FROM reserved_usernames WHERE username = LOWER(:candidate)` before the unique constraint. Seeds: `admin`, `support`, `moderator`, `system`, `nearyou`, `staff`, `official`, `akun_dihapus`, `deleted_user`, single-char + double-char range. Admin panel can add/remove entries (`07-Operations.md`); system seeds (`source = 'seed_system'`) are role-level immutable.

### Premium Username Customization (Premium Only) — DESIGN

> **Status: SHIPPED** (`premium-username-customization`, 2026-06-14). `PATCH /api/v1/user/username` + `GET /api/v1/username/check` are live in `backend/ktor/` (backend capability; the mobile Settings UI + admin oversight are separate follow-on changes). Migration-free — `users.username_last_changed_at` + `username_history` pre-existed since V2/V3.

Premium users may replace the auto-generated username with a custom handle via Settings.

**Rules**:
- Free: no customization; attempting the flow surfaces the paywall.
- Premium: 1 change per rolling 30 days, tracked via `users.username_last_changed_at`.
- Server-side constraints:
  - Length 3 to 30 chars (stricter than the 60-char schema ceiling the auto-generator uses)
  - Charset regex `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$` (dots middle-only)
  - No consecutive dots — application-layer `!candidate.contains("..")`; a single-pattern regex can't cleanly forbid it
  - Reserved-usernames check (incl. `source = 'admin_added'`)
  - Profanity + UU ITE keyword check — on hit the change is **rejected upfront** and a `moderation_queue` row (`trigger = 'username_flagged'`, `target_type = 'user'`) is inserted for admin awareness (canonical behaviour: `06-Security-Privacy.md` § Premium Username Moderation)
  - No collision with a held username or one on 30-day release hold
- **30-day release hold** (anti-impersonation): changing `oldname` to `newname` writes `oldname` to `username_history` with `released_at = changed_at + 30 days`; unclaimable until `released_at` passes.
- **Availability probe**: `GET /api/v1/username/check?candidate=foo` — 3/day per user, non-authoritative (race-safe check at PATCH time).
- **Change endpoint**: `PATCH /api/v1/user/username`, body `{ new_username }`.
- **Feature flag**: `premium_username_customization_enabled` (Firebase Remote Config, default TRUE) — kill switch if an abuse pattern emerges.

**Downgrade to Free**: custom username stays as last set (no revert to auto-generated); further changes disabled until re-subscribe. Rationale: revert would break `@mentions` in chats, notification references, and external profile links — punitive UX that hurts re-conversion.

**Re-subscribe**: customization unlocks again; the 30-day cooldown resumes from the last change (NOT reset by subscription cycle).

**Content limits**: username schema `VARCHAR(60)` (fits the auto-generated fallback with UUID suffix), Premium custom capped at 30 chars at the application layer; bio 160 chars; display name 50 chars.

> Schema (`users`, `username_history`, `reserved_usernames`), atomic generation algorithm, customization transaction flow: `05-Implementation.md`.

### Privacy Tiers

- Free: public only, Nearby 20km fixed
- Premium: private optional (opt-in), Nearby 10/20/50/100 km

### Suspension vs Ban

All admin actions:

- **7-day suspension**: sets `users.is_banned = TRUE` + `users.suspended_until = NOW() + 7 days`, increments `token_version`; a daily worker flips `is_banned = FALSE` + nulls `suspended_until` once the window elapses.
- **Permanent ban**: `is_banned = TRUE`, `suspended_until = NULL`; no automatic unban.
- **Shadow ban**: `is_shadow_banned = TRUE`; see `06-Security-Privacy.md`.

### Privacy Downgrade Flow (Premium to Free) — DESIGN

> **Status: DESIGN.** No code today — the `/internal/privacy-flip-worker` route, RevenueCat webhook handler, and worker SQL below do NOT exist; `Application.kt` mounts only `/internal/unban-worker` under the internal namespace. `users.privacy_flip_scheduled_at` IS present in V2 schema (data model partially ready).

When a user with `private_profile_opt_in = TRUE` downgrades to Free (RevenueCat `EXPIRATION` or grace elapse):

1. Webhook handler sets `users.privacy_flip_scheduled_at = NOW() + INTERVAL '72 hours'` (idempotent via `COALESCE`)
2. Inserts a `notifications` row, `type = 'privacy_flip_warning'`, scheduled deadline in `body_data`
3. Sends FCM push + in-app banner (user-facing text: `03-UX-Design.md`)
4. Re-subscribe before the deadline: handler clears `privacy_flip_scheduled_at` on the `premium_active` transition
5. Deadline elapses: the hourly `/internal/privacy-flip-worker` flips `private_profile_opt_in = FALSE`, busts the Redis profile cache, writes an audit log entry

During the 72h window the user is **still effectively private** via app-layer short-circuit (`privacy_flip_scheduled_at IS NOT NULL AND NOW() < privacy_flip_scheduled_at`) — the schema-level formula `private_profile_opt_in AND premium_status` resolves FALSE during the window, so the UX layer explicitly owns honouring the grace.

> Worker SQL, cancellation logic, audit format: `05-Implementation.md` Privacy Flip Worker.

---

## 2. Post System

### Overview

- Text post (max 280 chars) + automatic location (device-acquired GPS; no manual selection)
- Location: coordinate + auto-derived address resolution (no manual entry)
- **Visibility model**: no author-side reach control — a post "exists" at its creation location; the viewer chooses the radius
- Quota: Free 10/day, Premium unlimited
- **Premium edit window of 30 minutes** — server-side validation, transactional atomicity, race protection
- Soft delete only
- **Distance display**: floor 5km, round to nearest 1km above (7.4km shows "7km", 7.6km "8km"; actual <5km shows "5km")
- Premium can hide distance globally (number only; city name stays visible)

### Coordinate Storage Policy (Anti-Triangulation)

Posts carry 2 geography columns: `display_location` (fuzzed, public rendering) and `actual_location` (precise — admin/moderation/reverse-geocoding). Fuzz is deterministic per `post_id`, non-reversible without the server secret, distributed 50-500m along bearing 0-2π uniform (the 50 m floor is load-bearing — it prevents near-zero offsets that would leak `actual_location`; canonical envelope in `openspec/specs/coordinate-jitter/spec.md`).

> HMAC-based jitter algorithm, query rules, GIST index policy: `05-Implementation.md`.

### UX Copy Strategy (Avoid Misinterpretation)

User-facing strings (kept in Bahasa Indonesia):

- Disambiguation copy "Post dari lokasi ini" (not "Orang di sekitar kamu"): the `timeline_nearby_title` string is **retained in the catalog** but **no longer rendered as a screen header** (amended 2026-06-08, `mobile-home-shell-redesign`) — Nearby became a tab in the Home section's tab row, so an in-screen header duplicated the selected **Beranda** section + **Sekitar** tab (Material 3 redundancy + a status-bar gap). Its disambiguation (posts-from-this-location vs people-around-you) moved to (a) the onboarding hint below and (b) the per-card "Diposting dari {city}" context.
- Post detail: "Diposting dari {city_name}, {relative_time}"
- Author has since moved: posts NOT hidden, NOT updated to the new location — a post is a snapshot of the location at creation, forever.
- One-time onboarding hint: "NearYouID menampilkan post berdasarkan lokasi saat post dibuat, bukan lokasi terkini penulis" — now the **primary** anti-misinterpretation surface (implementation tracked by GitHub issue [#204](https://github.com/aditrioka/nearyou-id/issues/204) `mobile-location-disambiguation-onboarding-hint`, label `follow-up`).

### Post Edit History (Product Behavior)

Edited posts must have an audit trail.

**Frontend**: "Diedit [relative time]" label (user-facing); tapping it opens the "Riwayat edit" modal (user-facing) — full chronological history, versions rendered "Versi ke-N" (user-facing; `ROW_NUMBER OVER PARTITION BY post_id ORDER BY edited_at`).

**Admin Panel**: full access via post detail; report-queue filter "post has edit history" for prioritized review.

> Schema (`post_edits`), transactional SQL, race-safety patterns: `05-Implementation.md`.

---

## 3. Timeline Features

Three tabs: **Nearby**, **Following**, **Global**.

### Nearby Timeline

Posts within the viewer's filter range, chronological. 4-position slider 10/20/50/100 km — Free stuck at 20km (sliding bounces back + upsell); Premium picks any of the 4.

### Following Timeline

Posts from followed users, chronological.

### Global Timeline

Posts from all of Indonesia, chronological, no location filter; city name shown under each username.

- No province/island filter; no ranking/algorithm in the early phase
- Global is the entry point; Nearby and Following are home
- Guests: Global only, read-only, no login, capped 10 posts/session soft + 30/hour hard

**Status (2026-04):** authenticated `GET /api/v1/timeline/global` shipped via `global-timeline-with-region-polygons` (V11 schema + trigger, V12 552-row OSM polygon seed) — chronological feed with per-post `city_name` populated by the `posts_set_city_tg` trigger. Guest read-only access + the session/hour caps stay deferred: they need Redis-backed rate-limit infrastructure (Phase 1 item 24) and ship with that change.

### Polygon-Based Reverse Geocoding

Runs once at post creation; result stored as `city_name`.

**Primary dataset**: BPS kabupaten/kota polygons — public domain or CC-BY (verify in Pre-Phase 1). Backup: OpenStreetMap — ODbL, attribution required if derived data is shared; internal DB use is generally safe with attribution in the Privacy Policy.

**Polygon scope**: BPS kabupaten/kota level; DKI Jakarta special-cased as 5 kotamadya + Kepulauan Seribu at kabupaten/kota level; stored in `admin_regions` (schema: `05-Implementation.md`).

**Queries use `actual_location`** (not `display_location`) — accuracy matters for administrative boundaries.

**Fallback ladder**:
1. `ST_Contains(geom, actual_location)` strict match then return name
2. If 0 matches: `ST_DWithin(geom, point, 0.0001)` (~10m buffer) + `ORDER BY ST_Distance(geom_centroid, point) ASC LIMIT 1` then return name (deterministic tie-breaker for boundary points)
3. If still 0: nearest neighbor WITHIN 50km then return name + log `fuzzy_match` flag
4. If still 0: `city_name = NULL`, display "Indonesia" or "Luar Indonesia" (user-facing)

**Enclaves/exclaves**: included in the BPS dataset. If using OSM: filter `admin_level=5 AND boundary=administrative AND place != island`. Manually spot-check 10 complex kabupaten in Pre-Phase 1.

**Maritime**: sea points within 12 nautical miles of a coastal kabupaten's shoreline are assigned to it — the import script buffers coastal polygons by that 12nm (~22km) maritime extension; EEZ/international waters then `city_name = NULL`.

**Cache**: Redis key `geocode:{geocell:<lat2dp>_<lng2dp>}`, TTL 30 days; the `{geocell:...}` hash tag enforces Upstash cluster co-location consistency with other keys (key format standard: `05-Implementation.md`). Pre-warm top 100 popular cities in Pre-Phase 1. LRU eviction, cap 100k entries (~10MB).

### Shared Timeline Mechanics

- Cursor-based pagination, 20-50 posts/page; **cursor format**: base64-encoded `{created_at_microsec}:{post_id}` tuple, stable secondary sort by `post_id`
- PostGIS `ST_DWithin` + GIST index on `display_location` (Nearby only; Global and Following skip the spatial filter)
- Scroll limit: two layers, soft per-session + hard rolling per-hour (Business Model, `01-Business.md`)

> Composite indexes, cursor SQL pattern, Global timeline query, Redis session tracking: `05-Implementation.md`.

---

## 4. Social Features

- Follow/unfollow with relationship tracking (`follows` table)
- Like: **Free 10/day, Premium unlimited**; both tiers cap 500/hour burst (anti-bot). Stored in `post_likes`.
- Reply: Free 20/day, Premium unlimited; does not count toward post quota; max 280 chars; flat (no nested reply-to-reply threading in MVP). Stored in `post_replies`.
- Real-time notifications (FCM push, DB-persisted in `notifications`)

Authoritative limits table + rationale: `01-Business.md` § Freemium Tiers / § Free-Tier Write Quota Summary / § Content Length Limits.

### Block User (MVP, Free & Premium)

Block from the profile page or a post/reply context menu.

**Block effect** (symmetric):
- Neither party sees the other's posts, replies, or profile
- Blocked user cannot initiate a DM to the blocker; existing conversations remain visible in history, but no new messages can be sent
- Like/reply history preserved (audit integrity) but hidden from each party's timeline/profile
- Follow relationships (both directions) auto-removed on block
- Notifications from a blocked user: suppressed

**UX**: "Blokir" button (user-facing) in the post kebab menu + profile; confirmation modal; "Daftar yang diblokir" (user-facing) in Settings with an unblock path.

**Rate limit**: 30 block/unblock actions per hour per user (anti-flip-flop abuse).

> Schema `user_blocks`, query rules, view integration: `05-Implementation.md`.

### Report System

One-tap report from a post and profile; recorded in `reports` with reporter, target entity type (post/reply/user), reason category, free-text.

**Auto-hide**: 3 unique reporters (accounts >7 days old) then auto-hidden pre-review.

**Moderation queue**: auto-hidden posts + flagged content queue in `moderation_queue` for admin review.

> Schemas `reports` + `moderation_queue`: `05-Implementation.md`.

### Notifications (DB-Persisted)

Real-time in-app notification list backed by `notifications`; FCM push triggers the client to fetch it.

- Event types (canonical: V10 `notifications.type` CHECK constraint, 13 values): `post_liked`, `post_replied`, `followed`, `chat_message`, `subscription_billing_issue`, `subscription_expired`, `post_auto_hidden`, `account_action_applied`, `data_export_ready`, `chat_message_redacted`, `privacy_flip_warning`, `username_release_scheduled`, `apple_relay_email_changed`. First four have shipped writers; the remaining nine are reserved for their owning features (chat redaction, billing webhook, admin moderation, privacy-flip worker, etc.) — see the V10 migration header.
- Read state per notification (`read_at` timestamp)
- Retention: 90 days, auto-purge via weekly cleanup worker

> Canonical CHECK + per-type `body_data` JSON: `05-Implementation.md` § Notifications Schema.

### Search (Premium, Month 1+)

Searches post content + usernames. **Implementation**: PostgreSQL full-text search (`tsvector`) + `pg_trgm` fuzzy matching, GIN index.

**Scope**: post content from users NOT private + NOT shadow-banned + NOT blocked; usernames exact + fuzzy; global (all of Indonesia); no location filter (MVP).

**Rate limit**: 60 queries/hour Premium (abuse prevention).

**Re-index trigger** (deferred to Month 6+, when the Redis search-result cache lands per `docs/05-Implementation.md` § Search): async job on every shadow ban / block / unban applied — needed only once a results cache exists to invalidate. Pre-cache, the view-based shadow-ban filter (`visible_posts` / `visible_users`) plus GIN auto-maintenance handle correctness inline.

> FTS schema, query pattern, index definition: `05-Implementation.md`.

---

## 5. Direct Messaging (1:1 Chat)

### Strategy

Supabase Realtime **Broadcast mode** pre-swap (Months 1-14), Ktor as the authoritative publisher; swap to Ktor WebSocket + Redis Streams when cost triggers are hit (Month 15+). **Broadcast rationale**: much lower cost (RLS evaluates once per subscribe, not per message); alignment with the Ktor-as-publisher pattern smooths the post-swap migration to Ktor WS.

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

> Full technical flow, RLS policies, schemas, failure handling: `05-Implementation.md`.

### Spec

- **Chat context card** (post embed): stores `embedded_post_id` + `embedded_post_snapshot JSONB` + `embedded_post_edit_id` at chat initiation
- Persistence: all messages stored in Postgres
- Quota: Free 50/day, Premium unlimited; message content max 2000 chars
- Block enforcement: blocked user cannot initiate a DM; existing conversations keep visible history but sending is rejected with 403 + user-facing "Tidak dapat mengirim pesan ke user ini."

### Embedded Post Behavior

**Snapshot policy**:
- At chat initiation from a post: copy `{content, location_display, city_name, author_display_name, author_username_at_embed_time, created_at, edited_at_at_embed_time}` into `embedded_post_snapshot`
- Post edit: snapshot NOT updated (version pinning via `embedded_post_edit_id`)
- Post soft-delete: snapshot still renders + warning banner (user-facing) "Post ini sudah dihapus"
- Post hard-delete: `embedded_post_id` SET NULL via FK; snapshot still renders + permanent label (user-facing) "Post ini sudah dihapus" + author label "Akun Dihapus" if the author is tombstoned

**Edit history navigation**:
- Tap embed then redirect to post detail at the **current content version**, with banner (user-facing) "Post ini sudah di-edit setelah kamu chat" if current ≠ snapshot version
- Tap "Riwayat edit" (user-facing) then a modal of all content versions, the chat-initiation version highlighted via `embedded_post_edit_id`

**Storage growth monitoring**: snapshot ~500 bytes/message; compress JSONB if >200 bytes/message (gzip at application layer); archive messages >6 months old to R2 if Supabase DB size approaches 60% of cap.

**Encryption**: in transit — HTTPS (REST) + WSS over TLS (Supabase Realtime), both mandatory in production; at rest — Supabase Pro database-level encryption; **not E2E** — admins can read for moderation appeals (transparently documented in the Privacy Policy + explicit onboarding disclaimer).

---

## 6. Premium Media Upload (Image, Month 6+) — DESIGN

> **Status: DESIGN entire section.** No code today — no image-upload endpoint, Cloudflare Images / R2 wiring, Vision Safe Search check, CSAM webhook handler, or `image_upload_enabled` flag enforcement. The `:infra:r2` and `:infra:cloudflare-images` modules described in `docs/04-Architecture.md` are unscaffolded.

### Feature Flag Gating

Built during Phase 4 (Weeks 14-16), released to users in Month 6. Gated via:

- **Firebase Remote Config flag** `image_upload_enabled` (boolean, default FALSE)
- Backend: `POST /api/v1/post` with `image_id` rejects 403 if the flag is FALSE
- Mobile UI: upload button hidden if the flag is FALSE
- Admin Panel toggle to flip the flag (audit-logged)
- Pre-Month 6 launch rehearsal: enable in the internal QA build variant, dogfood 2 weeks before public enable

### Storage & Processing

**Cloudflare Images** — auto-generated variants, format negotiation (WebP/AVIF), built-in global CDN. R2 retained for non-image files (future video/audio post-MVP).

**Delivery via custom subdomain** `https://img.nearyou.id/...` — sits in the `nearyou.id` zone, which already has the CSAM Tool enabled, so the delivery path is CF-cached and auto-scanned.

**Pre-Phase 1 verification**: confirm the exact CF Images URL structure with a custom subdomain (default `/cdn-cgi/imagedelivery/<account_hash>/<image_id>/<variant>`; CF Images also offers "Custom Image URLs" for a cleaner path); document the final format in the Version Pinning Decisions Log.

### CSAM Detection

**Cloudflare CSAM Scanning Tool** (free, zone-level): automatic fuzzy hash scan on cached images as they enter the cache, matched against NCMEC + partner NGO lists. On match: blocks the URL (HTTP 451) + daily email notification; Cloudflare files third-party reports to NCMEC automatically.

**Important: the CF CSAM Scanning Tool does NOT emit webhooks.** The downstream `/internal/csam-webhook` handler must be invoked by one of (full detail: `04-Architecture.md`):

- **Primary (MVP)**: admin reviews the CF email notification in the Admin Panel and triggers the handler manually by pasting the matched URL / image_id.
- **Automated Phase 2+**: a Cloudflare Worker attached to the `img.nearyou.id` route watches for `451 Unavailable For Legal Reasons` responses and POSTs to `/internal/csam-webhook`.
- **Alternative (deferred)**: a daily Cloud Run Job parsing the inbound email via IMAP or the email provider API.

**Pre-launch verification in Pre-Phase 1**:
1. Set up `img.nearyou.id` as a CNAME to the Cloudflare edge
2. Enable the CSAM Scanning Tool on the `nearyou.id` zone via Dashboard > Caching > Configuration
3. Verify email for match notifications
4. Upload sample legal test content via Cloudflare Images, request via `img.nearyou.id/...`, verify it appears in the CF Images dashboard and scan log
5. Document the SOP (incl. the admin-triggered handler invocation flow)

### Explicit Content Upfront

Google Cloud Vision Safe Search at upload time blocks adult/violent content before it enters the cache. Pay-per-image (verify actual rate in Pre-Phase 1).

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

> CSAM archive schema, Kominfo reporting SOP, encryption approach: `06-Security-Privacy.md`.

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
- Anomaly detection: delivery >5x baseline per user then auto-flag (baseline rolling 30-day per user; users <30 days use global avg)
- CSAM detection count (expected near-zero; any positive triggers urgent admin review)

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

**Image lifecycle**: hard-deleting a post cascade-deletes its image in the Cloudflare Images API; CSAM-flagged images stay in the archive 90 days (preservation obligation).
