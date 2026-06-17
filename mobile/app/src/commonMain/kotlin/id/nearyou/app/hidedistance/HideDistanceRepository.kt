package id.nearyou.app.hidedistance

/** The hide-distance toggle's current state for the Settings screen. */
data class HideDistanceState(
    val hideDistance: Boolean,
    val premium: Boolean,
)

/**
 * Settings data-layer seam (docs/11 §2.6) for the Premium hide-distance toggle. The `SettingsViewModel`
 * talks to this, never to the [HideDistanceApiClient]; a fake implementation backs the screen tests.
 */
interface HideDistanceRepository {
    /** Current toggle state, or `null` on load failure (the screen then defaults to off / non-Premium). */
    suspend fun loadState(): HideDistanceState?

    /** Persists the flag; `true` on success, `false` on any failure (the screen reverts the toggle). */
    suspend fun setHideDistance(value: Boolean): Boolean
}

class DefaultHideDistanceRepository(
    private val apiClient: HideDistanceApiClient,
) : HideDistanceRepository {
    override suspend fun loadState(): HideDistanceState? =
        when (val result = apiClient.fetchState()) {
            is HideDistanceStateResult.Success -> HideDistanceState(result.hideDistance, result.premium)
            HideDistanceStateResult.Failure -> null
        }

    override suspend fun setHideDistance(value: Boolean): Boolean = apiClient.setHideDistance(value) is HideDistanceWriteResult.Success
}
