package id.nearyou.app.admin.auth

/**
 * Guards the `INET`-typed columns (`admin_sessions.ip`,
 * `admin_actions_log.ip`) against non-literal `clientIp` values.
 *
 * `call.clientIp` (per the `client-ip-extraction` capability) resolves to
 * `CF-Connecting-IP` → first `X-Forwarded-For` entry → `remoteHost` → the
 * literal `"unknown"`. In production behind Cloudflare the first source is a
 * valid IP, but the `remoteHost` fallback can be a hostname (`"localhost"`)
 * and the last-resort fallback is the non-IP string `"unknown"` — both of
 * which Postgres rejects at the `?::inet` cast, throwing a `PSQLException`
 * out of the login / audit INSERT (→ HTTP 500, and worse, a 500 on a
 * failure path breaks the no-enumeration contract).
 *
 * This sanitizer keeps a value only if it's a plausible IPv4/IPv6 literal,
 * substituting a caller-chosen fallback otherwise. The `admin_sessions.ip`
 * (NOT NULL) writer uses the `"0.0.0.0"` sentinel; the
 * `admin_actions_log.ip` (nullable) writer uses `null`.
 */
object InetSanitizer {
    private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:]+$""")

    /** Sentinel for the NOT NULL `admin_sessions.ip` column when the
     *  resolved client IP is not an IP literal. */
    const val UNKNOWN_IP_SENTINEL = "0.0.0.0"

    /**
     * Returns [ip] when it's a plausible IPv4 or IPv6 literal; otherwise
     * [fallback]. Rejects hostnames (`localhost`) + the `unknown` fallback
     * string so the `?::inet` cast never throws.
     */
    fun orFallback(
        ip: String,
        fallback: String?,
    ): String? = if (isInetLiteral(ip)) ip else fallback

    fun isInetLiteral(ip: String): Boolean {
        if (IPV4.matches(ip)) {
            return ip.split('.').all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
        }
        // IPv6 literals always contain ':'; the hex-and-colon-only guard
        // rejects hostnames like "localhost"/"unknown" (non-hex letters).
        return ip.contains(':') && IPV6.matches(ip)
    }
}
