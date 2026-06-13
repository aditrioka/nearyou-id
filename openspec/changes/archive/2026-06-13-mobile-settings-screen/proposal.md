# Proposal: mobile-settings-screen

## Why

Settings is the **last item on the mobile critical-path live menu** (`openspec/project.md` § Mobile-First to Full-Demo Priority, row #5) — the account-management + UU-PDP surface. Once it lands alongside the in-flight profile ([#245](https://github.com/aditrioka/nearyou-id/pull/245)), following-feed ([#246](https://github.com/aditrioka/nearyou-id/pull/246)), chat ([#247](https://github.com/aditrioka/nearyou-id/pull/247)), search ([#248](https://github.com/aditrioka/nearyou-id/pull/248)), post-detail (#159) and bottom-nav (#162), the authenticated core loop is demoable end-to-end and the project hits the documented flip trigger out of mobile-first priority (`project.md` § "Trigger to flip out of mobile-first priority").

The backing already exists for the three functional pieces — block-list management (`GET /api/v1/blocks` + `DELETE /api/v1/blocks/{user_id}`, the `user-blocking` capability), analytics-consent submit (`PATCH /api/v1/user/consent`, the `analytics-consent-update` capability), and logout (client-side `SecureTokenStore` wipe). This is **mobile-only**: NO Flyway migration, NO backend code, NO new library pin. The canonical visual reference is `dev/mockups/nearyou-screens-mockup.html` **frame 16 ("Pengaturan")** (binding per docs/11 § 2.8).

## What Changes

A NEW `mobile-settings` capability that ships the Settings surface **mockup-faithful** (every frame-16 row rendered; backed rows wired, deferred rows visible-but-non-writing per the operator's scope decision):

**Navigation + surface:**

- Three `@Serializable` parameterless NavKeys — `SettingsRoute`, `BlockedUsersRoute`, `ConsentSettingsRoute` — registered in `NavKeys.kt` + the `AppNavSerialization` polymorphic `SerializersModule` (iOS back-stack saveability), pushed onto the **root** back stack so the surfaces overlay the section `NavigationBar` (the `PostDetailRoute` precedent — no per-tab back stack).
- `SettingsScreen` — a pushed root-stack overlay owning its **own** M3 `Scaffold` + "Pengaturan" app bar + `arrow_back` (the `PostDetailScreen` precedent; reconciles with the design-system single-Scaffold rule, which governs *section* surfaces — see design D2). Body = the grouped frame-16 list with section headers **AKUN / PREMIUM / PRIVASI / LAINNYA**.
- **Entry point**: a settings gear on PR #245's profile surface pushes `SettingsRoute`. The capability owns the route + push contract; the gear control is wired on the profile surface as an integration step **sequenced after #245 merges** (design D7).

**Backed, fully-functional rows:**

- **PRIVASI > "Pengguna diblokir"** — `BlockedUsersScreen` lists the viewer's outbound blocks (`GET /api/v1/blocks`, cursor-paginated) and unblocks (`DELETE /api/v1/blocks/{user_id}`). New data seam `BlockedUsersApiClient → BlockedUsersRepository → sealed BlockedUsersOutcome` (mirrors the timeline/consent seam). Loading / empty ("Belum ada pengguna yang diblokir") / error states; row shows display name + @handle, never the UUID; 401 → sign-in.
- **PRIVASI > "Privasi & data"** — `ConsentSettingsScreen` **reuses the existing mobile consent seam** (`consent/ConsentApiClient`, `ConsentFlow`, `ConsentOutcome` from `mobile-analytics-consent`); three toggles submit via `PATCH /api/v1/user/consent`. Read-state gap (no GET endpoint) handled by a device-local last-submitted snapshot, falling back to the V2 safe defaults (analytics OFF, crash ON, ads OFF).
- **LAINNYA > "Keluar"** — confirmation dialog → wipes `SecureTokenStore` → `replaceAll` to sign-in (no server call).
- **LAINNYA > "Ketentuan & kebijakan privasi"** — opens the static policy URL.

**Deferred-but-visible rows (mockup parity; non-writing "Segera hadir"; each a follow-up issue):** AKUN > "Edit profil" (no profile-write endpoint), AKUN > "Ganti username" ✦ (Premium DESIGN), PREMIUM > "Perjalanan Premium" (Phase 4 tenure), PREMIUM > "Kelola langganan" (Phase 4 billing), PRIVASI > "Profil privat" ✦ + "Sembunyikan jarak" ✦ (Premium DESIGN — **no privacy-flag write is performed**, deliberately staying off the `@allow-privacy-write` invariant surface).

**Explicitly out of scope (not in frame 16, no backend):** account deletion ("Hapus Akun"), data export ("Unduh Data Saya"), suspension-countdown UI, notification chat-preview toggle — each deferred as a `follow-up` issue. (The live-menu-row-#5 text mentioned account-deletion + suspension; the canonical mockup governs the visible entry set and omits them — divergence documented in design.md.)

## Capabilities

### New Capabilities

- `mobile-settings`: the Settings surface contract — the three root-stack NavKeys + profile-gear entry, the frame-16 grouped list, the backed block-list / consent / logout / legal rows, the consent local-snapshot init with V2 fallback, the deferred-row no-dead-write rule, the block-list data seam, NavEntry-scoped state holders, the test trio, and the explicit out-of-scope deferrals.

### Modified Capabilities

- None. The entry gear lives on the `mobile-profile` capability (PR #245); to keep proposal validation clean while #245 is still a proposal, the gear-wiring is captured as a design Decision + a sequenced integration task rather than a MODIFIED delta against a not-yet-merged spec.

## Impact

- **Mobile**: new `screens/settings/**` (`SettingsScreen`, `BlockedUsersScreen`, `ConsentSettingsScreen` + view models + UI-state projections), new `block/BlockedUsersApiClient`/`BlockedUsersRepository`/`BlockedUsersOutcome` data seam, `NavKeys.kt` (+ 3 NavKeys, polymorphic registration), `AppEntryProvider` (root-stack push wiring), the Koin module (+ settings bindings), a device-local consent-snapshot holder (reusing existing storage, no new pin — design D5), `:shared:resources` strings (section headers, row titles/subtitles, empty/error/coming-soon/logout copy, handle format, contentDescriptions). Tests: commonTest (DTO parse, projections, snapshot init, Koin), Robolectric `*ScreenTest`s (+ Release-variant exclude), an iOS flow test.
- **Backend**: none (mobile-only; all three functions back onto shipped endpoints).
- **Docs**: none load-bearing (the live-menu-vs-mockup divergence is recorded in this change's design.md; no canonical doc claim is contradicted).
- **Cross-PR**: sequenced to merge **after** PR #245 (`mobile-profile-screen`), whose profile surface hosts the settings gear. Footprint is otherwise disjoint from the in-flight mobile claims (no shared migration, no shared screen file) → parallel-merge-safe.
- **Wire/compat**: additive client-only; existing endpoints unchanged; `ignoreUnknownKeys` on the new block-list DTO.
