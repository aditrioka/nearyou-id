package id.nearyou.app.infra.fcm

import id.nearyou.data.repository.NotificationType

/**
 * In-source Indonesian copy for FCM push notifications. Per
 * `openspec/changes/fcm-push-dispatch/design.md` D4: the four V10-wired
 * notification types are the only emit-sites that ship today, so a server-side
 * i18n abstraction would be over-engineering for a four-string surface. Once
 * chat lands and the remaining nine types ship their emit-sites, a migration
 * to a `:shared:resources`-style server i18n module is a one-file refactor.
 *
 * MUST NOT call any external service, MUST NOT read any database row, AND
 * MUST NOT depend on Moko Resources (the latter is a KMP client concern).
 */
object PushCopy {
    private const val APP_NAME = "NearYou"
    private const val FALLBACK_BODY = "Notifikasi baru dari NearYou"
    private const val UNKNOWN_ACTOR = "Seseorang"

    /**
     * Per-type push title. Currently a constant (`"NearYou"`) for every type
     * and the fallback — there is no per-type title differentiation in this
     * change.
     */
    fun titleFor(
        @Suppress("UNUSED_PARAMETER") type: String,
    ): String = APP_NAME

    /**
     * Per-type push body parameterized by the actor username (when available).
     *
     * For unwired types (the nine V10 enum values that have no emit-site yet),
     * returns the fallback `"Notifikasi baru dari NearYou"` regardless of
     * whether [actorUsername] is null — the fallback intentionally does not
     * interpolate the actor handle.
     */
    fun bodyFor(
        type: String,
        actorUsername: String?,
    ): String {
        val actor = actorUsername ?: UNKNOWN_ACTOR
        return when (type) {
            NotificationType.POST_LIKED.wire -> "$actor menyukai post-mu"
            NotificationType.POST_REPLIED.wire -> "$actor membalas post-mu"
            NotificationType.FOLLOWED.wire -> "$actor mulai mengikuti kamu"
            NotificationType.POST_AUTO_HIDDEN.wire ->
                "Postinganmu disembunyikan otomatis karena beberapa laporan"
            else -> FALLBACK_BODY
        }
    }
}
