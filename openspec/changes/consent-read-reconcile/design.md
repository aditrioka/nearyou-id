## Context

Consent is stored per account in `users.analytics_consent`:

- JSONB, `NOT NULL DEFAULT {"analytics": false, "crash": true, "ads_personalization": false}`;
- added in V2, documented in docs/06 § Analytics & Tracking Consent.

The backend ships only `PATCH /api/v1/user/consent` (`analytics-consent-update`). The mobile app keeps a durable device mirror, `ConsentSnapshotStore` (SharedPreferences / NSUserDefaults). It is written only on a PATCH `200` and read by four consumers:

1. the crash startup gate (`KoinInit.startCrashReporting`);
2. the RootRouter consent re-gate (snapshot `null` → `ConsentRoute`);
3. the consent-gated analytics tracker and ads controller;
4. consent Settings seeding.

Because the mirror can only be filled by a PATCH from this device, a returning user on a new device or a reinstall starts with an empty mirror:

- the crash gate runs with crash ON;
- the next cold start re-gates them onto `ConsentScreen` showing the V2 defaults;
- one "continue" overwrites the crash decline the server already holds;
- Settings shows the defaults instead of the account's real choices.

Logout also leaves the mirror in place, so a second account signing in on the device inherits the first account's consent.

Found while tracing the sign-in path: the shared `HttpClient`'s `Auth { bearer }` plugin caches the `BearerTokens` from `loadTokens`. The cache is cleared only by its own refresh, or by `ProactiveTokenRefreshTrigger.clearToken()`. Logout does not bump `token_version` (the ≤15-min access token expires naturally, per `LogoutService`). So after a logout followed by a sign-in in the same process, the new session's requests keep carrying the previous account's access token until it expires. Any read issued right after sign-in, the new consent reconcile included, would act as the previous account.

## Goals / Non-Goals

**Goals:**

- Make the server's consent the source of truth on every device, without breaking the re-gate. Two cases must behave correctly:
  - a re-gated user must still re-confirm consent;
  - a same-device user who skipped consent must still be re-gated.
- Honor a server-side crash decline from sign-in onward: in the running session at once, and at every later cold start.
- Settings reflects a change made on another device.
- Consent never leaks across accounts on one device. The same goes for the bearer token.

**Non-Goals:**

- A server-side "consent completed" marker. Snapshot presence stays the per-device completion flag, as the re-gate requirement says.
- A background retry of a failed read. A failed read keeps the local state; the next sign-in or Settings entry retries.
- Changing the PATCH contract or the onboarding submit flow.
- Admin surface. Admin has no consent view, and this change adds no admin-visible entity or field (see Cross-layer scope).

## Decisions

**D1: The GET lives in the existing `consentRoutes(...)` and reads through `ConsentRepository`.**

- Same file, same `authenticate(AUTH_PROVIDER_USER)` block as the PATCH, so `Application.kt` is untouched. This also avoids the contested wiring file.
- The route calls the repository directly, with no service. This is the shipped shape of the PATCH and of the `GET /api/v1/user/hide-distance` / `private-profile` own-flag reads: a single own-row statement has no business rule or transaction to host.
- Alternative considered: a `ConsentService`. Rejected: it would be an empty pass-through.

**D2: SQL is `SELECT analytics_consent FROM users WHERE id = ?` on raw `users`, annotated `@AllowMissingBlockJoin`.**

- This is an own-row read, with the same rationale and annotation as `HideDistanceRepository.SQL_SELECT`:
  - a shadow-banned caller reads their own settings;
  - you cannot block yourself.
- The three booleans are extracted in SQL with a per-key `COALESCE` to the V2 default, so an older or partial object can never read a missing `crash` as `false` (a decline). JDBC `getBoolean` would map a SQL `NULL` to `false`.
- The caller's row always exists post-auth (`configureUserJwt` validates `sub ∈ users`). A missing row is therefore treated as an internal error (`500`), never as fabricated defaults.

**D3: The wire shape reuses the existing `ConsentResponse` DTO.**

