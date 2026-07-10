package id.nearyou.app.infra.repo

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UserRow(
    val id: UUID,
    val username: String,
    val displayName: String,
    val email: String?,
    val googleIdHash: String?,
    val appleIdHash: String?,
    val appleRelayEmail: Boolean,
    val isShadowBanned: Boolean,
    val isBanned: Boolean,
    val suspendedUntil: Instant?,
    val tokenVersion: Int,
    val deletedAt: Instant?,
    /**
     * V2 schema: VARCHAR(32) NOT NULL DEFAULT 'free' with CHECK constraint
     * IN ('free', 'premium_active', 'premium_billing_retry'). Loaded at auth time
     * so handlers (notably the like rate-limit gate) can read tier from the
     * principal without issuing a fresh DB SELECT — see
     * `openspec/changes/like-rate-limit/specs/post-likes/spec.md` § "Read-site
     * constraint" for why this MUST be on the auth principal.
     */
    val subscriptionStatus: String = "free",
    /**
     * V2 schema: nullable TIMESTAMPTZ. NULL until the user's first Premium
     * username change; the `premium-username-customization` 30-day cooldown gate
     * reads it. Defaulted so existing UserRow construction sites are unaffected.
     */
    val usernameLastChangedAt: Instant? = null,
    /**
     * V25 schema: BOOLEAN NOT NULL DEFAULT FALSE. The Premium "Hide Distance" preference
     * (`hide-distance` capability). Loaded at auth time — like [subscriptionStatus] — so the Nearby
     * read path can evaluate the symmetric viewer-side suppression from the principal WITHOUT a
     * per-request `users` SELECT (the `timeline-read-rate-limit` "zero users SELECTs in the timeline
     * handler" invariant). Defaulted so existing UserRow construction sites are unaffected.
     */
    val hideDistanceOptIn: Boolean = false,
)

/**
 * NOT NULL input needed to insert a new `users` row at signup.
 * Zero-valued defaults in V2 (token_version, analytics_consent, etc.)
 * are left to the DB; anything that might eventually need to be set at
 * signup time (display_name, invite_code_prefix) belongs here explicitly.
 */
data class NewUserRow(
    val id: UUID,
    val username: String,
    val displayName: String,
    val dateOfBirth: LocalDate,
    val googleIdHash: String?,
    val appleIdHash: String?,
    val inviteCodePrefix: String,
    val deviceFingerprintHash: String?,
)

/**
 * Minimal live-inviter projection for referral ticket creation, resolved from an
 * invite code (== `invite_code_prefix`, exact equality). Only the fields the
 * referral eligibility gates need; restricted to live rows (`deleted_at IS NULL`).
 */
data class InviterRow(
    val id: UUID,
    val isBanned: Boolean,
    val createdAt: Instant,
    val deviceFingerprintHash: String?,
)

interface UserRepository {
    fun findById(id: UUID): UserRow?

    fun findByGoogleIdHash(hash: String): UserRow?

    fun findByAppleIdHash(hash: String): UserRow?

    fun incrementTokenVersion(id: UUID): Int

    /** In-transaction variant (logout-all runs this atomically with the refresh-token + FCM deletes). */
    fun incrementTokenVersion(
        conn: Connection,
        id: UUID,
    ): Int

    fun setAppleRelayEmail(
        appleIdHash: String,
        enabled: Boolean,
    ): Int

    /** In-transaction provider-hash collision check (used by signup). */
    fun existsByProviderHash(
        conn: Connection,
        hash: String,
        type: IdentifierType,
    ): Boolean

    /** Pre-transaction invite-code-prefix collision probe. */
    fun existsByInviteCodePrefix(prefix: String): Boolean

    /**
     * Resolve an invite code (== `users.invite_code_prefix`, exact equality) to
     * its live inviter for referral ticket creation. Returns null when no live
     * user (`deleted_at IS NULL`) carries that prefix. O(1) on the
     * `invite_code_prefix` UNIQUE index. Reads raw `users` — covered by the
     * class-level `@AllowMissingBlockJoin` on `JdbcUserRepository` (auth-plane
     * invite-code read; no viewer-block axis at pre-auth signup).
     */
    fun findInviterByInviteCodePrefix(prefix: String): InviterRow?

    /**
     * Insert a new `users` row inside the caller's transaction.
     * Throws `java.sql.SQLException` with SQLState 23505 on unique-violation
     * so the caller can catch and retry username generation.
     * Returns the row id (same as [NewUserRow.id]).
     */
    fun create(
        conn: Connection,
        row: NewUserRow,
    ): UUID

    /**
     * In-transaction `SELECT … FROM users WHERE id = ? FOR UPDATE` — locks the
     * row so the username-change re-validation + write happen atomically against
     * a concurrent change. Used by `premium-username-customization`.
     */
    fun findByIdForUpdate(
        conn: Connection,
        id: UUID,
    ): UserRow?

    /**
     * In-transaction case-insensitive existence check against the current
     * `users.username` (uses the `users_username_lower_idx` LOWER index). Caller
     * passes the already-lowercased candidate. Used by the username-change
     * collision re-check under the row lock.
     */
    fun usernameExists(
        conn: Connection,
        lowercaseCandidate: String,
    ): Boolean

    /**
     * In-transaction username write: `UPDATE users SET username = ?,
     * username_last_changed_at = NOW() WHERE id = ?`. The single Premium
     * customization writer reserved by critical invariant #7 (the allowlist
     * annotation lives on the impl). Caller owns the transaction boundary.
     */
    fun updateUsername(
        conn: Connection,
        id: UUID,
        newUsername: String,
    )
}
