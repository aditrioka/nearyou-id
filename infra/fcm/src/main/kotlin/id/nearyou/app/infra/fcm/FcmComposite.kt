package id.nearyou.app.infra.fcm

import id.nearyou.data.repository.ActorUsernameLookup
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationRepository
import id.nearyou.data.repository.UserFcmTokenReader

/**
 * Production composite-dispatcher factory for `:backend:ktor`. Hides all
 * `com.google.firebase.*` types behind a non-Firebase return shape so the
 * caller never has to import the Admin SDK — preserves the
 * `openspec/changes/fcm-push-dispatch/design.md` D16 boundary, which admits
 * that the Koin DI module *may* reference `FirebaseMessaging` directly but
 * encourages narrower coupling when feasible. This factory is the narrower
 * coupling: the only Firebase type that crosses the boundary is held inside
 * the returned [FcmComposite].
 *
 * Initializes `FirebaseApp` named `"nearyou-default"` exactly once via
 * [FirebaseAdminInit] (throws [FcmInitException] on failure — caller
 * fail-fasts), then assembles `FcmDispatcher` + `FcmAndInAppDispatcher` over
 * the supplied collaborator interfaces.
 */
data class FcmComposite(
    val dispatcher: NotificationDispatcher,
    val scope: FcmDispatcherScope,
    val onShutdown: () -> Unit,
)

fun buildFcmComposite(
    serviceAccountJson: String,
    notificationRepository: NotificationRepository,
    userFcmTokenReader: UserFcmTokenReader,
    actorUsernameLookup: ActorUsernameLookup,
    inAppDispatcher: NotificationDispatcher,
    parallelism: Int = 8,
    errorCounter: (delegate: String) -> Unit = FcmAndInAppDispatcher::incrementInProcess,
    drainMillis: Long = 5_000L,
): FcmComposite {
    FirebaseAdminInit.initialize(serviceAccountJson)
    val firebaseMessaging = FirebaseAdminInit.messaging()
    val scope = FcmDispatcherScope.production(parallelism = parallelism)
    val fcmDispatcher =
        FcmDispatcher(
            notificationRepository = notificationRepository,
            userFcmTokenReader = userFcmTokenReader,
            sender = FcmSender.fromFirebaseMessaging(firebaseMessaging),
            actorUsernameLookup = actorUsernameLookup,
            dispatcherScope = scope.scope,
        )
    val composite =
        FcmAndInAppDispatcher(
            fcm = fcmDispatcher,
            inApp = inAppDispatcher,
            errorCounter = errorCounter,
        )
    val onShutdown = {
        fcmDispatcher.markShutdown()
        scope.shutdown(drainMillis = drainMillis)
    }
    return FcmComposite(dispatcher = composite, scope = scope, onShutdown = onShutdown)
}
