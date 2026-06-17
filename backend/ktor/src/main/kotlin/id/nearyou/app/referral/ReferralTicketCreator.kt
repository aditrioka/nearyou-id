package id.nearyou.app.referral

import java.util.UUID

/**
 * Narrow seam `SignupService` depends on for best-effort referral ticket creation
 * after a user is created. Implemented by [ReferralService]; cross-feature access
 * via this interface (docs/11 §3.1) keeps signup thin and gives the deferred
 * activity-gate worker + entitlement-grant change a home to extend.
 *
 * Contract: implementations MUST NOT throw and MUST NOT alter the signup outcome.
 * Every failure — missing/blank/unresolvable code, ineligible inviter, anti-abuse
 * rejection, duplicate invitee, or unexpected error — is swallowed and audit-logged
 * server-side only (anti-probing). Signup still returns 201 regardless.
 */
interface ReferralTicketCreator {
    suspend fun createTicketIfInvited(
        inviteCode: String?,
        inviteeUserId: UUID,
        inviteeDeviceFingerprintHash: String?,
    )
}

/**
 * No-op binding for construction sites that do not wire referral (e.g. existing
 * signup tests that predate this capability). Production wires [ReferralService].
 */
object NoopReferralTicketCreator : ReferralTicketCreator {
    override suspend fun createTicketIfInvited(
        inviteCode: String?,
        inviteeUserId: UUID,
        inviteeDeviceFingerprintHash: String?,
    ) = Unit
}
