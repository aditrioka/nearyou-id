## Context

`:mobile:app` ships Google Sign-In end-to-end as of Mobile #3 (`mobile-auth-google-signin-flow`, archived 2026-05-30): `SignInScreen` → `GoogleSignInClient` ceremony → `POST /api/v1/auth/signin` → `SecureTokenStore` → `RootRouterScreen` token-gated routing to `HomeScreen`, with a Ktor `HttpClient` Bearer interceptor. The one gap is **new-account creation**: Mobile #3 maps `404 user_not_found` to a deliberate dead-end banner ("register via the next app update") and left `RootRouterScreen` generalized so an `AgeGateScreen` slots between `SignInScreen` and `HomeScreen` with one router branch (Mobile #3 design.md line 140).

The backend half is fully shipped and stable:
- `openspec/specs/auth-signup/spec.md` — `POST /api/v1/auth/signup`, body `{provider, id_token, date_of_birth (ISO-8601 YYYY-MM-DD), device_fingerprint_hash?}`, success `201 {access_token, refresh_token, expires_in: 900}`, error taxonomy exactly `{invalid_request(400), invalid_id_token(401), user_blocked(403), user_exists(409), username_generation_failed(503)}`, snake_case wire format (Mobile #3 folded in the snake_case fix + `AuthWireFormatTest`).
- `openspec/specs/age-gate/spec.md` — strict 18+ DOB check (inclusive at exactly 18, strict below), `rejected_identifiers` anti-DOB-shopping blocklist, and byte-identical `403 user_blocked` bodies for "fresh under-18" vs "already-rejected identifier."

This change is **mobile-only**: no backend code, no schema, no new library pins (`kotlinx-datetime` at `mobile/app/build.gradle.kts:77` and Material 3 `DatePicker` are already on the classpath).

## Goals / Non-Goals

**Goals:**
- Ship `AgeGateScreen` (DOB picker + 18+ gate) and the `AuthRepository.signUpWithGoogle` orchestration so a verified Google identity with no account can register.
- Swap the temporary `404`-dead-end for navigation into the age-gate/signup flow (resolving `mobile-auth-signin-404-route-to-age-gate`).
- Map every signup result to an explicit outcome with no generic fallthrough, preserving Mobile #3's PII discipline and copy-via-resources discipline.
- Keep the anti-DOB-shopping blocklist effective by ensuring under-18 attempts reach the server.

**Non-Goals:**
- Apple Sign-In iOS (stays the separate `mobile-auth-signin-apple-ios` change).
- Stronger age *verification* (Apple Declared Age Range API, Google Play Families SDK) — deferred; self-declared DOB ships first.
- Attestation / `device_fingerprint_hash` (deferred with the attestation follow-up family).
- The analytics-consent and location-permission onboarding screens that `docs/03-UX-Design.md` sequences *after* the age gate — those are later changes; Mobile #4 routes a successful signup straight to `HomeScreen` (same terminus as sign-in).

## Decisions

### D1 — Client MUST NOT hard-block under-18; the DOB picker is not constrained to 18+ (SECURITY, load-bearing)

The DOB `DatePicker` MUST allow selecting an under-18 date, and the client MUST NOT locally reject an under-18 submission before calling the server. Client-side validation is **format/sanity only** (valid calendar date; not in the future). The server is the sole authority on the 18+ decision.

**Rationale:** the anti-DOB-shopping blocklist (`rejected_identifiers`, age-gate spec) only works if an honest under-18 DOB reaches `POST /api/v1/auth/signup`, which writes the `(identifier_hash, identifier_type, 'age_under_18')` row and returns `403`. A picker that constrained its range to 18+-only dates — or a client that hard-rejected under-18 before the call — would silently train a minor to pick a fake 18+ date on the first try, the blocklist row would never be written, and a later retry with a fabricated DOB would succeed. Pushing the under-18 DOB to the server is what makes the one-honest-attempt → permanent-block design hold.

- **(rejected) Constrain the `DatePicker` `selectableDates`/`yearRange` to 18+-only.** Defeats the blocklist (above); also leaks the threshold to a casual user. The server's DB CHECK + app-layer guard remain the real gate.
- **(rejected) Client-side 18+ check that blocks the call entirely.** Same failure mode — no server round-trip means no blocklist write.

### D2 — `403 user_blocked` renders one generic blocked message; the client never tries to distinguish reasons

On `403 user_blocked`, the client shows a single generic blocked copy ("*Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas.*"). The client cannot and must not attempt to tell "fresh under-18 rejection" apart from "already-blocked identifier."

**Rationale:** the age-gate spec (§ Rejection body indistinguishability) and auth-signup spec (§ Privacy-preserving blocked body) guarantee the two `403` bodies are **byte-identical**. The client therefore naturally renders the same message for both — preserving the server's privacy property by construction. Branching on any heuristic (timing, etc.) would be both pointless and a privacy regression.

### D3 — Reuse the verified Google ID token from the sign-in ceremony; refresh once on `401`

When `/signin` returns `404`, `AuthRepository` carries the verified `id_token` from that `GoogleSignInResult.Success` into the age-gate flow (via the `NoAccount` outcome → navigation arg). `signUpWithGoogle(dob)` reuses it for `POST /api/v1/auth/signup`, so the user does **not** see a second Google account sheet. The `id_token` MUST NOT be logged (consistent with Mobile #3's `LogLevel.HEADERS` + `Authorization` sanitization posture). **Edge case:** Google ID tokens are short-lived (~1h); if `/signup` returns `401 invalid_id_token` (token staled between the `404` and the DOB submission), `signUpWithGoogle` re-invokes `GoogleSignInClient.signIn()` exactly once to obtain a fresh token and retries `/signup` once; a second `401` surfaces a terminal token-invalid state.

- **(rejected) Re-run the Google ceremony on `AgeGateScreen` entry.** Shows the Google sheet twice for one continuous registration, worse UX, and is unnecessary because the token is valid for the seconds-to-minutes a DOB pick takes. Reuse-with-one-refresh gets the same robustness without the double prompt.

### D4 — `device_fingerprint_hash` omitted from the `/signup` body

The signup request body carries only `{provider, id_token, date_of_birth}`. `device_fingerprint_hash` is omitted (the auth-signup spec lists it optional), consistent with Mobile #3 Decision 9 — attestation (Play Integrity / App Attest) is deferred. Tracked by the existing `mobile-auth-signin-attestation-fingerprint-hash` follow-up family.

### D5 — Apple Sign-In iOS is NOT bundled here

Mobile #4 ships the age-gate/signup flow for the **Google** identity on both platforms, mirroring Mobile #3's Google-on-both substrate posture. This resolves the `mobile-auth-signin-apple-ios` follow-up's open question ("bundle with #4 or ship separately?") in favor of **separately** — Apple Developer Program enrollment + entitlements + cert setup are gating costs that would balloon this change's scope and risk.

### D6 — Self-declared DOB only; stronger age *verification* deferred (with explicit user buy-in)

Mobile #4 ships self-declared DOB — the MVP-standard approach and what the backend already enforces. The cross-checks `docs/06-Security-Privacy.md` § Verification names (Apple Declared Age Range API on iOS 18+, Google Play Families SDK on Android) are **deferred** to a new `FOLLOW_UPS.md` entry `mobile-age-gate-stronger-verification`. This was an explicit user decision during `/next-change` Phase A, made with the regulatory context in view: PP 17/2025 ("PP TUNAS", in effect ~March 2026) pushes toward age *assurance*, not just self-declaration — so the follow-up is a real, dated launch-hardening item, not speculative.

### D7 — `AgeGateScreen` UI: Material 3 `DatePicker`, theme-aware, PII-clean

`AgeGateScreen` is a Voyager `Screen` in commonMain using the Compose Multiplatform Material 3 `DatePicker` (already on the classpath). It renders under `NearYouTheme` (light/dark), reuses the brand-logo pattern from `SignInScreen`/`HomeScreen`, and sources every string via `stringResource(Res.string.X)` (CMP Resources — no hardcoded literals). It MUST NOT render the Google `email` or `displayName` anywhere (inherited from Mobile #3's error-state PII rule); the identity payload is consumed only for the `/signup` call body.

## Risks / Trade-offs

- **A determined minor lies about their DOB** → no client-side measure stops this; the blocklist only catches the honest single-attempt minor. Mitigation: out of scope by design — stronger verification (D6 follow-up) is the real mitigation, and the server remains authoritative. The 18+ posture + UU PDP/PP TUNAS compliance is met at the self-declaration tier that the whole app category uses at MVP.
- **`id_token` expiry between `404` and DOB submission** → `401 invalid_id_token`. Mitigation: D3's one-shot silent refresh + retry; a second `401` is a terminal, user-actionable state (re-start sign-in).
- **Carrying `id_token` through navigation state risks accidental logging/leak.** Mitigation: never log it (Mobile #3 sanitization posture), keep it out of any UI string, and don't persist it (in-memory navigation arg only; not written to `SecureTokenStore`).
- **`409 user_exists` mid-flow** (a race where the account got created between `/signin 404` and `/signup`) → route the user back to sign in rather than erroring. Mitigation: explicit `409 → SignInScreen` outcome with "account already exists, please sign in" copy.
- **Copy not yet UX-reviewed** → `docs/03-UX-Design.md` § Age Gate Screen gives the under-18 reject copy verbatim but not the title/CTA/loading strings. Mitigation: derive sensible Bahasa Indonesia strings consistent with Mobile #3's register, flag for review in Open Questions.

## Migration Plan

No data migration. The only "migration" is behavioral, all within `:mobile:app`:
1. Add the `mobile-age-gate` strings to `:shared:resources`.
2. Add `AgeGateScreen` + `AuthRepository.signUpWithGoogle` + signup DTO.
3. Add the `RootRouterScreen`/navigator branch and swap the `mobile-auth-signin` `404` handler from "banner on `SignInScreen`" to "navigate to `AgeGateScreen` carrying the identity."
4. Retire `signin_error_no_account` from the `404` path (keep only if a narrow network-edge fallback still needs it; otherwise remove the string).
5. Pre-archive staging smoke (real Google account with no user row → DOB → `201` → Home; under-18 DOB → `403` blocked copy; existing-account Google → `409` → routed to sign in).

Rollback: revert the branch; Mobile #3's `404`-dead-end behavior returns. No persisted state to unwind.

## Open Questions

- **Copy review.** Title / DOB-label / create-account-CTA / loading / `409`-account-exists / network / token-invalid strings are derived, not docs-canonical (only the under-18 reject copy is verbatim in `docs/03-UX-Design.md`). Confirm wording at review.
- **`signin_error_no_account` fate.** Fully remove the string, or keep it for a narrow "backend reachable but ambiguous" edge? Default: remove from the `404` path; decide retention at implementation.
- **Post-signup terminus.** Mobile #4 routes a `201` straight to `HomeScreen` (parity with sign-in). The analytics-consent + location-permission screens that `docs/03-UX-Design.md` sequences after the age gate are explicitly later changes — confirm that's the intended MVP sequencing and not an omission.
