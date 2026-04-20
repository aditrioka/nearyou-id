## Context

Auth-foundation (archived 2026-04-20) shipped RS256 JWT issuance, refresh rotation, sign-in for existing users, and V2 schema (`users`, `refresh_tokens`). `POST /api/v1/auth/signin` returns `404 user_not_found` for unknown provider subjects — there is no supported way to create a user row today other than the `dev/scripts/seed-test-user.sh` helper. This change closes that gap.

The constraint set making signup non-trivial:

- `users` has four NOT NULL columns that are each bound to a separate feature: `date_of_birth` (18+ CHECK), `username` (UNIQUE), `display_name`, `invite_code_prefix` (UNIQUE). All must land in one atomic INSERT.
- `rejected_identifiers` must be pre-checked before DOB parsing to prevent DOB-shopping (a user declared under-18, tries again with a different DOB under the same Google account).
- The username generation algorithm is transactional and depends on `reserved_usernames` + `username_history`, which do not yet exist. They must arrive in the same migration as the first signup.
- The endpoint must share the `ProviderIdTokenVerifier` + JWT issuer + refresh repository with sign-in (auth-foundation already built these) — no duplication.

Related docs (load-bearing):
- `docs/05-Implementation.md § Users Schema (Canonical)` — every NOT NULL column + the `invite_code_prefix` derivation formula.
- `docs/05-Implementation.md § Reserved Usernames Schema` — table shape, triggers, seed list (including the 1- and 2-char rule).
- `docs/05-Implementation.md § Rejected Identifiers Schema` — `(identifier_hash, identifier_type)` UNIQUE, the generic rejection body, indefinite retention.
- `docs/05-Implementation.md § Username Generation & Customization` — the 5-attempt loop + fallback, plus the `released_at > NOW()` guard.
- `docs/08-Roadmap-Risk.md` Phase 1 items 2, 3 (age gate + rejected identifiers) and item 21 (schema subsets including `username_history`, `rejected_identifiers`, `reserved_usernames`).
- `openspec/changes/archive/2026-04-20-auth-foundation/proposal.md` Flag #1 — the explicit deferral this change unblocks.

## Goals / Non-Goals

**Goals:**
- Deliver one usable `POST /api/v1/auth/signup` endpoint returning a token pair, fully wired into the existing auth stack.
- Land `reserved_usernames`, `rejected_identifiers`, and `username_history` in V3 with the canonical shape (no deviations from docs).
- Guarantee all-or-nothing signup at the DB level: if any step fails, no `users`, `rejected_identifiers`, or partial state persists.
- Make the privacy-preserving rejection body indistinguishable between "rejected identifier pre-check hit" and "under-18 DOB declared now."
- Seed `reserved_usernames` completely enough for the signup path to be correct (all 1- and 2-char strings + the documented list).
- Keep the change backend-only. No mobile, no Cloud Run, no attestation.

**Non-Goals:**
- `invite_code?` intake and inviter linkage (that ships with `referral_tickets` in the referral-system change).
- Premium username customization (`PATCH /api/v1/user/username`) — schema only.
- Attestation at signup (Play Integrity / App Attest) — a later attestation-integration change owns the `/infra/attestation` wiring.
- The full 600 × 600 × 100 word-pair dataset. Dev seed (50 × 50 × 10) is enough for this change's test surface. The README next to `wordpairs.json` flags the replacement task.
- A live-network end-to-end Google/Apple signin test. As with auth-foundation, the genuine E2E lands with the mobile change that actually obtains a real ID token. This change's coverage is test-stubbed `ProviderIdTokenVerifier` + hand-rolled `curl` against a dev instance with the existing dev token override.

## Decisions

### 1. Signup as a distinct endpoint, not merged into `/signin`

Merging signup and sign-in into one endpoint (auto-create on 404) is a common shortcut. We don't do it because:

- Signup takes `date_of_birth` which sign-in does not and should not accept. Forcing the union body on every sign-in wastes bandwidth and leaks the rule that "DOB is only read at creation time."
- The failure taxonomies are different. Sign-in has `user_not_found` (404) and `account_banned` (403). Signup adds `user_blocked` (403), `user_exists` (409), `under_age` (handled as `user_blocked`), `invalid_id_token` (401). Collapsing produces a muddy error space.
- Most importantly: sign-in is hot-path on every app cold start after the first signup. Signup runs once per account. Mixing them forces sign-in to carry the age-gate branch for no reason.

