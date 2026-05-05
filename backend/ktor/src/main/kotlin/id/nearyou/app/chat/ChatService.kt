package id.nearyou.app.chat

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset
import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Thin orchestrator over [ChatRepository]. Surfaces a sealed result type for create-or-return
 * so the route handler can map exception cases (block / self-DM / recipient-missing) to the
 * canonical 403 / 400 / 404 responses without coupling to the repository's exception classes.
 *
 * **Rate-limit ordering on `POST /api/v1/chat/{conversation_id}/messages`** (see
 * `chat-rate-limit` change § chat-conversations spec):
 *  1. Auth (route-layer).
 *  2. UUID validation on `conversation_id` (route-layer).
 *  3. **Daily limiter** (Free only — Premium skips entirely): `{scope:rate_chat_send_day}:{user:U}`,
 *     capacity = `premium_chat_send_cap_override` Remote Config flag (default 50), ttl =
 *     `computeTTLToNextReset(userId)` (per-user WIB stagger). Implemented by [tryRateLimit].
 *     The route MUST call this BEFORE JSON body parse + content guard + [sendMessage] so a
 *     Free attacker spamming oversized payloads / non-existent conversation UUIDs / blocked
 *     recipients still consumes slots and hits 429 at the cap rather than burning unlimited
 *     `invalid_request` / `404` / `403` responses (design.md Decision 5).
 *  4. JSON body parse + chat-foundation 2000-char content-length guard + empty-content guard
 *     (route-layer; runs AFTER limiter).
 *  5. [sendMessage]: conversation lookup → participant check → block check → INSERT +
 *     `last_message_at` UPDATE in one transaction. 404 / 403 (non-participant) / 403 (blocked)
 *     paths all leave the slot consumed — slot consumption is anti-abuse-positive (a Free
 *     attacker probing conversation UUIDs gets exactly 50 attempts per day).
 *  6. Return 201.
 *
 * **No `releaseMostRecent` on any path.** Unlike `LikeService.like`, the chat send handler has
 * no idempotent re-action equivalent (no `INSERT ... ON CONFLICT DO NOTHING` no-op). Every
 * successful POST is a real new row in `chat_messages`. 404 / 403 / 400 / 5xx all keep the
 * slot consumed (anti-abuse). Mirrors `ReplyService.post` byte-for-byte on this point.
 *
 * Send/list-messages/list-conversations otherwise pass through to the repository directly.
 * `POST /api/v1/conversations`, `GET /api/v1/conversations`, and
 * `GET /api/v1/chat/{id}/messages` are NOT rate-limited at the per-endpoint layer — read-side
 * throttling lives at the timeline session/hourly layer; conversation-create is rare and
 * already serialized by the user-pair advisory lock from chat-foundation.
 *
 * Lettuce's sync API blocks the calling thread on Netty I/O; per the `RateLimiter` contract
 * (interface non-suspending to preserve V9 compatibility), the wrap belongs at this call
 * site. [tryRateLimit] runs `tryAcquire` inside `withContext(Dispatchers.IO)`.
 */
