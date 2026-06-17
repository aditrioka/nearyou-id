package id.nearyou.app.username

/**
 * The username-customization orchestration contract consumed by `UsernameCustomizationScreen` /
 * `UsernameCustomizationViewModel`. The production binding is [UsernameRepository]; commonTest
 * substitutes a `FakeUsernameFlow` so the screen + ViewModel tests drive a specific outcome / assert
 * (re-)invocation without a backend — mirroring the `SearchFlow` / `ProfileFlow` seams.
 *
 * Both methods consume the SHIPPED, FROZEN backend (`premium-username-customization`):
 * `PATCH /api/v1/user/username` (the change) and `GET /api/v1/username/check?candidate=` (the
 * non-authoritative, 3/day-rate-limited probe).
 */
interface UsernameFlow {
    /** Issues `PATCH /api/v1/user/username` with `{ "new_username": <candidate> }` and maps the result
     *  to exactly one [UsernameChangeOutcome]. */
    suspend fun change(newUsername: String): UsernameChangeOutcome

    /** Issues `GET /api/v1/username/check?candidate=<candidate>` and maps to exactly one
     *  [UsernameCheckOutcome]. Budget-aware: the screen invokes this sparingly (the shipped probe is
     *  3/day per user). */
    suspend fun check(candidate: String): UsernameCheckOutcome
}

/**
 * Every `PATCH` maps to EXACTLY one member, keyed on the HTTP **status** — reading the shipped body
 * `error` code ONLY to split the two same-status pairs (`429` cooldown_active vs rate_limited; `422`
 * invalid_username vs username_rejected) per design D4, with no generic "load failed" fallthrough. A
 * terminal `401` (survived the shipped `Auth` `refreshTokens`) maps to [SessionExpired]; the `Auth`
 * plugin + `SessionInvalidator` own the actual re-route.
 */
sealed interface UsernameChangeOutcome {
    /** HTTP 200 `{ "username": "<new>" }` — the change committed; carries the new handle. */
    data class Success(val username: String) : UsernameChangeOutcome

    /** HTTP 403 `premium_required` — the Free-tier gate (the screen renders the upsell). */
    data object PremiumGate : UsernameChangeOutcome

    /** HTTP 409 `username_unavailable` — reserved / on release hold / taken / lost race. The shipped
     *  envelope does NOT distinguish these; the screen shows one generic message. */
    data object Unavailable : UsernameChangeOutcome

    /** HTTP 422 `invalid_username` — a format failure (defensive; the live local guard should pre-empt). */
    data object InvalidFormat : UsernameChangeOutcome

    /** HTTP 422 `username_rejected` — the profanity / UU-ITE moderation soft flag. */
    data object Moderated : UsernameChangeOutcome

    /** HTTP 429 `cooldown_active` — the 30-day one-change cooldown; carries the `Retry-After` seconds
     *  (absent/unparseable → 0, the screen floors to one day). */
    data class CooldownActive(val retryAfterSeconds: Long) : UsernameChangeOutcome

    /** HTTP 429 `rate_limited` — the failed-attempt throttle; carries the `Retry-After` seconds
     *  (absent/unparseable → 0, the screen floors to one minute). */
    data class RateLimited(val retryAfterSeconds: Long) : UsernameChangeOutcome

    /** HTTP 503 `feature_disabled` — the `premium_username_customization_enabled` kill switch. */
    data object Disabled : UsernameChangeOutcome

    /** HTTP 400 `invalid_request` (unparseable body — not expected) — retryable, logged. */
    data object Error : UsernameChangeOutcome

    /** Terminal HTTP 401 — session expired. The screen renders a neutral redirect placeholder (NO retry,
     *  NOT the connectivity copy). MUST NOT map to [NetworkError]/[Error]. */
    data object SessionExpired : UsernameChangeOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated non-2xx status — retryable. */
    data object NetworkError : UsernameChangeOutcome
}

/**
 * Every probe maps to EXACTLY one member, status-driven. The probe is non-authoritative (the under-lock
 * check at `PATCH` time is the backstop), so [ProbeExhausted] is a NON-blocking degradation, not an error.
 */
sealed interface UsernameCheckOutcome {
    /** HTTP 200 `{ "available": <bool> }`. */
    data class Available(val isAvailable: Boolean) : UsernameCheckOutcome

    /** HTTP 403 `premium_required`. */
    data object CheckPremiumGate : UsernameCheckOutcome

    /** HTTP 422 `invalid_username` (defensive — the local guard should pre-empt). */
    data object CheckInvalidFormat : UsernameCheckOutcome

    /** HTTP 429 `rate_limited` — the 3/day budget is exhausted; the screen degrades to "checked at save". */
    data object ProbeExhausted : UsernameCheckOutcome

    /** HTTP 503 `feature_disabled` — the kill switch. */
    data object CheckDisabled : UsernameCheckOutcome

    /** Terminal HTTP 401 — session expired. */
    data object CheckSessionExpired : UsernameCheckOutcome

    /** HTTP 5xx, transport/IO, or any unenumerated non-2xx status — non-blocking (treated as "checked at
     *  save"; the authoritative check is the `PATCH`). */
    data object CheckNetworkError : UsernameCheckOutcome
}
