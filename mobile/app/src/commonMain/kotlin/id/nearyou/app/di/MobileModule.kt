package id.nearyou.app.di

import id.nearyou.app.auth.AuthApiClient
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.AuthRepository
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.config.apiBaseUrl
import id.nearyou.app.config.httpClientEngine
import id.nearyou.app.config.isDebugBuild
import id.nearyou.app.consent.ConsentApiClient
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentRepository
import id.nearyou.app.location.CachingLocationProvider
import id.nearyou.app.location.LocationTuning
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.notifications.NotificationsApiClient
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsRepository
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.CreatePostRepository
import id.nearyou.app.post.PostCreationApiClient
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.app.timeline.GlobalTimelineApiClient
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineRepository
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.app.timeline.NearbyTimelineApiClient
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineRepository
import id.nearyou.app.timeline.SessionIdProvider
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.TimeSource

/**
 * Cross-platform Koin bindings for `:mobile:app`. Platform-specific bindings
 * (`SecureTokenStore`, `GoogleSignInGateway`, the Android `CurrentActivityHolder`) live in
 * [platformModule]; both are loaded in [initKoin].
 *
 * Mobile #3 registers: `SessionInvalidator`, the shared `HttpClient`, and `AuthApiClient`.
 * `AuthRepository` is registered here in §6.
 */
val mobileModule =
    module {
        single { SessionInvalidator(get()) }
        single {
            HttpClientFactory.create(
                apiBaseUrl = apiBaseUrl,
                tokenStore = get(),
                sessionInvalidator = get(),
                engine = httpClientEngine(),
                installLogging = isDebugBuild,
            )
        }
        single { AuthApiClient(get()) }
        single {
            AuthRepository(
                googleSignIn = get(),
                authApiClient = get(),
                tokenStore = get(),
                sessionInvalidator = get(),
            )
        }
        // Bind the AuthFlow interface to the concrete AuthRepository so screens depend on the
        // testable seam while the concrete remains resolvable.
        single<AuthFlow> { get<AuthRepository>() }

        // mobile-nav-swap-to-navigation3 — the in-memory holder for the verified Google id_token
        // carried from the sign-in no-account path (SignInScreen sets it) to the age-gate signup
        // flow (AgeGateScreen reads it). A single so both screens share one instance; never
        // persisted, never on a NavKey (design Decision 4).
        single { PendingSignupIdentity() }

        // mobile-location-acquisition-tuning — the unqualified LocationProvider both Nearby and the
        // composer inject is the in-process warm-fix decorator (single-flighted, injected monotonic
        // clock) wrapping the real platform provider bound behind named("deviceLocation") in each
        // platformModule. One shared warm fix across screens; the consumers' injection is unchanged.
        single<LocationProvider> {
            CachingLocationProvider(
                delegate = get(named("deviceLocation")),
                timeSource = TimeSource.Monotonic,
                stalenessWindow = LocationTuning.inProcessWarmStaleness,
            )
        }

        // mobile-nearby-timeline-screen — the Nearby timeline graph. SessionIdProvider is a
        // single so its captured session id is stable per process (per-session soft-cap bucket).
        // The unqualified LocationProvider is bound just above (the mobile-location-acquisition-tuning
        // CachingLocationProvider decorator over the platformModule's named("deviceLocation") real
        // provider); StubLocationProvider is retained in commonMain (id.nearyou.app.timeline) as the
        // test double. The NearbyTimelineFlow testable seam stays here (bound to the concrete repository
        // so FakeNearbyTimelineFlow can drive screen tests); Koin resolves the decorator into the
        // repository.
        single { NearbyTimelineApiClient(get()) }
        single { SessionIdProvider() }
        single { NearbyTimelineRepository(get(), get(), get()) }
        single<NearbyTimelineFlow> { get<NearbyTimelineRepository>() }

        // mobile-global-timeline — the Global feed graph (mobile-home-tab-host Global tab). Mirrors the
        // Nearby seam, minus the LocationProvider (Global has no spatial filter). REUSES the existing
        // SessionIdProvider single above (the X-Session-Id soft-cap bucket is shared across feeds — do
        // NOT register a second). GlobalTimelineFlow is bound to the concrete repository so a
        // FakeGlobalTimelineFlow can drive the screen tests.
        single { GlobalTimelineApiClient(get()) }
        single { GlobalTimelineRepository(get(), get()) }
        single<GlobalTimelineFlow> { get<GlobalTimelineRepository>() }

        // mobile-bottom-nav-sections-and-notifications — the notifications graph (the Notifikasi section's
        // NotificationsScreen + the shell's unread badge). Mirrors the Global seam: ApiClient → Repository
        // bound behind the NotificationsFlow seam (so a FakeNotificationsFlow drives the screen/shell
        // tests; the concrete stays resolvable). Reuses the shared HttpClient (Bearer attached by the Auth
        // plugin); no X-Session-Id / SessionIdProvider (the notifications routes carry no per-session
        // soft-cap accounting). No new HttpClient.
        single { NotificationsApiClient(get()) }
        single { NotificationsRepository(get()) }
        single<NotificationsFlow> { get<NotificationsRepository>() }

        // mobile-post-creation-screen — the create-post graph. Reuses the shared HttpClient, the
        // unqualified LocationProvider (the CachingLocationProvider decorator above, shared with
        // Nearby), and the platform-bound LocationPermissionController (supplied by each platformModule
        // via mobile-location-permission-flow) — NO new permission binding is introduced.
        // CreatePostRepository is bound behind the CreatePostFlow seam so a FakeCreatePostFlow can drive
        // the screen tests (the concrete stays resolvable).
        single { PostCreationApiClient(get()) }
        single { CreatePostRepository(get(), get(), get()) }
        single<CreatePostFlow> { get<CreatePostRepository>() }

        // mobile-analytics-consent-screen — the consent-submit graph. Reuses the shared
        // (bearer-authed) HttpClient; ConsentRepository is bound behind the ConsentFlow seam so a
        // FakeConsentFlow can drive the screen tests (the concrete stays resolvable).
        single { ConsentApiClient(get()) }
        single { ConsentRepository(get()) }
        single<ConsentFlow> { get<ConsentRepository>() }
    }

/**
 * Platform-specific Koin module. Android binds `SecureTokenStore` (via `androidContext()`),
 * the `CurrentActivityHolder`, and `GoogleSignInGateway` (Credential Manager). iOS binds the
 * Keychain `SecureTokenStore` + the GoogleSignIn-SDK `GoogleSignInGateway`.
 */
expect val platformModule: org.koin.core.module.Module
