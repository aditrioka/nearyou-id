package id.nearyou.app.infra.sentryjvm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PiiScrubberTest : StringSpec({
    "each PII shape is redacted" {
        mapOf(
            "loc -6.2088, 106.8456 end" to "loc [redacted] end",
            "Authorization: Bearer abc.DEF_123-x=" to "Authorization: [redacted]",
            "t=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig" to "t=[redacted]",
            "from someone.name+tag@mail.example.co.id" to "from [redacted]",
            "peer 203.0.113.9:443" to "peer [redacted]:443",
            "peer 2001:db8:85a3:0:0:8a2e:370:7334" to "peer [redacted]",
            "Detail: Key (username)=(budi_01) already exists." to "Detail: Key (username)=([redacted]) already exists.",
        ).forEach { (input, expected) -> PiiScrubber.scrub(input) shouldBe expected }
    }

    "non-PII survives" {
        listOf(
            "2026-09-25 12:34:56.789 took 12:05",
            "user 3f2b6c1e-9a4d-4e7b-8c1a-2b3c4d5e6f70 not found",
            "version 1.2.3 build 42",
            "event=unhandled_exception method=GET path=/api/v1/posts/nearby",
        ).forEach { PiiScrubber.scrub(it) shouldBe it }
    }
})
