package id.nearyou.app.image

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Minimal `201`-body DTO for `POST /api/v1/images`, mirroring the **shipped** backend serialization in
 * `backend/ktor/src/main/kotlin/id/nearyou/app/image/ImageRoutes.kt` — which builds the body via a manual
 * `buildJsonObject { put("image_id", …); put("delivery_url", …) }`, so both keys are **snake_case**. The
 * client treats [deliveryUrl] as opaque (the real shape is `<base>/<accountHash>/<image_id>/public`, a
 * 4-segment URL) — it never reconstructs the path. Relies on the shared `Json`'s `ignoreUnknownKeys = true`
 * to tolerate any other field the server may add.
 */
@Serializable
data class UploadedImageDto(
    @SerialName("image_id") val imageId: String,
    @SerialName("delivery_url") val deliveryUrl: String,
)

/** The backend error envelope `{ "error": { "code", "message" } }`; only [ImageErrorEnvelope.code] is
 *  load-bearing (the repository disambiguates the two `403`s / two `429`s on it). */
@Serializable
data class ImageErrorBody(
    val error: ImageErrorEnvelope,
)

@Serializable
data class ImageErrorEnvelope(
    val code: String,
    val message: String? = null,
)

/**
 * Low-level result of an image upload, mirroring [id.nearyou.app.post.PostCreationApiResult]: the parsed
 * body on `201`, the HTTP [HttpError.status] + best-effort parsed [HttpError.errorCode] on any non-2xx, or
 * a transport failure. The repository keys outcome on [HttpError.status] + [HttpError.errorCode] (the two
 * `403`s and two `429`s are disambiguated by `error.code`, not status). A non-2xx is a value, NEVER a
 * thrown exception.
 */
sealed interface ImageUploadApiResult {
    data class Success(val body: UploadedImageDto) : ImageUploadApiResult

    /** Non-2xx response. [errorCode] is the parsed `error.code` when the envelope decodes, else null
     *  (e.g. a 5xx with an empty/non-JSON body); the repository maps it together with [status]. */
    data class HttpError(val status: Int, val errorCode: String?) : ImageUploadApiResult

    /** Transport-level failure (IOException, timeout, host unreachable). */
    data class NetworkError(val cause: Throwable) : ImageUploadApiResult
}

/**
 * Thin wrapper over the shared [HttpClient] for `POST /api/v1/images` (the canonical premium image-upload
 * endpoint per `openspec/specs/image-upload/spec.md`, served by `ImageRoutes.kt`). The body is a multipart
 * form carrying the image bytes under the `file` part with the picked MIME content-type. The Bearer
 * `Authorization` header is attached by the shipped `Auth` plugin and a terminal 401 is handled there
 * (→ `SessionInvalidator` → `SignInScreen`) — this client MUST NOT reimplement token attachment or 401
 * refresh, and MUST NOT construct an ad-hoc `HttpClient`.
 *
 * Privacy discipline: the image bytes are NEVER logged (no `println`/log of the payload), and the client
 * MUST NOT widen the inherited client log level. The raw `bytes` + `mime` keep this client independent of
 * the Phase-4 `ImagePicker`/`PickedImage` seam.
 */
class ImageUploadApiClient(
    private val client: HttpClient,
) {
    suspend fun upload(
        bytes: ByteArray,
        mime: String,
    ): ImageUploadApiResult {
        val response: HttpResponse =
            try {
                client.post("/api/v1/images") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    key = "file",
                                    value = bytes,
                                    headers =
                                        Headers.build {
                                            // The picked image MIME drives the part content-type; the
                                            // backend's allowlist (ContentType.Image.Any) keys on it.
                                            append(HttpHeaders.ContentType, mime)
                                            // A filename makes the part a FileItem (what readImagePart
                                            // requires); the exact name is opaque to the server.
                                            append(
                                                HttpHeaders.ContentDisposition,
                                                ContentDisposition.File
                                                    .withParameter(ContentDisposition.Parameters.FileName, "image")
                                                    .toString(),
                                            )
                                        },
                                )
                            },
                        ),
                    )
                }
            } catch (cause: CancellationException) {
                // Never swallow cancellation — let structured concurrency unwind (mirrors PostCreationApiClient).
                throw cause
            } catch (cause: Throwable) {
                return ImageUploadApiResult.NetworkError(cause)
            }

        if (response.status == HttpStatusCode.Created) {
            return try {
                ImageUploadApiResult.Success(response.body<UploadedImageDto>())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // A 201 whose body fails to parse is a transport/contract failure, not a Success.
                ImageUploadApiResult.NetworkError(cause)
            }
        }

        // Best-effort parse of the `{ "error": { "code" } }` envelope; an empty/non-JSON body
        // (e.g. a bare 5xx) yields null — the repository maps the unknown non-2xx to Unavailable.
        val errorCode =
            try {
                response.body<ImageErrorBody>().error.code
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                null
            }
        return ImageUploadApiResult.HttpError(status = response.status.value, errorCode = errorCode)
    }
}
