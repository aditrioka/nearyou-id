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
            "Detail: Failing row contains (1, halo (dunia), 0101000020E6100000, null)." to
                "Detail: Failing row contains ([redacted]).",
            "geom SRID=4326;POINT(106.8456 -6.2088) bad" to "geom SRID=4326;[redacted] bad",
            "q lat=-6.2088&lng=106.8456 end" to "q lat=[redacted]&lng=[redacted] end",
            "{\"latitude\": -6.2, \"longitude\":106.8}" to "{\"latitude\": [redacted], \"longitude\":[redacted]}",
        ).forEach { (input, expected) -> PiiScrubber.scrub(input) shouldBe expected }
    }

    "non-PII survives" {
        listOf(
            "2026-09-25 12:34:56.789 took 12:05",
            "user 3f2b6c1e-9a4d-4e7b-8c1a-2b3c4d5e6f70 not found",
            "version 1.2.3 build 42",
            "hit checkpoint(3) of 5",
            "event=unhandled_exception method=GET path=/api/v1/posts/nearby",
        ).forEach { PiiScrubber.scrub(it) shouldBe it }
    }
})
