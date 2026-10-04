package id.nearyou.app.notifications

import platform.Foundation.NSUserDefaults

/**
 * iOS [NotificationPromptOneShot]: a standard `NSUserDefaults` flag, so the first-send rationale survives
 * process death (once per install). `boolForKey` reads `false` for an absent key, which is the "unclaimed"
 * state, so no presence marker is needed.
 */
class IosNotificationPromptOneShot : NotificationPromptOneShot {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun claim(): Boolean {
        if (defaults.boolForKey(KEY_CLAIMED)) return false
        defaults.setBool(true, forKey = KEY_CLAIMED)
        return true
    }

    private companion object {
        const val KEY_CLAIMED = "nearyou_first_send_rationale_claimed"
    }
}
