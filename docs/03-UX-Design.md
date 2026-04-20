# NearYouID - UX & Design

User experience flows, copy strategy, onboarding design, empty states, and interaction design decisions.

---

## UX Copy Strategy (Avoid Misinterpretation)

Because the app's location-based nature is ambiguous between "posts from this location" vs "people around you", copy MUST be unambiguous at every touchpoint. All user-facing strings below are kept in Bahasa Indonesia:

- Timeline header: "Post dari lokasi ini" (not "Orang di sekitar kamu")
- Post detail: "Diposting dari {city_name}, {relative_time}"
- Posts from an author who has since moved: NOT hidden, NOT updated to the new location. A post is a snapshot of the location at creation, forever.
- One-time onboarding hint: "NearYouID menampilkan post berdasarkan lokasi saat post dibuat, bukan lokasi terkini penulis"

---

## User Onboarding Flow

### First App Open

- Default tab: Global (read-only, no login)
- User immediately sees content from Indonesia, can scroll through 10 posts
- At the 11th post: user-facing CTA "Login untuk lihat lebih banyak"

### Login Wall

- Switching to Nearby/Following triggers the login wall
- Post/Like/Reply/Follow/Chat all require login
- Viewing a profile requires login

### Auth Flow

1. Android: "Masuk dengan Google" (primary, user-facing; under the hood uses Android Credential Manager)
2. iOS: "Masuk dengan Apple" (primary, user-facing)

On first login, the attestation check (Play Integrity / App Attest) runs automatically in the background. Emulator/rooted detection rejects with user-facing "Aplikasi tidak dapat digunakan di perangkat ini" + fallback manual review link.

Backend verifies the ID token + attestation, issues a Ktor RS256 JWT (15 minutes) + refresh token (30 days, tagged with `family_id`) + Supabase HS256 JWT (1 hour).

**Account separation disclosure** (in the onboarding FAQ): "Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID".

### Age Gate Screen

After auth passes but before entering the app:
- Input date of birth (date picker)
- Age calculation server-side
- **<18**: reject with an explicit user-facing message "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas.", account is not created. Identifier hash is added to the `rejected_identifiers` blocklist (see `06-Security-Privacy.md`) to prevent DOB-shopping bypass.
- **18+**: proceed directly to the next step

### Analytics & Tracking Consent Screen (UU PDP)

After the age gate, before location permission:

- Short summary of data collected (user-facing):
  - "Bantu kami perbaiki aplikasi dengan data penggunaan anonim (Amplitude)"
  - "Laporkan crash otomatis untuk perbaikan bug (Sentry)"
  - "Iklan dapat disesuaikan dengan minat kamu (Google AdMob UMP)"
- Opt-in toggle per category (Analytics, Crash Reporting, Ads Personalization)
- Default: OFF with explanatory copy (opt-in for analytics and ads personalization; crash reporting default ON, user can still decline)
- Preference stored in `users.analytics_consent JSONB {analytics, crash, ads_personalization}`
- Settings page allows the user to change the toggle (applies going forward; historical data deletion via the data export + delete account flow)

### Location Permission

- **Granularity**:
  - Approximate: for radius 10-20km (default)
  - Precise: only if the user activates a smaller radius or picks manually
- **Consent modal**: explain why location is needed, what data is collected, and how often it's accessed (UU PDP)

### Permission Denial Fallback

- **Nearby**: not accessible. Screen shows user-facing "Aktifkan lokasi untuk lihat postingan sekitar" + CTA deep link to Settings
- **Following**: remains accessible, but distance info is not shown. Replaced with city name + post time
- **Global**: remains fully accessible, city name still shown

### Notification Permission (Android 13+, iOS All Versions)

POST_NOTIFICATIONS + UNNotification authorization runtime permission is requested when the first chat message is sent/received, not at onboarding. Contextual, with higher conversion.

**FCM token registration**: after permission is granted, client sends the FCM token via `POST /api/v1/user/fcm-token`. Client must re-register when:
- App is first opened after install
- FCM token refresh event fires (SDK callback)
- User switches device / reinstalls
- Manual logout + re-login

### Username Auto-Generate

Generated at the register step. Displayed to the user as informational (not editable at signup). Reserved usernames (from the `reserved_usernames` table) are skipped automatically. Free users keep this auto-generated username permanently; Premium users can customize it later from Settings (see the Premium Username Customization section below).

**Onboarding copy** (user-facing Bahasa Indonesia):
> "Username kamu: @{username}. Kamu bisa mengubah username nanti dengan berlangganan Premium."

### Empty State

- **Nearby is sparse**: user-facing "Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?" + button to switch to Global
- **Following empty**: direct user to Nearby/Global
- **Global empty** (edge case): loading skeleton user-facing "Sedang memuat postingan…"

---

## Paywall & Premium Disclosure

**Paywall disclosure mandatory Months 1-5**: the paywall screen shows features available NOW (does not mention image upload, since the image feature only ships in Month 6).

**ToS clause** (user-facing): "Fitur Premium dapat berubah atau ditambahkan seiring waktu."