Alternative rejected: a flag `?create_if_missing=true` on `/signin`. Same drawbacks + worse discoverability.

### 2. Privacy-preserving rejection body: `user_blocked` covers both reasons

`docs/05-Implementation.md § Rejected Identifiers` explicitly calls out: "return the same user-facing under-18 rejection message to avoid confirming the state to the user." We implement this as a single error code `user_blocked` with a fixed body, used for both:

- Rejected-identifier pre-check hit.
- Fresh under-18 DOB declaration (after we've already inserted into `rejected_identifiers`).

The response body is byte-identical in both cases; only the server-side log line differs so we can audit which branch fired. The client cannot tell whether this is the first or Nth rejection.

Alternative rejected: `under_age` vs `user_blocked` as distinct codes. Leaks the same info the privacy note is designed to hide.

### 3. Age gate is app-layer-first; DB CHECK is backstop

The DB CHECK `date_of_birth <= CURRENT_DATE - INTERVAL '18 years'` is non-negotiable defense-in-depth. But we do the rejection at the app layer **before** hitting the DB because:

- We want to insert into `rejected_identifiers` as a side effect of under-18 rejection; the DB CHECK would just raise an exception without the insert.
- The privacy-preserving body is easier to build from the app layer (we already know the reason).
- The order is: pre-check rejected_identifiers → parse & validate DOB → (on under-18) INSERT rejected_identifiers + return 403 → (on ≥18) proceed to username generation + INSERT users.

If the user lies at the client and the DB CHECK is the only thing that catches it, the DB error surfaces as a 500 (no rejected_identifiers insert). That's an acceptable failure mode because (a) it means the app-layer guard is broken, which is an incident, and (b) the `rejected_identifiers` insert was never the primary control — the DB CHECK is.

### 4. Username generation: canonical 5-attempt loop, fallback always-lands

Docs prescribe exactly:

```
1–2: {adj}_{noun}
3–4: {adj}_{noun}_{modifier}
5:   {adj}_{noun}_{random_5_digit}
fallback: {adj}_{noun}_{uuid8hex}
```

Each attempt: check `reserved_usernames(LOWER(candidate))` → check `username_history(LOWER(old_username))` where `released_at > NOW()` → attempt `INSERT INTO users` → catch `unique_violation` → continue.

On attempt 5 failing, fall back to the UUID-suffixed candidate. The fallback template embeds 8 hex chars derived from `UUID.randomUUID()`, so collision probability is negligible; we do not retry it further. If it fails (operationally implausible), the signup returns 503 `username_generation_failed` and the caller retries — no silent loop.

Decision point: **do we insert the candidate inside the loop or pre-validate then insert at the end?** We insert-then-catch, mirroring the docs. Pre-validation has a TOCTOU gap that grows with concurrency. The INSERT with UNIQUE constraint is the authoritative check; the reserved/history lookups are fast-path filters to avoid wasting writes on obvious misses.

### 5. `invite_code_prefix`: HMAC derived, 8-char base32, 10-char fallback

Per docs: `base32(HMAC-SHA256(invite-code-secret, user_id.bytes))[0..8]`. Deterministic from `user_id`, so we compute it immediately before the `users` INSERT using the client-generated `UUID.randomUUID()` for `user_id` — rather than letting Postgres default `gen_random_uuid()` — so the prefix can go into the same INSERT.

On UNIQUE collision (astronomical at 100k users, but handled for completeness), we regenerate by lengthening to 10 chars per docs. No salt-counter scheme; the prefix is fully determined by `user_id` and `invite-code-secret`, and at 10 chars the birthday-paradox collision probability drops past 2^-35 even at 10M users.

### 6. `display_name` defaults to the generated username at signup

`users.display_name VARCHAR(50) NOT NULL` needs a value. Options:
1. Default to the generated username.
2. Accept `display_name` in the signup body.
3. Require a follow-up `PATCH /api/v1/user/display-name` call.

We pick (1) for this change. It satisfies the NOT NULL without expanding the API surface, and the display name is user-editable later via the (future) profile endpoints. The generated username fits the 50-char cap (worst case `{adj}_{noun}_{uuid8hex}` is ≤60 chars but the 50-char display limit means we truncate the UUID suffix if needed — see the generator impl). Truncation loses uniqueness for display but that's fine because `display_name` is not UNIQUE.

Alternative (2) is a natural extension when mobile signup lands; a follow-up change can add the optional body field without breaking this one.

### 7. V3 migration strategy: one file, everything land together

`V3__signup_flow.sql` creates all three tables + seed insert in one file. No splitting into V3a/V3b. Rationale:

- Signup correctness depends on all three being present.
- The seed insert into `reserved_usernames` is idempotent via `ON CONFLICT DO NOTHING`.
- The `reserved_usernames_protect_seed` trigger must exist before the seed insert to be useful (actually not strictly — the trigger fires on UPDATE/DELETE, and seed is INSERT), but co-location is clearer.

The migration runs against any V2-sourced DB. No backfill is needed for the empty `users` table, which is the only state the V3 migration will ever meet in practice (staging Supabase and prod are both fresh at this point in the timeline).

**Existing seeded dev users**: `seed-test-user.sh` from auth-foundation inserted rows without `invite_code_prefix` (which was NOT NULL UNIQUE in V2). That means either (a) those rows failed to insert on auth-foundation (likely — the seed script would have errored), or (b) the seed script supplied a placeholder. Audit: read the seed script, then either update it to generate a prefix (preferred) or add a tiny backfill DO block at the top of V3. Tasks cover this.

### 8. `invite-code-secret` resolution

Adds one entry to the env-var-based `SecretResolver` chain from auth-foundation:
- Name: `invite-code-secret`
- Env var (dev): `INVITE_CODE_SECRET`
- Format: 32 raw random bytes, base64-encoded in the env var.
- `dev/scripts/generate-rsa-keypair.sh` grows a sibling line emitting the base64 value.

Staging/prod secret-manager wiring is out of scope (lives with the staging-bootstrap change). The same `SecretResolver` interface picks up the GCP implementation when that change lands.

### 9. Repository boundaries

- New `ReservedUsernameRepository` (in `:infra:supabase`) — `exists(lowerCaseUsername): Boolean`.
- New `RejectedIdentifierRepository` (in `:infra:supabase`) — `exists(identifierHash, identifierType): Boolean` and `insert(identifierHash, identifierType, reason)`.
- Extend `UserRepository` with `create(row: NewUserRow): UUID` (returns the generated UUID) and `existsByProviderSub(hash, type): Boolean` (or reuse the existing `findByGoogleIdHash` / `findByAppleIdHash`).

All the cross-table signup logic (pre-check → DOB validate → generate username → derive prefix → INSERT) lives in a `SignupService` class in `:backend:ktor`. Repositories stay dumb.

The transaction boundary is opened at `SignupService` via a `DataSource` connection; every repository call is passed the same connection. This matches the existing `RefreshTokenService` pattern from auth-foundation.

### 10. Word-pair dataset: resource file, not DB table

Docs don't prescribe a DB table. A resource file is faster to load (millisecond cold start), trivially swappable when the full dataset lands, and keeps schema small. Format:

```json
{
  "adjectives": ["ramah", "gesit", ...],
  "nouns": ["harimau", "rinjani", ...],
  "modifiers": ["asli", "jaya", ...]
}
```

Loaded once at app start into three immutable lists. `UsernameGenerator` samples with `java.util.SplittableRandom` (threadsafe, seedable for tests).

Decision: dev seed size is 50 × 50 × 10 = 25 000 combos. Exercises the collision-retry and fallback paths in tests; full dataset (36M combos) is Pre-Phase 1 asset work and swaps in by file replacement.

## Risks / Trade-offs

- **[Risk] Privacy-preserving rejection body could be weakened accidentally.** Someone adding a new `user_blocked` reason in future (e.g., `attestation_persistent_fail`) might add a distinct body. → **Mitigation**: the rejection body is a shared constant `SIGNUP_USER_BLOCKED_BODY` used by all branches; a unit test asserts byte-identity across the three current branches + any future ones.

- **[Risk] Reserved-username seed list omitting a character combination that later becomes controversial (e.g., profanity).** Seed list is documented and committed; adding later requires admin-panel UI, which doesn't exist yet. → **Mitigation**: use `source = 'admin_added'` rows via direct SQL in the interim; the protect-seed trigger only blocks `seed_system` row deletion, so admins can add freely.

- **[Risk] `invite_code_prefix` birthday collisions in a signup surge before the 10-char fallback kicks in.** At 100k users, the collision probability for 8-char base32 is ~10^-4 per new signup. → **Mitigation**: accept the retry cost (one extra `UserRepository.create` INSERT). Alerting on "invite_code_prefix collision retry count > 10/hr" can land with observability. Not blocking.

- **[Risk] DB CHECK failure hidden behind app-layer guard is an incident, not a bug.** If a client-side bypass makes an under-18 DOB reach the DB, we see a 500 instead of a 403. → **Mitigation**: structured log line `"signup.dob_check_db_fallback_fired"` + a Sentry breadcrumb when we catch the DB CHECK failure. The 500 is the right response because reaching the DB means our app-layer code is wrong.

- **[Trade-off] Username generator uses app-layer `SplittableRandom`, not PG `RANDOM()`.** We pay an extra DB round trip per attempt (existence check + INSERT) but get deterministic testability (seedable RNG) and can move the allocation off the DB. Acceptable at MVP volume; if concurrency becomes an issue, we move the whole algorithm into a PL/pgSQL function.

- **[Trade-off] `display_name` defaulting to the generated username means display names look robotic until the user edits them.** Accept this; the UX copy for the (future) profile edit flow handles the nudge.

- **[Risk] The 5-attempt budget is too small for the dev seed (25 000 combos) when concurrency is very high.** In practice concurrent dev signups are a handful; this is a non-issue. → **Mitigation**: the UUID fallback always lands, so correctness is preserved; only the "aesthetic" username suffers.

## Migration Plan

1. Land V3 in a feature branch. Run `./gradlew :backend:ktor:flywayMigrate` against local Postgres. Verify the three new tables exist + reserved seed populated.
2. Audit `dev/scripts/seed-test-user.sh` — fix if it inserts without `invite_code_prefix` (add a small shell-side HMAC derivation or a default `'seed_' || random_4hex'` value). Document the change in `dev/README.md`.
3. Ship the change in a single commit titled something like `feat(auth): signup endpoint + age gate + username generation + V3 schema`.

**Rollback**: the V3 migration is purely additive (new tables + one column write via seeded user backfill, if chosen). Rolling back the code is safe; rolling back the migration means dropping the three tables + undoing any `invite_code_prefix` backfill. Flyway `undo` is not wired (we don't maintain undo migrations); operationally we'd accept the unused tables rather than roll back in staging/prod.

## Open Questions

- **Q1: Fallback on `{adj}_{noun}_{uuid8hex}` collision — accept 503 or retry with `{adj}_{noun}_{uuid12hex}`?** Current plan: 503 once, let the client retry. The second signup attempt re-samples the UUID and statistically lands. But if we want a fully-always-lands flow, we can add the 12-hex fallback. Recommend deferring until we see a real collision in logs.
- **Q2: `display_name` truncation rule when the generated username > 50 chars.** Strategy: take the first 50 chars. Alternative: take `{adj}_{noun}` (always ≤30 chars). Recommend the former so `display_name == username` holds most of the time; truncation only kicks in on the fallback path.
- **Q3: Should the age-gate app-layer guard use the user's declared timezone or server UTC for "today − 18 years"?** Server UTC is simpler and matches the DB CHECK's `CURRENT_DATE`. Declaring a timezone would require a second field. Going with UTC; documenting the "exactly 18 today in WIB" edge case as a known rough edge.
- **Q4: Do we want a canary `GET /api/v1/health/signup-dependencies` probe?** E.g., verify `reserved_usernames` has >1000 rows + word-pair resource loaded. Probably not — add later with observability if needed.
