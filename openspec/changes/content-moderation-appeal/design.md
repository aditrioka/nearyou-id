## Context

Moderation actions are issued and audited, but the actioned party has no recourse. The data model ([docs/02-Product.md:79-85](../../../docs/02-Product.md)): a **7-day suspension** sets `users.is_banned = TRUE` + `users.suspended_until = NOW() + 7d` and increments `token_version`; a **permanent ban** sets `is_banned = TRUE`, `suspended_until = NULL`; a **shadow ban** sets `is_shadow_banned = TRUE` (the user is *not* `is_banned` and retains full, invisible-to-them access). The `auth-jwt` validate block ([openspec/specs/auth-jwt/spec.md:49-59](../../../openspec/specs/auth-jwt/spec.md)) short-circuits **every** authenticated request from an `is_banned` or actively-suspended user with HTTP 403 (`account_banned` / `account_suspended`). The unban path already exists (`admin-user-moderation` + the daily `suspension-unban-worker`), as does immutable audit (`admin_actions_log`), the admin panel substrate (Pebble + HTMX, intentionally unstyled), and Redis rate-limit infra.

This change adds the appeal loop. The central tension: the people who need to appeal are exactly the people the auth boundary 403s on every route.

## Goals / Non-Goals

**Goals:**
- A banned/suspended user can submit one pending appeal and read its outcome on the appeal screen (reached off the banned sign-in surface, not Settings — suspension is session-terminating).
- An admin can review the pending-appeal queue and approve (→ unban) or reject, with immutable audit.
- Preserve shadow-ban invisibility absolutely: a shadow-banned-only user must never see or be able to submit an appeal.
- Reuse the shipped unban path, audit log, rate-limit infra, and auth validate block — no parallel patterns.

**Non-Goals:**
- Proactive push/in-app notification of the decision (the own-status read is the MVP outcome surface; deferred as an explicit spec requirement).
- Appeals against individual post auto-hide / report outcomes (account-level actions only).
- Appeals against shadow-ban (never surfaced, by design).
- Admin WebAuthn / second-admin concerns (out of scope; TOTP-gated admin stands).

## Decisions

### D1 — Ban-exempt authenticated realm (the core decision)
A **dedicated named Ktor auth provider** (`authenticate("appeal") { … }`) validates the JWT signature + `token_version` (instant-revocation check preserved) and populates `UserPrincipal`, but does **NOT** apply the `is_banned` / `suspended_until` 403 short-circuit. Only `POST /api/v1/appeals` and the own-status GET mount under it. Every other authenticated route keeps the standard realm unchanged. *Alternatives rejected:* (a) a route-attribute flag toggling the short-circuit inside one realm — implicit, easy to misapply; (b) raw JWT verification inside the appeal handler — duplicates auth logic, violates the anti-patchwork rule. The shared SELECT + `token_version` check is factored so both realms reuse it and differ only on the ban short-circuit. This is why `auth-jwt` is a **MODIFIED** capability: the "the same middleware SHALL also reject … is_banned" requirement gains an explicit, single carve-out.

### D2 — The appeal credential: a limited-scope appeal token (load-bearing; resolves the auth-signin contradiction)
Suspension bumps `token_version`, revoking the appellant's existing JWTs (next request → 401 `token_revoked`). The appellant must re-authenticate — but the canonical `auth-signin` spec ("Banned user blocked at sign-in") returns **403 `account_banned` with NO tokens** for any `is_banned = TRUE` subject (a suspended user included). So sign-in alone leaves the appellant with no credential the appeal realm would accept. (An earlier draft of this design wrongly assumed sign-in issues a normal token to banned users — the review caught this; docs/03:268 "login succeeds" is UX prose, not the canonical spec.)

Resolution (the **MODIFIED `auth-signin`** delta): a banned/suspended sign-in additionally returns a **limited-scope appeal token** in the 403 body — an RS256 JWT with the subject's `sub`, the *current* `token_version`, a `scope = "appeal"` claim, and a ≤1h TTL. No normal access/refresh token is issued. The ban-exempt appeal realm (D1) accepts this token (it keeps the `token_version` + soft-delete + signature checks, so a logged-out / stale-token / deleted-account actor still 401s — only the ban gate is relaxed). Confinement runs the other way: every **standard** realm MUST reject a `scope = "appeal"` token with 401, so the limited token can never reach a normal route. This is the established auth-rejection-response pattern (the 403 carries the recourse credential), extended with a narrow scope claim — not a parallel auth path.

### D3 — Eligibility & shadow-ban exclusion (`is_banned` is the gate)
Eligibility = `is_banned = TRUE` (covers suspension and permanent ban). Because a shadow-banned-only user is `is_banned = FALSE`, the same predicate **naturally excludes** them — there is no separate "hide from shadow-banned" branch that could leak the state. The submission endpoint returns the same `409 no_actionable_moderation` envelope to any `is_banned = FALSE` caller (shadow-banned or perfectly normal), so the response never distinguishes shadow-ban from no-ban. Explicit negative-guard scenario in the spec.

