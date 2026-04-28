package id.nearyou.app.infra.fcm

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode

/**
 * Test seam around `FirebaseMessaging.send(Message)`. Returns a sealed
 * [FcmSendResult] instead of throwing so tests can drive every error path
 * without constructing a `FirebaseMessagingException` (whose constructors are
 * package-private in the Admin SDK and cannot be subclassed cleanly).
 *
 * Production wiring: [FcmSender.fromFirebaseMessaging] wraps the SDK's
 * `send(Message)` and translates exceptions into the result variants per
 * `openspec/specs/fcm-push-dispatch/spec.md` — `UNREGISTERED` and
 * `SENDER_ID_MISMATCH` map to [FcmSendResult.PermanentTokenError]; everything
 * else (`INVALID_ARGUMENT`, `QUOTA_EXCEEDED`, `UNAVAILABLE`, `INTERNAL`,
 * non-FCM transport errors) maps to [FcmSendResult.TransientError].
 */
fun interface FcmSender {
    fun send(message: Message): FcmSendResult

    companion object {
        @JvmStatic
        fun fromFirebaseMessaging(messaging: FirebaseMessaging): FcmSender =
            FcmSender { msg ->
                try {
                    FcmSendResult.Sent(messaging.send(msg))
                } catch (e: FirebaseMessagingException) {
                    val code = e.messagingErrorCode
                    when (code) {
                        MessagingErrorCode.UNREGISTERED, MessagingErrorCode.SENDER_ID_MISMATCH ->
                            FcmSendResult.PermanentTokenError(code)
                        else ->
                            FcmSendResult.TransientError(
                                code = code?.toString() ?: e.errorCode?.toString() ?: "unknown",
                                cause = e,
                            )
                    }
                } catch (t: Throwable) {
                    FcmSendResult.TransientError(code = "unknown", cause = t)
                }
            }
    }
}

/**
 * Outcome of an [FcmSender.send] call. Models the three buckets the
 * dispatcher cares about per the spec — sent, permanent-token-error
 * (DELETE-eligible), transient (no DELETE).
 */
sealed interface FcmSendResult {
    data class Sent(val messageId: String) : FcmSendResult

    data class PermanentTokenError(val code: MessagingErrorCode) : FcmSendResult

    data class TransientError(val code: String, val cause: Throwable? = null) : FcmSendResult
}
