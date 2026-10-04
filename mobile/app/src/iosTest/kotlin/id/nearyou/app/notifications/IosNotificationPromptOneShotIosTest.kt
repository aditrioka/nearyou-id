package id.nearyou.app.notifications

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #494 — the iOS first-send rationale one-shot is per INSTALL: claimed once, a freshly constructed
 * instance over the same standard `NSUserDefaults` (the process-restart simulation) does not re-claim.
 */
class IosNotificationPromptOneShotIosTest {
    @AfterTest
    fun clearDefaults() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey("nearyou_first_send_rationale_claimed")
    }

    @Test
    fun claimsOnceThenNeverAgainEvenAfterARestart() =
        runTest {
            clearDefaults()
            assertTrue(IosNotificationPromptOneShot().claim(), "the first claim on a fresh install wins")
            assertFalse(IosNotificationPromptOneShot().claim(), "a new process (new instance) does not re-claim")
        }
}
