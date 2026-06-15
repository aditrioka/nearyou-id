package id.nearyou.app.admin.featureflags

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure value-validation matrix for the Feature Flag Admin catalog
 * (`admin-feature-flags` spec Req "A moderation_match_threshold write is
 * validated to the [1, 10000] range" + Req "A flag write validates the submitted
 * value against the parameter type"). No DB — the route tests only spot-check that
 * a rejected value yields no publish / no audit.
 */
class FeatureFlagCatalogTest : StringSpec({

    fun valid(
        key: String,
        raw: String,
    ): ValueValidation.Valid = FeatureFlagCatalog.validate(key, raw).shouldBeInstanceOf<ValueValidation.Valid>()

    fun invalid(
        key: String,
        raw: String,
    ) = FeatureFlagCatalog.validate(key, raw).shouldBeInstanceOf<ValueValidation.Invalid>()

    "boolean flag accepts true/false case-insensitively, normalized lowercase" {
        valid("search_enabled", "true").normalized shouldBe "true"
        valid("search_enabled", "FALSE").normalized shouldBe "false"
        valid("premium_like_cap_override", " True ").normalized shouldBe "true"
    }

    "boolean flag rejects a non-boolean value" {
        invalid("search_enabled", "maybe")
        invalid("search_enabled", "1")
        invalid("search_enabled", "")
    }

    "attestation_mode accepts each of enforce / warn / off" {
        valid("attestation_mode", "enforce").normalized shouldBe "enforce"
        valid("attestation_mode", "warn").normalized shouldBe "warn"
        valid("attestation_mode", "off").normalized shouldBe "off"
    }

    "attestation_mode rejects an unknown value (case-sensitive enum)" {
        invalid("attestation_mode", "strict")
        invalid("attestation_mode", "ENFORCE")
        invalid("attestation_mode", "")
    }

    "moderation_match_threshold accepts the inclusive bounds and a mid value" {
        valid("moderation_match_threshold", "1").normalized shouldBe "1"
        valid("moderation_match_threshold", "10000").normalized shouldBe "10000"
        valid("moderation_match_threshold", "50").normalized shouldBe "50"
    }

    "moderation_match_threshold rejects out-of-range and non-integer values" {
        invalid("moderation_match_threshold", "0")
        invalid("moderation_match_threshold", "10001")
        invalid("moderation_match_threshold", "-1")
        invalid("moderation_match_threshold", "abc")
        invalid("moderation_match_threshold", "3.5")
    }

    "an unknown or read-only-list parameter is never editable" {
        invalid("does_not_exist", "true")
        invalid("moderation_profanity_list", "[]")
        invalid("moderation_uu_ite_list", "[]")
    }
})
