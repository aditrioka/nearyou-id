package id.nearyou.app.infra.cloudflareimages

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Cloudflare Images implementation of [ImageStore] via **server-side upload** (design D1):
 * the backend already holds the moderated bytes, so it POSTs them to the Cloudflare Images
 * API directly rather than handing the client a Direct-Creator-Upload URL (which would
 * bypass upfront Safe Search). Cloudflare ships no official JVM SDK, so this uses the Ktor
 * client (the `:infra:openai-moderation` Apache5-engine precedent).
 */
class CloudflareImageStore(
    private val config: CloudflareImagesConfig,
    engine: HttpClientEngine? = null,
) : ImageStore {
    private val log = LoggerFactory.getLogger(CloudflareImageStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Explicit request timeout: the orphan-cleanup sweep holds a DB row lock across
    // delete(), so the call's upper bound must be declared, not inherited from
    // engine defaults (design D2 — "bounded by the HTTP client timeout").
    private val client: HttpClient =
        (if (engine != null) HttpClient(engine) else HttpClient(Apache5)).config {
            install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }

    override fun isConfigured(): Boolean = true

    override suspend fun upload(
        bytes: ByteArray,
        contentType: String,
        fileName: String,
    ): StoredImage {
        val response =
            client.submitFormWithBinaryData(
                url = "https://api.cloudflare.com/client/v4/accounts/${config.accountId}/images/v1",
                formData =
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            },
                        )
                    },
            ) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiToken}")
            }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.warn("event=cloudflare_images_upload_http_error status={}", response.status.value)
            throw CloudflareImageStoreException("Cloudflare Images upload failed: HTTP ${response.status.value}")
        }
        val parsed = json.decodeFromString<CfImagesResponse>(body)
        val imageId = parsed.result?.id
        if (!parsed.success || imageId.isNullOrBlank()) {
            log.warn("event=cloudflare_images_upload_unsuccessful errors={}", parsed.errors)
            throw CloudflareImageStoreException("Cloudflare Images upload unsuccessful")
        }

        // Construct the delivery URL on our controlled `img` subdomain (Open Decision #32 —
        // we never serve from imagedelivery.net except the documented emergency fallback) via the
        // single shared builder so the read-path surfacing emits the identical shape.
        return StoredImage(imageId = imageId, deliveryUrl = config.deliveryUrl(imageId))
    }

    override suspend fun delete(imageId: String) {
        val response =
            client.delete(
                "https://api.cloudflare.com/client/v4/accounts/${config.accountId}/images/v1/$imageId",
            ) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiToken}")
            }

        // 404 = already gone (a prior run crashed after the CF delete but before its ledger
        // commit) — success, so the orphan-cleanup retry converges instead of wedging.
        if (response.status == HttpStatusCode.NotFound) return
        if (!response.status.isSuccess()) {
            log.warn("event=cloudflare_images_delete_http_error status={}", response.status.value)
            throw CloudflareImageStoreException("Cloudflare Images delete failed: HTTP ${response.status.value}")
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
    }
}

@Serializable
private data class CfImagesResponse(
    val success: Boolean = false,
    val result: CfImageResult? = null,
    val errors: List<CfError> = emptyList(),
)

@Serializable
private data class CfImageResult(
    val id: String? = null,
)

@Serializable
private data class CfError(
    val code: Int = 0,
    @SerialName("message") val message: String = "",
)

class CloudflareImageStoreException(message: String) : RuntimeException(message)
