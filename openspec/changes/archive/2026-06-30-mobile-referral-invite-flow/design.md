## Context

The referral system's backend is complete and archived: signup accepts an optional `invite_code` and creates a `pending_activity` ticket (`referral-ticket-creation`), the daily activity-check worker promotes qualifying tickets to `granted` and grants the 1-week Premium entitlements via RevenueCat (`referral-grant-worker`), and every user is assigned a unique `users.invite_code_prefix` (8-char base32 lowercase via `InviteCodePrefixDeriver`, resolved at redemption by `UserRepository.findInviterByInviteCodePrefix`). The reward rules are in [`docs/01-Business.md`](../../../docs/01-Business.md) § Referral System: invitees earn 1 week of Premium per activated registration; an inviter earns 1 week of Premium **exactly once per lifetime** when the **5th** successful referral is confirmed (`ReferralActivityCheckWorker.INVITER_MILESTONE = 5`; sentinel `users.inviter_reward_claimed_at`).

What is missing is **every user-facing surface**: the mobile app neither lets an invitee enter a code at signup, nor lets a user see/share their own code, nor shows referral progress — and there is no read endpoint to fetch the caller's code+progress. This change ships those surfaces. It introduces no schema change and no new dependency.

## Goals / Non-Goals

**Goals:**
- A user can find and copy their shareable invite code from Settings → "Undang teman", and see progress toward the 5-referral inviter reward.
- A new user can optionally enter an invite code during signup; it is forwarded to the (already-accepting) backend so a referral ticket is created.
- A JWT-authenticated `GET /api/v1/user/referral` returns the caller's code + progress, principal-only (no IDOR), PII-safe, fail-soft.
- Complete vertical slice: backend read endpoint + mobile share surface + mobile redemption entry, all in one change.

**Non-Goals:**
- No change to the referral *mechanics* (eligibility, activity gate, anti-collision, grant stacking, reward timing) — all live server-side and are already shipped. This surface is read-only display + an optional signup field.
- No native system share-sheet in v1 (copy-to-clipboard only; share-sheet `expect/actual` deferred — D2).
- No Premium gate on the referral surface — participation is open to all (D3).
- No Flyway migration; no new `libs.versions.toml` entry.
- No deep-link / install-attribution (e.g. Play Install Referrer) — codes are entered manually in v1.

## Decisions

### D1 — The shareable code IS `users.invite_code_prefix`, returned verbatim
The 8-char base32 prefix already serves as the resolvable code at redemption (`findInviterByInviteCodePrefix(code)`), so the read endpoint returns it directly — no second derivation, no new column, no formatting transform. The mobile screen displays it as-is and the signup field passes the typed string straight through to `invite_code`. *Alternative considered:* deriving a separate human-friendlier code — rejected; it would require a new column + a second resolution path and break the existing redemption contract.

### D2 — Share = copy-to-clipboard (v1); native share-sheet deferred
v1 uses Compose's `LocalClipboardManager` (commonMain, already on the CMP classpath) for a "Salin kode" action — zero new dependency, zero `expect/actual`. A native system share-sheet (Android `Intent.ACTION_SEND` / iOS `UIActivityViewController`) needs an `expect/actual` and is **declared as a `docs/12` §3 deferred requirement** in `mobile-referral` (positive statement + negative-guard scenario) with a tracking `follow-up` issue. *Alternative:* ship the share-sheet now — rejected to keep this slice tight and avoid a new platform-glue surface; copy-to-clipboard fully delivers the share use case for v1.

### D3 — The referral surface is open to all tiers (NOT Premium-gated)
`docs/01` § Referral System ("Open signup; invite codes add bonuses") makes referral participation universal — the *rewards* are Premium grants, but anyone can share a code and refer. So `ReferralRoute` carries no `PaywallEntry` and the screen shows no upsell. *Contrast:* `UsernameCustomizationRoute` and `SearchRoute` ARE Premium-gated; referral deliberately is not. This is the key behavioral difference a reviewer should not "fix."

