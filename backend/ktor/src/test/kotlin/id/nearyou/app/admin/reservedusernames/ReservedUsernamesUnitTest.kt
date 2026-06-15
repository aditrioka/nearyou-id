package id.nearyou.app.admin.reservedusernames

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Pure (no-DB) unit tests for the reserved-username validation + cursor codec
 * (`admin-reserved-usernames-editor`): the username charset/normalization
 * (tasks 5.9), the reason length boundary (tasks 5.31), and the keyset cursor
 * round-trip + malformed-token tolerance (supports tasks 5.1).
 */
class ReservedUsernamesUnitTest : StringSpec({

    // ---- normalizeUsername (5.9) ------------------------------------------

    "normalizeUsername lowercases + trims a valid handle" {
        ReservedUsernameValidation.normalizeUsername("  Brand_X ") shouldBe "brand_x"
        ReservedUsernameValidation.normalizeUsername("UPPER.case_1") shouldBe "upper.case_1"
    }

    "normalizeUsername accepts short/edge handles below the signup 3-char minimum" {
        // The V3 seed data reserves 1-2-char short handles, so the editor must too
        // (charset-only validation, NOT the full signup shape rules — design D9).
        ReservedUsernameValidation.normalizeUsername("a") shouldBe "a"
        ReservedUsernameValidation.normalizeUsername("ab") shouldBe "ab"
    }

    "normalizeUsername rejects out-of-charset and over-length usernames" {
        ReservedUsernameValidation.normalizeUsername("bad-user").shouldBeNull() // hyphen not in [a-z0-9._]
        ReservedUsernameValidation.normalizeUsername("has space").shouldBeNull()
        ReservedUsernameValidation.normalizeUsername("emoji😀").shouldBeNull()
        ReservedUsernameValidation.normalizeUsername("x".repeat(31)).shouldBeNull() // > 30
        ReservedUsernameValidation.normalizeUsername("").shouldBeNull()
        ReservedUsernameValidation.normalizeUsername(null).shouldBeNull()
    }

    // ---- validateReason (5.31) --------------------------------------------

    "validateReason trims, rejects blank, and caps at the 64-char column width" {
        ReservedUsernameValidation.validateReason("  brand protection  ") shouldBe "brand protection"
        ReservedUsernameValidation.validateReason("").shouldBeNull()
        ReservedUsernameValidation.validateReason("   ").shouldBeNull()
        ReservedUsernameValidation.validateReason(null).shouldBeNull()
        ReservedUsernameValidation.validateReason("x".repeat(64)) shouldBe "x".repeat(64) // exactly the column width OK
        ReservedUsernameValidation.validateReason("x".repeat(65)).shouldBeNull() // over the column width rejected
    }

    // ---- cursor codec (5.1) -----------------------------------------------

    "cursor round-trips through encode/decode" {
        val cursor = ReservedUsernamesCursor(Instant.parse("2026-05-20T00:00:00.123456Z"), "brand_x")
        val decoded = ReservedUsernamesCursor.decode(cursor.encode())
        decoded shouldBe cursor
    }

    "malformed / blank cursor tokens decode to null (→ first page), never throw" {
        ReservedUsernamesCursor.decode("not-valid-base64!!").shouldBeNull()
        ReservedUsernamesCursor.decode("").shouldBeNull()
        ReservedUsernamesCursor.decode(null).shouldBeNull()
    }
})
