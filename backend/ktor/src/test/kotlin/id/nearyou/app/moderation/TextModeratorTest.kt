package id.nearyou.app.moderation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for [TextModerator]. Covers the 6 spec scenarios in
 * `### Requirement: TextModerator.moderate(content) orchestrates loader + matcher into a Verdict`
 * + the 2 Sentry-payload-content tests in
 * `### Requirement: TextModerator Sentry events do NOT carry user content`.
 *
 * Uses an in-process [RecordingLoader] for spy verification (call counts, profanity-precedence
 * short-circuit confirmation).
 */
class TextModeratorTest : StringSpec({

    "Single profanity match short-circuits to Reject — UU ITE not loaded" {
        val loader =
            RecordingLoader(
                profanity = listOf("badword"),
                uuIte = listOf("sara"),
                threshold = 3,
            )
        val moderator = TextModerator(loader)

        val verdict = moderator.moderate("this contains badword somewhere")

        verdict.shouldBeRejectMatching(listOf("badword"))
        // Profanity-precedence: UU ITE list and threshold are NOT loaded.
        loader.uuIteLoadCount shouldBe 0
        loader.thresholdLoadCount shouldBe 0
    }

    "UU ITE single match below threshold returns Allow" {
        val loader =
            RecordingLoader(
                profanity = emptyList(),
                uuIte = listOf("sara1", "sara2", "sara3"),
                threshold = 3,
            )
        val moderator = TextModerator(loader)

        moderator.moderate("only one sara1 in this text") shouldBe Verdict.Allow
    }

    "UU ITE matches at threshold returns Flag" {
        val loader =
            RecordingLoader(
                profanity = emptyList(),
                uuIte = listOf("sara1", "sara2", "sara3", "sara4"),
                threshold = 3,
            )
        val moderator = TextModerator(loader)

        val verdict = moderator.moderate("text with sara1 and sara2 and sara3")
        verdict.shouldBeFlagMatching(listOf("sara1", "sara2", "sara3"))
    }

    "UU ITE matches above threshold returns Flag" {
        val loader =
            RecordingLoader(
                profanity = emptyList(),
                uuIte = listOf("sara1", "sara2", "sara3", "sara4"),
                threshold = 3,
            )
        val moderator = TextModerator(loader)

        val verdict = moderator.moderate("text with sara1 and sara2 and sara3 and sara4")
        verdict.shouldBeFlagMatching(listOf("sara1", "sara2", "sara3", "sara4"))
    }

    "Both lists empty (fail-open) returns Allow" {
        val loader =
            RecordingLoader(
                profanity = emptyList(),
                uuIte = emptyList(),
                threshold = 3,
            )
        val moderator = TextModerator(loader)

        // Even content that would normally match SOMETHING passes — both lists are empty,
        // matcher cannot produce any matches.
        moderator.moderate("any content here, including profane words") shouldBe Verdict.Allow
    }

    "Profanity precedence — content matching both lists is Rejected, not Flagged" {
        val loader =
            RecordingLoader(
                profanity = listOf("badword"),
                uuIte = listOf("sara1", "sara2", "sara3"),
                threshold = 3,
            )
        val moderator = TextModerator(loader)

        val verdict = moderator.moderate("this contains badword and sara1, sara2, sara3")
        verdict.shouldBeRejectMatching(listOf("badword"))
        // Verifiable via the loader spy: UU ITE was never consulted.
        loader.uuIteLoadCount shouldBe 0
        loader.thresholdLoadCount shouldBe 0
    }

    "Reject Sentry breadcrumb omits raw content and matched keywords" {
        // The Sentry breadcrumb shape: log.info("event=content_moderation_rejected verdict_kind=reject matched_keyword_count=1")
        // Test contract: the structured log line MUST NOT contain the raw content sentinel
        // OR the matched keyword string. We verify by capturing the log output.
        val loader =
            RecordingLoader(
                profanity = listOf("badword"),
                uuIte = emptyList(),
                threshold = 3,
            )
        val moderator = TextModerator(loader)

        val captured =
            LogCapture.capture(TextModerator::class.java) {
                moderator.moderate("sentinel-content-DO-NOT-LEAK badword")
            }

        captured.shouldNotContain("sentinel-content-DO-NOT-LEAK")
        captured.shouldNotContain("badword")
        // Spec contract: the breadcrumb DOES carry the matched_keyword_count integer.
        captured shouldContain "matched_keyword_count=1"
        captured shouldContain "verdict_kind=reject"
    }

    "Flag Sentry breadcrumb omits matched keyword list" {
        val loader =
            RecordingLoader(
                profanity = emptyList(),
                uuIte = listOf("k1", "k2", "k3"),
                threshold = 3,
            )
        val moderator = TextModerator(loader)

        val captured =
            LogCapture.capture(TextModerator::class.java) {
                moderator.moderate("sentinel content k1 with k2 and k3 inside")
            }

        captured.shouldNotContain("sentinel content")
        captured.shouldNotContain("k1")
        captured.shouldNotContain("k2")
        captured.shouldNotContain("k3")
        captured shouldContain "matched_keyword_count=3"
        captured shouldContain "verdict_kind=flag"
    }
})

private fun Verdict.shouldBeRejectMatching(expected: List<String>) {
    require(this is Verdict.Reject) { "expected Reject but got $this" }
    matchedKeywords shouldContainExactly expected
}

private fun Verdict.shouldBeFlagMatching(expected: List<String>) {
    require(this is Verdict.Flag) { "expected Flag but got $this" }
    matchedKeywords shouldContainExactly expected
}

private infix fun String.shouldContain(substring: String) {
    if (!contains(substring)) {
        throw AssertionError("expected log capture to contain '$substring' but did not. Capture:\n$this")
    }
}

/**
 * Records loader call counts so tests verify the profanity-precedence short-circuit
 * (Layer 1 hit MUST short-circuit before Layer 2 is loaded).
 */
private class RecordingLoader(
    private val profanity: List<String>,
    private val uuIte: List<String>,
    private val threshold: Int,
) : ModerationListLoader {
    var profanityLoadCount: Int = 0
    var uuIteLoadCount: Int = 0
    var thresholdLoadCount: Int = 0

    override fun load(list: ModerationList): List<String> =
        when (list) {
            ModerationList.ProfanityList -> {
                profanityLoadCount += 1
                profanity
            }
            ModerationList.UuIteList -> {
                uuIteLoadCount += 1
                uuIte
            }
        }

    override fun loadThreshold(): Int {
        thresholdLoadCount += 1
        return threshold
    }
}

/**
 * Captures SLF4J output during a block. Used to verify breadcrumb payload contents.
 * Attaches a temporary appender to the logger named after [loggerSource], runs the
 * block, then detaches. Safe to call concurrently in different test threads — each
 * call attaches its own appender.
 */
private object LogCapture {
    fun capture(
        loggerSource: Class<*>,
        block: () -> Unit,
    ): String {
        val logger = org.slf4j.LoggerFactory.getLogger(loggerSource) as ch.qos.logback.classic.Logger
        val appender =
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
                start()
            }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.joinToString("\n") { event ->
            // Reconstruct the formatted message using the formatter the layout would use,
            // including arguments substituted into the {} placeholders.
            event.formattedMessage
        }
    }
}
