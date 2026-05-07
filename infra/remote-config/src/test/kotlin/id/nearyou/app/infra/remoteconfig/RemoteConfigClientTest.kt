package id.nearyou.app.infra.remoteconfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [FirebaseRemoteConfigClient]. The Firebase SDK round-trip
 * (`getServerTemplate(...)` + `evaluate()`) is replaced with an in-memory
 * [ConfigSource] lambda so tests do not require a real Firebase project. The
 * production binding [FirebaseServerConfigSource] (which actually hits Firebase)
 * is exercised at staging deploy time via the smoke script.
 */
class RemoteConfigClientTest : StringSpec({

    "Successful string-array fetch parses JSON" {
        val client = clientWith("moderation_profanity_list" to """["a", "b", "c"]""")
        client.fetchStringList("moderation_profanity_list") shouldBe listOf("a", "b", "c")
    }

    "Parameter missing returns null" {
        val client = clientWith()
        client.fetchStringList("moderation_profanity_list").shouldBeNull()
        client.fetchInt("moderation_match_threshold").shouldBeNull()
        client.fetchBoolean("flag_x").shouldBeNull()
    }

    "Malformed JSON for string-array returns null" {
        val client = clientWith("moderation_profanity_list" to "not-an-array")
        client.fetchStringList("moderation_profanity_list").shouldBeNull()
    }

    "Object instead of array returns null" {
        val client = clientWith("moderation_profanity_list" to """{"key":"value"}""")
        client.fetchStringList("moderation_profanity_list").shouldBeNull()
    }

    "Array of non-strings returns null" {
        val client = clientWith("moderation_profanity_list" to """[1, 2, 3]""")
        client.fetchStringList("moderation_profanity_list").shouldBeNull()
    }

    "fetchInt parses integer value" {
        val client = clientWith("moderation_match_threshold" to "5")
        client.fetchInt("moderation_match_threshold") shouldBe 5
    }

    "fetchInt returns null for non-numeric" {
        val client = clientWith("moderation_match_threshold" to "abc")
        client.fetchInt("moderation_match_threshold").shouldBeNull()
    }

    "fetchBoolean parses true/false case-insensitively" {
        val client =
            clientWith(
                "flag_a" to "true",
                "flag_b" to "FALSE",
                "flag_c" to "True",
                "flag_d" to "yes",
            )
        client.fetchBoolean("flag_a") shouldBe true
        client.fetchBoolean("flag_b") shouldBe false
        client.fetchBoolean("flag_c") shouldBe true
        // The Firebase SDK's asBoolean treats "yes" as truthy, but our parser is strict
        // ("true"/"false" only). This is a deliberate convention to keep flag values
        // unambiguous at the operator-edit time.
        client.fetchBoolean("flag_d").shouldBeNull()
    }

    "ConfigSource returning null is treated as unset across all three fetch methods" {
        // Equivalent to a Server template parameter with ValueSource.STATIC: the
        // FirebaseServerConfigSource returns null → all type-coerced fetches return null.
        val client = FirebaseRemoteConfigClient(ConfigSource { null })
        client.fetchStringList("any").shouldBeNull()
        client.fetchInt("any").shouldBeNull()
        client.fetchBoolean("any").shouldBeNull()
    }

    "ConfigSource exception is caught at the source layer; client treats result as null" {
        // The FirebaseServerConfigSource catches all SDK throwables and returns null,
        // so by contract callers never see exceptions. We simulate the post-catch null
        // here; the actual catch behavior is tested in the production binding.
        val client = FirebaseRemoteConfigClient(ConfigSource { null })
        client.fetchStringList("any").shouldBeNull()
    }

    "Whitespace-trimmed integer parses" {
        val client = clientWith("moderation_match_threshold" to "  7  ")
        client.fetchInt("moderation_match_threshold") shouldBe 7
    }

    "Empty string-array parameter parses as empty list (not null)" {
        // Distinguishes "parameter set to empty array" from "parameter unset". The loader's
        // 4-tier cascade treats empty list as "operator-cleared" (cascade to next tier);
        // null = "unreachable" (also cascade). Both behaviors are correct, but the parsing
        // distinction matters for cascade-event reason field ("empty" vs "parse").
        val client = clientWith("moderation_profanity_list" to "[]")
        client.fetchStringList("moderation_profanity_list") shouldBe emptyList()
    }
})

private fun clientWith(vararg pairs: Pair<String, String>): FirebaseRemoteConfigClient {
    val map = pairs.toMap()
    return FirebaseRemoteConfigClient(ConfigSource { name -> map[name] })
}
