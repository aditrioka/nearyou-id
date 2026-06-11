package id.nearyou.app.common

import id.nearyou.app.guard.ContentEmptyException
import id.nearyou.app.guard.ContentTooLongException
import id.nearyou.app.post.ContentModeratedProfanityException
import id.nearyou.app.post.LocationOutOfBoundsException
import id.nearyou.app.post.PostRateLimitedException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * The application's single StatusPages configuration — extracted from `Application.module`
 * so `StatusPagesClientErrorTest` can mount the EXACT production mapping (not a copy).
 *
 * Ordering note: StatusPages dispatches on the most-specific registered exception type,
 * but the `Throwable` catch-all REPLACES Ktor's built-in default exception→status mapping
 * (BadRequestException→400 etc.) — so every built-in client-error type the app can emit
 * must be re-registered here explicitly, or it becomes an alarm-grade 500 (caught live in
 * the 2026-06-11 device verification via a malformed Authorization header).
 */
internal fun Application.installAppStatusPages() {
    install(StatusPages) {
        exception<ContentEmptyException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "content_empty",
                            "message" to "Content is required.",
                        ),
                ),
            )
        }
        exception<ContentTooLongException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "content_too_long",
                            "message" to "Content exceeds the maximum of ${cause.limit} characters.",
                        ),
                ),
            )
        }
        exception<LocationOutOfBoundsException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "location_out_of_bounds",
                            "message" to "Coordinate is outside the supported region.",
                        ),
                ),
            )
        }
        // Layer 1 profanity hit per `content-moderation-keyword-lists` Verdict.Reject.
        // The matched keyword list intentionally does NOT appear in the response body
        // — see `### Requirement: User-facing rejection message is in Bahasa Indonesia,
        // omits matched keywords`. The exception's matchedKeywords field is captured by
        // the TextModerator's Sentry breadcrumb, NOT by this StatusPages handler.
        exception<ContentModeratedProfanityException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "content_moderated_profanity",
                            "message" to "Konten ini mengandung kata yang tidak diperbolehkan. Silakan ubah dan coba lagi.",
                        ),
                ),
            )
        }
        // docs/05 § Layer 2 daily post cap (2026-06-10 audit, 02-M2). Same
        // 429 + Retry-After shape as the like/reply/report limiters.
        exception<PostRateLimitedException> { call, cause ->
            call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "rate_limited",
                            "message" to "Too many posts today. Try again after the daily reset.",
                        ),
                ),
            )
        }
        // Ktor's built-in client-error exceptions MUST keep their 4xx mappings: without
        // these handlers the Throwable catch-all below shadows Ktor's default
        // exception→status mapping and turns CLIENT errors into alarm-grade 500s.
        // Caught live in the 2026-06-11 device verification — a malformed Authorization
        // header (parseAuthorizationHeader throws BadRequestException) and any
        // ContentNegotiation deserialization failure were both 500ing. Regression test:
        // StatusPagesClientErrorTest.
        exception<io.ktor.server.plugins.BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to mapOf("code" to "invalid_request", "message" to "Malformed request")),
            )
        }
        exception<io.ktor.server.plugins.NotFoundException> { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to mapOf("code" to "not_found", "message" to "Not found")),
            )
        }
        exception<io.ktor.server.plugins.UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                mapOf("error" to mapOf("code" to "unsupported_media_type", "message" to "Unsupported media type")),
            )
        }
        exception<io.ktor.server.plugins.PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to mapOf("code" to "payload_too_large", "message" to "Payload too large")),
            )
        }
        exception<Throwable> { call, cause ->
            // Fixed message — echoing cause.message leaks Hikari/PSQL/Lettuce
            // internals to clients (docs/11 §3.3). This handler is also the ONLY
            // place the 500's stack trace reaches the logs (CallLogging records
            // the status line only). Path (no query string — nearby-timeline
            // queries carry coordinates) + method give the correlation handle.
            org.slf4j.LoggerFactory.getLogger("id.nearyou.app.Application").error(
                "event=unhandled_exception method={} path={}",
                call.request.httpMethod.value,
                call.request.path(),
                cause,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to
                        mapOf(
                            "code" to "internal_error",
                            "message" to "Internal server error",
                        ),
                ),
            )
        }
    }
}
