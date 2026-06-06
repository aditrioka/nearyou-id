package id.nearyou.app.consent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PII-discipline guard for the `mobile-analytics-consent` capability: no source file under the
 * consent packages may reference the bearer token / auth header (the shared `Auth { bearer }` client
 * supplies it — the consent code neither handles nor logs it). Comments are stripped FIRST so a KDoc
 * legitimately naming "token" does not trip the scan (per the source-scan-guard precedent). This is a
 * plain JVM test (file I/O), not a Robolectric UI test, so it runs in every variant.
 */
class ConsentSourceGuardTest {
    private val sources =
        listOf(
            "src/commonMain/kotlin/id/nearyou/app/screens/consent/ConsentScreen.kt",
            "src/commonMain/kotlin/id/nearyou/app/screens/consent/ConsentUiState.kt",
            "src/commonMain/kotlin/id/nearyou/app/consent/ConsentApiClient.kt",
            "src/commonMain/kotlin/id/nearyou/app/consent/ConsentFlow.kt",
            "src/commonMain/kotlin/id/nearyou/app/consent/ConsentOutcome.kt",
        )

    @Test
    fun consentSources_referenceNoAuthHeaderOrBearerToken() {
        val forbidden = listOf("Authorization", "Bearer", "accessToken", "refreshToken")
        for (path in sources) {
            val file = File(path)
            assertTrue(file.exists(), "expected consent source at $path")
            val stripped =
                file.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("//[^\n]*"), "")
            for (token in forbidden) {
                // The shared bearer client owns auth; consent code never handles/logs the token.
                assertTrue(!stripped.contains(token), "$path must not reference '$token' (PII discipline)")
            }
        }
    }

    /**
     * 8.11 (back-stack semantics, structural) — the signup-success leg REPLACES `AgeGateRoute` with
     * `ConsentRoute` (so the back stack holds `ConsentRoute` with no `AgeGateRoute` beneath →
     * back-press cannot re-enter the age gate), and the consent-done leg REPLACES with `HomeRoute`.
     * Both must use `replaceAll`, never `add` (push). Asserted on `AppEntryProvider` source because
     * driving the full age-gate → consent transition requires the DOB picker (impractical, per the
     * `mobile-age-gate` test limitation); `replaceAll` itself is a verified Nav3 primitive.
     */
    @Test
    fun appEntryProvider_reachesConsentAndHomeViaReplaceAll_notPush() {
        val stripped =
            File("src/commonMain/kotlin/id/nearyou/app/screens/routing/AppEntryProvider.kt")
                .readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\n]*"), "")
        assertTrue(
            stripped.contains("replaceAll(ConsentRoute)"),
            "age-gate signup-success must replaceAll(ConsentRoute)",
        )
        assertTrue(
            !stripped.contains("add(ConsentRoute)"),
            "ConsentRoute must be REPLACED in (not pushed onto) the stack — back-press must not reach the age gate",
        )
        assertTrue(
            Regex("entry<ConsentRoute>.*?replaceAll\\(HomeRoute\\)", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(stripped),
            "the ConsentRoute entry's onDone must replaceAll(HomeRoute)",
        )
    }
}
