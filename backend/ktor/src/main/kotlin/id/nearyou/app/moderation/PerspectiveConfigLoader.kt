package id.nearyou.app.moderation

import id.nearyou.app.infra.redis.RedisStringCache
import id.nearyou.app.infra.remoteconfig.RemoteConfigClient
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Cache layer for the Perspective Layer 3 kill-switch + threshold flags. Lives in
 * `:backend:ktor` (NOT inside `:infra:remote-config`) because the cache layer
 * depends on Redis — the infra module is stateless by contract per
 * `text-moderation-perspective-api-layer/spec.md`
 * `### Requirement: PerspectiveConfigLoader caches kill-switch + thresholds in :backend:ktor`.
 *
 * 4-tier resolution ladder per flag (mirrors the [CachingModerationListLoader] pattern):
 *
 *  1. **Redis cache** (key `{scope:perspective_config}:{flag:<flag-name>}`, TTL 5 min).
 *  2. **Firebase Remote Config** via [RemoteConfigClient]; cached to Tier 1 on success.
 *  3. **Static default** (`isEnabled = true`, `highScoreThreshold = 0.8`, `flagThreshold = 0.6`).
 *  4. (kill-switch only) **Fail-OPEN to `true`** if Tier 2 throws + Sentry **ERROR**
 *     `event = "perspective_kill_switch_unavailable"` per design.md Decision 12.
 *
 * Threshold reads apply a `[0.0, 1.0]` clamp on EVERY read (Tier 1 cache + Tier 2)
 * so a single bad Remote Config push CANNOT poison Layer 3 enforcement for the 5-min
 * cache window. Out-of-range values fall back to the static default and emit a Sentry
 * WARN `event = "perspective_threshold_fallback"`.
 *
 * Cross-flag misconfiguration (`flag_threshold > high_score_threshold`) is detected
 * at the orchestrator (`PerspectiveModerator`), NOT here — the loader returns
 * thresholds independently; the orchestrator checks the cross-flag invariant.
 */
interface PerspectiveConfigLoader {
    suspend fun isEnabled(): Boolean

    suspend fun highScoreThreshold(): Double

    suspend fun flagThreshold(): Double
}

/**
 * Production binding for [PerspectiveConfigLoader]. Suspending on Redis I/O via
 * the calling coroutine context (Lettuce sync API; the orchestrator is invoked from
 * the dispatcher scope's `Dispatchers.IO` per `PerspectiveDispatcherScope`).
 *
 * Sentry events are surfaced via SLF4J → logback → Sentry per the platform pattern;
 * `event=...` log fields are picked up as Sentry tags. No raw user content or per-attribute
 * scores ever appear in events emitted by this class.
 */
class CachingPerspectiveConfigLoader(
    private val redisCache: RedisStringCache,
    private val remoteConfigClient: RemoteConfigClient,
    private val cacheTtl: Duration = Duration.ofMinutes(5),
) : PerspectiveConfigLoader {
    private val log = LoggerFactory.getLogger(CachingPerspectiveConfigLoader::class.java)

    override suspend fun isEnabled(): Boolean {
        // Tier 1 — Redis cache.
        val cached = redisCache.get(KILL_SWITCH_CACHE_KEY)?.trim()?.lowercase()
        when (cached) {
            "true" -> return true
            "false" -> return false
            else -> Unit // cache miss / parse failure → cascade to Tier 2
        }

        // Tier 2 — Remote Config. On throw, fail-OPEN to `true` AND emit Sentry ERROR.
        return try {
            val rc = remoteConfigClient.fetchBoolean(KILL_SWITCH_PARAM)
            if (rc != null) {
                redisCache.set(KILL_SWITCH_CACHE_KEY, rc.toString(), cacheTtl)
                rc
            } else {
                // Tier 3 — static default (true). Cache the default so subsequent calls
                // short-circuit; the cache TTL bounds re-fetch frequency to the same
                // 5-min operational contract.
                redisCache.set(KILL_SWITCH_CACHE_KEY, "true", cacheTtl)
                DEFAULT_KILL_SWITCH
            }
        } catch (t: Throwable) {
            log.error(
                "event={} key={} reason={} fail_mode=open",
                EVENT_KILL_SWITCH_UNAVAILABLE,
                KILL_SWITCH_PARAM,
                t.javaClass.simpleName,
            )
            DEFAULT_KILL_SWITCH
        }
    }

    override suspend fun highScoreThreshold(): Double = readThreshold(HIGH_SCORE_PARAM, DEFAULT_HIGH_SCORE_THRESHOLD)

    override suspend fun flagThreshold(): Double = readThreshold(FLAG_PARAM, DEFAULT_FLAG_THRESHOLD)

    private suspend fun readThreshold(
        param: String,
        default: Double,
    ): Double {
        val cacheKey = thresholdCacheKey(param)

        // Tier 1 — Redis cache. Apply clamp on EVERY read; reject negative or
        // > 1.0 cached values (a single bad Remote Config push corrected after the fact
        // SHALL NOT poison Layer 3 for the 5-min cache window).
        val cached = redisCache.get(cacheKey)?.trim()?.toDoubleOrNull()
        if (cached != null && cached in CLAMP_LOW..CLAMP_HIGH) {
            return cached
        }

        // Tier 2 — Remote Config. Apply the same clamp; out-of-range values trigger
        // a fallback warn AND return the default.
        val rc =
            try {
                remoteConfigClient.fetchDouble(param)
            } catch (t: Throwable) {
                log.warn(
                    "event={} key={} tier=remote_config to=default reason={}",
                    EVENT_THRESHOLD_FALLBACK,
                    param,
                    t.javaClass.simpleName,
                )
                null
            }
        if (rc != null) {
            if (rc in CLAMP_LOW..CLAMP_HIGH) {
                redisCache.set(cacheKey, rc.toString(), cacheTtl)
                return rc
            }
            log.warn(
                "event={} key={} tier=remote_config to=default reason=out_of_range",
                EVENT_THRESHOLD_FALLBACK,
                param,
            )
            redisCache.set(cacheKey, default.toString(), cacheTtl)
            return default
        }

        // Tier 3 — static default. Cache so subsequent calls don't re-fetch from RC.
        redisCache.set(cacheKey, default.toString(), cacheTtl)
        return default
    }

    companion object {
        const val KILL_SWITCH_PARAM: String = "perspective_api_enabled"
        const val HIGH_SCORE_PARAM: String = "perspective_api_high_score_threshold"
        const val FLAG_PARAM: String = "perspective_api_flag_threshold"

        const val DEFAULT_KILL_SWITCH: Boolean = true
        const val DEFAULT_HIGH_SCORE_THRESHOLD: Double = 0.8
        const val DEFAULT_FLAG_THRESHOLD: Double = 0.6

        const val CLAMP_LOW: Double = 0.0
        const val CLAMP_HIGH: Double = 1.0

        const val KILL_SWITCH_CACHE_KEY: String =
            "{scope:perspective_config}:{flag:perspective_api_enabled}"

        const val EVENT_KILL_SWITCH_UNAVAILABLE: String = "perspective_kill_switch_unavailable"
        const val EVENT_THRESHOLD_FALLBACK: String = "perspective_threshold_fallback"

        internal fun thresholdCacheKey(param: String): String = "{scope:perspective_config}:{flag:$param}"
    }
}
