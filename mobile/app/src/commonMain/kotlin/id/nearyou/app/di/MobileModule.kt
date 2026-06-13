package id.nearyou.app.di

import id.nearyou.app.auth.AuthApiClient
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.AuthRepository
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenRefresher
import id.nearyou.app.config.apiBaseUrl
import id.nearyou.app.config.appVersionName
import id.nearyou.app.config.devicePlatform
import id.nearyou.app.config.httpClientEngine
import id.nearyou.app.config.isDebugBuild
import id.nearyou.app.consent.ConsentApiClient
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentRepository
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.diagnostics.ConsoleDiagnosticSink
import id.nearyou.app.diagnostics.DiagnosticSink
import id.nearyou.app.location.CachingLocationProvider
import id.nearyou.app.location.LocationTuning
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.notifications.NotificationsApiClient
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsRepository
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.CreatePostRepository
import id.nearyou.app.post.LikeApiClient
import id.nearyou.app.post.PostCreationApiClient
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostDetailRepository
import id.nearyou.app.post.ReplyApiClient
import id.nearyou.app.push.FcmTokenApiClient
import id.nearyou.app.push.FcmTokenRegistrar
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.app.screens.routing.ProactiveTokenRefreshTrigger
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
        // mobile-session-expiry-and-proactive-refresh (D6) — the real coordinate-free diagnostic sink
        // the timeline repositories wire (replacing their no-op default), so nearby/global network +
        // 400 diagnostics are observable. Debug-only console output; no-op in release.
        single<DiagnosticSink> { ConsoleDiagnosticSink(isDebugBuild) }
        // mobile-session-expiry-and-proactive-refresh (D2) — the single-flight refresh round-trip,
        // shared by the bearer plugin's refreshTokens callback AND the proactive on-resume trigger.
        // Exactly ONE instance so an overlapping proactive + reactive refresh performs one POST.
        single { TokenRefresher(get(), get()) }
        single {
            HttpClientFactory.create(
                apiBaseUrl = apiBaseUrl,
                tokenStore = get(),
                sessionInvalidator = get(),
                engine = httpClientEngine(),
                installLogging = isDebugBuild,
                tokenRefresher = get(),
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

        // mobile-session-expiry-and-proactive-refresh (D3) — the app-root ON_RESUME preemptive-refresh
        // trigger. Holds the shared TokenRefresher single + the shared HttpClient (no cycle —
        // TokenRefresher takes the client per call). ProactiveRefreshEffect injects this at the app root.
        single { ProactiveTokenRefreshTrigger(tokenStore = get(), tokenRefresher = get(), httpClient = get()) }

        // mobile-session-expiry-and-proactive-refresh (D5) — the in-memory holder for the destination to
        // return to after an involuntary re-auth + the involuntary-entry flag SignInScreen reads to show
        // the session-expired notice. Mirrors PendingSignupIdentity: a single, never persisted, never on
        // a NavKey.
        single { PendingReturnDestination() }

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
        // diagnosticLog wired to the real coordinate-free DiagnosticSink (D6), NOT the no-op default —
        // the sink call sites pass only status/cause-message strings (no coordinate, no token).
        single { NearbyTimelineRepository(get(), get(), get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<NearbyTimelineFlow> { get<NearbyTimelineRepository>() }

        // mobile-global-timeline — the Global feed graph (mobile-home-tab-host Global tab). Mirrors the
        // Nearby seam, minus the LocationProvider (Global has no spatial filter). REUSES the existing
        // SessionIdProvider single above (the X-Session-Id soft-cap bucket is shared across feeds — do
        // NOT register a second). GlobalTimelineFlow is bound to the concrete repository so a
        // FakeGlobalTimelineFlow can drive the screen tests.
        single { GlobalTimelineApiClient(get()) }
        // diagnosticLog wired to the real coordinate-free DiagnosticSink (D6), mirroring Nearby.
        single { GlobalTimelineRepository(get(), get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<GlobalTimelineFlow> { get<GlobalTimelineRepository>() }

        // mobile-bottom-nav-sections-and-notifications — the notifications graph (the Notifikasi section's
        // NotificationsScreen + the shell's unread badge). Mirrors the Global seam: ApiClient → Repository
        // bound behind the NotificationsFlow seam (so a FakeNotificationsFlow drives the screen/shell
        // tests; the concrete stays resolvable). Reuses the shared HttpClient (Bearer attached by the Auth
        // plugin); no X-Session-Id / SessionIdProvider (the notifications routes carry no per-session
        // soft-cap accounting). No new HttpClient.
        single { NotificationsApiClient(get()) }
        // diagnosticLog wired to the real sink — the repo's status-only diagnostic strings went
        // nowhere via the no-op default (2026-06-10 audit, 06 medium: sink-wiring drift).
        single { NotificationsRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<NotificationsFlow> { get<NotificationsRepository>() }

        // mobile-post-creation-screen — the create-post graph. Reuses the shared HttpClient, the
        // unqualified LocationProvider (the CachingLocationProvider decorator above, shared with
        // Nearby), and the platform-bound LocationPermissionController (supplied by each platformModule
        // via mobile-location-permission-flow) — NO new permission binding is introduced.
        // CreatePostRepository is bound behind the CreatePostFlow seam so a FakeCreatePostFlow can drive
        // the screen tests (the concrete stays resolvable).
        single { PostCreationApiClient(get()) }
        // The (status, errorCode) sink shape is adapted onto the shared string DiagnosticSink
        // (2026-06-10 audit, 06 medium). Coordinate-free by construction: an Int + a server
        // error-code enum.
        single {
            val sink = get<DiagnosticSink>()
            CreatePostRepository(
                get(),
                get(),
                get(),
                diagnosticLog = { status, errorCode -> sink.log("create_post_error: status=$status code=$errorCode") },
            )
        }
        single<CreatePostFlow> { get<CreatePostRepository>() }

        // mobile-analytics-consent-screen — the consent-submit graph. Reuses the shared
        // (bearer-authed) HttpClient; ConsentRepository is bound behind the ConsentFlow seam so a
        // FakeConsentFlow can drive the screen tests (the concrete stays resolvable).
        single { ConsentApiClient(get()) }
        // diagnosticLog wired to the real sink (2026-06-10 audit, 06 medium: sink-wiring drift).
        single { ConsentRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<ConsentFlow> { get<ConsentRepository>() }

        // mobile-post-detail-screen — the post-detail graph (like toggle + replies + reply composer).
        // Reuses the shared HttpClient (NO new client, NO X-Session-Id — the like/reply endpoints are not
        // session-soft-capped, unlike the timeline reads). PostDetailRepository is a stateless singleton
        // (every method takes the postId) bound behind the PostDetailFlow seam so a FakePostDetailFlow can
        // drive the screen tests; the concrete stays resolvable.
        single { LikeApiClient(get()) }
        single { ReplyApiClient(get()) }
        // Same (status, errorCode) → string-sink adapter as CreatePostRepository above.
        single {
            val sink = get<DiagnosticSink>()
            PostDetailRepository(
                get(),
                get(),
                diagnosticLog = { status, errorCode -> sink.log("post_detail_error: status=$status code=$errorCode") },
            )
        }
        single<PostDetailFlow> { get<PostDetailRepository>() }
        // mobile-inline-post-actions (D1) — the extracted cross-surface like seam: the SAME
        // PostDetailRepository singleton, additionally bound as LikeFlow so the timeline ViewModels'
        // inline like depends on exactly the like surface (no second like client/repository, no
        // duplicate status→LikeOutcome mapping).
        single<LikeFlow> { get<PostDetailRepository>() }

        // mobile-fcm-token-registration — the push-token registration graph. Reuses the shared
        // (bearer-authed) HttpClient (NO new client). The FcmTokenProvider platform actual is bound in
        // each platformModule (AndroidFcmTokenProvider / IosFcmTokenProvider). The platform constant +
        // app_version come from the config seam. diagnosticLog wired to the real coordinate-free sink —
        // it receives ONLY platform + outcome, NEVER the token (a device-addressed credential; backend D11).
        single { FcmTokenApiClient(get(), platform = devicePlatform, appVersion = appVersionName) }
        single {
            FcmTokenRegistrar(
                provider = get(),
                apiClient = get(),
                platform = devicePlatform,
                diagnosticLog = get<DiagnosticSink>()::log,
            )
        }
    }

/**
 * Platform-specific Koin module. Android binds `SecureTokenStore` (via `androidContext()`),
 * the `CurrentActivityHolder`, and `GoogleSignInGateway` (Credential Manager). iOS binds the
 * Keychain `SecureTokenStore` + the GoogleSignIn-SDK `GoogleSignInGateway`.
 */
expect val platformModule: org.koin.core.module.Module