class ChatService(
    private val repository: ChatRepository,
    private val notifications: NotificationEmitter,
    private val dispatcher: NotificationDispatcher,
    private val rateLimiter: RateLimiter,
    private val remoteConfig: RemoteConfig,
    private val clock: () -> Instant = Instant::now,
) {
    sealed interface CreateConversationResult {
        data class Created(val outcome: CreateConversationOutcome) : CreateConversationResult

        data class Returned(val outcome: CreateConversationOutcome) : CreateConversationResult

        data object Blocked : CreateConversationResult

        data object SelfDm : CreateConversationResult

        data object RecipientNotFound : CreateConversationResult
    }

    /**
     * Outcome of [tryRateLimit]. Mirrors `ReplyService.RateLimitOutcome` shape for HTTP-layer
     * mapping consistency. The route handler maps [RateLimitOutcome.RateLimited] → HTTP 429 +
     * `Retry-After` header; [Allowed] advances to JSON parse + content guard + [sendMessage].
     */
    sealed interface RateLimitOutcome {
        data object Allowed : RateLimitOutcome

        data class RateLimited(val retryAfterSeconds: Long) : RateLimitOutcome
    }

    /**
     * Runs the daily limiter for the chat send endpoint. MUST be invoked by the route
     * BEFORE JSON body parsing + content-length guard + [sendMessage]. Premium tiers
     * (`premium_active`, `premium_billing_retry`) skip the limiter entirely — the bucket
     * is never written for Premium.
     *
     * **Defensive null-tier fallback:** if `subscriptionStatus` is unexpectedly null /
     * empty (auth-path defect), the handler treats the caller as Free and applies the
     * cap (per spec § Daily send-rate cap "Defect-mode behavior"). The underlying defect
     * MUST be fixed in the auth-jwt path; this is a guardrail.
     */
    suspend fun tryRateLimit(
        userId: UUID,
        subscriptionStatus: String?,
    ): RateLimitOutcome {
        if (subscriptionStatus in PREMIUM_STATES) {
            return RateLimitOutcome.Allowed
        }
        if (subscriptionStatus.isNullOrBlank()) {
            log.warn(
                "event=chat_send_rate_limit_null_tier user_id={} fallback=apply_free_cap",
                userId,
            )
        }
        val cap = resolveDailyCap(userId)
        val outcome =
            withContext(Dispatchers.IO) {
                rateLimiter.tryAcquire(
                    userId = userId,
                    key = dailyKey(userId),
                    capacity = cap,
                    ttl = computeTTLToNextReset(userId, clock()),
                )
            }
        return when (outcome) {
            is RateLimiter.Outcome.RateLimited ->
                RateLimitOutcome.RateLimited(retryAfterSeconds = outcome.retryAfterSeconds)
            is RateLimiter.Outcome.Allowed -> RateLimitOutcome.Allowed
        }
    }

    fun createConversation(
        callerId: UUID,
        recipientId: UUID,
    ): CreateConversationResult {
        return try {
            val outcome = repository.findOrCreate1to1(callerId, recipientId)
            if (outcome.wasCreated) {
                CreateConversationResult.Created(outcome)
            } else {
                CreateConversationResult.Returned(outcome)
            }
        } catch (_: SelfDmException) {
            CreateConversationResult.SelfDm
        } catch (_: RecipientNotFoundException) {
            CreateConversationResult.RecipientNotFound
        } catch (_: BlockedException) {
            CreateConversationResult.Blocked
        }
    }

    fun listMyConversations(
        viewerId: UUID,
        cursor: ConversationsCursor?,
        limit: Int,
    ): List<ConversationListRow> = repository.listMyConversations(viewerId, cursor, limit)

    fun listMessages(
        conversationId: UUID,
        viewerId: UUID,
        cursor: MessagesCursor?,
        limit: Int,
    ): List<ChatMessageRow> = repository.listMessages(conversationId, viewerId, cursor, limit)

    /**
     * Inserts a chat message + emits the V10 `chat_message` notification + updates
     * `conversations.last_message_at`, all in one transaction (chat-foundation atomicity
     * contract + chat-message-notification design § D5 ordering: INSERT → emit → UPDATE
     * last_message_at → COMMIT). Throws [ConversationNotFoundException] (404),
     * [NotParticipantException] (403), or [BlockedException] (403 with the canonical
     * "Tidak dapat mengirim pesan ke user ini" body) on the corresponding rejection paths.
     *
     * Sender-shadow-ban skip per design § D3: when [senderShadowBanned] is true, the emit
     * step is skipped entirely (the `chat_messages` row still persists per the invisible-
     * actor contract). The flag is read from `UserPrincipal.isShadowBanned` at the route
     * layer — this method does NOT issue any `SELECT is_shadow_banned FROM users` for the
     * decision. Self-action suppression is structurally a no-op (1:1 chat: sender ≠
     * recipient — chat-foundation slot-race serialization rejects single-participant
     * conversations); block-suppression is delegated to [NotificationEmitter] (per design
     * § D6 — the emitter's bidirectional `user_blocks` query is the canonical check).
     *
     * After the transaction commits successfully, the FCM dispatcher fires post-commit
     * (fire-and-forget on `Dispatchers.IO` inside the dispatcher impl). A `null` emittedId
     * (e.g., suppressed by emitter-internal block-check on a race) skips dispatch.
     *
     * Caller MUST have already invoked [tryRateLimit] and observed [RateLimitOutcome.Allowed]
     * (the limiter is route-orchestrated to satisfy the spec's "limiter BEFORE all DB reads
     * AND before content guard" ordering). [sendMessage] does NOT consult the limiter and the
     * limiter does NOT call `releaseMostRecent` on any path — once a slot is acquired, it
     * stays consumed even if this method throws or rolls back the transaction.
     */
    fun sendMessage(
        conversationId: UUID,
        senderId: UUID,
        content: String,
        senderShadowBanned: Boolean,
    ): ChatMessageRow {
        var emittedId: UUID? = null
        // @chat-shadow-ban-skip-couples: realtime-broadcast+notification-emit
        // Both ChatRealtimeClient.publish and NotificationEmitter.emit MUST skip together
        // when viewer.isShadowBanned is true. Removing one without the other breaks the
        // symmetric privacy guarantee — see chat-conversations spec § "Send-message endpoint"
        // and chat-realtime-broadcast spec § "Publish-side shadow-ban skip".
        val emitInTx: ((java.sql.Connection, ChatMessageRow, UUID) -> Unit)? =
            if (senderShadowBanned) {
                null
            } else {
                { conn, row, recipientId ->
                    emittedId =
                        notifications.emit(
                            conn = conn,
                            recipientId = recipientId,
                            actorUserId = senderId,
                            type = NotificationType.CHAT_MESSAGE,
                            targetType = "message",
                            targetId = row.id,
                            bodyData = buildChatMessageBodyData(conversationId, row.content),
                        )
                }
            }
        val row =
            repository.sendMessage(
                conversationId = conversationId,
                senderId = senderId,
                content = content,
                emitInTx = emitInTx,
            )
        emittedId?.let(dispatcher::dispatch)
        return row
    }

    private fun buildChatMessageBodyData(
        conversationId: UUID,
        content: String?,
    ): JsonObject =
        buildJsonObject {
            put("conversation_id", JsonPrimitive(conversationId.toString()))
            if (content == null) {
                put("preview", JsonNull)
            } else {
                put("preview", JsonPrimitive(content.firstCodePoints(CHAT_PREVIEW_MAX_CODE_POINTS)))
            }
        }

    /**
     * Resolves the daily cap for a Free-tier caller. Reads
     * `premium_chat_send_cap_override` from Remote Config; coerces unset / null /
     * non-positive / oversized integers and SDK errors all to the default 50.
     * Mirrors `ReplyService.resolveDailyCap` byte-for-byte semantics — same fallback
     * ladder, same audit-log shape, same upper-bound clamp threshold at 10,000.
     *
     * The clamp threshold (10,000) prevents accidental cap removal via a typo
     * (e.g., `5000000000` instead of `50`). No abuse signal supports a Free user
     * sending >10,000 chat messages/day. 10,000 is the threshold that triggers the
     * fallback, not a clamp value applied to the override.
     */
    private fun resolveDailyCap(userId: UUID): Int {
        val raw =
            try {
                remoteConfig.getLong(PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY)
            } catch (t: Throwable) {
                log.warn(
                    "event=remote_config_error key={} fallback=default_50 user_id={} message={}",
                    PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY,
                    userId,
                    t.message,
                )
                return DEFAULT_DAILY_CAP
            }
        if (raw == null) {
            return DEFAULT_DAILY_CAP
        }
        if (raw <= 0L || raw > MAX_OVERRIDE_CAP) {
            log.warn(
                "event=remote_config_invalid key={} value={} fallback=default_50 reason={}",
                PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY,
                raw,
                if (raw <= 0L) "non_positive" else "above_clamp_threshold",
            )
            return DEFAULT_DAILY_CAP
        }
        val cap = raw.toInt()
        log.info(
            "event=remote_config_override_applied key={} value={} user_id={}",
            PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY,
            cap,
            userId,
        )
        return cap
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
        const val MAX_PAGE_SIZE: Int = 100
        const val DEFAULT_DAILY_CAP: Int = 50
        const val PREMIUM_CHAT_SEND_CAP_OVERRIDE_KEY: String = "premium_chat_send_cap_override"

        /**
         * Code-point ceiling for `body_data.preview` per `chat-message-notification` spec
         * § "body_data.preview shape" — matches the `post_excerpt` / `reply_excerpt` 80-cp
         * truncation precedent. Truncation is on Unicode code points (NOT chars / bytes)
         * so a 4-byte emoji at the boundary either fits whole or is excluded whole.
         */
        const val CHAT_PREVIEW_MAX_CODE_POINTS: Int = 80

        /**
         * Upper-bound clamp threshold for the override flag — values above this fall
         * back to [DEFAULT_DAILY_CAP] (anti-typo guard; spec § Flag oversized integer
         * scenario). 10,000 chosen because no abuse signal supports a Free user
         * sending >10,000 chat messages/day.
         */
        private const val MAX_OVERRIDE_CAP: Long = 10_000L

        /** Subscription statuses that skip the daily limiter entirely. */
        private val PREMIUM_STATES = setOf("premium_active", "premium_billing_retry")

        private val log = LoggerFactory.getLogger(ChatService::class.java)

        internal fun dailyKey(userId: UUID): String = "{scope:rate_chat_send_day}:{user:$userId}"
    }
}

/**
 * Surrogate-pair-safe code-point truncation, mirroring the `ReplyService.firstCodePoints`
 * helper. Code-point counted (NOT char-counted via `String.length`) so a non-BMP emoji
 * (`String.codePointCount > 1`-position by Java char semantics) is treated as a single
 * code point. A 4-byte emoji whose code point would be position 81 is excluded whole;
 * an emoji whose code point is exactly position 80 is included whole.
 */
internal fun String.firstCodePoints(n: Int): String {
    if (n <= 0) return ""
    var cpCount = 0
    var i = 0
    while (i < length && cpCount < n) {
        val cp = codePointAt(i)
        i += Character.charCount(cp)
        cpCount++
    }
    return substring(0, i)
}
