## MODIFIED Requirements

### Requirement: PaywallRoute is a payload-carrying serializable NavKey registered for the iOS-saveable back stack

The mobile app SHALL declare a `PaywallRoute` `NavKey` (in `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/NavKeys.kt`). It is a `@Serializable data class` carrying a single non-PII `entry: PaywallEntry` property, where `PaywallEntry` is an enum naming the gated surface that opened the paywall. The enum SHALL declare exactly these values, each wired at its call site in `appEntryProvider` (or threaded to it through the surface's hoisted `onActivatePremium`):

| Entry | Opened from |
|---|---|
| `LIKE_CAP` | the like-cap `DailyCapUpsellDialog` on the three feeds and on post-detail |
| `SEARCH_GATE` | the search `403 premium_required` gate |
| `USERNAME` | the premium-username gate (`mobile-premium-username`) |
| `IMAGE_ATTACH` | the composer's proactive image-attach gate (`mobile-image-attachment`) |
| `CHAT_CAP` | the chat 50/day cap dialog on the chat thread and the share-to-chat picker |
| `REPLY_CAP` | the reply 20/day cap dialog on post-detail |
| `POST_CAP` | the post 10/day cap dialog on the composer |
| `EDIT_GATE` | the post-edit `403 premium_required` upsell |
| `RADIUS_GATE` | the Nearby Premium-radius upsell |
| `TIMELINE_CAP` | the Free timeline read cap (`timeline-read-rate-limit`): the soft-limit banner and the hard-limit state on the three feeds |

New values SHALL be appended, never reordered or renamed: kotlinx.serialization encodes the enum by name, so a persisted iOS back stack must keep decoding. A gated surface MUST NOT open the paywall with another surface's entry (e.g. the radius upsell MUST NOT push `LIKE_CAP`).

The route MUST NOT carry any PII, token, coordinate, or user identifier. It SHALL be registered in the `navSavedStateConfiguration` polymorphic `SerializersModule` (`mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/AppNavSerialization.kt`) via an explicit `subclass(PaywallRoute::class, PaywallRoute.serializer())` entry so the back stack is saveable on Kotlin/Native (iOS), mirroring `PostDetailRoute`. `PaywallRoute` SHALL be appended to the **root** back stack (overlaying the section `NavigationBar`), the same mechanism `SearchRoute` / `PostDetailRoute` / the composer FAB use — deliberately NOT a per-tab back stack.

#### Scenario: PaywallRoute survives a serialized back-stack round-trip for every entry

- **WHEN** a back stack containing `PaywallRoute(entry = e)` for EVERY `e` in `PaywallEntry.entries` is serialized via `navSavedStateConfiguration` and restored (`NavKeySerializationTest`)
- **THEN** each restored entry is a `PaywallRoute` whose `entry` equals the original `e` (the polymorphic `subclass(...)` registration makes it decode on Kotlin/Native)

#### Scenario: PaywallEntry declares exactly the wired gates

- **WHEN** inspecting the `PaywallEntry` enum
- **THEN** its values are exactly `LIKE_CAP`, `SEARCH_GATE`, `USERNAME`, `IMAGE_ATTACH`, `CHAT_CAP`, `REPLY_CAP`, `POST_CAP`, `EDIT_GATE`, `RADIUS_GATE`, `TIMELINE_CAP`, in that declaration order

#### Scenario: PaywallRoute carries only the non-PII entry-context

- **WHEN** inspecting the `PaywallRoute` declaration
- **THEN** its only property is the `PaywallEntry` enum AND it declares no `latitude`/`longitude`, no token, no user id, and no other identity payload

### Requirement: PaywallScreen renders the frame-17 paywall surface and is navigation-free

The mobile app SHALL ship a composable `PaywallScreen` (file: `mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/paywall/PaywallScreen.kt`), mapped from the `PaywallRoute` `NavKey` by the `appEntryProvider`, rendering the paywall per the canonical mockup (frame 17, `dev/mockups/nearyou-screens-mockup.html`, binding for look/layout per docs/11 §2.8). It renders:
- a top app bar with a close (X) affordance;
- a Premium hero (the `workspace_premium` premium-accent icon + a "NearYouID Premium" heading);
- the benefit list (§ "The paywall benefit set");
- the pricing cards (§ "Pricing, anchors, and savings are derived");
- a full-width primary "Aktifkan Premium" CTA;
- the disclosure footer (§ "The disclosure footer").

The hero subheadline SHALL be tailored to the route's `PaywallEntry` while always presenting the full Premium offering. The mapping is exhaustive over the enum with no `else` branch, each subheadline via `stringResource`:

| Entry | Subheadline key |
|---|---|
| `LIKE_CAP` | `paywall_subhead_like_cap` |
| `SEARCH_GATE` | `paywall_subhead_search` |
| `USERNAME` | `username_premium_gate_body` (the `docs/03-UX-Design.md` § Premium Username Customization paywall copy, "Ganti username adalah fitur Premium.") |
| `CHAT_CAP` | `paywall_subhead_chat_cap` |
| `REPLY_CAP` | `paywall_subhead_reply_cap` |
| `POST_CAP` | `paywall_subhead_post_cap` |
| `EDIT_GATE` | `paywall_subhead_edit` |
| `RADIUS_GATE` | `paywall_subhead_radius` |
| `TIMELINE_CAP` | `paywall_subhead_timeline_cap` ("Baca timeline tanpa batas") |
| `IMAGE_ATTACH` | `paywall_subhead_default` |

`IMAGE_ATTACH` deliberately keeps the generic `paywall_subhead_default` headline. Image upload is a Month-6 feature behind the `image_upload_enabled` flag (default `false`, docs/05), and docs/01 + docs/03 § Paywall & Premium Disclosure forbid advertising image upload before it ships. A photo-led headline would promise a feature a buyer may not receive. A later change MAY tailor it once the image launch is live.

`PaywallScreen` SHALL be navigation-free: it holds no back-stack reference; its close affordance invokes a hoisted `onClose` lambda and a successful purchase invokes a hoisted `onPurchaseComplete` (or equivalent return) lambda. No hardcoded UI string literals SHALL appear in the screen source (every `Text` / `contentDescription` resolves via `stringResource(Res.string.<name>)`); colors and typography SHALL come from `NearYouTheme` tokens (no hex literals); the screen SHALL render under both light and dark schemes.

#### Scenario: The paywall renders the frame-17 surface and is navigation-free

- **GIVEN** `PaywallScreen` composed for `PaywallRoute(entry = LIKE_CAP)` over a Content state with loaded packages under `NearYouTheme`
- **THEN** the rendered tree contains the Premium hero, the benefit list, the three pricing cards, a primary CTA labelled `stringResource(Res.string.cta_activate_premium)`, and a close affordance bound to the hoisted `onClose` AND the screen holds no back-stack reference (navigation is delivered via the hoisted lambdas only)

#### Scenario: No hardcoded UI strings and token-only styling

- **WHEN** inspecting `PaywallScreen.kt`
- **THEN** every user-visible text resolves via `stringResource(Res.string.<name>)` AND the source contains no hex color literals (theme tokens only) AND the screen renders without crash under `NearYouTheme` light and dark

#### Scenario: The hero headline is tailored to the entry-context

- **GIVEN** `PaywallScreen` composed once for `PaywallRoute(entry = LIKE_CAP)` and once for `PaywallRoute(entry = SEARCH_GATE)`, both in the Content state
- **THEN** the two renderings present a different hero headline (the entry-context tailoring) AND both still present the full benefit list and pricing cards (the contextual hero leads, it does not narrow the offering)

#### Scenario: Every new cap/gate entry renders its own headline

- **GIVEN** `PaywallScreen` composed in the Content state for each of `CHAT_CAP`, `REPLY_CAP`, `POST_CAP`, `EDIT_GATE`, `RADIUS_GATE`, `TIMELINE_CAP`, and `USERNAME`
- **THEN** each rendering shows its mapped subheadline (`paywall_subhead_chat_cap`, `paywall_subhead_reply_cap`, `paywall_subhead_post_cap`, `paywall_subhead_edit`, `paywall_subhead_radius`, `paywall_subhead_timeline_cap`, `username_premium_gate_body` respectively) AND none shows `paywall_subhead_default`

#### Scenario: The image-attach entry does not advertise image upload

- **GIVEN** `PaywallScreen` composed in the Content state for `PaywallRoute(entry = IMAGE_ATTACH)`
- **THEN** the hero shows `paywall_subhead_default` AND the rendered tree contains no node advertising image/photo upload
