package id.nearyou.app.moderation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Structural source-scan test for the
 * `text-moderation-perspective-api-layer` capability per task 9.3 + spec
 * `### Requirement: Async dispatch wired at posts and post_replies INSERT call
 * sites with OTel context propagation`.
 *
 * Three assertions:
 *
 *  - `CreatePostService.kt` invokes `perspectiveDispatcherScope.dispatch` AFTER
 *    the canonical `posts.create(` INSERT call.
 *  - `ReplyService.kt` invokes `perspectiveDispatcherScope.dispatch` AFTER the
 *    canonical `replies.insertInTx(` INSERT call.
 *  - Chat send service files do NOT contain `perspectiveDispatcherScope.dispatch`
 *    OR `perspectiveModerator.moderate` invocations — chat-message Layer 3 is
 *    explicitly deferred per design Open Question 1.
 *
 * Implementation mirrors [PostCreationCallOrderTest] / [ChatModerationCallOrderTest]:
 * read the source file as text, find substring positions, assert ordering. No
 * runtime exercise needed — this is a static-analysis assertion.
 */
class PerspectiveDispatchCallSiteTest : StringSpec({

    "CreatePostService dispatches Perspective Layer 3 AFTER posts.create INSERT" {
        val source = File("src/main/kotlin/id/nearyou/app/post/CreatePostService.kt").readText()

        val insertMarker = source.indexOf("posts.create(")
        val dispatchMarker = source.indexOf("perspectiveDispatcherScope.dispatch") // intentional: substring matched by lint scan
        val moderateMarker = source.indexOf("perspectiveModerator")

        require(insertMarker > 0) { "posts.create(...) not found in CreatePostService.kt" }
        require(dispatchMarker > 0) { "perspectiveDispatcherScope.dispatch not found in CreatePostService.kt" }
        require(moderateMarker > 0) { "perspectiveModerator reference not found in CreatePostService.kt" }

        // The dispatch call MUST appear AFTER the INSERT call (Layer 3 is post-INSERT).
        (dispatchMarker > insertMarker) shouldBe true
    }

    "ReplyService dispatches Perspective Layer 3 AFTER replies.insertInTx INSERT" {
        val source = File("src/main/kotlin/id/nearyou/app/engagement/ReplyService.kt").readText()

        val insertMarker = source.indexOf("replies.insertInTx(")
        val dispatchMarker = source.indexOf("perspectiveDispatcherScope.dispatch")
        val moderateMarker = source.indexOf("perspectiveModerator")

        require(insertMarker > 0) { "replies.insertInTx(...) not found in ReplyService.kt" }
        require(dispatchMarker > 0) { "perspectiveDispatcherScope.dispatch not found in ReplyService.kt" }
        require(moderateMarker > 0) { "perspectiveModerator reference not found in ReplyService.kt" }

        (dispatchMarker > insertMarker) shouldBe true
    }

    "Chat send service files do NOT carry the Layer 3 dispatch (deferred per Open Question 1)" {
        val chatDir = File("src/main/kotlin/id/nearyou/app/chat")
        require(chatDir.isDirectory) { "chat dir not found at ${chatDir.absolutePath}" }
        val serviceFiles =
            chatDir.listFiles { f -> f.isFile && f.name.endsWith("Service.kt") }
                ?: error("no chat service files discovered")
        require(serviceFiles.isNotEmpty()) { "expected at least one *Service.kt under chat/" }

        for (f in serviceFiles) {
            val src = f.readText()
            check(!src.contains("perspectiveDispatcherScope.dispatch")) {
                "${f.name} unexpectedly contains 'perspectiveDispatcherScope.dispatch' " +
                    "— chat-message Layer 3 is deferred per `text-moderation-perspective-api-layer` " +
                    "design Open Question 1"
            }
            check(!src.contains("perspectiveModerator.moderate")) {
                "${f.name} unexpectedly contains 'perspectiveModerator.moderate' " +
                    "— chat-message Layer 3 is deferred per `text-moderation-perspective-api-layer` " +
                    "design Open Question 1"
            }
        }
    }
})
