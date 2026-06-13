# Design: mobile-settings-screen

## Context

The mobile app has shipped the authenticated feed surfaces (Nearby/Global timelines, post creation, post detail, the bottom-nav section shell, analytics-consent onboarding) and has four more critical-path screens in flight in parallel sessions (profile #245, following-feed #246, chat #247, search #248). Settings is the **last** live-menu row (`openspec/project.md` § Mobile-First to Full-Demo Priority, row #5) and the account-management + UU-PDP surface. The canonical visual reference is `dev/mockups/nearyou-screens-mockup.html` **frame 16 ("Pengaturan")**, rendered/consulted per docs/11 § 2.8 at proposal time, with sections AKUN / PREMIUM / PRIVASI / LAINNYA.

Backend backing already exists for the three functional pieces: `GET /api/v1/blocks` + `DELETE /api/v1/blocks/{user_id}` (`BlockRoutes.kt`, the `user-blocking` capability), `PATCH /api/v1/user/consent` (`ConsentRoutes.kt`, the `analytics-consent-update` capability), and the client-side `SecureTokenStore`. Several frame-16 rows have **no** backend yet (edit-profile, Premium username/private-profile/hide-distance, Premium tenure, manage-subscription) — they are Phase 4 / DESIGN.

Constraints: the 16 code-level invariants (`openspec/project.md` § Coding Conventions — CMP Resources strings, the `@allow-privacy-write` gate, PII discipline), docs/11 § 2 mobile contracts (Pattern Registry, the loading/empty/error state contract, the single-Scaffold rule), the one-PR-per-change lifecycle, and the mobile-first priority. Mobile-only — no migration, no backend code, no new library pin.

## Goals / Non-Goals

**Goals:**

- Ship the Settings surface **mockup-faithful** (frame 16): all rows rendered, backed rows wired, deferred rows visible but non-writing — no dead controls that silently do nothing harmful, and no controls that perform a partial/forbidden write.
- Wire the three backed functions: block-list management (list + unblock), analytics-consent (submit + local-snapshot init), logout.
- Reach Settings from a gear on PR #245's profile surface, with the cross-PR dependency made explicit and the merge sequenced after #245.
- Hold PII discipline (no blocked-user UUID rendered/logged; no token/sub/consent-body logging) and stay off the `@allow-privacy-write` invariant surface (the Premium privacy toggles are deferred/non-writing).

**Non-Goals:**

- Account deletion, data export, suspension-countdown UI, notification chat-preview toggle (no backend; absent from frame 16 — deferred follow-ups).
- Edit-profile, Premium username change, Premium tenure journey, manage-subscription, Premium private-profile + hide-distance toggles (Phase 4 / DESIGN — rendered as deferred rows, not wired).
- A server consent-read endpoint (the read-state gap is mirrored locally; the true-server-read is a follow-up).
- Backend changes of any kind; per-tab `NavDisplay` back stacks (issue #189).

## Decisions

### D1 — `mobile-settings` is a NEW capability owning three root-stack NavKeys

Settings + its two stateful sub-surfaces (block list, consent) get one owner spec. `SettingsRoute` / `BlockedUsersRoute` / `ConsentSettingsRoute` are `@Serializable data object`s registered in the `AppNavSerialization` polymorphic `SerializersModule` (iOS back-stack saveability, per `mobile-app-scaffold`), carrying NO identity payload (identity lives in the token). They append onto the **root** back stack — the `PostDetailRoute`/`PostCreationRoute` precedent — so they overlay the bottom `NavigationBar` without a per-tab `NavDisplay` back stack (still deferred, issue #189). Alternative rejected: a single `SettingsRoute` with nested in-screen navigation for block-list/consent — that re-derives navigation inside a screen instead of using the Nav3 back stack the design system mandates.

### D2 — `SettingsScreen` owns its own Scaffold (the pushed-overlay precedent)

`mobile-design-system` says "the app shell owns a single Scaffold and window insets." That requirement governs shell-rendered **section** surfaces (Home/Notifikasi/Profil under the shell). Root-stack **overlay** screens pushed above the shell — `PostDetailScreen` (its own back bar, per audit 06-#4) and `PostCreationScreen` — keep their own chrome; `SettingsScreen` follows that precedent with its own `Scaffold` + "Pengaturan" `TopAppBar` + `arrow_back`. This is consistent with the existing reading of the rule, so **no `mobile-design-system` delta is needed** (the rule already scopes to shell sections; the overlay precedent is established). The block-list and consent sub-screens likewise own their back-bar chrome.

### D3 — Mockup-faithful shell: render every row; partition backed vs deferred; no dead/forbidden writes

The operator chose the mockup-faithful shell over a lean backed-only screen. Every frame-16 row renders. Backed rows (Pengguna diblokir, Privasi & data, Ketentuan & kebijakan privasi, Keluar) navigate/act. Deferred rows (Edit profil, Ganti username, Perjalanan Premium, Kelola langganan, Profil privat, Sembunyikan jarak) render their mockup icon/title/subtitle but activation surfaces a non-trapping "Segera hadir" affordance and performs **no backend write and no navigation to a missing destination**. The critical sub-rule: the deferred "Profil privat" / "Sembunyikan jarak" toggles **do not** issue any `UPDATE users` / privacy-flag write — this keeps the change off the `@allow-privacy-write: worker|user_settings` invariant surface entirely (when those toggles are genuinely wired in a later change, that change adds the annotated writer + its tests). Alternative rejected: lean backed-only screen (operator preferred mockup parity); alternative rejected: wiring the Premium toggles now (no backend; would ship a forbidden/partial write or a dead toggle).

### D4 — Block-list data seam mirrors the established ApiClient → Repository → sealed-Outcome pattern

Per docs/11 § 2.6 (the shape the timeline + consent seams use): `BlockedUsersApiClient` (HTTP boundary on the shared `Auth { bearer }` client), `BlockedUsersRepository` (DTO→domain + sealed outcome), sealed `BlockedUsersOutcome` (success / terminal-401 / retryable-error). The DTO matches the shipped `BlockRoutes.kt` wire **field-for-field**: `BlockListResponse { blocks: List<BlockListItem>, nextCursor: String? }`, `BlockListItem { userId: String, username: String, displayName: String, isPremium: Boolean, createdAt: String }` — all bare camelCase, `ignoreUnknownKeys`. `userId` is held only as the `DELETE /api/v1/blocks/{userId}` path parameter — never rendered as text, never logged (PII discipline; only display name + @handle are shown). `nextCursor` is threaded for load-more; if pagination wiring is deferred, the first page still renders and the deferral is a follow-up. No second networking pattern (anti-patchwork, Pattern Registry).

### D5 — Consent read-state gap: device-local last-submitted snapshot, V2 fallback

There is **no** server consent-read endpoint (`analytics-consent-update` ships `PATCH` only; the onboarding screen issues no GET and hardcodes the V2 defaults). So `ConsentSettingsScreen` cannot read the authoritative server value. It initializes from a **device-local last-submitted snapshot** (written on each successful `PATCH`), falling back to the V2 column defaults (analytics OFF, crash ON, ads OFF) when none exists. Persistence mechanism: reuse the app's existing on-device key-value storage (the same platform-secure/preferences store family that backs `SecureTokenStore`) so **no new library pin** is introduced; the consent snapshot is non-secret preference data. The reused mobile consent seam (`consent/ConsentApiClient`, `ConsentFlow`, `ConsentOutcome`) is the networking path — **no second consent path** (anti-patchwork). The true-server-state read gap (and durable cross-session reliability) is a follow-up, related to the deferred reliable-persist hardening issue [#198](https://github.com/aditrioka/nearyou-id/issues/198). Alternative rejected: re-defaulting to V2 on every settings entry (would silently misrepresent a user who changed consent — a UU-PDP-relevant misstatement); alternative rejected: blocking the consent settings sub-screen until a GET ships (drops a backed write the operator wants).

### D6 — Logout is a client-side token wipe + `replaceAll` to sign-in

A confirmation dialog (`stringResource` copy) → `SecureTokenStore` clear → navigation `replaceAll` to the sign-in surface (authenticated back stack cleared; back gesture can't return). No server call: the MVP relies on the client token wipe; server-side `token_version` rotation is a separate concern not gated by this UI. Mirrors the existing token-write discipline (the screen touches `SecureTokenStore` only for the clear).

### D7 — Entry gear on PR #245's profile surface; sequenced after #245; captured as task, not delta

The settings gear belongs on the profile surface (`mobile-profile`, PR #245), per the operator's choice. `mobile-profile` is **not yet** in `openspec/specs/` (#245 is still a proposal), so writing a MODIFIED delta against it now would reference a non-existent base spec and muddy `openspec validate --strict`. Instead: the `mobile-settings` capability OWNS the `SettingsRoute` + push contract (the entry-contract requirement), and the actual gear control is added on `ProfileScreen` as an **integration task sequenced after #245 merges** (tasks.md § 6). If #245's profile surface lands first (expected), the gear wiring is a small additive edit on its screen; if scheduling slips, the settings PR rebases onto merged #245 before adding the gear. This keeps the two PRs' spec deltas disjoint (parallel-merge-safe) while making the dependency explicit.

### D8 — Live-menu-vs-mockup divergence (account-deletion, suspension) resolved toward the mockup

The project.md live-menu row #5 text lists "account-deletion entry, suspension-countdown UI"; the canonical mockup frame 16 contains **neither** (it has the Premium/PRIVASI/LAINNYA rows + logout instead). Per CLAUDE.md "the canonical visual reference is the mockup board … on behavior conflicts, specs/docs win over mockups" — here it is not a behavior conflict but an **entry-set** question (which rows exist), which the mockup governs. Additionally, neither has a backend endpoint (account deletion "ships later" per `AuthPlugin.kt`; suspension is surfaced only at the auth/write-403 boundary with no client read). Shipping either now would be a dead control. Resolution: defer both as `follow-up` issues; record the divergence here so a reviewer/operator can reconcile the live-menu text later if desired. This is a bucket-(c)-style divergence surfaced to the operator at proposal time (the operator confirmed the mockup-faithful scope).

## Standards conformance (docs/11)

Builds on the Pattern Registry unchanged — **no deviations, so no docs/11 Pattern Registry amendment is needed**:

- **State holder** (§ 2.2): `viewModel { }` NavEntry-scoped view models (`BlockedUsersViewModel`, the consent settings VM, the settings/logout holder) — the established pattern, no new state mechanism.
- **Navigation** (§ 2.3): Nav3 serializable NavKeys on the root back stack + polymorphic `SerializersModule` registration — the shipped pattern.
- **Data layer** (§ 2.6): `ApiClient → Repository → sealed Outcome` seam for the block list; wire-truth taken from the shipped `BlockRoutes.kt` DTOs; the consent path reuses the existing seam (no fork).
- **Components / theming** (§ 2.1, § 2.8): theme tokens only, Material 3 icons, CMP Resources strings; mockup frame 16 consulted per § 2.8.
- **Single-Scaffold rule**: preserved (D2 — overlay screens keep their own chrome; the rule governs shell sections).
- **DoD** (§ 5): manual verification on Android emulator + iOS simulator against frame 16 (light/dark), block-list list+unblock round-trip, consent submit, logout — evidence attached to the PR body at implementation time.

No `gradle/libs.versions.toml` change → the propose-time substrate WebSearch/version-currency gate is N/A.
