package id.nearyou.app.notifications

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
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

    @Test
    fun claimsOnce_thenNeverAgain_evenAfterARestart() =
        runTest {
            assertTrue(AndroidNotificationPromptOneShot(context).claim(), "the first claim on a fresh install wins")
            assertFalse(AndroidNotificationPromptOneShot(context).claim(), "a new process (new instance) does not re-claim")
        }
}
