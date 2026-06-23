package id.nearyou.app.data.consent

/**
 * The last-submitted analytics-consent triple, mirrored on the device so `ConsentSettingsScreen` can
 * seed its toggles without a server read (there is NO consent-GET endpoint — `analytics-consent-update`
 * ships `PATCH` only). The value written is the triple the server **echoes in the `PATCH` `200` body**
 * (`ConsentResponse`) — the server's authoritative acknowledgement, not a client guess.
 */
data class ConsentSnapshot(
    val analytics: Boolean,
    val crash: Boolean,
    val adsPersonalization: Boolean,
)

/**
 * Read/write contract for the consent snapshot (`mobile-settings` § "Consent settings initialize from
 * the last-submitted snapshot"). [read] returns `null` when nothing has been submitted from settings yet
 * (the caller falls back to the V2 column defaults — analytics OFF, crash ON, ads OFF).
 */
interface ConsentSnapshotStore {
    fun read(): ConsentSnapshot?

    fun write(snapshot: ConsentSnapshot)
}

/**
 * The durable production binding (`mobile-amplitude-analytics`, resolving issue
 * [#198](https://github.com/aditrioka/nearyou-id/issues/198)): the snapshot persists across process
 * death so a consumer reading it (the analytics tracker, the crash gate) honors the user's last
 * server-acknowledged choice on every cold start. Platform actuals (consent flags are non-secret, so
 * plain key-value storage — no encryption — synchronous to match this interface):
 *  - androidMain: `SharedPreferences` (a `Context`-scoped private prefs file).
 *  - iosMain: `NSUserDefaults` (standard defaults).
 *
 * No constructor is declared here — construction happens only in the per-platform Koin `platformModule`
 * (android passes `androidContext()`; iOS is no-arg), so commonMain never instantiates it (the
 * `SecureTokenStore` expect/actual precedent, design D2). `InMemoryConsentSnapshotStore` remains the
 * test double.
 */
expect class DurableConsentSnapshotStore : ConsentSnapshotStore {
    override fun read(): ConsentSnapshot?

    override fun write(snapshot: ConsentSnapshot)
}

/**
 * Process-lifetime in-memory binding — the **test double** (commonTest substitutes it for the platform
 * [DurableConsentSnapshotStore] so consent/settings projections can be exercised without a platform
 * store). It survives ViewModel reconstruction within a process but NOT process death; production uses
 * the durable expect/actual binding above.
 */
class InMemoryConsentSnapshotStore : ConsentSnapshotStore {
    private var snapshot: ConsentSnapshot? = null

    override fun read(): ConsentSnapshot? = snapshot

    override fun write(snapshot: ConsentSnapshot) {
        this.snapshot = snapshot
    }
}
