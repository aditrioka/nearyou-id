## Context

[`docs/05-Implementation.md`](../../../docs/05-Implementation.md) § Anti-Spam defines Layer 4 "Per Area (anti local spam)": *"Max 50 new posts in a 1km radius / 1h (via `display_location` spatial query). Threshold hit → manual review. Redis INCR + EXPIRE counter."* Layers 1–3 ship; this is the last layer. The per-user daily cap (Layer 2) already lives in `CreatePostService.create()` as a `PostRateLimiter` gate (step 2.5), and the text moderator's `Verdict.Flag` branch already does the exact "INSERT post + `moderation_queue` row in the same transaction, `ON CONFLICT DO NOTHING`" routing we need. So the area gate is **a new trigger condition feeding an existing routing pattern**, not a new subsystem.

Current relevant constraints:
- **Spatial-fuzzing invariant**: non-admin paths read `display_location` (HMAC-fuzzed), never `actual_location`. `CreatePostService` is non-admin. Jitter distance is deterministically `[50, 500]` m (`JitterEngine`, docs/05 § Coordinate Fuzzing).
- **`moderation_queue.trigger`** is an inline column CHECK (`trigger VARCHAR(32) NOT NULL CHECK (trigger IN (… 7 values …))`, V9) — auto-named `moderation_queue_trigger_check`. None of the 7 reserved values denotes per-area spam.
- **Rate-limit Pattern Registry** (docs/11 §3.3): rate limiting stays Redis-backed custom; the per-user-daily stagger (`computeTTLToNextReset`) applies to *daily* keys only — *hourly limits skip the stagger* (docs/05 § Layer 2 timezone-stagger note).
- **Redis key shape** (`RedisHashTagRule`): two-segment `{scope:<value>}:{axis:<value>}` hash-tag form.

## Goals / Non-Goals

**Goals:**
- Ship Layer 4: flag the next post in an over-dense ~1 km area within a 1 h window to `moderation_queue` for human review, without rejecting it.
- Apply to all tiers (area-keyed, not user-keyed).
- Reuse the existing limiter abstraction + the existing same-transaction queue-write path — no parallel Redis-counter mechanism, no new routing pattern.
- Keep the `POST /api/v1/posts` request/`201` response wire shape byte-identical (no mobile change).
- Keep the area gate off the hot path's DB cost (no per-create PostGIS query).

**Non-Goals:**
- The Phase 4 #17 per-user 30-day-baseline `anomaly_detection` capability — a *distinct* mechanism with its own reserved enum value. Explicitly NOT conflated here.
- Any new admin UI beyond rendering/resolving the existing `moderation_queue` row shape with the new `area_spam` trigger value.
- Notifying the poster (silent routing — anti-probing; matches the `Verdict.Flag` path).
- Remote-Config runtime tuning of the threshold/window (constants for MVP; seam noted in Decision 5).

## Decisions

### Decision 1 — Cell derivation: degree-grid rounding of `display_location` (~1.1 km), not geohash, not PostGIS

The cell key is `display_location` rounded to a **0.01° lat/lng grid**. All of Indonesia sits within ~6°S–6°N, where 0.01° ≈ **1.10–1.11 km** in *both* lat and lng (`cos(6°) = 0.9945`), so a square ~1.1 km cell is an effectively-uniform proxy for the docs/05 "1 km radius" without trigonometry per call. Cell id = `"<round(lat,2)>_<round(lng,2)>"`.

- **Why over geohash**: geohash adds a dependency and yields *rectangular* cells (~1.22 km × 0.61 km at precision 6) that distort off-equator; degree-rounding is dependency-free and near-square at Indonesia's latitudes.
- **Why over PostGIS `ST_DWithin` COUNT**: an exact 1 km-radius count is a spatial aggregate on the **hot write path** (every post create) — the audit (02-H1/H2) is already fighting JDBC on this path. A Redis counter is O(1) and matches docs/05's "Redis INCR + EXPIRE counter" intent.
- **Fuzz tolerance**: because jitter ∈ [50, 500] m ≪ 1.1 km, posts from one actual spot land within ~500 m of their true point in `display` space — almost always the same cell, occasionally an adjacent one at a boundary. The per-cell count stays a faithful density proxy; boundary diffusion only *under*-counts slightly (conservative — fewer false flags). Keying on `display_location` (not `actual_location`) is *also* what the spatial-fuzzing invariant requires and what docs/05 § Layer 4 literally says.

