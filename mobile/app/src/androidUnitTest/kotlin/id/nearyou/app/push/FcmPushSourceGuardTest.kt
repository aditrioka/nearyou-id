package id.nearyou.app.push

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strips block comments (incl. KDoc) then line comments, so an explanatory KDoc that names a forbidden
 *  token does not trip the scan (mirrors `LocationSourceGuardTest`). */
private fun String.stripKotlinComments(): String {
    val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(this, " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/**
 * Static-source guards for `mobile-fcm-token-registration` invariants that are properties of the source, not
 * of a render. Mirrors the repo `LocationSourceGuardTest` file-scan idiom (walk to the repo root, read source,
 * assert). Runs in every variant (not a `*ScreenTest`).
 *
 * Covers:
 * - **Firebase confinement (the mobile-module enforcement the global `VendorSdkLeakageScanTest` does NOT
 *   provide — its roots are the `core` modules + `backend/ktor` only):** no commonMain push source references the
 *   Firebase / platform-notification SDK; the Firebase imports appear ONLY in the Android / iOS actuals.
 * - **Token confidentiality (no-token-in-logs):** no enumerated log sink in the registrar / api client / both
 *   actuals receives the token, and the registration path does not widen `HttpClient` logging to BODY/ALL.
 */
class FcmPushSourceGuardTest {
    private val repoRoot: File = findRepoRoot()

    private fun rawSource(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.exists(), "expected source file missing: $relativePath (resolved under $repoRoot)")
        return file.readText()
    }

    private fun code(relativePath: String): String = rawSource(relativePath).stripKotlinComments()

    private val commonBase = "mobile/app/src/commonMain/kotlin/id/nearyou/app/push"
    private val androidProvider by lazy { code("mobile/app/src/androidMain/kotlin/id/nearyou/app/push/AndroidFcmTokenProvider.kt") }
    private val iosProvider by lazy { code("mobile/app/src/iosMain/kotlin/id/nearyou/app/push/IosFcmTokenProvider.kt") }
    private val registrar by lazy { code("$commonBase/FcmTokenRegistrar.kt") }
    private val apiClient by lazy { code("$commonBase/FcmTokenApiClient.kt") }
    private val providerInterface by lazy { code("$commonBase/FcmTokenProvider.kt") }

    // ---- Firebase confinement ----

    @Test
    fun commonMainPushSources_referenceNoPlatformSdkType() {
        val forbidden = listOf("com.google.firebase", "FirebaseMessaging", "Messaging", "UNUserNotificationCenter", "FIRMessaging")
        for ((name, src) in listOf(
            "FcmTokenProvider" to providerInterface,
            "FcmTokenApiClient" to apiClient,
            "FcmTokenRegistrar" to registrar,
            "FcmRegistrationOutcome" to code("$commonBase/FcmRegistrationOutcome.kt"),
        )) {
            for (token in forbidden) {
                assertFalse(src.contains(token), "$name (commonMain) must not reference the platform SDK type '$token'")
            }
        }
    }

    @Test
    fun firebaseImports_appearOnlyInPlatformActuals() {
        // The actuals DO use the SDK — assert each references its platform Firebase entry point.
        assertTrue(androidProvider.contains("FirebaseMessaging"), "the Android actual must use FirebaseMessaging")
        assertTrue(iosProvider.contains("FIRMessaging"), "the iOS actual must use the FirebaseMessaging Pod (FIRMessaging)")
    }

    // ---- token confidentiality: no-token-in-logs ----

    @Test
    fun pushSources_neverLogTheTokenThroughAnySink() {
        val logToken = Regex("""\bLog\.""")
        val printToken = Regex("""\bprint\s*\(""")
        for ((name, src) in listOf(
            "FcmTokenRegistrar" to registrar,
            "FcmTokenApiClient" to apiClient,
            "FcmRegistrationOutcome" to code("$commonBase/FcmRegistrationOutcome.kt"),
            "AndroidFcmTokenProvider" to androidProvider,
            "IosFcmTokenProvider" to iosProvider,
        )) {
            assertFalse(src.contains("println"), "$name must not println the token")
            assertFalse(printToken.containsMatchIn(src), "$name must not print( the token (Kotlin/Native stdout)")
            assertFalse(logToken.containsMatchIn(src), "$name must not use android.util.Log")
            assertFalse(src.contains("NSLog"), "$name must not NSLog")
            assertFalse(src.contains("os_log"), "$name must not os_log")
            assertFalse(src.contains("Napier"), "$name must not log via Napier")
            assertFalse(src.contains("Timber"), "$name must not log via Timber")
            assertFalse(src.contains("LogLevel.BODY"), "$name must not widen logging to BODY (the token rides in the request body)")
            assertFalse(src.contains("LogLevel.ALL"), "$name must not widen logging to ALL")
        }
    }

    @Test
    fun sharedHttpClientLoggingLevel_neverWidensToBodyOrAll() {
        // The registration request's token rides in the request BODY over the SHARED HttpClient
        // (FcmTokenApiClient does not build its own client). The spec's "the Logging level on the path that
        // carries the registration request is not BODY/ALL" therefore constrains HttpClientFactory, not just
        // the push files — assert it here so the credential-confidentiality claim is test-enforced end-to-end.
        val factory = code("mobile/app/src/commonMain/kotlin/id/nearyou/app/network/HttpClientFactory.kt")
        assertFalse(
            factory.contains("LogLevel.BODY"),
            "the shared HttpClient must not log at LogLevel.BODY (the FCM token rides in the body)",
        )
        assertFalse(factory.contains("LogLevel.ALL"), "the shared HttpClient must not log at LogLevel.ALL")
    }

    private companion object {
        fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).canonicalFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            return dir ?: error("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
        }
    }
}
