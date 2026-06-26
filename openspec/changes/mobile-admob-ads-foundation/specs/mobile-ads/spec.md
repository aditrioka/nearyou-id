## ADDED Requirements

### Requirement: Vendor-SDK-fenced `:infra:admob` ad-provider seam

Ad serving SHALL be exposed to `:mobile:app` through a vendor-SDK-free commonMain `AdProvider` interface (initialize, request UMP consent + report consent state, load a native ad as a vendor-free `NativeAdContent`, dispose) living in a new root-level KMP module `:infra:admob`, alongside the single Google Mobile Ads + UMP SDK implementation. `:mobile:app` SHALL depend on the interface only; the Google Mobile Ads / UMP SDK SHALL NOT appear on the `:mobile:app` compile classpath (invariant #16, the `vendor-sdk-leakage-scan` contract). This follows the established vendor-SDK `:infra` seam (docs/11 §2.6, `:infra:supabase-realtime` / `:infra:revenuecat`) — it SHALL NOT introduce a parallel pattern.

#### Scenario: The Google Ads SDK does not leak into the app module

- **WHEN** the `:mobile:app` compile classpath is inspected (or the vendor-SDK leakage scan runs)
- **THEN** no `com.google.android.gms.ads` / UMP SDK symbol is present — only the `:infra:admob` `AdProvider` interface and vendor-free models

#### Scenario: The app consumes ads only via the interface

- **WHEN** a screen displays an ad
- **THEN** it does so through the injected `AdProvider` interface and `NativeAdContent`, never a Google SDK type

### Requirement: UMP consent gate before any ad loads

When ads are enabled for the viewer, on the first ad-eligible session the client SHALL request UMP consent information and SHALL present the UMP consent form when UMP reports it is required (UU PDP / docs/01 § Privacy Compliance), before any ad is loaded. A consent-request failure SHALL degrade to no ads (fail-safe), never blocking the feed.

#### Scenario: Consent form shown when required

- **WHEN** ads are enabled for a Free viewer and UMP reports consent is required
- **THEN** the UMP consent form is presented before any native ad is requested

#### Scenario: Consent flow failure shows no ads, never an error

- **WHEN** the UMP consent request fails
- **THEN** no ad is loaded and the feed renders normally with no error chrome

### Requirement: Stored `ads_personalization` consent governs personalized vs non-personalized ads

The client SHALL honor the user's stored `users.analytics_consent.ads_personalization` (the consent-screen toggle, default OFF): when `false` or declined the ad request SHALL be **non-personalized** (the mandatory fallback, docs/01); when `true` and UMP consent is granted, personalized ads MAY be requested. The non-personalized path SHALL be enforced by the client request configuration (manual `npa` extra) and SHALL NOT rely solely on UMP regional behavior.

#### Scenario: Personalization OFF forces non-personalized ads

- **WHEN** a Free viewer with `ads_personalization = false` is served an ad
- **THEN** the ad request is non-personalized

#### Scenario: Personalization ON allows personalized ads

- **WHEN** a Free viewer with `ads_personalization = true` and granted UMP consent is served an ad
- **THEN** a personalized ad request is permitted

### Requirement: Timeline native-ad placement every N posts

When ads are enabled for the viewer, native ads SHALL be interleaved into the Nearby, Following, and Global feeds once every `timeline_frequency` posts (the `ads-config` value, 5–7 per docs/01 § Placement), rendered through the canonical `PostFeedList<T>` / `ui/components` list seam (docs/11 §2.1) as a sealed feed item with a stable `key` + `contentType` (docs/11 §2.4). The native-ad card SHALL carry a localized sponsored label from `:shared:resources` (no hardcoded UI string). Post-fetch behavior of the feeds SHALL be unchanged.

#### Scenario: Ad slot appears every `timeline_frequency` posts

- **WHEN** a Free viewer with ads enabled scrolls a feed longer than `timeline_frequency` posts
- **THEN** a native-ad slot is rendered after every `timeline_frequency` posts, each showing the localized "Bersponsor" label

#### Scenario: Feed shorter than the frequency shows no ad

- **WHEN** a feed contains fewer than `timeline_frequency` posts
- **THEN** no native-ad slot is rendered

#### Scenario: Ads are placed without changing post fetching

- **WHEN** ad slots are interleaved into a feed
- **THEN** the post list contents and ordering from the timeline endpoint are unchanged — ads are an overlay

### Requirement: Premium viewers see zero ads

A viewer the server reports as premium (via the `ads-config` `ads_enabled = false` for premium, the single server-authoritative source — no client-only premium flag) SHALL see no ads anywhere: no SDK initialization for ad serving, no UMP form, and no ad slots interleaved into any feed.

#### Scenario: Premium viewer has no ad slots

- **WHEN** a premium viewer (whose `ads-config` returns `ads_enabled = false`) browses any feed
- **THEN** no native-ad slot appears and no UMP consent form is shown

### Requirement: Ad serving is gated by the server `ads_enabled` flag and fails safe

The client SHALL initialize the ad SDK and interleave ad slots ONLY when `ads-config` returns `ads_enabled = true`. When `ads_enabled = false`, or when the `ads-config` fetch fails, the client SHALL show no ads (fail-safe) and SHALL NOT initialize the ad SDK.

#### Scenario: Flag OFF shows no ads

- **WHEN** `ads-config` returns `ads_enabled = false`
- **THEN** the ad SDK is not initialized and no ad slot is rendered

#### Scenario: Config fetch failure fails safe to no ads

- **WHEN** the `GET /api/v1/config/ads` fetch fails
- **THEN** the client renders feeds with no ads and no error chrome

### Requirement: iOS ad rendering is deferred to a follow-up

This slice ships the Android `AdProvider` actual fully; the iOS `AdProvider` actual is deferred (the cinterop binding to Google-Mobile-Ads-SDK + UserMessagingPlatform is a tracked `follow-up`). On iOS the `AdProvider` SHALL report ads unavailable: no UMP request, no native ad, no ad slots — the feed renders posts only. Because `ads_enabled` defaults OFF, this is invisible at launch. This deferral SHALL be tracked as a `follow-up` issue, not silently dropped.

#### Scenario: iOS reports ads unavailable

- **WHEN** the app runs on iOS in this slice
- **THEN** the `AdProvider` reports unavailable, no UMP form is shown, and feeds render posts with no ad slots

### Requirement: Ad requests carry no precise location (data minimization)

Per docs/01 § Privacy Compliance ("share city-level location, not precise coordinates") and the UU PDP data-minimization posture (docs/06), the ad request SHALL NOT pass the device's precise coordinates to the ad SDK — the SDK's location signal SHALL be disabled / no precise `location` set on the request. This holds even when the app holds Precise location permission (used for small-radius Nearby).

#### Scenario: No precise coordinate reaches the ad request

- **WHEN** an ad is requested for a viewer whose app holds Precise location permission
- **THEN** the request carries no precise latitude/longitude — at most city-level signal

### Requirement: The chat thread screen never shows ads (permanent)

The chat thread (1:1 conversation) screen SHALL NEVER display any ad, in this change or any future one (docs/01 — preserve trust). This is a permanent product rule, NOT a deferral, and a future change that adds a placement SHALL NOT weaken it.

#### Scenario: No ad in a chat thread

- **WHEN** a viewer opens a 1:1 chat thread
- **THEN** no ad of any format is rendered

### Requirement: This change ships only the timeline native placement

Ad placements other than the timeline native ad are out of scope for THIS slice and SHALL NOT render: no interstitials (app-open #5/#10/#15 or post-submit), no profile banner, and no chat-list native ad. Each deferred placement (interstitials, profile banner, chat-list native) SHALL be tracked as a `follow-up` issue, whose number is recorded in this requirement when filed (docs/12 §3).

#### Scenario: No interstitial on app-open or post-submit

- **WHEN** the app is opened a 5th/10th/15th time, or a post is submitted
- **THEN** no interstitial ad is shown

#### Scenario: No ad on profile or chat list

- **WHEN** a viewer opens a profile screen or the conversation list
- **THEN** no ad is rendered (these placements are deferred)

### Requirement: This change does not add ad mediation

Ad requests SHALL go directly to Google AdMob with no mediation adapter; AppLovin MAX mediation (docs/01 Phase 2+) is out of scope and SHALL be tracked as a `follow-up`.

#### Scenario: No mediation adapter is wired

- **WHEN** the `:infra:admob` implementation requests an ad
- **THEN** it requests from AdMob directly with no third-party mediation adapter configured
