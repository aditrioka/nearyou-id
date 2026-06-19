package id.nearyou.app.infra.cloudvision

import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood.LIKELY
import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood.POSSIBLE
import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood.UNLIKELY
import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood.VERY_LIKELY
import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood.VERY_UNLIKELY
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ImageModeratorTest : StringSpec({

    fun verdict(
        adult: SafeSearchLikelihood = VERY_UNLIKELY,
        violence: SafeSearchLikelihood = VERY_UNLIKELY,
        racy: SafeSearchLikelihood = VERY_UNLIKELY,
    ) = SafeSearchVerdict(adult, violence, racy)

    "isExplicit true when adult is LIKELY" {
        verdict(adult = LIKELY).isExplicit.shouldBeTrue()
    }

    "isExplicit true when violence is VERY_LIKELY" {
        verdict(violence = VERY_LIKELY).isExplicit.shouldBeTrue()
    }

    "isExplicit true when racy is LIKELY (racy is in the reject set — design D2)" {
        verdict(racy = LIKELY).isExplicit.shouldBeTrue()
    }

    "isExplicit false when the highest category is POSSIBLE" {
        verdict(adult = POSSIBLE, violence = POSSIBLE, racy = POSSIBLE).isExplicit.shouldBeFalse()
    }

    "isExplicit false when all UNLIKELY or lower" {
        verdict(adult = UNLIKELY, violence = VERY_UNLIKELY, racy = UNLIKELY).isExplicit.shouldBeFalse()
    }

    "NoOpImageModerator reports not configured" {
        NoOpImageModerator.isConfigured().shouldBeFalse()
    }

    "factory returns NoOp on null or blank service-account JSON" {
        imageModerator(null).isConfigured().shouldBeFalse()
        imageModerator("").isConfigured().shouldBeFalse()
        imageModerator("   ").isConfigured().shouldBeFalse()
    }

    "factory returns the Vision-backed moderator on a non-blank credential" {
        val moderator = imageModerator("{\"type\":\"service_account\"}")
        moderator.isConfigured().shouldBeTrue()
        moderator.shouldBeInstanceOf<GoogleCloudVisionImageModerator>()
    }

    "all SafeSearchLikelihood values round-trip the explicit threshold consistently" {
        SafeSearchLikelihood.entries.forEach { l ->
            val explicit = verdict(adult = l).isExplicit
            explicit shouldBe (l == LIKELY || l == VERY_LIKELY)
        }
    }
})
