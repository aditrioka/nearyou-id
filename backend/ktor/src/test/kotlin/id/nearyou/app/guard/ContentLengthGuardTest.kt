package id.nearyou.app.guard

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContentLengthGuardTest : StringSpec({
    val guard = ContentLengthGuard(mapOf("post.content" to 280))

    "accepts a normal 20-char post" {
        guard.enforce("post.content", "Halo dari Jakarta!!!") shouldBe "Halo dari Jakarta!!!"
    }

    "accepts exactly-280-code-point content" {
        val exactly280 = "a".repeat(280)
        guard.enforce("post.content", exactly280) shouldBe exactly280
    }

    "rejects 281 code points with ContentTooLong" {
        val overflow = "a".repeat(281)
        val ex = shouldThrow<ContentTooLongException> { guard.enforce("post.content", overflow) }
        ex.key shouldBe "post.content"
        ex.actualCodePoints shouldBe 281
        ex.limit shouldBe 280
    }

    "rejects empty with ContentEmpty" {
        shouldThrow<ContentEmptyException> { guard.enforce("post.content", "") }
        shouldThrow<ContentEmptyException> { guard.enforce("post.content", null) }
    }

    "rejects whitespace-only with ContentEmpty (post-trim)" {
        shouldThrow<ContentEmptyException> { guard.enforce("post.content", "   ") }
        shouldThrow<ContentEmptyException> { guard.enforce("post.content", "\t\n  ") }
    }

    "NFKC normalization: fullwidth digit ASCII-folds" {
        // fullwidth "１２３" (U+FF11..U+FF13) → "123" under NFKC
        guard.enforce("post.content", "１２３") shouldBe "123"
    }

    "counts Unicode code points, not Java chars" {
        // An emoji above BMP is 2 Java chars but 1 code point.
        // Rocket 🚀 = U+1F680 (surrogate pair, 2 chars, 1 code point).
        val oneCodePoint = "🚀"
        val accepted = guard.enforce("post.content", oneCodePoint)
        accepted.codePointCount(0, accepted.length) shouldBe 1
        // A string of 280 emoji should be accepted despite being 560 Java chars.
        val twoEightyEmoji = "🚀".repeat(280)
        twoEightyEmoji.length shouldBe 560 // Java-char count
        guard.enforce("post.content", twoEightyEmoji).codePointCount(0, 560) shouldBe 280
    }

    "281 emoji code points rejected" {
        val over = "🚀".repeat(281)
        shouldThrow<ContentTooLongException> { guard.enforce("post.content", over) }
    }

    "unknown key is a programmer error (not a runtime rejection)" {
        shouldThrow<IllegalStateException> { guard.enforce("bogus.key", "x") }
    }
})
