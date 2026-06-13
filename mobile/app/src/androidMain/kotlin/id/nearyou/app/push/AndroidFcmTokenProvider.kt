package id.nearyou.app.push

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Android [FcmTokenProvider] actual (`mobile-fcm-token-registration`). The Firebase SDK import lives ONLY
 * here (the platform-boundary invariant; enforced by `FcmPushSourceGuardTest`). Android needs NO
 * `POST_NOTIFICATIONS` runtime permission to obtain a token — the permission gates notification *display*,
 * not token issuance — so this actual requests none (the contextual prompt is the consuming feature's
 * concern).
 *
 * Defensive against the absent operator Firebase config: without a configured `FirebaseApp`
 * (`google-services.json` + the `google-services` plugin, OR a manual init — neither shipped by this change),
 * `FirebaseMessaging.getInstance()` throws — [currentToken] catches it and returns `null` so the app runs
 * without the config (the registrar maps `null` to `NoTokenAvailable`). The raw token is never logged.
 */
class AndroidFcmTokenProvider : FcmTokenProvider {
    override suspend fun currentToken(): String? =
        try {
            suspendCancellableCoroutine { cont ->
                // A Task completes as success XOR failure exactly once; the cont.isActive guards mirror the
                // iOS actual and are belt-and-suspenders against a resume after cancellation.
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token -> if (cont.isActive) cont.resume(token) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            // No configured FirebaseApp / SDK unavailable — no token, not a crash.
            null
        }

    override val tokenRefreshes: Flow<String> = NearYouFirebaseMessagingService.tokenRefreshes
}

/**
 * Bridges the FCM SDK's token-rotation callback into the commonMain [FcmTokenProvider.tokenRefreshes] Flow.
 * Registered in the Android manifest. When no `FirebaseApp` is configured the SDK never invokes this, so the
 * Flow simply emits nothing (consistent with [AndroidFcmTokenProvider.currentToken] returning `null`). The
 * raw token is never logged — it is only emitted to the in-process bridge Flow.
 */
class NearYouFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        tokenRefreshFlow.tryEmit(token)
    }

    companion object {
        // replay = 0 (a refresh while no collector is active is dropped — the next session-active
        // registerCurrentToken() picks up the current token); a small extra buffer absorbs a burst.
        private val tokenRefreshFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
        val tokenRefreshes: Flow<String> = tokenRefreshFlow.asSharedFlow()
    }
}
