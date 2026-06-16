package id.nearyou.app.infra.cloudvision

/**
 * Categorical Safe Search likelihood, mirroring Google Cloud Vision's `Likelihood`
 * enum as a plain Kotlin type so the vendor SDK never leaks past `:infra:cloud-vision`
 * (the `RemoteConfigClient` plain-types precedent; no vendor SDK import outside `:infra:*`).
 */
enum class SafeSearchLikelihood { UNKNOWN, VERY_UNLIKELY, UNLIKELY, POSSIBLE, LIKELY, VERY_LIKELY }

/**
 * Vision Safe Search verdict for an image — the three categories the moderation
 * policy evaluates (design D2). `medical` / `spoof` are not part of the reject set.
 */
data class SafeSearchVerdict(
    val adult: SafeSearchLikelihood,
    val violence: SafeSearchLikelihood,
    val racy: SafeSearchLikelihood,
) {
    /**
     * Reject when `adult` OR `violence` OR `racy` is `LIKELY` or `VERY_LIKELY`
     * (design D2 — matches docs/02 §6 flow + docs/06). `POSSIBLE` and lower pass.
     */
    val isExplicit: Boolean
        get() = listOf(adult, violence, racy).any {
            it == SafeSearchLikelihood.LIKELY || it == SafeSearchLikelihood.VERY_LIKELY
        }
}

/**
 * Upfront image moderation port. The single vendor implementation
 * ([GoogleCloudVisionImageModerator]) lives in this module; `:backend:ktor` depends
 * only on this interface.
 *
 * [isConfigured] reports whether the Vision service-account credential is present —
 * an unconfigured environment fails soft (the FCM / RevenueCat precedent): the
 * caller checks [isConfigured] and returns 503 rather than calling [safeSearch].
 */
interface ImageModerator {
    fun isConfigured(): Boolean

    /**
     * Runs Safe Search on the image bytes. Callers MUST gate on [isConfigured] first.
     * Implementations may throw on a transient Vision error — the caller treats a
     * moderation failure as fail-closed (do not store an un-moderated image).
     */
    suspend fun safeSearch(bytes: ByteArray): SafeSearchVerdict
}

/**
 * Fail-soft binding when no Vision credential is configured. [isConfigured] is false;
 * [safeSearch] is never reached on the gated path (it throws defensively if it is).
 */
object NoOpImageModerator : ImageModerator {
    override fun isConfigured(): Boolean = false

    override suspend fun safeSearch(bytes: ByteArray): SafeSearchVerdict =
        throw IllegalStateException("ImageModerator is not configured — gate on isConfigured()")
}

/**
 * Factory. Returns the real Vision-backed moderator when [serviceAccountJson] is a
 * non-blank credential, else the fail-soft [NoOpImageModerator]. Returns the public
 * [ImageModerator] interface so `:backend:ktor` imports no `com.google.cloud.*` symbol.
 */
fun imageModerator(serviceAccountJson: String?): ImageModerator =
    if (serviceAccountJson.isNullOrBlank()) {
        NoOpImageModerator
    } else {
        GoogleCloudVisionImageModerator(serviceAccountJson)
    }
