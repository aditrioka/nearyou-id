## Why

nearyou-id's anti-spam defense is a documented **4-layer architecture** ([`docs/05-Implementation.md`](../../../docs/05-Implementation.md) § Anti-Spam): Layer 1 device attestation, Layer 2 per-user daily caps, Layer 3 one-identifier-one-account, and **Layer 4 "Per Area (anti local spam)"**. Layers 1–3 all ship — including the per-user 10/day post cap that landed in the 2026-06-10 holistic-audit wave 2 (finding 02-M2 part 1). **Layer 4 is the lone unbuilt layer**: docs/05:1330 specifies "Max 50 new posts in a 1km radius / 1h (via `display_location` spatial query). Threshold hit → manual review. Redis INCR + EXPIRE counter." — but there is zero code, no spec, and no tracking issue (audit finding 02-M2 part 2). For a *location-based* social app, geographic spam-clustering (a flood of posts pinned to one area) is the core abuse vector the per-user caps cannot catch — many accounts each posting under their own daily limit still bury a neighborhood's timeline. This change closes that gap.

## What Changes

- **New per-area density gate on post creation.** When a ~1 km area accumulates more than **50 new posts within a rolling 1-hour window**, the *next* post created in that area is routed to the `moderation_queue` for human review. The post is still created and returned `201` — this is a **soft flag to manual review, NOT a hard reject** (unlike the per-user daily cap, which returns `429`). The poster is never told (anti-probing; identical to the existing `Verdict.Flag` moderation path).
- **Applies to ALL tiers.** Area-spam is *area-keyed*, not user-keyed — Premium posters are subject to the area gate, unlike the per-user daily cap which Premium skips. A neighborhood floods regardless of who is paying.
- **Redis fixed-window counter** keyed on a ~1.1 km grid cell derived from the post's `display_location` (the HMAC-fuzzed coordinate — preserves the spatial-fuzzing invariant; non-admin paths never read `actual_location`). `INCR` + conditional `EXPIRE 3600` per cell, mirroring the docs/05 "Redis INCR + EXPIRE counter" verbatim. No PostGIS query on the hot write path (see design.md Decision 1).
- **New `moderation_queue.trigger` enum value `area_spam`** via Flyway migration **V36** (next free version). The V9 trigger CHECK reserves 7 forward-compat values; none denotes per-area spam, so the gate needs its own value to keep admin triage filterable and distinct from the (separate, unbuilt) per-user `anomaly_detection` mechanism.
- **Composes with the existing same-transaction queue-write path.** On a threshold hit the gate INSERTs the `posts` row AND one `moderation_queue` row (`target_type='post'`, `trigger='area_spam'`, `ON CONFLICT (target_type,target_id,trigger) DO NOTHING`, `is_auto_hidden=FALSE`) in the same transaction — the exact shape the `Verdict.Flag` UU-ITE path already uses. The threshold-hit and Flag paths can both fire on one post (two queue rows, one per trigger; the existing grouping handles this).
- **Admin read-path stays whole (docs/12).** The existing report-queue / moderation viewer renders `moderation_queue` rows by `(target_type, target_id)`; this change verifies `trigger='area_spam'` rows display + resolve there, and includes any one-line admin-side enum addition needed so the new rows are triageable (no backend-only slice that writes rows no surface can show).

## Capabilities

### New Capabilities

- `post-area-density-cap`: the per-area density mechanism — the Redis fixed-window geocell counter (key shape, threshold, window, cell derivation from `display_location`), the all-tiers rule, the "soft flag to manual review, not reject" semantics, and idempotent single-queue-row-per-post behavior.

### Modified Capabilities

- `post-creation`: the `create()` flow gains the area-density gate — its call-order placement (after the daily cap, composing with the moderator Flag path), the same-transaction `moderation_queue` write on a threshold hit, the unchanged `201` response/error envelope (no new error code), and the all-tiers applicability.
- `moderation-queue`: the `trigger` CHECK enum gains the value `area_spam` (8 values total), shipped by the V36 migration as a `DROP CONSTRAINT … ADD CONSTRAINT` on the inline `moderation_queue_trigger_check`.

## Impact

- **Code**: `backend/ktor/.../post/CreatePostService.kt` (new gate between the daily-cap step and/or the moderator step, plus the same-tx queue write); a new `AreaPostDensityLimiter` (geocell counter, mirroring the `PostRateLimiter` delegation shape) under `id.nearyou.app.post`; the `moderation_queue` insert helper. No route/DTO/wire change — `POST /api/v1/posts` request + `201` response are byte-identical.
- **Schema**: Flyway **V36** — `ALTER TABLE moderation_queue DROP CONSTRAINT moderation_queue_trigger_check, ADD CONSTRAINT moderation_queue_trigger_check CHECK (trigger IN (… 8 values …))`. No new tables/columns.
- **Redis**: one new single-key counter family `{scope:area_post}:{cell:<lat>_<lng>}` (INCR + EXPIRE 3600). Single-key op (no cross-slot multi-key), hash-tag-conformant for `RedisHashTagRule`.
- **Admin**: read-only — confirm/extend the moderation-queue viewer to render + resolve `trigger='area_spam'` (docs/12 cohesion).
- **Mobile**: none (silent server-side routing; the poster is not notified — anti-probing).
- **Lint/invariants**: `display_location`-only on the non-admin path (spatial-fuzzing invariant ✓); `{scope:}` hash-tag key shape (`RedisHashTagRule` ✓); content guards already enforced upstream; no `secretKey`/IP-extraction surface added.
- **Docs**: docs/05 § Layer 4 wording reconciled to the shipped geocell-counter mechanism (the "spatial query" phrasing) — see design.md Decision 1 + tasks.md.
- **Out of scope** (declared, not silently dropped): the Phase 4 #17 per-user 30-day-baseline `anomaly_detection` capability (distinct mechanism + distinct reserved enum value); any *new* admin UI beyond rendering the existing row shape with the new trigger value.