**Reconciliation (B.3):** docs/05:1330 says "via `display_location` spatial query" — we implement a `display_location`-derived **grid-cell counter**, not a literal spatial query. A tasks.md item amends the docs/05 § Layer 4 sentence to describe the shipped geocell-counter mechanism (it is *the same doc's* own "Redis INCR + EXPIRE counter" clause made precise; canonical-doc-is-source so this is an (a) in-PR doc fix, not a follow-up).

### Decision 2 — Counter via the shared `RateLimiter` abstraction (hourly, sliding window), reaction = flag not reject

A new `AreaPostDensityLimiter` (under `id.nearyou.app.post`) **delegates to the shared `RateLimiter`** — same Redis-backed-custom pattern, **no second counter mechanism** (Pattern Registry §3.3 conformant). It calls the limiter's **axis-agnostic `tryAcquireByKey(key, capacity, ttl)` entry point** (the documented geocell/IP/fingerprint call site — `RateLimiter.kt` KDoc literally names "geocell"; precedent `ReferralTicketRateLimiter`) — **NOT** `PostRateLimiter`'s user-axis `tryAcquire(userId, …)`, which attaches per-user telemetry + WIB-stagger inference inappropriate for an area bucket. The key is the **geocell**, capacity **50**, window **1 h**. The limiter's `RateLimited` outcome is mapped to an `OverThreshold` result, which the service turns into a **`moderation_queue` write — never a 429**. Posts 1–50 in the cell/window are clean; the 51st onward (`count > 50`) are flagged.

- **No per-user stagger**: this is an *hourly*, *geocell-keyed* limit — `computeTTLToNextReset` (the per-user WIB-midnight daily stagger) deliberately does NOT apply, per docs/05's own "hourly limits skip the stagger". `RateLimitTtlRule` keys off the `_day}` marker (`^\{scope:rate_…_day\}`) — the `{scope:area_post}` key carries no `_day}`, so the rule does not fire and no allowlist annotation is needed (verified against the rule regex). Existing hourly limiters (`SearchRateLimiter` 60/h, `ReportRateLimiter`) set the precedent.
- **Sliding vs fixed window**: the shared limiter's non-`_day}` path is a sliding-window (ZADD) counter — strictly *better* than docs/05's literal "INCR + EXPIRE" fixed window (no boundary-reset gaming). We adopt the shipped sliding-window hourly semantics; Decision 1's docs/05 amendment covers the wording.
- **Key shape**: `{scope:area_post}:{cell:<lat>_<lng>}` — two-segment `{scope:}:{axis:}` form for `RedisHashTagRule`. Single-key INCR (no cross-slot multi-key op). The exact literal is verified against the lint rule at apply time.
- **Alternative rejected**: a bespoke `INCR`+`EXPIRE` helper matching docs/05 verbatim — rejected because it forks a second Redis-counter pattern the registry exists to prevent; the shared limiter already provides equivalent (better) semantics.

### Decision 3 — Flagged post stays VISIBLE; only a `moderation_queue` row is written

docs/05 says "Threshold hit → **manual review**" — not auto-hide. The flagged post is created with `is_auto_hidden = FALSE` and a `moderation_queue` row (`trigger='area_spam'`), exactly mirroring the UU-ITE `Verdict.Flag` precedent (soft-flag, reversible by an admin's `hide`/`delete` resolution). This is least-surprising (a genuine local poster in a busy area isn't silently shadow-hidden) and reversible.

- **Trade-off**: spam stays visible until an admin acts. Mitigations: (a) the per-user daily cap already bounds any single account; (b) the queue row is `priority = 5` and surfaces in the existing triage queue; (c) an admin `hide`/`shadow_ban_author` resolution is one click. Auto-hide-on-threshold is rejected as too blunt for an area signal (false-positives in legitimately busy areas — markets, events).

### Decision 4 — New trigger value `area_spam` (not reuse `anomaly_detection`)

Add `area_spam` to the `moderation_queue.trigger` CHECK enum (8 values total) via **Flyway V36** — `ALTER TABLE moderation_queue DROP CONSTRAINT moderation_queue_trigger_check, ADD CONSTRAINT moderation_queue_trigger_check CHECK (trigger IN ( … 7 existing …, 'area_spam'))`.

- **Why not reuse `anomaly_detection`**: that reserved value is earmarked for the Phase 4 #17 per-user 30-day-baseline mechanism — a different signal. Conflating them would make admin triage unable to distinguish "this *area* is flooding" from "this *user* deviates from their baseline". A dedicated value keeps the queue filterable and the two future mechanisms cleanly separable.
- **V36** is the next free version (V35 is current max; the V29 gap is behind the already-applied staging/prod frontier and must NOT be reused). Parallel-session collision watch: if a sibling change grabs V36 first, `git mv` to V37 pre-merge (project memory: Flyway version collision).

### Decision 5 — Threshold/window/cell as named constants (RC seam noted, not built)

`AREA_POST_THRESHOLD = 50`, `AREA_POST_WINDOW = 1h`, `AREA_CELL_PRECISION = 0.01°` ship as named constants, mirroring how the daily-cap default is a constant. Remote-Config tunability (a `area_post_threshold` flag through the existing `remote_config:{flag:*}` Redis cache) is a deliberate later layer, not MVP — added only if operations needs to retune without a deploy. Noted so a reviewer doesn't read the constant as an oversight.

### Decision 6 — Idempotent single queue row per post

The `moderation_queue` UNIQUE `(target_type, target_id, trigger)` + `ON CONFLICT … DO NOTHING` makes the `area_spam` insert idempotent (one row per `(post, area_spam)`), identical to the Flag path. A post that hits *both* the area threshold AND a UU-ITE flag produces two rows — one per trigger — which the existing `(target_type, target_id)` grouping surfaces together. No new idempotency machinery.

### Decision 7 — Call order: after Reject short-circuit, INCR only would-be-created posts

Within `create()`: length → envelope → daily cap (Free only) → `moderate()` (`Verdict.Reject` short-circuits to 400, no INSERT, no INCR) → **area-density INCR + check** → INSERT (+ `moderation_queue` rows for Flag and/or area_spam in the same tx). Running the area INCR *after* the Reject short-circuit means rejected (never-created) posts don't count toward "new posts" density (the accurate reading of docs/05's "50 new posts") and burn no counter slot. A static-analysis call-order test pins this, extending the existing "Moderator runs AFTER length guard and BEFORE INSERT" test.

