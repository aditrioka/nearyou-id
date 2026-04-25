package id.nearyou.app.infra.redis

import id.nearyou.app.core.domain.ratelimit.RateLimiter
import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Redis-backed [RateLimiter] using a single Lua sliding-window script.
 *
 * **Atomicity:** the entire admit-or-reject decision (prune + count + add OR find-oldest)
 * runs inside one Lua call. Concurrent same-user requests cannot both observe slot
 * `capacity` and both admit — the Lua VM serializes them per-key.
 *
 * **Hash-tag co-location:** keys MUST be passed in `{scope:<value>}:{<axis>:<value>}`
 * form so both segments map to the same Redis Cluster slot — this matters only on
 * Upstash cluster mode (production); standalone dev / CI Redis ignores hash tags.
 * The `RedisHashTagRule` Detekt rule enforces the format at all rate-limit call sites.
 *
 * **Blocking note:** Lettuce's sync API blocks the calling thread on the Netty round-
 * trip. Callers running inside a Ktor coroutine context SHOULD wrap each call with
 * `withContext(Dispatchers.IO)` to keep the dispatcher unblocked. The interface is
 * deliberately non-suspending to match V9 `ReportRateLimiter`'s contract byte-for-
 * byte (the V9 port is the regression gate); pushing the IO-dispatch concern to the
 * call site keeps the interface clean.
 *
 * **TTL vs window semantics:** the Lua script treats `ttl` as both the prune-older-than
 * window AND the Redis key TTL — they're the same value per call. For the daily
 * limiter this means the key dies at the WIB-staggered reset moment; for the burst
 * limiter, the key dies 1 hour after the last write. Either way, an idle bucket is
 * eventually GC'd by Redis, so per-user memory is bounded.
 */
