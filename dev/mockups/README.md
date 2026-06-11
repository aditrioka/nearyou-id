# dev/mockups — canonical UI reference boards

Static high-fidelity HTML mockups serving as the **canonical visual reference** for the NearYouID
mobile app (`:mobile:app`, Compose Multiplatform + Material 3) **and the admin panel**
(`backend/ktor` `/admin/*`, Pebble + HTMX + vendored CSS). The binding rules live in
[`docs/11-Engineering-Standards.md`](../../docs/11-Engineering-Standards.md) — § 2.8 (mobile) and
§ 3.6 (admin): every UI-affecting change (proposal **and** implementation — `/next-change`,
`/opsx:apply`, `mobile-ui-foundation`, or any other skill) consults the matching frame(s) before
building.

## Files

- **`nearyou-admin-mockup.html`** — the 23-frame **admin panel** board (desktop frames, light-only),
  the canonical UI/UX reference for ALL admin features — shipped and planned. Five sections:
  - *A · Shell, akses & dasbor* (1–3): login (Argon2id + TOTP), app shell + sidebar nav + session
    card + logout, Operational Dashboard.
  - *B · Moderasi inti* (4–9): Report Queue card-based triage + in-row resolution, **4b** narrow-width
    snapshot (the responsive contract), User Moderation lookup + suspend/unban, full User
    Management, Audit Log, Post Edit History, Chat Redaction.
  - *C · Anti-abuse & keamanan* (10–14): Rejected Identifiers (+ clear action #190), Attestation
    Review, Block Registry, CSAM Log + handler + Kominfo workflow, Account Security (WebAuthn + sessions).
  - *D · Lifecycle & UU PDP* (15–17): Hard Delete Queue, Data Export Queue, Privacy Flip Monitor.
  - *E · Premium, growth & konfigurasi* (18–22): Subscription Grace, Referral Manual Grant, Feature
    Flags, Reserved Usernames Editor, Premium Username Change Oversight.
- **`nearyou-screens-mockup.html`** — the 19-frame screen board, grouped in four sections:
  - *Main screen* (1–4): Beranda (Sekitar feed + radius slider), Pesan, Profil, Notifikasi — the 4
    bottom-nav sections.
  - *Layar pushed* (5–9): ruang chat 1:1 (embed `embedded_post_snapshot`), composer, detail
    postingan + balasan, edit profil, bottom sheet "Perjalanan Premium".
  - *Onboarding, langganan & pengaturan* (10–18): onboarding 3 slide, masuk, gerbang usia,
    persetujuan analitik, pengaturan, paywall, upsell saat kena batas.
  - *Dark theme* (19): Beranda dengan `NearYouColorScheme.dark`.
- **`nearyou-premium-tenure-badges.html`** — companion concept board for the premium **tenure**
  badge system (docs/01 § Premium Tenure Counter): 5 tier (Perunggu → Berlian), live CSS animations
  as motion reference, placement decisions (including the rejected avatar-ring and the adopted
  tier-colored **name shimmer**), and Compose implementation notes.

## Fidelity guarantees (what makes these "canonical")

- Color tokens are copied verbatim from `shared/resources` `NearYouColorScheme.kt` (light + dark)
  and `NearYouColors.kt` (coral `locationPin`, amber `premiumBadge`, semantic status colors).
  The admin board uses the same light tokens + the brand logo vector (light-only for MVP).
- Typography is Plus Jakarta Sans, same as `NearYouTypography`.
- Every frame caption lists the **M3 components used**, cites the **governing doc/spec** per
  element (e.g. radius slider = docs/02 4-position 10/20/50/100 km; distance floor = 5 km), and
  tags the frame **"Sudah ada"** (shipped — copy uses the real `strings.xml` strings) or
  **"Usulan"** (proposed — needs a spec/backend before it can ship).
- Spacing follows a 4dp-grid intent. Treat sizes as dp intent, not pixel contracts — the M3
  component named in the caption is the source of truth for its metrics.
- **Admin board specifics**: "Sudah ada" frames mirror the real fields/columns/actions of the
  shipped `templates/admin/*.peb` (the *styling* is the target — the live panel is intentionally
  unstyled pre-board); "Usulan" frames cite `docs/07-Operations.md` § Core Features /
  `docs/08-Roadmap-Risk.md` Phase 3.5 / the tracking `follow-up` issue. A third tag **"Sebagian"**
  marks frames mixing both. In-frame copy is English (mirrors shipped templates); captions are
  Indonesian. Frame 4b encodes the **responsive contract** (fluid CSS, sidebar drawer, disclosure
  filters, card-based queues, `overflow-x` tables) — all other frames are fixed-width snapshots
  of it.

## How an AI session should consume them

1. **Render, don't read raw HTML.** Open the board in a browser / IDE preview, or capture frames
   via a browser screenshot tool (e.g. Playwright `file://` + screenshot) — whichever gives the
   clearest read of the frame. The agent picks the approach; the point is to *see* the frame.
2. **Locate the frame(s)** for your screen by number/title (list above).
3. **Read the caption** — it is the grounding layer: component mapping, spec citations,
   shipped-vs-proposed status, and deliberate deviations (e.g. asymmetric paddings that produce
   symmetric *visual* gaps are commented in the HTML).
4. **Translate to the surface's idioms**, not literal CSS:
   - *Mobile boards* → Compose Multiplatform: the named M3 composable + `NearYouTheme` tokens
     (never hex literals), `stringResource` copy, the docs/11 § 2 contracts. Animations
     (shimmer/sweep/glow) map to `rememberInfiniteTransition` + animated `Brush` offsets — see the
     badge board's "Catatan implementasi" cards; honor reduce-motion.
   - *Admin board* → Pebble templates + HTMX fragment swaps (keep the no-JS fallback discipline) +
     vendored vanilla CSS: lift the design tokens from the board's `.frame` CSS-custom-property
     block into the panel stylesheet; fluid layout per the frame-4b responsive contract. No client
     framework, no CDN assets, no inline styles in production templates (docs/11 § 3.6).
5. **Precedence on conflict**: `openspec/specs/` + `docs/02/03` govern *behavior*; mockups govern
   *look and layout*. If they disagree, the spec wins — flag the divergence, don't silently follow
   the mockup.

## Updating the boards

- Keep frames grounded: any product claim added to a frame must cite its doc, or be tagged
  "Usulan" in the caption.
- When `NearYouColorScheme.kt` / `NearYouColors.kt` change, sync the CSS custom properties in all
  three files (declared once per file, in the `.frame` / `.frame.dark` blocks).
- Admin board: when a shipped `templates/admin/*.peb` changes its fields/columns/actions, update
  the matching "Sudah ada" frame in the same PR; when an "Usulan" feature ships, retag its frame
  and align it with the as-built template.
- Synthetic data only — no real PII, no secrets (public-repo posture per CLAUDE.md).
