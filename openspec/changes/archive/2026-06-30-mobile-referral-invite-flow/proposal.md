## Why

The referral growth/revenue loop is **fully built on the backend but 100% unreachable from the mobile app** — a [`docs/12-Integration-Contracts.md`](../../../docs/12-Integration-Contracts.md) cohesion gap. `POST /api/v1/auth/signup` already accepts an optional `invite_code` ([`SignupRoutes.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/auth/signup/SignupRoutes.kt) → `SignupService` → `ReferralService.createTicketIfInvited`), every user is assigned a `users.invite_code_prefix` (the 8-char base32 shareable code; `InviteCodePrefixDeriver`), and ticket-creation + grant-worker + activity-check are all shipped and archived (`referral-ticket-creation`, `referral-grant-worker`). But the mobile app has **no invite-code field at signup** (the invitee can never redeem a code), **no share-your-code surface**, **no progress display**, and there is **no backend read endpoint** for a user to fetch their own code. The growth engine — open-signup virality, plus the Premium grants it drives — is dark.

Now: under the Balanced cadence ([`openspec/project.md`](../../../openspec/project.md) § Mobile-First to Full-Demo Priority) this spans two live lanes (mobile follow-ups + Phase 4 / revenue) and is a clean complete-vertical-slice pick with **no Flyway migration** (the referral schema already exists), so it merges safely alongside the in-flight PRs. Mechanics are spec'd in [`docs/01-Business.md`](../../../docs/01-Business.md) § Referral System ("invite code in Settings"; invitee reward = 1 week Premium per activated registration; inviter reward = 1 week Premium exactly once at the 5th successful referral) and roadmap [`docs/08-Roadmap-Risk.md`](../../../docs/08-Roadmap-Risk.md) Phase 4 #21–23.

## What Changes

- **Add a backend read endpoint** `GET /api/v1/user/referral` (JWT-required, principal-only — no IDOR surface) returning the caller's shareable invite code (`users.invite_code_prefix`) plus referral progress (granted-referral count, the 5-referral milestone, and whether the inviter lifetime reward is already claimed). Mirrors the existing per-user [`HideDistanceRoutes.kt`](../../../backend/ktor/src/main/kotlin/id/nearyou/app/user/HideDistanceRoutes.kt) GET precedent. No schema change.
- **Add the mobile referral surface**: a new `ReferralRoute` (parameterless `NavKey`), a `ReferralScreen` + `ReferralViewModel` showing the user's invite code (copy-to-clipboard), progress ("X dari 5") toward the inviter reward + reward-unlocked state, and explanatory copy; reached from a Settings **"Undang teman"** entry row (per `docs/01` "invite code in Settings"). Referral participation is **open to all** — NOT Premium-gated.
- **Wire invite-code redemption at signup**: the `AgeGateScreen` gains an **optional** "Kode undangan (opsional)" input; the signup request adds an optional `invite_code` field (the backend already accepts it). Omitted/blank when not entered.
- **Strings** added to `:shared:resources` CMP Resources (Bahasa Indonesia; no hardcoded UI strings).
- **Share is copy-to-clipboard for v1**; a native system share-sheet (`expect/actual`) is an explicit `docs/12` §3 deferred requirement with a tracking `follow-up` issue.

No breaking changes (the new endpoint is additive; the signup `invite_code` field is optional and already honored server-side).

## Capabilities

### New Capabilities
- `referral-read`: the JWT-authenticated `GET /api/v1/user/referral` read endpoint returning the caller's own shareable invite code + referral progress (granted count, milestone, inviter-reward-claimed). Principal-only, no IDOR, PII-safe logging, fail-soft.
- `mobile-referral`: the mobile referral surface — the `ReferralRoute`/`ReferralScreen`/`ReferralViewModel` (invite-code display + copy-to-clipboard, progress + reward state), the Settings "Undang teman" entry, the data layer (`ReferralApiClient` + `ReferralRepository`), and the deferred share-sheet requirement. Open to all tiers.

### Modified Capabilities
- `mobile-age-gate`: the signup request contract gains an **optional** `invite_code` (snake_case wire field; the requirement currently states the body carries *exactly* `{provider, id_token, date_of_birth}`), and `AgeGateScreen` renders an optional invite-code input. The field is non-secret data held in the age-gate ViewModel UI state (NOT the `PendingSignupIdentity` credential holder).

## Impact

- **Backend** (`:backend:ktor`): new `ReferralReadRoutes` + `ReferralReadRepository` (or equivalent) + response DTO, wired in `Application.kt`; reads `users.invite_code_prefix`, `referral_tickets` granted count, `users.inviter_reward_claimed_at`. No migration. New `*RoutesTest` (must `autoClose` its pool per the DB connection-budget rule) + repository test.
- **Mobile** (`:mobile:app`): new `referral` package (data layer + screen + ViewModel), `ReferralRoute` registered in the `navSavedStateConfiguration` polymorphic `SerializersModule`, a Settings entry row, the `AgeGateScreen`/`AgeGateViewModel`/`SignUpRequest`/`AuthApiClient.signUp` invite-code wiring. New CMP Resource strings.
- **Specs**: `referral-read` + `mobile-referral` ADDED; `mobile-age-gate` MODIFIED.
- **APIs**: `GET /api/v1/user/referral` (new); `POST /api/v1/auth/signup` request gains optional `invite_code` (server contract unchanged — client now sends it).
- **Dependencies**: none new — Compose `LocalClipboardManager` is already on the CMP classpath; no `libs.versions.toml` change.
- **Mockups**: no admin/mobile mockup frame exists for the referral surface → built from the `mobile-design-system` substrate + `docs/01` mechanics; the absent frame is flagged as a known gap (tasks item).
