## Context

The Ktor backend has shipped 12 Flyway migrations and a complete Phase 1 schema for users, posts, blocks, follows, likes, replies, reports, notifications, region polygons, and a Premium FTS index. **Push notification infrastructure has not been built.** The `notifications` table (V10, [`openspec/specs/in-app-notifications/spec.md`](openspec/specs/in-app-notifications/spec.md)) persists in-app notifications but the parallel FCM-push leg ([`docs/02-Product.md:225`](docs/02-Product.md), [`docs/04-Architecture.md:506-558`](docs/04-Architecture.md)) cannot land until the server has device tokens to address.

The canonical contract for the registration handshake lives in three places:

- [`docs/05-Implementation.md:1372-1400`](docs/05-Implementation.md) — schema (verbatim) + endpoint contract + cleanup paths.
- [`docs/04-Architecture.md:515-527`](docs/04-Architecture.md) — registration trigger semantics (token refresh callback, login/logout) + the freshness signal `last_seen_at`.
- [`docs/08-Roadmap-Risk.md:90`](docs/08-Roadmap-Risk.md) — Phase 1 item 18, "FCM token registration endpoint (`POST /api/v1/user/fcm-token`)".

Stakeholders:
- **Mobile clients** (Phase 3) — call `POST /api/v1/user/fcm-token` from the FCM SDK token-refresh callback and on app login. The endpoint contract is the boundary.
- **Phase 2 push-send infrastructure** — reads `user_fcm_tokens` to address devices; deletes rows on `404`/`410` from FCM Admin SDK send.
- **Phase 3.5 cleanup worker** (`/internal/cleanup`) — weekly DELETE of rows with `last_seen_at < NOW() - 30d`.
- **Solo operator** — debugs "why isn't user X getting pushes?" by querying `user_fcm_tokens` directly.

Constraints:
- **JWT-required**: `userId` comes from the authenticated `UserPrincipal` ([`auth-jwt`](openspec/specs/auth-jwt/spec.md) precedent — same pattern as every other `/api/v1/user/*` endpoint).
- **Content-length guard**: per the project critical-invariant in [`CLAUDE.md`](CLAUDE.md), input endpoints must validate request body size. FCM tokens are typically ~163 chars (FCM v1) so a 4 KB body cap is generous.
- **Module boundary**: the User module (per [`docs/04-Architecture.md:129`](docs/04-Architecture.md)) owns FCM token registration. Endpoint code lives in `backend/ktor/.../user/`.
- **No vendor SDK in `:backend:ktor` or `:core:domain`**: this change does NOT import the FCM Admin SDK (the SDK lives in the Phase 2 send-path module, when it ships). Registration is pure JDBC + Ktor.
- **Hash-tag Redis keys**: not applicable here — this change has no Redis dependency.

## Goals / Non-Goals

**Goals:**
- Ship the canonical schema + endpoint verbatim from [`docs/05-Implementation.md:1372-1400`](docs/05-Implementation.md) so Phase 2 push-send work has a stable contract to consume.
- Make the upsert idempotent and race-safe via `INSERT ... ON CONFLICT (user_id, platform, token) DO UPDATE` — refreshing `last_seen_at` is the dominant success path (token-refresh callback fires periodically per [`docs/04-Architecture.md:521`](docs/04-Architecture.md)).
- Lock in the validation error vocabulary as a closed set so the future Phase 2 send-path and Phase 3.5 cleanup worker have a stable shape to reason about.
- Document the deferred-but-coupled contracts (404/410 immediate-delete, weekly stale cleanup) in the spec so future changes know the design intent.

**Non-Goals:**
- FCM send-path implementation (Phase 2 — separate change).
- 404/410 immediate-delete plumbing (Phase 2 — needs the send path).
- `/internal/cleanup` worker for stale tokens (Phase 3.5 — needs `/internal/*` OIDC).
- Mobile-side token-refresh callback wiring (Phase 3 mobile).
- Per-user token cap (open question — see D2).
- A `DELETE /api/v1/user/fcm-token` endpoint for explicit logout cleanup. The token-refresh callback + the future on-send 404/410 path + the future stale-cleanup worker cover the GC matrix; explicit user-triggered delete is not in any roadmap item and would require a body-or-query-param token specifier that opens a tiny info-leak surface (proves a token-existence by 404 vs 204). Deferred unless a product requirement surfaces.

