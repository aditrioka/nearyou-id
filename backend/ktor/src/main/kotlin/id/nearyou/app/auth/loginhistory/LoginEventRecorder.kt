package id.nearyou.app.auth.loginhistory

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Records a `login_events` row for an authenticated sign-in / refresh, best-effort.
 *
 * Login history is essential security / anti-abuse data (the source for the
 * referral activity-gate's engagement + device-fingerprint anti-collision legs)
 * collected under a legitimate-interest basis — it is **not** gated by the
 * `analytics_consent` toggle (the recorder never reads it). Writing is a
 * non-critical side effect: a `login_events` insert failure MUST NOT break
 * sign-in or refresh, so any exception is swallowed and logged and the auth
 * response is unaffected (fail-soft — the `NoOpReferralEntitlementGranter`
 * precedent). A dropped write only ever under-counts an invitee's engagement
 * legs (a false `pending`, re-evaluated next run), never a false grant.
 *
 * Invoked from the route layer (`AuthRoutes`), where `call.clientIp` (the
 * sanctioned client-IP accessor — never raw `X-Forwarded-For`) and the request
 * body live; `RefreshTokenService` is deliberately untouched.
 */
class LoginEventRecorder(
    private val repository: LoginEventRepository,
) {
    suspend fun record(
        userId: UUID,
        eventType: LoginEventType,
        ip: String?,
        deviceFingerprintHash: String?,
        identifierHash: String?,
    ) {
        try {
            repository.insert(userId, eventType, ip, deviceFingerprintHash, identifierHash)
        } catch (e: Exception) {
            log.warn("login_events insert failed (fail-soft) user={} type={}", userId, eventType.wire, e)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(LoginEventRecorder::class.java)
    }
}
