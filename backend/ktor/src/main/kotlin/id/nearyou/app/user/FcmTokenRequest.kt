package id.nearyou.app.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/v1/user/fcm-token`.
 *
 * `token` and `platform` are non-nullable: a missing field
 * deserialization-fails to `malformed_body` per the spec, NOT to
 * `empty_token` / `invalid_platform`.
 *
 * The wire field name `app_version` is canonical per
 * docs/05-Implementation.md:1394.
 */
@Serializable
data class FcmTokenRequest(
    val token: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
)
