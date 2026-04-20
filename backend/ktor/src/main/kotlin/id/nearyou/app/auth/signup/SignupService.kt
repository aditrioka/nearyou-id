package id.nearyou.app.auth.signup

import id.nearyou.app.auth.jwt.JwtIssuer
import id.nearyou.app.auth.provider.InvalidIdTokenException
import id.nearyou.app.auth.provider.ProviderIdTokenVerifier
import id.nearyou.app.auth.session.RefreshTokenService
import id.nearyou.app.infra.repo.IdentifierType
import id.nearyou.app.infra.repo.RejectedIdentifierRepository
import id.nearyou.app.infra.repo.RejectedReason
import id.nearyou.app.infra.repo.UserRepository
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.sql.DataSource

/**
 * Signup orchestration — combines provider verification, age gate,
 * rejected-identifier blocklist, atomic username insert, and access/refresh
 * token issuance into the single `POST /api/v1/auth/signup` surface.
 *
 * Exceptions (mapped by the route to HTTP statuses):
 * - [InvalidIdTokenException]           → 401 invalid_id_token
 * - [UserBlockedException]              → 403 user_blocked (canonical body)
 * - [UserExistsException]               → 409 user_exists
 * - [UsernameGenerationFailedException] → 503 username_generation_failed
 * - [InvalidRequestException]           → 400 invalid_request
 */
class SignupService(
    private val dataSource: DataSource,
    private val providers: SignupProviders,
    private val users: UserRepository,
    private val rejected: RejectedIdentifierRepository,
    private val usernameGenerator: UsernameGenerator,
    private val inviteDeriver: InviteCodePrefixDeriver,
    private val refreshTokens: RefreshTokenService,
    private val jwtIssuer: JwtIssuer,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SignupService::class.java)

    class SignupProviders(
        val google: ProviderIdTokenVerifier,
        val apple: ProviderIdTokenVerifier,
    )

    suspend fun signup(req: SignupRequest): SignupResult {
        val type =
            when (req.provider) {
                "google" -> IdentifierType.GOOGLE
                "apple" -> IdentifierType.APPLE
                else -> throw InvalidRequestException("provider must be 'google' or 'apple'")
            }
        val verifier =
            when (type) {
                IdentifierType.GOOGLE -> providers.google
                IdentifierType.APPLE -> providers.apple
            }

        val verified = verifier.verify(req.idToken) // throws InvalidIdTokenException → 401
        val subHash = sha256Hex(verified.sub)

        // (1) Pre-check: previously-rejected identifier → 403 before DOB parse.
        if (rejected.exists(subHash, type)) {
            logBlocked("rejected_precheck", subHash)
            throw UserBlockedException()
        }

        // (2) Parse + validate DOB.
        val dob =
            try {
                LocalDate.parse(req.dateOfBirth)
            } catch (_: DateTimeParseException) {
                throw InvalidRequestException("date_of_birth must be ISO-8601 (YYYY-MM-DD)")
            }
        if (!isAtLeast18(dob)) {
            dataSource.connection.use { conn ->
                rejected.insert(conn, subHash, type, RejectedReason.AGE_UNDER_18)
            }
            logBlocked("under_age", subHash)
            throw UserBlockedException()
        }

        // (3) User-exists collision.
        dataSource.connection.use { conn ->
            if (users.existsByProviderHash(conn, subHash, type)) {
                throw UserExistsException()
            }
        }

        // (4) Derive invite_code_prefix outside the user-insert tx.
        val newUserId = UUID.randomUUID()
        val invitePrefix = inviteDeriver.deriveWithRetry(newUserId) { users.existsByInviteCodePrefix(it) }

        // (5) Atomic user insert with generated username.
        val (googleHash, appleHash) =
            when (type) {
                IdentifierType.GOOGLE -> subHash to null
                IdentifierType.APPLE -> null to subHash
            }
        val generated =
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val result =
                        usernameGenerator.generateAndInsert(
                            conn,
                            UsernameGenerator.BaseRow(
                                id = newUserId,
                                dateOfBirth = dob,
                                googleIdHash = googleHash,
                                appleIdHash = appleHash,
                                inviteCodePrefix = invitePrefix,
                                deviceFingerprintHash = req.deviceFingerprintHash,
                            ),
                        )
                    conn.commit()
                    result
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }

        // (6) Issue access + refresh. Separate transaction; on DB flake caller retries
        //     /signup (→ 409 user_exists) and then /signin to recover. See design.md.
        val access = jwtIssuer.issueAccessToken(generated.userId, tokenVersion = 0)
        val refresh = refreshTokens.issue(generated.userId, req.deviceFingerprintHash)
        return SignupResult(
            userId = generated.userId,
            username = generated.username,
            accessToken = access,
            refreshToken = refresh.rawToken,
        )
    }

    private fun isAtLeast18(dob: LocalDate): Boolean {
        val today = LocalDate.now(clock)
        return Period.between(dob, today).years >= 18
    }

    private fun logBlocked(
        branch: String,
        identifierHash: String,
    ) {
        // Full hash is the only signal we ever have for audit; it is non-reversible,
        // and the caller already has it (they submitted the id_token).
        log.warn("signup.blocked branch={} identifier_hash={}", branch, identifierHash.take(16) + "…")
    }

    companion object {
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

data class SignupRequest(
    val provider: String,
    val idToken: String,
    val dateOfBirth: String,
    val deviceFingerprintHash: String? = null,
)

data class SignupResult(
    val userId: UUID,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
)

class UserBlockedException : RuntimeException("signup rejected")

class UserExistsException : RuntimeException("user already exists for provider identity")

class InvalidRequestException(message: String) : RuntimeException(message)
