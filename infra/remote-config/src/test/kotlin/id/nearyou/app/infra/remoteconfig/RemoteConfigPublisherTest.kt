package id.nearyou.app.infra.remoteconfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tests for [FirebaseRemoteConfigServerPublisher]. The REST round-trip is
 * replaced with an in-memory [ServerTemplateTransport] fake (mirrors the read
 * side's [ConfigSource] substitution in [RemoteConfigClientTest]) so tests need
 * no real Firebase project — the production binding [HttpServerTemplateTransport]
 * is exercised at staging deploy time via the smoke step.
 */
class RemoteConfigPublisherTest : StringSpec({

    val templateJson =
        """
        {
          "parameters": {
            "search_enabled": {"defaultValue": {"value": "false"}, "valueType": "BOOLEAN"},
            "moderation_match_threshold": {"defaultValue": {"value": "3"}, "valueType": "NUMBER"}
          },
          "version": {"versionNumber": "42"}
        }
        """.trimIndent()

    "fetchServerTemplate parses etag, version, and requested parameter values" {
        val publisher = publisherWith(TemplateHttpResponse(200, "etag-1", templateJson))
        val snapshot = publisher.fetchServerTemplate(setOf("search_enabled", "moderation_match_threshold", "absent_param"))
        snapshot!!.etag shouldBe "etag-1"
        snapshot.version shouldBe "42"
        snapshot.parameters["search_enabled"] shouldBe "false"
        snapshot.parameters["moderation_match_threshold"] shouldBe "3"
        snapshot.parameters["absent_param"].shouldBeNull()
    }

    "fetchServerTemplate returns null on a non-200 status" {
        val publisher = publisherWith(TemplateHttpResponse(500, "etag-1", "{}"))
        publisher.fetchServerTemplate(setOf("search_enabled")).shouldBeNull()
    }

    "fetchServerTemplate returns null when the ETag header is absent" {
        val publisher = publisherWith(TemplateHttpResponse(200, null, templateJson))
        publisher.fetchServerTemplate(setOf("search_enabled")).shouldBeNull()
    }

    "publishServerParameter publishes and returns the new ETag on a matching-version 200" {
        val transport =
            FakeTransport(TemplateHttpResponse(200, "etag-1", templateJson)) { _, _ ->
                TemplateHttpResponse(200, "etag-2", null)
            }
        val result =
            FirebaseRemoteConfigServerPublisher(transport)
                .publishServerParameter("search_enabled", "true", expectedEtag = "etag-1")
        result.shouldBeInstanceOf<PublishResult.Published>()
        (result as PublishResult.Published).newEtag shouldBe "etag-2"
        transport.lastIfMatch shouldBe "etag-1"
        // The PUT body patched the target parameter and preserved the other one.
        val putBody = Json.parseToJsonElement(transport.lastPutBody!!) as JsonObject
        valueOf(putBody, "search_enabled") shouldBe "true"
        valueOf(putBody, "moderation_match_threshold") shouldBe "3"
    }

    "publishServerParameter rejects a stale render ETag without PUTting (no clobber)" {
        val transport = FakeTransport(TemplateHttpResponse(200, "etag-CURRENT", templateJson))
        val result =
            FirebaseRemoteConfigServerPublisher(transport)
                .publishServerParameter("search_enabled", "true", expectedEtag = "etag-STALE")
        result shouldBe PublishResult.StaleVersion
        transport.putCount shouldBe 0
    }

    "publishServerParameter maps a 412 on PUT to StaleVersion (get/put race)" {
        val transport =
            FakeTransport(TemplateHttpResponse(200, "etag-1", templateJson)) { _, _ ->
                TemplateHttpResponse(412, null, null)
            }
        FirebaseRemoteConfigServerPublisher(transport)
            .publishServerParameter("search_enabled", "true", expectedEtag = "etag-1") shouldBe PublishResult.StaleVersion
    }

    "publishServerParameter maps a 403 on GET to WriteUnavailable" {
        val transport = FakeTransport(TemplateHttpResponse(403, null, null))
        FirebaseRemoteConfigServerPublisher(transport)
            .publishServerParameter("search_enabled", "true", expectedEtag = "etag-1") shouldBe PublishResult.WriteUnavailable
    }

    "publishServerParameter maps a 403 on PUT to WriteUnavailable" {
        val transport =
            FakeTransport(TemplateHttpResponse(200, "etag-1", templateJson)) { _, _ ->
                TemplateHttpResponse(403, null, null)
            }
        FirebaseRemoteConfigServerPublisher(transport)
            .publishServerParameter("search_enabled", "true", expectedEtag = "etag-1") shouldBe PublishResult.WriteUnavailable
    }

    "publishServerParameter maps an unexpected PUT status to Failed" {
        val transport =
            FakeTransport(TemplateHttpResponse(200, "etag-1", templateJson)) { _, _ ->
                TemplateHttpResponse(500, null, null)
            }
        val result =
            FirebaseRemoteConfigServerPublisher(transport)
                .publishServerParameter("search_enabled", "true", expectedEtag = "etag-1")
        result.shouldBeInstanceOf<PublishResult.Failed>()
    }

    "patchParameterDefaultValue creates the parameter when absent and preserves others" {
        val patched =
            FirebaseRemoteConfigServerPublisher.patchParameterDefaultValue(templateJson, "attestation_mode", "warn")!!
        val root = Json.parseToJsonElement(patched) as JsonObject
        valueOf(root, "attestation_mode") shouldBe "warn"
        valueOf(root, "search_enabled") shouldBe "false"
        // version block preserved verbatim
        ((root["version"] as JsonObject)["versionNumber"] as JsonPrimitive).content shouldBe "42"
    }

    "NoOpRemoteConfigPublisher is unconfigured, fetches nothing, and writes are unavailable" {
        NoOpRemoteConfigPublisher.isConfigured() shouldBe false
        NoOpRemoteConfigPublisher.fetchServerTemplate(setOf("search_enabled")).shouldBeNull()
        NoOpRemoteConfigPublisher.publishServerParameter("search_enabled", "true", "etag-1") shouldBe
            PublishResult.WriteUnavailable
    }
})

private fun valueOf(
    root: JsonObject,
    parameterName: String,
): String? {
    val parameters = root["parameters"] as? JsonObject ?: return null
    val parameter = parameters[parameterName] as? JsonObject ?: return null
    val default = parameter["defaultValue"] as? JsonObject ?: return null
    return (default["value"] as? JsonPrimitive)?.content
}

private fun publisherWith(getResponse: TemplateHttpResponse): FirebaseRemoteConfigServerPublisher =
    FirebaseRemoteConfigServerPublisher(FakeTransport(getResponse))

private class FakeTransport(
    private val getResponse: TemplateHttpResponse,
    private val putResponder: (body: String, ifMatch: String) -> TemplateHttpResponse = { _, _ ->
        TemplateHttpResponse(200, "etag-new", null)
    },
) : ServerTemplateTransport {
    var putCount = 0
    var lastPutBody: String? = null
    var lastIfMatch: String? = null

    override fun get(): TemplateHttpResponse = getResponse

    override fun put(
        templateJson: String,
        ifMatchEtag: String,
    ): TemplateHttpResponse {
        putCount++
        lastPutBody = templateJson
        lastIfMatch = ifMatchEtag
        return putResponder(templateJson, ifMatchEtag)
    }
}