## Standards conformance (docs/11 — MUST)

- **§3.1 layering**: `CreatePostService` (business rule + tx boundary) gains the gate; `AreaPostDensityLimiter` is a service-layer collaborator delegating to the shared `RateLimiter` via `tryAcquireByKey` (key-axis precedent: `ReferralTicketRateLimiter`). Routes/DTOs unchanged; no SQL in the route.
- **§3.2 JDBC**: the same-tx `moderation_queue` INSERT runs inside the existing `withContext(dbDispatcher)` transaction block — no new connection, no raw `Dispatchers.IO`.
- **§3.3 rate limiting**: Redis-backed custom via the shared `RateLimiter` — the canonical pattern; **no docs/11 amendment needed** (this builds on the registered pattern; hourly-skips-stagger is already the documented rule). Geocell *keying* is a new key axis, not a new mechanism.
- **§4 reuse-first**: reuses `RateLimiter`, the `moderation_queue` insert path, and the `display_location`/`JitterEngine` output already computed in `create()` (the gate reads the `display` coordinate the flow already derives — no recompute).

No Pattern-Registry deviation is introduced, so no docs/11 § Pattern Registry edit is required.

## Cross-layer cohesion (docs/12)

This capability spans **backend** (gate + V36 migration) and **admin (read)**; **no mobile** layer.
- **Backend**: the gate, the limiter, the V36 enum extension, the same-tx queue write.
- **Admin**: the `moderation_queue` viewer must render + resolve `trigger='area_spam'` rows. The viewer addresses rows by `(target_type, target_id)` and renders the `trigger` string generically; apply-phase MUST verify this (grep admin Pebble templates + Kotlin for any hardcoded `trigger` enum allow-list). If the admin layer hardcodes the trigger set anywhere (label map, filter dropdown), the one-line `area_spam` addition ships **in this change** — not deferred — so the change never writes queue rows no surface can triage (docs/12 §2). A negative-guard test asserts the new trigger is displayable.
- **Mobile**: none, by design (silent routing). Captured as a Non-Goal, not a deferred layer.

