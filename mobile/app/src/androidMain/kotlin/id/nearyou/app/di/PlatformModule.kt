package id.nearyou.app.di

import id.nearyou.app.BuildConfig
import id.nearyou.app.auth.CurrentActivityHolder
import id.nearyou.app.auth.GoogleSignInClient
import id.nearyou.app.auth.GoogleSignInGateway
import id.nearyou.app.auth.SecureTokenStore
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.data.consent.DurableConsentSnapshotStore
import id.nearyou.app.image.AndroidImagePicker
import id.nearyou.app.image.ImagePicker
import id.nearyou.app.image.ImagePickerRequestBridge
import id.nearyou.app.infra.admob.AdProvider
import id.nearyou.app.infra.admob.AdsActivityProvider
import id.nearyou.app.infra.admob.AndroidAdProvider
import id.nearyou.app.location.AndroidLocationPermissionController
import id.nearyou.app.location.AndroidLocationProvider
import id.nearyou.app.location.LocationPermissionController
import id.nearyou.app.location.LocationPermissionRequestBridge
import id.nearyou.app.notifications.AndroidNotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionController
import id.nearyou.app.notifications.NotificationPermissionRequestBridge
import id.nearyou.app.push.AndroidFcmTokenProvider
import id.nearyou.app.push.AndroidNotificationContentPreferenceStore
import id.nearyou.app.push.FcmTokenProvider
import id.nearyou.app.push.NotificationContentPreference
import id.nearyou.app.push.PersistedNotificationContentPreference
import id.nearyou.app.timeline.LocationProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val platformModule: Module =
    module {
        single<TokenStore> { SecureTokenStore(androidContext()) }
        // mobile-amplitude-analytics — the durable consent snapshot (resolves #198). SharedPreferences
        // (non-secret consent flags; synchronous to match the ConsentSnapshotStore interface). Read by
        // the analytics-consent gate, the Sentry crash gate, and Settings seeding.
        single<ConsentSnapshotStore> { DurableConsentSnapshotStore(androidContext()) }
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
        // mobile-fcm-token-registration — the Android FCM token provider (Firebase Messaging SDK). The
        // vendor SDK import is confined to this actual (FcmPushSourceGuardTest enforces the boundary).
        single<FcmTokenProvider> { AndroidFcmTokenProvider() }
        // mobile-push-message-handling — the content-privacy preference (default OFF); DataStore-backed.
        single<NotificationContentPreference> {
            PersistedNotificationContentPreference(AndroidNotificationContentPreferenceStore(androidContext()))
        }
        // image-attached-posts (Phase 4) — the Android Photo Picker (PickVisualMedia) actual + its
        // Activity-result bridge (launcher set by MainActivity, mirroring the location/notification
        // permission bridges). The picker requires NO storage permission; the actual re-encodes the
        // picked image to ≤5 MB JPEG (AndroidImageCompressor).
        single { ImagePickerRequestBridge() }
        single<ImagePicker> { AndroidImagePicker(androidContext(), get()) }
        // mobile-admob-ads-foundation — the Android Google Mobile Ads + UMP AdProvider actual (vendor SDKs
        // fenced in :infra:admob, invariant #16). The UMP consent form needs a foreground Activity, supplied
        // via the existing CurrentActivityHolder (set by MainActivity onResume/onPause).
        single<AdProvider> {
            AndroidAdProvider(
                context = androidContext(),
                activityProvider = AdsActivityProvider { get<CurrentActivityHolder>().activity },
            )
        }
    }
