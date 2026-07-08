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

    // mobile-push-message-handling (task 7.1/7.3): the display + tap-routing sources.
    private val androidPushBase = "mobile/app/src/androidMain/kotlin/id/nearyou/app/push"
    private val incomingPushHandler by lazy { code("$androidPushBase/IncomingPushHandler.kt") }
    private val pushBatchTracker by lazy { code("$androidPushBase/PushBatchTracker.kt") }
    private val commonMainDisplaySources by lazy {
        mapOf(
            "NotificationContentPreference" to code("$commonBase/NotificationContentPreference.kt"),
            "PushDisplayCopy" to code("$commonBase/PushDisplayCopy.kt"),
            "PushTapNavSignal" to code("$commonBase/PushTapNavSignal.kt"),
            "NotificationNavigation" to
                code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/notifications/NotificationNavigation.kt"),
            "PushTapNavigationEffect" to
                code("mobile/app/src/commonMain/kotlin/id/nearyou/app/screens/routing/PushTapNavigationEffect.kt"),
        )
    }

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
    fun commonMainDisplaySources_referenceNoPlatformNotificationSdk() {
        // mobile-push-message-handling task 7.1: the display / tap-routing commonMain sources must
        // not leak Firebase, UNUserNotificationCenter/UserNotifications, or NotificationCompat —
        // those stay in androidMain (IncomingPushHandler) / iosMain / the NSE target.
        val forbidden =
            listOf(
                "com.google.firebase", "FirebaseMessaging", "FIRMessaging", "RemoteMessage",
                "UNUserNotificationCenter", "UserNotifications", "UNNotification",
                "NotificationCompat", "NotificationChannel", "NotificationManager",
            )
        for ((name, src) in commonMainDisplaySources) {
            for (token in forbidden) {
                assertFalse(src.contains(token), "$name (commonMain) must not reference the platform SDK type '$token'")
            }
        }
    }

    @Test
    fun androidDisplaySources_carryNoHardcodedUserVisibleNotificationCopy() {
        // mobile-push-message-handling task 7.2: the notification copy lives in :shared:resources
        // (PushDisplayCopy), never as literals in the Android display source. Scan the
        // comment-stripped source for the copy catalog's distinctive words inside string literals.
        val copyWords = listOf("Pesan baru", "menyukai", "membalas", "mengikuti", "mengirim", "Notifikasi baru", "disembunyikan")
        val stringLiterals = Regex("\"([^\"\\n]*)\"").findAll(incomingPushHandler + pushBatchTracker).map { it.groupValues[1] }
        for (literal in stringLiterals) {
            for (word in copyWords) {
                assertFalse(
                    literal.contains(word),
                    "Android display source must not hardcode user-visible copy (found \"$literal\" — use :shared:resources)",
                )
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
    fun displayAndTapRoutingSources_reachNoLogSink() {
        // mobile-push-message-handling task 7.3: no actor_user_id / target_id / conversation_id /
        // message preview / raw FCM token can reach a log sink from the display + tap-routing paths
        // — enforced the same way the token-log guard above is: the sources contain NO log-sink
        // call at all (mirrors the mobile-fcm-token-registration idiom; prose alone is insufficient).
        val logToken = Regex("""\bLog\.""")
        val printToken = Regex("""\bprint\s*\(""")
        val sources =
            commonMainDisplaySources +
                mapOf(
                    "IncomingPushHandler" to incomingPushHandler,
                    "PushBatchTracker" to pushBatchTracker,
                    "MainActivity(tap-routing)" to code("mobile/app/src/androidMain/kotlin/id/nearyou/app/MainActivity.kt"),
                    // The spec scenario explicitly scopes "the iOS delegate/NSE" — the Kotlin iOS
                    // sources AND the Swift NSE target source (same comment syntax, so the
                    // comment-stripper applies).
                    "PushNotificationTapDelegate" to
                        code("mobile/app/src/iosMain/kotlin/id/nearyou/app/push/PushNotificationTapDelegate.kt"),
                    "NsePayloadProjection" to code("mobile/app/src/iosMain/kotlin/id/nearyou/app/push/NsePayloadProjection.kt"),
                    "IosNotificationContentPreferenceStore" to
                        code("mobile/app/src/iosMain/kotlin/id/nearyou/app/push/IosNotificationContentPreferenceStore.kt"),
                    "NotificationService.swift(NSE)" to code("iosApp/NotificationService/NotificationService.swift"),
                )
        for ((name, src) in sources) {
            assertFalse(src.contains("println"), "$name must not println (ids/preview would reach stdout)")
            assertFalse(printToken.containsMatchIn(src), "$name must not print(")
            assertFalse(logToken.containsMatchIn(src), "$name must not use android.util.Log")
            assertFalse(src.contains("NSLog"), "$name must not NSLog")
            assertFalse(src.contains("os_log"), "$name must not os_log")
            assertFalse(src.contains("Napier"), "$name must not log via Napier")
            assertFalse(src.contains("Timber"), "$name must not log via Timber")
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
