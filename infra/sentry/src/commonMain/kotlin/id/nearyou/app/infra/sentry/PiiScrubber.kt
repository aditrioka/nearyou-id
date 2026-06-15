package id.nearyou.app.infra.sentry

/**
 * Pure, vendor-free PII redaction applied by [SentryCrashReporter]'s `beforeSend` backstop (and at the
 * breadcrumb-input boundary). Defense-in-depth BEHIND the primary defenses: `sendDefaultPii = false`
 * (no client-IP `{{auto}}` inference), opaque-id-only `setUser`, and the coordinate-free `DiagnosticSink`
 * contract. Mirrors the intent of `:infra:otel`'s `ForbiddenAttributeStripper`; kept pure so the
 * mobile-crash-reporting spec scrubbing scenario is unit-testable without the SDK.
 */
internal object PiiScrubber {
    private const val REDACTED = "[redacted]"

    // A decimal lat,long pair (≥3 fractional digits → real coordinates, not version numbers):
    // "-6.2088,106.8456" or "-6.2088, 106.8456".
    private val coordinatePair = Regex("""-?\d{1,3}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}""")

    // "Bearer <token>" headers.
    private val bearer = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=\-]+""")

    // JWT-shaped blobs (header.payload.signature, base64url).
    private val jwt = Regex("""eyJ[A-Za-z0-9._\-]{10,}""")

    /** Returns [text] with coordinate pairs, bearer tokens, and JWTs replaced by `[redacted]`. */
    fun scrub(text: String): String =
        text
            .replace(coordinatePair, REDACTED)
            .replace(bearer, REDACTED)
            .replace(jwt, REDACTED)
}
