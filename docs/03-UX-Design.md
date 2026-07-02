# NearYouID - UX & Design

User experience flows, copy strategy, onboarding design, empty states, and interaction design decisions. Quoted strings are verbatim user-facing product copy in Bahasa Indonesia unless context indicates otherwise.

> **Status (2026-05-12).** **The spec source for mobile UX scaffolding work** — prescriptive UX copy + flows that the next 5+ mobile OpenSpec changes implement. `:mobile:app` is still the JetBrains Compose Multiplatform wizard scaffold, so the screens/copy below are not yet rendered to users; proposals cite this document when scaffolding each surface. Server contracts (endpoints, error codes, schema columns) match `docs/02-Product.md` § Status tags + `openspec/specs/`. Change-by-change menu: [`openspec/project.md`](../openspec/project.md) § Mobile + Admin Scaffolding Priority.

---

## UX Copy Strategy (Avoid Misinterpretation)

The app's location-based nature is ambiguous between "posts from this location" vs "people around you" — copy MUST be unambiguous at every touchpoint:

- Disambiguation copy "Post dari lokasi ini" (not "Orang di sekitar kamu"): `timeline_nearby_title` stays in the catalog but is **no longer rendered as a Nearby screen header** (amended 2026-06-08, `mobile-home-shell-redesign`: with Nearby a text tab inside Home, the separate header duplicated the **Beranda** section + **Sekitar** tab and re-applied the status-bar inset — the nested-Scaffold gap). Disambiguation now lives in the onboarding hint below + the per-card "Diposting dari {city}" context; implementing the relocated hint: GitHub issue [#204](https://github.com/aditrioka/nearyou-id/issues/204) `mobile-location-disambiguation-onboarding-hint` (`follow-up`).
- Post detail: "Diposting dari {city_name}, {relative_time}"
- Posts from an author who has since moved: NOT hidden, NOT updated — a post is a snapshot of the location at creation, forever.
- One-time onboarding hint: "NearYouID menampilkan post berdasarkan lokasi saat post dibuat, bukan lokasi terkini penulis" — now the **primary** anti-misinterpretation surface (was secondary reinforcement of the removed header).

---

## User Onboarding Flow

### First App Open

- Default tab: Global (read-only, no login); immediately shows content from Indonesia, scrollable through 10 posts
- At the 11th post: CTA "Login untuk lihat lebih banyak"

### Login Wall

Switching to Nearby/Following triggers it; Post/Like/Reply/Follow/Chat and viewing a profile all require login.

### Auth Flow

1. Android: "Masuk dengan Google" (primary; under the hood uses Android Credential Manager)
2. iOS: "Masuk dengan Apple" (primary)

> **Status (Mobile #3 `mobile-auth-google-signin-flow`, 2026-05):** iOS ships Google Sign-In as a substrate-proving stopgap; "Masuk dengan Apple" remains the eventual iOS primary (follow-up `mobile-auth-signin-apple-ios`), launch-required — App Store Review Guideline 4.8 mandates it when other social logins are offered.

On first login, the attestation check (Play Integrity / App Attest) runs automatically in the background; emulator/rooted rejection copy + manual-review fallback: § Attestation Rejection UX.

Backend verifies the ID token + attestation, issues a Ktor RS256 JWT (15 minutes) + refresh token (30 days, tagged with `family_id`) + Supabase HS256 JWT (1 hour).

**Account separation disclosure** (onboarding FAQ): "Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID".

### Age Gate Screen

After auth passes, before entering the app: input date of birth (date picker); age calculation server-side.

- **<18**: reject — "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas." — account not created. Policy + anti-bypass blocklist: `06-Security-Privacy.md` § Age Gate.
- **18+**: proceed directly to the next step

### Analytics & Tracking Consent Screen (UU PDP)

After the age gate, before location permission. Data-collected summary:

- "Bantu kami perbaiki aplikasi dengan data penggunaan anonim (Amplitude)"
- "Laporkan crash otomatis untuk perbaikan bug (Sentry)"
- "Iklan dapat disesuaikan dengan minat kamu (Google AdMob UMP)"

Per-category opt-in toggles (Analytics, Crash Reporting, Ads Personalization), default OFF with explanatory copy — analytics + ads personalization opt-in; crash reporting default ON, still declinable. Stored in `users.analytics_consent JSONB {analytics, crash, ads_personalization}`; changeable in Settings going forward (historical data deletion via the data export + delete account flow).

### Location Permission

- **Granularity**: Approximate for radius 10-20km (default); Precise only for a smaller radius or manual pick
- **Consent modal**: why location is needed, what data is collected, how often it's accessed (UU PDP)

### Permission Denial Fallback

- **Nearby**: not accessible — "Aktifkan lokasi untuk lihat postingan sekitar" + CTA deep link to Settings
- **Following**: remains accessible, but distance info is replaced with city name + post time
- **Global**: remains fully accessible, city name still shown

### Notification Permission (Android 13+, iOS All Versions)

POST_NOTIFICATIONS + UNNotification authorization runtime permission is requested at the first chat message sent/received, not at onboarding — contextual, higher conversion.

**FCM token registration**: after grant the client sends the token via `POST /api/v1/user/fcm-token`; re-register on first open after install, token refresh (SDK callback), device switch / reinstall, manual logout + re-login.

### Username Auto-Generate

Generated at the register step; shown as informational (not editable at signup). Reserved usernames (`reserved_usernames` table) are skipped automatically. Free users keep it permanently; Premium can customize later from Settings (§ Premium Username Customization (UX)).

**Onboarding copy**:
> "Username kamu: @{username}. Kamu bisa mengubah username nanti dengan berlangganan Premium."

### Empty State

- **Nearby is sparse**: "Area kamu belum ramai. Sementara lihat dari seluruh Indonesia dulu?" + button to switch to Global
- **Following empty**: direct user to Nearby/Global
- **Global empty** (edge case): loading skeleton "Sedang memuat postingan…"

---

## Paywall & Premium Disclosure

**Paywall disclosure**: the paywall screen shows features available NOW (no mention of image upload before it ships). Disclosure-window mandate, ToS clause, downgrade-flow internals — policy: `01-Business.md`; flow spec: `02-Product.md` § Privacy Downgrade Flow.

**Downgrade flow privacy flip**: downgrading to Free with a private profile does NOT auto-flip it. Send push + in-app banner:
> "Private profile akan jadi public dalam 72 jam. Tap untuk Premium ulang atau confirm switch public."

The banner countdown is personalized per user; during the 72h window the client treats the profile as private in every rendering path.

**Username on downgrade**: the custom username stays as last-set (not reverted); further changes disabled until Premium is renewed. In-app banner:
> "Username @{username} tetap milikmu. Untuk mengubahnya lagi, aktifkan Premium."

---

## Premium Username Customization (UX)

Feature rules: `02-Product.md` § Premium Username Customization.

### Entry Point

- Settings > Profil > "Ganti Username"
- Free user taps: paywall opens with copy "Ganti username adalah fitur Premium" + CTA "Aktifkan Premium"
- Premium user taps: enters the customization screen

### Customization Screen

- Current username + field for the new one; live availability probe (debounced 500ms) with inline validation
- Error states:
  - Reserved: "Username ini tidak tersedia. Coba username lain."
  - Collision: "Username ini sudah dipakai."
  - On release hold: "Username ini sedang dalam masa tahan. Coba lagi nanti."
  - Profanity/UU ITE soft flag: "Username ini akan ditinjau tim moderasi. Silakan pilih username lain atau tunggu hasil review."
  - Cooldown active: "Kamu bisa ganti username lagi pada {date}."

### Cooldown Messaging

After a username change, the entry point shows a disabled state with copy "Ganti username berikutnya tersedia dalam {countdown} hari." — personalized per user.

### Submit Confirmation

- Modal: "Ganti username dari @{old} menjadi @{new}? Username lama akan dilepas ke publik 30 hari setelah perubahan." Primary button "Ganti", secondary "Batal"
- Post-submit: toast "Username berhasil diganti" + the profile reloads

### 30-Day Release Hold Explanation

FAQ entry: "Setelah kamu ganti username, username lama akan ditahan selama 30 hari agar tidak langsung dipakai orang lain. Ini untuk melindungi kamu dari impersonasi."

### Downgrade Copy

Already documented above (banner on downgrade). No reversion.

### Post-MVP Privacy Note

@mentions, profile URLs, and chat references via the old username keep working while it remains in `username_history` during the 30-day hold; after release the handle is claimable by another user and historical references do not re-route to the original account.

---

## Notification Content (UX)

### Default Content Privacy

- Chat notification body: "Pesan baru" + sender username (NOT full content)
- Post interaction: full context ("{username} menyukai postingan kamu")

**Distance in push body**: not included (MVP) — staleness risk (60-second enqueue→delivery gap; the user could have moved); actual distance shows on app open.

### In-App Notification List

Backed by the `notifications` table (`05-Implementation.md`). Pull-to-refresh + infinite scroll; unread badge count in the tab bar; tapping deep-links to the target post/reply/profile and flips `read_at`.

**Notification type rendering**:
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

Toggle "Tampilkan preview pesan chat di notifikasi", default OFF; ON then body = full content truncated to 100 characters.

### Rate Limit Communication (UX)

- **FAQ**: "Kuota harian reset setiap hari sekitar jam 00:00-01:00 WIB. Waktu tepat bisa berbeda sedikit per akun."
- **In-app modal countdown**: personalized per user, realtime to the reset moment
- **Response header** `X-RateLimit-Reset`: user-specific reset timestamp
- **Free like-cap modal** (10/day cap hit): "Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam {countdown}." CTA: "Aktifkan Premium" primary, "Tutup" secondary.

---

## Attestation Rejection UX

**Rejection messaging**:
- Emulator/rooted device: "Aplikasi tidak dapat digunakan di perangkat ini" + fallback manual review link

**CGNAT-aware guest error** (both IP + fingerprint limits hit):
> "Terlalu banyak permintaan dari jaringan ini, coba WiFi lain atau login"

---

## Post Edit UX

- An edited post shows a "Diedit [relative time]" label; tapping it opens a "Riwayat edit" modal with the full chronological history
- Content version display: "Versi ke-N"
- Transactional error edge case (sub-microsecond collision): return 409 CONFLICT with "Coba lagi sebentar."

---

## Chat Context Card UX

**Edit history navigation**:
- Tap embed → post detail at the **current content version**, with banner "Post ini sudah di-edit setelah kamu chat" if current version ≠ snapshot version
- Tap "Riwayat edit" → modal of all content versions, the version at chat initiation highlighted

**Hard-delete state**: snapshot still renders + permanent label "Post ini sudah dihapus" + author label "Akun Dihapus" if the author is tombstoned.

---

## Block User UX

- Kebab menu (post, reply, profile page): "Blokir @{username}"
- Confirmation modal: "Blokir @{username}? Kalian berdua tidak akan saling melihat post, profil, atau bisa memulai percakapan baru." Red "Blokir" button, secondary "Batal"
- Post-block: toast "Pengguna telah diblokir"
- Settings > Privasi > "Daftar Diblokir": list with unblock button

---

## Report UX

- Kebab menu (post, reply, profile page): "Laporkan"
- Chat message: **long-press** a received message bubble → "Laporkan" (the chat bubble has no kebab; the long-press is the chat-surface idiom — same reason picker + note + outcome). Own/sent and already-redacted messages expose no report affordance. (`docs/06` § Report System is canonical for the four reportable surfaces incl. chat message.)
- Reason picker: "Spam", "Ujaran kebencian (SARA)", "Pelecehan", "Konten dewasa", "Misinformasi", "Lainnya"
- Optional 200-char note (placeholder: "Jelaskan lebih detail jika perlu")
- Post-submit: toast "Laporan terkirim. Tim moderasi akan meninjau."
- Reporters get no visibility into the review outcome (prevents retaliation); if an account-level action (suspension / permanent ban) was taken, the reported party gets an appeal path **from the banned sign-in screen** (suspension is session-terminating — it revokes the user's tokens — so the appeal entry lives at sign-in, not Settings; see the `content-moderation-appeal` capability)

---

## Search UX (Premium)

- Search bar at the top of the Timeline (Premium only; Free users see an upsell on tap)
- Autocomplete: username from the top 5 results (pg_trgm)
- Query runs on Enter or after a 500ms typing pause
- Result: post grid (20 per page), "Lihat lebih banyak" for pagination
- Empty state: "Tidak ada hasil untuk '{query}'. Coba kata kunci lain."
- 60 queries/hour rate limit: modal "Kamu sudah mencapai batas pencarian. Reset dalam X menit."

---

## Profile / Account UX

### Account Deletion

- "Hapus Akun" button in Settings; 30-day grace period (user can restore)
- Post deletion tombstone: "Akun Dihapus" placeholder in posts/chats/replies

### Account Recovery (None by Design)

Onboarding + FAQ disclose explicitly: losing your Google/Apple account means losing your NearYouID account; no alternative email/phone/password recovery flow.

### Data Export

- Settings > "Unduh Data Saya"
- Confirmation: "Export akan dikirim sebagai link download via email dalam 7 hari. Link berlaku 24 jam setelah dikirim."
- Sent via Resend with an R2 signed URL

### Suspension UX

Suspension is **session-terminating and enforced at the auth boundary** — login does NOT succeed while `is_banned = TRUE`, and there is no in-app read-only mode or write-endpoint countdown modal (an earlier design; superseded by the shipped `auth-signin` / `mobile-appeal` model).

When `users.is_banned = TRUE` AND `users.suspended_until` is non-null (7-day suspension): sign-in returns 403 `account_banned` carrying a limited-scope appeal token and the `suspended_until` expiry timestamp. The sign-in screen shows "Akun kamu sedang ditangguhkan sementara. Kamu bisa mengajukan banding." (`signin_error_suspended`) with the "Ajukan banding" in-app appeal entry (`mobile-appeal`). Any session that was live when the suspension landed is cut off within one ~15-minute access-token TTL: every authenticated request is 403'd by the per-request `AuthPlugin` `is_banned` gate, and `POST /api/v1/auth/refresh` refuses a banned/suspended owner a new access token. Auto-unban when the daily worker flips the flag.

When `users.is_banned = TRUE` AND `users.suspended_until IS NULL` (permanent): same sign-in 403 `account_banned`, but with no `suspended_until` value in the body (presence-vs-absence is the client's suspension-vs-permanent discriminator). The sign-in screen shows "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru." (`signin_error_banned`) and routes to the support path — no in-app appeal entry.

---

## iOS Privacy Manifest (UX Implication)

When an iOS 17+ user opens the app, the system can show a popup for required reasons APIs. The app MUST have a `PrivacyInfo.xcprivacy` file declaring:
- Data collected (linked to user, used for tracking, etc.)
- Required Reason APIs used (e.g. `NSUserDefaults`, `FileTimestamp`)

User-facing: no extra consent beyond what's already handled in the Analytics & Tracking Consent Screen. The manifest file is an App Store requirement, not an in-app UX element.

---

## Material 3 Design System / Foundation

> **Canonical substrate (added 2026-06-08, `mobile-home-shell-redesign`).** Each authenticated `:mobile:app` screen so far shipped as an isolated change with no shared layout/loading/icon/copy contract, so screens reinvented their own Scaffold, loading state, dot-icons, and copy language — and drifted (a status-bar gap, lists that didn't fill, double loading indicators, broken pull-to-refresh, invisible selected labels, mixed-language tabs). This section is the durable, screen-agnostic substrate every subsequent screen (profile → following → chat → search → settings) inherits. The authoritative spec home is the `mobile-design-system` capability (`openspec/specs/mobile-design-system/`); this is the human-readable canonical reference. **Future mobile screen changes MUST cite this section rather than re-deriving these rules.**

### Single Scaffold + edge-to-edge insets

The authenticated surface applies window insets in exactly **one** place — the app section shell's `Scaffold` (`AppShellScreen`), running edge-to-edge (`enableEdgeToEdge()` in the Android entry + the shell `Scaffold`'s `contentWindowInsets`). For shell-rendered section surfaces, the shell `Scaffold`'s `topBar` slot is the only place a top app bar may exist — as of `mobile-timeline-card-redesign` it hosts the Home section's **centered brand-logo `CenterAlignedTopAppBar`** (`logo_brand_light`/`logo_brand_dark` per scheme, pinned, mockup frames 1/19); root-stack **overlay** screens (e.g. `PostDetailScreen`'s back bar) own their own chrome. Every composable inside the shell body — section content, the Home feed tab host, each feed/timeline screen — is **inset-free**: it declares NO `Scaffold` and NO `TopAppBar` of its own and consumes the shell's `innerPadding` (`Modifier.padding(innerPadding)` + `Modifier.consumeWindowInsets(innerPadding)`). A Compose `Scaffold` *applies but does not consume* insets, so nesting Scaffolds re-adds the status-bar inset (the gap) and re-owns content padding (the list won't fill) — the bug this rule prevents.

### Material 3 icon set per destination

Bottom-nav sections, the composer FAB, and post-card affordances use **real Material 3 icon glyphs** — NOT brand-tinted placeholder dots — delivered as bundled XML vector drawables in `:shared:resources` (the `logo_brand_*.xml` idiom) accessed via `painterResource(Res.drawable.*)`, so the app ships exactly the glyphs it uses without the heavy `material-icons-extended` artifact. Canonical glyphs: bottom-nav **Home / Notifications / Person** (outlined when unselected, filled when selected); composer **add** (`+`); composer privacy-note **shield** (`verified_user`, decorative — added by `mobile-mockup-visual-conformance` per mockup frame 6); post-card **location** (place/pin), **like** (outlined↔filled), **reply** (chat bubble) — the post **time** renders as plain TEXT in the card's identity header (the clock glyph was removed by `mobile-timeline-card-redesign`, per mockup frames 1/19). **Exception — feed tabs are text-only**: a `PrimaryTabRow` text label under the M3 underline indicator, NO icon and NO dot (matching the operator's X / Niche-style references).

### Label visibility

`NavigationBarItem` and feed `Tab` labels are visible in **both** the selected and unselected states — never a custom color that can collapse to the background. A selected bottom-nav or tab item never renders an invisible (background-colored) label.

> **Note (M3 1.4+ · brand-identity selected nav):** Material 3 1.4 resolves the bare `NavigationBarItemDefaults.colors()` default `selectedTextColor` to `secondary` and `indicatorColor` to `secondaryContainer`. `NearYouColorScheme` now defines those as genuine **readable accents** (the M3-conformant tonal scheme — `secondary = #595D72` — replacing the earlier neutralized near-white `#EEF0F4` that rendered the selected label invisible on-device, 2026-06-08), so the bare default is no longer unsafe. The shell nonetheless applies explicit tokens via `nearYouNavigationBarItemColors()` (`AppShellScreen.kt`) as a deliberate **brand-identity** choice — the selected bottom-nav state uses the **PRIMARY (brand cobalt) family** rather than M3's default `secondary` accent: a `primaryContainer` indicator pill, a `primary` selected icon (brand cobalt), an `onSurface` selected label, and `onSurfaceVariant` for the unselected state. **Any future screen adding a `NavigationBar`/`NavigationRail` SHOULD reuse `nearYouNavigationBarItemColors()` for a consistent brand-cobalt selected state.** Feed `Tab`s use the bare default (the `Tab` selected color is `primary` = brand cobalt). Enforced by WCAG-contrast assertions in `AppShellScreenTest` (selected label ≥ 4.5:1; selected icon vs pill ≥ 3:1), not mere inequality checks.

### Canonical list loading and refresh pattern

Every scrollable list surface distinguishes **initial load** (no content yet) from **refresh** (a reload while content already exists), and never displays two progress indicators at once:
- **Initial load** → a skeleton/placeholder presentation with at most one in-content indicator; the pull-to-refresh spinner is NOT shown. The state model carries an explicit `isInitialLoad` flag (not a generic `inFlight`) that the pure projection maps to the skeleton.
- **Refresh of existing content** → the `PullToRefreshBox` indicator shows over the **retained** content list; the scrollable stays mounted (the projection keeps returning `Content`), and the in-content initial-load indicator is NOT shown. The ViewModel keeps the prior outcome and flips only a separate `isRefreshing` flag, which feeds `PullToRefreshBox(isRefreshing = …)`.
- The **empty / error / rate-limit** (non-`Content`) states are rendered inside a scrollable container (a single-item `LazyColumn`) so the pull-to-refresh gesture is recognized from them too; a refresh from a non-`Content` state retains that state (it does not flip back to the initial-load skeleton).

### Single-language Bahasa Indonesia

All user-facing labels are a single language — Bahasa Indonesia — with no EN/ID mix within a surface, all sourced via `:shared:resources` `stringResource(Res.string.<name>)`. (Feed tab labels are `Sekitar` / `Mengikuti` / `Global` to match the `Beranda` / `Notifikasi` / `Profil` bottom-nav sections.) Runtime user-selectable language switching is **deferred** (GitHub issue [#203](https://github.com/aditrioka/nearyou-id/issues/203) `mobile-localization-language-switching`, label `follow-up`) — this rule is satisfied by normalizing the catalog copy, not by an in-app language picker.
