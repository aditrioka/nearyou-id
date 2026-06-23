package id.nearyou.app.data.consent

import platform.Foundation.NSUserDefaults

/**
 * iOS [DurableConsentSnapshotStore] actual: `NSUserDefaults` standard defaults. Consent flags are
 * non-secret (the auth tokens in `SecureTokenStore` use the Keychain; consent does not need it), so the
 * standard defaults are the right synchronous key-value fit for this synchronous [ConsentSnapshotStore]
 * interface. A [KEY_PRESENT] marker distinguishes "never written" (→ `read()` returns `null`) from a
 * written all-false triple (`boolForKey` returns `false` for an absent key, so a marker is required).
 */
actual class DurableConsentSnapshotStore : ConsentSnapshotStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual override fun read(): ConsentSnapshot? {
        if (defaults.objectForKey(KEY_PRESENT) == null) return null
        return ConsentSnapshot(
            analytics = defaults.boolForKey(KEY_ANALYTICS),
            crash = defaults.boolForKey(KEY_CRASH),
            adsPersonalization = defaults.boolForKey(KEY_ADS),
        )
    }

    actual override fun write(snapshot: ConsentSnapshot) {
        defaults.setBool(snapshot.analytics, forKey = KEY_ANALYTICS)
        defaults.setBool(snapshot.crash, forKey = KEY_CRASH)
        defaults.setBool(snapshot.adsPersonalization, forKey = KEY_ADS)
        defaults.setBool(true, forKey = KEY_PRESENT)
    }

    private companion object {
        const val KEY_PRESENT = "nearyou_consent_present"
        const val KEY_ANALYTICS = "nearyou_consent_analytics"
        const val KEY_CRASH = "nearyou_consent_crash"
        const val KEY_ADS = "nearyou_consent_ads"
    }
}
