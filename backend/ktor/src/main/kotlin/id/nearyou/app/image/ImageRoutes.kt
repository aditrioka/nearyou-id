package id.nearyou.app.image

import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream

/** Max upload size — 5 MB, guarded server-side as a streamed cap (design D9 / spec). */
const val MAX_IMAGE_BYTES: Long = 5L * 1024 * 1024

/**
 * `POST /api/v1/images` — the premium image-upload endpoint. Order (design D9):
 * authenticate → [ImageUploadService.checkGate] (flag/premium/throttle/quota — BEFORE reading
 * the body) → streamed multipart read with the 5 MB and image content-type guards → [moderateAndStore].
 */
fun Application.imageRoutes(service: ImageUploadService) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            post("/api/v1/images") {
                val principal =
                    call.principal<UserPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@post
                    }

                // Phase 1 — cheap gates, before reading the (up to 5 MB) body.
                when (val gate = service.checkGate(principal.userId, principal.subscriptionStatus)) {
                    ImageUploadService.GateResult.FlagDisabled ->
                        return@post call.respondError(HttpStatusCode.Forbidden, "image_upload_disabled", "Image upload is not available.")
                    ImageUploadService.GateResult.PremiumRequired ->
                        return@post call.respondError(HttpStatusCode.Forbidden, "premium_required", "Image upload is a Premium feature.")
                    is ImageUploadService.GateResult.Throttled -> {
                        call.response.headers.append(HttpHeaders.RetryAfter, gate.retryAfterSeconds.toString())
                        return@post call.respondError(
                            HttpStatusCode.TooManyRequests,
                            "image_upload_throttled",
                            "Tunggu sebentar sebelum mengunggah lagi.",
                        )
                    }
                    is ImageUploadService.GateResult.QuotaExceeded -> {
                        call.response.headers.append(HttpHeaders.RetryAfter, gate.retryAfterSeconds.toString())
                        return@post call.respondError(
                            HttpStatusCode.TooManyRequests,
                            "image_upload_quota_exceeded",
                            "Batas unggah harian tercapai.",
                        )
                    }
                    ImageUploadService.GateResult.Ok -> Unit
                }

                // Phase 2 — streamed body read with the size + content-type guards.
                when (val read = readImagePart(call.receiveMultipart(), MAX_IMAGE_BYTES)) {
                    MultipartReadResult.TooLarge ->
                        return@post call.respondError(HttpStatusCode.PayloadTooLarge, "image_too_large", "Gambar melebihi batas 5 MB.")
                    MultipartReadResult.UnsupportedType ->
                        return@post call.respondError(
                            HttpStatusCode.UnsupportedMediaType,
                            "unsupported_image_type",
                            "Format gambar tidak didukung.",
                        )
                    MultipartReadResult.NoFile ->
                        return@post call.respondError(HttpStatusCode.BadRequest, "no_image", "Tidak ada gambar pada permintaan.")
                    is MultipartReadResult.Ok ->
                        when (val result = service.moderateAndStore(principal.userId, read.bytes, read.contentType, read.fileName)) {
                            is ImageUploadService.StoreResult.Success -> {
                                val body =
                                    buildJsonObject {
                                        put("image_id", JsonPrimitive(result.imageId))
                                        put("delivery_url", JsonPrimitive(result.deliveryUrl))
                                    }
                                call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.Created)
                            }
                            ImageUploadService.StoreResult.Rejected ->
                                call.respondError(
                                    HttpStatusCode.UnprocessableEntity,
                                    "image_rejected",
                                    "Gambar tidak memenuhi standar konten.",
                                )
                            ImageUploadService.StoreResult.Unavailable ->
                                call.respondError(
                                    HttpStatusCode.ServiceUnavailable,
                                    "image_upload_unavailable",
                                    "Layanan unggah gambar sedang tidak tersedia.",
                                )
                        }
                }
            }
        }
    }
}

/** Result of reading the multipart file part with the streamed size + content-type guards. */
sealed interface MultipartReadResult {
    data class Ok(val bytes: ByteArray, val contentType: String, val fileName: String) : MultipartReadResult

    data object TooLarge : MultipartReadResult

    data object UnsupportedType : MultipartReadResult

    data object NoFile : MultipartReadResult
}

/**
 * Reads the first file part, enforcing an image content-type allowlist (ContentType.Image.Any)
 * and a streamed [maxBytes] cap that aborts once the byte count is crossed (no full-payload
 * buffering — the multipart-bomb / OOM guard).
 */
internal suspend fun readImagePart(
    multipart: io.ktor.http.content.MultiPartData,
    maxBytes: Long,
): MultipartReadResult {
    var result: MultipartReadResult = MultipartReadResult.NoFile
    multipart.forEachPart { part ->
        if (part is PartData.FileItem && result is MultipartReadResult.NoFile) {
            val contentType = part.contentType
            result =
                if (contentType == null || !contentType.match(ContentType.Image.Any)) {
                    MultipartReadResult.UnsupportedType
                } else {
                    val bytes = readCapped(part.provider(), maxBytes)
                    if (bytes == null) {
                        MultipartReadResult.TooLarge
                    } else {
                        MultipartReadResult.Ok(bytes, contentType.toString(), part.originalFileName ?: "image")
                    }
                }
        }
        part.dispose()
    }
    return result
}

/** Reads up to [maxBytes] from [channel]; returns null once the cap is exceeded (bounded buffering). */
private suspend fun readCapped(
    channel: ByteReadChannel,
    maxBytes: Long,
): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read > 0) {
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) return null
        }
    }
    return out.toByteArray()
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respond(status, mapOf("error" to mapOf("code" to code, "message" to message)))
