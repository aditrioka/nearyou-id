package id.nearyou.app.infra.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Generic string-keyed Redis cache with TTL semantics. Public surface is two methods:
 * `get(key)` and `set(key, value, ttl)`. Both fail-soft — a Redis outage returns
 * `null` from `get` (callers cascade to lower tiers) and is silently swallowed on
 * `set` (the next call refreshes the cache; missing a write is acceptable).
 *
 * The Lettuce [RedisClient] is owned by callers (the same client backs
 * [RedisRateLimiter]); this class wraps a thin `GET`/`SET` API around the existing
 * connection. Hash-tag-formatted keys (`{scope:<value>}:{<axis>:<value>}`) are the
 * caller's responsibility, enforced by the `RedisHashTagRule` Detekt rule at the
 * call site (e.g., `:backend:ktor` `ModerationListLoader`).
 *
 * **Blocking note:** Lettuce's sync API blocks the calling thread. Callers running
 * inside a Ktor coroutine context SHOULD wrap each call in `withContext(Dispatchers.IO)`
 * — same convention as [RedisRateLimiter], which is non-suspending for compat with
 * V9 `ReportRateLimiter`.
 */
interface RedisStringCache {
    /** Returns the cached value, or `null` on miss / Redis unavailability. */
    fun get(key: String): String?

    /** Caches [value] at [key] with a fixed [ttl]. Silently swallows Redis errors. */
    fun set(
        key: String,
        value: String,
        ttl: Duration,
    )
}

/**
 * Lettuce-backed [RedisStringCache]. Owns connection-recovery via the same
 * `nextAttemptAtMillis` retry-suppression pattern as [RedisRateLimiter] so a Redis
 * outage doesn't hammer reconnect attempts on every cache call.
 *
 * Constructor takes [RedisClient] (NOT a URL) so the same client backs both this
 * cache and [RedisRateLimiter] in a single connection pool.
 */
class LettuceRedisStringCache(
    private val client: RedisClient,
    private val clock: Clock = Clock.systemUTC(),
    private val retryBackoff: Duration = Duration.ofSeconds(30),
) : RedisStringCache, AutoCloseable {
    @Volatile private var connection: StatefulRedisConnection<String, String>? = null

    @Volatile private var nextAttemptAtMillis: Long = 0L
    private val backoffMillis: Long = retryBackoff.toMillis()

    private val log = LoggerFactory.getLogger(LettuceRedisStringCache::class.java)

    private fun sync(): io.lettuce.core.api.sync.RedisCommands<String, String>? {
        connection?.let { existing -> if (existing.isOpen) return existing.sync() }
        if (clock.millis() < nextAttemptAtMillis) return null
        return synchronized(this) {
            connection?.let { existing -> if (existing.isOpen) return@synchronized existing.sync() }
            if (clock.millis() < nextAttemptAtMillis) return@synchronized null
            try {
                val fresh = client.connect()
                connection = fresh
                fresh.sync()
            } catch (t: Throwable) {
                log.warn(
                    "event=redis_cache_connect_failed reason={} fail_soft=true",
                    t.javaClass.simpleName,
                )
                nextAttemptAtMillis = clock.millis() + backoffMillis
                null
            }
        }
    }

    override fun get(key: String): String? {
        val cmds = sync() ?: return null
        return try {
            cmds.get(key)
        } catch (t: Throwable) {
            log.warn(
                "event=redis_cache_get_failed key={} reason={}",
                key,
                t.javaClass.simpleName,
            )
            null
        }
    }

    override fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) {
        val cmds = sync() ?: return
        try {
            cmds.set(key, value, SetArgs.Builder.ex(ttl.seconds))
        } catch (t: Throwable) {
            log.warn(
                "event=redis_cache_set_failed key={} reason={}",
                key,
                t.javaClass.simpleName,
            )
        }
    }

    override fun close() {
        synchronized(this) {
            connection?.close()
            connection = null
        }
    }
}

/**
 * No-op [RedisStringCache] used in dev/test profiles when `REDIS_URL` is unset.
 * Mirrors the [NoOpRateLimiter] pattern — `get` always returns null (forces the
 * caller's lower-tier fallback), `set` is a no-op.
 */
class NoOpRedisStringCache : RedisStringCache {
    override fun get(key: String): String? = null

    override fun set(
        key: String,
        value: String,
        ttl: Duration,
    ) = Unit
}

/**
 * Convenience factory mirroring [redisRateLimiterFromUrl] / [lettuceRedisProbeFromUrl] —
 * accepts a Redis URL, wires the OTel-instrumented Lettuce client, returns a cache.
 */
fun lettuceRedisStringCacheFromUrl(
    url: String,
    tracingEnabled: Boolean = true,
    openTelemetry: io.opentelemetry.api.OpenTelemetry? = null,
): LettuceRedisStringCache {
    val redisUri = io.lettuce.core.RedisURI.create(url)
    val client =
        if (tracingEnabled) {
            val resources =
                if (openTelemetry != null) {
                    id.nearyou.app.infra.otel.OtelInstrumentation.lettuceClientResources(openTelemetry)
                } else {
                    id.nearyou.app.infra.otel.OtelInstrumentation.lettuceClientResources()
                }
            RedisClient.create(resources, redisUri)
        } else {
            RedisClient.create(redisUri)
        }
    return LettuceRedisStringCache(client)
}
