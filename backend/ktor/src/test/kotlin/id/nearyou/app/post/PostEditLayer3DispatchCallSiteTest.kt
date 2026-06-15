package id.nearyou.app.post

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Static source-scan for the `premium-post-editing` Layer-3 dispatch call-site
 * contract (tasks.md 5.17), mirroring
 * [id.nearyou.app.moderation.Layer3DispatchCallSiteTest]: the edit path MUST
 * dispatch `layer3DispatcherScope.dispatch(...)` AFTER the `posts.updateContent(`
 * UPDATE (so a rolled-back edit never produces a Layer 3 row), carrying
 * `coroutineContext` for OTel trace propagation.
 *
 * The "Reject verdict dispatches nothing" half of 5.17 is a RUNTIME assertion in
 * [PostEditServiceTest] (the Reject path throws before commit, so no dispatch and no
 * committed row); a static scan can only pin call ORDER, which it does here.
 */
class PostEditLayer3DispatchCallSiteTest : StringSpec({

    "PostEditService dispatches Layer 3 AFTER posts.updateContent UPDATE, carrying coroutineContext" {
        val source = File("src/main/kotlin/id/nearyou/app/post/PostEditService.kt").readText()

        val updateMarker = source.indexOf("posts.updateContent(")
        val dispatchMarker = source.indexOf("layer3DispatcherScope.dispatch")
        val moderateMarker = source.indexOf("layer3Moderator")
        val contextMarker = source.indexOf("dispatch(coroutineContext)")

        require(updateMarker > 0) { "posts.updateContent(...) not found in PostEditService.kt" }
        require(dispatchMarker > 0) { "layer3DispatcherScope.dispatch not found in PostEditService.kt" }
        require(moderateMarker > 0) { "layer3Moderator reference not found in PostEditService.kt" }
        require(contextMarker > 0) { "dispatch(coroutineContext) not found — OTel context must propagate" }

        // The dispatch call MUST appear AFTER the UPDATE (Layer 3 is post-commit).
        (dispatchMarker > updateMarker) shouldBe true
    }

    "PostEditService moderates BEFORE the content UPDATE (re-moderation gate, design D1)" {
        val source = File("src/main/kotlin/id/nearyou/app/post/PostEditService.kt").readText()

        val moderateCallMarker = source.indexOf("textModerator.moderate(")
        val updateMarker = source.indexOf("posts.updateContent(")

        require(moderateCallMarker > 0) { "textModerator.moderate(...) not found in PostEditService.kt" }
        require(updateMarker > 0) { "posts.updateContent(...) not found in PostEditService.kt" }

        // Synchronous moderation MUST run before the content write (Reject short-circuits
        // to 400 before any snapshot/update).
        (moderateCallMarker < updateMarker) shouldBe true
    }
})
