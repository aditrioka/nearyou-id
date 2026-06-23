package id.nearyou.app.infra.revenuecatapi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent

class ReferralEntitlementGranterTest : StringSpec({

    "NoOp granter is unconfigured and returns NotConfigured" {
        NoOpReferralEntitlementGranter.isConfigured() shouldBe false
        NoOpReferralEntitlementGranter.grant(GrantRequest("u", "premium", 1L, "d")) shouldBe GrantResult.NotConfigured
    }

    "factory returns NoOp when the key is null or blank" {
        referralEntitlementGranter(null) shouldBe NoOpReferralEntitlementGranter
        referralEntitlementGranter("") shouldBe NoOpReferralEntitlementGranter
        referralEntitlementGranter("   ") shouldBe NoOpReferralEntitlementGranter
    }

    "factory returns a configured live client when the key is present" {
        referralEntitlementGranter("sk_test").isConfigured() shouldBe true
    }

    "live client POSTs the v1 promotional endpoint with bearer + end_time_ms and maps 2xx to Dispatched" {
        var captured: HttpRequestData? = null
        val engine =
            MockEngine { request ->
                captured = request
                respond("{}", HttpStatusCode.OK)
            }
        val client = RevenueCatPromotionalGranter("sk_secret", engine)

        val result =
            client.grant(GrantRequest(appUserId = "user-123", entitlementId = "premium", endTimeMs = 999L, dedupKey = "t:invitee"))

        result shouldBe GrantResult.Dispatched
        captured!!.url.toString() shouldBe
            "https://api.revenuecat.com/v1/subscribers/user-123/entitlements/premium/promotional"
        captured!!.headers[HttpHeaders.Authorization] shouldBe "Bearer sk_secret"
        (captured!!.body as TextContent).text shouldContain "\"end_time_ms\":999"
    }

    "live client maps a non-2xx response to Failed" {
        val engine = MockEngine { respond("bad request", HttpStatusCode.BadRequest) }
        val client = RevenueCatPromotionalGranter("sk_secret", engine)

        client.grant(GrantRequest("u", "premium", 1L, "d")).shouldBeInstanceOf<GrantResult.Failed>()
    }

    "live client maps a thrown engine error to Failed and never throws" {
        val engine = MockEngine { throw RuntimeException("network down") }
        val client = RevenueCatPromotionalGranter("sk_secret", engine)

        client.grant(GrantRequest("u", "premium", 1L, "d")).shouldBeInstanceOf<GrantResult.Failed>()
    }
})
