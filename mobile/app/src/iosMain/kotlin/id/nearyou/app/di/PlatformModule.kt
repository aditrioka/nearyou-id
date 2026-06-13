package id.nearyou.app.di

import id.nearyou.app.auth.GoogleSignInClient
import id.nearyou.app.auth.GoogleSignInGateway
import id.nearyou.app.auth.SecureTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.location.IosLocationPermissionController
import id.nearyou.app.location.IosLocationProvider
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.push.FcmTokenProvider
import id.nearyou.app.push.IosFcmTokenProvider
import id.nearyou.app.timeline.LocationProvider
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val platformModule: Module =
    module {
        single<TokenStore> { SecureTokenStore() }
        single<GoogleSignInGateway> { GoogleSignInClient() }
        // mobile-location-permission-flow — the real CLLocationManager-backed provider replaces the
        // StubLocationProvider as the production binding; the controller drives when-in-use authorization.
        // mobile-location-acquisition-tuning — bound behind a qualifier; the unqualified LocationProvider
        // consumers inject is the CachingLocationProvider decorator (mobileModule) wrapping this.
        single<LocationProvider>(named("deviceLocation")) { IosLocationProvider() }
        single<LocationPermissionController> { IosLocationPermissionController() }
        // mobile-fcm-token-registration — the iOS FCM token provider (Firebase Messaging Pod). The
        // vendor SDK import is confined to this actual (FcmPushSourceGuardTest enforces the boundary).
        single<FcmTokenProvider> { IosFcmTokenProvider() }
    }
