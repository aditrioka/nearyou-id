package id.nearyou.app.infra.supabase.realtime

import id.nearyou.app.core.domain.chat.ChatMessageBroadcast
import id.nearyou.app.core.domain.chat.ChatRealtimeClient
import id.nearyou.app.core.domain.chat.PublishResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.UUID

/**
 * Supabase Realtime broadcast publisher backing [ChatRealtimeClient]. Hand-rolled HTTP
 * client against the Supabase broadcast endpoint (`POST {projectUrl}/realtime/v1/api/broadcast`)
 * per `chat-realtime-broadcast` design § D7's fallback path — the project-wide policy
 * "no Supabase SDK outside `:infra:supabase`" is satisfied either way; the hand-roll keeps
 * dependency footprint tight and gives full control over per-attempt timeout + retry policy.
 *
 * **Channel/topic mapping (design § D5).** The publisher constructs a channel identifier
 * `realtime:conversation:<conversationId>` for visibility/symmetry with the Supabase client
 * API; the prefix is stripped to derive the realtime TOPIC `conversation:<conversationId>`
 * sent in the broadcast HTTP body. The V15 `participants_can_subscribe` RLS policy regex
 * `^conversation:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` is what
 * Supabase evaluates against the topic AFTER stripping.
 *
 * **Retry policy (design § D4).** 4 total attempts (1 initial + 3 retries) with backoff
 * sleeps ~100 / 300 / 900 ms BEFORE retries 2/3/4 (3 inter-attempt sleeps for 4 total
 * attempts; mathematically consistent). Retry on [IOException], [HttpRequestTimeoutException],
 * and HTTP 5xx. Do NOT retry on HTTP 4xx (caller misconfiguration is non-recoverable).
 *
 * **Failure shape (design § D12).** [PublishResult.Failure.reason] carries the
 * fully-qualified class name of the LAST exception captured during the retry loop
 * (e.g., `"java.io.IOException"`). For 4xx, [SupabaseBroadcastHttp4xxException] is the
 * captured class.
 *
 * **Credential resolution.** The constructor takes the Supabase service role key directly;
 * the DI binding resolves it via `secretKey(env, "supabase-service-role-key")` per
 * `chat-realtime-broadcast` spec § "Credential resolution via secretKey helper". The key
 * value is held in the `serviceRoleKey` field for the lifetime of this instance and is
 * NEVER logged. The defense-in-depth scenarios in the spec verify this.
 *
 * **RLS bypass is intentional.** The Supabase service role key bypasses RLS by design —
 * the V15 `participants_can_subscribe` policy gates SUBSCRIBERS, not the server-originated
 * publisher. A future maintainer MUST NOT switch this client to the anon key or HS256
 * client token; both fail to authorize a server-side broadcast.
 */
