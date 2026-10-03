## MODIFIED Requirements

### Requirement: The shared daily-cap upsell dialog renders per mockup frame 18

The mobile app SHALL ship a shared daily-cap upsell dialog composable (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/ui/components/DailyCapUpsellDialog.kt`, the docs/11 § 2.1 `ui/components/` package). It SHALL be a Material 3 `AlertDialog` matching the canonical mockup (frame 18, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 § 2.8):

- **Title**: `stringResource(Res.string.cap_dialog_title)` — "Batas harian tercapai" (the frame-18 title; deliberately cap-generic because the frame's caption declares the same modal pattern for the post / reply / chat caps).
- **Body**: a caller-supplied, already-formatted string — the parameterization is the reuse seam. The dialog SHALL be the ONE upsell surface for every Free daily cap, each host supplying its cap's body formatted with the live countdown string (§ "The countdown derives from Retry-After and ticks to the reset"):
  - **like** (10/day — the feeds and post-detail): `post_detail_likes_cap_upsell`, the **verbatim** `docs/03-UX-Design.md` § Rate Limit Communication like-cap modal body ("Kamu sudah menggunakan 10 like hari ini. Upgrade ke Premium untuk like tanpa batas, atau tunggu reset dalam %1$s.");
  - **reply** (20/day — post-detail): `post_detail_reply_cap_upsell` ("Kamu sudah menggunakan 20 balasan hari ini. Upgrade ke Premium untuk balas tanpa batas, atau tunggu reset dalam %1$s.");
  - **post** (10/day — the composer): `post_create_cap_upsell` ("Kamu sudah membuat 10 postingan hari ini. Upgrade ke Premium untuk posting tanpa batas, atau tunggu reset dalam %1$s.");
  - **chat** (50/day — the chat thread and the share-to-chat picker): `chat_cap_upsell` ("Kamu sudah mengirim 50 pesan hari ini. Upgrade ke Premium untuk chat tanpa batas, atau tunggu reset dalam %1$s.").
  
  The reply / post / chat copy SHALL also be recorded in `docs/03-UX-Design.md` § Rate Limit Communication alongside the like copy, so all four are canonical.
- **Confirm button** (right): a filled `Button` labelled `stringResource(Res.string.cta_activate_premium)` — "Aktifkan Premium" (the docs/03 primary CTA), invoking a hoisted `onActivatePremium` callback (§ "Premium CTA navigates to the paywall").
- **Dismiss button** (left): a `TextButton` labelled `stringResource(Res.string.cta_close)` — the EXISTING "Tutup" key (the docs/03 secondary CTA), invoking a hoisted `onDismiss` callback. The dialog's `onDismissRequest` (scrim tap / back) SHALL behave as the dismiss button.

No hardcoded UI string literals SHALL appear in the component source (Compose Multiplatform Resources only); colors/typography SHALL come from `NearYouTheme` tokens (no literals); the component SHALL render correctly under both light and dark schemes. The component holds no navigation reference and no rate-limit state of its own — show/hide is owned by the host surface's state. No surface SHALL render a second, parallel cap surface (e.g. an inline cap banner) for the same `429`.

#### Scenario: The like instantiation renders the frame-18 dialog with the verbatim copy and both CTAs

- **GIVEN** the dialog composed with the like body (`post_detail_likes_cap_upsell` formatted with a countdown string) under `NearYouTheme`
- **WHEN** the rendered tree is inspected
- **THEN** it contains the `cap_dialog_title` text ("Batas harian tercapai"), the formatted verbatim body, a filled confirm button labelled "Aktifkan Premium", and a text dismiss button labelled "Tutup"

#### Scenario: Each cap supplies its own body through the same component

- **GIVEN** the dialog hosted on post-detail for a reply `429`, on the composer for a post `429`, and on the chat thread for a send `429`
- **WHEN** each dialog renders
- **THEN** each shows the same `cap_dialog_title` and CTA pair AND the body is respectively `post_detail_reply_cap_upsell`, `post_create_cap_upsell`, and `chat_cap_upsell` formatted with the countdown

#### Scenario: Scrim/back dismissal behaves as Tutup

- **GIVEN** the dialog composed with recording `onDismiss` / `onActivatePremium` callbacks
- **WHEN** the dialog's `onDismissRequest` fires (scrim tap / back)
- **THEN** `onDismiss` is invoked exactly once AND `onActivatePremium` is not invoked

#### Scenario: No hardcoded strings and token-only styling

- **WHEN** inspecting `DailyCapUpsellDialog.kt`
- **THEN** every user-visible text resolves via `stringResource(Res.string.<name>)` AND the source contains no hex color literals (theme tokens only) AND the component renders without crash under `NearYouTheme` light and dark

### Requirement: The countdown derives from Retry-After and ticks to the reset

The dialog's countdown SHALL be driven by the rate-limited outcome's `retryAfterSeconds` — the `Retry-After` header value the host's repository carries on its `RateLimited` outcome (`LikeOutcome.RateLimited`, `ReplyPostOutcome.RateLimited`, `PostCreationOutcome.RateLimited`, `SendOutcome.RateLimited`). It is the cap endpoints' ONLY reset signal. `docs/03-UX-Design.md` § Rate Limit Communication mentions an `X-RateLimit-Reset` response header, but the shipped cap endpoints send only `Retry-After`, which encodes the same per-user staggered WIB reset (`computeTTLToNextReset`, `docs/05-Implementation.md`). This is a declared divergence, resolved without a backend change.

- The remaining time SHALL be formatted by a **pure commonMain formatter** (unit-testable without composing UI, no wall-clock dependency): minutes = the remaining seconds rounded **up** to the next full minute (the countdown never shows a zero-minute value while time remains); at ≥ 60 minutes it renders via `cap_countdown_hours_minutes` ("%1$d j %2$d mnt"); below 60 minutes via `cap_countdown_minutes` ("%1$d mnt") — matching frame 18's "14 j 19 mnt" treatment.
- A non-positive input SHALL be floored to one minute. The shipped clients map a 429 whose `Retry-After` is absent, stripped, or unparseable (e.g. proxy/CDN-rewritten to an HTTP-date) to `RateLimited(0)`, and the backend never legitimately sends < 1 s. So `retryAfterSeconds ≤ 0` renders the 1-minute treatment and the dialog MUST NOT auto-dismiss on entry: it ticks its one floored minute, then auto-dismisses.
- While the dialog is shown, the rendered countdown SHALL tick **per minute** — decrementing via monotonic coroutine delay (NO wall-clock platform API), updating the formatted body — satisfying the `docs/03-UX-Design.md` § Rate Limit Communication "in-app modal countdown … realtime to the reset moment" mandate at minute granularity.
- When the remaining time reaches zero, the dialog SHALL auto-dismiss (invoke `onDismiss`): the cap has reset and the user can act again.

The former post-detail cap **banner** and its coarse hour treatment (`post_detail_reset_hours`, "%1$d jam") are retired. The post-detail like and reply caps now use this dialog and its minute countdown, so there is no longer a second countdown format.

#### Scenario: Formatter renders hours+minutes and minutes-only

- **WHEN** the pure formatter is invoked with `retryAfterSeconds = 51540` and again with `1140`
- **THEN** it yields the `cap_countdown_hours_minutes` rendering for 14 j 19 mnt and the `cap_countdown_minutes` rendering for 19 mnt respectively

#### Scenario: Sub-minute remainders round up, never to zero

- **WHEN** the pure formatter is invoked with `retryAfterSeconds = 59` and with `3601`
- **THEN** the first yields the 1-minute rendering ("1 mnt") AND the second yields the 1-hour-1-minute rendering ("1 j 1 mnt") — remaining seconds always round UP to the next minute

#### Scenario: A zero Retry-After is floored, not flash-dismissed

- **GIVEN** the dialog shown with `retryAfterSeconds = 0` (the shipped clients' mapping for an absent/stripped/unparseable `Retry-After`)
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

#### Scenario: The retired banner countdown no longer exists

- **WHEN** inspecting `strings.xml` and the post-detail UI-state source
- **THEN** there is no `post_detail_reset_hours` key AND no `resetHours(...)` projection AND no `PostDetailBanner` member for a like or reply cap

### Requirement: Premium CTA navigates to the paywall

Tapping "Aktifkan Premium" SHALL invoke the hoisted `onActivatePremium` callback. Every host surface SHALL wire that callback to dismiss the dialog AND push `PaywallRoute(entry = <the cap's entry>)` onto the root back stack (the `mobile-paywall` capability — mockup frame 17, `docs/03-UX-Design.md` § Paywall & Premium Disclosure). The entry SHALL name the cap that fired:

| Cap | Hosts | Entry |
|---|---|---|
| like | the three feeds, post-detail | `PaywallEntry.LIKE_CAP` |
| reply | post-detail | `PaywallEntry.REPLY_CAP` |
| post | the composer | `PaywallEntry.POST_CAP` |
| chat | the chat thread, the share-to-chat picker | `PaywallEntry.CHAT_CAP` |

The `DailyCapUpsellDialog` component itself SHALL remain navigation-free: it holds no back-stack reference and performs NO navigation side-effect of its own — it only invokes the hoisted `onActivatePremium` and `onDismiss`. The navigation is owned by the host surface, keeping the component a pure, reusable presentation piece. (This resolved the v1 dismiss-only placeholder — issue [#235](https://github.com/aditrioka/nearyou-id/issues/235) — and is extended to every cap by issue [#493](https://github.com/aditrioka/nearyou-id/issues/493).)

#### Scenario: The Premium CTA invokes the hoisted callback and the host pushes the paywall

- **GIVEN** the dialog shown on a feed surface whose host wires `onActivatePremium` over a test root back stack
- **WHEN** the "Aktifkan Premium" button is tapped
- **THEN** `onActivatePremium` fires exactly once AND the host appends `PaywallRoute(entry = PaywallEntry.LIKE_CAP)` to the root back stack AND the dialog is dismissed

#### Scenario: Each cap host pushes its own entry

- **GIVEN** the cap dialog raised on post-detail by a reply `429`, on the composer by a post `429`, and on the chat thread by a send `429`, each under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped on each
- **THEN** the root back stack's top entry is respectively `PaywallRoute(REPLY_CAP)`, `PaywallRoute(POST_CAP)`, and `PaywallRoute(CHAT_CAP)` AND the dialog is dismissed

#### Scenario: The dialog component itself holds no navigation reference

- **WHEN** inspecting `DailyCapUpsellDialog.kt`
- **THEN** the component holds no back-stack reference and performs no navigation itself — it only invokes the hoisted `onActivatePremium` / `onDismiss` (navigation is the host's responsibility)

#### Scenario: Scrim/back dismissal still behaves as Tutup and does not navigate

- **GIVEN** the dialog composed with recording `onDismiss` / `onActivatePremium` callbacks over a test root back stack
- **WHEN** the dialog's `onDismissRequest` fires (scrim tap / back)
- **THEN** `onDismiss` is invoked exactly once AND `onActivatePremium` is not invoked AND no `PaywallRoute` is appended to the back stack