### D4 — `appeals` data model (V34)
```
appeals(
  id              UUID PK default gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  action_type     TEXT NOT NULL CHECK (action_type IN ('suspension','permanent_ban')),
  appeal_text     TEXT NOT NULL CHECK (char_length(appeal_text) BETWEEN 1 AND 1000),
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','approved','rejected')),
  decision_reason TEXT CHECK (decision_reason IS NULL OR char_length(decision_reason) <= 1000),
  reviewed_by     UUID REFERENCES admin_users(id) ON DELETE SET NULL,
  reviewed_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
```
- **One pending appeal per user** via a partial-unique index: `CREATE UNIQUE INDEX appeals_one_pending_per_user ON appeals(user_id) WHERE status = 'pending'` — no `NOW()` in the predicate (invariant). A second submission while one is pending → `409 appeal_already_pending`.
- **Pending queue index** for the admin list: `CREATE INDEX appeals_pending_created_idx ON appeals(created_at) WHERE status = 'pending'`.
- `reviewed_by` is `ON DELETE SET NULL` (admin-session FK invariant — admin deletion must not cascade-destroy appeal history). `appeal_text` length CHECK is the schema backstop to the content-length-guard invariant (route-level guard is primary).
- `V34` is disjoint from in-flight V29×2 (referral, csam) + V30 (data-export); **`tasks.md` re-verifies the next-free version at pre-merge** per the known parallel-Flyway-collision risk.

### D5 — Submission guards & rate-limit
`POST /api/v1/appeals` (body: `appeal_text`): (1) eligibility (`is_banned = TRUE`, else 409 per D3); (2) one-pending guard (409 `appeal_already_pending`); (3) length guard (≤1000, route-level, before DB); (4) per-user submission rate-limit via the canonical Redis hash-tag key shape `{scope:rate_appeal_day}:{user:<user_id>}` (matching the shipped `{scope:rate_post_day}:{user:…}` / `{scope:rate_chat_send_day}:{user:…}` family — `_day` is the fixed-window marker; the `{scope:…}:{user:…}` two-segment shape is required by `RedisHashTagRule`) with `computeTTLToNextReset` (a low daily cap, e.g. 3/day, throttles abuse without locking out a legitimate appellant). `action_type` is **derived server-side** from the user's row (`suspended_until IS NULL → permanent_ban`, else `suspension`) — never client-supplied.

