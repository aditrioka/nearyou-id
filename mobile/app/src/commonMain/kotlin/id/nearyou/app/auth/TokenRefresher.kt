package id.nearyou.app.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * The single owner of the `POST /api/v1/auth/refresh` round-trip, shared by BOTH the reactive
 * (Ktor `Auth` bearer `refreshTokens`) path and the proactive on-resume trigger
 * (mobile-session-expiry-and-proactive-refresh, design D2).
 *
 * **Single-flight (the load-bearing contract):** a concurrent caller that arrives while a refresh
 * is already in flight **awaits and reuses** the in-flight result rather than issuing its own POST,
 * so an overlapping proactive + reactive pair performs **exactly ONE** network refresh (preserving
 * the shipped "Concurrent 401s … retry once" guarantee). Implemented as a leader/follower split over
 * a [Mutex] + a shared [CompletableDeferred]: the FIRST caller becomes the leader and performs the
 * POST **in its own coroutine** (so the round-trip stays on the caller's dispatcher — `runTest`-/
 * test-scheduler-friendly, never an orphaned background coroutine); every follower awaits the
 * leader's deferred and returns its `TokenPair`.
 *
 * The [HttpClient] is passed **per call** (`refresh(client)`) rather than held as a field: both the
 * reactive `RefreshTokensParams.client` and the proactively-injected shared client are the same
 * singleton, and passing it avoids a Koin construction cycle (`HttpClient` ↔ `TokenRefresher`).
 *
 * On a rejected refresh token (null refresh token in the store, or a non-success refresh response)
 * the refresh funnels through [SessionInvalidator.invalidate] → the reliable re-route to
 * `SignInScreen`. A transport failure (IOException/timeout) is NOT swallowed: it propagates so the
 * caller surfaces a connectivity error and the token pair is kept (no spurious logout).
 */
class TokenRefresher(
    private val tokenStore: TokenStore,
    private val sessionInvalidator: SessionInvalidator,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var inFlight: CompletableDeferred<TokenPair?>? = null

    /**
     * Refresh the access token, single-flighted across all callers. Returns the new [TokenPair] on
     * success, or `null` when the session was invalidated (rejected refresh token). Re-throws a
     * transport failure (so a connectivity error stays a connectivity error, not a logout).
     */
    suspend fun refresh(client: HttpClient): TokenPair? {
        val (isLeader, deferred) =
            mutex.withLock {
                val existing = inFlight
                if (existing != null) {
                    false to existing
                } else {
                    val fresh = CompletableDeferred<TokenPair?>()
                    inFlight = fresh
                    true to fresh
                }
            }
        if (!isLeader) {
            // Follower: await the in-flight refresh and REUSE its result — does NOT re-POST.
            return deferred.await()
        }
        return try {
            val result = performRefresh(client)
            deferred.complete(result)
            result
        } catch (cause: Throwable) {
            // Publish the failure to any follower (so it does not hang), then propagate.
            deferred.completeExceptionally(cause)
            throw cause
        } finally {
            mutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }

    private suspend fun performRefresh(client: HttpClient): TokenPair? {
        val refreshToken = tokenStore.read()?.refreshToken
        if (refreshToken == null) {
            sessionInvalidator.invalidate()
            return null
        }
        val response: HttpResponse =
            client.post("/api/v1/auth/refresh") {
                // Equivalent to `RefreshTokensParams.markAsRefreshTokenRequest()`: tag the request with
                // the AuthCircuitBreaker attribute so the shared `Auth` plugin does NOT attempt a
                // refresh-on-401 of the refresh POST itself (which would re-enter this single-flight and
                // deadlock the Mutex). The member-extension is only callable inside the `refreshTokens { }`
                // block; the proactive path issues the POST here, so the same attribute is set directly.
                attributes.put(AuthCircuitBreaker, Unit)
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken))
            }
        return if (response.status.isSuccess()) {
            val body = response.body<SignInResponse>()
            val tokens =
                TokenPair(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    accessExpiresAtEpochMillis = nowMillis() + body.expiresIn * 1000L,
                )
            tokenStore.write(tokens)
            tokens
        } else {
            // Refresh token expired/revoked (terminal) → clear the store + signal the re-route.
            sessionInvalidator.invalidate()
            null
        }
    }
}
