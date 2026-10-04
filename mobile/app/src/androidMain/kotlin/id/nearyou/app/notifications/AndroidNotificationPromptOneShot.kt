package id.nearyou.app.notifications

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [NotificationPromptOneShot]: a `Context`-scoped private `SharedPreferences` flag, so the first-send
 * rationale survives process death (once per install). The first prefs access blocks on the file load, so
 * the read/write hops to IO (the `AndroidNotificationPermissionController` / 07-#4 precedent).
 */
class AndroidNotificationPromptOneShot(context: Context) : NotificationPromptOneShot {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun claim(): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(this@AndroidNotificationPromptOneShot) {
                if (prefs.getBoolean(KEY_CLAIMED, false)) {
                    false
                } else {
                    prefs.edit().putBoolean(KEY_CLAIMED, true).apply()
                    true
                }
            }
        }

    private companion object {
        const val PREFS_NAME = "nearyou_notification_prompt"
        const val KEY_CLAIMED = "first_send_rationale_claimed"
    }
}
