package id.nearyou.app.common

import id.nearyou.app.image.MAX_IMAGE_BYTES
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

@Serializable
private data class Probe(val content: String)

private const val KIB = 1024
private const val MIB = 1024 * KIB

/**
 * The global `RequestBodyLimit` (backend-bootstrap § "Global request body size limit", #545),
 * driven through the production [installAppStatusPages] + [installRequestBodyLimit] wiring.
 * `handled` counts handlers that got past `call.receive` — it staying 0 is the proof that an
 * oversize body was rejected before deserialisation.
 */
class RequestBodyLimitTest : StringSpec({

    suspend fun withApp(block: suspend ApplicationTestBuilder.(handled: AtomicInteger) -> Unit) {
        val handled = AtomicInteger()
        testApplication {
            application {
                install(ContentNegotiation) { json(AppJson) }
                installAppStatusPages()
                installRequestBodyLimit()
                installResponseCompression()
                // Rejects every caller — proves the 413 happens BEFORE authentication.
                install(Authentication) { basic("always-reject") { validate { null } } }
                routing {
                    authenticate("always-reject") {
                        post("/api/v1/posts") {
                            call.receive<Probe>()
                            handled.incrementAndGet()
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                    // Echoes how many bytes the handler actually received.
                    post("/api/v1/raw-probe") {
                        call.respondText(call.receive<ByteArray>().size.toString())
                    }
                    post("/api/v1/replies-probe") {
                        call.receive<Probe>()
                        handled.incrementAndGet()
                        call.respond(HttpStatusCode.OK)
                    }
                    for (path in listOf(
                        "/api/v1/images",
                        "/admin/reserved-usernames/bulk",
                        "/admin/feature-flags/wordlists/profanity",
                    )) {
                        post(path) {
                            call.receive<ByteArray>()
                            handled.incrementAndGet()
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
            }
            block(handled)
        }
    }

    suspend fun ApplicationTestBuilder.postBytes(
        path: String,
        size: Int,
    ): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(size))
        }

    /** A JSON `Probe` body padded to exactly [size] bytes. */
    fun probeJson(size: Int): String {
        val shell = """{"content":""}"""
        return """{"content":"${"a".repeat(size - shell.length)}"}"""
    }

    "oversize JSON body with Content-Length → 413 before authentication and deserialisation" {
        withApp { handled ->
            val resp =
                client.post("/api/v1/posts") {
                    contentType(ContentType.Application.Json)
                    setBody(probeJson(MIB))
                }
            resp.status shouldBe HttpStatusCode.PayloadTooLarge
            resp.bodyAsText() shouldContain "payload_too_large"
            handled.get() shouldBe 0
        }
    }

    fun chunked(
        bytes: ByteArray,
        type: ContentType,
    ) = object : OutgoingContent.WriteChannelContent() {
        override val contentType = type

        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(bytes)
        }
    }

    "oversize chunked JSON body is cut off with a 400, never a 5xx, never deserialised" {
        // No Content-Length → no pre-check; the limiter cuts the stream at 64 KiB and
        // ContentNegotiation drops the PayloadTooLargeException cause, so the documented
        // answer is the 400 invalid_request envelope (spec: 4xx, never 5xx).
        withApp { handled ->
            val resp =
                client.post("/api/v1/replies-probe") {
                    setBody(chunked(probeJson(MIB).toByteArray(), ContentType.Application.Json))
                }
            resp.status shouldBe HttpStatusCode.BadRequest
            resp.bodyAsText() shouldContain "invalid_request"
            handled.get() shouldBe 0
        }
    }

    "oversize chunked raw body surfaces the limiter's 413 directly" {
        withApp { handled ->
            val resp =
                client.post("/api/v1/images") {
                    setBody(chunked(ByteArray(MAX_IMAGE_BYTES.toInt() + 128 * KIB), ContentType.Application.OctetStream))
                }
            resp.status shouldBe HttpStatusCode.PayloadTooLarge
            resp.bodyAsText() shouldContain "payload_too_large"
            handled.get() shouldBe 0
        }
    }

    "a gzip request body is not inflated past the cap (request decompression is off)" {
        val inflated = 4 * MIB
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(inflated)) } }.toByteArray()
        withApp { _ ->
            val resp =
                client.post("/api/v1/raw-probe") {
                    header(HttpHeaders.ContentEncoding, "gzip")
                    contentType(ContentType.Application.OctetStream)
                    setBody(gzipped)
                }
            // The handler sees the raw compressed bytes — never the 4 MiB expansion.
            resp.status shouldBe HttpStatusCode.OK
            resp.bodyAsText() shouldBe gzipped.size.toString()
        }
    }

    "a body of exactly 64 KiB on a default-limit path reaches the handler" {
        withApp { handled ->
            val resp =
                client.post("/api/v1/replies-probe") {
                    contentType(ContentType.Application.Json)
                    setBody(probeJson(64 * KIB))
                }
            resp.status shouldBe HttpStatusCode.OK
            handled.get() shouldBe 1
        }
    }

    "image upload path admits a full 5 MB image plus envelope, rejects beyond its cap" {
        withApp { handled ->
            postBytes("/api/v1/images", MAX_IMAGE_BYTES.toInt() + KIB).status shouldBe HttpStatusCode.OK
            handled.get() shouldBe 1
            val tooBig = postBytes("/api/v1/images", MAX_IMAGE_BYTES.toInt() + 64 * KIB + 1)
            tooBig.status shouldBe HttpStatusCode.PayloadTooLarge
            tooBig.bodyAsText() shouldContain "payload_too_large"
            handled.get() shouldBe 1
        }
    }

    "admin bulk paths admit bodies above the default and reject above their own caps (1 MiB / 4 MiB)" {
        withApp { handled ->
            postBytes("/admin/reserved-usernames/bulk", 512 * KIB).status shouldBe HttpStatusCode.OK
            postBytes("/admin/feature-flags/wordlists/profanity", 2 * MIB).status shouldBe HttpStatusCode.OK
            handled.get() shouldBe 2
            for ((path, size) in listOf(
                "/admin/reserved-usernames/bulk" to MIB + 1,
                "/admin/feature-flags/wordlists/profanity" to 4 * MIB + 1,
                "/admin/feature-flags/wordlists/profanity/preview" to 4 * MIB + 1,
            )) {
                val res = postBytes(path, size)
                res.status shouldBe HttpStatusCode.PayloadTooLarge
                res.bodyAsText() shouldContain "payload_too_large"
            }
            handled.get() shouldBe 2
        }
    }

    "resolver: overrides are exact (or the wordlist prefix) and never leak to sibling paths" {
        requestBodyLimitFor("/api/v1/images") shouldBe MAX_IMAGE_BYTES + 64 * KIB
        requestBodyLimitFor("/admin/reserved-usernames/bulk") shouldBe MIB.toLong()
        requestBodyLimitFor("/admin/feature-flags/wordlists/profanity") shouldBe 4L * MIB
        requestBodyLimitFor("/admin/feature-flags/wordlists/uu_ite/preview") shouldBe 4L * MIB
        for (sibling in listOf("/api/v1/images-x", "/admin/reserved-usernames", "/admin/feature-flags", "/api/v1/posts")) {
            requestBodyLimitFor(sibling) shouldBe DEFAULT_REQUEST_BODY_LIMIT
        }
        DEFAULT_REQUEST_BODY_LIMIT shouldBe 64L * KIB
    }

    "sibling paths of an override get the default: 1 MiB is rejected 413 (pre-check runs before routing)" {
        withApp { handled ->
            for (sibling in listOf("/api/v1/images-x", "/admin/reserved-usernames", "/admin/feature-flags")) {
                postBytes(sibling, MIB).status shouldBe HttpStatusCode.PayloadTooLarge
            }
            handled.get() shouldBe 0
        }
    }
})
