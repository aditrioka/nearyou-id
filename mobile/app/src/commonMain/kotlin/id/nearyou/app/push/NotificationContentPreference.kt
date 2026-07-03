package id.nearyou.app.push

/**
 * The "show chat preview in notifications" content-privacy preference
 * (`mobile-push-message-handling`; `docs/03` §178). Compose-free commonMain
 * seam: the Android render path and the iOS NSE read it before surfacing
 * `body_data.preview` in a notification body. Defaults to **OFF** (private)
 * when unset — at OFF the chat body is the sender+non-content form, never the
 * message preview. The Settings control row that flips it is deferred
 * (follow-up #431); only the store + safe default ship in this change.
 */
interface NotificationContentPreference {
    /** Whether the chat message preview may be surfaced. `false` (private) when unset. */
    suspend fun previewEnabled(): Boolean

    suspend fun setPreviewEnabled(v: Boolean)
}

/**
 * Platform persistence seam: a nullable-boolean KV read/write where `null`
 * means "never written". Android actual = DataStore Preferences; iOS actual =
 * the `group.id.nearyou.shared` App-Group `UserDefaults` suite (the NSE is a
 * separate process and can only reach the App Group, not the app sandbox —
 * design D4). Kept separate from [NotificationContentPreference] so the
 * default-OFF projection lives (and is tested) once in commonMain.
 */
interface NotificationContentPreferenceStore {
    suspend fun read(): Boolean?

    suspend fun write(v: Boolean)
}

/** Applies the default-OFF-when-unset contract over the platform [store]. */
class PersistedNotificationContentPreference(
    private val store: NotificationContentPreferenceStore,
) : NotificationContentPreference {
    override suspend fun previewEnabled(): Boolean = store.read() ?: false

    override suspend fun setPreviewEnabled(v: Boolean) = store.write(v)
}
