package id.nearyou.app.image

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.core.domain.ratelimit.computeTTLToNextReset
import id.nearyou.app.infra.cloudflareimages.ImageStore
import id.nearyou.app.infra.cloudvision.ImageModerator
import id.nearyou.app.subscription.PREMIUM_STATES
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Image-upload pipeline orchestration (design D9 validation order). Split into two phases so
 * the (potentially 5 MB) request body is NOT read until the cheap gates pass:
 *
 *  - [checkGate] — flag → premium → throttle → daily-quota (consumed at attempt). The route
 *    calls this BEFORE reading the multipart body.
 *  - [moderateAndStore] — Safe Search → Cloudflare upload → ledger insert. The route calls
 *    this AFTER it has streamed + size/content-type-validated the bytes.
 *
 * The route maps [GateResult] / [StoreResult] to HTTP (403/429 for the gate; 201/422/503 for
 * the store); the 413/415 size + content-type guards live in the route (streamed, pre-service).
 */
class ImageUploadService(
    private val repository: ImageUploadRepository,
    private val flagGate: ImageUploadFlagGate,
    private val rateLimiter: ImageUploadRateLimiter,
    private val moderator: ImageModerator,
    private val store: ImageStore,
    private val remoteConfig: RemoteConfig,
    private val nowProvider: () -> Instant = Instant::now,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(ImageUploadService::class.java)

    /** Phase 1 — flag → premium → throttle → daily quota (D9). Consumes throttle + quota at attempt. */
    suspend fun checkGate(
        userId: UUID,
        subscriptionStatus: String,
    ): GateResult {
        if (!flagGate.isEnabled()) return GateResult.FlagDisabled
        if (subscriptionStatus !in PREMIUM_STATES) return GateResult.PremiumRequired

        val throttle = withContext(dbDispatcher) { rateLimiter.tryAcquireThrottle(userId) }
        if (throttle is ImageUploadRateLimiter.Outcome.RateLimited) {
            return GateResult.Throttled(throttle.retryAfterSeconds)
        }

        val cap = resolveDailyCap(userId)
        val daily =
            withContext(dbDispatcher) {
                rateLimiter.tryAcquireDaily(userId, cap, computeTTLToNextReset(userId, nowProvider()))
            }
        if (daily is ImageUploadRateLimiter.Outcome.RateLimited) {
            return GateResult.QuotaExceeded(daily.retryAfterSeconds)
        }
        return GateResult.Ok
    }

    /** Phase 2 — Safe Search (fail-closed) → Cloudflare store → `image_uploads` ledger insert. */
    suspend fun moderateAndStore(
        userId: UUID,
        bytes: ByteArray,
        contentType: String,
        fileName: String,
    ): StoreResult {
        if (!moderator.isConfigured() || !store.isConfigured()) {
            return StoreResult.Unavailable
        }
        // Fail-closed: a moderation error must not let an un-moderated image through → 503 (retryable).
        val verdict =
            try {
                moderator.safeSearch(bytes)
            } catch (t: Throwable) {
                log.warn("event=image_moderation_failed reason={}", t.javaClass.simpleName)
                return StoreResult.Unavailable
            }
        if (verdict.isExplicit) return StoreResult.Rejected

        val stored =
            try {
                store.upload(bytes, contentType, fileName)
            } catch (t: Throwable) {
                log.warn("event=image_store_failed reason={}", t.javaClass.simpleName)
                return StoreResult.Unavailable
            }

        withContext(dbDispatcher) {
            repository.insertUploaded(stored.imageId, userId, verdict)
        }
        return StoreResult.Success(imageId = stored.imageId, deliveryUrl = stored.deliveryUrl)
    }

    /**
     * Resolves the daily upload cap. Reads `premium_image_upload_cap_override` from Remote
     * Config (the uncached `premium_like_cap_override` precedent); non-positive / out-of-range /
     * unset / error all collapse to the default 50.
     */
    private fun resolveDailyCap(userId: UUID): Int {
        val raw =
            try {
                remoteConfig.getLong(CAP_OVERRIDE_KEY)
            } catch (t: Throwable) {
                log.warn(
                    "event=remote_config_error key={} fallback=default_50 user_id={}",
                    CAP_OVERRIDE_KEY,
                    userId,
                )
                return ImageUploadRateLimiter.DEFAULT_DAILY_CAP
            }
        return raw?.takeIf { it in 1..MAX_CAP }?.toInt() ?: ImageUploadRateLimiter.DEFAULT_DAILY_CAP
    }

    sealed interface GateResult {
        data object Ok : GateResult

        data object FlagDisabled : GateResult

        data object PremiumRequired : GateResult

        data class Throttled(val retryAfterSeconds: Long) : GateResult

        data class QuotaExceeded(val retryAfterSeconds: Long) : GateResult
    }

    sealed interface StoreResult {
        data class Success(val imageId: String, val deliveryUrl: String) : StoreResult

        data object Rejected : StoreResult

        data object Unavailable : StoreResult
    }

    companion object {
        const val CAP_OVERRIDE_KEY: String = "premium_image_upload_cap_override"
        const val MAX_CAP: Long = 10_000L
    }
}