## Risks / Trade-offs

- **Redis INCR not transactional with the PG INSERT** → a post whose INSERT later rolls back already incremented the cell (mild over-count). Mitigation: matches the existing daily-cap "slot not released on later failure" anti-abuse posture; over-count is conservative and self-heals within the 1 h window.
- **Busy legitimate areas (markets, concerts) trip the threshold** → false-positive queue rows. Mitigation: soft-flag only (Decision 3) — an admin keeps the posts; threshold is RC-retunable later (Decision 5). A market trips it precisely because it's genuinely dense; review cost is bounded by the per-cell single-flag-per-post idempotency.
- **Cell-boundary spam** → a spammer posting from a point near a 0.01° grid line gets ~2× headroom across two adjacent cells, and up to **~4× at a 4-cell junction** (the worst case). Jitter is *deterministic per author-secret and centered on the true point*, so it does NOT meaningfully diffuse a *coordinated multi-account* flood deliberately sited on a seam. Mitigation (accepted at MVP, soft-flag posture): the per-user daily cap bounds any single account, and ~200 posts to game a junction is itself anomalous; the soft-flag (visible-pending-review) means an admin still catches a seam flood on the merged `(target_type, target_id)` queue view. The **PostGIS `ST_DWithin`-radius upgrade is the telemetry-gated remedy** if seam-gaming shows up in staging — it eliminates the grid entirely (true 1 km radius, no seams), traded against the hot-path COUNT cost (Decision 1). Documented here so the corner case is a known, bounded acceptance, not an oversight.
- **Equator-seam sign split** → `%.2f` keeps a coordinate's sign even when the magnitude rounds to `0.00`, so a `display_location` just south of the equator (`-0.00x`) would key a different cell than its northern `+0.00x` half. Because Indonesia straddles lat 0, this seam is in-country (a pure sign artifact, distinct from the grid-line cell-boundary case above). Mitigation: `cellId` collapses the `-0.00` token to `0.00`, touching only the zero token (every non-zero cell's `%.2f` rounding is unchanged); pinned by an `AreaPostDensityLimiterTest` case.
- **docs/05 wording drift** if the amendment is skipped → Mitigation: the amendment is a tasks.md item gated before archive.

## Migration Plan

1. V36 `ALTER … DROP/ADD CONSTRAINT` extends the trigger enum (additive; no data rewrite; existing rows untouched). Forward-only; rollback = a V37 reverting the constraint (no `area_spam` rows can exist until app code ships, so a pre-app-deploy rollback is safe).
2. Deploy order: migration first (enum accepts `area_spam`), then app code (writes it). Standard merge → staging auto-deploy.
3. Rollback: the gate is self-contained; reverting the app code stops writing `area_spam` rows. Existing rows remain valid (admin can resolve them). The constraint stays (harmless superset).

## Open Questions

- **Threshold value (50)**: docs/05 says 50; kept verbatim. If staging telemetry shows Jakarta CBD legitimately exceeds 50/cell/h, retune via Decision 5's RC seam — flagged for the operator, not blocking.
- **Queue priority for `area_spam` (5 vs lower)**: shipped at the default 5 (parity with UU-ITE Flag). If area-spam should triage below content flags, lower it — a one-constant change, deferred to operator preference.
