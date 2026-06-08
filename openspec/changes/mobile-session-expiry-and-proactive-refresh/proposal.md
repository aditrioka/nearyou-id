## Why

On a live on-device debug (2026-06-08, staging flavor, physical Galaxy A17), the Nearby ("Sekitar") tab rendered **"Tidak bisa terhubung. Periksa koneksi internet kamu."** when the real fault was an **expired auth session**: logcat showed the request reaching `api-staging.nearyou.id` and returning HTTP 401, the automatic `POST /api/v1/auth/refresh` also returning 401 (refresh token rejected), and only on a *manual* retry did the app re-route to `SignInScreen`. So a terminal-401 is (a) mislabeled as a connectivity failure and (b) not reliably auto-routed to re-auth. Separately, the mobile client already stores `TokenPair.accessExpiresAtEpochMillis` but never uses it for the **preemptive refresh** that `docs/05-Implementation.md` § Session management (line 38) already prescribes — so every cold start after the 15-min access-token TTL eats a user-visible 401→refresh round-trip. Every remaining demo screen (profile, following, chat, search, settings) is authenticated, so a robust session lifecycle is a cross-cutting prerequisite for a demoable app (`openspec/project.md` § Mobile-First to Full-Demo Priority, "cross-cutting wiring… sequence each as it gates a demo scenario").

## What Changes

- **Terminal 401 ≠ connectivity error.** The timeline repositories currently map a terminal `HttpError(401)` (one that survived the Ktor `Auth` plugin's refresh) through `else -> NetworkError`, rendering `signin_error_network`. Split a genuine **transport failure** (IOException/timeout → keep the connectivity copy) from a **terminal 401** (→ session-expired / re-auth path, never the connectivity copy) in both `NearbyTimelineRepository` and `GlobalTimelineRepository`.
- **Reliable terminal-401 redirect.** `SessionInvalidator` emits the re-route over a `MutableSharedFlow(replay=0, extraBufferCapacity=1)`; an `invalidate()` that fires before `SessionExpiryEffect` subscribes (cold start) drops the signal and strands the user. Harden the signal so it cannot be lost (mechanism decided in `design.md`).
- **Session-expired copy.** On an involuntary logout, `SignInScreen` shows a clear notice ("Sesi kamu berakhir. Masuk lagi untuk lanjut.") via a **new CMP Resources string** (`signin_session_expired`) — not the connectivity string, not a blank landing.
- **Proactive (preemptive) refresh.** Use the stored `accessExpiresAtEpochMillis`: on app wake / cold start, if the access token expires in **< 5 min**, refresh **async, non-blocking** (per `docs/05-Implementation.md` line 38). Reactive (Ktor `Auth` bearer) refresh remains the fallback; the existing single-flight "exactly ONE refresh" guarantee is preserved.
- **Refresh-on-foreground.** On app `ON_RESUME` after an idle threshold, kick the same proactive refresh via a KMP-safe lifecycle hook (no vendor SDK import outside `:infra:*`).
- **Preserve destination after re-login** — return the user to the screen they were on, not just Home. (If this materially enlarges scope it lands as an explicit deferred-but-spec'd requirement, not a silent drop.)
- **Observability:** wire `NearbyTimelineRepository`'s currently-no-op `diagnosticLog` to a real (coordinate-free) sink so `nearby_network_error` is visible.
- **No backend change.** The backend is already best-practice (`ACCESS_TOKEN_TTL_SECONDS=900`, `REFRESH_TOKEN_TTL_DAYS=30`, refresh-token rotation via `tokens.rotate`, reuse detection via `TokenReuseException` + `token_version`). This change is mobile-only.

**Scope boundary.** IN scope: the **Ktor REST JWT** lifecycle on mobile (the `docs/05-Implementation.md` line 36 401-ladder paths 1/3/4). OUT of scope: the **Supabase realtime token** (ladder path 2 — that is chat, `mobile-chat-screen`, unbuilt) and **device attestation** on re-auth (a separate pending wiring item per `project.md` line 107). Re-auth here means routing to the existing "Masuk dengan Google" flow. User-initiated logout (Settings "Keluar") stays with `mobile-settings-screen` (FOLLOW_UPS `mobile-logout-ui-deferred`); this change is the **involuntary-expiry** sibling and only shares the `SessionInvalidator` seam.

## Capabilities

### New Capabilities

<!-- None — this change modifies existing capabilities only. -->

### Modified Capabilities

- `mobile-auth-signin`: ADD requirements for (1) reliable, non-droppable terminal-401 re-route; (2) session-expired user-facing copy on involuntary logout; (3) proactive preemptive refresh (< 5 min to expiry, async non-blocking, single-flight-preserving) on wake/cold-start; (4) refresh-on-foreground after idle; (5) destination preservation across the involuntary re-auth (or its explicit deferred guard). Tightens the existing "Refresh failure produces terminal 401 + store cleared" requirement.
- `mobile-nearby-timeline`: MODIFY the outcome-mapping requirement so a terminal 401 maps to the session-expired/re-auth path, not `NetworkError`; a transport failure keeps the connectivity copy. Wire the diagnostic sink.
- `mobile-global-timeline`: MODIFY the outcome-mapping requirement identically (terminal 401 → session-expired path, transport failure → connectivity copy).

## Impact

- **Mobile (`:mobile:app`):** `auth/SessionInvalidator.kt`, `screens/routing/SessionExpiryEffect.kt` + `RootRouterScreen`/`AppEntryProvider` (foreground hook host), `network/HttpClientFactory.kt` (proactive-refresh seam), `timeline/NearbyTimelineRepository.kt` + `timeline/GlobalTimelineRepository.kt` (401-vs-transport split), `screens/auth/SignInScreen.kt` (session-expired notice), `di/MobileModule.kt` (diagnosticLog wiring + any new seam). New CMP Resources string in `shared/resources/src/commonMain/composeResources/values/strings.xml` (+ any localized variants).
- **Tests:** repository 401-vs-IOException mapping (Nearby + Global), `SessionInvalidator` emit-before-subscribe regression, proactive-refresh threshold + single-flight, `SignInScreen` session-expired render (Robolectric — add to the Release-variant exclude if it uses the ui-test-manifest host), destination-preservation (or its deferred guard).
- **Dependencies:** none new — foreground/wake detection uses the already-present `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose`. No `gradle/libs.versions.toml` change. No backend, schema, or migration change.
- **Canonical docs:** aligns mobile behavior to `docs/05-Implementation.md` §§ 36/38/126 (401 ladder, preemptive refresh, re-auth triggers); no doc rewrite required.
