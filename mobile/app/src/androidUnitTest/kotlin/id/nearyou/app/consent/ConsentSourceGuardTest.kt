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
}
