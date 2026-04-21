package id.nearyou.app.dev

import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.jwt.RsaKeyLoader
import java.util.UUID

/**
 * Dev-only helper. Mints an access JWT for an existing `users.id` so manual curl smoke
 * tests can hit authenticated endpoints without the real Google/Apple OAuth round-trip.
 *
 * Reads `KTOR_RSA_PRIVATE_KEY` from the environment (same value the running server uses
 * to sign + verify), so the printed token validates against the live `Authentication`
 * plugin without further configuration.
 *
 * Invoke via `dev/scripts/mint-dev-jwt.sh <user-uuid> [token-version]` — the wrapper sources
 * `dev/.env` and shells out to the `mintDevJwt` Gradle task. Do not call this in production.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: mint-dev-jwt <user-uuid> [token-version]")
        kotlin.system.exitProcess(2)
    }
    val userId =
        try {
            UUID.fromString(args[0])
        } catch (_: IllegalArgumentException) {
            System.err.println("first arg must be a valid UUID")
            kotlin.system.exitProcess(2)
        }
    val tokenVersion = args.getOrNull(1)?.toIntOrNull() ?: 0
    val pem =
        System.getenv("KTOR_RSA_PRIVATE_KEY")?.takeIf { it.isNotBlank() }
            ?: run {
                System.err.println("KTOR_RSA_PRIVATE_KEY is not set; source dev/.env first")
                kotlin.system.exitProcess(1)
            }
    val keys = RsaKeyLoader(pem)
    val issuer = JwtIssuer(keys)
    print(issuer.issueAccessToken(userId, tokenVersion))
}
