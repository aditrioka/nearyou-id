package id.nearyou.app.ads

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.infra.redis.RedisStringCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * `ads_enabled` kill-switch read for the `ads-config` capability, conforming to docs/11 §3.3 — the
 * Remote-Config→Redis flag seam with a per-flag **short TTL** override (30s) so an emergency flip-to-FALSE
 * (an ad-quality / policy incident) propagates within the TTL without a deploy, instead of the 5-min
 * default staleness budget. A byte-for-byte sibling of [id.nearyou.app.image.ImageUploadFlagGate] (the
 * first short-TTL flag, operator-ratified 2026-06-11), NOT the uncached `search_enabled` path.
 *
 * **Fail-CLOSED (default FALSE)** on any error or unset value — ads ship BUILT-but-OFF and must stay dark
 * unless the flag is explicitly TRUE (the launch kill-switch posture, the `image_upload_enabled`
 * precedent; the opposite of `search_enabled`'s fail-OPEN).
 */
class AdsEnabledFlagGate(
    private val redisCache: RedisStringCache,
    private val remoteConfig: RemoteConfig,
    private val cacheTtl: Duration = Duration.ofSeconds(SHORT_TTL_SECONDS),
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(AdsEnabledFlagGate::class.java)

    suspend fun isEnabled(): Boolean =
        withContext(dbDispatcher) {
            // Tier 1 — Redis cache (30s short TTL).
            when (redisCache.get(CACHE_KEY)?.trim()?.lowercase()) {
                "true" -> return@withContext true
                "false" -> return@withContext false
                else -> Unit // cache miss / parse failure → cascade to Tier 2
            }

            // Tier 2 — Remote Config. On throw, fail CLOSED (FALSE) WITHOUT caching, so a transient
            // error doesn't pin the feature dark for the full TTL.
            val fetched =
                try {
                    remoteConfig.getBoolean(FLAG_KEY)
                } catch (t: Throwable) {
                    log.warn(
                        "event=ads_enabled_flag_error key={} fallback=closed_false reason={}",
                        FLAG_KEY,
                        t.javaClass.simpleName,
                    )
                    return@withContext false
                }
            // Unset (null) → fail closed to FALSE; cache the resolved value so reads short-circuit
            // for the 30s window.
            val effective = fetched ?: false
            redisCache.set(CACHE_KEY, effective.toString(), cacheTtl)
            effective
        }

    companion object {
        const val FLAG_KEY: String = "ads_enabled"
        const val SHORT_TTL_SECONDS: Long = 30L
        const val CACHE_KEY: String = "{scope:remote_config}:{flag:ads_enabled}"
    }
}