### D4 — `ReferralRoute` is a parameterless `data object`; code fetched by the ViewModel
Mirrors `UsernameCustomizationRoute`: the route carries no payload; the `ReferralViewModel` fetches the code+progress via the read endpoint on entry. The invite code is a non-secret public sharing token, but the route still carries nothing — consistent with the back-stack PII discipline (the route persists to disk on iOS) and the "self-read data is fetched, not routed" pattern. Registered in the `navSavedStateConfiguration` polymorphic `SerializersModule`.

### D5 — Invite code at signup lives in the age-gate ViewModel UI state, not `PendingSignupIdentity`
The `id_token` is a credential and lives in the in-memory `PendingSignupIdentity` holder (never on the route, never logged). The invite code is **not** a credential — it is user-typed, non-secret data — so it lives in `AgeGateUiState` (survives configuration change, re-read on resubmit) and is passed to `AuthRepository.signUpWithGoogle(...)`. The `mobile-age-gate` "Signup call" requirement is MODIFIED to add the optional `invite_code` wire field; the body stays snake_case and still omits `device_fingerprint_hash`. Blank/whitespace input is sent as omitted (the field is `null`), matching the backend's `inviteCode?.trim()?.takeIf { it.isNotEmpty() }` treatment.

### D6 — Progress semantics
The response carries `grantedReferrals` (= `COUNT(referral_tickets WHERE inviter_user_id = caller AND status = 'granted')`, the `ReferralGrantRepository.grantedCountForInviter` shape), `milestone` (= 5, the worker's `INVITER_MILESTONE`), and `inviterRewardClaimed` (= `users.inviter_reward_claimed_at IS NOT NULL`). The screen renders "X dari 5" and a distinct reward-unlocked state once `inviterRewardClaimed` is true (the inviter reward fires exactly once, so past the milestone the count keeps climbing but the reward state stays "claimed"). The endpoint reports **observed server state**; it never computes or grants anything.

### D7 — Backend endpoint mirrors `HideDistanceRoutes` GET exactly
JWT via `AUTH_PROVIDER_USER`, `principal.userId` only (no path/body user-id → no IDOR), `MAX_BODY`/transport discipline not needed for a GET, PII-safe logging (event name + exception class only, never the token/sub/code), `CancellationException` rethrow, fail-soft `500` on repository error. **Lint:** the `invite_code_prefix` read needs **no username/privacy-flag *write* allowlist** annotation (it is a read, not a `username` / `private_profile_opt_in` write). It DOES, however, `SELECT ... FROM users`, and `users` is a `BlockExclusionJoinRule`-protected table — so the SQL-holding property MUST carry `@AllowMissingBlockJoin("own-row self read scoped to id = caller; a user cannot block themselves; not a feed/visibility read")`, exactly as the cited `HideDistanceRepository.getHideDistance` precedent does. The annotation goes on the `const`/`val` holding the SQL string, NOT the function (the rule matches the SQL-bearing property). The `referral_tickets` granted-count query is NOT annotated — `referral_tickets` is deliberately outside the protected-table set. The DTO is bare camelCase (`inviteCode`, `grantedReferrals`, `milestone`, `inviterRewardClaimed`) consistent with the timeline/hide-distance wire convention.

### D8 — No mockup frame exists for the referral surface
The mockup board (`dev/mockups/`, binding rule `docs/11` § 3.6) has **no** referral frame (verified: only `README.md` + the admin board mention "invite/referral" incidentally). The screen is therefore built from the `mobile-design-system` substrate (the `mobile-ui-foundation` checklist) + the `docs/01` mechanics, reusing the Settings/sub-surface visual idiom (the `ConsentSettingsScreen` / `BlockedUsersScreen` precedent — both of which also lack a dedicated frame). **Decision: substrate-only for this change; the referral mockup frame is deferred** to a later board pass via a `follow-up` issue (task 7.2) — NOT "add a frame OR defer", but explicitly defer. `docs/11` § 3.6 conformance is satisfied by the substrate, not a frame, for this change.

### Standards conformance (docs/11 Pattern Registry — required)
This change introduces **no new patterns**; it consumes the existing skeleton:
- **State holder:** `ReferralViewModel` is an androidx `ViewModel` (commonMain) exposing exactly **one** `StateFlow<ReferralUiState>` via `stateIn(WhileSubscribed)` — the audit #409/#410/#414 single-`stateIn` contract. `AgeGateViewModel` is extended in place (its existing single-state-holder shape preserved).
- **Navigation:** typed Navigation 3 `NavKey` (`ReferralRoute`), `@Serializable`, registered in the polymorphic `SerializersModule` — the `mobile-app-scaffold` typed-nav contract.
- **Data layer:** `ReferralApiClient` (thin shared-`HttpClient` wrapper, sealed `Success`/`Failure` result) + `ReferralRepository` mapping to a domain state — the `HideDistanceApiClient` / `ConsentApiClient` precedent.
- **Backend layering:** Routes → Repository (JDBC), shadow-ban/block joins N/A (self-read of own row + own ticket counts), `visible_*` N/A (no post/user-visibility read). Mirrors `HideDistanceRoutes`/`HideDistanceRepository`.

No deviation from the registry → no `docs/11` § Pattern Registry amendment task.

### Cross-layer scope declaration (docs/12 — required)
This is a **user-facing capability** spanning **backend + mobile** (no admin surface needed). Layers shipped in this change:
- **Backend wire contract:** `GET /api/v1/user/referral` (read-path that returns the referral entity) — shipped here.
- **Mobile client surfaces:** the referral screen (share/progress) + the signup redemption field — both shipped here.
- **Deferred layer (explicit, docs/12 §3):** the native share-sheet — declared as a `mobile-referral` deferred requirement (positive + negative-guard scenario) + `follow-up` issue, NOT silent prose.

No layer of the *usable* capability is silently omitted: redemption (signup field) + sharing (copy) + progress (screen) + the read endpoint are all present.

## Risks / Trade-offs

- **[Copy-only share is lower-friction than a native sheet]** → Mitigation: copy-to-clipboard + a "Bagikan" follow-up issue; the code is short (8 chars) so manual paste into any app is trivial.
- **[A user reads a stale `grantedReferrals` between worker runs]** → Mitigation: the screen states the count reflects *confirmed* (activity-gated) referrals; the activity-check worker is the single source of truth. The endpoint is a pure read of server state, so no double-count risk.
- **[Invite-code typos at signup silently no-op]** → Mitigation: this matches the **existing backend contract** — redemption is best-effort + silent-to-invitee (anti-probing, `ReferralService`); the client surfaces no "invalid code" error by design (a code-validity probe would leak inviter existence). Documented in the spec.
- **[New `*RoutesTest` pool exhausts the CI connection budget]** → Mitigation: the routes test `autoClose`s its pool per `docs/11` §3.2 (the connection-budget rule), and the read is a single self-scoped query.
- **[Settings entry-row overlaps in-flight #424 `mobile-data-export-entry`]** → Mitigation: different list rows in `SettingsScreen.kt`; coordinate-not-split per `docs/12` §2. Whichever merges first, the second rebases a one-row addition.

## Migration Plan

No DB migration. Deploy order is irrelevant for correctness (the endpoint is additive; the signup field is optional and already honored): backend `GET /api/v1/user/referral` can ship before/with the mobile client. Rollback = revert the PR; no data written by this change (it is read-only display + an existing-contract signup field). The referral schema and workers are untouched.

## Open Questions

- **Copy ("Undang teman", reward-unlocked phrasing):** final Bahasa Indonesia strings to be confirmed at apply time against the existing Settings/consent copy register (not behavior-affecting).
- **Reward-unlocked visual treatment:** badge vs. inline text once `inviterRewardClaimed` — a `mobile-ui-foundation` apply-time decision within the design-system substrate (D8), not a spec'd behavior.
