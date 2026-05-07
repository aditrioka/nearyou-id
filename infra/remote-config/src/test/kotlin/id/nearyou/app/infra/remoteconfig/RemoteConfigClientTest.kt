package id.nearyou.app.infra.remoteconfig

import com.google.firebase.remoteconfig.Parameter
import com.google.firebase.remoteconfig.ParameterValue
import com.google.firebase.remoteconfig.Template
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [FirebaseRemoteConfigClient]. The Firebase SDK's `getTemplate()` round-trip
 * is replaced with an in-memory [TemplateProvider] lambda so tests do not require a
 * real Firebase project. The vendor types (`Template`, `Parameter`, `ParameterValue`)
 * have public constructors and setters in Admin SDK 9.x — we build them directly.
 */
class RemoteConfigClientTest : StringSpec({

    "Successful string-array fetch parses JSON" {
        val template = templateWith("moderation_profanity_list" to """["a", "b", "c"]""")
        val client = FirebaseRemoteConfigClient { template }
        client.fetchStringList("moderation_profanity_list") shouldBe listOf("a", "b", "c")
    }

    "Parameter missing returns null" {
        val template = templateWith()
        val client = FirebaseRemoteConfigClient { template }
        client.fetchStringList("moderation_profanity_list").shouldBeNull()
        client.fetchInt("moderation_match_threshold").shouldBeNull()
        client.fetchBoolean("flag_x").shouldBeNull()
    }

    "Malformed JSON for string-array returns null" {
        val template = templateWith("moderation_profanity_list" to "not-an-array")
        val client = FirebaseRemoteConfigClient { template }
        client.fetchStringList("moderation_profanity_list").shouldBeNull()
    }

    "Object instead of array returns null" {
        val template = templateWith("moderation_profanity_list" to """{"key":"value"}""")
        val client = FirebaseRemoteConfigClient { template }
        client.fetchStringList("moderation_profanity_list").shouldBeNull()
    }

    "Array of non-strings returns null" {
        val template = templateWith("moderation_profanity_list" to """[1, 2, 3]""")
        val client = FirebaseRemoteConfigClient { template }
        client.fetchStringList("moderation_profanity_list").shouldBeNull()
    }

    "fetchInt parses integer value" {
        val template = templateWith("moderation_match_threshold" to "5")
        val client = FirebaseRemoteConfigClient { template }
        client.fetchInt("moderation_match_threshold") shouldBe 5
    }

    "fetchInt returns null for non-numeric" {
        val template = templateWith("moderation_match_threshold" to "abc")
        val client = FirebaseRemoteConfigClient { template }
        client.fetchInt("moderation_match_threshold").shouldBeNull()
    }

    "fetchBoolean parses true/false case-insensitively" {
        val template =
            templateWith(
                "flag_a" to "true",
                "flag_b" to "FALSE",
                "flag_c" to "True",
                "flag_d" to "yes",
            )
        val client = FirebaseRemoteConfigClient { template }
        client.fetchBoolean("flag_a") shouldBe true
        client.fetchBoolean("flag_b") shouldBe false
        client.fetchBoolean("flag_c") shouldBe true
        client.fetchBoolean("flag_d").shouldBeNull()
    }

    "InAppDefault parameter is treated as unset" {
        val template = Template("etag-test")
        val parameter = Parameter().setDefaultValue(ParameterValue.inAppDefault())
        template.setParameters(mapOf("key" to parameter))
        val client = FirebaseRemoteConfigClient { template }
        client.fetchStringList("key").shouldBeNull()
    }

    "templateProvider exception returns null without propagating" {
        val client = FirebaseRemoteConfigClient { throw RuntimeException("simulated network failure") }
        client.fetchStringList("any").shouldBeNull()
        client.fetchInt("any").shouldBeNull()
        client.fetchBoolean("any").shouldBeNull()
    }

    "Named-FirebaseApp constant is distinct from FCM's nearyou-default" {
        // Confirms the design.md D3 isolation requirement: :infra:remote-config and
        // :infra:fcm own DIFFERENT FirebaseApp instances even though they share the
        // same firebase-admin-sa secret slot. The :infra:fcm constant
        // NEARYOU_FIREBASE_APP_NAME = "nearyou-default" lives in
        // infra/fcm/.../FirebaseAdminInit.kt; we don't import it across module
        // boundaries here since :infra:remote-config does NOT depend on :infra:fcm
        // (each owns its own FirebaseApp). The hardcoded string check below is the
        // structural assertion that prevents either side from drifting.
        NEARYOU_REMOTE_CONFIG_APP_NAME shouldBe "nearyou-rc"
        // NEARYOU_FIREBASE_APP_NAME == "nearyou-default" is asserted by :infra:fcm tests.
        (NEARYOU_REMOTE_CONFIG_APP_NAME == "nearyou-default") shouldBe false
    }

    "Whitespace-trimmed integer parses" {
        val template = templateWith("moderation_match_threshold" to "  7  ")
        val client = FirebaseRemoteConfigClient { template }
        client.fetchInt("moderation_match_threshold") shouldBe 7
    }
})

private fun templateWith(vararg pairs: Pair<String, String>): Template {
    val template = Template("etag-test")
    val parameters =
        pairs.associate { (key, value) ->
            key to Parameter().setDefaultValue(ParameterValue.of(value))
        }
    template.setParameters(parameters)
    return template
}
