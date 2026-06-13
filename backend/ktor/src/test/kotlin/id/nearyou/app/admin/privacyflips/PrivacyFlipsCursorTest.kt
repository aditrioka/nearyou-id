package id.nearyou.app.admin.privacyflips

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Pure unit tests for the privacy-flip keyset-cursor codec
 * (`admin-privacy-flip-monitor` tasks.md 2.4). No DB — runs in PR CI.
 *
 * Verifies the round-trip preserves `(privacy_flip_scheduled_at, id)` at
 * microsecond precision AND that every malformed token decodes to `null`
 * without throwing (so a tampered/garbage `?cursor=` falls back to the first
 * page rather than 500-ing).
 */
class PrivacyFlipsCursorTest : StringSpec({

    "encode then decode round-trips scheduledAt (micros) + id" {
        val scheduledAt = Instant.parse("2026-06-13T08:00:00.123456Z")
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val cursor = PrivacyFlipsCursor(scheduledAt, id)

        val decoded = PrivacyFlipsCursor.decode(cursor.encode())

        decoded.shouldNotBeNull()
        decoded.id shouldBe id
        decoded.scheduledAt shouldBe scheduledAt
    }

    "decode of a garbage token returns null (not an exception)" {
        PrivacyFlipsCursor.decode("not-a-valid-cursor").shouldBeNull()
    }

    "decode of an empty / blank / null token returns null" {
        PrivacyFlipsCursor.decode("").shouldBeNull()
        PrivacyFlipsCursor.decode("   ").shouldBeNull()
        PrivacyFlipsCursor.decode(null).shouldBeNull()
    }

    "decode of a non-base64url token returns null" {
        PrivacyFlipsCursor.decode("****!!!!").shouldBeNull()
    }

    "decode of base64url whose payload is not '<micros>|<uuid>' returns null" {
        fun b64(s: String) =
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.toByteArray(Charsets.UTF_8))

        PrivacyFlipsCursor.decode(b64("no-separator-here")).shouldBeNull()
        PrivacyFlipsCursor.decode(b64("notalong|11111111-2222-3333-4444-555555555555")).shouldBeNull()
        PrivacyFlipsCursor.decode(b64("123456|not-a-uuid")).shouldBeNull()
        PrivacyFlipsCursor.decode(b64("|11111111-2222-3333-4444-555555555555")).shouldBeNull()
        PrivacyFlipsCursor.decode(b64("123456|")).shouldBeNull()
        PrivacyFlipsCursor.decode(
            b64("99999999999999999999999999999999|11111111-2222-3333-4444-555555555555"),
        ).shouldBeNull()
    }
})