- `{"analytics", "crash", "ads_personalization"}`, snake_case, identical to the PATCH `200` echo. The client needs only one response DTO.
- `200` for success, `401` from the auth guard, `500` on a read failure.
- No new log content: only the event name `analytics_consent_read_failed` with the error class. No sub, no body, no token, matching the PATCH's PII discipline.
- No rate limit: the precedent is the own-flag GETs. It is a single indexed PK read behind auth.

**D4: Mobile read seam: `ConsentApiClient.fetchConsent()` → `ConsentRepository.fetchConsent(): ConsentSnapshot?` behind `ConsentFlow`.**

- Shape per docs/11 §2.6: the ApiClient returns a sealed `ConsentReadResult` (`Success(dto)` / `HttpError(status)` / `NetworkError(cause)`). The repository maps it to the domain `ConsentSnapshot` or `null`, logging a type/status-only diagnostic.
- `null` means "unavailable". This mirrors `HideDistanceRepository.loadState(): HideDistanceState?`.
- No caller branches on why the read failed:
  - a terminal 401 is already owned globally (shared client refresh → `SessionInvalidator` → `SessionExpiryEffect`);
  - any other failure keeps the local state.
- `ConsentFlow` gains the read so the screens keep one seam and `FakeConsentFlow` drives both directions. The fake's read defaults to `null`, so existing tests are unaffected.

**D5: `ConsentReconciler(flow, store, onCrashConsentChanged)` is the one place that turns a server read into device state.**

- `reconcile(isStillWanted)` does three things:
  - reads;
  - re-checks the caller's guard **after** the suspension;
  - then writes the snapshot and applies `crash` to `CrashReportingController.applyConsent` (close on decline, re-init on re-consent).
- The guard is what stops a stale read from clobbering a newer local save.
- Two callers:
  - `AuthRepository`, on sign-in;
  - `ConsentSettingsViewModel`, on entry, with guard = "the user has not touched a toggle or saved".
- Single concrete class, no interface: its dependencies are already seams (`ConsentFlow`, `ConsentSnapshotStore`, a lambda).
- Alternative considered: duplicate the write-and-apply in each caller. Rejected: two copies of a privacy-relevant ordering.

**D6: Reconcile on sign-in, not at the RootRouter re-gate.**

- The server cannot tell a user who never confirmed consent (V2 defaults) from one who confirmed exactly the defaults. Reconciling whenever the snapshot is empty would therefore silently satisfy the #199 re-gate for a same-device skip / force-quit.
- A sign-in is different: it is a returning account whose server record is the account's state. That record is either a confirmed choice, or the privacy-safe defaults (analytics/ads opt-in OFF, crash opt-out ON per docs/06). The reconcile writes it, so a returning user on a new device lands on Home with their real consent.
- Sign-up does not reconcile: it routes straight to `ConsentScreen`, which writes the snapshot on submit.
- Bounding:
  - `withTimeoutOrNull(CONSENT_RECONCILE_TIMEOUT = 3 s)`, the same budget as the RevenueCat identity bind;
  - it runs **concurrently** with that bind (`coroutineScope { launch; launch }`), so sign-in's worst-case added wait stays 3 s, not 6 s;
  - it runs **before** `SignInOutcome.Success` is returned, so Home's first frame and the ad/analytics gates already see the reconciled snapshot.
- A failed or timed-out reconcile degrades to today's behavior: an empty snapshot, then a re-gate at the next cold start. That re-gate now seeds from the server (D8).

**D7: Consent Settings seeds from the snapshot and then reconciles. The ViewModel moves to one `uiState`.**

- First frame: the snapshot, else the V2 defaults. No spinner and no new strings.
- Then `reconciler.reconcile { !touched }`. On a result, the toggles take the server values.
- Any toggle change, or `save()`, sets `touched`, so a late read neither moves the user's switches nor overwrites a newer save.
- docs/11 §2.2 names multi-`StateFlow` ViewModels as known debt to "consolidate when a VM is next touched". `ConsentSettingsViewModel`'s five flows become one `StateFlow<ConsentSettingsUiState>`, projected from a private `VmState` via `stateIn(WhileSubscribed(5_000))`, mirroring `ConsentViewModel`. No new pattern.

**D8: Onboarding / re-gate `ConsentScreen` shows the server values but never writes them.**

