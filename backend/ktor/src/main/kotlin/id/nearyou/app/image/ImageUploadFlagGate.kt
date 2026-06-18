package id.nearyou.app.image

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.infra.redis.RedisStringCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * `image_upload_enabled` kill-switch read, conforming to docs/11 §3.3 (operator-ratified
 * 2026-06-11, which names this flag as the first short-TTL candidate). The read goes through
 * a Redis-cached flag read with a **30s** per-flag TTL override (vs the 5-min default), so an
 * emergency flip-to-FALSE propagates within 30s — following the `Layer3ConfigLoader` Redis
 * cache pattern, NOT the uncached `SearchService` path.
 *
 * **Fail-CLOSED (default FALSE)** on any error or unset value — a launch-gated flag must stay
 * dark unless explicitly TRUE (the opposite of `search_enabled`'s fail-OPEN; design D6).
 */
class ImageUploadFlagGate(
    private val redisCache: RedisStringCache,
    private val remoteConfig: RemoteConfig,
    private val cacheTtl: Duration = Duration.ofSeconds(SHORT_TTL_SECONDS),
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(ImageUploadFlagGate::class.java)

    suspend fun isEnabled(): Boolean =
        withContext(dbDispatcher) {
            // Tier 1 — Redis cache (30s short TTL).
            when (redisCache.get(CACHE_KEY)?.trim()?.lowercase()) {
                "true" -> return@withContext true
                "false" -> return@withContext false
                else -> Unit // cache miss / parse failure → cascade to Tier 2
            }

            // Tier 2 — Remote Config. On throw, fail CLOSED (FALSE) WITHOUT caching, so a
            // transient error doesn't pin the feature dark for the full TTL.
            val fetched =
                try {
                    remoteConfig.getBoolean(FLAG_KEY)
                } catch (t: Throwable) {
                    log.warn(
                        "event=image_upload_flag_error key={} fallback=closed_false reason={}",
                        FLAG_KEY,
                        t.javaClass.simpleName,
                    )
                    return@withContext false
                }
            // Unset (null) → fail closed to FALSE; cache the resolved value so reads
            // short-circuit for the 30s window.
            val effective = fetched ?: false
            redisCache.set(CACHE_KEY, effective.toString(), cacheTtl)
            effective
        }

    companion object {
        const val FLAG_KEY: String = "image_upload_enabled"
        const val SHORT_TTL_SECONDS: Long = 30L
        const val CACHE_KEY: String = "{scope:remote_config}:{flag:image_upload_enabled}"
    }
}
