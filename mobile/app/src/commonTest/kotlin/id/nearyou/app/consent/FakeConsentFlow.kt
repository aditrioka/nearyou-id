package id.nearyou.app.consent

/**
 * Test double for [ConsentFlow] — returns a fixed [outcome] and records each submit (count + the
 * last-submitted triple) so screen tests can drive a specific outcome and assert (non-)invocation
 * without a backend (mirrors `FakeAuthFlow`).
 */
class FakeConsentFlow(
    private val outcome: ConsentOutcome = ConsentOutcome.Success,
) : ConsentFlow {
    var submitInvocationCount = 0
        private set
    var lastSubmitted: Triple<Boolean, Boolean, Boolean>? = null
        private set

    override suspend fun submitConsent(
        analytics: Boolean,
        crash: Boolean,
        adsPersonalization: Boolean,
    ): ConsentOutcome {
        submitInvocationCount++
        lastSubmitted = Triple(analytics, crash, adsPersonalization)
        return outcome
    }
}