### D6 — Admin review surface (unstyled; reuses unban path + audit)
`GET /admin/appeals` paginated pending queue (the full `appeal_text` renders inline — bounded by the 1000-char V34 `CHECK`, so no separate per-appeal detail view is needed); **approve** and **reject** are HTMX form-POSTs under the existing owner/admin gate + CSRF. They are **NOT** counted by `admin-destructive-action-rate-limit` — that limiter gates *destructive* throughput from a fixed action-type set, but approve is restorative (it lifts a ban) and reject changes no user state; the gate + CSRF + immutable audit are the controls (an earlier draft wrongly claimed the destructive limiter applied — the review caught that the limiter's fixed set would never count these). **Approve** reuses the unban transaction (`UPDATE users SET is_banned = FALSE, suspended_until = NULL` — the same statement shape as `suspension-unban-worker`/admin unban) AND flips the appeal to `approved`, in one transaction, then writes one `admin_actions_log` row with new action type `appeal_approved`. **Reject** flips to `rejected` (+ optional `decision_reason`) and writes `appeal_rejected`. Mirrors `admin-report-queue` idioms (Pebble + HTMX fragment swap + no-JS fallback). **Styling: shipped unstyled** — the admin board has no appeal frame yet (it is the documented "sole known gap", [docs/11:149](../../../docs/11-Engineering-Standards.md)); per §3.6 this is an "Usulan" surface whose behavior ships now and whose styled frame lands with a later admin design-foundation pass. The change consults §3.6 and adopts the panel's existing column/action/CSRF idioms (content contract), deferring only look-and-feel.

### D7 — Permanent-ban reachability (resolves the docs/08-vs-docs/03 tension)
[docs/08:512](../../../docs/08-Roadmap-Risk.md) says "banned or suspended can submit"; [docs/03:270](../../../docs/03-UX-Design.md) routes permanent ban to "Hubungi support" at the login screen (no in-app session). Resolution: the **backend accepts both** `action_type`s (eligibility = `is_banned`, complete + future-proof for a support portal). The **mobile MVP surfaces the in-app form to suspended users only** (who obtain the limited appeal token by re-authenticating at sign-in — suspension revokes their normal tokens); a permanently-banned user sees the existing "deactivated — contact support" copy. Permanent-ban appeals via support email are the documented path; an in-app permanent-ban entry is captured as an explicit deferred requirement, not silently dropped.

### D8 — Mobile surface
A `screens/appeal` feature (`AppealScreen` + `AppealViewModel` + `AppealUiState`, androidx `ViewModel` in commonMain via `koinViewModel()`, one `StateFlow<AppealUiState>`), reached from a new **"Ajukan banding"** entry on the banned sign-in screen, shown when sign-in returns the banned/suspended state (`SignInOutcome.Banned`). The entry lives at **sign-in, not Settings**: suspension is session-terminating (it bumps `token_version`, revoking the user's normal tokens), so there is no in-app Settings session to host the entry; the limited appeal token is captured from the sign-in 403 body into the in-memory appeal-session holder. Data layer: `AppealApiClient` (DTOs colocated) + `AppealRepository` exposing a sealed `AppealOutcome`. A new `AppealRoute` NavKey (`@Serializable`, registered in the polymorphic `SerializersModule`). Strings via `:shared:resources` CMP Resources only. The screen shows: form (multiline `appeal_text`, 1000-char counter) → submit → status display (pending / approved / rejected + reason), driven by the own-status GET.

### Standards conformance (docs/11 Pattern Registry)
- **Backend layering §3.1:** `AppealRoutes` (thin) → `AppealService` (tx boundary, eligibility/guards) → `JdbcAppealRepository` (SQL). Admin surface mirrors `admin-report-queue`.
- **Auth §3.x / auth-jwt + auth-signin:** the ban-exempt realm extends the existing validate block (factored shared SELECT + `token_version`), and the appeal token reuses the existing RS256 issuance + the auth-rejection-response pattern with one narrow `scope` claim — not a parallel auth path. Declared as the MODIFIED `auth-jwt` + `auth-signin` capabilities (no silent second pattern).
- **JDBC §3.2:** bounded dispatcher, one tx per service op, test pools `autoClose(hikari())` + size 2.
- **Rate-limit §3.3:** Redis-backed `computeTTLToNextReset` + hash-tag key shape; no Ktor in-memory plugin.
- **Mobile state §2.2 / nav §2.3 / data §2.6:** ViewModel + single `StateFlow` + `collectAsStateWithLifecycle`; NavKey registered in the polymorphic module; `ApiClient` + `Repository` + sealed `Outcome` on the shared `HttpClient`.
- **Admin UI §3.6:** Pebble + HTMX + vendored CSS; consult the board (no appeal frame yet → unstyled, styled frame deferred — the documented gap).
- No deviation from any listed pattern → no docs/11 amendment required.

## Risks / Trade-offs

- **Appeal credential must reach the banned user without granting normal access (D2)** → *Mitigation:* the MODIFIED `auth-signin` issues a `scope = "appeal"`, ≤1h, current-`token_version` token in the 403 body (no normal/refresh token); every standard realm rejects `scope = "appeal"` (401). Tests assert: a banned subject obtains an appeal token and reaches the appeal realm; that token is rejected on a normal route.
- **Ban-exempt realm is a new attack surface** (a banned user CAN hit one authenticated endpoint) → *Mitigation:* the realm is scoped to exactly two read/write appeal routes; `token_version` + soft-delete + signature checks retained (only the ban gate relaxed); no-token/invalid-sig → 401, no principal; per-user rate-limit + one-pending guard cap abuse; `appeal_text` length-bounded; no other behavior reachable.
- **Approve double-unban race** (admin approves while the daily unban worker also unbans an elapsed suspension) → *Mitigation:* approve is idempotent (`UPDATE … SET is_banned = FALSE` is a no-op if already false; appeal status transition guarded `WHERE status = 'pending'`), so concurrent unban is harmless.
- **Shadow-ban leak via timing/response shape** → *Mitigation:* identical `409 no_actionable_moderation` envelope for every `is_banned = FALSE` caller (D3); no branch that a shadow-banned user could distinguish.
- **Unstyled admin surface** ships ahead of its mockup frame → accepted per §3.6 (behavior now, styling with the admin design-foundation pass); content contract follows shipped admin templates.

## Migration Plan

- Forward: `V34__appeals.sql` (additive — new table + two partial indexes; re-verify the next-free version pre-merge). Backend + admin + mobile ship in one PR. The ban-exempt realm is additive (new named provider; existing realm gains only the `scope = "appeal"` reject). The `auth-signin` change is additive for non-banned users (their 200 response is unchanged); a banned user's existing 403 simply gains an `appeal_token` body field — no client breakage.
- Rollback: drop the `appeals` table + remove the realm/routes; no data migration on existing tables, so rollback is clean. The admin action types `appeal_approved` / `appeal_rejected` are additive enum-of-strings in `admin_actions_log` (no schema enum to revert).

## Open Questions

- Daily submission cap value (D5) — proposed 3/day; confirm against abuse posture at review.
- Whether the approve action should also clear `is_shadow_banned` — **No** (shadow-ban is a separate, deliberately-invisible control; an appeal is against a *visible* action). Documented here to forestall the question.
- Permanent-ban in-app entry (D7) — deferred to support-email path for MVP; revisit if support-ticket volume warrants an in-app form (mirrors Open Decision #15's "minimal path" framing).
