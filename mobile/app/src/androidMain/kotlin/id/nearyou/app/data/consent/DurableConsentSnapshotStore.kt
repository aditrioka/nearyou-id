package id.nearyou.app.data.consent

import android.content.Context

/**
 * Android [DurableConsentSnapshotStore] actual: a `Context`-scoped private `SharedPreferences` file.
 * Consent flags are non-secret (unlike the auth tokens in `SecureTokenStore`, which use encrypted
 * DataStore + Keychain), so plain `SharedPreferences` is the right synchronous key-value fit for this
 * synchronous [ConsentSnapshotStore] interface. A [KEY_PRESENT] marker distinguishes "never written"
 * (→ `read()` returns `null`, so consumers apply their own safe default) from a written all-false triple.
 */
actual class DurableConsentSnapshotStore(context: Context) : ConsentSnapshotStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    actual override fun read(): ConsentSnapshot? {
        if (!prefs.getBoolean(KEY_PRESENT, false)) return null
        return ConsentSnapshot(
            analytics = prefs.getBoolean(KEY_ANALYTICS, false),
            crash = prefs.getBoolean(KEY_CRASH, true),
            adsPersonalization = prefs.getBoolean(KEY_ADS, false),
        )
    }

    actual override fun write(snapshot: ConsentSnapshot) {
        prefs.edit()
            .putBoolean(KEY_PRESENT, true)
            .putBoolean(KEY_ANALYTICS, snapshot.analytics)
            .putBoolean(KEY_CRASH, snapshot.crash)
            .putBoolean(KEY_ADS, snapshot.adsPersonalization)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "nearyou_consent_snapshot"
        const val KEY_PRESENT = "present"
        const val KEY_ANALYTICS = "analytics"
        const val KEY_CRASH = "crash"
        const val KEY_ADS = "ads_personalization"
    }
}
