package id.nearyou.app.consent

import kotlinx.coroutines.CompletableDeferred

/**
 * Test double for [ConsentFlow] — returns a fixed [outcome] and records each submit (count + the
 * last-submitted triple) so screen + ViewModel tests can drive a specific outcome and assert
 * (non-)invocation without a backend (mirrors `FakeAuthFlow`).
 *
 * Pass [gate] to make `submitConsent` SUSPEND until it completes — so a ViewModel's single-in-flight
 * guard can be exercised deterministically (a second tap while one is pending must NOT increment the
 * count). Defaulted `null` = no suspension, so existing call sites are unaffected.
 */
class FakeConsentFlow(
    private val outcome: ConsentOutcome = ConsentOutcome.Success,
    private val gate: CompletableDeferred<Unit>? = null,
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
        gate?.await()
        return outcome
    }
}
