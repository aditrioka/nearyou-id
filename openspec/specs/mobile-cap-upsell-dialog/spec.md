# mobile-cap-upsell-dialog Specification

## Purpose
`mobile-cap-upsell-dialog` is the shared daily-cap upsell dialog for `:mobile:app` — the `ui/components/` Material 3 `AlertDialog` (`DailyCapUpsellDialog`, per the canonical mockup frame 18 of `dev/mockups/nearyou-screens-mockup.html`, docs/11 § 2.8) shown when a Free-tier daily quota is hit. Its body copy is caller-supplied so the one component serves the future post / reply / chat caps (frame 18's caption declares the shared pattern); the like instantiation — its only consumer today, wired by `mobile-nearby-timeline` / `mobile-global-timeline` — passes the verbatim `docs/03-UX-Design.md` § Rate Limit Communication modal copy. The dialog derives its countdown from the 429's `Retry-After` (the like wire's only reset signal), renders hours+minutes, ticks down per minute via a monotonic delay (no wall-clock — a pure, restore-safe formatter), floors to one minute (a stripped/zero header never flash-dismisses), and auto-dismisses when the cap resets. The "Aktifkan Premium" CTA is a dismiss-only placeholder in v1 — the paywall destination is the deferred requirement tracked by GitHub issue [#235](https://github.com/aditrioka/nearyou-id/issues/235); the component holds no navigation reference and no rate-limit state of its own (show/hide is the host's one-shot state, docs/11 § 2.2).
## Requirements
### Requirement: The shared daily-cap upsell dialog renders per mockup frame 18

The mobile app SHALL ship a shared daily-cap upsell dialog composable (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/ui/components/DailyCapUpsellDialog.kt`, the docs/11 § 2.1 `ui/components/` package — shared by the Nearby and Global surfaces in this change). It SHALL be a Material 3 `AlertDialog` matching the canonical mockup (frame 18, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 § 2.8):

- **Title**: `stringResource(Res.string.cap_dialog_title)` — NEW string "Batas harian tercapai" (the frame-18 title; deliberately cap-generic because the frame's caption declares the same modal pattern for the future post/reply/chat caps).
- **Body**: a caller-supplied, already-formatted string — the parameterization is the reuse seam for those future caps. The LIKE instantiation (this change's only consumer) supplies `stringResource(Res.string.post_detail_likes_cap_upsell)` — the existing key whose value is the **verbatim** `docs/03-UX-Design.md:187` modal body ("Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam %1$s.") — formatted with the live countdown string (§ "The countdown derives from Retry-After and ticks to the reset").
- **Confirm button** (right): a filled `Button` labelled `stringResource(Res.string.cta_activate_premium)` — NEW string "Aktifkan Premium" (the docs/03:187 primary CTA), invoking a hoisted `onActivatePremium` callback (§ "Premium CTA navigates to the paywall").
- **Dismiss button** (left): a `TextButton` labelled `stringResource(Res.string.cta_close)` — the EXISTING "Tutup" key (the docs/03:187 secondary CTA), invoking a hoisted `onDismiss` callback. The dialog's `onDismissRequest` (scrim tap / back) SHALL behave as the dismiss button.

No hardcoded UI string literals SHALL appear in the component source (Compose Multiplatform Resources only); colors/typography SHALL come from `NearYouTheme` tokens (no literals); the component SHALL render correctly under both light and dark schemes. The component holds no navigation reference and no rate-limit state of its own — show/hide is owned by the host surface's state.

#### Scenario: The like instantiation renders the frame-18 dialog with the verbatim copy and both CTAs

- **GIVEN** the dialog composed with the like body (`post_detail_likes_cap_upsell` formatted with a countdown string) under `NearYouTheme`
- **WHEN** the rendered tree is inspected
- **THEN** it contains the `cap_dialog_title` text ("Batas harian tercapai"), the formatted verbatim body, a filled confirm button labelled "Aktifkan Premium", and a text dismiss button labelled "Tutup"

#### Scenario: Scrim/back dismissal behaves as Tutup

- **GIVEN** the dialog composed with recording `onDismiss` / `onActivatePremium` callbacks
- **WHEN** the dialog's `onDismissRequest` fires (scrim tap / back)
- **THEN** `onDismiss` is invoked exactly once AND `onActivatePremium` is not invoked

#### Scenario: No hardcoded strings and token-only styling

- **WHEN** inspecting `DailyCapUpsellDialog.kt`
- **THEN** every user-visible text resolves via `stringResource(Res.string.<name>)` AND the source contains no hex color literals (theme tokens only) AND the component renders without crash under `NearYouTheme` light and dark

### Requirement: The countdown derives from Retry-After and ticks to the reset

The dialog's countdown SHALL be driven by the rate-limited like's `retryAfterSeconds` (the `Retry-After` header value carried on `LikeOutcome.RateLimited` — the like wire's ONLY reset signal; `docs/03-UX-Design.md:186` mentions an `X-RateLimit-Reset` response header, but the shipped like endpoints send only `Retry-After`, which encodes the same per-user staggered WIB reset (`computeTTLToNextReset`, `docs/05-Implementation.md`) — a declared divergence, resolved without a backend change):

- The remaining time SHALL be formatted by a **pure commonMain formatter** (unit-testable without composing UI, no wall-clock dependency): minutes = the remaining seconds rounded **up** to the next full minute (the countdown never shows a zero-minute value while time remains); at ≥ 60 minutes it renders via NEW string `cap_countdown_hours_minutes` ("%1$d j %2$d mnt"); below 60 minutes via NEW string `cap_countdown_minutes` ("%1$d mnt") — matching frame 18's "14 j 19 mnt" treatment.
- A non-positive input SHALL be floored to one minute: the shipped client maps a 429 whose `Retry-After` is absent, stripped, or unparseable (e.g. proxy/CDN-rewritten to an HTTP-date) to `RateLimited(0)`, and the backend never legitimately sends < 1 s — so `retryAfterSeconds ≤ 0` renders the 1-minute treatment and the dialog MUST NOT auto-dismiss on entry (it ticks its one floored minute, then auto-dismisses). This mirrors the shipped post-detail banner's floor precedent (a 0/absent `Retry-After` still reads as a positive wait, never an instant flash-dismiss).
- While the dialog is shown, the rendered countdown SHALL tick **per minute** — decrementing via monotonic coroutine delay (NO wall-clock platform API), updating the formatted body — satisfying the `docs/03-UX-Design.md:185` "in-app modal countdown … realtime to the reset moment" mandate at minute granularity.
- When the remaining time reaches zero, the dialog SHALL auto-dismiss (invoke `onDismiss`): the cap has reset and the user can like again.

The post-detail cap **banner**'s coarse hour treatment (`post_detail_reset_hours`, "%1$d jam") is NOT changed by this capability — the banner and this dialog are distinct surfaces (declared divergence; aligning the banner is out of scope).

#### Scenario: Formatter renders hours+minutes and minutes-only

- **WHEN** the pure formatter is invoked with `retryAfterSeconds = 51540` and again with `1140`
- **THEN** it yields the `cap_countdown_hours_minutes` rendering for 14 j 19 mnt and the `cap_countdown_minutes` rendering for 19 mnt respectively

#### Scenario: Sub-minute remainders round up, never to zero

- **WHEN** the pure formatter is invoked with `retryAfterSeconds = 59` and with `3601`
- **THEN** the first yields the 1-minute rendering ("1 mnt") AND the second yields the 1-hour-1-minute rendering ("1 j 1 mnt") — remaining seconds always round UP to the next minute

#### Scenario: A zero Retry-After is floored, not flash-dismissed

- **GIVEN** the dialog shown with `retryAfterSeconds = 0` (the shipped client's mapping for an absent/stripped/unparseable `Retry-After`)
- **WHEN** the first frame renders
- **THEN** the body shows the 1-minute rendering ("1 mnt") AND the dialog does NOT auto-dismiss on entry (it remains shown until its floored minute elapses or the user dismisses)

#### Scenario: The shown dialog ticks down by the minute

- **GIVEN** the dialog shown with a countdown of 2 minutes under a test dispatcher
- **WHEN** one simulated minute elapses
- **THEN** the rendered body updates to the 1-minute rendering (the countdown is live, not static-at-open)

#### Scenario: Reaching zero auto-dismisses

- **GIVEN** the dialog shown with a countdown of 1 minute under a test dispatcher
- **WHEN** the final simulated minute elapses
- **THEN** `onDismiss` is invoked (the dialog closes itself — the cap has reset)

### Requirement: Test coverage for the dialog and countdown

The change SHALL ship: (1) a Robolectric `DailyCapUpsellDialogTest` (`mobile/app/src/androidUnitTest/...`, using the v2 ComposeUiTest API per docs/11 § 2.7) covering the verbatim like-body render with both CTAs, the scrim/back dismissal, the minute tick-down, the zero auto-dismiss, and the CTA/dismiss callback routing — ADDED to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (the established `*ScreenTest`-style exclusion; verify `:mobile:app:testDevReleaseUnitTest` still passes); (2) commonTest coverage for the pure countdown formatter (hours+minutes vs minutes-only split, round-up boundaries, the 51540 → "14 j 19 mnt" frame-18 fixture).

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `DailyCapUpsellDialogTest` and the countdown-formatter commonTest are discovered AND each documented behavior above corresponds to at least one `@Test`

#### Scenario: Dialog test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists the dialog test alongside the existing `*ScreenTest` exclusions AND `:mobile:app:testDevReleaseUnitTest` passes

### Requirement: Premium CTA navigates to the paywall

Tapping "Aktifkan Premium" SHALL invoke the hoisted `onActivatePremium` callback; every host surface SHALL wire that callback to push `PaywallRoute(entry = PaywallEntry.LIKE_CAP)` onto the root back stack (the `mobile-paywall` capability — mockup frame 17, `docs/03-UX-Design.md` § Paywall & Premium Disclosure) AND dismiss the dialog. The `DailyCapUpsellDialog` component itself SHALL remain navigation-free: it holds no back-stack reference and performs NO navigation side-effect of its own — it only invokes the hoisted `onActivatePremium` and `onDismiss`. The navigation is owned by the host surface (the feed / post-detail surface that showed the dialog), keeping the component a pure, reusable presentation piece. This resolves the v1 dismiss-only placeholder: the CTA is no longer a dead-end, and GitHub issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) `mobile-paywall-screen` (the former `follow-up`) is closed by the change that introduces this behavior.

#### Scenario: The Premium CTA invokes the hoisted callback and the host pushes the paywall

- **GIVEN** the dialog shown on a feed surface whose host wires `onActivatePremium` over a test root back stack
- **WHEN** the "Aktifkan Premium" button is tapped
- **THEN** `onActivatePremium` fires exactly once AND the host appends `PaywallRoute(entry = PaywallEntry.LIKE_CAP)` to the root back stack AND the dialog is dismissed

#### Scenario: The dialog component itself holds no navigation reference

- **WHEN** inspecting `DailyCapUpsellDialog.kt`
- **THEN** the component holds no back-stack reference and performs no navigation itself — it only invokes the hoisted `onActivatePremium` / `onDismiss` (navigation is the host's responsibility)

#### Scenario: Scrim/back dismissal still behaves as Tutup and does not navigate

- **GIVEN** the dialog composed with recording `onDismiss` / `onActivatePremium` callbacks over a test root back stack
- **WHEN** the dialog's `onDismissRequest` fires (scrim tap / back)
- **THEN** `onDismiss` is invoked exactly once AND `onActivatePremium` is not invoked AND no `PaywallRoute` is appended to the back stack

