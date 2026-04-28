package id.nearyou.app.notifications

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Covers `fcm-push-dispatch` spec § "DI graph SHALL bind a composite
 * dispatcher in production and InAppOnlyDispatcher in tests, with catch-and-
 * log composite error handling" (NotificationService structural assertion +
 * DI test-isolation static-analysis guard).
 *
 * Tasks 7.5 + 7.5.a — structural file-text assertions, NOT brittle hash
 * snapshots. The point is to prevent accidental coupling of
 * `NotificationService` (or the four V10 emit-site services) to Firebase
 * Admin SDK types, AND to prevent test sources from accidentally
 * referencing the production composite binding outside the explicit
 * override-installation paths.
 */
class FcmDispatchStructuralTest : StringSpec(
    {
        val backendKtorMain =
            File("src/main/kotlin/id/nearyou/app").let {
                if (it.exists()) it else File("backend/ktor/src/main/kotlin/id/nearyou/app")
            }
        val backendKtorTest =
            File("src/test/kotlin/id/nearyou/app").let {
                if (it.exists()) it else File("backend/ktor/src/test/kotlin/id/nearyou/app")
            }

        // 7.5 — NotificationService + emit-site services contain no FCM /
        // Firebase coupling.
        "7.5 NotificationService.kt does not import FcmDispatcher or com.google.firebase.*" {
            val ns = backendKtorMain.resolve("notifications/NotificationService.kt")
            ns.exists() shouldBe true
            val text = ns.readText()
            text.contains("FcmDispatcher") shouldBe false
            text.contains("com.google.firebase") shouldBe false
        }

        "7.5 four V10 emit-site services contain no FcmDispatcher / com.google.firebase imports" {
            val emitSites =
                listOf(
                    backendKtorMain.resolve("engagement/LikeService.kt"),
                    backendKtorMain.resolve("engagement/ReplyService.kt"),
                    backendKtorMain.resolve("follow/FollowService.kt"),
                    backendKtorMain.resolve("moderation/ReportService.kt"),
                )
            emitSites.forEach { f ->
                f.exists() shouldBe true
                val text = f.readText()
                text.contains("FcmDispatcher") shouldBe false
                text.contains("com.google.firebase") shouldBe false
            }
        }

        // 7.5.a — DI test-isolation guard: no test source references
        // FcmAndInAppDispatcher outside the designated override-installation
        // path. (As of this change, NO test source SHOULD reference it —
        // production binding only. If a future test deliberately wants the
        // composite, install it via a clearly-named override module and add
        // its file path to ALLOWED_TEST_FILES below.)
        "7.5.a no test source references FcmAndInAppDispatcher outside the designated override path" {
            val allowedTestFiles =
                setOf(
                    // The static-analysis guard itself MUST reference the
                    // class name to scan for it. This is the allowlist's
                    // self-reference — every other test file is forbidden.
                    "notifications/FcmDispatchStructuralTest.kt",
                )
            val offenders =
                backendKtorTest
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { f -> f.readText().contains("FcmAndInAppDispatcher") }
                    .filter { f -> f.relativeTo(backendKtorTest).path !in allowedTestFiles }
                    .map { it.relativeTo(backendKtorTest).path }
                    .toList()
            offenders shouldBe emptyList()
        }
    },
)
