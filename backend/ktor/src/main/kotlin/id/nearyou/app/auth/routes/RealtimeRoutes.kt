package id.nearyou.app.auth.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import id.nearyou.app.auth.AUTH_PROVIDER_USER
import id.nearyou.app.auth.UserPrincipal
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Date

const val REALTIME_TOKEN_TTL_SECONDS = 3600L

@Serializable
data class RealtimeTokenResponse(
    val token: String,
    val expiresIn: Long,
)

class RealtimeTokenIssuer(
    private val hmacSecret: String,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun issue(userId: String): String {
        val now = nowProvider()
        return JWT.create()
            .withSubject(userId)
            .withClaim("role", "authenticated")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(REALTIME_TOKEN_TTL_SECONDS)))
            .sign(Algorithm.HMAC256(hmacSecret))
    }
}

fun Application.realtimeRoutes(issuer: RealtimeTokenIssuer) {
    routing {
        authenticate(AUTH_PROVIDER_USER) {
            get("/api/v1/realtime/token") {
                val principal = call.principal<UserPrincipal>()!!
                val token = issuer.issue(principal.userId.toString())
                call.respond(RealtimeTokenResponse(token = token, expiresIn = REALTIME_TOKEN_TTL_SECONDS))
            }
        }
    }
}
