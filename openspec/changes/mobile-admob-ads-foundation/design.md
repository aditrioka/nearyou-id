## Context

Ads are docs/08 Phase 4 item 18 and the Free-tier half of "Premium + Payment + Ads" — the only unbuilt revenue stream. docs/01 § Ads Implementation specifies Google AdMob, a UMP consent gate (UU PDP), a non-personalized fallback, a placement table (timeline native every 5–7 posts is the primary slot), and a KMP `interface AdProvider` with manual expect/actual. The consent substrate already exists: the analytics-consent screen (docs/03) stores `users.analytics_consent.ads_personalization` (default OFF), round-tripped via `ConsentRoutes`/`ConsentRepository` (backend) and `ConsentSnapshotStore` (mobile). Premium is fully shipped, including a server-authoritative entitlement seam. No ad renders today.

This change is the **first vertical slice**: a working, flag-gated, test-ad display loop — `:infra:admob` seam + UMP gate + one placement (timeline native) + Premium suppression + a server kill-switch — verifiable on Android with Google's documented test ad-unit IDs, shipped gated OFF.

Constraints: invariant #16 (no vendor SDK import outside `:infra:*`); docs/11 §2.5 (expect/actual discipline), §2.6 (vendor-SDK `:infra` seam), §2.1 (canonical list primitives), §3.3 (Remote-Config→Redis flag seam + short-TTL kill-switch), §4 (Pattern Registry — no silent second patterns), §5 (DoD). Solo operator, pre-launch; ads revenue starts ~Month 3 post-launch, so BUILT-but-OFF is the correct posture.

## Goals / Non-Goals

**Goals:**
- A vendor-SDK-fenced `:infra:admob` module exposing a commonMain `AdProvider` interface; `:mobile:app` never imports the Google SDK.
- UMP consent gate (form shown when required) with the stored `ads_personalization` honored — personalized when ON, non-personalized when OFF/declined.
- Timeline native-ad placement (Nearby/Following/Global) every 5–7 posts via the shared `PostFeedList<T>` list seam.
- Premium viewers see zero ads (server-authoritative entitlement); Free viewers see ads when enabled.
- A `GET /api/v1/config/ads` kill-switch (`ads_enabled` default FALSE + `timeline_frequency`) over the Remote-Config→Redis seam with a short-TTL emergency override.
- Android actual fully implemented + Test-Lab-verifiable on Google's test ad-unit IDs.

