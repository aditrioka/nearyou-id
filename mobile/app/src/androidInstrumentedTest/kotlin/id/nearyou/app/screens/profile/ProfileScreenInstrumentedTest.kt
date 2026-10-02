package id.nearyou.app.screens.profile

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import id.nearyou.app.auth.SelfUserIdProvider
import id.nearyou.app.data.block.BlockOutcome
import id.nearyou.app.data.report.ReportOutcome
import id.nearyou.app.data.report.ReportReasonCategory
import id.nearyou.app.profile.FollowToggleOutcome
import id.nearyou.app.profile.ProfileFlow
import id.nearyou.app.profile.ProfileOutcome
import id.nearyou.app.profile.UserProfile
import id.nearyou.app.theme.NearYouTheme
import org.junit.After
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import java.io.File

private const val SELF_ID = "self-1"

/** Read-only double: the smoke only renders, so every mutation is unreachable. */
private object SelfProfileFlow : ProfileFlow {
    override suspend fun loadProfile(userId: String): ProfileOutcome =
        ProfileOutcome.Loaded(
            UserProfile(
                userId = userId,
                username = "raka.jkt",
                displayName = "Raka Pratama",
                bio = "halo",
                followerCount = 3,
                followingCount = 5,
                isSelf = true,
                followedByViewer = false,
                isPremium = false,
                isPrivate = false,
            ),
        )

    override suspend fun follow(userId: String): FollowToggleOutcome = error("render-only smoke")

    override suspend fun unfollow(userId: String): FollowToggleOutcome = error("render-only smoke")

    override suspend fun block(userId: String): BlockOutcome = error("render-only smoke")

    override suspend fun report(
        userId: String,
        reasonCategory: ReportReasonCategory,
        note: String?,
    ): ReportOutcome = error("render-only smoke")
}

/**
 * On-device smoke for an authenticated screen (#275): the self Profil tab, which the Robo crawl in
 * device-run.yml never reaches because Google Sign-In's account picker belongs to Play Services, not
 * the app. Fakes stand in for the session and the backend (the `ProfileScreenTest` Robolectric
 * idiom), so the run needs no staging JWT and no live api-staging. The screenshot lands in the app's
 * external files dir, which scripts/test_firebase.sh pulls from the Test Lab device. Uses the v2
 * `runComposeUiTest` (docs/11 §2.7); `waitUntil` advances its StandardTestDispatcher clock.
 */
@OptIn(ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O) // captureToImage needs API 26
class ProfileScreenInstrumentedTest {
    @After
    fun tearDown() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
    }

    @Test
    fun selfProfile_rendersOnDevice() =
        runComposeUiTest {
            if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
            startKoin {
                modules(
                    module {
                        single<ProfileFlow> { SelfProfileFlow }
                        single<SelfUserIdProvider> {
                            object : SelfUserIdProvider {
                                override suspend fun selfUserId(): String = SELF_ID
                            }
                        }
                    },
                )
            }
            setContent {
                NearYouTheme {
                    // The self section draws no background of its own: in the app it sits in the
                    // shell's Scaffold, whose container this Surface stands in for.
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ProfileScreen(targetUserId = null, onBack = null)
                    }
                }
            }

            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Raka Pratama").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("@raka.jkt").assertExists()
            onNodeWithTag(PROFILE_FOLLOWERS_TAG).assertExists()
            onNodeWithTag(PROFILE_SETTINGS_TAG).assertExists()

            val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screenshots")
            File(dir, "profile-self.png").outputStream().use {
                onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
}
