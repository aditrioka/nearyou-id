## Why

Auth-foundation landed sign-in for **existing** users only. `POST /api/v1/auth/signin` returns `404 user_not_found` for unknown provider subjects because the canonical `users` INSERT needs `date_of_birth` (NOT NULL + 18+ CHECK), `username` (NOT NULL UNIQUE), and `invite_code_prefix` (NOT NULL UNIQUE) — each bound to a deferred feature. Until a signup path exists, the only way to get a user row is the dev seed script, which blocks every downstream feature that assumes "a user can actually join." This change closes that loop end-to-end at the backend layer, so mobile, post creation, chat, referrals, and anything else can assume a populated `users` row is reachable via a supported API call.

The three feature pieces this pulls together — age gate (18+ DB-backed, rejected-identifier blocklist against DOB-shopping), username auto-generation (atomic adj × noun × modifier with reserved-word guard), and invite-code prefix derivation — are all Phase 1 items 2, 3, and the username half of 21 in `docs/08-Roadmap-Risk.md`. They are mutually dependent at the signup transaction (every NOT NULL column has to land in a single INSERT), so splitting them produces stubs that leak across change boundaries. Landing them together delivers one usable endpoint and one coherent V3 migration.

## What Changes

### Backend — new endpoint
- `POST /api/v1/auth/signup`: body `{ provider: "google"|"apple", id_token, date_of_birth (ISO-8601 date), device_fingerprint_hash? }`. On success returns `{ access_token, refresh_token, expires_in: 900 }` identical to sign-in.
- Request flow (all inside one DB transaction):
  1. Verify `id_token` via the existing `ProviderIdTokenVerifier` (reused from auth-foundation).
  2. Hash provider sub (reusing the same `google_id_hash` / `apple_id_hash` algorithm from sign-in). **Pre-check** `rejected_identifiers` on `(identifier_hash, identifier_type)` — if present, return 403 `user_blocked` with the same generic body used for under-18 (privacy note in docs/05-Implementation.md § Rejected Identifiers) so the caller cannot distinguish "new under-18 rejection" from "previously-rejected identifier." No row created, no DOB read past parsing.
  3. Validate `date_of_birth` at app layer: reject if `date_of_birth > today - 18y`. On under-18: INSERT into `rejected_identifiers` with `reason = 'age_under_18'`, `identifier_type = 'google'|'apple'`, then return 403 `user_blocked` (same privacy-preserving body).
  4. If the provider sub already exists in `users`, return 409 `user_exists` (caller should switch to `/signin`).
  5. Atomically generate username via the canonical algorithm (see docs/05-Implementation.md § Username Generation): 5-attempt loop across `{adj}_{noun}` → `{adj}_{noun}_{modifier}` → `{adj}_{noun}_{random_5_digit}`, each candidate checked against `reserved_usernames` + `username_history WHERE released_at > NOW()`; on all-collisions fall back to `{adj}_{noun}_{uuid8hex}`.
  6. INSERT into `users` with generated `username`, `date_of_birth`, provider hash, `display_name` defaulted to the generated username, and `invite_code_prefix` derived from `base32(HMAC-SHA256(invite-code-secret, user_id.bytes))[0..8]`. On the astronomical UNIQUE collision on `invite_code_prefix`, retry with the 10-character prefix fallback documented in the Users Schema notes.
  7. Issue access + refresh tokens via the existing JWT issuer + refresh repository.

Note: the `invite_code` request param is **not** accepted in this change. The invitee → inviter linkage lives in `referral_tickets`, which this change does not create. The referral-system change will land both the `referral_tickets` schema and the `invite_code?` signup param together — a cleaner cut than stashing a linkage column that has no owner here.

### Backend — behavioral note on sign-in
- `/signin` keeps returning `404 user_not_found` for unknown users. No behavior change. The proposal just formalizes that signup is the distinct path.

### Database — Flyway V3
- `V3__signup_flow.sql` creates three tables verbatim from docs/05-Implementation.md (no deviations):
  - `reserved_usernames` — including the `source` column CHECK, the `reserved_usernames_set_updated_at` function + `reserved_usernames_updated_at_trigger`, and the `reserved_usernames_protect_seed` function + trigger. Seed insert (all `source = 'seed_system'`): `admin`, `support`, `moderator`, `system`, `nearyou`, `staff`, `official`, `akun_dihapus`, `deleted_user`, plus every 1- and 2-character lowercase string (letters a–z and digits 0–9).
  - `rejected_identifiers` — `identifier_hash TEXT`, `identifier_type VARCHAR(8) CHECK ('google','apple')`, `reason VARCHAR(32) CHECK ('age_under_18','attestation_persistent_fail')`, UNIQUE `(identifier_hash, identifier_type)`, plus the `rejected_identifiers_hash_idx` index. The `attestation_persistent_fail` reason code is declared in the CHECK but not written by this change (reserved for the later attestation-integration change).
  - `username_history` — `user_id UUID REFERENCES users(id) ON DELETE CASCADE`, `old_username VARCHAR(60)`, `new_username VARCHAR(60)`, `changed_at`, `released_at`, plus the three canonical indexes (`LOWER(old_username)`, `released_at`, `(user_id, changed_at DESC)`). **Schema only** — no writes yet (Premium customization deferred).
