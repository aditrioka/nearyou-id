package id.nearyou.app.screens.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments — mirrors PostCreationSourceGuardTest. */
private fun String.stripComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * `profile-send-message` — the `mobile-chat` scenario "The thread ViewModel carries no create-or-return
 * entry": `ChatThreadViewModel` is keyed on an existing conversation id and is never a create-or-return
 * caller (the other-user profile "Kirim pesan" via `ProfileViewModel` is the sole one). Guards against
 * the deleted placeholder (`startConversation` / `startedConversationId` / `startOutcome`) creeping back.
 * Not a `*ScreenTest`, so it runs in every variant.
 */
class ChatThreadViewModelSourceGuardTest {
    @Test
    fun chatThreadViewModel_carriesNoCreateOrReturnEntry() {
        val file = File(findRepoRoot(), "mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/chat/ChatThreadViewModel.kt")
        assertTrue(file.exists(), "expected source file missing: ${file.path}")
        val code = file.readText().stripComments()
        for (token in listOf("startConversation", "startedConversationId", "startOutcome", "createOrReturn")) {
            assertFalse(code.contains(token), "ChatThreadViewModel must not declare/call `$token` (profile is the sole caller)")
        }
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
    }
}