## Decisions

### D1: UNIQUE constraint on `(user_id, platform, token)` rather than `(token)` alone

**Decision**: The UNIQUE constraint covers all three columns: `(user_id, platform, token)`. A single FCM token can appear in multiple rows if it's owned by different `user_id`s (family-shared device, account-switching).

**Rationale**: FCM tokens are device-scoped, not user-scoped. A family-shared tablet where two users both log into the app would produce two rows with the same `token` but different `user_id`s — both should receive pushes addressed to their own account. A `UNIQUE (token)` would force one user to lose push capability when the other registered. The canonical schema in [`docs/05-Implementation.md:1385`](docs/05-Implementation.md) specifies `UNIQUE (user_id, platform, token)` precisely for this reason.

The downside: when the same physical device rotates its token (FCM rotates periodically per [`docs/04-Architecture.md:521`](docs/04-Architecture.md)), the OLD token row stays until the stale-cleanup worker (Phase 3.5) deletes it after 30 days of no `last_seen_at` refresh. During the gap, send-side will get a `404`/`410` on the old token and the Phase 2 immediate-delete path cleans it up. So the row inventory is bounded by `<= one_per_active_token_per_device` and the GC matrix is complete (covered by either the on-send delete or the weekly worker).

**Alternatives considered**:
- `UNIQUE (token)`: rejected per the family-shared-device argument.
- `UNIQUE (user_id, platform)` (force one token per platform per user): rejected because a user with two Android phones (work + personal) would lose one. The natural per-device cardinality lives at the token level.

### D2: No per-user token cap in this change

**Decision**: This change does not enforce a max-N-tokens-per-user limit. Row growth is bounded by the natural device count of the user multiplied by the rotation frequency, with the Phase 3.5 stale-cleanup worker as the GC backstop.

**Rationale**: A typical user has 1–3 devices. Pathological cases (token rotation every minute due to an FCM SDK bug, or an attacker with stolen JWT spamming the endpoint) are bounded by the rate-limit on the JWT-authenticated request surface (D3). The 30-day stale window is long enough to absorb genuine multi-device + rotation scenarios. A cap-N would require careful eviction logic (LRU on `last_seen_at`?) that isn't worth the complexity for a Phase 1 deliverable.

**Alternatives considered**:
- Hard cap N=10 per user with LRU eviction: rejected as premature complexity. Deferred to a future change if Phase 2 push-send observes pathological row counts.
- Soft warn at N=10 (log only): rejected as no-value telemetry without a corresponding response action.

### D3: No new rate-limit category — rely on existing per-user authenticated write rate-limits

