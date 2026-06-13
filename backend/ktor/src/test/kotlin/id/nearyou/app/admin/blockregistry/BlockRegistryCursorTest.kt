package id.nearyou.app.admin.blockregistry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [BlockRegistryCursor] encode/decode — the opaque keyset cursor
 * over `(created_at, blocker_id, blocked_id)`. Pure (no DB); asserts the
 * round-trip and the lenient malformed-token → null contract (a malformed
 * cursor falls back to the first page, never throws — design.md D1).
 */
class BlockRegistryCursorTest : StringSpec({

    "encode → decode round-trips created_at + both UUIDs (microsecond precision)" {
        val cursor =
            BlockRegistryCursor(
                createdAt = Instant.parse("2026-05-20T12:34:56.123456Z"),
                blockerId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                blockedId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            )
        val decoded = BlockRegistryCursor.decode(cursor.encode())
        decoded shouldBe cursor
    }

    "a null token decodes to null (first page)" {
        BlockRegistryCursor.decode(null).shouldBeNull()
    }

    "a blank token decodes to null (first page)" {
        BlockRegistryCursor.decode("   ").shouldBeNull()
    }

    "a non-base64url token decodes to null, never throws" {
        BlockRegistryCursor.decode("not-a-valid-cursor!!!").shouldBeNull()
    }

    "a base64url token with the wrong field count decodes to null" {
        // base64url("123|only-two") — 2 parts, not 3.
        val twoParts =
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("123|only-two".toByteArray())
        BlockRegistryCursor.decode(twoParts).shouldBeNull()
    }

    "a base64url token whose UUID segment is not a UUID decodes to null" {
        val badUuid =
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("123|not-a-uuid|also-not".toByteArray())
        BlockRegistryCursor.decode(badUuid).shouldBeNull()
    }
})
