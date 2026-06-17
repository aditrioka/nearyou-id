package id.nearyou.app.timeline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Pure unit coverage of the symmetric hide-distance rule (hide-distance capability) — no DB.
 * The end-to-end author/viewer/premium gating is covered by NearbyTimelineServiceTest (database).
 */
class EffectiveDistanceMetersTest : StringSpec({

    "both sides off → raw meters pass through" {
        effectiveDistanceMeters(1234.5, authorHidesDistance = false, viewerHidesDistance = false) shouldBe 1234.5
    }

    "author hides → null (suppressed for every viewer)" {
        effectiveDistanceMeters(1234.5, authorHidesDistance = true, viewerHidesDistance = false).shouldBeNull()
    }

    "viewer hides → null (suppressed on every post)" {
        effectiveDistanceMeters(1234.5, authorHidesDistance = false, viewerHidesDistance = true).shouldBeNull()
    }

    "both hide → null" {
        effectiveDistanceMeters(1234.5, authorHidesDistance = true, viewerHidesDistance = true).shouldBeNull()
    }

    "raw value below the 5km floor still passes through unchanged (floor is render-side)" {
        effectiveDistanceMeters(15.0, authorHidesDistance = false, viewerHidesDistance = false) shouldBe 15.0
    }
})