**Non-Goals (each captured as an explicit `mobile-ads` requirement with a negative guard + a tracked `follow-up`, never silent):**
- Interstitials (app-open #5/#10/#15, post-submit 1-in-5).
- Profile-banner + chat-list native placements. The **chat screen NEVER gets ads** (docs/01 — preserve trust); that is a permanent rule, not a deferral.
- AppLovin MAX mediation (docs/01 Phase 2+).
- **The iOS `AdProvider` cinterop actual** — deferred (Decision D7); the iOS actual reports "unavailable" this slice.
- Real ad-unit IDs / live ad serving (gated OFF; operator/human-required — see Migration Plan).
- Any DB schema change; any admin UI change (the `ads_enabled` flag is managed through the existing `admin-feature-flags` editor + Remote Config — no new admin surface).

## Decisions

### D1 — `:infra:admob` KMP module wrapping the OFFICIAL Google SDK, not a community wrapper
A new root-level `:infra:admob` KMP module holds a vendor-SDK-free commonMain `AdProvider` interface (initialize, request UMP consent + report state, load a native ad, dispose) + plain domain models (`NativeAdContent`, `ConsentState`, `AdRequestMode { PERSONALIZED, NON_PERSONALIZED }`). The single Google Mobile Ads + UMP SDK implementation lives inside this module; `:mobile:app` depends on the interface only, with the vendor dependency `implementation`-scoped so it never reaches the app compile classpath. This mirrors `:infra:supabase-realtime` and `:infra:revenuecat` exactly (docs/11 §2.6) and satisfies invariant #16 + guiding principle 4 (vendor-abstraction for frictionless migration).
- *Alternative — community KMP wrapper (`LexiLabs-App/basic-ads`, verified 2026-06-26 as the leading KMP AdMob lib): rejected.* It re-exposes the SDK as composables, but adds an unmaintained third-party layer between us and Google, owns the seam we must own for portability, and would still need fencing. Wrapping the official SDK ourselves is the established project pattern (RevenueCat/Supabase precedent).

### D2 — commonMain `interface` + Koin-bound per-platform actuals (not `expect class`)
`AdProvider` is a commonMain `interface`; platform implementations are bound in the existing Koin platform modules (docs/11 §2.5 — `expect class` is Beta, reserve expect/actual for top-level functions). This matches the `ImagePicker` / realtime-subscriber precedent. Native-ad rendering is exposed as a commonMain composable contract whose actual draws the platform `NativeAdView`; the interface returns vendor-free `NativeAdContent` the UI binds.

### D3 — UMP is the consent dialog; `ads_personalization` governs personalized vs non-personalized
UMP shows the consent form when required (UU PDP / docs/01 § Privacy Compliance). For the personalized-vs-not decision we honor our own stored `ads_personalization`: when OFF/declined we request **non-personalized** ads. Per current guidance (verified 2026-06-26: the UMP SDK writes the consent/TCF string the GMA SDK reads automatically; manual `npa=1` is the explicit fallback), the impl relies on UMP-managed consent and, when our toggle is OFF, additionally forces non-personalized via the request extra — belt-and-suspenders so a Free user who declined personalization never gets a personalized ad regardless of UMP region behavior. eCPM is reduced 30–50% in that path (docs/01, mandatory).
- *Alternative — our own consent UI instead of UMP: rejected* (Google requires UMP/a certified CMP for AdMob; docs/01 mandates the UMP SDK).

### D4 — Server kill-switch (`ads-config` endpoint) over the Remote-Config→Redis seam; default OFF
`GET /api/v1/config/ads` (thin route → config service → Redis-cached flag read, docs/11 §3.1/§3.3) returns `{ads_enabled: Boolean (default FALSE), timeline_frequency: Int}` sourced from `remote_config:{flag:ads_enabled}` with a **per-flag short-TTL override (30–60 s)** because it is an emergency kill-switch (the §3.3 mandate, the `image_upload_enabled` precedent). Mobile initializes the SDK + shows placements ONLY when `ads_enabled=TRUE`; a fetch failure degrades to ads-OFF (fail-safe, never error chrome). No DB table — it is a flag read.
- *Alternative — client Firebase Remote Config fetch on mobile: rejected.* No mobile RC-fetch precedent exists; mobile kill-switches are server-authoritative today (`search_enabled` 503, `image_upload_enabled`). A server endpoint keeps the operator in sole control and the staleness budget ours (§3.3).
- *Alternative — fold into a general `/config/bootstrap`: deferred.* No bootstrap endpoint exists; a focused `/config/ads` is the minimal correct slice and a bootstrap can absorb it later.

### D5 — Timeline placement composes OVER the feed via `PostFeedList<T>`; timeline specs unchanged
Ad slots interleave into the existing Nearby/Following/Global feeds every `timeline_frequency` posts through the canonical `PostFeedList<T>` / `ui/components` list seam (docs/11 §2.1) — a feed item is a sealed `FeedItem { Post | NativeAd }`, with stable `key` + `contentType` per item (docs/11 §2.4 lazy-list perf). Post-fetch behavior is unchanged, so `mobile-nearby-timeline` / `mobile-following-timeline` / `mobile-global-timeline` are **not** modified — `mobile-ads` owns the interleave requirement. The native-ad card reuses `PostCard` metrics with a "Bersponsor" label (docs/11 §2.8 mockup parity — same card geometry).
- *Alternative — modify each of the three timeline specs to carry ad slots: rejected* (their post-fetch contract doesn't change; ads are a presentation overlay, and three spec edits would bloat the change + collide with timeline-owning work).

### D6 — Premium suppression reads the server-authoritative entitlement seam, not a client flag; uses the ACCESS-control formula
Whether a viewer sees ads is decided server-side from `users.subscription_status`, with no client-only premium boolean (the invariant the premium specs already enforce). Premium → the `ads-config` endpoint returns `ads_enabled = false`, so the ad provider is never asked + no slots interleave.
- **Formula**: ad-free is a premium *access benefit*, so suppression uses the **access-control** formula `subscription_status ∈ {premium_active, premium_billing_retry}` — the `PREMIUM_STATES` set used by `PostEditService` / `CreatePostService` — **not** the stricter `premium_active`-only *badge* formula used by `user-profile-read` / `FollowRoutes`. Rationale: a `premium_billing_retry` user is in the 7-day grace window where "Premium access REMAINS active" (docs/08 Phase 4 item 4 BILLING_ISSUE), so they keep the ad-free benefit. Picking the badge formula would wrongly show ads to grace-period subscribers — a reconciliation trap (B.3) flagged because two premium formulas coexist in the codebase by design.

### D7 — iOS `AdProvider` actual deferred (explicit), Android actual fully in-scope
The iOS actual cinterop binding to Google-Mobile-Ads-SDK + UserMessagingPlatform is deferred to a follow-up; this slice ships the iOS actual as an "unavailable" implementation (UMP not requested, `loadNativeAd` returns none, provider reports not-initialized). Rationale: (a) `ads_enabled` defaults OFF, so neither platform renders ads at merge; (b) iOS is **never CI-gated** (docs/13) and cinterop to the GMA framework can't be verified in our pipeline, so shipping it unverified adds risk without payoff now; (c) it keeps the slice Android-Test-Lab-verifiable and shippable; (d) the deferral is an **explicit `mobile-ads` requirement** (positive: "iOS reports ads unavailable until the cinterop follow-up"; negative-guard scenario) + a tracked `follow-up`, per docs/12 §3 — not a silent skip. This is surfaced to the operator at handoff for explicit sign-off (it is the one scope cut in this slice).
- *Alternative — implement the iOS cinterop now: viable but rejected for this slice* on the cost/verifiability grounds above; it is the immediate follow-up.

### D8 — `:infra:admob` is excluded from the backend Dockerfile COPY list
`:infra:admob` is mobile-only KMP. The settings.gradle.kts↔Dockerfile module-copy CI guard expects non-backend infra modules to be absent from the backend image COPY list (the `:infra:revenuecat` mobile module precedent vs `:infra:revenuecat-api` backend module). A `tasks.md` step verifies the guard stays green.

## Standards conformance (docs/11 Pattern Registry)

Builds on existing patterns, no deviations (so no docs/11 § Pattern Registry amend needed):
- **State §2.2** — ad-eligibility / consent state surfaced through the relevant screen `ViewModel`(s) as part of their single `StateFlow<UiState>`; no new state pattern.
- **List seam §2.1** — placement uses `PostFeedList<T>` + `ui/components`; introducing a *second* list pattern would be a fork — we do not.
- **expect/actual §2.5** — commonMain interface + Koin-bound actuals (D2).
- **Vendor-SDK `:infra` seam §2.6** — `:infra:admob` follows the realtime/revenuecat seam (D1); explicitly NOT a parallel pattern.
- **Backend layering §3.1 + flag seam §3.3** — thin route → service → Redis flag read (D4).
- **Mockup reference §2.8** — timeline native-ad card translated from the feed-card frame (PostCard geometry + sponsored label).

## Cross-layer scope (docs/12 Integration-Contracts)

Layers spanned: **backend** (the `ads-config` read endpoint) + **mobile** (the ad-serving surface). **admin** = none new (the `ads_enabled` flag is operated through the existing `admin-feature-flags` editor + Remote Config — declared, not silently omitted). Read-paths: `GET /api/v1/config/ads`, the existing consent read, the existing entitlement read. Deferred layers/placements/iOS are each an explicit `mobile-ads` / `ads-config` requirement (positive + negative-guard) with a tracked `follow-up`, not bare prose.

## Risks / Trade-offs

- **iOS ad rendering absent this slice (D7)** → explicit deferred requirement + follow-up; gated OFF so no user-visible gap at merge; operator signs off at handoff.
- **Vendor SDK app-size / init cost** → pin the stable Google Mobile Ads SDK; the 2026 GMA Next-Gen SDK (Kotlin rewrite, ~17% smaller, ~27% faster banners, mandatory background init) is a watch-item logged in Open Questions, not adopted blind.
- **Native ad in a LazyColumn perf** → stable `key`/`contentType` per `FeedItem`, ad objects loaded/cached off the composition path, disposed on item disposal (§2.4).
- **Flag fetch failure** → fail-safe to ads-OFF; never blocks the feed, never error chrome.
- **Test-ad-only until AdMob approval** → operator/human-required tasks (Migration Plan); build + Android verify proceed on Google's documented test units.
- **settings↔Dockerfile guard (D8)** → explicit verify task; mobile-only infra stays out of the backend COPY list.
- **UMP regional behavior variance** → honor our `ads_personalization` toggle as the authoritative non-personalized trigger (D3), independent of UMP's region logic.

## Migration Plan

Ship gated OFF (`ads_enabled=FALSE`). Build + verify on Android with Google's documented test ad-unit IDs. To go live (operator, post-AdMob-approval): create the AdMob account + app + real native ad-unit IDs, file the Google Play Data Safety form, add the Apple Privacy Nutrition Labels / `PrivacyInfo.xcprivacy` entries, then flip `ads_enabled=TRUE` (per-region) via the admin feature-flag editor / Remote Config. Rollback = flip the flag OFF (sub-minute via the short-TTL override); no deploy needed.

## Open Questions

- **GMA Next-Gen vs stable SDK** — pin the stable Google Mobile Ads SDK for this slice (native-ads-mature, widely deployed) or adopt Next-Gen (smaller/faster, newer)? Resolve at `/opsx:apply` substrate re-check; default = stable, log Next-Gen as a currency watch-item (docs/09).
- **`timeline_frequency` exact value** — docs/01 says "every 5–7 posts"; server-driven via `ads-config` so tunable without a release. Default 6; confirm against eCPM/UX once live.
- **iOS cinterop timing** — the immediate follow-up after this slice; sequence vs other mobile follow-ups at the operator's discretion.
