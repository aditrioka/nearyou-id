package id.nearyou.app.push

import kotlinx.coroutines.flow.Flow

/**
 * The commonMain seam for FCM push-token acquisition (`mobile-fcm-token-registration`). Compose-free
 * and **platform-free** by contract: this interface references NO Firebase / platform-notification API
 * (`FirebaseMessaging`, `Messaging`, `UNUserNotificationCenter`, `FirebaseMessagingService`) — the
 * vendor SDK lives ONLY in the Android / iOS actuals (the "no vendor SDK import outside the platform /
 * `:infra:*` boundary" invariant). Production binds a platform actual in each `platformModule`; a
 * `FakeFcmTokenProvider` drives tests (mirrors the `LocationProvider` / `GoogleSignInGateway`
 * testability idiom).
 *
 * The mobile-module Firebase-confinement of this interface is test-enforced by `FcmPushSourceGuardTest`
 * — the global `VendorSdkLeakageScanTest` (`:lint:detekt-rules`) scans only the `core` modules + `backend/ktor`,
 * NOT `mobile/app`, so it does not cover this boundary.
 */
interface FcmTokenProvider {
    /**
     * Acquire the current device FCM registration token, or `null` when none is available — the token
     * is not yet issued, or a platform prerequisite is unmet (iOS notification authorization denied; no
     * configured `FirebaseApp` because the operator Firebase client config is absent). A `null` return is
     * a normal, non-exceptional outcome the registrar short-circuits on (`NoTokenAvailable`).
     */
    suspend fun currentToken(): String?

    /**
     * Emits the new token whenever the platform SDK rotates it (Android `FirebaseMessagingService.onNewToken`;
     * iOS the messaging-delegate registration-token callback). Collected for the authenticated surface's
     * lifetime so a rotation re-registers.
     */
    val tokenRefreshes: Flow<String>
}
