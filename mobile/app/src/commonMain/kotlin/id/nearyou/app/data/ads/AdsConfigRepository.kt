package id.nearyou.app.data.ads

/**
 * The resolved ads-config state the ad-eligibility wiring consumes. [Disabled] is BOTH the server "ads off
 * / premium-suppressed" state AND the fail-safe state (fetch failure) — the client cannot tell them apart
 * and treats both as "no ads, no error chrome" (design D4).
 */
sealed interface AdsConfigOutcome {
    data class Enabled(val timelineFrequency: Int) : AdsConfigOutcome

    data object Disabled : AdsConfigOutcome
}

/** Fakeable seam the eligibility controller depends on (a `FakeAdsConfigFlow` drives the tests). */
fun interface AdsConfigFlow {
    suspend fun fetchConfig(): AdsConfigOutcome
}

/**
 * Maps the [AdsConfigApiClient] result to an [AdsConfigOutcome]. A fetch failure (network OR any non-200)
 * resolves to [AdsConfigOutcome.Disabled] — ads OFF, fail-safe, never error chrome (spec § "Config fetch
 * failure fails safe to no ads"). mobile-admob-ads-foundation.
 */
class AdsConfigRepository(
    private val apiClient: AdsConfigApiClient,
    private val diagnosticLog: (String) -> Unit = {},
) : AdsConfigFlow {
    override suspend fun fetchConfig(): AdsConfigOutcome =
        when (val result = apiClient.fetch()) {
            is AdsConfigApiResult.Success ->
                if (result.body.adsEnabled) {
                    AdsConfigOutcome.Enabled(result.body.timelineFrequency)
                } else {
                    AdsConfigOutcome.Disabled
                }
            is AdsConfigApiResult.HttpError -> {
                diagnosticLog("ads_config_http_error: status=${result.status}")
                AdsConfigOutcome.Disabled
            }
            is AdsConfigApiResult.NetworkError -> {
                diagnosticLog("ads_config_network_error: ${result.cause::class.simpleName}")
                AdsConfigOutcome.Disabled
            }
        }
}
