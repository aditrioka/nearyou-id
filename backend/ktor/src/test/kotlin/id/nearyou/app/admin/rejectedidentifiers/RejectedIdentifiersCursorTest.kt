package id.nearyou.app.admin.rejectedidentifiers

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Pure unit tests for the keyset-cursor codec (`admin-rejected-identifiers-
 * viewer` tasks.md 2.4). No DB — runs in PR CI.
 *
 * Verifies the round-trip preserves `(rejected_at, id)` at microsecond
 * precision AND that every malformed token decodes to `null` without throwing
 * (so a tampered/garbage `?cursor=` falls back to the first page rather than
 * 500-ing).
 */
class RejectedIdentifiersCursorTest : StringSpec({

    "encode then decode round-trips rejected_at (micros) + id" {
        // An instant with sub-second precision to prove micros survive.
        val rejectedAt = Instant.parse("2026-05-29T12:34:56.123456Z")
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val cursor = RejectedIdentifiersCursor(rejectedAt, id)

        val decoded = RejectedIdentifiersCursor.decode(cursor.encode())

        decoded.shouldNotBeNull()
        decoded.id shouldBe id
        // Postgres timestamptz is microsecond precision; the codec encodes
        // epoch-micros, so the round-tripped instant equals the original
        // truncated to micros (here already micros-aligned).
        decoded.rejectedAt shouldBe rejectedAt
    }

    "decode of a garbage token returns null (not an exception)" {
        RejectedIdentifiersCursor.decode("not-a-valid-cursor").shouldBeNull()
    }

    "decode of an empty / blank / null token returns null" {
        RejectedIdentifiersCursor.decode("").shouldBeNull()
        RejectedIdentifiersCursor.decode("   ").shouldBeNull()
        RejectedIdentifiersCursor.decode(null).shouldBeNull()
    }

    "decode of a non-base64url token returns null" {
        // '*' and '!' are outside the base64url alphabet.
        RejectedIdentifiersCursor.decode("****!!!!").shouldBeNull()
    }

    "decode of base64url whose payload is not '<micros>|<uuid>' returns null" {
        fun b64(s: String) =
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.toByteArray(Charsets.UTF_8))

        RejectedIdentifiersCursor.decode(b64("no-separator-here")).shouldBeNull()
        RejectedIdentifiersCursor.decode(b64("notalong|11111111-2222-3333-4444-555555555555")).shouldBeNull()
        RejectedIdentifiersCursor.decode(b64("123456|not-a-uuid")).shouldBeNull()
        RejectedIdentifiersCursor.decode(b64("|11111111-2222-3333-4444-555555555555")).shouldBeNull()
        RejectedIdentifiersCursor.decode(b64("123456|")).shouldBeNull()
    }
})
