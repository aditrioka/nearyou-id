package id.nearyou.app.consent

import kotlinx.coroutines.sync.Mutex

/**
 * The consent-submit orchestration contract consumed by `ConsentScreen`. The production binding is
 * [ConsentRepository]; commonTest substitutes a `FakeConsentFlow` so screen tests can drive a
 * specific outcome / assert (non-)invocation without a backend.
 */
interface ConsentFlow {
    /**
     * Submits the complete consent triple via `PATCH /api/v1/user/consent` and returns the
     * status-driven [ConsentOutcome]. A concurrent invocation while one is in flight returns
     * [ConsentOutcome.Cancelled] (no second PATCH).
     */
    suspend fun submitConsent(
        analytics: Boolean,
        crash: Boolean,
        adsPersonalization: Boolean,
    ): ConsentOutcome
}

/**
 * Maps the low-level [ConsentApiClient] result onto exactly one [ConsentOutcome], keyed on the HTTP
 * status / transport type (design D5) — no generic fallthrough. A double-tap while a submit is in
 * flight is rejected by the [submitMutex] `tryLock` guard (returns [ConsentOutcome.Cancelled] — no
 * second PATCH), mirroring `AuthRepository`'s defense.
 *
 * The optional [diagnosticLog] is a non-user-facing sink (wired to Sentry / OTel when that lands;
 * no-op for now). It MUST NOT carry a token, the JWT `sub`, or the consent body — only a status /
 * transport-cause envelope (PII discipline, `mobile-analytics-consent` spec).
 */
class ConsentRepository(
    private val apiClient: ConsentApiClient,
    private val diagnosticLog: (String) -> Unit = {},
) : ConsentFlow {
    private val submitMutex = Mutex()

    override suspend fun submitConsent(
        analytics: Boolean,
        crash: Boolean,
        adsPersonalization: Boolean,
    ): ConsentOutcome {
        if (!submitMutex.tryLock()) {
            // A submit is already in flight — reject the concurrent invocation silently.
            return ConsentOutcome.Cancelled
        }
        return try {
            map(apiClient.submitConsent(analytics, crash, adsPersonalization))
        } finally {
            submitMutex.unlock()
        }
    }

    private fun map(result: ConsentApiResult): ConsentOutcome =
        when (result) {
            is ConsentApiResult.Success -> ConsentOutcome.Success
            is ConsentApiResult.NetworkError -> {
                // Type-only, not cause.message (a transport exception message can embed the request URL)
                // — the module-wide diagnostic convention (2026-06-10 audit, 06 low).
                diagnosticLog("consent_network_error: ${result.cause::class.simpleName}")
                ConsentOutcome.RetryableError
            }
            is ConsentApiResult.HttpError ->
                when (result.status) {
                    // The just-minted token failed verification — terminal token-invalid (no route).
                    401 -> ConsentOutcome.TokenInvalid
                    // A 400 is a client bug here (the screen always sends a well-formed triple) — surface
                    // as retryable WITH a logged diagnostic rather than a silent no-op / crash.
                    400 -> {
                        diagnosticLog("consent_invalid_request: status=400")
                        ConsentOutcome.RetryableError
                    }
                    // 5xx / 503 / any other unenumerated status: a defined retryable state, NOT a
                    // generic "consent failed" fallthrough.
                    else -> ConsentOutcome.RetryableError
                }
        }
}
