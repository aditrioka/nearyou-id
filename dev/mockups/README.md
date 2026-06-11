# dev/mockups — canonical UI reference boards

Static high-fidelity HTML mockups serving as the **canonical visual reference** for the NearYouID
mobile app (`:mobile:app`, Compose Multiplatform + Material 3). The binding rule lives in
[`docs/11-Engineering-Standards.md`](../../docs/11-Engineering-Standards.md) § 2.8: every
UI-affecting change (proposal **and** implementation — `/next-change`, `/opsx:apply`,
`mobile-ui-foundation`, or any other skill) consults the matching frame(s) before building.

## Files

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
- Typography is Plus Jakarta Sans, same as `NearYouTypography`.
- Every frame caption lists the **M3 components used**, cites the **governing doc/spec** per
  element (e.g. radius slider = docs/02 4-position 10/20/50/100 km; distance floor = 5 km), and
  tags the frame **"Sudah ada"** (shipped — copy uses the real `strings.xml` strings) or
  **"Usulan"** (proposed — needs a spec/backend before it can ship).
- Spacing follows a 4dp-grid intent. Treat sizes as dp intent, not pixel contracts — the M3
  component named in the caption is the source of truth for its metrics.

## How an AI session should consume them

1. **Render, don't read raw HTML.** Open the board in a browser / IDE preview, or capture frames
   via a browser screenshot tool (e.g. Playwright `file://` + screenshot) — whichever gives the
   clearest read of the frame. The agent picks the approach; the point is to *see* the frame.
2. **Locate the frame(s)** for your screen by number/title (list above).
3. **Read the caption** — it is the grounding layer: component mapping, spec citations,
   shipped-vs-proposed status, and deliberate deviations (e.g. asymmetric paddings that produce
   symmetric *visual* gaps are commented in the HTML).
4. **Translate to Compose Multiplatform idioms**, not literal CSS: the named M3 composable +
   `NearYouTheme` tokens (never hex literals), `stringResource` copy, the docs/11 § 2 contracts.
   Animations (shimmer/sweep/glow) map to `rememberInfiniteTransition` + animated `Brush` offsets —
   see the badge board's "Catatan implementasi" cards; honor reduce-motion.
5. **Precedence on conflict**: `openspec/specs/` + `docs/02/03` govern *behavior*; mockups govern
   *look and layout*. If they disagree, the spec wins — flag the divergence, don't silently follow
   the mockup.

## Updating the boards

- Keep frames grounded: any product claim added to a frame must cite its doc, or be tagged
  "Usulan" in the caption.
- When `NearYouColorScheme.kt` / `NearYouColors.kt` change, sync the CSS custom properties in both
  files (they are declared once per file, in the `.frame` / `.frame.dark` blocks).
- Synthetic data only — no real PII, no secrets (public-repo posture per CLAUDE.md).
