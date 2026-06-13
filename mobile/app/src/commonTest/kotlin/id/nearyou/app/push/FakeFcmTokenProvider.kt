package id.nearyou.app.push

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * commonTest double for [FcmTokenProvider] — a configurable `currentToken()` return + a manually-emittable
 * [tokenRefreshes] (drives both the cold-acquire and the rotation paths without any platform dependency).
 * A `null` [token] models the iOS denied-authorization / no-configured-FirebaseApp path.
 */
class FakeFcmTokenProvider(
    var token: String? = "fake-token",
) : FcmTokenProvider {
    var currentTokenCalls: Int = 0
        private set

    private val refreshes = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)

    override suspend fun currentToken(): String? {
        currentTokenCalls++
        return token
    }

    override val tokenRefreshes: Flow<String> = refreshes

    /** Emit a rotated token to any active [observeTokenRefreshes][FcmTokenRegistrar.observeTokenRefreshes] collector. */
    suspend fun emitRefresh(newToken: String) = refreshes.emit(newToken)
}
