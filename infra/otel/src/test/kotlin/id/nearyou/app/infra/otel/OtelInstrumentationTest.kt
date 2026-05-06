package id.nearyou.app.infra.otel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class OtelInstrumentationTest : StringSpec({
    "sanitizeRedisUri strips userinfo from redis://" {
        OtelInstrumentation.sanitizeRedisUri("redis://user:password@host:6379/0") shouldBe
            "redis://host:6379/0"
    }

    "sanitizeRedisUri strips userinfo from rediss://" {
        OtelInstrumentation.sanitizeRedisUri("rediss://default:secret@host:6380/0") shouldBe
            "rediss://host:6380/0"
    }

    "sanitizeRedisUri leaves URIs without userinfo unchanged" {
        OtelInstrumentation.sanitizeRedisUri("redis://host:6379/0") shouldBe "redis://host:6379/0"
        OtelInstrumentation.sanitizeRedisUri("rediss://host:6380") shouldBe "rediss://host:6380"
    }

    "sanitizeRedisUri returns input unchanged on parse failure" {
        // Bad URIs short-circuit to the input rather than throwing —
        // bootstrap MUST NOT crash on a malformed Redis URL.
        val malformed = ":::not-a-uri:::"
        OtelInstrumentation.sanitizeRedisUri(malformed) shouldBe malformed
    }
})
