package id.nearyou.app.privateprofile

/** The private-profile toggle's current state for the Settings screen. */
data class PrivateProfileState(
    val privateProfile: Boolean,
    val premium: Boolean,
)

/**
 * Settings data-layer seam (docs/11 §2.6) for the Premium private-profile toggle. The `SettingsViewModel`
 * talks to this, never to the [PrivateProfileApiClient]; a fake implementation backs the screen tests.
 */
interface PrivateProfileRepository {
    /** Current toggle state, or `null` on load failure (the screen then defaults to off / non-Premium). */
    suspend fun loadState(): PrivateProfileState?

    /** Persists the flag; `true` on success, `false` on any failure (the screen reverts the toggle). */
    suspend fun setPrivateProfile(value: Boolean): Boolean
}

class DefaultPrivateProfileRepository(
    private val apiClient: PrivateProfileApiClient,
) : PrivateProfileRepository {
    override suspend fun loadState(): PrivateProfileState? =
        when (val result = apiClient.fetchState()) {
            is PrivateProfileStateResult.Success -> PrivateProfileState(result.privateProfile, result.premium)
            PrivateProfileStateResult.Failure -> null
        }

    override suspend fun setPrivateProfile(value: Boolean): Boolean =
        apiClient.setPrivateProfile(value) is PrivateProfileWriteResult.Success
}