class SupabaseBroadcastChatClient(
    private val projectUrl: String,
    private val serviceRoleKey: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    /**
     * Suspending sleeper hook — defaults to [delay]. Tests inject a deterministic
     * sleeper to capture inter-attempt timing without burning real wall-clock.
     */
    private val sleeper: suspend (Long) -> Unit = ::delay,
) : ChatRealtimeClient {
    init {
        require(projectUrl.isNotBlank()) { "projectUrl must not be blank" }
        require(serviceRoleKey.isNotBlank()) { "serviceRoleKey must not be blank" }
    }

    override suspend fun publish(
        conversationId: UUID,
        message: ChatMessageBroadcast,
    ): PublishResult {
        val channelIdentifier = chatBroadcastChannelIdentifier(conversationId)
        val topic = channelIdentifier.removePrefix(REALTIME_PREFIX)
        val requestBody = buildBroadcastRequest(topic, message)

        var lastError: Throwable = LastErrorPlaceholder
        for (attemptIndex in 0 until TOTAL_ATTEMPTS) {
            if (attemptIndex > 0) {
                sleeper(BACKOFF_MS[attemptIndex - 1])
            }
            try {
                val response: HttpResponse =
                    httpClient.post("$projectUrl$BROADCAST_PATH") {
                        header(HEADER_API_KEY, serviceRoleKey)
                        header(HEADER_AUTHORIZATION, "Bearer $serviceRoleKey")
                        contentType(ContentType.Application.Json)
                        setBody(requestBody.toString())
                    }
                if (response.status.isSuccess()) {
                    return PublishResult.Success
                }
                if (response.status.value in CLIENT_ERROR_RANGE) {
                    // 4xx: caller misconfiguration / bad credential / unprocessable entity.
                    // Per design § D4 this is non-retryable; surface the failure now.
                    return PublishResult.Failure(SupabaseBroadcastHttp4xxException::class.java.name)
                }
                // 5xx (or unexpected non-2xx): retryable.
                lastError = SupabaseBroadcastHttp5xxException(response.status)
            } catch (e: HttpRequestTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        return PublishResult.Failure(lastError.javaClass.name)
    }

    private fun buildBroadcastRequest(
        topic: String,
        message: ChatMessageBroadcast,
    ): JsonObject =
        buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("topic", JsonPrimitive(topic))
                            put("event", JsonPrimitive(BROADCAST_EVENT))
                            put("private", JsonPrimitive(true))
                            put("payload", payloadFor(message))
                        },
                    )
                },
            )
        }

    companion object {
        /** Hand-rolled JSON object matching the wire schema in `chat-realtime-broadcast` § Payload. */
        internal fun payloadFor(message: ChatMessageBroadcast): JsonObject =
            buildJsonObject {
                put("id", JsonPrimitive(message.id.toString()))
                put("conversation_id", JsonPrimitive(message.conversationId.toString()))
                put("sender_id", JsonPrimitive(message.senderId.toString()))
                // Render policy: when the row is redacted, content surfaces as null
                // regardless of in-memory value (mirrors `chatMessageJson` in the chat
                // foundation REST DTO). New sends always have redactedAt = null so the
                // null branch fires only via the forward-compat scenario in the spec.
                if (message.redactedAt != null) {
                    put("content", JsonNull)
                } else {
                    put("content", message.content?.let(::JsonPrimitive) ?: JsonNull)
                }
                put("embedded_post_id", message.embeddedPostId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("embedded_post_snapshot", message.embeddedPostSnapshot ?: JsonNull)
                put("embedded_post_edit_id", message.embeddedPostEditId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("created_at", JsonPrimitive(message.createdAt.toString()))
                put("redacted_at", message.redactedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                // redaction_reason is intentionally NEVER serialized (matches the
                // chat-foundation read-path render policy).
            }

        /**
         * Build the publisher-side channel identifier for [conversationId]. Exposed for
         * test layering: ChatRealtimeBroadcastTest captures `conversationId` from a
         * test-double and asserts via this helper that the channel-identifier shape +
         * topic-extraction round-trip matches the canonical V15 RLS regex.
         */
        fun chatBroadcastChannelIdentifier(conversationId: UUID): String = "$REALTIME_PREFIX$CHANNEL_TOPIC_PREFIX$conversationId"

        /** Strip the publisher-side `realtime:` prefix to derive the underlying topic. */
        fun chatBroadcastTopic(channelIdentifier: String): String = channelIdentifier.removePrefix(REALTIME_PREFIX)

        const val REALTIME_PREFIX: String = "realtime:"
        const val CHANNEL_TOPIC_PREFIX: String = "conversation:"
        const val BROADCAST_PATH: String = "/realtime/v1/api/broadcast"
        const val BROADCAST_EVENT: String = "chat_message"
        const val HEADER_API_KEY: String = "apikey"
        const val HEADER_AUTHORIZATION: String = "Authorization"

        const val TOTAL_ATTEMPTS: Int = 4
        val BACKOFF_MS: LongArray = longArrayOf(100L, 300L, 900L)

        const val PER_ATTEMPT_TIMEOUT_MS: Long = 500L

        private val CLIENT_ERROR_RANGE: IntRange = 400..499

        private val log = LoggerFactory.getLogger(SupabaseBroadcastChatClient::class.java)

        private fun defaultHttpClient(): HttpClient =
            HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = PER_ATTEMPT_TIMEOUT_MS
                    connectTimeoutMillis = PER_ATTEMPT_TIMEOUT_MS
                    socketTimeoutMillis = PER_ATTEMPT_TIMEOUT_MS
                }
            }

        /**
         * Sentinel placeholder for the "no error captured yet" path. Should never be
         * surfaced via `PublishResult.Failure.reason` because the loop only returns
         * Failure after at least one attempt has captured a real error.
         */
        private object LastErrorPlaceholder : RuntimeException("publish loop did not capture an error")
    }
}

/**
 * Marker exception for HTTP 5xx responses from the Supabase broadcast endpoint. Captured
 * as `lastError` inside the retry loop and surfaces as
 * `PublishResult.Failure.reason = "id.nearyou.app.infra.supabase.realtime.SupabaseBroadcastHttp5xxException"`
 * after retry exhaustion.
 */
class SupabaseBroadcastHttp5xxException(val status: HttpStatusCode) :
    IOException("Supabase broadcast returned ${status.value}")

/**
 * Marker exception for HTTP 4xx responses. Non-retryable. Surfaces immediately as
 * `PublishResult.Failure.reason = "id.nearyou.app.infra.supabase.realtime.SupabaseBroadcastHttp4xxException"`.
 */
class SupabaseBroadcastHttp4xxException(message: String = "Supabase broadcast returned 4xx") :
    RuntimeException(message)
