package id.nearyou.app.push

import cocoapods.FirebaseMessaging.FIRMessaging
import cocoapods.FirebaseMessaging.FIRMessagingDelegateProtocol
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * iOS [FcmTokenProvider] actual (`mobile-fcm-token-registration`). The Firebase Messaging Pod import lives
 * ONLY here (the platform-boundary invariant; enforced by `FcmPushSourceGuardTest`).
 *
 * iOS couples token retrieval to APNs: `FIRMessaging.token` resolves only after the app registers for remote
 * notifications, which requires notification authorization. So [currentToken] requests the **minimal**
 * authorization token acquisition structurally needs (`requestAuthorizationWithOptions` +
 * `registerForRemoteNotifications`) — NOT the product-level contextual permission UX (that is the consuming
 * chat surface's concern). On **denied** authorization (or no configured `FirebaseApp` because the operator
 * `GoogleService-Info.plist` is absent) it returns `null` — no token, no crash (the registrar maps `null` to
 * `NoTokenAvailable`). The raw token is never logged.
 *
 * NOTE: the iOS target builds only on a Mac (CocoaPods + Xcode); it is not compiled in the Android cloud
 * sandbox. The exact cinterop symbol shapes (`FIRMessaging`, the delegate protocol) are pinned to the
 * `FirebaseMessaging` Pod's generated bindings.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFcmTokenProvider : FcmTokenProvider {
    override suspend fun currentToken(): String? =
        withContext(Dispatchers.Main) {
            if (!requestAuthorizationAndRegister()) return@withContext null
            try {
                suspendCancellableCoroutine { cont ->
                    FIRMessaging.messaging().tokenWithCompletion { token, error ->
                        if (cont.isActive) cont.resume(if (error != null) null else token)
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                // No configured FirebaseApp / SDK unavailable — no token, not a crash.
                null
            }
        }

    override val tokenRefreshes: Flow<String> = registrationTokenRefreshes

    /**
     * Requests the token-acquisition-minimum notification authorization and registers for remote
     * notifications. Returns `true` when authorization was granted (a token can be obtained), `false` on
     * denial. The contextual product-level prompt UX is deferred to the consuming chat surface.
     */
    private suspend fun requestAuthorizationAndRegister(): Boolean {
        val granted =
            try {
                suspendCancellableCoroutine { cont ->
                    val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
                    UNUserNotificationCenter.currentNotificationCenter()
                        .requestAuthorizationWithOptions(options) { granted, _ ->
                            if (cont.isActive) cont.resume(granted)
                        }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                false
            }
        if (granted) {
            // APNs registration must run on the main thread; currentToken() already confines to Main.
            UIApplication.sharedApplication.registerForRemoteNotifications()
            FIRMessaging.messaging().setDelegate(sharedMessagingDelegate)
        }
        return granted
    }

    private companion object {
        // Bridges the FCM SDK's registration-token rotation callback into [tokenRefreshes]. replay = 0
        // (a refresh while no collector is active is dropped — the next session-active registerCurrentToken()
        // picks up the current token); a small extra buffer absorbs a burst.
        private val tokenRefreshFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
        val registrationTokenRefreshes: Flow<String> = tokenRefreshFlow.asSharedFlow()

        // Retained for the process lifetime (FIRMessaging holds its delegate weakly). Emits the rotated
        // token (never logs it) into the bridge Flow.
        val sharedMessagingDelegate: FIRMessagingDelegateProtocol =
            object : NSObject(), FIRMessagingDelegateProtocol {
                override fun messaging(
                    messaging: FIRMessaging,
                    didReceiveRegistrationToken: String?,
                ) {
                    didReceiveRegistrationToken?.let { tokenRefreshFlow.tryEmit(it) }
                }
            }
    }
}