**Decision**: This endpoint does NOT introduce a new `tryAcquire` call. It relies on whatever default per-user authenticated-write rate-limit is in place (currently none specific to this surface; the project's Phase 1 rate-limit infrastructure exists but no global per-user write cap has been wired).

**Rationale**: The endpoint is JWT-gated (no unauthenticated abuse surface), idempotent (a malicious flood of `INSERT ... ON CONFLICT DO UPDATE` produces zero net rows after the first), and small in body size (4 KB cap). The cost-of-abuse is bounded — a stolen JWT spamming the endpoint at 10k req/sec wastes JDBC connections but cannot exhaust storage (idempotent upsert) or leak information. If pathological abuse surfaces later, a dedicated rate-limit (e.g., 60 registrations per user per hour) can be wired via the existing `tryAcquire` infrastructure in a follow-up — that's a one-line addition.

The deferred Phase 2 send-side 404/410 path naturally GC's the row inventory; the deferred Phase 3.5 stale-cleanup worker is the second line. So even an attacker who succeeds at row growth has bounded blast radius.

**Alternatives considered**:
- Wire `tryAcquire` with cap=60/hour now: rejected as premature optimization. The Phase 1 like-rate-limit cycle established the pattern; introducing it here without a concrete abuse-pressure observation would just bloat the change.
- Rely on a future global per-user write cap: that cap doesn't exist yet; documenting "we'll wait for it" is a clearer scope statement than "we'll add a half-measure now."

### D4: Validation error vocabulary as a closed set

**Decision**: 4xx responses use a fixed-vocabulary `error` field: `"invalid_platform" | "empty_token" | "app_version_too_long" | "malformed_body"`. Anti-info-leak — no exception messages, no stack traces, no DB error pass-through.

**Rationale**: Mirrors the [`health-check`](openspec/specs/health-check/spec.md) D6 precedent (`"timeout" | "connection_refused" | ..."unknown"`). Closed vocabulary makes the contract testable and gives mobile clients a stable set of error states to localize. Future expansion (e.g., a per-user token cap with `"too_many_tokens"`) is additive without breaking the existing set.

The original exception (if any) MUST be logged at WARN with full context for operator debugging.

**Alternatives considered**:
- Free-form `error` string: rejected (info-leak risk + no localization stability).
- Numeric error codes: rejected (less ergonomic for mobile error rendering; strings are self-describing).

### D5: Endpoint returns `204 No Content`, not `200 OK` with body

**Decision**: Successful registration returns `204 No Content` with no body. The mobile client does not need confirmation of which row was affected (insert vs update) because the freshness signal is server-side state, not client-side state.

**Rationale**: Matches REST conventions for idempotent state-change endpoints. The server-side log line distinguishes insert vs update for ops debugging (proposal § Impact). Returning `200 {created: true|false}` would expose row-existence information that a mobile client cannot meaningfully act on (the FCM SDK doesn't care whether this is the first registration or the 100th refresh — it just calls the endpoint when the SDK fires the callback).

**Alternatives considered**:
- `200 {ok: true}`: rejected as boilerplate.
- `200 {created: true|false}`: rejected per the above — non-actionable client information that leaks server-side row-existence semantics.
- `201 Created` (insert) vs `200 OK` (update): rejected — distinguishing the two requires an extra round-trip for the server to know which path the upsert took, which contradicts the "single atomic upsert" design (D6).

### D6: `INSERT ... ON CONFLICT DO UPDATE` — single atomic upsert, not "SELECT-then-INSERT-or-UPDATE"

**Decision**: The repository uses one Postgres statement: `INSERT INTO user_fcm_tokens (user_id, platform, token, app_version, last_seen_at) VALUES (?, ?, ?, ?, NOW()) ON CONFLICT (user_id, platform, token) DO UPDATE SET last_seen_at = NOW(), app_version = EXCLUDED.app_version`.

**Rationale**: Atomic, race-safe, single round-trip. Two clients racing to register the same `(user_id, platform, token)` triple produce a single row with the latest `last_seen_at` and `app_version` — no `unique_violation` exceptions to catch, no `SELECT FOR UPDATE` advisory-lock plumbing. The `EXCLUDED.app_version` semantics mean the most recent registration wins on `app_version` (clients on different builds racing — last write wins, which matches the freshness intent).

**Alternatives considered**:
- `SELECT 1 ... THEN INSERT ELSE UPDATE`: rejected — race window between SELECT and INSERT/UPDATE produces `unique_violation` under load.
- `INSERT ... ON CONFLICT DO NOTHING` then a follow-up `UPDATE last_seen_at`: rejected — two round trips and the same race surface in reverse.
- `INSERT ... ON CONFLICT DO UPDATE SET last_seen_at = GREATEST(last_seen_at, NOW())`: redundant — `NOW()` is monotonically increasing within a transaction; `GREATEST` would only matter if the column were updated from a stale source, which it never is.

### D7: `app_version` is captured but not validated against a server-side allowlist

**Decision**: `app_version` is stored as opaque `TEXT` (max 64 chars enforced application-side, no DB CHECK). No SemVer parsing, no comparison against `force_update_min_version` Remote Config, no rejection of "weird" values.

**Rationale**: This column exists for ops debugging ("which app version is registering tokens?" — useful for diagnosing rollout issues). It is NOT the canonical version-gate for force-update logic; that lives in the Remote Config `force_update_min_version` flag ([`docs/05-Implementation.md:1412`](docs/05-Implementation.md)) consumed mobile-side. Validating `app_version` here would (a) duplicate that logic and (b) reject legitimate registrations from older clients during a rollout, which is precisely the cohort ops needs to identify.

The 64-char cap is a sanity bound (a SemVer like `1.2.3-beta.1+build.123.abcdef` is ~30 chars; 64 is generous) to prevent DB bloat from a malformed client sending a multi-MB string.

**Alternatives considered**:
- SemVer-validate `app_version`: rejected per the above.
- Make `app_version` non-nullable: rejected — early mobile builds may not have version-injection wired; making it optional matches [`docs/05-Implementation.md:1382`](docs/05-Implementation.md) which declares `app_version TEXT` (nullable by default).

### D8: Migration is V14 (next available number)

**Decision**: The Flyway migration file is `V14__user_fcm_tokens.sql`. Latest in the tree is `V13__premium_search_fts.sql`.

**Rationale**: Sequential V-numbering is the project convention (see [`backend/ktor/src/main/resources/db/migration/`](backend/ktor/src/main/resources/db/migration/)). No conflict possible — the proposal PR opens before any other migration-introducing change, so V14 is reserved for this change. If a parallel change races to V14 between this PR opening and merging, the rebase trivially renames to V15.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Mobile client misconfigures the FCM SDK and floods the endpoint with rapid token-refresh callbacks (e.g., one per minute) | Endpoint is idempotent (D6 — flood produces zero net rows after the first). JDBC connection cost per call is sub-millisecond. If pathological frequency observed in production, wire a `tryAcquire` per-user cap in a follow-up (D3 documented this). |
| Family-shared device produces two rows with the same `token` (different `user_id`s); Phase 2 send-side fans out to both rows | Documented expected behavior (D1). Both family members SHOULD receive the push to the shared device — that's the product intent. Any future de-duplication logic at send-time can group-by `token` if needed. |
| Old tokens accumulate after device rotation (Phase 2 immediate-delete + Phase 3.5 weekly cleanup are deferred) | Acceptable Phase 1 trade-off. Stale rows do not break correctness — they just sit in the table consuming KB-scale storage. The Phase 2 send-path will GC on `404`/`410`; the Phase 3.5 worker is the long-tail backstop. Estimated growth: ~2x the active-device count over a 30-day window in the worst case (every active device rotated once and the old token is still in the table). For the launch-target user base (~1k devices), that's ~2k rows = single-digit MB. Not a Phase 1 concern. |
| `app_version` field receives unvalidated input from mobile, potentially storing junk | 64-char cap prevents bloat (D7). Junk values surface in ops dashboards as "unknown app version" and are filtered out — they don't affect routing or send-correctness. |
| Migration V14 conflicts with a parallel PR if two changes both try to claim V14 | Trivial rebase rename. Not a real risk in this single-developer project where changes ship sequentially. |
| `INSERT ... ON CONFLICT DO UPDATE` atomicity in a transaction context | Postgres guarantees single-statement atomicity; there is no transaction surface — the handler runs the upsert in its own connection autocommit block. No multi-statement coupling to manage. |
| JWT-required gate but the endpoint is `POST` (state-changing): missing CSRF? | Not applicable — REST APIs guarded by Bearer JWT (not cookie-based session) are not subject to CSRF. CSRF applies to admin-panel cookie-session endpoints, which use the `__Host-admin_session` + `X-CSRF-Token` mechanism per [`docs/05-Implementation.md`](docs/05-Implementation.md) admin sessions. The user API surface is pure Bearer JWT. |

## Migration Plan

This is a purely additive change: one new table, one new endpoint, no existing schema or endpoint modified. No data migration required. Rollback is a `git revert` of the squash-merge commit + a Flyway down-migration (manual `DROP TABLE user_fcm_tokens;` since Flyway forward-only is the project convention; in pre-launch this is acceptable).

Deployment sequence:
1. Land the implementation + tests (CI green: ktlint + Detekt + JVM tests).
2. Deploy to staging via the existing `deploy-staging.yml` (Flyway migration runs as part of the `nearyou-migrate-staging` Cloud Run Job pre-deploy).
3. Smoke test:
   - `curl -i -X POST https://api-staging.nearyou.id/api/v1/user/fcm-token -H "Authorization: Bearer <test-jwt>" -H "Content-Type: application/json" -d '{"token":"test-token-123","platform":"android","app_version":"0.1.0-staging"}'` → `204`.
   - Re-issue the same `curl` → still `204`; check `SELECT last_seen_at FROM user_fcm_tokens WHERE token = 'test-token-123'` shows the second timestamp is later than the first.
   - Negative: `curl -X POST ... -d '{"token":"x","platform":"web"}'` → `400 {"error": "invalid_platform"}`.
   - Negative: `curl -X POST ... -d '{"token":"","platform":"android"}'` → `400 {"error": "empty_token"}`.
   - Negative: `curl -X POST ...` (no Authorization header) → `401`.
4. Archive change.

Production deployment is a separate change cycle (no `deploy-prod.yml` exists yet — same posture as `health-check-endpoints`).

## Reconciliation notes

Reconciliation against canonical docs surfaced one minor terminology divergence resolved in this proposal, and zero substantive deviations:

1. **`success status code`**: [`docs/05-Implementation.md:1392-1400`](docs/05-Implementation.md) does not explicitly specify the success status code (the section describes the upsert semantics + `last_seen_at` refresh but stops short of "returns 204"). [`docs/04-Architecture.md:517`](docs/04-Architecture.md) is similarly silent. This proposal canonicalizes `204 No Content` per D5 — a reasonable default for an idempotent state-change endpoint with no body. If the user disagrees, the alternative is `200 OK` with empty body; the spec scenario is one-line easy to flip.

2. **All other claims align verbatim** with canonical docs:
   - Schema: [`docs/05-Implementation.md:1376-1389`](docs/05-Implementation.md) ↔ proposal § What Changes (verbatim copy).
   - Endpoint path + method: [`docs/05-Implementation.md:1394`](docs/05-Implementation.md) `POST /api/v1/user/fcm-token` ↔ proposal.
   - Body shape: [`docs/05-Implementation.md:1394`](docs/05-Implementation.md) `{ token, platform, app_version }` ↔ proposal.
   - Upsert key: [`docs/05-Implementation.md:1394`](docs/05-Implementation.md) `(user_id, platform, token) unique` ↔ proposal D1.
   - Freshness signal: [`docs/05-Implementation.md:1394`](docs/05-Implementation.md) `update last_seen_at = NOW() on every call` ↔ proposal § What Changes + spec scenarios.
   - Cleanup matrix: [`docs/05-Implementation.md:1397-1400`](docs/05-Implementation.md) (404/410 immediate + weekly stale) ↔ proposal § Out of scope (deferred to Phase 2 + Phase 3.5).
   - Module ownership: [`docs/04-Architecture.md:129`](docs/04-Architecture.md) "User module owns FCM token registration" ↔ proposal § Impact.
   - Phase 1 placement: [`docs/08-Roadmap-Risk.md:90`](docs/08-Roadmap-Risk.md) Phase 1 item 18 ↔ proposal § Why.

## Open Questions

1. **Success status code**: confirm `204 No Content` per D5, or override to `200 OK`. Default proceeding with `204` (REST idiomatic for idempotent state-change without response body); if reviewer prefers `200`, one-line spec scenario flip.
2. **Per-user token cap**: deferred per D2. If Phase 2 push-send observes pathological row counts, file a follow-up `fcm-token-per-user-cap` change.
