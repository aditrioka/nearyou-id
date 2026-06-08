package id.nearyou.app.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `PATCH /api/v1/user/consent` (the `analytics-consent-update`
 * capability).
 *
 * All three fields are non-nullable `Boolean`: a missing key
 * deserialization-fails (`MissingFieldException`) to `400`, and a non-boolean
 * value fails (type-mismatch `SerializationException`) to `400`. The full triple
 * is always required because the handler full-object-writes
 * `users.analytics_consent` (no partial merge). Extra unknown keys are ignored
 * by the app-wide `ContentNegotiation { Json { ignoreUnknownKeys = true } }`,
 * matching the `FcmTokenRequest` precedent — an extra key cannot corrupt the
 * write since only these three fields are read.
 *
 * Wire keys are snake_case to match the `users.analytics_consent` JSONB
 * sub-keys (`{"analytics", "crash", "ads_personalization"}`,
 * `V2__auth_foundation.sql`).
 */
@Serializable
data class ConsentUpdateRequest(
    val analytics: Boolean,
    val crash: Boolean,
    @SerialName("ads_personalization") val adsPersonalization: Boolean,
)

/**
 * Response body for `PATCH /api/v1/user/consent` — echoes the stored triple.
 */
@Serializable
data class ConsentResponse(
    val analytics: Boolean,
    val crash: Boolean,
    @SerialName("ads_personalization") val adsPersonalization: Boolean,
)
