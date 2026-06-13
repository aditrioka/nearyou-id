package id.nearyou.app.di

import id.nearyou.app.BuildConfig
import id.nearyou.app.auth.CurrentActivityHolder
import id.nearyou.app.auth.GoogleSignInClient
import id.nearyou.app.auth.GoogleSignInGateway
import id.nearyou.app.auth.SecureTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.location.AndroidLocationPermissionController
import id.nearyou.app.location.AndroidLocationProvider
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionRequestBridge
import id.nearyou.app.notifications.AndroidNotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionRequestBridge
import id.nearyou.app.timeline.LocationProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val platformModule: Module =
    module {
        single<TokenStore> { SecureTokenStore(androidContext()) }
        single { CurrentActivityHolder() }
        single<GoogleSignInGateway> {
            GoogleSignInClient(
                activityHolder = get(),
                serverClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
            )
        }
        // mobile-location-permission-flow — the real device-location provider replaces the
        // StubLocationProvider as the production binding; the controller drives the runtime
        // ACCESS_COARSE_LOCATION request through the Activity-result bridge (set by MainActivity).
        single { LocationPermissionRequestBridge() }
        // mobile-location-acquisition-tuning — the real provider binds behind a qualifier; the
        // unqualified LocationProvider consumers inject is the CachingLocationProvider decorator
        // (mobileModule), which wraps this. Two LocationProvider bindings would clash without it.
        single<LocationProvider>(named("deviceLocation")) { AndroidLocationProvider(androidContext()) }
        single<LocationPermissionController> { AndroidLocationPermissionController(androidContext(), get()) }
        // mobile-chat-screen (task 9.4) — the POST_NOTIFICATIONS request bridge (launcher set by
        // MainActivity) + the controller the chat first-send flow drives.
        single { NotificationPermissionRequestBridge() }
        single<NotificationPermissionController> { AndroidNotificationPermissionController(androidContext(), get()) }
    }