class RedisRateLimiter(
    private val client: RedisClient,
    private val clock: Clock = Clock.systemUTC(),
    private val retryBackoff: Duration = Duration.ofSeconds(30),
) : RateLimiter, AutoCloseable {
    // Connection is lazy + fail-soft: a Redis hiccup (DNS, TCP, auth) at boot or
    // during a request MUST NOT crash the app. Earlier eager `client.connect()` in
    // the constructor crashed Ktor module load when Lettuce's first connect attempt
    // failed → unhandled exception in main → Cloud Run startup probe failed → revision
    // rollback (observed on staging rev nearyou-backend-staging-00033-xj6, 2026-04-25).
    // The proposal called this out as fail-soft intent; this brings the implementation
    // in line.
    @Volatile private var connection: StatefulRedisConnection<String, String>? = null

    @Volatile private var nextAttemptAtMillis: Long = 0L
    private val backoffMillis: Long = retryBackoff.toMillis()

    /**
     * Returns a live [RedisCommands] handle, or null if Redis is currently unreachable.
     * Caller is responsible for fail-soft behavior on null. After a connect failure
     * we suppress reattempts for [retryBackoff] to avoid hammering Redis on every
     * request.
     */
    private fun sync(): RedisCommands<String, String>? {
        connection?.let { existing -> if (existing.isOpen) return existing.sync() }
        if (clock.millis() < nextAttemptAtMillis) return null
        return synchronized(this) {
            connection?.let { existing -> if (existing.isOpen) return@synchronized existing.sync() }
            if (clock.millis() < nextAttemptAtMillis) return@synchronized null
            try {
                val fresh = client.connect()
                connection = fresh
                fresh.sync()
            } catch (e: Exception) {
                logger.warn(
                    "event=redis_connect_failed reason={} message={} fail_soft=true",
                    e.javaClass.simpleName,
                    e.message,
                )
                nextAttemptAtMillis = clock.millis() + backoffMillis
                null
            }
        }
    }

    override fun tryAcquire(
        userId: UUID,
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome {
        val sync = sync() ?: return RateLimiter.Outcome.Allowed(remaining = capacity)
        val nowMs = clock.millis()
        val windowMs = ttl.toMillis()
        val ttlMs = ttl.toMillis()
        val jti = UUID.randomUUID().toString()

        return try {
            @Suppress("UNCHECKED_CAST")
            val result =
                sync.eval<List<Long>>(
                    LUA_TRY_ACQUIRE,
                    ScriptOutputType.MULTI,
                    arrayOf(key),
                    nowMs.toString(),
                    windowMs.toString(),
                    ttlMs.toString(),
                    capacity.toString(),
                    jti,
                )
            val flag = result[0]
            val value = result[1]
            if (flag == 1L) {
                RateLimiter.Outcome.Allowed(remaining = value.toInt())
            } else {
                RateLimiter.Outcome.RateLimited(retryAfterSeconds = value)
            }
        } catch (e: Exception) {
            logger.warn(
                "event=redis_acquire_failed key={} reason={} message={} fail_soft=true",
                key,
                e.javaClass.simpleName,
                e.message,
            )
            invalidateOnRuntimeFailure()
            RateLimiter.Outcome.Allowed(remaining = capacity)
        }
    }

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) {
        val sync = sync() ?: return
        try {
            // ZPOPMAX returns nil for an empty/missing key — the no-op contract holds
            // automatically. Single-call atomic on the Redis side.
            sync.zpopmax(key, 1)
        } catch (e: Exception) {
            logger.warn(
                "event=redis_release_failed key={} reason={} message={}",
                key,
                e.javaClass.simpleName,
                e.message,
            )
            invalidateOnRuntimeFailure()
        }
    }

    private fun invalidateOnRuntimeFailure() {
        synchronized(this) {
            connection?.runCatching { close() }
            connection = null
            nextAttemptAtMillis = clock.millis() + backoffMillis
        }
    }

    /**
     * Closes the underlying Lettuce connection if one was opened. Safe to call
     * multiple times. The underlying [RedisClient] is NOT shutdown here — the caller
     * owns the client lifecycle (typically registered as a Koin singleton, shutdown
     * at server stop).
     */
    override fun close() {
        synchronized(this) {
            connection?.close()
            connection = null
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RedisRateLimiter::class.java)

        /**
         * Lua sliding-window admit-or-reject script.
         *
         * KEYS[1] = the rate-limit key (full hash-tag form).
         * ARGV[1] = now_ms (current server time millis).
         * ARGV[2] = window_ms (prune-older-than threshold).
         * ARGV[3] = ttl_ms (PEXPIRE the key for this many ms after admit).
         * ARGV[4] = capacity (slot count).
         * ARGV[5] = jti (unique sorted-set member; per-call random UUID prevents
         *           same-millisecond collision on `ZADD` from the same user).
         *
         * Returns: a 2-element MULTI bulk reply.
         *   {1, remaining}            — admitted; remaining = capacity - count - 1.
         *   {0, retry_after_seconds}  — rejected; seconds until oldest counted entry
         *                              ages out, ceiling, coerced to >= 1.
         */
        private val LUA_TRY_ACQUIRE =
            """
            local key = KEYS[1]
            local now_ms = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local ttl_ms = tonumber(ARGV[3])
            local capacity = tonumber(ARGV[4])
            local jti = ARGV[5]

            -- Prune entries older than the window (inclusive of the boundary).
            redis.call('ZREMRANGEBYSCORE', key, 0, now_ms - window_ms)

            local count = redis.call('ZCARD', key)
            if count >= capacity then
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                local oldest_ms = tonumber(oldest[2])
                local retry_after_ms = oldest_ms + window_ms - now_ms
                local retry_after_seconds = math.ceil(retry_after_ms / 1000)
                if retry_after_seconds < 1 then retry_after_seconds = 1 end
                return {0, retry_after_seconds}
            end

            redis.call('ZADD', key, now_ms, jti)
            redis.call('PEXPIRE', key, ttl_ms)
            local remaining = capacity - count - 1
            return {1, remaining}
            """.trimIndent()
    }
}
