package id.nearyou.app.auth.loginhistory

import java.util.UUID

/** One captured login-history write (the in-memory fake's record of an `insert`). */
data class RecordedLoginEvent(
    val userId: UUID,
    val eventType: LoginEventType,
    val ip: String?,
    val deviceFingerprintHash: String?,
    val identifierHash: String?,
)

/**
 * In-memory [LoginEventRepository] fake for route/recorder tests — mirrors the
 * `InMemoryUsers` / `InMemoryRefreshTokens` precedent. When [failing] is true the
 * `insert` throws, to exercise the recorder's fail-soft contract.
 */
class InMemoryLoginEvents(
    private val failing: Boolean = false,
) : LoginEventRepository {
    val recorded = mutableListOf<RecordedLoginEvent>()

    override suspend fun insert(
        userId: UUID,
        eventType: LoginEventType,
        ip: String?,
        deviceFingerprintHash: String?,
        identifierHash: String?,
    ) {
        if (failing) error("simulated login_events insert failure")
        recorded += RecordedLoginEvent(userId, eventType, ip, deviceFingerprintHash, identifierHash)
    }
}
