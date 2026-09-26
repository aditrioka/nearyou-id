package id.nearyou.app.infra.sentryjvm

/**
 * Pure PII redaction applied by [SentryBootstrap]'s `beforeSend` to the event message and every
 * exception value. Backstop BEHIND the primary defenses (`sendDefaultPii = false`, no user, no
 * request data, no breadcrumbs).
 *
 * Backend sibling of the mobile `:infra:sentry` `PiiScrubber` (same name + shape; that module is
 * KMP android/iOS-only, so this is pattern reuse, not an import — backend-error-reporting spec).
 * Superset of the mobile patterns: server exception messages can also carry emails, IPs, and
 * pgJDBC's `Detail: Key (col)=(value)` echo of the offending row value.
 */
internal object PiiScrubber {
    private const val REDACTED = "[redacted]"

    // pgJDBC unique/FK violations: "Key (email)=(a@b.co) already exists" — keep the column, drop
    // the value. ponytail: values containing ')' are only partially matched; still redacted up to it.
    private val pgKeyDetail = Regex("""Key \(([^)]*)\)=\([^)]*\)""")

    // A decimal lat,long pair (≥3 fractional digits → real coordinates, not version numbers).
    private val coordinatePair = Regex("""-?\d{1,3}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}""")

    private val bearer = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=\-]+""")

    private val jwt = Regex("""eyJ[A-Za-z0-9._\-]{10,}""")

    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    // ≥4 hex groups so HH:MM:SS timestamps survive. ponytail: compressed forms ("::1") not matched.
    private val ipv6 = Regex("""\b(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}\b""")

    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    /** Returns [text] with every PII-shaped span replaced by `[redacted]`. */
    fun scrub(text: String): String =
        text
            .replace(pgKeyDetail, "Key ($1)=($REDACTED)")
            .replace(coordinatePair, REDACTED)
            .replace(bearer, REDACTED)
            .replace(jwt, REDACTED)
            .replace(email, REDACTED)
            .replace(ipv6, REDACTED)
            .replace(ipv4, REDACTED)
}