**Downgrade flow privacy flip**: users who downgrade to Free and have a private profile are NOT automatically flipped. Send push + in-app banner (user-facing):
> "Private profile akan jadi public dalam 72 jam. Tap untuk Premium ulang atau confirm switch public."

Banner countdown is personalized from `users.privacy_flip_scheduled_at`. Re-subscribing to Premium before the deadline cancels the flip (server clears the column on `premium_active` transition). An hourly worker flips `private_profile_opt_in = FALSE` once the deadline elapses. During the 72h window the client treats the profile as private in every rendering path. See `05-Implementation.md` Privacy Flip Worker for the full mechanism.

**Username on downgrade**: the custom username stays as last-set (not reverted). Further changes disabled until Premium is renewed. In-app banner (user-facing):
> "Username @{username} tetap milikmu. Untuk mengubahnya lagi, aktifkan Premium."

---

## Premium Username Customization (UX)

### Entry Point

- Settings > Profil > "Ganti Username" (user-facing)
- Free user taps the entry: paywall opens with copy "Ganti username adalah fitur Premium" + CTA "Aktifkan Premium"
- Premium user taps: enters the customization screen

### Customization Screen

- Shows current username + field for the new username
- Live availability probe (`GET /api/v1/username/check?candidate=...`, debounced 500ms, rate-limited 3/day)
- Inline validation: length 3 to 30, charset regex `^[a-z0-9][a-z0-9_.]*[a-z0-9_]$` (must start with a letter or digit, end with a letter/digit/underscore, dots allowed only in the middle), no `..` consecutive
- Error states (user-facing):
  - Reserved: "Username ini tidak tersedia. Coba username lain."
  - Collision: "Username ini sudah dipakai."
  - On release hold: "Username ini sedang dalam masa tahan. Coba lagi nanti."
  - Profanity/UU ITE soft flag: "Username ini akan ditinjau tim moderasi. Silakan pilih username lain atau tunggu hasil review."
  - Cooldown active: "Kamu bisa ganti username lagi pada {date}."

### Cooldown Messaging

- If the user just changed their username, the entry point shows disabled state with copy (user-facing): "Ganti username berikutnya tersedia dalam {countdown} hari."
- Personalized per user; matches the `users.username_last_changed_at + 30 days` server-side enforcement

### Submit Confirmation

- Modal (user-facing): "Ganti username dari @{old} menjadi @{new}? Username lama akan dilepas ke publik 30 hari setelah perubahan."
- Primary button "Ganti", secondary "Batal"
- Post-submit: toast "Username berhasil diganti" + the profile reloads

### 30-Day Release Hold Explanation

- FAQ entry (user-facing): "Setelah kamu ganti username, username lama akan ditahan selama 30 hari agar tidak langsung dipakai orang lain. Ini untuk melindungi kamu dari impersonasi."

### Downgrade Copy

Already documented above (banner on downgrade). No reversion.

### Post-MVP Privacy Note

@mentions, profile URLs, and chat references using the old username continue to work as far as the old username remains in `username_history` within the 30-day hold; after release, the old handle is free to be claimed by another user, and historical references will not re-route to the original account.

---

## Notification Content (UX)

### Default Content Privacy

- Chat notification body: user-facing "Pesan baru" + sender username (NOT full content)
- Post interaction: full context (user-facing "{username} menyukai postingan kamu")

**Distance in push body**: not included (MVP). Reason: staleness risk (60-second gap between enqueue and delivery, user could have moved). User sees the actual distance when they open the app.

### In-App Notification List

Backed by the `notifications` table (see `05-Implementation.md`). Pull-to-refresh + infinite scroll. Unread badge count in the tab bar. Tapping a notification deep-links to the target post/reply/profile and flips `read_at`.

**Notification type rendering** (user-facing strings):
- `post_liked`: "{username} menyukai postingan kamu"
- `post_replied`: "{username} membalas postingan kamu"
- `followed`: "{username} mulai mengikuti kamu"
- `chat_message`: "Pesan baru dari {username}"
- `subscription_billing_issue`: "Ada masalah pembayaran. Perbarui sebelum {grace_end_at} untuk menjaga Premium."
- `subscription_expired`: "Premium kamu telah berakhir."
- `post_auto_hidden`: "Salah satu postingan kamu disembunyikan untuk ditinjau tim moderasi."
- `account_action_applied`: "Akun kamu menerima tindakan moderasi. Lihat email pemberitahuan."
- `data_export_ready`: "Data export kamu siap diunduh."
- `chat_message_redacted`: "Sebuah pesan dalam percakapan dihapus oleh tim moderasi."
- `privacy_flip_warning`: "Private profile akan jadi public dalam {countdown}. Tap untuk Premium ulang."
- `username_release_scheduled`: "Username lama kamu akan dilepas pada {released_at}."
- `apple_relay_email_changed`: "Email bayangan Apple kamu sudah diperbarui."

### User Toggle in Settings

- User-facing toggle "Tampilkan preview pesan chat di notifikasi", default OFF
- ON then body = full content truncated to 100 characters

