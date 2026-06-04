package id.nearyou.app.di

import id.nearyou.app.auth.AuthApiClient
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.AuthRepository
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.config.apiBaseUrl
import id.nearyou.app.config.httpClientEngine
import id.nearyou.app.config.isDebugBuild
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.CreatePostRepository
import id.nearyou.app.post.PostCreationApiClient
import id.nearyou.app.timeline.NearbyTimelineApiClient
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineRepository
import id.nearyou.app.timeline.SessionIdProvider
import org.koin.dsl.module

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

        // mobile-nearby-timeline-screen — the Nearby timeline graph. SessionIdProvider is a
        // single so its captured session id is stable per process (per-session soft-cap bucket).
        // The LocationProvider binding is NOT here: mobile-location-permission-flow moved it to each
        // platformModule (the real fused / CLLocationManager provider); StubLocationProvider is
        // retained in commonMain (id.nearyou.app.timeline) as the test double. The NearbyTimelineFlow
        // testable seam stays here (bound to the concrete repository so FakeNearbyTimelineFlow can
        // drive screen tests); Koin resolves the platformModule's LocationProvider into the repository.
        single { NearbyTimelineApiClient(get()) }
        single { SessionIdProvider() }
        single { NearbyTimelineRepository(get(), get(), get()) }
        single<NearbyTimelineFlow> { get<NearbyTimelineRepository>() }

        // mobile-post-creation-screen — the create-post graph. Reuses the shared HttpClient, the
        // platform-bound LocationProvider, and the platform-bound LocationPermissionController (both
        // supplied by each platformModule via mobile-location-permission-flow) — NO new location /
        // permission binding is introduced. CreatePostRepository is bound behind the CreatePostFlow
        // seam so a FakeCreatePostFlow can drive the screen tests (the concrete stays resolvable).
        single { PostCreationApiClient(get()) }
        single { CreatePostRepository(get(), get(), get()) }
        single<CreatePostFlow> { get<CreatePostRepository>() }
    }

/**
 * Platform-specific Koin module. Android binds `SecureTokenStore` (via `androidContext()`),
 * the `CurrentActivityHolder`, and `GoogleSignInGateway` (Credential Manager). iOS binds the
 * Keychain `SecureTokenStore` + the GoogleSignIn-SDK `GoogleSignInGateway`.
 */
expect val platformModule: org.koin.core.module.Module
