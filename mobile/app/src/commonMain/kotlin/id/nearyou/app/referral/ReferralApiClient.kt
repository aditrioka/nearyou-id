package id.nearyou.app.referral

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

/** `GET /api/v1/user/referral` response — the caller's invite code + referral progress (bare camelCase). */
@Serializable
data class ReferralStateDto(
    val inviteCode: String,
    val grantedReferrals: Int,
    val milestone: Int,
    val inviterRewardClaimed: Boolean,
)

/** Low-level result of the referral-state read. The repository maps this to a domain state (or failure). */
sealed interface ReferralStateResult {
    data class Success(
        val inviteCode: String,
        val grantedReferrals: Int,
        val milestone: Int,
        val inviterRewardClaimed: Boolean,
    ) : ReferralStateResult

    /** Non-2xx OR a transport/parse failure — the screen renders its error state. */
    data object Failure : ReferralStateResult
}

/**
 * Thin wrapper over the shared (bearer-authed) [HttpClient] for `GET /api/v1/user/referral`. The
 * `Auth { bearer }` interceptor on the shared client supplies the access token — this client adds no
 * auth header and never touches the token. A `CancellationException` is rethrown, never swallowed.
 * Mirrors `HideDistanceApiClient`.
 */
class ReferralApiClient(private val client: HttpClient) {
    suspend fun fetchState(): ReferralStateResult {
        val response: HttpResponse =
            try {
                client.get("/api/v1/user/referral")
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                return ReferralStateResult.Failure
            }
        if (response.status != HttpStatusCode.OK) return ReferralStateResult.Failure
        return try {
            val dto = response.body<ReferralStateDto>()
            ReferralStateResult.Success(
                inviteCode = dto.inviteCode,
                grantedReferrals = dto.grantedReferrals,
                milestone = dto.milestone,
                inviterRewardClaimed = dto.inviterRewardClaimed,
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            ReferralStateResult.Failure
        }
    }
}
