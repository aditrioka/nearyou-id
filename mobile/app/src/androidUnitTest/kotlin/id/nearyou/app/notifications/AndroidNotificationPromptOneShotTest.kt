package id.nearyou.app.notifications

import android.content.Context
import id.nearyou.app.di.platformModule
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatformTools
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #494 — the first-send rationale one-shot is per INSTALL: claimed once, the flag survives a freshly
 * constructed instance over the same SharedPreferences (the process-restart simulation, mirroring
 * `DurableConsentSnapshotStoreTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidNotificationPromptOneShotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @AfterTest
    fun clearPrefs() {
        context.getSharedPreferences("nearyou_notification_prompt", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // The binding moved from mobileModule to the platform modules — the real Android platformModule resolves
    // the persisted impl (a lazy single: nothing else in the module is constructed).
    @Test
    fun platformModule_bindsThePersistedImpl() {
        if (KoinPlatformTools.defaultContext().getOrNull() != null) stopKoin()
        try {
            val koin =
                startKoin {
                    androidContext(context)
                    modules(platformModule)
                }.koin
            assertIs<AndroidNotificationPromptOneShot>(koin.get<NotificationPromptOneShot>())
        } finally {
            stopKoin()
        }
    }

    @Test
    fun claimsOnce_thenNeverAgain_evenAfterARestart() =
        runTest {
            assertTrue(AndroidNotificationPromptOneShot(context).claim(), "the first claim on a fresh install wins")
            assertFalse(AndroidNotificationPromptOneShot(context).claim(), "a new process (new instance) does not re-claim")
        }
}
