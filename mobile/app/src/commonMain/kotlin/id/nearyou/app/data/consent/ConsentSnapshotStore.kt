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
 * Process-lifetime in-memory binding. It survives ViewModel reconstruction within a process (so a later
 * Settings re-entry seeds from the last submit), which is what the `mobile-settings` snapshot scenarios
 * exercise. **Durable on-disk persistence (surviving process death) is deferred to issue
 * [#198](https://github.com/aditrioka/nearyou-id/issues/198)** (design D5) — at which point a platform
 * expect/actual binding (DataStore / NSUserDefaults, the no-new-pin storage family backing
 * `SecureTokenStore`) replaces this one behind the same interface. Correctness is preserved meanwhile:
 * the snapshot is a faithful mirror of the last server-acknowledged state, and the V2 fallback is
 * privacy-safe, so a cold start simply re-shows the safe defaults until the user re-opens consent.
 */
class InMemoryConsentSnapshotStore : ConsentSnapshotStore {
    private var snapshot: ConsentSnapshot? = null

    override fun read(): ConsentSnapshot? = snapshot

    override fun write(snapshot: ConsentSnapshot) {
        this.snapshot = snapshot
    }
}
