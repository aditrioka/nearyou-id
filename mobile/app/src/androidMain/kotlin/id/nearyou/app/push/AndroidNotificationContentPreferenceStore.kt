package id.nearyou.app.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val PREVIEW_ENABLED_KEY = booleanPreferencesKey("chat_preview_enabled")

// A corrupted prefs file resolves to empty (= unset = private default) instead of
// throwing on every read — mirrors the SecureTokenStore corruption posture.
private val Context.notificationPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "nearyou_notification_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Android [NotificationContentPreferenceStore]: DataStore Preferences (the
 * pinned preference substrate). The flag is non-secret (a display preference),
 * so no encryption — unlike the token store sharing this file-level idiom.
 * An absent key reads as `null` (= unset → the commonMain default-OFF).
 */
class AndroidNotificationContentPreferenceStore(
    context: Context,
) : NotificationContentPreferenceStore {
    private val dataStore = context.applicationContext.notificationPrefs

    override suspend fun read(): Boolean? = dataStore.data.first()[PREVIEW_ENABLED_KEY]

    override suspend fun write(v: Boolean) {
        dataStore.edit { it[PREVIEW_ENABLED_KEY] = v }
    }
}