### Rate Limit Communication (UX)

- **FAQ** (user-facing): "Kuota harian reset setiap hari sekitar jam 00:00-01:00 WIB. Waktu tepat bisa berbeda sedikit per akun."
- **In-app modal countdown**: personalized per user, realtime to the reset moment
- **Response header** `X-RateLimit-Reset`: user-specific reset timestamp
- **Free tier like limit modal** (Free user hits the 10/day like cap, user-facing): "Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam {countdown}." CTA: "Aktifkan Premium" primary, "Tutup" secondary.

---

## Attestation Rejection UX

**Rejection messaging** (user-facing):
- Emulator/rooted device: "Aplikasi tidak dapat digunakan di perangkat ini" + fallback manual review link

**CGNAT-aware guest error** (when both IP + fingerprint limits are hit, user-facing):
> "Terlalu banyak permintaan dari jaringan ini, coba WiFi lain atau login"

---

## Post Edit UX

- An edited post shows a "Diedit [relative time]" label (user-facing)
- Tapping the label opens a user-facing "Riwayat edit" modal with the full chronological history
- Content version display: user-facing "Versi ke-N"
- Transactional error edge case (sub-microsecond collision): return 409 CONFLICT with user-facing message "Coba lagi sebentar."

---

## Chat Context Card UX

**Edit history navigation**:
- Tap embed then redirect to the post detail at the **current content version**, with banner (user-facing) "Post ini sudah di-edit setelah kamu chat" if current version ≠ snapshot version
- Tap "Riwayat edit" then modal of all content versions; the version at chat initiation is highlighted

**Hard-delete state**: snapshot still renders + permanent label (user-facing) "Post ini sudah dihapus" + author label "Akun Dihapus" if author is tombstoned.

---

## Block User UX

- Kebab menu on post, reply, and profile page: user-facing "Blokir @{username}"
- Confirmation modal (user-facing): "Blokir @{username}? Kalian berdua tidak akan saling melihat post, profil, atau bisa memulai percakapan baru."
- Red "Blokir" button, secondary "Batal" button (user-facing)
- Post-block: user-facing toast "Pengguna telah diblokir"
- Settings > Privasi > "Daftar Diblokir" (user-facing): list with unblock button

---

## Report UX

- Kebab menu on post, reply, and profile page: user-facing "Laporkan"
- Reason picker (user-facing): "Spam", "Ujaran kebencian (SARA)", "Pelecehan", "Konten dewasa", "Misinformasi", "Lainnya"
- Optional 200-char note (user-facing placeholder: "Jelaskan lebih detail jika perlu")
- Post-submit: user-facing toast "Laporan terkirim. Tim moderasi akan meninjau."
- No visibility into the review outcome for reporters (prevents retaliation). Appeal path is available for the reported party via Settings if action was taken.

---

## Search UX (Premium)

- Search bar at the top of the Timeline (Premium only; Free users see an upsell on tap)
- Autocomplete: username from the top 5 results (pg_trgm)
- Query runs when the user presses Enter or stops typing for 500ms
- Result: post grid (20 per page), user-facing "Lihat lebih banyak" for pagination
- Empty state (user-facing): "Tidak ada hasil untuk '{query}'. Coba kata kunci lain."
- 60 queries/hour rate limit: user-facing modal "Kamu sudah mencapai batas pencarian. Reset dalam X menit."

---

## Profile / Account UX

### Account Deletion

- "Hapus Akun" button (user-facing) in Settings
- 30-day grace period: user can restore
- Post deletion tombstone: "Akun Dihapus" (user-facing) placeholder in posts/chats/replies

### Account Recovery (None by Design)

- Disclosure in onboarding + FAQ explicit: losing your Google/Apple account means losing your NearYouID account
- No alternative email/phone/password recovery flow

### Data Export

- Settings > user-facing "Unduh Data Saya"
- Confirmation (user-facing): "Export akan dikirim sebagai link download via email dalam 7 hari. Link berlaku 24 jam setelah dikirim."
- Email sent via Resend with an R2 signed URL

### Suspension UX

When `users.is_banned = TRUE` AND `users.suspended_until > NOW()`: login succeeds but all write endpoints return 403 with a user-facing countdown modal: "Akun kamu dalam suspensi sementara sampai {date}. Alasan: lihat email pemberitahuan." The user can still read Global content. Auto-unban when the daily worker flips the flag.

When `users.is_banned = TRUE` AND `users.suspended_until IS NULL` (permanent): login screen shows user-facing "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."

---

## iOS Privacy Manifest (UX Implication)

When an iOS 17+ user opens the app, the system can show a popup for required reasons APIs. The app MUST have a `PrivacyInfo.xcprivacy` file declaring:
- Data collected (linked to user, used for tracking, etc.)
- Required Reason APIs used (e.g. `NSUserDefaults`, `FileTimestamp`)

User-facing: no extra consent beyond what's already handled in the Analytics & Tracking Consent Screen. The manifest file is an App Store requirement, not an in-app UX element.
