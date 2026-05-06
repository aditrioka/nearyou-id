package id.nearyou.app.infra.otel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CloudRegionProbeTest : StringSpec({
    "parseRegion strips projects/<num>/regions/ prefix" {
        CloudRegionProbe.parseRegion("projects/123456/regions/asia-southeast1") shouldBe "asia-southeast1"
    }

    "parseRegion accepts a bare region name" {
        CloudRegionProbe.parseRegion("asia-southeast1") shouldBe "asia-southeast1"
    }

    "parseRegion returns null on blank input" {
        CloudRegionProbe.parseRegion("") shouldBe null
        CloudRegionProbe.parseRegion("   ") shouldBe null
    }

    "parseRegion returns null on unrecognized format" {
        CloudRegionProbe.parseRegion("unexpected/path/shape") shouldBe null
        CloudRegionProbe.parseRegion("CapitalLetters") shouldBe null
    }

    "fetch returns 'unknown' when metadata server is unreachable (the local-dev/CI default)" {
        // Local + CI environments cannot reach metadata.google.internal —
        // connect-timeout / DNS failure / network unreachable funnel into
        // 'unknown' per spec. This test confirms the failure-mode contract
        // holds without depending on Cloud Run runtime.
        val region = CloudRegionProbe.fetch()
        region shouldBe "unknown"
    }
})
