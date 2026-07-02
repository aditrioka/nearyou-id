## Why

**Private profile is a headline Premium perk (`docs/01-Business.md` § freemium table — "Private profile (opt-in) | No | Yes | Month 1"), and every layer of it is shipped EXCEPT the one that lets a user turn it on.** The `users.private_profile_opt_in` column exists (V2); the effective-private formula (`private_profile_opt_in AND subscription_status IN ('premium_active','premium_billing_retry')`, plus the 72h grace short-circuit) is implemented in `JdbcUserProfileReader`; the read-side ENFORCEMENT is live in `premium-search` (the "Premium private-profile gate" hides a private Premium user's posts from non-followers, exempting followers); the *downgrade* writer (`PrivacyFlipWorker`, `@allow-privacy-write: worker`) flips it to `FALSE` when Premium lapses; and the privacy-flag-write lint allowlist already **reserves a `user_settings` writer** (`@allow-privacy-write: worker|user_settings`).

But **nothing writes the `TRUE` side.** There is no `/api/v1/user/private-profile` endpoint, and the mobile Settings "Profil privat" row is a deferred dead control (renders chrome, issues no write, tracked by a follow-up issue). A Premium user literally cannot enable a feature they pay for. This change ships the missing `user_settings` writer — completing the Premium privacy-settings trio alongside username customization ✅ and hide-distance ✅.

## What Changes

- **Backend (new `private-profile` capability)** — add `PATCH /api/v1/user/private-profile` (writes `users.private_profile_opt_in` for the JWT caller, the sanctioned `// @allow-privacy-write: user_settings` writer) and `GET /api/v1/user/private-profile` (returns the stored opt-in + the caller's effective-Premium status so the Settings toggle seeds + gates interactive-vs-upsell). Mirrors the shipped `hide-distance` route/repository/DTO precedent exactly, with one deliberate difference: `private_profile_opt_in` IS on the privacy-flag-write allowlist, so the `UPDATE` carries the annotation.
- **Grace-window correctness** — an opt-**out** write (`{"privateProfile": false}`) ALSO clears any pending `privacy_flip_scheduled_at` (the "confirm switch public" action in `docs/03-UX-Design.md` § Downgrade flow privacy flip). Without this, a user in the 72h grace window who toggles private OFF would still read as effectively-private via the grace short-circuit — a broken toggle. This realizes the documented "confirm switch public" path.
- **Mobile (MODIFIES `mobile-settings`)** — promote the PRIVASI > "Profil privat" deferred row to a live Material 3 `Switch` row: seeded on open via `GET /api/v1/user/private-profile`, interactive for effectively-Premium callers (PATCH-on-toggle, revert + non-trapping error on write failure), Premium upsell / disabled affordance for Free callers (no write). Remove "Profil privat" from the deferred-row set. Mirrors the shipped "Sembunyikan jarak" toggle wiring.
- **No migration** — `private_profile_opt_in` (and `privacy_flip_scheduled_at`) already exist (V2). Purely additive endpoint + mobile wiring.
- **No read-path change** — the search Premium-private gate and the self `is_private` projection already enforce/expose private correctly; this change does NOT alter them, and broader read-hiding (timelines, profile page) is explicitly OUT of scope (deferred per docs/12 §3).

## Capabilities

### New Capabilities

- **private-profile** — the user-facing Premium private-profile opt-in: the `PATCH` / `GET /api/v1/user/private-profile` endpoints, the `user_settings` privacy-flag writer, the effective-private reference (unchanged formula), the grace-window "confirm switch public" clear, and the explicit out-of-scope of broader read-hiding.

### Modified Capabilities

- **mobile-settings** — the "Profil privat" row moves from deferred (dead control) to a backed Premium-gated toggle wired to `PATCH` / `GET /api/v1/user/private-profile`.

## Impact

- **Affected specs:** `private-profile` (new), `mobile-settings` (modified).
- **Affected code:** `backend/ktor/.../user/` (new `PrivateProfileRoutes.kt`, `PrivateProfileRepository.kt`, request/response DTOs, Koin wiring in `Application.kt`); `mobile/app/.../privateprofile/` (new `PrivateProfileApiClient` + `PrivateProfileRepository` + sealed outcome), `mobile/app/.../screens/settings/` (`SettingsViewModel` + `SettingsScreen` toggle), `:shared:resources` strings.
- **No Flyway migration; no Supabase-parity change** (additive endpoint over an existing column).
- **Follow-up:** on merge, comment on the "Profil privat" deferred-row follow-up issue that the row is now wired.
