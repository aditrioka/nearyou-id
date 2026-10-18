## Context

The username surface has two layers. The Settings AKUN row "Ganti username" pushes `UsernameCustomizationRoute`, and that route-scoped screen owns the Free/Premium gate (`mobile-premium-username` design D2). The screen does a self-profile read on entry for `isPremium`. Settings does no self read; both `mobile-settings` and `mobile-premium-username` say so explicitly.

The backend cooldown is DB-authoritative on `users.username_last_changed_at`. `UsernameChangeService.cooldownRemaining` computes `lastChanged + 30d − now`. Only the customization `PATCH` writes the column (`UPDATE users SET username = ?, username_last_changed_at = NOW()`), so non-null means "customized at least once".

## Goals / Non-Goals

**Goals**
- Expose the caller's own `username_last_changed_at` on the self-profile read, and only there.
- At the Settings entry point, show the proactive cooldown as a disabled row with a day countdown (#333).
- Show the docs/03 downgrade copy to a Free user who customized before (#335), as a Settings banner and as the username gate's body.

**Non-Goals**
- **A proactive cooldown state inside the username screen.** The disabled row blocks the normal path into the screen during cooldown. The rare ways in (the Settings read failed, or the user tapped before it landed) still get the authoritative reactive `429 cooldown_active` countdown. A second proactive surface would add a state to the screen without changing what the user learns.
- **Exposing the field on other-user reads or in admin.** Other users must not learn when someone renamed. The operator already has the Premium Username Change Oversight surface.
- **Changing the backend cooldown or `PATCH` semantics.** None change.

## Decisions

### D1. One raw field: `usernameLastChangedAt`, not a server-computed `cooldownRemainingSeconds` / `canChangeUsername`

docs/03 § Cooldown Messaging names this field, and it answers both issues: #333 needs the time, and #335 needs only non-null. A server-computed "seconds remaining" would be skew-free, but it goes stale while Settings stays open, and #335 would still need a second field. The client mirrors the 30-day constant, just as `UsernameFormatGuard` mirrors the backend format rules. The reactive `429` stays authoritative, so device clock skew can only show a wrong day count or leave the row enabled. It can never let a change through.

**Wire:** self-only, ISO-8601 `Instant.toString()` (the `ChatRoutes` `createdAt` precedent), camelCase, and omitted when null (`explicitNulls = false`). Other-user reads never select the column, so the `SQL_OTHER` path cannot leak it.

### D2. Settings gets its own NavEntry-scoped `SettingsUsernameEntryViewModel`

Settings already hosts one VM per concern: `SettingsViewModel` (logout + toggles), `SettingsAccountDeletionViewModel` and `SettingsDataExportViewModel`. The username entry state is a fourth concern with its own read, so it gets its own VM. Folding it into `SettingsViewModel` would grow a VM that docs/11 § 2.2 already flags as known debt (8 `StateFlow`s). The new VM exposes ONE `StateFlow<UsernameEntryState>`. It is a single source flow with no derived inputs, so `asStateFlow()` is used, as in its two sibling Settings VMs.

It reads through the existing stateless `ProfileFlow.loadProfile(selfId)` and `SelfUserIdProvider`, the same seam the username screen uses (docs/11 § 2.6; no ApiClient in the VM). Settings resolves both through the screen's existing fail-safe `koin.getOrNull` idiom. An unbound graph (the existing Settings screen tests) or a failed read leaves the state `Open`. That is today's row, so nothing regresses.

**Freshness:** the read runs on init, and the screen forwards every `ON_RESUME` to `refresh()`. That is the docs/11 § 2.3 "data the overlay itself edited" idiom, as in `ProfileScreen`. A guard makes the first resume a no-op while the init load is in flight. A failed re-read keeps the last state. So a return from a successful change shows the 30-day cooldown, and a return from a purchase drops the banner once the tier has flipped.

### D3. The projection is a pure function of the profile and `now`

```
usernameEntryState(profile, now):
  profile.isPremium && cooldownDaysRemaining(profile.usernameLastChangedAt, now) != null → Cooldown(days)
  !profile.isPremium && profile.usernameLastChangedAt != null                          → Retained(profile.username)
  else                                                                                 → Open
```

`usernameCooldownDaysRemaining(lastChangedAt, now)` returns `null` when the value is null or `now ≥ lastChangedAt + 30d`. Otherwise it returns the remaining whole days rounded up, through the shipped `cooldownDays(seconds)`. That is the same rounding the reactive `Retry-After` path uses, so the proactive and reactive counts agree. `now` is injected (`() -> Instant`, default `Clock.System.now()`), so the projection is deterministic in tests.

**Billing retry:** `isPremium` is `premium_active` only, but the backend lets `premium_billing_retry` change usernames. A billing-retry user is therefore read as Free:
- In cooldown, they see the row enabled, and the reactive `429` still counts down.
- If they customized, they see the retained banner, which asks them to (re)activate Premium.

This is the same mis-classification `mobile-premium-username` already accepts for its gate ("harmless because re-subscribing is the intended action"). Fixing it would need a second wire field for a billing edge case.

### D4. Where the downgrade copy renders

docs/03 calls it an "in-app banner", so the primary surface is a non-blocking banner at the top of Settings. That is the existing banner slot (`DeletionScheduledBanner`, `DataExportReadyBanner`), seen without tapping anything. It uses `secondaryContainer` because it is informational, not an error. It has no button: the "Ganti username" row directly below leads to the gate's "Aktifkan Premium".

The username gate shows the same copy in place of "Ganti username adalah fitur Premium", so a customized user who taps through is not pitched as if they never had the perk. The gate tests "customized" (`usernameLastChangedAt != null`) and not `!isPremium`. That way it also covers the downgrade race, where the self read said Premium but a probe or submit met a `403`.

The webhook-lag activating notice (#517) takes precedence on both surfaces: the banner hides while `rememberPremiumActivating()` is true, and the gate keeps its activating body.

### D5. A disabled `SettingsRow`, and why the subtitle is not muted

`SettingsRow(enabled = false)` sets `clickable(enabled = false)`, which also gives the node the disabled semantics. It mutes the title, icon and chevron to the M3 disabled alpha (0.38). The subtitle stays `onSurfaceVariant`, because in this state it is the message, the countdown, and muting it would fail contrast for text the user needs to read.

## Risks / Trade-offs

- **Client clock skew** can show a day count off by one or more, or enable the row early. The reactive `429` governs, as described in D1.
- **One extra self-profile `GET` per Settings open and resume.** It is cheap and bounded (one row plus two `COUNT`s). It is the same read the profile tab already does on every resume.
- **SettingsScreen grows past the ~400 LOC soft cap** (already 588). The banner sits with its two sibling banners in the same file. Splitting the Settings file is a separate refactor, not this change's.

## Migration Plan

Additive wire field; no migration. Ship order inside the one PR: backend + mobile together. An older client ignores the key, and a newer client against an older backend sees it absent and treats it as "never customized" (`Open`).
