# mobile-cap-upsell-dialog — Delta Specification

## ADDED Requirements

### Requirement: The shared daily-cap upsell dialog renders per mockup frame 18

The mobile app SHALL ship a shared daily-cap upsell dialog composable (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/ui/components/DailyCapUpsellDialog.kt`, the docs/11 § 2.1 `ui/components/` package — shared by the Nearby and Global surfaces in this change). It SHALL be a Material 3 `AlertDialog` matching the canonical mockup (frame 18, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 § 2.8):

- **Title**: `stringResource(Res.string.cap_dialog_title)` — NEW string "Batas harian tercapai" (the frame-18 title; deliberately cap-generic because the frame's caption declares the same modal pattern for the future post/reply/chat caps).
- **Body**: a caller-supplied, already-formatted string — the parameterization is the reuse seam for those future caps. The LIKE instantiation (this change's only consumer) supplies `stringResource(Res.string.post_detail_likes_cap_upsell)` — the existing key whose value is the **verbatim** `docs/03-UX-Design.md:187` modal body ("Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam %1$s.") — formatted with the live countdown string (§ "The countdown derives from Retry-After and ticks to the reset").
- **Confirm button** (right): a filled `Button` labelled `stringResource(Res.string.cta_activate_premium)` — NEW string "Aktifkan Premium" (the docs/03:187 primary CTA), invoking a hoisted `onActivatePremium` callback (§ "Premium CTA navigation is deferred").
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
- While the dialog is shown, the rendered countdown SHALL tick **per minute** — decrementing via monotonic coroutine delay (NO wall-clock platform API), updating the formatted body — satisfying the `docs/03-UX-Design.md:185` "in-app modal countdown … realtime to the reset moment" mandate at minute granularity.
- When the remaining time reaches zero, the dialog SHALL auto-dismiss (invoke `onDismiss`): the cap has reset and the user can like again.

The post-detail cap **banner**'s coarse hour treatment (`post_detail_reset_hours`, "%1$d jam") is NOT changed by this capability — the banner and this dialog are distinct surfaces (declared divergence; aligning the banner is out of scope).

#### Scenario: Formatter renders hours+minutes and minutes-only

- **WHEN** the pure formatter is invoked with `retryAfterSeconds = 51540` and again with `1140`
- **THEN** it yields the `cap_countdown_hours_minutes` rendering for 14 j 19 mnt and the `cap_countdown_minutes` rendering for 19 mnt respectively

#### Scenario: Sub-minute remainders round up, never to zero

- **WHEN** the pure formatter is invoked with `retryAfterSeconds = 59` and with `3601`
- **THEN** the first yields the 1-minute rendering ("1 mnt") AND the second yields the 1-hour-1-minute rendering ("1 j 1 mnt") — remaining seconds always round UP to the next minute

#### Scenario: The shown dialog ticks down by the minute

- **GIVEN** the dialog shown with a countdown of 2 minutes under a test dispatcher
- **WHEN** one simulated minute elapses
- **THEN** the rendered body updates to the 1-minute rendering (the countdown is live, not static-at-open)

#### Scenario: Reaching zero auto-dismisses

- **GIVEN** the dialog shown with a countdown of 1 minute under a test dispatcher
- **WHEN** the final simulated minute elapses
- **THEN** `onDismiss` is invoked (the dialog closes itself — the cap has reset)

### Requirement: Premium CTA navigation is deferred

Tapping "Aktifkan Premium" SHALL invoke the hoisted `onActivatePremium` callback; in this change every host surface SHALL wire that callback to dismiss the dialog ONLY (the operator-authorized placeholder — no paywall screen exists). The CTA MUST NOT push a route, mutate any back stack, or perform any navigation side-effect in this change (no dead navigation). GitHub issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) `mobile-paywall-screen` (label `follow-up`) tracks the paywall destination; the future paywall change SHALL MODIFY this requirement to navigate to the paywall (mockup frame 17, `docs/03-UX-Design.md` § Paywall & Premium Disclosure).

#### Scenario: The Premium CTA invokes the hoisted callback and the v1 wiring only dismisses

- **GIVEN** the dialog shown on a feed surface with the v1 wiring
- **WHEN** the "Aktifkan Premium" button is tapped
- **THEN** `onActivatePremium` fires exactly once AND the dialog is dismissed AND no `PostDetailRoute`/paywall/other entry is appended to any back stack (no navigation side-effect)

#### Scenario: Follow-up issue tracks the paywall deferral

- **WHEN** inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** GitHub issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) (label `follow-up`) tracks `mobile-paywall-screen`, including rewiring this CTA

### Requirement: Test coverage for the dialog and countdown

The change SHALL ship: (1) a Robolectric `DailyCapUpsellDialogTest` (`mobile/app/src/androidUnitTest/...`, using the v2 ComposeUiTest API per docs/11 § 2.7) covering the verbatim like-body render with both CTAs, the scrim/back dismissal, the minute tick-down, the zero auto-dismiss, and the CTA/dismiss callback routing — ADDED to the `mobile/app/build.gradle.kts` Release-variant test-exclude list (the established `*ScreenTest`-style exclusion; verify `:mobile:app:testDevReleaseUnitTest` still passes); (2) commonTest coverage for the pure countdown formatter (hours+minutes vs minutes-only split, round-up boundaries, the 51540 → "14 j 19 mnt" frame-18 fixture).

#### Scenario: Test classes exist and are discoverable

- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `DailyCapUpsellDialogTest` and the countdown-formatter commonTest are discovered AND each documented behavior above corresponds to at least one `@Test`

#### Scenario: Dialog test is excluded from the Release variant

- **WHEN** inspecting `mobile/app/build.gradle.kts`
- **THEN** the Release-variant `tasks.withType<Test>()` exclude block lists the dialog test alongside the existing `*ScreenTest` exclusions AND `:mobile:app:testDevReleaseUnitTest` passes
