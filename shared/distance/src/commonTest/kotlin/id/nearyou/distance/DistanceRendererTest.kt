package id.nearyou.distance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DistanceRendererTest : StringSpec({
    "4500m fuzz-floored to 5km" {
        DistanceRenderer.render(4500.0) shouldBe "5km"
    }

    "5000m boundary stays at 5km" {
        DistanceRenderer.render(5000.0) shouldBe "5km"
    }

    "7400m rounds down to 7km" {
        DistanceRenderer.render(7400.0) shouldBe "7km"
    }

    "7600m rounds up to 8km" {
        DistanceRenderer.render(7600.0) shouldBe "8km"
    }

    "12800m rounds to 13km (spot check)" {
        DistanceRenderer.render(12800.0) shouldBe "13km"
    }
})
