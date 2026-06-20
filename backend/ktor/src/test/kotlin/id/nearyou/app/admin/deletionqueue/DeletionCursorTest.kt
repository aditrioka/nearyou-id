package id.nearyou.app.admin.deletionqueue

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Pure-unit tests for the opaque keyset [DeletionCursor] codec — the base64url
 * `encode()`/`decode()` round-trip the route relies on (`nextUrl` ↔ `?cursor=`),
 * and the null-on-garbage robustness (a malformed token MUST decode to null →
 * first page, never throw / 500). No DB.
 */
class DeletionCursorTest : StringSpec({

    "encode → decode round-trips (microsecond precision)" {
        val original = DeletionCursor(Instant.parse("2026-06-20T10:00:00.123456Z"), UUID.randomUUID())
        val decoded = DeletionCursor.decode(original.encode())
        decoded shouldBe original
    }

    "round-trips a pre-epoch instant (floorDiv/floorMod negative handling)" {
        val original = DeletionCursor(Instant.parse("1969-12-31T23:59:59.000001Z"), UUID.randomUUID())
        DeletionCursor.decode(original.encode()) shouldBe original
    }

    "null / blank token decodes to null" {
        DeletionCursor.decode(null).shouldBeNull()
        DeletionCursor.decode("").shouldBeNull()
        DeletionCursor.decode("   ").shouldBeNull()
    }

    "non-base64 garbage decodes to null (never throws)" {
        DeletionCursor.decode("not base64 !!!").shouldBeNull()
    }

    "well-formed base64 with no separator decodes to null" {
        val noSep = Base64.getUrlEncoder().withoutPadding().encodeToString("12345".toByteArray())
        DeletionCursor.decode(noSep).shouldBeNull()
    }

    "base64 with a non-numeric micros segment decodes to null" {
        val badMicros =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString("abc|${UUID.randomUUID()}".toByteArray())
        DeletionCursor.decode(badMicros).shouldBeNull()
    }

    "base64 with a non-UUID id segment decodes to null" {
        val badId =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1000000|not-a-uuid".toByteArray())
        DeletionCursor.decode(badId).shouldBeNull()
    }
})
