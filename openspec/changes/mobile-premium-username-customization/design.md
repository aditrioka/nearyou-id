## Context

The backend `premium-username-customization` capability is shipped and frozen (PR [#301](https://github.com/aditrioka/nearyou-id/pull/301)): `PATCH /api/v1/user/username` (body `{ "new_username" }`) + `GET /api/v1/username/check?candidate=` (the non-authoritative probe), gated feature-flag → Premium → 30-day cooldown, with format/collision/moderation validation and a race-safe release-hold transaction. There is **no mobile surface** — the Settings "Ganti username" row (`SettingsScreen.kt`, mockup frame 16) renders a non-trapping "Segera hadir". This change adds that surface, consuming the two shipped endpoints with zero backend work, and is the established mobile data-seam shape (`mobile-search` is the closest precedent: a premium-gated single screen over a shipped endpoint, sealed outcome over HTTP status, route-scoped ViewModel, pure projection).

**Wire truth (per docs/11 §2.6 — trust the Kotlin route files).** `backend/.../user/UserUsernameRoutes.kt`: change → `200 {username}` · `403 {error:premium_required,upsell:true}` · `409 {error:username_unavailable}` · `422 {error:invalid_username}` (format) / `422 {error:username_rejected}` (moderation) · `429 {error:cooldown_active}` / `429 {error:rate_limited}` (both + `Retry-After`) · `503 {error:feature_disabled}` · `400 {error:invalid_request}` · `401` at the auth boundary. Probe → `200 {available}` · `403` · `422 invalid_username` · `429 rate_limited` (3/day) · `503` · `401`. DTOs: `UsernameChangeRequest{@SerialName("new_username") newUsername}`, `UsernameChangeResponse{username}`, `UsernameCheckResponse{available}`.

**Three shipped-reality constraints** that the docs/03 UX copy (older design text) predates, each reconciled to the wire (the shipped backend is authoritative; docs/03 governs behaviour only where the wire supports it):

1. The client's Premium signal (`isPremium` on the self `ProfileFlow`) is `true` only for `premium_active`, but the backend treats **both** `premium_active` and `premium_billing_retry` as Premium → a purely proactive `isPremium` gate would wrongly paywall a billing-retry user.
2. The shipped self-profile read (`UserProfileResponse`) exposes **no `username_last_changed_at`** → the proactive "next change in N days" disabled-entry state (docs/03 §129) is not client-computable.
3. The probe is **rate-limited 3/day** → docs/03 §119's "live debounced probe" cannot be a per-keystroke network call.

## Goals / Non-Goals

**Goals:**
- Ship the complete docs/03 §107–134 Ganti Username screen — current handle, live-validated field, availability feedback, all wire-supported error/cooldown/gate/disabled/session states, the submit-confirmation modal, success toast + profile refresh — consuming the two shipped endpoints.
- Wire the existing Settings "Ganti username" row: Premium → the screen; Free → the paywall.
- Reuse the `mobile-search` data seam + the Pattern-Registry mobile patterns (no new pattern).

**Non-Goals:**
- Any backend change (the endpoints are frozen) and the admin username-change oversight ([#285](https://github.com/aditrioka/nearyou-id/issues/285)).
- The proactive cooldown disabled-entry state, the three distinct unavailable messages, the distinct downgrade banner, and username autocomplete — all explicitly deferred (constraints 2/3 + missing backend signals); see the spec's deferred-scope requirement.

## Decisions

**D1 — Mirror the `mobile-search` data seam (Standards conformance).** `UsernameApiClient` (HTTP + wire-truth DTOs) → `UsernameRepository` (sealed `UsernameChangeOutcome` / `UsernameCheckOutcome`) behind a `UsernameFlow` interface (Koin `single`), consumed by a route-scoped androidx `ViewModel` exposing one `StateFlow<UsernameUiState>`, with a pure `usernameUiState(...)` projection. This builds on the docs/11 Pattern-Registry patterns: state-holder ViewModel (§2.2), Navigation 3 serializable NavKey (§2.3), data layer ApiClient→Repository→sealed-Outcome (§2.6), CMP Resources strings. **No new pattern is introduced → no docs/11 § Pattern Registry amendment is required.** Alternative (a bespoke screen-local `remember` + inline `HttpClient` call) rejected — it would be a second networking pattern (anti-patchwork).

**D2 — Reactive-authoritative Premium gate; proactive `isPremium` entry-hint as a nicety.** The authoritative gate is the backend `403 premium_required` → `PremiumGate`, handled in the screen regardless of any client hint (this is the backstop for constraint 1's billing-retry edge + `isPremium` staleness). The Settings entry additionally uses the self `isPremium` hint to route Free → paywall proactively (honouring docs/03 §114 "Free user taps: paywall opens"); a billing-retry user mis-hinted as Free simply sees the paywall, where re-subscribing is the desired action anyway. Both the proactive entry route and the reactive gate target the **same** hoisted `onActivatePremium` → `PaywallRoute`. Alternative (pure reactive, mirroring search's deferred-proactive `#253`) rejected for the entry because docs/03 is explicit about the proactive paywall and the `isPremium` hint already exists — but the reactive path is retained as the correctness backstop, so we get docs/03's UX without trusting the hint for correctness.

**D3 — Live LOCAL format validation + budget-aware network probe (reconciles constraint 3).** The format rules (length 3–30, charset regex, no `..`) are fully client-checkable, so the screen validates them live (debounced 500 ms, no network) and surfaces the inline format message instantly. The network probe is issued only for a format-valid candidate that differs from the last probed value, after the debounce — never per keystroke — and degrades to a non-blocking "akan dicek saat kamu simpan" state on `429 rate_limited`. Authoritative availability is the under-lock check at `PATCH` (a `409` is the backstop). This satisfies docs/03 §119's intent ("live" feedback) within the shipped 3/day reality. A `follow-up` issue records the docs/03 wording clarification.

**D4 — Same-status outcome disambiguation via the body `error` code.** Unlike `mobile-search` (each status 1:1 with an outcome), this wire splits two UX outcomes within one status twice: `429` → `cooldown_active` vs `rate_limited`; `422` → `invalid_username` vs `username_rejected`. The repository therefore reads the body `error` code **only** for these two statuses to select the outcome — a scoped, documented deviation from search's pure-status mapping (justified: the shipped wire encodes two distinct user-facing meanings in one status). Everything else stays status-keyed.

**D5 — Cooldown is surfaced reactively (constraint 2).** With no `username_last_changed_at` on the client, the cooldown cannot gate the entry proactively; it is surfaced at submit via `429 cooldown_active` + `Retry-After` → a day-countdown ("Ganti username berikutnya tersedia dalam N hari"). The proactive entry-disabled state is deferred (needs a backend self-profile field); `follow-up` issue filed.

**D6 — Single generic unavailable message (shipped `409` envelope).** The `409 username_unavailable` is one envelope for reserved/collision/release-hold — the wire carries no reason. The screen shows one generic "Username ini tidak tersedia. Coba username lain."; the three distinct docs/03 §121–123 messages are deferred pending a backend reason discriminator (`follow-up` filed).

**D7 — Submit-confirmation modal gates the destructive `PATCH`** (docs/03 §133); success → toast + self-`ProfileFlow` refresh (so the new handle propagates) + pop to Settings, modeled as a one-shot nullable UiState field per docs/11 §2.2 (no event-bus).

**D8 — #309 dependency softened to the app-entry wiring.** The screen and the Settings row invoke a hoisted `onActivatePremium` lambda; only the `appEntryProvider` call site references `PaywallRoute` (introduced by #309). So this change's own packages don't depend on #309 — only the one-line wiring does. The squash-merge SHOULD sequence behind #309; until then the wiring can point `onActivatePremium` at a TODO/no-op without blocking the rest.

**D9 — Mockup reference (docs/11 §2.8).** No dedicated username-customization frame exists on the board — only the Settings frame 16 carries the "Ganti username" entry row (line 1625). The screen is therefore built from docs/03 §117–134 over the `mobile-design-system` substrate (the `mobile-search` frameless-surface precedent), not from a bespoke frame. The frame-16 entry row's look is the binding visual for the Settings touchpoint.

## Risks / Trade-offs

- **Billing-retry user mis-paywalled at the proactive entry** → the reactive `403` backstop (D2) keeps correctness; the paywall is the desired action for a grace user, so the UX cost is minimal.
- **3/day probe budget exhausted mid-session** → live local validation covers format feedback; the probe degrades to "checked at save"; the under-lock `PATCH` check is authoritative — so availability is never wrong, only deferred.
- **#309 not yet merged** → hoisted callback (D8) decouples the code; merge sequencing is the only coordination cost; disjoint from #321.
- **Reduced-fidelity copy vs docs/03** (single unavailable message, reactive-only cooldown) → each is an explicit deferred requirement + `follow-up`, not a silent gap; the v1 is fully functional.

## Migration Plan

Mobile-only; no Flyway migration; no new library pin. Deploy is the standard mobile path. **Sequence the squash-merge behind #309** (`PaywallRoute`); if #309 slips, the `onActivatePremium` wiring stays a documented TODO and the rest ships. UI-affecting change → the docs/11 §5 DoD manual `verify-loop` bring-up with screenshot evidence is required before archive; run `linkDebugFrameworkIosSimulatorArm64` locally (new route + iosTest touch the Native target).

## Open Questions

- Confirm the docs/03 reconciliations are acceptable as **deferred follow-ups** (proactive cooldown entry-state, three distinct unavailable messages, distinct downgrade banner) rather than blockers — the proposal assumes yes (shipped backend is authoritative; each is tracked, not dropped). If the operator wants the fuller UX in this change, it requires backend enrichment (a self-profile `username_last_changed_at` field + a `409`/probe reason discriminator) — a separate backend change this one would then sequence behind.
