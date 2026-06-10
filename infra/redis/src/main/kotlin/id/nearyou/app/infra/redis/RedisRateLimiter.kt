package id.nearyou.app.infra.redis

import id.nearyou.app.core.domain.ratelimit.RateLimiter
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.security.MessageDigest
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
 * **TTL vs window semantics:** rolling keys (hourly/burst/session) use the sliding-
 * window script where `ttl` is both the prune-older-than window AND the Redis key
 * TTL. Keys carrying [RateLimiter.FIXED_WINDOW_KEY_MARKER] (`_day}` — every daily
 * cap) use a FIXED-WINDOW `INCR`/`PEXPIRE` script instead: the count never prunes
 * mid-window and the bucket resets only when the key expires at the WIB-staggered
 * reset. The previous always-sliding behavior pruned daily entries after
 * `ttl` = time-REMAINING-to-reset (not 24 h), so late-day usage refilled daily
 * caps several-fold (2026-06-10 audit, finding 02-H4). Either way an idle bucket
 * is eventually GC'd by Redis, so per-user memory is bounded.
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

    // SHA1 of LUA_TRY_ACQUIRE — precomputed once at class construction so both
    // `tryAcquire` and `tryAcquireByKey` invoke the same EVALSHA cache slot. The
    // rate-limit-infrastructure MODIFIED spec scenario "tryAcquireByKey delegates
    // to the same Lua script as tryAcquire" mandates SHA1 equality across methods;
    // sharing this single field is the structural enforcement.
    @Suppress("MemberVisibilityCanBePrivate")
    internal val scriptSha: String = sha1Hex(LUA_TRY_ACQUIRE)

    // Fixed-window companion script for `_day}` keys (02-H4). Same EVALSHA-with-
    // EVAL-fallback discipline as the sliding script.
    internal val fixedWindowScriptSha: String = sha1Hex(LUA_TRY_ACQUIRE_FIXED_WINDOW)
    internal val releaseFixedWindowScriptSha: String = sha1Hex(LUA_RELEASE_FIXED_WINDOW)

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
    ): RateLimiter.Outcome = admit(key = key, capacity = capacity, ttl = ttl, telemetryUserId = userId)

    override fun tryAcquireByKey(
        key: String,
        capacity: Int,
        ttl: Duration,
    ): RateLimiter.Outcome = admit(key = key, capacity = capacity, ttl = ttl, telemetryUserId = null)

    /**
     * Shared admit-or-reject path for both [tryAcquire] (user-axis) and
     * [tryAcquireByKey] (axis-agnostic). Identical Lua-script invocation; only
     * the telemetry tag differs — `userId` field is logged when present, absent
     * for key-axis calls.
     */
    private fun admit(
        key: String,
        capacity: Int,
        ttl: Duration,
        telemetryUserId: UUID?,
    ): RateLimiter.Outcome {
        val sync = sync() ?: return RateLimiter.Outcome.Allowed(remaining = capacity)
        val ttlMs = ttl.toMillis()
        val fixedWindow = RateLimiter.isFixedWindowKey(key)

        return try {
            val result =
                if (fixedWindow) {
                    evalShaWithFallback(
                        sync = sync,
                        sha = fixedWindowScriptSha,
                        script = LUA_TRY_ACQUIRE_FIXED_WINDOW,
                        keys = arrayOf(key),
                        args = arrayOf(ttlMs.toString(), capacity.toString()),
                    )
                } else {
                    val nowMs = clock.millis()
                    val windowMs = ttlMs
                    val jti = UUID.randomUUID().toString()
                    evalShaWithFallback(
                        sync = sync,
                        sha = scriptSha,
                        script = LUA_TRY_ACQUIRE,
                        keys = arrayOf(key),
                        args =
                            arrayOf(
                                nowMs.toString(),
                                windowMs.toString(),
                                ttlMs.toString(),
                                capacity.toString(),
                                jti,
                            ),
                    )
                }
            val flag = result[0]
            val value = result[1]
            if (flag == 1L) {
                if (telemetryUserId != null) {
                    logger.debug(
                        "event=rate_limit_check user_id={} key={} outcome=allowed remaining={}",
                        telemetryUserId,
                        key,
                        value,
                    )
                } else {
                    logger.debug(
                        "event=rate_limit_check key={} outcome=allowed remaining={}",
                        key,
                        value,
                    )
                }
                RateLimiter.Outcome.Allowed(remaining = value.toInt())
            } else {
                if (telemetryUserId != null) {
                    logger.debug(
                        "event=rate_limit_check user_id={} key={} outcome=rate_limited retry_after_seconds={}",
                        telemetryUserId,
                        key,
                        value,
                    )
                } else {
                    logger.debug(
                        "event=rate_limit_check key={} outcome=rate_limited retry_after_seconds={}",
                        key,
                        value,
                    )
                }
                RateLimiter.Outcome.RateLimited(retryAfterSeconds = value)
            }
        } catch (e: Exception) {
            // Telemetry: emit `user_id=...` only for user-axis calls; key-axis
            // calls log `key=...` only (per the rate-limit-infrastructure
            // MODIFIED scenario "tryAcquireByKey omits userId from telemetry").
            if (telemetryUserId != null) {
                logger.warn(
                    "event=redis_acquire_failed user_id={} key={} reason={} message={} fail_soft=true",
                    telemetryUserId,
                    key,
                    e.javaClass.simpleName,
                    e.message,
                )
            } else {
                logger.warn(
                    "event=redis_acquire_failed key={} reason={} message={} fail_soft=true",
                    key,
                    e.javaClass.simpleName,
                    e.message,
                )
            }
            invalidateOnRuntimeFailure()
            RateLimiter.Outcome.Allowed(remaining = capacity)
        }
    }

    /**
     * Try `EVALSHA scriptSha` first (cache-hit on the Redis side). If Redis
     * raises `NOSCRIPT` (script flushed, fresh server, etc.), fall back to
     * `EVAL` which uploads the script and executes in one round-trip; subsequent
     * calls hit the cache again. This is the canonical Lettuce pattern.
     */
    @Suppress("UNCHECKED_CAST")
    private fun evalShaWithFallback(
        sync: RedisCommands<String, String>,
        sha: String,
        script: String,
        keys: Array<String>,
        args: Array<String>,
    ): List<Long> =
        try {
            sync.evalsha<List<Long>>(sha, ScriptOutputType.MULTI, keys, *args)
        } catch (e: RedisNoScriptException) {
            // Cache miss on the Redis side; reload via EVAL. No need to manually
            // call SCRIPT LOAD — EVAL implicitly caches the script.
            sync.eval<List<Long>>(script, ScriptOutputType.MULTI, keys, *args)
        }

    override fun releaseMostRecent(
        userId: UUID,
        key: String,
    ) {
        val sync = sync() ?: return
        try {
            if (RateLimiter.isFixedWindowKey(key)) {
                // Fixed-window buckets are plain INCR counters: release = atomic
                // DECR floored at 0 (no-op on empty/missing — contract preserved).
                try {
                    sync.evalsha<Long>(releaseFixedWindowScriptSha, ScriptOutputType.INTEGER, arrayOf(key))
                } catch (_: RedisNoScriptException) {
                    sync.eval<Long>(LUA_RELEASE_FIXED_WINDOW, ScriptOutputType.INTEGER, arrayOf(key))
                }
                return
            }
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

        /**
         * Fixed-window admit-or-reject for `_day}` keys (02-H4).
         *
         * KEYS[1] = the rate-limit key. ARGV[1] = ttl_ms (time to the WIB-staggered
         * reset, set ONLY when the bucket is created — first event of the window).
         * ARGV[2] = capacity.
         *
         * Returns the same 2-element protocol as the sliding script:
         *   {1, remaining}            — admitted; remaining = capacity - count.
         *   {0, retry_after_seconds}  — rejected; seconds until the bucket expires
         *                               (= the daily reset), ceiling, >= 1.
         *
         * Rejected attempts still INCR (count may exceed capacity) — harmless: the
         * bucket resets wholesale at expiry, and release (DECR floored at 0) is only
         * ever called after a successful acquire per the interface contract.
         */
        private val LUA_TRY_ACQUIRE_FIXED_WINDOW =
            """
            local key = KEYS[1]
            local ttl_ms = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])

            local count = redis.call('INCR', key)
            if count == 1 then
                redis.call('PEXPIRE', key, ttl_ms)
            end
            if count > capacity then
                local pttl = redis.call('PTTL', key)
                if pttl < 0 then
                    -- Lost TTL (manual key surgery / persistence edge): re-arm so the
                    -- bucket cannot outlive its window forever.
                    redis.call('PEXPIRE', key, ttl_ms)
                    pttl = ttl_ms
                end
                local retry_after_seconds = math.ceil(pttl / 1000)
                if retry_after_seconds < 1 then retry_after_seconds = 1 end
                return {0, retry_after_seconds}
            end
            return {1, capacity - count}
            """.trimIndent()

        /** DECR floored at 0; preserves the key TTL. Returns the pre-release count. */
        private val LUA_RELEASE_FIXED_WINDOW =
            """
            local c = tonumber(redis.call('GET', KEYS[1]) or '0')
            if c > 0 then
                redis.call('DECR', KEYS[1])
            end
            return c
            """.trimIndent()

        private fun sha1Hex(s: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
