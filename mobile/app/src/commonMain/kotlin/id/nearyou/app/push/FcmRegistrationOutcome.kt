package id.nearyou.app.push

/**
 * The closed result vocabulary of an FCM token registration attempt (`mobile-fcm-token-registration`).
 * Exhaustive over the backend wire (`POST /api/v1/user/fcm-token` — `backend/ktor/.../user/FcmTokenRoutes.kt`)
 * plus the client-side guard + no-token outcomes; NO generic fallthrough.
 */
sealed interface FcmRegistrationOutcome {
    /** HTTP 204 — the token is registered (or its `last_seen_at` refreshed; the backend upsert is idempotent). */
    data object Registered : FcmRegistrationOutcome

    /**
     * A rejected registration — either the backend's closed 400 vocabulary parsed from `{"error":<code>}`
     * ([code] ∈ `malformed_body` / `invalid_platform` / `empty_token` / `token_too_long` /
     * `app_version_too_long`), the client-side guard's pre-flight rejection (same code set, no request
     * issued), or [UNKNOWN] for an unrecognized / unparseable 400 body. Never a crash, never a silent drop.
     */
    data class Rejected(val code: String) : FcmRegistrationOutcome

    /**
     * HTTP 401. Because the shipped `HttpClient` `Auth` plugin already owns one refresh-and-retry on a 401
     * (and invalidates the session on refresh failure), a 401 surfacing here means that refresh has ALREADY
     * failed — the session is being torn down. The registrar does NOT retry-loop; the next session-active
     * transition re-attempts after re-authentication.
     */
    data object Unauthorized : FcmRegistrationOutcome

    /** Transport/IO failure or any other non-2xx — swallowed by the registrar, retried on the next trigger. */
    data object TransportError : FcmRegistrationOutcome

    /** The provider yielded no token (e.g. iOS denied authorization, or no configured `FirebaseApp`). No request issued. */
    data object NoTokenAvailable : FcmRegistrationOutcome

    companion object {
        /** The [Rejected.code] for an unrecognized / unparseable 400 error body. */
        const val UNKNOWN: String = "unknown"

        // The backend's closed 400 error vocabulary (mirrors FcmTokenRoutes.kt's ERROR_* codes).
        const val EMPTY_TOKEN: String = "empty_token"
        const val TOKEN_TOO_LONG: String = "token_too_long"
        const val INVALID_PLATFORM: String = "invalid_platform"
        const val APP_VERSION_TOO_LONG: String = "app_version_too_long"
        const val MALFORMED_BODY: String = "malformed_body"
    }
}
