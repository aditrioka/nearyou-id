# post-area-density-cap Specification

## Purpose

The post-area-density-cap capability is **Layer 4 "Per Area (anti local spam)"** of the documented 4-layer anti-spam architecture (`docs/05-Implementation.md` § Anti-Spam) — the geographic-clustering defense the per-user daily cap (Layer 2) cannot provide, since many accounts each posting under their own limit can still bury a neighborhood's timeline. It maintains a per-area counter of recently-created posts keyed on a ~1.1 km grid cell derived from the post's HMAC-fuzzed `display_location` (never `actual_location` — the spatial-fuzzing invariant), via the shared Redis `RateLimiter`'s sliding-window `tryAcquireByKey` path (key `{scope:area_post}:{cell:<lat>_<lng>}`, capacity 50, 1 h window). When a cell exceeds 50 posts within the rolling hour, the next post is routed to the `moderation_queue` (`trigger = 'area_spam'`) for human review — a **soft flag, not a hard reject**: the post is still created, returned `201`, and stays visible, and the poster is never told (anti-probing). The gate applies to all tiers (area-keyed, not user-keyed; Premium is not exempt), is idempotent (one queue row per post), and composes with the moderator `Verdict.Flag` write in the same transaction.
## Requirements
### Requirement: Per-area density gate routes over-dense-area posts to manual review

The system SHALL maintain a per-area counter of recently-created posts and, when an area exceeds the density threshold within the window, route the *next* post created in that area to the `moderation_queue` for human review. This is the Layer 4 "Per Area (anti local spam)" control (`docs/05-Implementation.md` § Anti-Spam). The threshold is **50 posts** and the window is a **rolling 1 hour**; a post is flagged when the area's post count within the window **strictly exceeds** the threshold — `count > 50` (the 1st–50th posts in a cell/window are clean; the 51st and beyond are flagged). The boundary is exact: the post whose creation makes the in-window count become 51 is the first flagged post. A flagged post SHALL still be created and returned `HTTP 201` — the gate is a **soft flag to manual review, NOT a hard reject**. On a flag, the system SHALL INSERT one `moderation_queue` row with `target_type = 'post'`, `target_id = <new_post_id>`, `trigger = 'area_spam'`, `status = 'pending'`, `priority = 5`, in the **same transaction** as the `posts` INSERT, using `ON CONFLICT (target_type, target_id, trigger) DO NOTHING`. The flagged post SHALL NOT be auto-hidden (`is_auto_hidden = FALSE`).

#### Scenario: Below threshold — post created, no queue row
- **WHEN** a post is created in an area whose post count within the last hour is 49 (this would be the 50th)
- **THEN** the response is HTTP 201 AND the `posts` row exists AND no `moderation_queue` row exists for that post

#### Scenario: Over threshold — post created and flagged
- **WHEN** a post is created in an area whose post count within the last hour already reached 50 (this is the 51st)
- **THEN** the response is HTTP 201 with the canonical post payload AND the `posts` row exists with `is_auto_hidden = FALSE` AND exactly one `moderation_queue` row exists with `target_type = 'post'`, `target_id = <new_post_id>`, `trigger = 'area_spam'`, `status = 'pending'`

#### Scenario: Flag does not change the response wire shape
- **WHEN** a post is flagged by the area-density gate
- **THEN** the `201` response body field set is identical to an unflagged create (`{ id, content, latitude, longitude, distance_m, created_at }`) AND no error code is returned AND no `Retry-After` header is present

### Requirement: Area-density gate applies to all subscription tiers

The area-density gate is area-keyed, not user-keyed, and SHALL apply to every poster regardless of `subscription_status`. Unlike the per-user daily post cap (which Premium users skip), a Premium poster contributing to an over-dense area SHALL be flagged the same as a Free poster.

#### Scenario: Premium poster is subject to the area gate
- **WHEN** a Premium (`premium_active`) user creates the 51st post in an over-dense area within the window
- **THEN** the response is HTTP 201 AND a `moderation_queue` row with `trigger = 'area_spam'` is written for that post (Premium does NOT skip the area gate)

### Requirement: Area cell derived from display_location on a degree grid