- `users.invite_code_prefix` is already NOT NULL UNIQUE in V2 from auth-foundation; this change populates it for the first time (existing seeded users inserted before V3 will need a backfill step or seed script update — see design.md).

### Word-pair dataset
- Committed at `backend/ktor/src/main/resources/username/wordpairs.json` as three JSON arrays: `adjectives[]`, `nouns[]`, `modifiers[]`. Dev-scale seed (50 × 50 × 10 = 25 000 combos) — enough to exercise collision paths. A short README next to it marks the file as "seed — replace with the full 600 × 600 × 100 dataset at Pre-Phase 1 close."

### Tests
- `AgeGateTest`: under-18 → 403 `user_blocked` + row in `rejected_identifiers` with `reason = 'age_under_18'`; exactly-18-today → accepted; over-18 → accepted.
- `RejectedIdentifierPrecheckTest`: identifier already in table → 403 `user_blocked` BEFORE any DOB parse or user INSERT attempt; response body identical to the under-18 rejection body (privacy-preserving, no leak).
- `UsernameGenerationTest`: deterministic candidate seed produces expected handle; candidate hitting reserved word triggers next attempt; candidate hitting an unreleased `username_history` row triggers next attempt; all five template attempts exhausted → `{adj}_{noun}_{uuid8hex}` fallback used; generated username always ≤ 60 chars.
- `InviteCodePrefixTest`: HMAC determinism — same `user_id` always yields the same 8-char prefix; simulated UNIQUE collision triggers the documented 10-char prefix fallback.
- `SignupFlowTest` (integration, plain Postgres via dev compose): Google path + Apple path; resulting `users` row has every NOT NULL column populated (`username`, `display_name`, `date_of_birth`, `invite_code_prefix`, plus V2 defaults); access + refresh pair returned and the refresh token round-trips through `/refresh`.

## Capabilities

### New Capabilities
- `auth-signup`: HTTP contract for `POST /api/v1/auth/signup` — request/response shape, error taxonomy (`user_blocked` / `under_age` / `user_exists` / `invalid_id_token` / `invite_code_invalid` / `username_generation_failed`), transactional guarantees (all-or-nothing user creation), token-pair issuance on success.
- `age-gate`: DOB validation rule (strict 18+ at app layer, DB CHECK as backstop), rejected-identifier blocklist schema + pre-check + insert-on-reject semantics, anti-DOB-shopping guarantee.
- `username-generation`: Atomic server-side generation algorithm (adj × noun × modifier), reserved-word enforcement, UNIQUE-collision retry budget, `user_<8hex>` fallback, deterministic test seed hook.

### Modified Capabilities
- `users-schema`: V3 migration adds `reserved_usernames`, `rejected_identifiers`, and `username_history` tables (with their indexes, triggers, and the reserved-username seed insert). Requirements grow: the canonical table set now includes these three, and the reserved-username seed is load-bearing (signup correctness depends on it).
- `auth-signin`: Requirements clarify that `/signin` is the existing-user path and `/signup` is the distinct creation path; `/signin` still returns `404 user_not_found` for unknown users (no reshape).

## Impact

- **Code paths**: new package `backend/ktor/src/main/kotlin/id/nearyou/app/auth/signup/` (route handler, age-gate service, username generator, invite-code derivation service); extends `:infra:supabase` with `RejectedIdentifierRepository` and `ReservedUsernameRepository` interfaces + JDBC impls, and adds a `create(userRow)` method to `UserRepository`; new resource `backend/ktor/src/main/resources/username/wordpairs.json` + README; new Flyway file `V3__signup_flow.sql`; updated dev seed script to populate `invite_code_prefix` for existing seeded users (or a tiny backfill fragment at the top of V3).
- **Secrets**: requires `invite-code-secret` in the resolver chain. Dev adds it to `dev/.env.example` and the `generate-rsa-keypair.sh` script gains a sibling line emitting a 32-byte base64 random value. No staging/prod deploy change (staging-bootstrap change handles Secret Manager plumbing later).
- **External dependencies**: none new. Google/Apple JWKS already wired.
- **Onward dependencies unblocked**: any feature that needs a real `users` row (post creation, mobile sign-in, block list, FCM token registration, referral reward issuance) can now assume signup works. The `username_history` schema lets the Premium-username change land without another migration; the `rejected_identifiers` schema lets the attestation-integration change add `attestation_persistent_fail` writes without another migration.
- **Out of scope (flags, carried or introduced)**:
  - Attestation at signup (Play Integrity / App Attest) — deferred to the attestation-integration change. Signup is unattested in dev, which matches the `attestation_mode=off` staging posture already documented.
  - `invite_code?` intake + inviter linkage — deferred to the referral-system change (which also owns `referral_tickets` creation).
  - Premium username customization `PATCH /api/v1/user/username` — deferred. `username_history` schema lands now, endpoint later.
  - Full 600 × 600 × 100 word-pair dataset — Pre-Phase 1 asset work. The dev seed (50 × 50 × 10) is enough for collision tests.
  - `/internal/cleanup` refresh-token sweeper — still deferred (carried forward from auth-foundation).
  - Suspension unban worker — untouched; its dependency is a populated `users` table, which lands here but the worker itself is a later Phase 1 item.
