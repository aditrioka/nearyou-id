package id.nearyou.app.auth.routes

import id.nearyou.app.auth.signup.SignupRequestDto
import id.nearyou.app.auth.signup.SignupTokenPairResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pins the auth wire contract to **snake_case**, per the canonical specs:
 *  - `openspec/specs/auth-signin/spec.md`  → request `{ provider, id_token, device_fingerprint_hash }`,
 *    success `{ access_token, refresh_token, expires_in }`
 *  - `openspec/specs/auth-session/spec.md` → refresh request `{ refresh_token }`
 *  - `openspec/specs/auth-signup/spec.md`  → request `{ provider, id_token, date_of_birth, device_fingerprint_hash }`
 *
 * Why this test exists: the auth DTOs are plain `@Serializable` data classes and the app-wide
 * `ContentNegotiation` Json uses kotlinx's DEFAULT (camelCase) naming. Dropping a field's
 * `@SerialName` silently reverts that field to camelCase on the wire — violating the spec. That
 * exact slip shipped undetected: every backend flow test AND the mobile `MockEngine` tests
 * round-trip through the same DTOs, so none of them pinned the LITERAL wire names. It only
 * surfaced on the first real-device sign-in, which returned `400 invalid_request`
 * ("Malformed sign-in payload") because the spec-conformant client sent `id_token` while the
 * backend expected `idToken`. `@SerialName` takes precedence over any future `JsonNamingStrategy`,
 * so these assertions remain valid regardless of the app's Json naming config.
 */
class AuthWireFormatTest : StringSpec({
    val json = Json { ignoreUnknownKeys = true }

    "SignInRequest deserializes the spec's snake_case body" {
        val req =
            json.decodeFromString<SignInRequest>(
                """{"provider":"google","id_token":"tok-123","device_fingerprint_hash":"fp-abc"}""",
            )
        req.provider shouldBe "google"
        req.idToken shouldBe "tok-123"
        req.deviceFingerprintHash shouldBe "fp-abc"
    }

    "SignInRequest serializes id_token / device_fingerprint_hash, never camelCase" {
        val s = json.encodeToString(SignInRequest("google", "tok", "fp"))
        s shouldContain "\"id_token\""
        s shouldContain "\"device_fingerprint_hash\""
        s shouldNotContain "idToken"
        s shouldNotContain "deviceFingerprintHash"
    }

    "TokenPairResponse serializes access_token / refresh_token / expires_in, never camelCase" {
        val s = json.encodeToString(TokenPairResponse("at", "rt", 900))
        s shouldContain "\"access_token\""
        s shouldContain "\"refresh_token\""
        s shouldContain "\"expires_in\""
        s shouldNotContain "accessToken"
        s shouldNotContain "refreshToken"
        s shouldNotContain "expiresIn"
    }

    "RefreshRequest deserializes refresh_token" {
        val req = json.decodeFromString<RefreshRequest>("""{"refresh_token":"rt-9"}""")
        req.refreshToken shouldBe "rt-9"
    }

    "LogoutRequest deserializes refresh_token" {
        val req = json.decodeFromString<LogoutRequest>("""{"refresh_token":"rt-9"}""")
        req.refreshToken shouldBe "rt-9"
    }

    "SignupRequestDto deserializes the spec's snake_case body" {
        val req =
            json.decodeFromString<SignupRequestDto>(
                """{"provider":"google","id_token":"tok","date_of_birth":"1995-03-14"}""",
            )
        req.provider shouldBe "google"
        req.idToken shouldBe "tok"
        req.dateOfBirth shouldBe "1995-03-14"
    }

    "SignupTokenPairResponse serializes access_token / refresh_token / expires_in, never camelCase" {
        val s = json.encodeToString(SignupTokenPairResponse("at", "rt", 900))
        s shouldContain "\"access_token\""
        s shouldContain "\"refresh_token\""
        s shouldContain "\"expires_in\""
        s shouldNotContain "accessToken"
    }
})
