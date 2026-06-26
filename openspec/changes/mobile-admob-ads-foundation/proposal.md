## Why

Ads are the Free-tier monetization pillar — the **only completely-unbuilt revenue stream** (Premium is shipped end-to-end: paywall, purchase, entitlements, image upload, premium username, post editing, billing webhook). docs/08 Phase 4 item 18 + docs/01 § Ads Implementation specify Google AdMob with a UMP consent gate; the consent groundwork already exists (`users.analytics_consent.ads_personalization` flows backend↔mobile), but no ad ever renders. This change builds the **first vertical slice** of ad serving — a working, flag-gated, test-ad display loop — so the Free-tier revenue loop is real and operator-controllable ahead of launch (ads revenue starts ~Month 3 post-launch; build now, gate OFF until AdMob approval).

## What Changes

- **New `:infra:admob` KMP module** (root-level, mobile-only — mirrors `:infra:revenuecat` / `:infra:supabase-realtime`): a vendor-SDK-free `AdProvider` interface + domain models in commonMain, with the single Google Mobile Ads + UMP SDK implementation fenced inside it per **invariant #16** (no vendor SDK import outside `:infra:*`). `:mobile:app` consumes only the interface. Adds Google Mobile Ads SDK + UMP SDK to `gradle/libs.versions.toml` (Android), iOS framework deps for the cinterop actual.
- **UMP consent gate**: request UMP consent info + show the consent form when required (UU PDP mandatory); map the stored `ads_personalization` consent to the ad request — personalized when `true`, **non-personalized fallback (npa) when `false`/declined** (mandatory per docs/01 § Privacy Compliance).
- **Timeline native-ad placement** (the primary placement, docs/01 § Placement): native ads interleaved into the Nearby/Following/Global feeds every 5–7 posts, rendered through the canonical `PostFeedList<T>` / `ui/components` list seam (docs/11 §2.1). Ad label string ("Bersponsor") via `:shared:resources`.
- **Premium suppression** (cross-cutting): Premium viewers see ZERO ads, read from the server-authoritative entitlement seam (no client-only premium flag). Free viewers see ads when enabled.
- **Backend ads-config read** `GET /api/v1/config/ads` → `{ads_enabled (default FALSE), timeline_frequency}`, read through the Remote-Config→Redis seam (`remote_config:{flag:ads_enabled}`, docs/11 §3.3) with a per-flag short-TTL (30–60 s) kill-switch override. Mobile initializes the SDK + shows placements ONLY when `ads_enabled=TRUE` — the launch kill-switch (BUILT-but-gated, like `image_upload_enabled`). No new DB table; it is a flag read.
- **Verified with Google's documented test ad-unit IDs** (staging/debug) — no AdMob account approval needed to build/verify.
- **Explicit deferred scope** (each captured as a spec requirement with a negative guard + tracked `follow-up`, never silent): interstitials (app-open #5/#10/#15, post-submit 1-in-5); profile-banner + chat-list native placements (chat screen NEVER gets ads); AppLovin MAX mediation; and — if cinterop proves too heavy for this slice — the iOS `AdProvider` actual (Android actual is fully in-scope and Test-Lab-verifiable; the iOS decision is made explicitly in `design.md`, never a silent skip).

## Capabilities

### New Capabilities

- `ads-config`: Backend server-authoritative ads configuration — the `GET /api/v1/config/ads` read returning the `ads_enabled` kill-switch (default OFF) + `timeline_frequency`, sourced from the Remote-Config→Redis flag seam with a short-TTL emergency override. This is the operator-controllable gate that makes ad serving BUILT-but-OFF until launch.
- `mobile-ads`: The `:mobile:app` + `:infra:admob` ad-serving surface — the vendor-SDK-fenced `AdProvider` seam, UMP consent gate wired to `ads_personalization` (personalized vs non-personalized), timeline native-ad placement every 5–7 posts via the shared list seam, Premium suppression, all gated by `ads-config`. Owns the explicit-deferral requirements for the placements/formats this slice does NOT ship.

### Modified Capabilities

<!-- None. The timeline native-ad placement composes OVER the existing post feeds via the shared PostFeedList seam without changing post-fetch behavior, so mobile-nearby-timeline / mobile-following-timeline / mobile-global-timeline are not modified. The ads_personalization consent is READ, not changed, so analytics-consent-update / mobile-analytics-consent are not modified. design.md § Cross-layer records both interactions. -->

## Impact

- **New module**: `:infra:admob` (KMP, mobile-only) — added to `settings.gradle.kts`. **NOT** added to the backend Dockerfile COPY list (mobile-only infra; the settings↔Dockerfile CI guard expects non-backend infra modules to be excluded — design.md records this).
- **Amends docs/01 § KMP Integration**: docs/01 (line ~177) currently locates `interface AdProvider` in `:core:data`; this change instead places it in the new vendor-fenced `:infra:admob` module (per the docs/11 §2.6 vendor-SDK `:infra` seam — `:infra:revenuecat` / `:infra:supabase-realtime` precedent), which fences the Google SDK far more cleanly than `:core:data` would. docs/01 §177 is updated to match (tasks.md 6.2) — an explicit, declared reconciliation, not silent divergence.
- **Lint**: a new `VendorSdkLeakageScanTest` clause enforcing invariant #16 for the Ads/UMP SDK on `:mobile:app` (`com.google.android.gms.ads.` / `com.google.android.ump.`) — the existing scan is per-vendor opt-in and does not yet cover Ads (tasks.md 3.6).
- **Substrate**: `gradle/libs.versions.toml` gains the Google Mobile Ads SDK + UMP SDK pins (Android); iOS framework deps (Google-Mobile-Ads-SDK + UserMessagingPlatform) for the cinterop actual.
- **Backend**: one new route `GET /api/v1/config/ads` (thin route → config service → Redis-cached flag read); no schema migration.
- **Mobile**: new `ui/components` native-ad card + feed interleave; reads the existing consent + entitlement seams; new `:shared:resources` ad-label string.
- **Consumes (unchanged)**: `users.analytics_consent.ads_personalization` (consent), the premium/entitlement status seam, the `PostFeedList<T>` list seam.
- **Human-required / operator** (surfaced at preflight, gated OFF until done): AdMob account + app registration + real ad-unit IDs; Google Play Data Safety form; Apple Privacy Nutrition Labels / `PrivacyInfo.xcprivacy`; iOS framework dependency setup; physical-device ad-render verify.