The area "cell" SHALL be derived from the post's `display_location` (the HMAC-fuzzed coordinate) — never `actual_location` — rounded to a fixed **0.01° latitude/longitude grid** (≈ 1.1 km per side at Indonesian latitudes). The cell id SHALL be a deterministic, locale-independent string of the rounded lat/lng pair using a fixed format (e.g. `"%.2f_%.2f"` with `Locale.ROOT`) so that equal coordinates always produce one identical key — `-6.20` and `-6.2` MUST NOT split a cell into two counters. Two posts whose `display_location` rounds to the same grid cell SHALL share one counter; posts in different cells SHALL be counted independently. Reading `display_location` (not `actual_location`) preserves the spatial-fuzzing invariant for this non-admin path.

#### Scenario: Distinct cells are counted independently
- **GIVEN** an area cell A is already over the density threshold within the window
- **WHEN** a post is created in a different cell B whose count is well below the threshold
- **THEN** the post in cell B is HTTP 201 with no `moderation_queue` row (cell A's saturation does not flag cell B)

#### Scenario: Gate keys on display_location, not actual_location
- **WHEN** the area-density counter is incremented for a new post
- **THEN** the cell is derived from the post's `display_location` (fuzzed) coordinate AND the code path never reads `actual_location` (spatial-fuzzing invariant; `display_location`-only on non-admin paths)

### Requirement: Counter is a Redis hourly sliding-window limiter reusing the shared RateLimiter

The per-area counter SHALL be implemented via the shared Redis-backed `RateLimiter` abstraction — NOT a parallel Redis-counter mechanism — through its **axis-agnostic `tryAcquireByKey(key, capacity, ttl)` entry point** (the documented geocell/IP/fingerprint call site), NOT the user-axis `tryAcquire(userId, …)` the per-user post cap uses (which would attach per-user telemetry/WIB-stagger semantics to an area bucket). It SHALL be keyed `{scope:area_post}:{cell:<lat>_<lng>}` with capacity 50 over a **rolling (sliding) 1-hour window** (the shared limiter's non-`_day}` path is sliding-window — superseding the literal fixed INCR+EXPIRE of docs/05 §Layer 4, an improvement; see design Decision 2). Because this is an **hourly** limit, it SHALL NOT use the per-user daily reset stagger (`computeTTLToNextReset`); the window is a plain 1-hour limiter window. The key SHALL conform to the `{scope:<value>}:{axis:<value>}` two-segment hash-tag shape enforced by `RedisHashTagRule`.

#### Scenario: Window expiry resets the cell count
- **GIVEN** an area cell reached the threshold within the window
- **WHEN** more than one hour passes with no further posts in that cell
- **THEN** a subsequent post in that cell is NOT flagged (the hourly window has elapsed and the cell count no longer exceeds the threshold)

#### Scenario: Sliding window ages out individual posts (not a fixed-window reset)
- **GIVEN** 50 posts were created in one cell at time t0 (count = 50, none flagged) AND one more post at t0+59m is flagged (in-window count = 51)
- **WHEN** a further post is created at t0+61m — by which point the original t0 posts have aged out of the trailing-60-minute window (only the t0+59m post remains in-window)
- **THEN** that post is NOT flagged (the in-window count is 2, not 52) — confirming the window slides per-post rather than resetting as one fixed block

#### Scenario: Counter key uses the hash-tag scope:axis shape
- **WHEN** the area-density limiter constructs its Redis key for a cell
- **THEN** the key has the form `{scope:area_post}:{cell:<lat>_<lng>}` (two hash-tagged segments) satisfying `RedisHashTagRule`

### Requirement: Area-spam queue insert is idempotent per post

A given post SHALL produce at most one `moderation_queue` row for `trigger = 'area_spam'`. A retry or a concurrent duplicate that would insert the same `(target_type='post', target_id, 'area_spam')` tuple SHALL be suppressed by the UNIQUE `(target_type, target_id, trigger)` constraint plus `ON CONFLICT … DO NOTHING`.

#### Scenario: Duplicate area-spam insert suppressed
- **GIVEN** a post P was flagged with a `moderation_queue` row `(target_type='post', target_id=P, trigger='area_spam')`
- **WHEN** a second insert attempts the same tuple for P
- **THEN** the `moderation_queue` table contains exactly ONE row for `(target_type='post', target_id=P, trigger='area_spam')`

### Requirement: Area-density routing is silent to the poster

The poster SHALL NOT be told that their post was routed to manual review by the area-density gate — no error, no distinct response field, no notification — mirroring the existing `Verdict.Flag` moderation path (anti-probing). The only observable effect is the `moderation_queue` row visible to admins.

#### Scenario: No client-observable signal on area flag
- **WHEN** a post is flagged by the area-density gate
- **THEN** the client receives the standard `201` with no indication of the flag AND no `notifications` row of any area-spam type is written for the poster

