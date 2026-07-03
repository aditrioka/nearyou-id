package id.nearyou.app.push

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

/**
 * iOS [NotificationContentPreferenceStore]: the `group.id.nearyou.shared`
 * App-Group `UserDefaults` suite (`docs/04` §490) so the out-of-process
 * Notification Service Extension can read the preference — the NSE cannot
 * reach the app sandbox, only the App Group (design D4). `objectForKey`
 * distinguishes "never written" (`null` → the commonMain default-OFF) from a
 * written `false` (mirrors the `DurableConsentSnapshotStore` marker rationale
 * without needing a marker key).
 *
 * Without the operator App-Group entitlement (#430) the suite falls back to a
 * non-shared defaults domain — reads/writes still work in-process (no crash;
 * design D9), the NSE just cannot see them until the entitlement ships.
 */
class IosNotificationContentPreferenceStore : NotificationContentPreferenceStore {
    private val defaults = NSUserDefaults(suiteName = APP_GROUP_SUITE)

    override suspend fun read(): Boolean? = (defaults.objectForKey(PREVIEW_ENABLED_KEY) as? NSNumber)?.boolValue

    override suspend fun write(v: Boolean) {
        defaults.setBool(v, forKey = PREVIEW_ENABLED_KEY)
    }

    companion object {
        /** The `docs/04` §490 canonical App-Group identifier — shared with the NSE target. */
        const val APP_GROUP_SUITE = "group.id.nearyou.shared"
        const val PREVIEW_ENABLED_KEY = "nearyou_chat_preview_enabled"
    }
}
