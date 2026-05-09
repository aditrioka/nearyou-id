## MODIFIED Requirements

### Requirement: `TextModerator` integration is invoked AFTER existing length and rate-limit gates, BEFORE INSERT

Every write-path handler that wires `TextModerator.moderate(...)` SHALL run the moderator AFTER existing length validation, rate-limit check, and (where applicable) block check, AND BEFORE the content INSERT. The exact order is:

1. Authentication / authorization
2. Per-endpoint rate limit (existing where applicable)
3. Block check (existing where applicable: chat send)
4. Content length guard (post 280 / reply 280 / chat 2000)
5. **`TextModerator.moderate(content)`** ← Layer 1 + Layer 2 (synchronous)
6. INSERT into the relevant table
7. **`perspectiveDispatcherScope.launch { perspectiveModerator.moderate(targetType, newRowId, content) }`** ← Layer 3 (asynchronous, fire-and-forget) — applies to `posts` and `post_replies` per the [`text-moderation-perspective-api-layer`](../../../specs/text-moderation-perspective-api-layer/spec.md) capability; does NOT apply to chat as of this change (see that capability's design Open Question 1 for the chat deferral rationale)
8. (chat path only) broadcast publish via `ChatRealtimeClient`

This ordering ensures: cheap deterministic checks (length, rate limit, block) reject malformed/abusive requests before invoking the moderator (which has Redis/Remote Config network surface); the moderator runs against already-length-validated content (no content too long to fingerprint); content is moderated before becoming visible.

For `Verdict.Flag`, the `moderation_queue` row SHALL be written in the same SQL transaction as the content INSERT, with `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` for idempotency (per existing [`moderation-queue/spec.md`](../../specs/moderation-queue/spec.md) UNIQUE constraint).

**Layer 3 boundary:** Layer 3 (Perspective API) runs AFTER the synchronous INSERT in a separate async dispatcher (the `PerspectiveDispatcherScope`). Layer 3 is NOT part of the synchronous `Verdict` produced by `TextModerator.moderate(content)`. The synchronous moderator returns `Allow` / `Reject` / `Flag`; the asynchronous Layer 3 produces a separate `Outcome` (`NoAction` / `FlagOnly` / `AutoHide`) per the [`text-moderation-perspective-api-layer`](../../../specs/text-moderation-perspective-api-layer/spec.md) capability. The two surfaces have different lifetimes (synchronous request vs fire-and-forget coroutine), different transaction boundaries (Layer 1+2 share the request transaction; Layer 3 owns its own transaction), and different failure semantics (Layer 1+2 throw or return Allow on loader failure; Layer 3 always fails open via `Outcome.NoAction`).

#### Scenario: Moderator runs after length guard, before INSERT
- **WHEN** a write-path handler is statically analyzed for the call order of `contentLengthGuard`, `TextModerator.moderate`, and the canonical INSERT call
- **THEN** the call order is exactly: `contentLengthGuard` → `TextModerator.moderate` → `INSERT` (no INSERT before moderate; no moderate before length guard)

#### Scenario: Flag verdict writes moderation_queue row in the same transaction as INSERT
- **WHEN** `Verdict.Flag` is produced AND the handler proceeds to INSERT
- **THEN** the SQL transaction emits both the content INSERT (e.g., `INSERT INTO posts ...`) AND the `INSERT INTO moderation_queue (target_type, target_id, trigger, ...) VALUES (...) ON CONFLICT DO NOTHING` AND a single COMMIT (atomicity)

#### Scenario: Flag with idempotent retry does not double-write moderation_queue
- **GIVEN** a `Verdict.Flag` writes a `moderation_queue` row with `(target_type, target_id, trigger) = ('post', U, 'uu_ite_keyword_match')` AND a retry of the same content+target produces another `Verdict.Flag`
- **WHEN** the second handler attempts the INSERT
- **THEN** the `ON CONFLICT (target_type, target_id, trigger) DO NOTHING` clause suppresses the duplicate AND the queue contains exactly one row for that target+trigger

#### Scenario: Concurrent Flag inserts collapse to one queue row
- **GIVEN** two concurrent transactions T1 and T2 each produce a `Verdict.Flag` for the same target tuple `(target_type='post', target_id=P, trigger='uu_ite_keyword_match')` (e.g., a retry race where both clients submit the same content nearly simultaneously, both pass auth + length + moderation, both reach the INSERT step)
- **WHEN** both transactions execute `INSERT INTO moderation_queue ... ON CONFLICT (target_type, target_id, trigger) DO NOTHING` AND both COMMIT
- **THEN** exactly one `moderation_queue` row exists for the tuple AND neither transaction surfaces a unique-violation error to its caller (mirrors the existing `moderation-queue` capability's idempotency guarantee on the auto-hide-3-reports writer; the Layer 2 writer this change introduces has the same semantics)

#### Scenario: Layer 3 dispatch fires AFTER the INSERT commit (post path)
- **WHEN** the `POST /api/v1/posts` handler runs AND `TextModerator.moderate(content)` returns `Verdict.Allow` AND the INSERT into `posts` commits successfully
- **THEN** within the handler scope, `perspectiveDispatcherScope.launch { perspectiveModerator.moderate(POST, <new post id>, content) }` is invoked exactly once AFTER the INSERT commit AND BEFORE the response is sent (Layer 3 runs in a fire-and-forget coroutine; the response is not blocked on Perspective)

#### Scenario: Layer 3 dispatch fires AFTER the INSERT commit (reply path)
- **WHEN** the `POST /api/v1/posts/{post_id}/replies` handler runs AND `TextModerator.moderate(content)` returns `Verdict.Allow` AND the INSERT into `post_replies` commits successfully
- **THEN** within the handler scope, `perspectiveDispatcherScope.launch { perspectiveModerator.moderate(REPLY, <new reply id>, content) }` is invoked exactly once AFTER the INSERT commit AND BEFORE the response is sent

#### Scenario: Layer 1 reject prevents Layer 3 dispatch
- **WHEN** `TextModerator.moderate(content)` returns `Verdict.Reject(matchedKeywords = listOf("badword"))` AND the handler returns HTTP 400
- **THEN** `perspectiveDispatcherScope.launch { ... }` is NOT invoked (no row was INSERTed; no target exists to moderate; the handler short-circuits before reaching the dispatch call site — verifiable via mock-spy call count on the dispatcher scope)

#### Scenario: Layer 2 flag still triggers Layer 3 dispatch
- **WHEN** `TextModerator.moderate(content)` returns `Verdict.Flag(matchedKeywords = listOf("sara1", "sara2", "sara3"))` (Layer 2 writes a queue row with `trigger = 'uu_ite_keyword_match'`) AND the INSERT commits successfully
- **THEN** `perspectiveDispatcherScope.launch { ... }` IS invoked AFTER the INSERT commit (Layer 3 runs independently of Layer 2's outcome — both can fire on the same row, producing two queue rows with distinct triggers per [`docs/05-Implementation.md:545`](../../../../../docs/05-Implementation.md))

#### Scenario: Chat path does NOT invoke Layer 3 dispatch (as of this change)
- **WHEN** `POST /api/v1/chat/{conversation_id}/messages` runs AND `TextModerator.moderate(content)` returns `Verdict.Allow` AND the INSERT into `chat_messages` commits successfully
- **THEN** `perspectiveDispatcherScope.launch { ... }` is NOT invoked (chat-message Layer 3 is explicitly deferred per the `text-moderation-perspective-api-layer` capability design Open Question 1; chat ships Layer 1+2 only as of this change)
