package id.nearyou.app.chat

import id.nearyou.app.infra.supabaserealtime.RealtimeTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The realtime-token envelope, mirroring the SHIPPED backend `RealtimeTokenResponse`
 * (`backend/ktor/.../auth/routes/RealtimeRoutes.kt`): `{ "token": "<jws>", "expires_in": 3600 }`.
 * `expires_in` is **snake_case** per the auth-route convention (NOT camelCase, NOT a bare string — the
 * D9 wire-mirroring discipline). A test asserts the envelope parses to `token` + `expiresIn: Long`.
 */
@Serializable
data class RealtimeTokenResponseDto(
    val token: String,
    @SerialName("expires_in") val expiresIn: Long,
)

/**
 * Thin wrapper over the shared [HttpClient] for `GET /api/v1/realtime/token`, doubling as the
 * [RealtimeTokenProvider] the `:infra:supabase-realtime` subscriber injects (so the infra module need
 * not depend on `:mobile:app`). The Bearer `Authorization` header is attached by the shipped `Auth`
 * plugin — this client MUST NOT reimplement token attachment.
 *
 * **Token discipline (task 5.3):** the returned HS256 JWS is NEVER logged, NEVER written to disk, and
 * NEVER captured into a `@Serializable` `NavKey` / saved-state holder. It is fetched fresh per
 * (re)connect by [fetchToken] (called from supabase-kt's `accessToken` provider) and held only inside
 * the SDK's socket for the connection's lifetime. The companion-token's expiry drives supabase-kt's
 * internal re-auth cadence (it decodes the JWT `exp`); [getRealtimeToken] additionally surfaces
 * `expiresIn` for tests + any future explicit-refresh scheduling.
 */
class RealtimeTokenApiClient(
    private val client: HttpClient,
) : RealtimeTokenProvider {
    /** Fetches the token + its TTL. Throws on transport / non-2xx (the caller degrades to REST-only). */
    suspend fun getRealtimeToken(): RealtimeTokenResponseDto = client.get("/api/v1/realtime/token").body()

    /**
     * [RealtimeTokenProvider] entry point invoked by supabase-kt on every (re)connection. Returns the
     * fresh JWS string; a thrown failure surfaces to the SDK, which logs + retries — the chat thread
     * stays usable in REST-only mode regardless (design D4).
     */
    override suspend fun fetchToken(): String = getRealtimeToken().token
}
