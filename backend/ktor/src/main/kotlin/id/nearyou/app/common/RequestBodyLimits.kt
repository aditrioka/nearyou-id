package id.nearyou.app.common

import id.nearyou.app.image.MAX_IMAGE_BYTES
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.CompressionConfig
import io.ktor.server.request.path

private const val KIB: Long = 1024
private const val MIB: Long = 1024 * KIB

/** Transport cap for every path without an override — far above any JSON body we accept. */
internal const val DEFAULT_REQUEST_BODY_LIMIT: Long = 64 * KIB

/**
 * The ONE request-body cap resolver (docs/11 §3.3). An override exists only where a route
 * legitimately takes more than the default, and is sized so the route's own guard stays the
 * one that answers for bodies it is meant to judge:
 *  - image upload: the 5 MB image + multipart envelope (route's `image_too_large` stays authoritative);
 *  - reserved-usernames bulk: above the form-encoded size of its 256 KB CSV guard (its `400` stays);
 *  - wordlist editor (`/{list}` + `/{list}/preview`): the full staged list, ≤ 10 000 × 100 chars, twice.
 */
internal fun requestBodyLimitFor(path: String): Long =
    when {
        path == "/api/v1/images" -> MAX_IMAGE_BYTES + 64 * KIB
        path == "/admin/reserved-usernames/bulk" -> MIB
        path.startsWith("/admin/feature-flags/wordlists/") -> 4 * MIB
        else -> DEFAULT_REQUEST_BODY_LIMIT
    }

/**
 * Application-wide `RequestBodyLimit` (#545). Its Content-Length pre-check runs in the
 * Plugins phase — before routing, auth and any `call.receive` — so an oversize body is
 * rejected 413 without being buffered; a chunked body is cut off once it crosses the cap.
 * Path-keyed rather than route-installed: a route-level install can't relax an
 * application-level pre-check that runs before the route is even resolved.
 */
internal fun Application.installRequestBodyLimit() {
    install(RequestBodyLimit) {
        bodyLimit { call -> requestBodyLimitFor(call.request.path()) }
    }
}

/**
 * Response compression only. Ktor's default `Mode.All` also inflates `Content-Encoding`
 * request bodies — in the receive pipeline AFTER [RequestBodyLimit] has counted the raw
 * (compressed) bytes, so a 64 KiB gzip body could expand ~1000× past the cap. No client
 * sends compressed request bodies.
 */
internal fun Application.installResponseCompression() {
    install(Compression) { mode = CompressionConfig.Mode.CompressResponse }
}
