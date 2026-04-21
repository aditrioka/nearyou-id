package id.nearyou.app.guard

import io.ktor.server.application.Application
import org.slf4j.LoggerFactory
import java.text.Normalizer

/**
 * Central input length-guard registry. Routes that accept user-authored text call
 * [enforce] and receive a normalized string or throw a typed exception the
 * StatusPages handler maps to `content_empty` / `content_too_long` (both 400).
 *
 * Units are Unicode code points — NOT bytes, NOT Java chars. This matters for
 * emoji: a four-byte UTF-8 emoji counts as 1-2 code points (usually 1 for
 * non-SP-sequenced emoji, 2 for VS16 / ZWJ base + combiner).
 *
 * Future input routes register their own keys here (reply: 280; chat: 2000;
 * bio: 160; display_name: 50; username-custom: 30). The middleware itself
 * doesn't need a change.
 */
class ContentLengthGuard(
    private val limits: Map<String, Int>,
) {
    /**
     * Normalize (NFKC) + trim the input, then length-check. Returns the normalized
     * string on success. Throws [ContentEmptyException] for blank/null/empty input
     * and [ContentTooLongException] for code-point count > the registered limit.
     */
    fun enforce(
        key: String,
        raw: String?,
    ): String {
        val limit = limits[key] ?: error("ContentLengthGuard: no limit registered for '$key'")
        val normalized = Normalizer.normalize(raw ?: "", Normalizer.Form.NFKC).trim()
        if (normalized.isEmpty()) throw ContentEmptyException(key)
        val codePoints = normalized.codePointCount(0, normalized.length)
        if (codePoints > limit) throw ContentTooLongException(key, codePoints, limit)
        return normalized
    }

    fun limitFor(key: String): Int? = limits[key]
}

class ContentEmptyException(val key: String) :
    RuntimeException("content empty for key=$key")

class ContentTooLongException(
    val key: String,
    val actualCodePoints: Int,
    val limit: Int,
) : RuntimeException("content exceeds limit: key=$key, cp=$actualCodePoints, limit=$limit")

/**
 * Register the guard at startup. Routes inject the resulting [ContentLengthGuard]
 * via Koin. Each input-bearing feature change should update the map by adding its
 * own key-limit entry here — one place, easy to audit.
 */
fun Application.installContentLengthGuard(): ContentLengthGuard {
    val guard =
        ContentLengthGuard(
            limits =
                mapOf(
                    // docs/05-Implementation.md: post content 280 code points.
                    "post.content" to 280,
                    // Reply content: 280 code points (matches post length — docs/05-Implementation.md §733).
                    "reply.content" to 280,
                    // Chat / bio / display_name / username register in later changes.
                ),
        )
    log.info("ContentLengthGuard installed with {} registered keys", guard.limitFor("post.content"))
    return guard
}

private val log = LoggerFactory.getLogger(ContentLengthGuard::class.java)
