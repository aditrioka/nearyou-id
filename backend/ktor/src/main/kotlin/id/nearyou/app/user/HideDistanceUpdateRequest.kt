package id.nearyou.app.user

import kotlinx.serialization.Serializable

/**
 * Request body for `PATCH /api/v1/user/hide-distance` (the `hide-distance` capability).
 *
 * Single non-nullable `Boolean`: a missing key deserialization-fails
 * (`MissingFieldException`) to `400`, and a non-boolean value fails (type-mismatch
 * `SerializationException`) to `400`. Bare camelCase `hideDistance` (no `@SerialName`).
 * Extra unknown keys are ignored by the app-wide
 * `ContentNegotiation { Json { ignoreUnknownKeys = true } }`.
 *
 * The write is permitted for any tier — effectiveness is read-gated (a Free user with a
 * stale `TRUE` reads as OFF), mirroring `private_profile_opt_in`. The user identity comes
 * solely from the verified JWT principal; the route accepts no `user_id` param (no IDOR).
 */
@Serializable
data class HideDistanceUpdateRequest(
    val hideDistance: Boolean,
)

/**
 * Response body for `PATCH /api/v1/user/hide-distance` — echoes the stored value.
 */
@Serializable
data class HideDistanceResponse(
    val hideDistance: Boolean,
)

/**
 * Response body for `GET /api/v1/user/hide-distance` — the toggle's current state for the mobile
 * Settings screen. [hideDistance] is the stored flag; [premium] is whether the caller is effectively
 * Premium (`subscription_status IN ('premium_active','premium_billing_retry')`, derived from the JWT
 * principal), which the screen uses to decide interactive-toggle vs Premium-upsell.
 */
@Serializable
data class HideDistanceStateResponse(
    val hideDistance: Boolean,
    val premium: Boolean,
)
