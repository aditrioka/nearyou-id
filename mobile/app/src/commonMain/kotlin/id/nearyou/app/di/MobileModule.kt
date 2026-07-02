package id.nearyou.app.di

import id.nearyou.app.analytics.ConsentGatedAnalyticsTracker
import id.nearyou.app.appeal.AppealApiClient
import id.nearyou.app.appeal.AppealFlow
import id.nearyou.app.appeal.AppealRepository
import id.nearyou.app.appeal.AppealSession
import id.nearyou.app.auth.AuthApiClient
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.AuthRepository
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.auth.TokenRefresher
import id.nearyou.app.auth.TokenStoreSelfUserIdProvider
import id.nearyou.app.chat.ChatFlow
import id.nearyou.app.chat.ChatMessagesApiClient
import id.nearyou.app.chat.ChatRepository
import id.nearyou.app.chat.ConversationsApiClient
import id.nearyou.app.chat.ConversationsFlow
import id.nearyou.app.chat.ConversationsRepository
import id.nearyou.app.chat.RealtimeTokenApiClient
import id.nearyou.app.chat.TokenViewerIdProvider
import id.nearyou.app.chat.ViewerIdProvider
import id.nearyou.app.config.amplitudeApiKey
import id.nearyou.app.config.apiBaseUrl
import id.nearyou.app.config.appVersionName
import id.nearyou.app.config.devicePlatform
import id.nearyou.app.config.httpClientEngine
import id.nearyou.app.config.isDebugBuild
import id.nearyou.app.config.sentryConfig
import id.nearyou.app.config.supabaseConfig
import id.nearyou.app.consent.ConsentApiClient
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.ConsentRepository
import id.nearyou.app.data.accountdeletion.AccountDeletionApiClient
import id.nearyou.app.data.accountdeletion.AccountDeletionFlow
import id.nearyou.app.data.accountdeletion.AccountDeletionRepository
import id.nearyou.app.data.block.BlockedUsersApiClient
import id.nearyou.app.data.block.BlockedUsersFlow
import id.nearyou.app.data.block.BlockedUsersRepository
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.ReportApiClient
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.diagnostics.CompositeDiagnosticSink
import id.nearyou.app.diagnostics.ConsoleDiagnosticSink
import id.nearyou.app.diagnostics.CrashReportingController
import id.nearyou.app.diagnostics.DiagnosticSink
import id.nearyou.app.diagnostics.SentryBreadcrumbDiagnosticSink
import id.nearyou.app.followlist.FollowListApiClient
import id.nearyou.app.followlist.FollowListFlow
import id.nearyou.app.followlist.FollowListRepository
import id.nearyou.app.hidedistance.DefaultHideDistanceRepository
import id.nearyou.app.hidedistance.HideDistanceApiClient
import id.nearyou.app.hidedistance.HideDistanceRepository
import id.nearyou.app.image.ImageUploadApiClient
import id.nearyou.app.image.ImageUploadRepository
import id.nearyou.app.image.ImageUploader
import id.nearyou.app.infra.amplitude.AmplitudeAnalyticsTracker
import id.nearyou.app.infra.amplitude.AmplitudeConfig
import id.nearyou.app.infra.amplitude.AnalyticsTracker
import id.nearyou.app.infra.amplitude.NoOpAnalyticsTracker
import id.nearyou.app.infra.revenuecat.PurchaseController
import id.nearyou.app.infra.revenuecat.RevenueCatPurchaseController
import id.nearyou.app.infra.sentry.CrashReporter
import id.nearyou.app.infra.sentry.CrashReporterConfig
import id.nearyou.app.infra.sentry.SentryCrashReporter
import id.nearyou.app.infra.supabaserealtime.ChatRealtimeSubscriber
import id.nearyou.app.infra.supabaserealtime.RealtimeTokenProvider
import id.nearyou.app.infra.supabaserealtime.SupabaseChatRealtimeSubscriber
import id.nearyou.app.location.CachingLocationProvider
import id.nearyou.app.location.LocationTuning
import id.nearyou.app.network.HttpClientFactory
import id.nearyou.app.notifications.NotificationPromptOneShot
import id.nearyou.app.notifications.NotificationsApiClient
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.notifications.NotificationsRepository
import id.nearyou.app.post.CreatePostFlow
import id.nearyou.app.post.CreatePostRepository
import id.nearyou.app.post.LikeApiClient
import id.nearyou.app.post.PostCreationApiClient
import id.nearyou.app.post.PostDetailFlow
import id.nearyou.app.post.PostDetailRepository
import id.nearyou.app.post.PostEditApiClient
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.post.PostEditRepository
import id.nearyou.app.post.ReplyApiClient
import id.nearyou.app.post.SinglePostApiClient
import id.nearyou.app.profile.ProfileApiClient
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileRepository
import id.nearyou.app.push.FcmTokenApiClient
import id.nearyou.app.push.FcmTokenRegistrar
import id.nearyou.app.push.PushTapNavSignal
import id.nearyou.app.screens.routing.PendingReturnDestination
import id.nearyou.app.screens.routing.PendingSignupIdentity
import id.nearyou.app.screens.routing.ProactiveTokenRefreshTrigger
import id.nearyou.app.search.SearchApiClient
import id.nearyou.app.search.SearchFlow
import id.nearyou.app.search.SearchRepository
import id.nearyou.app.timeline.FollowingTimelineApiClient
import id.nearyou.app.timeline.FollowingTimelineFlow
import id.nearyou.app.timeline.FollowingTimelineRepository
import id.nearyou.app.timeline.GlobalTimelineApiClient
import id.nearyou.app.timeline.GlobalTimelineFlow
import id.nearyou.app.timeline.GlobalTimelineRepository
import id.nearyou.app.timeline.LocationProvider
import id.nearyou.app.timeline.NearbyTimelineApiClient
import id.nearyou.app.timeline.NearbyTimelineFlow
import id.nearyou.app.timeline.NearbyTimelineRepository
import id.nearyou.app.timeline.SessionIdProvider
import id.nearyou.app.username.UsernameApiClient
import id.nearyou.app.username.UsernameFlow
import id.nearyou.app.username.UsernameRepository
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
        // mobile-crash-reporting — the Sentry-backed CrashReporter (single commonMain impl; the Sentry
        // SDK is fenced inside :infra:sentry). Init runs at process startup in initKoin#startCrashReporting
        // (opt-out default ON, consent-gated, blank-DSN no-op).
        single<CrashReporter> { SentryCrashReporter() }
        // mobile-amplitude-analytics — the consent-gated product-analytics tracker. The Amplitude HTTP V2
        // wrapper (or NoOpAnalyticsTracker when the ingestion key is blank — dev / un-provisioned) is
        // wrapped by the app-layer ConsentGatedAnalyticsTracker (design D3), which reads the durable
        // ConsentSnapshotStore per fire and silently suppresses when analytics consent is off.
        single<AnalyticsTracker> {
            ConsentGatedAnalyticsTracker(
                delegate =
                    if (amplitudeApiKey.isBlank()) {
                        NoOpAnalyticsTracker
                    } else {
                        AmplitudeAnalyticsTracker(
                            config = AmplitudeConfig(apiKey = amplitudeApiKey),
                            engine = httpClientEngine(),
                        )
                    },
                consentStore = get(),
            )
        }
        // mobile-paywall-screen — the RevenueCat-backed PurchaseController (single commonMain binding; the
        // purchases-kmp SDK is fenced inside :infra:revenuecat, invariant #16). Configured at startup via
        // configureRevenueCat; until then fetchOfferings fail-softs to Unavailable (the Unconfigured paywall).
        single<PurchaseController> { RevenueCatPurchaseController() }
        // mobile-crash-reporting — the start/stop lifecycle, shared by startup init (initKoin) and the
        // runtime consent toggle (ConsentSettingsViewModel). Config resolved from the flavor seam.
        single {
            CrashReportingController(
                crashReporter = get(),
                config =
                    CrashReporterConfig(
                        dsn = sentryConfig.dsn,
                        environment = sentryConfig.environment,
                        release = appVersionName ?: "unknown",
                    ),
            )
        }
        single { SessionInvalidator(get(), crashReporter = get()) }
        // mobile-session-expiry-and-proactive-refresh (D6) — the real coordinate-free diagnostic sink the
        // timeline repositories wire (replacing their no-op default), so nearby/global network + 400
        // diagnostics are observable. mobile-crash-reporting lands the reserved "until a Sentry/OTel sink
        // lands" seam: a composite fans each diagnostic to BOTH the debug console AND Sentry breadcrumbs —
        // no parallel diagnostics path.
        single<DiagnosticSink> {
            CompositeDiagnosticSink(
                listOf(
                    ConsoleDiagnosticSink(isDebugBuild),
                    SentryBreadcrumbDiagnosticSink(get()),
                ),
            )
        }
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
                crashReporter = get(),
                // content-moderation-appeal: the shared AppealSession holder (declared below) so a
                // banned 403's appeal token reaches the appeal screen.
                appealSession = get(),
                analytics = get(),
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

        // mobile-following-timeline-screen — the Following feed graph (mobile-home-tab-host Following
        // tab). Mirrors the Global seam EXACTLY (no LocationProvider — Following has no spatial filter).
        // REUSES the existing SessionIdProvider single above (the X-Session-Id soft-cap bucket is shared
        // across feeds — do NOT register a second). FollowingTimelineFlow is bound to the concrete
        // repository so a FakeFollowingTimelineFlow can drive the screen tests. diagnosticLog wired to
        // the real coordinate-free DiagnosticSink (status/type-only strings — Following coords live in
        // the response body, so the sink must never echo a body field).
        single { FollowingTimelineApiClient(get()) }
        single { FollowingTimelineRepository(get(), get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<FollowingTimelineFlow> { get<FollowingTimelineRepository>() }

        // mobile-bottom-nav-sections-and-notifications — the notifications graph (the Notifikasi section's
        // NotificationsScreen + the shell's unread badge). Mirrors the Global seam: ApiClient → Repository
        // bound behind the NotificationsFlow seam (so a FakeNotificationsFlow drives the screen/shell
        // tests; the concrete stays resolvable). Reuses the shared HttpClient (Bearer attached by the Auth
        // plugin); no X-Session-Id / SessionIdProvider (the notifications routes carry no per-session
        // soft-cap accounting). No new HttpClient.
        single { NotificationsApiClient(get()) }
        // diagnosticLog wired to the real sink — the repo's status-only diagnostic strings went
        // nowhere via the no-op default (2026-06-10 audit, 06 medium: sink-wiring drift).
        // SinglePostApiClient + ProfileApiClient (both bound below) back the deep-link target resolution
        // (mobile-notifications-deep-link-targets): post-target → full-projection single-post-read;
        // chat_message partner top-bar identity → user-profile-read (actor = the 1:1 sender = partner).
        single {
            NotificationsRepository(
                get(),
                get<SinglePostApiClient>(),
                get<ProfileApiClient>(),
                diagnosticLog = get<DiagnosticSink>()::log,
            )
        }
        single<NotificationsFlow> { get<NotificationsRepository>() }

        // mobile-push-message-handling — the consumed-once push-tap nav signal (docs/11 §2.2): the
        // platform tap handlers (MainActivity intent extras / the iOS notification delegate) offer the
        // raw routing fields; PushTapNavigationEffect resolves + navigates + consumes at the app root.
        single { PushTapNavSignal() }

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
            val selfId = get<SelfUserIdProvider>()
            CreatePostRepository(
                get(),
                get(),
                get(),
                diagnosticLog = { status, errorCode -> sink.log("create_post_error: status=$status code=$errorCode") },
                analytics = get(),
                selfUserId = { selfId.selfUserId() },
            )
        }
        single<CreatePostFlow> { get<CreatePostRepository>() }

        // mobile-post-editing — the edit-post graph. Reuses the shared HttpClient; PostEditApiClient (PATCH
        // + GET /edits) + SinglePostApiClient (the single-post-read freshness GET for the "Diedit" label +
        // isAuthor). PostEditRepository is bound behind the PostEditFlow seam so a FakePostEditFlow drives
        // the EditPostViewModel + post-detail screen tests (the concrete stays resolvable).
        single { PostEditApiClient(get()) }
        single { SinglePostApiClient(get()) }
        single {
            val sink = get<DiagnosticSink>()
            PostEditRepository(
                get(),
                get(),
                diagnosticLog = { status, errorCode -> sink.log("edit_post_error: status=$status code=$errorCode") },
            )
        }
        single<PostEditFlow> { get<PostEditRepository>() }

        // mobile-analytics-consent-screen — the consent-submit graph. Reuses the shared
        // (bearer-authed) HttpClient; ConsentRepository is bound behind the ConsentFlow seam so a
        // FakeConsentFlow can drive the screen tests (the concrete stays resolvable).
        single { ConsentApiClient(get()) }
        // diagnosticLog wired to the real sink (2026-06-10 audit, 06 medium: sink-wiring drift).
        single { ConsentRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<ConsentFlow> { get<ConsentRepository>() }

        // hide-distance capability — the Settings Premium toggle seam. ApiClient (GET state + PATCH) →
        // DefaultHideDistanceRepository bound behind the HideDistanceRepository interface so a fake drives
        // the screen tests; reuses the shared bearer-authed HttpClient.
        single { HideDistanceApiClient(get()) }
        single<HideDistanceRepository> { DefaultHideDistanceRepository(get()) }

        // mobile-settings-screen — the settings graph. The block-list seam (ApiClient → Repository bound
        // behind BlockedUsersFlow so a FakeBlockedUsersFlow drives the screen tests) reuses the shared
        // (bearer-authed) HttpClient; no new client, no X-Session-Id (the block endpoints carry no
        // per-session soft-cap accounting). The consent settings sub-screen REUSES the ConsentFlow above
        // (no second consent path). The ConsentSnapshotStore is the durable platform binding in
        // `platformModule` (mobile-amplitude-analytics, resolves #198).
        single { BlockedUsersApiClient(get()) }
        single { BlockedUsersRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<BlockedUsersFlow> { get<BlockedUsersRepository>() }

        // account-deletion-tombstone — the "Hapus Akun" + restore-banner seam (ApiClient → Repository
        // bound behind AccountDeletionFlow so a FakeAccountDeletionFlow drives the screen tests). Reuses
        // the shared bearer-authed HttpClient; no new client. The endpoints are not session-soft-capped.
        single { AccountDeletionApiClient(get()) }
        single { AccountDeletionRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<AccountDeletionFlow> { get<AccountDeletionRepository>() }

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

        // mobile-content-report — the shared report-submission seam (data/report/), consumed by BOTH the
        // profile (user report) and post-detail (post/reply report) surfaces. ReportApiClient wraps the
        // SHIPPED POST /api/v1/reports over the shared (bearer-authed) HttpClient (NO new client, NO
        // X-Session-Id — reports is not session-soft-capped); ReportSubmitter holds the single
        // status→ReportOutcome mapping (relocated from ProfileRepository.report). The (status, errorCode)
        // sink shape is adapted onto the shared string DiagnosticSink (coordinate-free by construction —
        // an Int + a server error-code enum), matching the CreatePost/PostDetail/Profile wiring.
        single { ReportApiClient(get()) }
        single {
            val sink = get<DiagnosticSink>()
            ReportSubmitter(
                get(),
                diagnosticLog = { status, errorCode -> sink.log("report_error: status=$status code=$errorCode") },
            )
        }

        // mobile-profile — the profile surface graph (read + follow/block/report). Reuses the shared
        // HttpClient (Bearer via the Auth plugin; NO X-Session-Id — none of these endpoints are
        // session-soft-capped) + the shared ReportSubmitter above (report submission). ProfileRepository
        // is bound behind the ProfileFlow seam so a FakeProfileFlow drives the screen/VM tests (the
        // concrete stays resolvable). SelfUserIdProvider decodes the access token's `sub` from the
        // TokenStore so the Profil section resolves the self id.
        single { ProfileApiClient(get()) }
        single {
            val sink = get<DiagnosticSink>()
            ProfileRepository(
                get(),
                reportSubmitter = get(),
                diagnosticLog = { status, errorCode -> sink.log("profile_error: status=$status code=$errorCode") },
            )
        }
        single<ProfileFlow> { get<ProfileRepository>() }
        single<SelfUserIdProvider> { TokenStoreSelfUserIdProvider(get()) }
        // mobile-follow-lists — the follower/following list graph (the tappable profile counts → tabbed
        // member lists). Reuses the shared HttpClient (Bearer via the Auth plugin; NO X-Session-Id — these
        // list reads are not session-soft-capped). FollowListRepository is bound behind the FollowListFlow
        // seam so a FakeFollowListFlow drives the screen/VM tests (the concrete stays resolvable). No new
        // HttpClient; no Flyway/backend dependency (pure consumer of the shipped follow-system endpoints).
        single { FollowListApiClient(get()) }
        single {
            val sink = get<DiagnosticSink>()
            FollowListRepository(
                get(),
                diagnosticLog = { status -> sink.log("follow_list_error: status=$status") },
            )
        }
        single<FollowListFlow> { get<FollowListRepository>() }
        // mobile-chat-screen — the 1:1 chat graph (conversation list + thread + realtime).
        //  - Three API clients over the shared (bearer-authed) HttpClient; NO X-Session-Id (chat
        //    endpoints are not session-soft-capped).
        //  - ConversationsRepository / ChatRepository bound behind ConversationsFlow / ChatFlow so the
        //    screen + ViewModel tests substitute fakes (the concretes stay resolvable).
        //  - RealtimeTokenApiClient doubles as the vendor-free RealtimeTokenProvider the infra
        //    subscriber injects (so :infra:supabase-realtime need not depend on :mobile:app).
        //  - SupabaseChatRealtimeSubscriber (the ONLY supabase-kt consumer) bound behind the vendor-free
        //    ChatRealtimeSubscriber seam, fed the non-secret project URL + anon key from flavor config.
        //  - TokenViewerIdProvider supplies the viewer's own id (JWT sub) for own-vs-other alignment.
        //  - NotificationPromptOneShot gates the first-send permission rationale (per-process one-shot).
        single { ConversationsApiClient(get()) }
        single { ChatMessagesApiClient(get()) }
        single { RealtimeTokenApiClient(get()) }
        single<RealtimeTokenProvider> { get<RealtimeTokenApiClient>() }
        single { ConversationsRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<ConversationsFlow> { get<ConversationsRepository>() }
        single {
            val sink = get<DiagnosticSink>()
            ChatRepository(get(), get(), diagnosticLog = { status -> sink.log("chat_error: status=$status") })
        }
        single<ChatFlow> { get<ChatRepository>() }
        single<ChatRealtimeSubscriber> {
            SupabaseChatRealtimeSubscriber(
                supabaseUrl = supabaseConfig.url,
                supabaseKey = supabaseConfig.anonKey,
                tokenProvider = get(),
            )
        }
        single<ViewerIdProvider> { TokenViewerIdProvider(get()) }
        single { NotificationPromptOneShot() }

        // mobile-search — the Premium-gated Cari graph (GET /api/v1/search). Reuses the shared HttpClient
        // (Bearer attached by the Auth plugin; NO X-Session-Id — search is not session-soft-capped). The
        // status→SearchOutcome mapping (403 gate / 429 rate-limit / 503 kill switch) lives in
        // SearchRepository, bound behind the SearchFlow seam so a FakeSearchFlow drives the screen +
        // ViewModel tests. diagnosticLog wired to the real coordinate-/query-safe sink (status/type only).
        single { SearchApiClient(get()) }
        single { SearchRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<SearchFlow> { get<SearchRepository>() }

        // image-attached-posts (Phase 5, upload data layer) — the premium image-upload seam over
        // POST /api/v1/images. Reuses the shared (bearer-authed) HttpClient (Bearer + 401 owned by the
        // Auth plugin; NO new client, NO X-Session-Id — the upload endpoint carries no per-session
        // soft-cap accounting). The status + error.code → ImageUploadOutcome mapping (the two 403s /
        // two 429s disambiguated by error.code) lives in ImageUploadRepository. The (status, errorCode)
        // sink shape is adapted onto the shared string DiagnosticSink (same adapter as CreatePostRepository
        // above) — coordinate-/bytes-free by construction (an Int + a server error-code string, NEVER the
        // image bytes). The ImagePicker actual + the composer ViewModel are Phase 4/6, NOT wired here.
        single { ImageUploadApiClient(get()) }
        single {
            val sink = get<DiagnosticSink>()
            ImageUploadRepository(
                get(),
                diagnosticLog = { status, errorCode -> sink.log("image_upload_error: status=$status code=$errorCode") },
            )
        }
        // image-attached-posts (Phase 6) — the composer ViewModel consumes the ImageUploader seam (bound to
        // the repository above), never the ApiClient (docs/11 §2.6). A FakeImageUploadRepository drives the
        // composer tests through the seam (the concrete stays resolvable).
        single<ImageUploader> { get<ImageUploadRepository>() }

        // mobile-premium-username-customization — the Ganti Username data seam over the SHIPPED, FROZEN
        // PATCH /api/v1/user/username + GET /api/v1/username/check endpoints (Bearer attached by the Auth
        // plugin; no new client). The status→outcome mapping (reading the body error code only to split
        // 429 cooldown_active/rate_limited + 422 invalid_username/username_rejected) lives in
        // UsernameRepository, bound behind the UsernameFlow seam so a FakeUsernameFlow drives the screen +
        // ViewModel tests. diagnosticLog wired to the real coordinate-/candidate-safe sink (status/type only).
        single { UsernameApiClient(get()) }
        single { UsernameRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<UsernameFlow> { get<UsernameRepository>() }

        // content-moderation-appeal (mobile-appeal) — the ban/suspension appeal graph. The appeal call is
        // ban-exempt and the caller has NO normal token, so AppealApiClient is wired to a RAW client
        // (createUnauthenticated — no Auth plugin) so the limited appeal token it attaches explicitly is not
        // overridden by the bearer plugin. AppealSession holds the token captured at the sign-in 403 (a
        // single, never persisted, never on a NavKey — the PendingSignupIdentity precedent). AppealFlow is
        // the testable seam (FakeAppealFlow drives the screen + ViewModel tests).
        single { AppealSession() }
        single { AppealApiClient(HttpClientFactory.createUnauthenticated(apiBaseUrl, httpClientEngine(), isDebugBuild)) }
        single { AppealRepository(get(), diagnosticLog = get<DiagnosticSink>()::log) }
        single<AppealFlow> { get<AppealRepository>() }

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
