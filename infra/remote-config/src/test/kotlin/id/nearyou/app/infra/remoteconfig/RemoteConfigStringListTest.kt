package id.nearyou.app.infra.remoteconfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Codec + extension tests for the moderation-wordlist string-list seam
 * (`admin-moderation-wordlist-editor` task 2.2): [RemoteConfigStringList] encodes
 * the canonical JSON-array shape the moderation reader parses, and
 * [publishServerStringList] delegates to [RemoteConfigPublisher.publishServerParameter]
 * with that encoding (the single publish call = the scope-boundary spy point).
 */
class RemoteConfigStringListTest : StringSpec({

    "encode produces a JSON string array and round-trips through decode" {
        val entries = listOf("anjing", "babi", "kata#hash")
        val encoded = RemoteConfigStringList.encode(entries)
        encoded shouldBe """["anjing","babi","kata#hash"]"""
        RemoteConfigStringList.decode(encoded)!!.shouldContainExactly("anjing", "babi", "kata#hash")
    }

    "encode of an empty list is an empty JSON array" {
        RemoteConfigStringList.encode(emptyList()) shouldBe "[]"
    }

    "decode returns null for null / blank / malformed / non-array values" {
        RemoteConfigStringList.decode(null).shouldBeNull()
        RemoteConfigStringList.decode("   ").shouldBeNull()
        RemoteConfigStringList.decode("not-json").shouldBeNull()
        RemoteConfigStringList.decode("""{"a":1}""").shouldBeNull()
        RemoteConfigStringList.decode("""["ok", 42]""").shouldBeNull() // a non-string element
    }

    "publishServerStringList delegates to publishServerParameter with the encoded JSON for the named param only" {
        val fake = CapturingPublisher()
        val result = fake.publishServerStringList("moderation_profanity_list", listOf("a", "b"), expectedEtag = "etag-1")

        result shouldBe PublishResult.Published("etag-1+")
        fake.calls shouldContainExactly listOf(Triple("moderation_profanity_list", """["a","b"]""", "etag-1"))
    }
})

/** Records every [publishServerParameter] call so the delegation + scope boundary are assertable. */
private class CapturingPublisher : RemoteConfigPublisher {
    val calls = mutableListOf<Triple<String, String, String>>()

    override fun isConfigured(): Boolean = true

    override fun fetchServerTemplate(parameterNames: Set<String>): ServerTemplateSnapshot? =
        ServerTemplateSnapshot(etag = "etag-1", version = "1", parameters = parameterNames.associateWith { null })

    override fun publishServerParameter(
        parameterName: String,
        rawValue: String,
        expectedEtag: String,
    ): PublishResult {
        calls += Triple(parameterName, rawValue, expectedEtag)
        return PublishResult.Published("$expectedEtag+")
    }
}
