package id.nearyou.app.screens

import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.consent.ConsentFlow
import id.nearyou.app.consent.FakeConsentFlow
import id.nearyou.app.data.block.BlockSubmitter
import id.nearyou.app.data.block.FakeBlockSubmitter
import id.nearyou.app.data.consent.ConsentSnapshotStore
import id.nearyou.app.data.consent.InMemoryConsentSnapshotStore
import id.nearyou.app.data.like.FakeLikeFlow
import id.nearyou.app.data.like.LikeFlow
import id.nearyou.app.data.report.FakeReportSubmitter
import id.nearyou.app.data.report.ReportSubmitter
import id.nearyou.app.notifications.FakeNotificationsFlow
import id.nearyou.app.notifications.NotificationsFlow
import id.nearyou.app.post.FakePostEditFlow
import id.nearyou.app.post.PostEditFlow
import id.nearyou.app.privateprofile.PrivateProfileRepository
import id.nearyou.app.privateprofile.PrivateProfileState
import id.nearyou.app.profile.FakeProfileFlow
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.push.fakeFcmTokenRegistrar
import id.nearyou.app.screens.username.FakeSelfUserIdProvider
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * The shared Koin harness for the iOS `*FlowIosTest`s ([#348](https://github.com/aditrioka/nearyou-id/issues/348)).
 *
 * Screens resolve their collaborators with `koinInject` at composition, so a test module that omits ONE
 * transitively-injected binding fails the whole class with `NoDefinitionFoundException`. Each test used
 * to hand-enumerate its screen tree's graph, so every new `koinInject<X>()` (`LikeFlow` #234,
 * `FcmTokenRegistrar`, `ConsentSnapshotStore`, `PostEditFlow`, `ProfileFlow`, …) silently drifted the
 * suite red. [flowTestDefaults] binds an inert fake for each cross-screen collaborator ONCE; a test's own
 * modules load after it (Koin `allowOverride`, on by default) and bind only what the test asserts on.
 * A screen that gains a new cross-screen `koinInject` adds its default here, not to N tests.
 */
fun startFlowTestKoin(vararg modules: Module) {
    stopFlowTestKoin()
    startKoin { modules(listOf(flowTestDefaults) + modules) }
}

fun stopFlowTestKoin() {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
}

private val flowTestDefaults =
    module {
        // The post-card actions every feed / post-detail surface injects.
        single<LikeFlow> { FakeLikeFlow() }
        single<ReportSubmitter> { FakeReportSubmitter() }
        single<BlockSubmitter> { FakeBlockSubmitter() }
        single<SelfUserIdProvider> { FakeSelfUserIdProvider() }
        single<PostEditFlow> { FakePostEditFlow() }
        single<ProfileFlow> { FakeProfileFlow() }
        single<ConsentSnapshotStore> { InMemoryConsentSnapshotStore() }
        single<ConsentFlow> { FakeConsentFlow() }
        // The shell's unread badge + the push-tap effect.
        single<NotificationsFlow> { FakeNotificationsFlow() }
        // No token → registers nothing, issues no HTTP (the shell's push-token seam).
        single { fakeFcmTokenRegistrar() }
        single<PrivateProfileRepository> {
            object : PrivateProfileRepository {
                override suspend fun loadState(): PrivateProfileState? = null

                override suspend fun setPrivateProfile(value: Boolean): Boolean = true
            }
        }
    }
