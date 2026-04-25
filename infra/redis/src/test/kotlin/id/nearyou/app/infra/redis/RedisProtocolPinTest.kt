package id.nearyou.app.infra.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.lettuce.core.protocol.ProtocolVersion

/**
 * Regression pin for the Upstash HELLO-handshake incompatibility found while
 * running the like-rate-limit task 9.7 smoke against staging (2026-04-25).
 *
 * Symptom: Lettuce's default RESP3 negotiation sends
 * `HELLO 3 AUTH default <password>` immediately after TCP-connect. Upstash
 * silently drops the connection mid-handshake → Lettuce throws
 * `RedisConnectionException: Connection closed prematurely` → the lazy
 * fail-soft path in `RedisRateLimiter` returns `Allowed(remaining=capacity)`
 * for every request → the rate limit is functionally a no-op in staging /
 * production.
 *
 * Lock: every Lettuce `RedisClient` this project constructs MUST run on
 * [ProtocolVersion.RESP2] so legacy `AUTH default <password>` is sent
 * instead of `HELLO`. If this test ever fails, the rate limit is broken in
 * staging / production by the change that flipped it.
 */
class RedisProtocolPinTest : StringSpec(
    {
        "lettuceClientOptions pins protocol version to RESP2" {
            lettuceClientOptions().protocolVersion shouldBe ProtocolVersion.RESP2
        }
    },
)
