## Context

"Hide Distance" is a Phase 4 Premium perk (`docs/01-Business.md` § Hide Distance Mechanics). The product rule: a Premium user toggles it on and the **distance number** disappears symmetrically — others stop seeing it on the activator's posts, and the activator stops seeing it on everyone's posts — while city name, the 5km floor, and time-ordering stay intact.

Current state established by codebase reconciliation:

- **Only `GET /api/v1/timeline/nearby` emits a distance number.** `NearbyPostDto.distanceM: Double` is the sole distance-bearing wire field. `following-timeline` / `global-timeline` specs already mandate **no** `distanceM`/`distance_m`; `single-post-read`, `post-creation` read, search, and chat-embedded all leave it null ("v1 has no viewer-location context"); push bodies never carry distance. So the 6-surface docs/01 checklist is **behaviorally concentrated on Nearby**, with every other surface a no-op no-regression assertion.
- The shared **`DistanceRenderer.render(Double): String`** (`:shared:distance` commonMain) is a pure formatter whose `distance-rendering` spec forbids it from altering its input — suppression cannot live inside it.
- The mobile **`PostCard` already takes `distanceM: Double?`** and renders the distance row only when non-null (`mobile-post-card` spec, "Global variant renders city only") — so an omitted distance needs **no card change**.
- The mobile Settings screen already ships a **deferred** PRIVASI > "Sembunyikan jarak" row that surfaces "Segera hadir" and performs no write (`mobile-settings` spec line 53; tracked by follow-up [#267](https://github.com/aditrioka/nearyou-id/issues/267)). This change promotes it to a real toggle.
- Premium-effective gating has an established precedent: `user-profile-read`'s `isPrivate` formula gates `private_profile_opt_in` on `subscription_status IN ('premium_active','premium_billing_retry')`.

## Goals / Non-Goals

**Goals:**
- A Premium-effective, symmetric (author-OR-viewer) suppression of the Nearby distance number, owned by a new `hide-distance` capability.
- A `users.hide_distance_opt_in` column + a `PATCH /api/v1/user/hide-distance` write endpoint.
- Promote the deferred mobile Settings "Sembunyikan jarak" row to a backed Premium-gated toggle.
- Keep the shared `DistanceRenderer.render()` pure; suppress by omitting the field upstream.
- Spec-assert that every non-Nearby surface remains distance-free (no-regression).

**Non-Goals:**
- No change to the 5km floor, the jitter/fuzz order, or the `display_location`-only invariant.
- No new viewer-relative distance on post-detail / profile / search / chat (they stay null by design).
- The Premium Tenure Counter (separate docs/01 feature) is out of scope.
- No tier gate on the *write* (effectiveness is read-gated; see D5).
- No `DistanceRenderer` signature change; no new library / `libs.versions.toml` entry.

## Decisions

**D1 — Suppression is server-side field omission, not client-side hiding.** When the rule says hide, the backend OMITS `distanceM` from the Nearby response entirely (the wire never carries the number). Alternative considered: send the number and let the client hide it — rejected because the privacy intent is that the value not leave the server, and a client could read the wire regardless. The shared `Json` already uses `explicitNulls = false`, so a null `distanceM` is omitted on the wire automatically.

**D2 — `DistanceRenderer.render()` stays pure; the visibility decision lives at the Nearby read path.** The hide decision maps to `distanceM: Double?` (null ⇒ hidden). Alternative considered: a `render(meters, hidden)` overload or a `renderHidden()` variant — rejected, it pollutes the spec-mandated pure-formatter contract and spreads the rule across surfaces. A small pure helper `effectiveDistanceMeters(rawMeters, authorHides, viewerHides): Double?` (returns null when either hides) MAY live in `:shared:distance` or the timeline service — design-time leaning: keep it in the `timeline` service (it is a Nearby-read concern, and `:shared:distance` should stay rendering-only), but it MUST be unit-tested as a pure function. This model supersedes the stale `renderDistance(viewer, post, hideDistance)` snippet in `docs/05` § renderDistance + the `renderDistance(post, viewer)` reference in `docs/01` § Hide Distance Mechanics (both already diverged from the shipped pure `render(Double)`); this PR amends both docs to match (tasks.md 8.1).

**D3 — Premium-effective gating reuses the `user-profile-read` EFFECTIVE formula:** `hide_distance_opt_in = TRUE AND subscription_status IN ('premium_active','premium_billing_retry')`. Note this deliberately includes `premium_billing_retry` (an effective-Premium state per `subscription-billing-webhook`), matching `isPrivate` — NOT the badge-only `premium_active` test used for `isPremium`. A Free user with a stale `TRUE` flag therefore reads as OFF.

**D4 — Symmetric rule evaluation.** For each Nearby row, hide iff `authorEffectiveHide OR viewerEffectiveHide`. The **author's** `hide_distance_opt_in` + `subscription_status` are projected as a single boolean from the already-joined author row (`visible_users`/self-arm `users`) — no new join. The **viewer's** effective-hide is resolved once per request (the viewer id is the principal; a single indexed-PK read, or folded into the existing query as a scalar) and applied to every row — when the viewer hides, all rows drop the number. Both Nearby arms (visible + self, per `shadow-ban-feed-self-visibility`) honor the rule identically; on the self arm author==viewer so the two terms coincide. The radius FILTER and `(created_at DESC, id DESC)` ordering are untouched — no ordering leak.

**D5 — The write endpoint is write-anytime + read-gated, not tier-gated.** `PATCH /api/v1/user/hide-distance` (JWT-required, body `{"hideDistance": <bool>}`) stores the bit for any caller; effectiveness is enforced at read (D3). Rationale: this mirrors `private_profile_opt_in` (a Free user can legitimately hold a stale `TRUE` after downgrade — the very case `user-profile-read` guards), and a boolean preference is better modeled as write-anytime than as the consequential, cooldown-bearing username PATCH (which 403s Free). The **mobile UX is the tier gate** — Free users see the Premium upsell affordance and never call the endpoint. Defense-in-depth: even a direct Free write produces no effect. Mirrors `ConsentRoutes`/`ConsentRepository` (`UPDATE users SET … WHERE id = ?`); the `hide_distance_opt_in` column is neither `username` nor `private_profile_opt_in`, so it is outside both Detekt write-allowlists — no `@allow-*` annotation, no new lint rule.

**D6 — Schema: additive column, no backfill.** `ALTER TABLE users ADD COLUMN hide_distance_opt_in BOOLEAN NOT NULL DEFAULT FALSE` in **V23** (latest is V22), mirroring `private_profile_opt_in` (V2). The default covers every existing row; no data migration. The V23 number overlaps in-flight `premium-image-upload-pipeline` (#325) and possibly `privacy-flip-worker` (#321) — a one-column add **renumbers trivially on rebase**; resolve at squash-merge sequencing per `openspec/project.md` § Archive commits touching shared specs.

**D7 — Mobile: promote the deferred row; reuse the null-tolerant card.** `SettingsScreen` swaps the "Sembunyikan jarak" deferred affordance for a real M3 `Switch` row wired through a `HideDistanceRepository`/ApiClient seam (per docs/11 §2.6; the `SettingsViewModel` holds the toggle state per §2.2). Free users keep the Premium upsell/disabled affordance (mirror the username-customization Premium-entry pattern). `NearbyPostDto.distanceM` (mobile wire DTO) becomes `Double? = null` so an omitted value parses; the already-`Double?` `PostCard`/`PostCardModel`/`NavKey` chain renders city-only with zero card change. Visual target: mockup frame 16 (`dev/mockups/nearyou-screens-mockup.html`, docs/11 §2.8) — generate the measurement annex at apply time.

**Standards conformance (docs/11 Pattern Registry).** This change builds ONLY on registered patterns with **zero deviations**, so it amends no docs/11 pattern: backend layering §3.1 (`UserHideDistanceRoutes` → repository write, mirroring `ConsentRoutes`; Nearby change stays in `NearbyTimelineService` + repository), JDBC discipline §3.2 (single-statement `UPDATE` on the bounded dispatcher; the Nearby read gains projected columns, not new round-trips), mobile data layer §2.6 (`HideDistanceApiClient` + repository; ViewModel never calls the ApiClient), mobile state §2.2 (`SettingsViewModel` `StateFlow` field for the toggle), mobile mockup §2.8 (frame 16). The premium-gating formula reuses the `user-profile-read` precedent rather than inventing a parallel one.

## Risks / Trade-offs

- **Nearby wire field changes from non-null to nullable `distanceM`** → existing clients that hard-require it would break; mitigated because the mobile DTO change ships in this same PR, `explicitNulls = false` already omits null, the shipped `PostCard` is already null-tolerant, and a parse test with an omitted `distanceM` is added.
- **Migration-number collision (V23) with #325/#321** → additive one-column add; renumber-on-rebase is trivial and sequenced at squash-merge.
- **Extra columns/lookup in the hot Nearby path** → the author flag is a projection on an already-joined row (no new join); the viewer flag is one indexed-PK read (or a folded scalar) per request — no N+1, negligible cost.
- **Premium-state nuance (active vs active+retry)** → resolved explicitly in D3 (use the effective active+retry formula, matching `isPrivate`); a `premium_billing_retry` test case guards it.
- **Symmetric semantics misread as one-directional** → spec carries explicit author-on-only and viewer-on-only scenarios plus the both-off (visible) case.

## Migration Plan

1. **V23** additive column (NOT NULL DEFAULT FALSE) — safe, no backfill, deployable independently.
2. Backend: Nearby read honors the rule; `PATCH /api/v1/user/hide-distance` goes live. A pre-mobile state is safe — until a client sets the flag, every row has `FALSE` and behavior is unchanged.
3. Mobile: Settings toggle + nullable Nearby DTO ship together.
4. **Rollback:** the column is additive and defaults `FALSE`; reverting code leaves a harmless unused column (no down-migration needed). No data is transformed.

## Open Questions

- Write-endpoint body field name (`hideDistance` vs `enabled`) and response shape (echo new state vs effective state) — minor; finalize at apply against the `ConsentRoutes` shape. Not blocking.
- Whether `effectiveDistanceMeters` ultimately lands in `:shared:distance` or the `timeline` service — leaning timeline-service (keeps `:shared:distance` rendering-only); confirm at apply. Either way it is a pure, unit-tested function.
