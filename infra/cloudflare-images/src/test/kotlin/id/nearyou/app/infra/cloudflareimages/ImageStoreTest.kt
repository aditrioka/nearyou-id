package id.nearyou.app.infra.cloudflareimages

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class ImageStoreTest : StringSpec({

    val config =
        CloudflareImagesConfig(
            apiToken = "cf-token",
            accountId = "acct123",
            accountHash = "hashABC",
            deliveryBaseUrl = "https://img-staging.nearyou.id",
        )

    fun jsonEngine(
        status: HttpStatusCode,
        body: String,
    ) = MockEngine {
        respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    "factory returns NoOp when config is null" {
        imageStore(null).isConfigured().shouldBeFalse()
    }

    "factory returns NoOp when config is incomplete (blank token)" {
        imageStore(config.copy(apiToken = "")).isConfigured().shouldBeFalse()
    }

    "factory returns the Cloudflare store when config is complete" {
        imageStore(config, jsonEngine(HttpStatusCode.OK, "{}")).isConfigured().shouldBeTrue()
    }

    "upload returns a StoredImage rooted at the img subdomain on success" {
        val body = """{"success":true,"result":{"id":"img-1"},"errors":[]}"""
        val store = imageStore(config, jsonEngine(HttpStatusCode.OK, body))

        val result = store.upload(byteArrayOf(1, 2, 3), "image/jpeg", "photo.jpg")

        result.imageId shouldBe "img-1"
        result.deliveryUrl shouldBe "https://img-staging.nearyou.id/hashABC/img-1/public"
        result.deliveryUrl shouldContain "img-staging.nearyou.id"
        result.deliveryUrl shouldNotContain "imagedelivery.net"
    }

    "upload throws on an HTTP error (fail-closed)" {
        val store = imageStore(config, jsonEngine(HttpStatusCode.InternalServerError, """{"success":false}"""))
        shouldThrow<CloudflareImageStoreException> {
            store.upload(byteArrayOf(1), "image/png", "x.png")
        }
    }

    "upload throws when the API reports success=false" {
        val body = """{"success":false,"result":null,"errors":[{"code":5400,"message":"bad"}]}"""
        val store = imageStore(config, jsonEngine(HttpStatusCode.OK, body))
        shouldThrow<CloudflareImageStoreException> {
            store.upload(byteArrayOf(1), "image/png", "x.png")
        }
    }
})