- `ConsentViewModel` starts at the V2 defaults as today. It issues `consentFlow.fetchConsent()` and applies the result to the toggles only while the user has not acted (no toggle change, no continue).
- At signup the read returns the just-created V2 defaults, so it is a visual no-op.
- At a re-gate it shows the account's real choices, so "continue" re-affirms a prior crash decline instead of overwriting it. This covers two cases:
  - a failed sign-in reconcile;
  - a same-device skip after the user changed consent on another device.
- The read never writes the snapshot. Only the confirmed submit does, so the re-gate's completion semantics are unchanged.

**D9: An explicit logout clears the consent snapshot.**

- `ConsentSnapshotStore` gains `clear()`, implemented in the Android and iOS actuals and the in-memory double.
- `SettingsViewModel.confirmLogout` calls it inside the existing `NonCancellable` wipe.
- The next sign-in repopulates the mirror (D6), or the next cold start re-gates.
- The involuntary `SessionInvalidator` path does not clear it: the same account usually signs straight back in, and the reconcile overwrites on success either way.

**D10 (found-while): a new session drops the shared client's cached bearer tokens.**

- After `tokenStore.write(...)` on sign-in and on sign-up success, `AuthRepository` calls `AuthApiClient.clearCachedBearerTokens()`. That function clears every `BearerAuthProvider` on the shared client, the same call `ProactiveTokenRefreshTrigger` already makes, so both share one helper.
- The next request re-runs `loadTokens` and attaches the new session's token.
- Session start is the single choke point that covers every way a session ends (logout, terminal invalidation, deletion). Clearing there needs no hook in each end path.

**Standards conformance (docs/11 Pattern Registry):**

- Backend: route → repository JDBC on the injected pool-bounded dispatcher (§3.1/§3.2, the PATCH's shape), raw-`users` own-row annotation precedent.
- Mobile:
  - data seam `ConsentApiClient` + `ConsentRepository` behind `ConsentFlow` (§2.6);
  - state holders are entry-scoped androidx ViewModels exposing ONE `StateFlow` via `stateIn` (§2.2);
  - the consolidation retires debt; it introduces no pattern.
- No deviation, so no Pattern Registry amendment.

**Cross-layer scope (docs/12):** backend (GET) + mobile (seam, sign-in reconcile, Settings, re-gate screen, logout). **Admin: none.** Admin has no consent surface today. This change adds no entity and no field: it adds a read of an existing column. Admin moderation never reads or edits a user's analytics consent, and docs/02 / docs/06 specify no admin consent view. So no admin layer is deferred, and no docs/12 §3 deferred requirement is needed.

## Risks / Trade-offs

- **An account that never confirmed consent signs in on a second device and is not re-gated.**
  - Its server record is the V2 defaults (analytics/ads OFF, crash ON), which is exactly the pre-consent posture docs/06 prescribes. No opt-in processing starts.
  - The same-device re-gate (#199) is unchanged.
  - Accepted: there is no server marker to distinguish the cases, and adding one is a product decision outside this issue.
- **Sign-in waits up to 3 s on a hung consent read** → bounded, concurrent with the existing billing bind. The shared client's `HttpTimeout` (15 s) and `withTimeoutOrNull` make the worst case explicit.
- **A toggle flips shortly after Settings or the re-gate renders** → the read is one indexed GET. It applies only before the user touches anything, so the user's own input always wins.
- **Stale read clobbers a newer save** → the `isStillWanted` guard runs after the suspension, on the main-confined `viewModelScope`, before the write.
- **The bearer-cache clear could drop a token mid-flight** → it runs only at session start, right after the new pair is persisted. The worst case is one extra `loadTokens` read of the store.

## Migration Plan

- No schema change. The GET is additive, so old clients are unaffected.
- Deploy the backend first, as the normal main deploy does. A new client against an old backend gets `405`/`404` on the GET, which the read seam treats as "unavailable": local behavior, exactly as before.
- Before archive (docs/11 §5): staging branch deploy, then smoke `GET /api/v1/user/consent` (unauth `401`; an authed read matches the PATCH).
- Rollback: revert the PR. Nothing persistent changes shape.

## Open Questions

None blocking. The "never-confirmed account on a second device" trade-off (Risks, first bullet) is recorded for operator visibility.
